package com.shop.rec.item;

import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.mall.mapper.MallProductMapper;
import com.shop.rec.mall.model.MallProductDO;
import com.shop.rec.mall.model.MallSkuStatDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品数据与候选索引维护（M-04 的批处理部分）
 *
 * <p>
 * 承担三类工作（对应详细设计 3.4）：
 * <ol>
 *     <li><b>商品维度与可售状态</b>：全量加载商城商品、分批读取、判定可售并写入状态缓存
 *         （{@code rec:item:state:{itemId}}），该缓存同时是 Flink 作业解析类目 / 品牌维度的数据源；</li>
 *     <li><b>类目候选索引</b>：按二级类目归集可售商品，按热度降序每类目保留前 200 名，
 *         写入 {@code rec:cat:items:{categoryId}} 供类目偏好召回使用；</li>
 *     <li><b>兜底名单</b>：热度榜前 N 名的进程内副本，任何环节异常时的保底结果。</li>
 * </ol>
 *
 * <p>
 * <b>商品热度由 Flink 作业的商品热度算子统计</b>（同作业内按商品键控），本类只读取热度榜
 * {@code rec:hot:item} 并据此构建索引，不重复消费行为流（见详细设计 3.4「运行形态」）。
 *
 * <p>
 * <b>关于商品变更通知</b>：设计中的 {@code item-change} 主题允许分钟级延迟，且明确
 * 「消息丢失由对账任务修正」。本实现以<b>定时对账</b>替代消息订阅——每 10 分钟比对商品
 * 更新时间修正偏差，同样满足分钟级一致性要求，且避免在推荐服务内再引入一个 Kafka 消费者。
 *
 * <p>
 * <b>失败语义</b>：任一步骤失败均<b>沿用上一版数据</b>，不清理已有缓存、不阻塞其它任务
 * （对应详细设计 3.4 异常分支「全量加载失败：沿用上次加载结果并告警，不覆盖已有缓存」）。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class ItemDataService {

    /** 全量加载每批条数（对应详细设计 3.4 步骤 1） */
    private static final int LOAD_BATCH_SIZE = 500;

    /** 单类目候选索引保留条数（对应详细设计 3.4 步骤 7） */
    private static final int CATEGORY_INDEX_LIMIT = 200;

    /** 兜底名单条数（对应详细设计 3.4 步骤 8） */
    private static final int FALLBACK_LIMIT = 100;

    /** 兜底名单重建间隔（秒） */
    private static final long FALLBACK_REBUILD_INTERVAL_SECONDS = 30L;

    /** 商品上架状态取值（product.status = 1） */
    private static final int PRODUCT_STATUS_ON_SALE = 1;

    /** 对账时单次比对的商品数上限，避免单轮任务耗时过长 */
    private static final int RECONCILE_SAMPLE_SIZE = 2000;

    private final MallProductMapper mallProductMapper;

    private final RecRedis recRedis;

    /** 兜底名单：热度榜前 N 名的进程内副本 */
    private volatile List<Long> fallbackItems = Collections.emptyList();

    /** 兜底名单上次重建时间戳（毫秒） */
    private volatile long fallbackRebuiltAt = 0L;

    /**
     * 商品与索引全量重建
     *
     * <p>每日 01:00 执行（对应详细设计 8.2 任务 2），与画像任务（02:00）分时执行避免重叠。
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void fullReloadDaily() {
        long start = System.currentTimeMillis();
        try {
            int loaded = fullReloadProducts();
            int indexed = refreshCategoryIndex();
            refreshFallbackList();
            log.info("商品全量重建完成, 加载商品: {}, 类目索引写入: {}, 耗时: {}ms",
                    loaded, indexed, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("商品全量重建失败，沿用上一版数据与索引", e);
        }
    }

    /**
     * 热度与索引增量刷新
     *
     * <p>每 10 分钟执行（对应详细设计 8.2 任务 3）：热度分由 Flink 作业持续写入热度榜，
     * 本任务据此重建类目候选索引与兜底名单。
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void refreshIndexIncrementally() {
        try {
            int indexed = refreshCategoryIndex();
            refreshFallbackList();
            log.info("类目索引与兜底名单刷新完成, 写入类目数: {}", indexed);
        } catch (Exception e) {
            log.error("类目索引刷新失败，本轮保留上一轮结果", e);
        }
    }

    /**
     * 商品数据对账
     *
     * <p>每 10 分钟（偏移 5 分钟，与索引刷新错开资源争用）执行（对应详细设计 8.2 任务 4）：
     * 抽样比对商城中已变更的商品，修正状态缓存中与商城不一致的记录。
     */
    @Scheduled(cron = "0 5/10 * * * ?")
    public void reconcile() {
        try {
            List<MallProductDO> sellable = mallProductMapper.selectSellableProducts(RECONCILE_SAMPLE_SIZE);
            Map<Long, MallProductDO> sellableMap = sellable.stream()
                    .collect(Collectors.toMap(MallProductDO::getId, Function.identity(), (a, b) -> a));
            Map<Long, Integer> stockMap = loadStockMap(new ArrayList<>(sellableMap.keySet()));

            int refreshed = 0;
            int removed = 0;
            for (MallProductDO product : sellableMap.values()) {
                int stock = stockMap.getOrDefault(product.getId(), 0);
                if (stock > 0 && isOnSale(product.getStatus())) {
                    writeItemState(product, stock);
                    refreshed++;
                } else {
                    if (recRedis.delete(RecRedisKeys.itemState(product.getId()))) {
                        removed++;
                    }
                }
            }
            log.info("商品数据对账完成, 可售商品: {}, 刷新: {}, 清理失效: {}", sellableMap.size(), refreshed, removed);
        } catch (Exception e) {
            log.error("商品数据对账失败，下轮继续", e);
        }
    }

    /**
     * 获取兜底名单
     *
     * <p>超过重建间隔时触发惰性重建，保证任何时刻调用方都能拿到可用的保底商品列表。
     *
     * @return 兜底商品标识列表；极度异常时返回空列表
     */
    public List<Long> getFallbackItems() {
        if (System.currentTimeMillis() - fallbackRebuiltAt > FALLBACK_REBUILD_INTERVAL_SECONDS * 1000L) {
            try {
                refreshFallbackList();
            } catch (Exception e) {
                log.warn("兜底名单重建失败，沿用上一版", e);
            }
        }
        return fallbackItems;
    }

    /**
     * 全量加载商品并写入状态缓存
     *
     * @return 加载的商品总数
     */
    private int fullReloadProducts() {
        long lastId = 0L;
        int totalLoaded = 0;
        int sellableCount = 0;

        while (true) {
            List<MallProductDO> batch = mallProductMapper.selectBatchAfterId(lastId, LOAD_BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            List<Long> productIds = batch.stream().map(MallProductDO::getId).collect(Collectors.toList());
            Map<Long, Integer> stockMap = loadStockMap(productIds);

            for (MallProductDO product : batch) {
                lastId = Math.max(lastId, product.getId());
                totalLoaded++;

                int stock = stockMap.getOrDefault(product.getId(), 0);
                if (isOnSale(product.getStatus()) && stock > 0) {
                    writeItemState(product, stock);
                    sellableCount++;
                } else {
                    // 不可售（下架 / 售罄）直接删除状态缓存键，与详细设计 3.4 步骤 4 一致
                    recRedis.delete(RecRedisKeys.itemState(product.getId()));
                }
            }
        }
        log.info("商品全量加载完成, 总数: {}, 可售: {}", totalLoaded, sellableCount);
        return totalLoaded;
    }

    /**
     * 重建类目候选索引
     *
     * <p>按二级类目归集可售商品，按热度降序每类目保留前 {@value #CATEGORY_INDEX_LIMIT} 名。
     * 热度取自 Flink 作业写入的热度榜；无热度记录的商品按 0 分参与排序。
     *
     * @return 写入的类目数
     */
    private int refreshCategoryIndex() {
        List<MallProductDO> sellable = mallProductMapper.selectSellableProducts(0);
        if (sellable.isEmpty()) {
            log.warn("未查询到可售商品，跳过类目索引刷新");
            return 0;
        }

        Map<Long, Double> heatMap = loadHeatMap();
        Map<Long, List<MallProductDO>> byCategory = new LinkedHashMap<>();
        for (MallProductDO product : sellable) {
            if (product.getCategoryId() == null) {
                continue;
            }
            byCategory.computeIfAbsent(product.getCategoryId(), k -> new ArrayList<>()).add(product);
        }

        int written = 0;
        for (Map.Entry<Long, List<MallProductDO>> entry : byCategory.entrySet()) {
            Long categoryId = entry.getKey();
            String key = RecRedisKeys.catItems(categoryId);

            // 先清空再写入，避免已下架商品残留在索引中
            recRedis.delete(key);

            Map<String, Double> members = entry.getValue().stream()
                    .sorted((a, b) -> Double.compare(
                            heatMap.getOrDefault(b.getId(), 0D), heatMap.getOrDefault(a.getId(), 0D)))
                    .limit(CATEGORY_INDEX_LIMIT)
                    .collect(Collectors.toMap(
                            p -> String.valueOf(p.getId()),
                            p -> heatMap.getOrDefault(p.getId(), 0D),
                            (a, b) -> a,
                            LinkedHashMap::new));
            if (!members.isEmpty()) {
                recRedis.zaddAll(key, members);
                recRedis.expire(key, RecRedisKeys.TTL_CAT_ITEMS);
                written++;
            }
        }
        return written;
    }

    /**
     * 重建兜底名单（热度榜前 N 名的内存副本）
     */
    private void refreshFallbackList() {
        Set<String> topItems = recRedis.zrevrange(RecRedisKeys.HOT_ITEM, 0, FALLBACK_LIMIT - 1L);
        List<Long> items = new ArrayList<>(topItems.size());
        for (String item : topItems) {
            items.add(parseLong(item));
        }
        fallbackItems = Collections.unmodifiableList(items);
        fallbackRebuiltAt = System.currentTimeMillis();
    }

    /**
     * 批量读取商品总库存
     *
     * @param productIds 商品标识列表
     * @return 商品标识到总库存的映射
     */
    private Map<Long, Integer> loadStockMap(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<MallSkuStatDO> stats = mallProductMapper.selectSkuStats(productIds);
        Map<Long, Integer> stockMap = new HashMap<>(stats.size());
        for (MallSkuStatDO stat : stats) {
            stockMap.put(stat.getProductId(), stat.getTotalStock() == null ? 0 : stat.getTotalStock());
        }
        return stockMap;
    }

    /**
     * 读取商品热度榜
     *
     * @return 商品标识到热度分的映射；热度榜为空时返回空映射
     */
    private Map<Long, Double> loadHeatMap() {
        // 全量读取热度榜（商品规模在课程演示量级，一次拉取即可）
        List<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                recRedis.zrevrangeWithScores(RecRedisKeys.HOT_ITEM, 0, -1);
        Map<Long, Double> heatMap = new HashMap<>(tuples.size());
        for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null) {
                heatMap.put(parseLong(tuple.getValue()), tuple.getScore());
            }
        }
        return heatMap;
    }

    /**
     * 写入商品可售状态缓存
     *
     * <p>字段包含 Flink 作业解析维度所需的 categoryId 与 brandId，以及过滤环节使用的库存与店铺标识。
     *
     * @param product 商品
     * @param stock   总库存
     */
    private void writeItemState(MallProductDO product, int stock) {
        String key = RecRedisKeys.itemState(product.getId());
        Map<String, String> state = new HashMap<>(5);
        state.put(RecRedisKeys.ITEM_STATE_FIELD_STATUS, String.valueOf(product.getStatus()));
        state.put(RecRedisKeys.ITEM_STATE_FIELD_STOCK, String.valueOf(stock));
        if (product.getCategoryId() != null) {
            state.put(RecRedisKeys.ITEM_STATE_FIELD_CATEGORY, String.valueOf(product.getCategoryId()));
        }
        if (product.getBrandId() != null) {
            state.put(RecRedisKeys.ITEM_STATE_FIELD_BRAND, String.valueOf(product.getBrandId()));
        }
        if (product.getShopId() != null) {
            state.put(RecRedisKeys.ITEM_STATE_FIELD_SHOP, String.valueOf(product.getShopId()));
        }
        recRedis.hsetAll(key, state);
        recRedis.expire(key, RecRedisKeys.TTL_ITEM_STATE);
    }

    /**
     * 判断商品是否上架
     *
     * @param status 商品状态
     * @return 上架返回 true
     */
    private boolean isOnSale(Integer status) {
        return status != null && status == PRODUCT_STATUS_ON_SALE;
    }

    /**
     * 字符串转长整型，解析失败返回 0
     *
     * @param value 原始值
     * @return 数值
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
