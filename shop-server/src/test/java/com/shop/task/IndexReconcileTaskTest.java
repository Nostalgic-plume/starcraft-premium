package com.shop.task;

import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import com.shop.rag.config.IndexReconcileProperties;
import com.shop.rag.index.ProductIndexSyncService;
import com.shop.rag.index.QdrantCollectionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 索引对账测试（纯 Mockito，不依赖 MySQL / Qdrant，CI 可跑）
 * <p>
 * 锁定四条契约（见 {@code docs/rag-index-reconcile-design.md}）：
 * <ol>
 *   <li><b>三层差异</b>：漏建（MySQL 有 / Qdrant 无）、多余（Qdrant 有 / MySQL 无）、
 *       陈旧（两边都有但 updateTime 不一致，含 payload 缺字段的旧索引）；</li>
 *   <li><b>阈值保护</b>：差异比例超限时只告警、不产生任何修复调用；</li>
 *   <li><b>失败即跳过</b>：Qdrant 查询失败（null）时不做任何修复，避免把「查不到」误判为「全部缺失」；</li>
 *   <li><b>一致时不动作</b>：无差异时不触发任何索引写入。</li>
 * </ol>
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IndexReconcileTask - 向量索引对账")
class IndexReconcileTaskTest {

    private static final long T1 = 1_728_000_000_000L;
    private static final long T2 = 1_728_000_100_000L;

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductIndexSyncService productIndexSyncService;
    @Mock
    private QdrantCollectionHelper qdrantCollectionHelper;

    private IndexReconcileProperties properties;
    private IndexReconcileTask task;

    @BeforeEach
    void setUp() {
        properties = new IndexReconcileProperties();
        // 修复类用例放开阈值：小数据集下 1 条差异就会超过默认 20%，
        // 阈值保护本体会单独用例覆盖
        properties.setMaxAutoFixRatio(1.0);
        task = new IndexReconcileTask(productMapper, productIndexSyncService, qdrantCollectionHelper, properties);
    }

    /** 构造商品：updateTime 按系统时区还原，与 ProductIndexer.toEpochMillis 保持同一转换 */
    private static ProductDO product(long id, long epochMillis) {
        ProductDO product = new ProductDO();
        product.setId(id);
        product.setUpdateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
        return product;
    }

    private void stubMySql(List<ProductDO> products) {
        when(productMapper.selectList(any())).thenReturn(products);
    }

    private void stubQdrant(Map<Long, Long> points) {
        when(qdrantCollectionHelper.scrollAllProducts(anyInt())).thenReturn(points);
    }

    @Test
    @DisplayName("完全一致：不触发任何索引写入")
    void consistentStateShouldDoNothing() {
        stubMySql(List.of(product(1L, T1), product(2L, T2)));
        stubQdrant(Map.of(1L, T1, 2L, T2));

        task.reconcile();

        verifyNoInteractions(productIndexSyncService);
    }

    @Test
    @DisplayName("漏建：MySQL 有、Qdrant 无 → 补建")
    void missingPointShouldBeIndexed() {
        stubMySql(List.of(product(1L, T1), product(2L, T2)));
        stubQdrant(Map.of(1L, T1));
        when(productMapper.selectById(2L)).thenReturn(product(2L, T2));

        task.reconcile();

        verify(productIndexSyncService).addOrUpdate(any(ProductDO.class));
    }

    @Test
    @DisplayName("多余：Qdrant 有、MySQL 无（已下架/删除）→ 删除")
    void extraPointShouldBeRemoved() {
        stubMySql(List.of(product(1L, T1)));
        stubQdrant(Map.of(1L, T1, 99L, T2));

        task.reconcile();

        verify(productIndexSyncService).remove(99L);
        verify(productIndexSyncService, never()).addOrUpdate(any(ProductDO.class));
    }

    @Test
    @DisplayName("陈旧：updateTime 不一致 → 重建")
    void staleContentShouldBeReindexed() {
        stubMySql(List.of(product(1L, T2)));
        stubQdrant(Map.of(1L, T1));
        when(productMapper.selectById(1L)).thenReturn(product(1L, T2));

        task.reconcile();

        verify(productIndexSyncService).addOrUpdate(any(ProductDO.class));
    }

    @Test
    @DisplayName("旧索引缺 updateTime：判为陈旧并重建（payload 升级路径）")
    void missingUpdateTimeInPayloadShouldBeTreatedAsStale() {
        stubMySql(List.of(product(1L, T1)));
        Map<Long, Long> actual = new HashMap<>();
        actual.put(1L, null);  // payload 里没有 updateTime
        stubQdrant(actual);
        when(productMapper.selectById(1L)).thenReturn(product(1L, T1));

        task.reconcile();

        verify(productIndexSyncService).addOrUpdate(any(ProductDO.class));
    }

    @Test
    @DisplayName("差异超阈值：只告警，不产生任何修复调用")
    void differenceOverThresholdShouldNotBeFixed() {
        properties.setMaxAutoFixRatio(0.2);
        // 10 条应有、0 条现存 → 差异 100%，必然超过 20%
        stubMySql(List.of(
                product(1L, T1), product(2L, T1), product(3L, T1), product(4L, T1), product(5L, T1),
                product(6L, T1), product(7L, T1), product(8L, T1), product(9L, T1), product(10L, T1)));
        stubQdrant(Map.of());

        task.reconcile();

        verifyNoInteractions(productIndexSyncService);
    }

    @Test
    @DisplayName("Qdrant 查询失败（null）：跳过，不把「查不到」当成「全部缺失」")
    void qdrantFailureShouldSkipReconcile() {
        stubMySql(List.of(product(1L, T1)));
        when(qdrantCollectionHelper.scrollAllProducts(anyInt())).thenReturn(null);

        task.reconcile();

        verifyNoInteractions(productIndexSyncService);
        verify(productMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("开关关闭：完全不执行（不查库、不查 Qdrant）")
    void disabledShouldSkipEverything() {
        properties.setEnabled(false);

        task.reconcile();

        verifyNoInteractions(productMapper, qdrantCollectionHelper, productIndexSyncService);
    }
}
