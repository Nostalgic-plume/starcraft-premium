package com.shop.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import com.shop.rag.config.IndexReconcileProperties;
import com.shop.rag.index.ProductIndexSyncService;
import com.shop.rag.index.ProductIndexer;
import com.shop.rag.index.QdrantCollectionHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 向量索引对账任务
 * <p>
 * 解决「索引更新失败只 WARN → 商品永久搜不到 / 搜到旧内容，且无人知晓」的缺口。
 * 与检索侧的候选池清洗（实时过滤幽灵索引）是**两层不同防护**：
 * <ul>
 *   <li>检索侧：保证「当前这一次查询」不返回不可售商品；</li>
 *   <li>对账侧：保证「索引数据本身」最终与 MySQL 一致。</li>
 * </ul>
 * 设计见 {@code docs/rag-index-reconcile-design.md}。
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexReconcileTask {

    private final ProductMapper productMapper;
    /** 索引同步门面：对账修复同时覆盖 Qdrant 与 ES（功能编号 001） */
    private final ProductIndexSyncService productIndexSyncService;
    private final QdrantCollectionHelper qdrantCollectionHelper;
    private final IndexReconcileProperties properties;

    /**
     * 执行一轮对账（集合一致性 + 内容陈旧检测）
     * <p>
     * 差异超过阈值时**只告警不修复** —— 大规模差异通常意味着环境异常，
     * 自动修复可能演变成大规模误删/误建。
     */
    @Scheduled(fixedRateString = "${rag.reconcile.fixed-rate-ms:1800000}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            // 1. MySQL 应索引集：id → updateTime(epoch millis)
            List<ProductDO> products = productMapper.selectList(
                    new LambdaQueryWrapper<ProductDO>()
                            .eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF)
                            .select(ProductDO::getId, ProductDO::getUpdateTime));
            Map<Long, Long> expected = new HashMap<>();
            for (ProductDO product : products) {
                if (product != null && product.getId() != null) {
                    expected.put(product.getId(), ProductIndexer.toEpochMillis(product.getUpdateTime()));
                }
            }

            // 2. Qdrant 现存集（null = 查询失败，与「空集合」区分）
            Map<Long, Long> actual = qdrantCollectionHelper.scrollAllProducts(properties.getScrollBatchSize());
            if (actual == null) {
                log.warn("[索引对账] Qdrant 不可用，本轮跳过");
                return;
            }

            // 3. 差异计算
            Set<Long> missing = new HashSet<>(expected.keySet());
            missing.removeAll(actual.keySet());

            Set<Long> extra = new HashSet<>(actual.keySet());
            extra.removeAll(expected.keySet());

            Set<Long> stale = new HashSet<>();
            for (Long productId : expected.keySet()) {
                if (!actual.containsKey(productId)) {
                    continue;
                }
                // payload 缺 updateTime（旧索引）也会走到这里：null != 毫秒值 → 判为陈旧 → 重建
                if (!Objects.equals(expected.get(productId), actual.get(productId))) {
                    stale.add(productId);
                }
            }

            int difference = missing.size() + extra.size() + stale.size();
            log.info("[索引对账] 应索引{} 现存{} 漏建{} 多余{} 陈旧{}",
                    expected.size(), actual.size(), missing.size(), extra.size(), stale.size());

            // 4. 阈值保护：修复必须在告警判断之后
            int total = Math.max(expected.size(), actual.size());
            if (total > 0 && difference > total * properties.getMaxAutoFixRatio()) {
                log.warn("[索引对账] 差异 {} 超过阈值 {}%（基数 {}），本轮不自动修复。"
                                + "若刚执行过索引重建属正常现象（重建会先删集合）；否则请人工排查",
                        difference, properties.getMaxAutoFixRatio() * 100, total);
                return;
            }

            // 5. 修复：单个失败只计数，不中断整轮
            if (difference == 0) {
                return;
            }
            int fixed = 0;
            int failed = 0;
            for (Long productId : missing) {
                if (reindex(productId)) fixed++; else failed++;
            }
            for (Long productId : stale) {
                if (reindex(productId)) fixed++; else failed++;
            }
            for (Long productId : extra) {
                try {
                    productIndexSyncService.remove(productId);
                    fixed++;
                } catch (Exception e) {
                    log.warn("[索引对账] 删除多余索引失败 productId={}: {}", productId, e.getMessage());
                    failed++;
                }
            }
            log.info("[索引对账] 修复完成: 成功{} 失败{}", fixed, failed);

        } catch (Exception e) {
            // 对账失败不影响检索与主流程，下轮重试
            log.warn("[索引对账] 执行异常，本轮跳过: {}", e.getMessage(), e);
        }
    }

    /**
     * 按 ID 重建单个商品的索引
     *
     * @return 成功返回 true
     */
    private boolean reindex(Long productId) {
        try {
            ProductDO product = productMapper.selectById(productId);
            if (product == null) {
                // 查不到 = 已被删除 / 对账期间下架 —— 按「多余」处理，幂等
                productIndexSyncService.remove(productId);
                return true;
            }
            productIndexSyncService.addOrUpdate(product);
            return true;
        } catch (Exception e) {
            log.warn("[索引对账] 重建商品索引失败 productId={}: {}", productId, e.getMessage());
            return false;
        }
    }
}
