package com.shop.rag;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ProductDO;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 商品索引器
 * 启动时全量构建 Qdrant 向量索引，支持增量增删改
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexer {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final OllamaEmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;

    /** productId → Qdrant pointId 映射，用于删除时定位 */
    private final ConcurrentHashMap<Long, String> pointIdMap = new ConcurrentHashMap<>();

    /** 索引统计 */
    private volatile int indexedCount = 0;
    private volatile long lastRebuildTime = 0;

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildAll() {
        log.info("===== 开始全量重建商品向量索引 =====");
        long start = System.currentTimeMillis();
        pointIdMap.clear();

        // 查所有上架商品
        List<ProductDO> products = productMapper.selectList(
                new LambdaQueryWrapper<ProductDO>()
                        .eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF)
                        .orderByDesc(ProductDO::getCreateTime));

        if (CollUtil.isEmpty(products)) {
            log.info("没有上架商品，跳过索引构建");
            indexedCount = 0;
            lastRebuildTime = System.currentTimeMillis();
            return;
        }

        // 批量构建 TextSegment（携元数据）
        List<TextSegment> segments = products.stream().map(this::buildTextSegment).collect(Collectors.toList());

        // 批量生成 embedding
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // 批量存入 Qdrant，记录返回的 pointId
        List<String> pointIds = embeddingStore.addAll(embeddings, segments);
        for (int i = 0; i < products.size() && i < pointIds.size(); i++) {
            pointIdMap.put(products.get(i).getId(), pointIds.get(i));
        }

        indexedCount = products.size();
        lastRebuildTime = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - start;
        log.info("===== 商品向量索引完成: {} 条, 耗时 {}ms =====", indexedCount, elapsed);
    }

    /**
     * 新增或更新商品索引
     */
    public void addOrUpdate(ProductDO product) {
        if (product == null || product.getStatus() == null
                || product.getStatus() != ProductConstants.STATUS_ON_SHELF) {
            return;
        }

        // 移除旧索引
        remove(product.getId());

        // 构建 embedding 并存储
        TextSegment segment = buildTextSegment(product);
        Embedding embedding = embeddingModel.embed(segment.text()).content();
        String pointId = embeddingStore.add(embedding, segment);
        pointIdMap.put(product.getId(), pointId);
        indexedCount++;
        log.debug("商品索引更新完成, productId: {}", product.getId());
    }

    /**
     * 删除商品索引
     */
    public void remove(Long productId) {
        String pointId = pointIdMap.remove(productId);
        if (pointId != null) {
            embeddingStore.removeAll(Collections.singletonList(pointId));
            indexedCount = Math.max(0, indexedCount - 1);
            log.debug("商品索引已删除, productId: {}", productId);
        }
    }

    /** 索引条数 */
    public int getIndexedCount() {
        return indexedCount;
    }

    /** 最后重建时间 */
    public long getLastRebuildTime() {
        return lastRebuildTime;
    }

    /**
     * 构建索引文本（供 embedding 用）
     */
    private String buildIndexText(ProductDO product) {
        CategoryDO category = categoryMapper.selectById(product.getCategoryId());
        String categoryName = category != null ? category.getName() : "";

        return String.format("名称：%s\n描述：%s\n品牌：%s\n分类：%s\n详情：%s",
                safe(product.getName()),
                safe(product.getDescription()),
                safe(product.getBrand()),
                categoryName,
                stripHtml(safe(product.getDetail())));
    }

    /**
     * 构建带元数据的 TextSegment
     */
    private TextSegment buildTextSegment(ProductDO product) {
        return TextSegment.from(buildIndexText(product),
                Metadata.from(toMetadataMap(product)));
    }

    private Map<String, Object> toMetadataMap(ProductDO product) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("productId", product.getId());
        meta.put("name", product.getName());
        meta.put("firstCategoryId", product.getFirstCategoryId());
        meta.put("shopId", product.getShopId());
        meta.put("mainImage", product.getMainImage());
        return meta;
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    /** 简单去除 HTML 标签 */
    private static String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
