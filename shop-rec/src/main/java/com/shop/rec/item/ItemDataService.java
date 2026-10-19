package com.shop.rec.item;

import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.mall.mapper.MallProductMapper;
import com.shop.rec.mall.model.MallProductDO;
import com.shop.rec.mall.model.MallSkuStatDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * 兜底名单重建间隔（秒）
     *
     * <p>取 5 秒与需求分析 4.3「行为产生后 5 秒内特征生效」对齐：降级时对外返回的
     * 就是这份兜底名单，若它比热度榜慢一个数量级，5 秒时效在降级路径上就不成立。
     * 重建成本仅为一次「读热度榜前 100 名」并构造列表，5 秒一次可忽略。
     */
    private static final long FALLBACK_REBUILD_INTERVAL_SECONDS = 5L;

    /** 商品上架状态取值（product.status = 1） */
    private static final int PRODUCT_STATUS_ON_SALE = 1;

    /** 对账时单次比对的商品数上限，避免单轮任务耗时过长 */
    private static final int RECONCILE_SAMPLE_SIZE = 2000;

    /** 未执行过重建时的状态说明（与前端约定：该文案表示「从未重建」，而非「失败」） */
    public static final String NOT_REBUILT = "尚未执行重建";

    private final MallProductMapper mallProductMapper;

    private final RecRedis recRedis;

    /**
     * 重建进行中标记
     *
     * <p>手动触发与定时任务共用：全量重建会清空并重写商品状态缓存，
     * 两次重建交叠会把彼此的写入互相覆盖，必须串行。
     */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /** 最近一次重建的结束时间；从未重建时为 null */
    private volatile LocalDateTime lastRebuildTime;

    /** 最近一次重建是否成功 */
    private volatile boolean lastRebuildSuccess;

    /** 最近一次重建的结果说明 */
    private volatile String lastRebuildMessage = NOT_REBUILT;

    /** 兜底名单：热度榜前 N 名的进程内副本 */
    private volatile List<Long> fallbackItems = Collections.emptyList();

    /** 兜底名单上次重建时间戳（毫秒） */
    private volatile long fallbackRebuiltAt = 0L;

    /**
     * 商品与索引全量重建
     *
     * <p>每日 01:00 执行（对应详细设计 8.2 任务 2），与画像任务（02:00）分时执行避免重叠。
     *
     * <p><b>同步执行（勿改为异步）</b>：定时任务与集成测试都直接调用本方法并
     * <b>紧接着断言</b>结果——{@code ItemAndProfileIntegrationTest} 与
     * {@code RecallFusionRankIntegrationTest} 的类注释写明「定时任务 cron 位于凌晨低峰期，
     * 无法在测试中等待，故直接调用其入口方法」。改成起线程后立即返回会让断言读到
     * {@link #rebuildAll()} 清空缓存之后、重写之前的中间态，测试必失败。
     * 需要异步的是管理后台手动触发（{@link #triggerRebuild()}），两者共用
     * {@link #rebuildAll()} 保证产出一致。
     */
    /**
     * 启动时全量补齐商品状态缓存。
     *
     * <p>商品状态缓存是 Flink 解析行为维度（类目/品牌）的数据源；若缓存只覆盖部分商品
     * （如进程跨过每日 01:00 重建点、或首次启动），缺失商品的行为会被计入却算不出偏好，
     * 表现为「用户浏览了但画像不变」的静默丢特征。启动后异步触发一次全量重建补齐。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        if (triggerRebuild()) {
            log.info("推荐服务启动，已触发商品状态缓存全量重建");
        }
    }

    @Scheduled(cron = "0 0 1 * * ?")
    public void fullReloadDaily() {
        if (!rebuilding.compareAndSet(false, true)) {
            log.warn("已有商品索引重建在执行中，本次定时触发被忽略");
            return;
        }
        try {
            rebuildAll();
        } finally {
            rebuilding.set(false);
        }
    }

    /**
     * 手动触发全量重建（管理后台用）
     *
     * <p><b>为什么需要这个入口</b>：商品索引此前完全依赖 {@code @Scheduled}（全量每日 01:00、
     * 增量每 10 分钟），换完商品数据只能干等——手动重建补齐了这个缺口。
     *
     * <p><b>与定时入口的区别仅在异步</b>：全量加载上万商品并重写索引耗时可达数秒至数十秒，
     * 同步执行会让管理后台请求超时。触发后由调用方轮询 {@link #rebuildStatus()} 获知结果。
     *
     * @return 已触发返回 true；已有重建在执行中返回 false
     */
    public boolean triggerRebuild() {
        if (!rebuilding.compareAndSet(false, true)) {
            log.warn("已有商品索引重建在执行中，本次触发被忽略");
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                rebuildAll();
            } finally {
                rebuilding.set(false);
            }
        }, "rec-item-rebuild");
        // 守护线程：重建不应阻止进程退出
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /**
     * 查询最近一次重建状态
     *
     * @return 重建状态快照
     */
    public RebuildStatus rebuildStatus() {
        return new RebuildStatus(rebuilding.get(), lastRebuildTime,
                lastRebuildSuccess, lastRebuildMessage);
    }

    /**
     * 全量重建商品状态缓存、类目候选索引与兜底名单
     *
     * <p>手动触发与定时任务共用本方法，保证两条路径产出一致。
     *
     * <p><b>与纯定时重建的差别在开头的清理</b>：定时任务面对的是商品的小幅变动，
     * 分页遍历写入即可覆盖；而手动重建的典型场景是商品数据被整体替换，此时
     * <b>已被物理删除的商品不会出现在分页查询结果中</b>——它们的状态缓存键既不会被写入语句
     * 覆盖，也不会被对账任务清掉（对账只遍历「当前可售商品」），会永久残留，
     * 使推荐结果里出现「已不存在但仍在售」的悬空商品。故先按模式清空状态缓存，再全量重写。
     *
     * <p>⚠️ 清理与重写之间存在短暂空窗，期间榜单可能因查不到商品状态而降级到兜底名单，
     * 属预期行为（重建是低频运维操作）。
     */
    private void rebuildAll() {
        long start = System.currentTimeMillis();

        // 清空前先探测商城库可达性：若库不可达却已把状态缓存清空，重建随即失败，
        // 榜单会在下一次重建成功之前持续降级。宁可不动缓存、直接放弃本轮。
        try {
            mallProductMapper.selectSellableProducts(1);
        } catch (Exception e) {
            lastRebuildSuccess = false;
            lastRebuildMessage = "重建中止：商城商品库不可达，未改动任何缓存";
            lastRebuildTime = LocalDateTime.now();
            log.error("商品索引重建中止：商城库不可达，未改动缓存", e);
            return;
        }

        try {
            long cleared = recRedis.deleteByPattern(RecRedisKeys.itemStatePattern());
            int loaded = fullReloadProducts();
            int indexed = refreshCategoryIndex();
            refreshFallbackList();
            lastRebuildSuccess = true;
            lastRebuildMessage = String.format(
                    "清理残留状态 %d 个，加载商品 %d 件，重建类目索引 %d 个", cleared, loaded, indexed);
            log.info("商品索引全量重建完成, {}，耗时: {}ms", lastRebuildMessage,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            lastRebuildSuccess = false;
            lastRebuildMessage = "重建失败：" + e.getMessage();
            log.error("商品索引全量重建失败", e);
        } finally {
            lastRebuildTime = LocalDateTime.now();
        }
    }

    /**
     * 重建状态快照（管理后台轮询用）
     *
     * @param rebuilding         是否正在重建
     * @param lastRebuildTime    最近一次重建的结束时间；从未重建时为 null
     * @param lastRebuildSuccess 最近一次重建是否成功
     * @param lastRebuildMessage 结果说明；从未重建时为「尚未执行重建」
     */
    public record RebuildStatus(boolean rebuilding, LocalDateTime lastRebuildTime,
                                boolean lastRebuildSuccess, String lastRebuildMessage) {
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
