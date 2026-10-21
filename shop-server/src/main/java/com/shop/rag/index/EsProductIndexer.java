package com.shop.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.BrandMapper;
import com.shop.mapper.CategoryMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.BrandDO;
import com.shop.model.entity.CategoryDO;
import com.shop.model.entity.ProductDO;
import com.shop.rag.config.RagElasticsearchProperties;
import com.shop.rag.search.SkuPriceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Elasticsearch 商品索引构建与同步（功能编号 001）
 * <p>
 * 关键词路从 MySQL FULLTEXT 升级为 ES + IK 后的索引侧实现，职责与 {@link ProductIndexer}
 * （Qdrant 向量索引）并列而非替代：两者各自消费同一份 MySQL 商品数据，产出不同形态的索引。
 * <p>
 * <b>为什么不复用 {@code ProductIndexer#buildEmbeddingText}：</b>
 * 那是「五段式拼成一段文本」喂给 embedding 的输入，ES 需要的是**按字段分开索引**
 * （名称要 boost 3、分类要能 term 过滤），共用会把所有字段混进同一个 text 字段，
 * boost 与过滤全部失效。两边文本构建刻意独立。
 * <p>
 * 索引文档只存检索所需字段，展示数据（名称/主图/描述）仍由
 * {@code RagHitEnricher} 回查 MySQL —— 与 Qdrant 侧「只召回 ID」的约定一致。
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsProductIndexer {

    /** 索引 mapping：沿用可行性文档 §八 的硬约束（IK 版本、ik_max_word/ik_smart 分工） */
    private static final String INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0,
                "refresh_interval": "1s"
              },
              "mappings": {
                "properties": {
                  "productId":       { "type": "long" },
                  "name":            { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "description":     { "type": "text", "analyzer": "ik_smart" },
                  "brandName":       { "type": "text", "analyzer": "ik_smart" },
                  "categoryName":    { "type": "text", "analyzer": "ik_smart" },
                  "firstCategoryId": { "type": "long" },
                  "shopId":          { "type": "long" },
                  "minPrice":        { "type": "scaled_float", "scaling_factor": 100 },
                  "maxPrice":        { "type": "scaled_float", "scaling_factor": 100 },
                  "status":          { "type": "integer" },
                  "deleted":         { "type": "integer" },
                  "updateTime":      { "type": "date", "format": "epoch_millis" }
                }
              }
            }
            """;

    /** 描述截断：与向量路一致，防止单文档过大拖慢索引 */
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final ElasticsearchClient esClient;
    private final RagElasticsearchProperties properties;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final SkuPriceResolver skuPriceResolver;

    /** 重建/导入防重入：启动事件与定时自愈可能同时触发 */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    // ===== 状态（管理接口消费；volatile 保证可见性） =====
    private volatile boolean available = false;
    private volatile long lastRebuildTime = 0L;
    private volatile boolean lastRebuildSuccess = false;
    private volatile String lastRebuildMessage = "尚未执行重建";

    /**
     * 启动就绪：建索引 + 空索引自动导入
     * <p>
     * ES 可能比应用晚就绪（compose healthcheck 60s），失败只 WARN 不阻断启动；
     * 由 {@link #selfHeal()} 定时重试。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            log.info("ES 关键词路已关闭（rag.elasticsearch.enabled=false），关键词检索走 MySQL FULLTEXT");
            return;
        }
        try {
            ensureIndex();
            available = true;
            long count = countDocuments();
            log.info("ES 索引就绪: {} 条文档", count);
            if (count == 0) {
                log.info("ES 索引为空，开始全量导入商品...");
                importAll();
            }
        } catch (Exception e) {
            available = false;
            log.warn("ES 不可用，关键词路将回退 MySQL FULLTEXT（{} 后自动重试）: {}",
                    properties.getRecoveryIntervalMs(), e.getMessage());
        }
    }

    /**
     * 定时自愈：ES 晚启动 / 索引被清空时自动恢复
     * <p>
     * 只在「不可用」或「索引为空」时动作，正常状态下仅一次 count 请求。
     * 与 {@link #rebuilding} 配合防重入。
     */
    @Scheduled(fixedDelayString = "${rag.elasticsearch.recovery-interval-ms:30000}")
    public void selfHeal() {
        if (!properties.isEnabled() || rebuilding.get()) {
            return;
        }
        try {
            ensureIndex();
            available = true;
            if (countDocuments() == 0) {
                log.info("[ES自愈] 索引为空，重新导入商品");
                importAll();
            }
        } catch (Exception e) {
            if (available) {
                // 只在状态切换时打 WARN，避免 ES 长时间不可用时每 30s 刷屏
                log.warn("[ES自愈] ES 不可用，关键词路回退 MySQL FULLTEXT: {}", e.getMessage());
            }
            available = false;
        }
    }

    /**
     * 全量重建：删除索引 → 重建 mapping → 导入全部上架商品
     * <p>
     * ⚠️ 删除到导入完成之间 ES 返回 404，检索侧会自动回退 FULLTEXT（设计文档 H8）。
     *
     * @return 导入条数；失败返回 -1
     */
    public int rebuildAll() {
        if (!properties.isEnabled()) {
            lastRebuildSuccess = false;
            lastRebuildMessage = "ES 关键词路已关闭";
            lastRebuildTime = System.currentTimeMillis();
            return -1;
        }
        if (!rebuilding.compareAndSet(false, true)) {
            lastRebuildMessage = "已有重建任务在执行";
            return -1;
        }
        long start = System.currentTimeMillis();
        try {
            deleteIndexQuietly();
            createIndex();
            int imported = importAllInternal();
            lastRebuildSuccess = true;
            lastRebuildMessage = String.format("ES 索引重建成功，导入 %d 条", imported);
            available = true;
            log.info("===== ES 商品索引重建完成: {} 条, 耗时 {}ms =====", imported, System.currentTimeMillis() - start);
            return imported;
        } catch (Exception e) {
            lastRebuildSuccess = false;
            lastRebuildMessage = "ES 索引重建失败: " + e.getMessage();
            available = false;
            log.error("ES 商品索引重建失败: {}", e.getMessage(), e);
            return -1;
        } finally {
            lastRebuildTime = System.currentTimeMillis();
            rebuilding.set(false);
        }
    }

    /**
     * 新增 / 更新单个商品文档（幂等：_id 固定为 productId）
     * <p>
     * 非上架商品直接删除文档 —— 虽然检索侧有 status 过滤兜底，
     * 但残留文档会持续占用索引空间，且会让「索引条数」统计失真。
     *
     * @param product 商品（可为 null）
     */
    public void upsert(ProductDO product) {
        if (!properties.isEnabled() || product == null || product.getId() == null) {
            return;
        }
        if (product.getStatus() == null || product.getStatus() != ProductConstants.STATUS_ON_SHELF) {
            delete(product.getId());
            return;
        }
        try {
            BigDecimal[] prices = skuPriceResolver.resolveOne(product.getId());
            EsProductDocument document = toDocument(product, prices, resolveCategoryName(product.getCategoryId()),
                    resolveBrandName(product.getBrandId()));
            esClient.index(i -> i.index(properties.getIndexName())
                    .id(String.valueOf(product.getId()))
                    .document(document));
            available = true;
            log.debug("ES 商品文档更新完成, productId: {}", product.getId());
        } catch (Exception e) {
            log.warn("ES 商品索引更新失败 productId={}（不影响主流程）: {}", product.getId(), e.getMessage());
        }
    }

    /**
     * 删除单个商品文档（不存在时静默忽略）
     *
     * @param productId 商品 ID
     */
    public void delete(Long productId) {
        if (!properties.isEnabled() || productId == null) {
            return;
        }
        try {
            esClient.delete(d -> d.index(properties.getIndexName()).id(String.valueOf(productId)));
        } catch (ElasticsearchException e) {
            if (!isNotFound(e)) {
                log.warn("ES 商品文档删除失败 productId={}: {}", productId, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("ES 商品文档删除失败 productId={}: {}", productId, e.getMessage());
        }
    }

    /**
     * 查询索引文档数（ES 不可用时返回 -1，不是 0）
     *
     * @return 文档数；失败 -1
     */
    public long countDocuments() {
        try {
            return esClient.count(c -> c.index(properties.getIndexName())).count();
        } catch (Exception e) {
            log.debug("ES 文档数查询失败: {}", e.getMessage());
            return -1;
        }
    }

    /** ES 关键词路当前是否可用（最近一次探测结果） */
    public boolean isAvailable() {
        return available;
    }

    public long getLastRebuildTime() {
        return lastRebuildTime;
    }

    public boolean isLastRebuildSuccess() {
        return lastRebuildSuccess;
    }

    public String getLastRebuildMessage() {
        return lastRebuildMessage;
    }

    // ===== 内部实现 =====

    /** 索引不存在则创建；已存在则跳过。IK 缺失时抛出带明确提示的异常 */
    private void ensureIndex() throws Exception {
        boolean exists = esClient.indices().exists(e -> e.index(properties.getIndexName())).value();
        if (exists) {
            return;
        }
        createIndex();
    }

    private void createIndex() throws Exception {
        try {
            esClient.indices().create(c -> c.index(properties.getIndexName())
                    .withJson(new StringReader(INDEX_MAPPING)));
            log.info("ES 索引已创建: {}", properties.getIndexName());
        } catch (ElasticsearchException e) {
            String reason = e.getMessage() == null ? "" : e.getMessage();
            if (reason.contains("resource_already_exists_exception")) {
                return;
            }
            if (reason.contains("analysis-ik") || reason.contains("analyzer [ik_")) {
                throw new IllegalStateException(
                        "创建 ES 索引失败：analysis-ik 分词器未安装（镜像应为 shop-elasticsearch:8.11.4）", e);
            }
            throw e;
        }
    }

    private void deleteIndexQuietly() {
        try {
            esClient.indices().delete(d -> d.index(properties.getIndexName()));
            log.info("ES 索引已删除: {}", properties.getIndexName());
        } catch (ElasticsearchException e) {
            if (!isNotFound(e)) {
                log.warn("ES 索引删除失败（继续重建）: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("ES 索引删除失败（继续重建）: {}", e.getMessage());
        }
    }

    /** 全量导入（不删索引），供启动自愈使用 */
    private void importAll() throws Exception {
        if (!rebuilding.compareAndSet(false, true)) {
            return;
        }
        try {
            importAllInternal();
        } finally {
            rebuilding.set(false);
        }
    }

    /** 查询上架商品并分批 bulk 导入 */
    private int importAllInternal() throws Exception {
        List<ProductDO> products = productMapper.selectList(
                new LambdaQueryWrapper<ProductDO>()
                        .eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF)
                        .orderByDesc(ProductDO::getCreateTime));
        if (products == null || products.isEmpty()) {
            log.warn("没有上架商品，ES 索引为空");
            return 0;
        }

        Map<Long, BigDecimal[]> priceMap = skuPriceResolver.resolve(
                products.stream().map(ProductDO::getId).filter(Objects::nonNull).toList());
        Map<Long, String> categoryNames = loadCategoryNameMap(products);
        Map<Long, String> brandNames = loadBrandNameMap(products);

        List<EsProductDocument> documents = products.stream()
                .map(p -> toDocument(p, priceMap.get(p.getId()),
                        nameOf(categoryNames, p.getCategoryId()), nameOf(brandNames, p.getBrandId())))
                .toList();

        int imported = 0;
        int batchSize = Math.max(1, properties.getBulkBatchSize());
        for (int i = 0; i < documents.size(); i += batchSize) {
            List<EsProductDocument> batch = documents.subList(i, Math.min(i + batchSize, documents.size()));
            imported += bulkIndex(batch);
        }
        return imported;
    }

    /** 单批 bulk 写入，返回成功条数 */
    private int bulkIndex(List<EsProductDocument> batch) throws Exception {
        BulkResponse response = esClient.bulk(b -> {
            for (EsProductDocument doc : batch) {
                b.operations(op -> op.index(idx -> idx
                        .index(properties.getIndexName())
                        .id(String.valueOf(doc.productId()))
                        .document(doc)));
            }
            return b;
        });

        if (response.errors()) {
            int failed = 0;
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    failed++;
                    log.warn("ES bulk 写入失败 id={}: {}", item.id(), item.error().reason());
                }
            }
            log.warn("ES bulk 部分失败: {}/{}", failed, batch.size());
            return batch.size() - failed;
        }
        return batch.size();
    }

    private EsProductDocument toDocument(ProductDO product, BigDecimal[] prices,
                                         String categoryName, String brandName) {
        Double minPrice = null;
        Double maxPrice = null;
        if (prices != null && prices.length == 2) {
            minPrice = prices[0] == null ? null : prices[0].doubleValue();
            maxPrice = prices[1] == null ? null : prices[1].doubleValue();
        }
        return new EsProductDocument(
                product.getId(),
                safe(product.getName()),
                truncate(product.getDescription()),
                brandName,
                categoryName,
                product.getFirstCategoryId(),
                product.getShopId(),
                minPrice,
                maxPrice,
                product.getStatus() == null ? ProductConstants.STATUS_ON_SHELF : product.getStatus(),
                product.getDeleted() == null ? 0 : product.getDeleted(),
                ProductIndexer.toEpochMillis(product.getUpdateTime()));
    }

    private Map<Long, String> loadCategoryNameMap(List<ProductDO> products) {
        Set<Long> ids = products.stream().map(ProductDO::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(CategoryDO::getId, CategoryDO::getName, (a, b) -> a));
    }

    private Map<Long, String> loadBrandNameMap(List<ProductDO> products) {
        Set<Long> ids = products.stream().map(ProductDO::getBrandId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return brandMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(BrandDO::getId, BrandDO::getName, (a, b) -> a));
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) {
            return "";
        }
        CategoryDO category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : "";
    }

    private String resolveBrandName(Long brandId) {
        if (brandId == null) {
            return "";
        }
        BrandDO brand = brandMapper.selectById(brandId);
        return brand != null ? brand.getName() : "";
    }

    private static String nameOf(Map<Long, String> map, Long id) {
        return id == null ? "" : map.getOrDefault(id, "");
    }

    private static boolean isNotFound(ElasticsearchException e) {
        return e.status() == 404;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DESCRIPTION_LENGTH ? text : text.substring(0, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * ES 商品文档（字段名即 JSON 键，与 {@link #INDEX_MAPPING} 严格对应）
     *
     * @param productId       商品 ID（同时作为文档 _id）
     * @param name            名称（boost 3）
     * @param description     描述（截断至 2000）
     * @param brandName       品牌名（boost 2）
     * @param categoryName    分类名（boost 1.5）
     * @param firstCategoryId 一级分类（term 过滤）
     * @param shopId          商家 ID（预留过滤）
     * @param minPrice        SKU 最低价（range 过滤）
     * @param maxPrice        SKU 最高价（range 过滤）
     * @param status          商品状态（检索过滤 status=1）
     * @param deleted         逻辑删除标记（检索过滤 deleted=0）
     * @param updateTime      epoch millis（供后续对账）
     */
    private record EsProductDocument(
            Long productId,
            String name,
            String description,
            String brandName,
            String categoryName,
            Long firstCategoryId,
            Long shopId,
            Double minPrice,
            Double maxPrice,
            Integer status,
            Integer deleted,
            Long updateTime) {
    }
}
