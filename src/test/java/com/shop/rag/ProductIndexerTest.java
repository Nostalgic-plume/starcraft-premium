package com.shop.rag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.entity.ProductDO;
import com.shop.model.entity.SkuDO;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductIndexer 单元测试（不依赖 Qdrant / MySQL / 嵌入服务，CI 可跑）
 * <p>
 * 回归两处历史缺陷：
 * 1）pointId 曾保存在进程内 Map，进程重启后失效 → 删除失效（下架商品残留为幽灵索引）、编辑商品重复累积向量；
 * 2）全量重建时过滤空向量后仍用 products 下标回查 → 点 id 与向量、文本错位。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductIndexer - 确定性 pointId 与重建对齐")
class ProductIndexerTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private SkuMapper skuMapper;
    @Mock
    private OpenAiEmbeddingModel embeddingModel;
    @Mock
    private QdrantEmbeddingStore embeddingStore;
    @Mock
    private QdrantCollectionHelper qdrantCollectionHelper;

    private ProductIndexer indexer;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 无 Spring 上下文：LambdaQueryWrapper 构造时立即解析列名，
        // 需手动初始化 MyBatis-Plus TableInfo 缓存（幂等，重复调用直接返回已有缓存）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SkuDO.class);
        TableInfoHelper.initTableInfo(assistant, ProductDO.class);

        indexer = new ProductIndexer(productMapper, categoryMapper, new SkuPriceResolver(skuMapper),
                embeddingModel, embeddingStore, qdrantCollectionHelper);
    }

    @Test
    @DisplayName("编辑同一商品两次：始终以 productId 作为点 id 覆盖写入，不产生重复向量")
    void addOrUpdate_shouldUpsertWithProductIdAsPointId() {
        ProductDO product = onShelfProduct(7L, "测试商品七");
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(vector(0.1f, 0.2f)));

        indexer.addOrUpdate(product);
        indexer.addOrUpdate(product);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore, times(2)).addAll(idsCaptor.capture(), anyList(), anyList());

        assertEquals(List.of("7"), idsCaptor.getAllValues().get(0));
        assertEquals(List.of("7"), idsCaptor.getAllValues().get(1));
        verify(embeddingStore, never()).add(any(Embedding.class), any(TextSegment.class));
    }

    @Test
    @DisplayName("下架商品：直接按 productId 删除点，无需进程内映射")
    void remove_shouldDeleteByProductId() {
        indexer.remove(42L);

        verify(embeddingStore).remove("42");
    }

    @Test
    @DisplayName("重启场景：新建实例（进程内状态天然为空）后，删除依然生效")
    void remove_afterRestart_shouldStillDelete() {
        ProductIndexer restarted = new ProductIndexer(productMapper, categoryMapper, new SkuPriceResolver(skuMapper),
                embeddingModel, embeddingStore, qdrantCollectionHelper);

        restarted.remove(99L);

        verify(embeddingStore).remove("99");
    }

    @Test
    @DisplayName("全量重建：跳过空向量后，点 id 与文本仍与商品一一对应，不错位")
    void rebuild_shouldKeepIdsAlignedWithSegmentsAfterSkippingEmptyVectors() {
        when(productMapper.selectList(any())).thenReturn(List.of(
                onShelfProduct(1L, "测试商品甲"),
                onShelfProduct(2L, "测试商品乙"),
                onShelfProduct(3L, "测试商品丙")));
        // 第二个商品返回 0 维空向量，应被跳过
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                vector(0.1f, 0.2f), new Embedding(new float[0]), vector(0.3f, 0.4f))));

        indexer.forceRebuildAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(idsCaptor.capture(), anyList(), segmentsCaptor.capture());

        assertEquals(List.of("1", "3"), idsCaptor.getValue());
        assertEquals(2, segmentsCaptor.getValue().size());
        assertTrue(segmentsCaptor.getValue().get(0).text().contains("测试商品甲"));
        assertTrue(segmentsCaptor.getValue().get(1).text().contains("测试商品丙"));
    }

    @Test
    @DisplayName("元数据字段为空的商品：仍能成功写入索引，不会因空值导致整批失败")
    void addOrUpdate_shouldTolerateNullMetadataFields() {
        ProductDO product = onShelfProduct(8L, "无主图商品");
        product.setMainImage(null);
        product.setFirstCategoryId(null);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(vector(0.1f, 0.2f)));

        indexer.addOrUpdate(product);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), anyList(), segmentsCaptor.capture());

        Map<String, Object> meta = segmentsCaptor.getValue().get(0).metadata().toMap();
        assertEquals(8L, meta.get("productId"));
        assertFalse(meta.containsKey("mainImage"));
        assertFalse(meta.containsKey("firstCategoryId"));
    }

    private static ProductDO onShelfProduct(Long id, String name) {
        ProductDO product = new ProductDO();
        product.setId(id);
        product.setName(name);
        product.setCategoryId(1L);
        product.setStatus(ProductConstants.STATUS_ON_SHELF);
        return product;
    }

    private static Embedding vector(float... values) {
        return new Embedding(values);
    }
}