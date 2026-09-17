package com.shop.rag;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.entity.SkuDO;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品索引器
 * 启动时全量构建 Qdrant 向量索引，支持增量增删改
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexer {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final SkuMapper skuMapper;
    private final OpenAiEmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;
    private final QdrantCollectionHelper qdrantCollectionHelper;

    /** 最后一次全量重建时间（毫秒时间戳，0 表示未重建） */
    private volatile long lastRebuildTime = 0;

    /**
     * 计算商品的确定性 Qdrant point id
     * <p>
     * 直接以 productId 作为点 id：写入天然具备 upsert 语义（同 id 覆盖），删除不再依赖任何进程内状态。
     * LangChain4j 的 QdrantEmbeddingStore.toPointId 会把纯数字字符串解析为 Qdrant 的 uint64 点 id。
     *
     * @param productId 商品 ID（不可为 null）
     * @return Qdrant 点 id
     */
    private static String pointIdOf(Long productId) {
        return String.valueOf(productId);
    }

    /** 详情文本最大长度（embedding 单条 token 上限防护，超长截断） */
    private static final int MAX_DETAIL_LENGTH = 500;

    /**
     * 统计 Qdrant 集合中已索引的商品数量
     * <p>
     * 统一委托 {@link QdrantCollectionHelper} 访问 Qdrant（集合名取自配置），避免在此硬编码集合名；
     * 同时用于启动时判断是否需要全量重建。
     *
     * @return 集合内 point 数量；Qdrant 不可用时返回 0
     */
    private int countIndexedPoints() {
        return (int) qdrantCollectionHelper.countPoints();
    }

    /**
     * 清理并重建 Qdrant 集合（委托 QdrantCollectionHelper 统一管理）
     */
    private void recreateCollection() {
        qdrantCollectionHelper.recreateCollection();
    }

    /**
     * 应用启动就绪时自动触发全量重建（Qdrant 已有数据则跳过，避免重复构建）
     * <p>
     * 由 {@link ApplicationReadyEvent} 事件驱动，托管于 Spring 容器生命周期。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildAll() {
        // 检查 Qdrant 是否已有数据，有则跳过全量重建
        if (countIndexedPoints() > 0) {
            log.info("Qdrant 集合已有向量数据，跳过索引重建");
            return;
        }
        doRebuild();
    }

    /**
     * 强制全量重建（不检查 Qdrant 是否已有数据）
     * 供管理后台手动触发调用
     */
    public void forceRebuildAll() {
        doRebuild();
    }

    /**
     * 全量重建共享逻辑
     */
    private void doRebuild() {
        log.info("===== 开始全量重建商品向量索引 =====");
        long start = System.currentTimeMillis();

        // 清理并重建 Qdrant 集合
        recreateCollection();

        // 查询所有上架商品
        List<ProductDO> products = productMapper.selectList(
                new LambdaQueryWrapper<ProductDO>()
                        .eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF)
                        .orderByDesc(ProductDO::getCreateTime));

        if (CollUtil.isEmpty(products)) {
            log.info("没有上架商品，跳过索引构建");
            lastRebuildTime = System.currentTimeMillis();
            return;
        }

        // 批量查询 SKU 价格
        Map<Long, BigDecimal[]> priceMap = loadPriceMap(products);

        // 批量构建 TextSegment（携元数据）
        List<TextSegment> segments = products.stream()
                .map(p -> buildTextSegment(p, priceMap.get(p.getId())))
                .collect(Collectors.toList());

        // 批量生成 embedding（分批，text-embedding-v1 单次最多 25 条）
        List<Embedding> embeddings = new ArrayList<>();
        int batchSize = 25;
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            List<Embedding> batchResult = embeddingModel.embedAll(batch).content();
            if (batchResult != null) {
                embeddings.addAll(batchResult);
            }
            log.debug("向量化进度: {}/{}", end, segments.size());
        }

        // 校验：跳过空向量（嵌入服务未就绪时可能返回 0 维向量）
        // 注意：ids / embeddings / segments 必须严格一一对应，过滤后不可再回查 products 下标
        List<String> validIds = new ArrayList<>();
        List<Embedding> validEmbeddings = new ArrayList<>();
        List<TextSegment> validSegments = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            Embedding embedding = embeddings.get(i);
            if (embedding.vector() != null && embedding.vector().length > 0) {
                validIds.add(pointIdOf(products.get(i).getId()));
                validEmbeddings.add(embedding);
                validSegments.add(segments.get(i));
            } else {
                log.warn("商品 '{}' 向量化为空，跳过索引", products.get(i).getName());
            }
        }

        if (validEmbeddings.isEmpty()) {
            log.warn("所有商品向量化结果均为空，请检查嵌入服务是否正常运行");
            lastRebuildTime = System.currentTimeMillis();
            return;
        }

        // 批量写入 Qdrant：点 id = productId，同 id 重复写入即为覆盖，无需记录返回值
        embeddingStore.addAll(validIds, validEmbeddings, validSegments);

        lastRebuildTime = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - start;
        log.info("===== 商品向量索引完成: {} 条, 耗时 {}ms =====", validIds.size(), elapsed);

        // 联动重建 MySQL FULLTEXT 索引
        rebuildFulltextIndex();
    }

    /**
     * 重建 MySQL FULLTEXT 索引
     * 通过 OPTIMIZE TABLE 重建整个表索引（含 FULLTEXT），确保索引处于最优状态
     */
    private void rebuildFulltextIndex() {
        try {
            log.info("开始重建 MySQL FULLTEXT 索引...");
            long start = System.currentTimeMillis();
            productMapper.optimizeFulltextIndex();
            long elapsed = System.currentTimeMillis() - start;
            log.info("MySQL FULLTEXT 索引重建完成, 耗时 {}ms", elapsed);
        } catch (Exception e) {
            log.warn("MySQL FULLTEXT 索引重建失败: {}", e.getMessage());
        }
    }

    /**
     * 检查 FULLTEXT 索引是否正常工作
     *
     * @return 索引状态信息
     */
    public Map<String, Object> checkFulltextIndex() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 执行测试查询
            int matchCount = productMapper.testFulltextIndex("手机");
            result.put("healthy", matchCount >= 0);  // 空表也算正常
            result.put("testMatchCount", matchCount);
            result.put("message", "FULLTEXT 索引正常");
        } catch (Exception e) {
            result.put("healthy", false);
            result.put("message", "FULLTEXT 索引异常: " + e.getMessage());
            result.put("testMatchCount", -1);
        }
        return result;
    }

    /**
     * 新增或更新商品索引
     */
    public void addOrUpdate(ProductDO product) {
        if (product == null || product.getStatus() == null
                || product.getStatus() != ProductConstants.STATUS_ON_SHELF) {
            return;
        }

        // 查询该商品 SKU 价格
        BigDecimal[] prices = loadProductPrice(product.getId());

        // 构建 embedding 并以确定性 point id 写入：同 id 覆盖，天然幂等，无需先删旧点
        TextSegment segment = buildTextSegment(product, prices);
        Embedding embedding = embeddingModel.embed(segment.text()).content();
        if (embedding.vector() == null || embedding.vector().length == 0) {
            log.warn("商品 '{}' 向量化为空，跳过索引更新", product.getName());
            return;
        }
        embeddingStore.addAll(List.of(pointIdOf(product.getId())), List.of(embedding), List.of(segment));
        log.debug("商品索引更新完成, productId: {}", product.getId());
    }

    /**
     * 删除商品索引
     * <p>
     * 点 id 由 productId 决定，无需任何进程内映射，进程重启后依然能准确删除，
     * 避免下架商品残留为可被检索到的幽灵索引。
     *
     * @param productId 商品 ID
     */
    public void remove(Long productId) {
        if (productId == null) {
            return;
        }
        embeddingStore.remove(pointIdOf(productId));
        log.debug("商品索引已删除, productId: {}", productId);
    }

    /** 索引条数（实时读取 Qdrant 集合内 point 数量，避免进程内计数在重启后漂移） */
    public int getIndexedCount() {
        return countIndexedPoints();
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
                truncateDetail(stripHtml(safe(product.getDetail()))));
    }

    /**
     * 构建带元数据的 TextSegment
     */
    private TextSegment buildTextSegment(ProductDO product, BigDecimal[] prices) {
        return TextSegment.from(buildIndexText(product),
                Metadata.from(toMetadataMap(product, prices)));
    }

    /**
     * 构建商品元数据 Map（检索过滤与展示字段：productId/名称/一级分类/商家ID/主图/价格区间）
     *
     * <p>
     * 空值字段一律跳过：LangChain4j 的 Metadata 不接受 null，任一字段为 null 都会抛
     * IllegalArgumentException —— 在增量更新时表现为该商品静默丢索引，在全量重建时表现为整批失败。
     * 缺失字段不参与检索过滤（passMetadataFilter 对缺失键按「不过滤」处理），也不影响展示。
     *
     * @param product 上架商品
     * @param prices  SKU 价格区间 [minPrice, maxPrice]（可为 null，此时不写价格字段）
     * @return 商品元数据 Map
     */
    private Map<String, Object> toMetadataMap(ProductDO product, BigDecimal[] prices) {
        Map<String, Object> meta = new HashMap<>();
        putIfNotNull(meta, "productId", product.getId());
        putIfNotNull(meta, "name", product.getName());
        putIfNotNull(meta, "firstCategoryId", product.getFirstCategoryId());
        putIfNotNull(meta, "shopId", product.getShopId());
        putIfNotNull(meta, "mainImage", product.getMainImage());
        if (prices != null && prices.length == 2) {
            putIfNotNull(meta, "minPrice", prices[0] == null ? null : prices[0].doubleValue());
            putIfNotNull(meta, "maxPrice", prices[1] == null ? null : prices[1].doubleValue());
        }
        return meta;
    }

    /** 仅在值非空时写入元数据（LangChain4j Metadata 不接受 null 值） */
    private static void putIfNotNull(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, value);
        }
    }

    /**
     * 批量查询商品 SKU 价格，返回 productId → [minPrice, maxPrice]
     */
    private Map<Long, BigDecimal[]> loadPriceMap(List<ProductDO> products) {
        Map<Long, BigDecimal[]> priceMap = new HashMap<>();
        if (CollUtil.isEmpty(products)) {
            return priceMap;
        }

        List<Long> productIds = products.stream().map(ProductDO::getId).collect(Collectors.toList());
        List<SkuDO> skus = skuMapper.selectList(
                new LambdaQueryWrapper<SkuDO>()
                        .in(SkuDO::getProductId, productIds)
                        .select(SkuDO::getProductId, SkuDO::getPrice));

        if (CollUtil.isNotEmpty(skus)) {
            Map<Long, List<SkuDO>> grouped = skus.stream()
                    .collect(Collectors.groupingBy(SkuDO::getProductId));
            for (Map.Entry<Long, List<SkuDO>> entry : grouped.entrySet()) {
                BigDecimal min = entry.getValue().stream()
                        .map(SkuDO::getPrice)
                        .filter(Objects::nonNull)
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
                BigDecimal max = entry.getValue().stream()
                        .map(SkuDO::getPrice)
                        .filter(Objects::nonNull)
                        .max(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
                priceMap.put(entry.getKey(), new BigDecimal[]{min, max});
            }
        }
        return priceMap;
    }

    /**
     * 查询单个商品 SKU 价格范围（委托批量查询）
     */
    private BigDecimal[] loadProductPrice(Long productId) {
        ProductDO stub = new ProductDO();
        stub.setId(productId);
        return loadPriceMap(List.of(stub)).get(productId);
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

    /** 详情文本截断（防 embedding 单条 token 超限，保留开头关键信息） */
    private static String truncateDetail(String text) {
        if (text == null || text.length() <= MAX_DETAIL_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_DETAIL_LENGTH) + "...";
    }
}
