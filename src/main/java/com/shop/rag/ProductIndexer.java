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
import java.net.HttpURLConnection;
import java.net.URI;
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
    private final SkuMapper skuMapper;
    private final OpenAiEmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;
    private final QdrantCollectionHelper qdrantCollectionHelper;

    /** productId → Qdrant pointId 映射，用于删除时定位 */
    private final ConcurrentHashMap<Long, String> pointIdMap = new ConcurrentHashMap<>();

    /** 索引统计 */
    private volatile int indexedCount = 0;
    private volatile long lastRebuildTime = 0;

    /** 详情文本最大长度（embedding 单条 token 上限防护，超长截断） */
    private static final int MAX_DETAIL_LENGTH = 500;

    /**
     * 检查 Qdrant 集合是否已有向量数据
     */
    private boolean hasExistingData() {
        try {
            URI uri = URI.create("http://localhost:6333/collections/products");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return false;
            }

            // 解析 JSON 获取 points_count
            byte[] bytes = conn.getInputStream().readAllBytes();
            conn.disconnect();
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return json.contains("\"points_count\":")
                    && !json.contains("\"points_count\":0");
        } catch (Exception e) {
            log.warn("检查 Qdrant 集合状态失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清理并重建 Qdrant 集合（委托 QdrantCollectionHelper 统一管理）
     */
    private void recreateCollection() {
        qdrantCollectionHelper.recreateCollection();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildAll() {
        // 检查 Qdrant 是否已有数据，有则跳过全量重建
        if (hasExistingData()) {
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
        pointIdMap.clear();

        // 清理并重建 Qdrant 集合
        recreateCollection();

        // 查询所有上架商品
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
        List<Embedding> validEmbeddings = new ArrayList<>();
        List<TextSegment> validSegments = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            if (embeddings.get(i).vector() != null && embeddings.get(i).vector().length > 0) {
                validEmbeddings.add(embeddings.get(i));
                validSegments.add(segments.get(i));
            } else {
                log.warn("商品 '{}' 向量化为空，跳过索引", products.get(i).getName());
            }
        }

        if (validEmbeddings.isEmpty()) {
            log.warn("所有商品向量化结果均为空，请检查嵌入服务是否正常运行");
            indexedCount = 0;
            lastRebuildTime = System.currentTimeMillis();
            return;
        }

        // 批量存入 Qdrant，记录返回的 pointId
        List<String> pointIds = embeddingStore.addAll(validEmbeddings, validSegments);
        for (int i = 0; i < products.size() && i < pointIds.size(); i++) {
            pointIdMap.put(products.get(i).getId(), pointIds.get(i));
        }

        indexedCount = products.size();
        lastRebuildTime = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - start;
        log.info("===== 商品向量索引完成: {} 条, 耗时 {}ms =====", indexedCount, elapsed);

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

        // 移除旧索引
        remove(product.getId());

        // 构建 embedding 并存储
        TextSegment segment = buildTextSegment(product, prices);
        Embedding embedding = embeddingModel.embed(segment.text()).content();
        if (embedding.vector() == null || embedding.vector().length == 0) {
            log.warn("商品 '{}' 向量化为空，跳过索引更新", product.getName());
            return;
        }
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
                truncateDetail(stripHtml(safe(product.getDetail()))));
    }

    /**
     * 构建带元数据的 TextSegment
     */
    private TextSegment buildTextSegment(ProductDO product, BigDecimal[] prices) {
        return TextSegment.from(buildIndexText(product),
                Metadata.from(toMetadataMap(product, prices)));
    }

    private Map<String, Object> toMetadataMap(ProductDO product, BigDecimal[] prices) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("productId", product.getId());
        meta.put("name", product.getName());
        meta.put("firstCategoryId", product.getFirstCategoryId());
        meta.put("shopId", product.getShopId());
        meta.put("mainImage", product.getMainImage());
        if (prices != null && prices.length == 2) {
            meta.put("minPrice", prices[0].doubleValue());
            meta.put("maxPrice", prices[1].doubleValue());
        }
        return meta;
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
