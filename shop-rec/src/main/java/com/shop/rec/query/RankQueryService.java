package com.shop.rec.query;

import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.enums.DegradeState;
import com.shop.rec.fusion.FusionFilterService;
import com.shop.rec.fusion.model.FilterResult;
import com.shop.rec.item.ItemDataService;
import com.shop.rec.mall.mapper.MallProductMapper;
import com.shop.rec.mall.model.MallProductDisplayDO;
import com.shop.rec.metric.MetricService;
import com.shop.rec.query.model.RankItemVO;
import com.shop.rec.query.model.RankQuery;
import com.shop.rec.query.model.RankSnapshot;
import com.shop.rec.query.model.RankVO;
import com.shop.rec.rank.RankService;
import com.shop.rec.rank.model.RankedItem;
import com.shop.rec.rank.model.RankResult;
import com.shop.rec.recall.RecallService;
import com.shop.rec.recall.model.RecallCandidate;
import com.shop.rec.recall.model.RecallResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 榜单查询服务（M-08，系统对外唯一出口）
 *
 * <p>
 * 严格对应《详细设计说明书》3.8：身份判定 → 参数校验 → 快照命中判定 → 编排召回 / 过滤 / 打分 →
 * 生成快照 → 切片返回。所有降级均在本类内部完成，对外只暴露一个降级状态。
 *
 * <p>
 * <b>降级状态裁决</b>：各环节各自上报命中的降级条件，本类按 7.2 的优先级取最严重者作为最终状态
 * （上游兜底 &gt; 兜底名单 &gt; 召回降级 &gt; 特征缺失 &gt; 冷启动 &gt; 游客 &gt; 正常）。
 *
 * <p>
 * <b>可用性底线</b>：任何异常都必须返回非空商品列表。内部凭证非法由过滤器拦截（错误码 1009）；
 * 游客直接返回热门榜单；链路异常逐级降级到兜底名单；兜底名单也为空时，最终由商城后端返回原生热销榜。
 *
 * <p>
 * <b>超时保护</b>：总耗时逼近上限（默认 800ms）时中断后续环节并直接返回兜底名单，
 * 避免请求挂起（详细设计 3.8 异常分支）。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class RankQueryService {

    /** 请求总耗时上限（毫秒）：超过即中断后续环节并返回兜底名单 */
    private static final long TOTAL_TIMEOUT_MILLIS = 800L;

    /** 游客与冷启动场景下热门榜单的取用条数上限 */
    private static final int HOT_RANK_SIZE = 200;

    private final RecallService recallService;

    private final FusionFilterService fusionFilterService;

    private final RankService rankService;

    private final SnapshotService snapshotService;

    private final MallProductMapper mallProductMapper;

    private final ItemDataService itemDataService;

    private final RecRedis recRedis;

    private final MetricService metricService;

    /**
     * 查询个性化榜单
     *
     * @param userId 用户标识；为 null 表示游客
     * @param query  查询参数
     * @return 榜单结果（任何情况下 items 均尽量非空）
     */
    public RankVO query(Long userId, RankQuery query) {
        long start = System.currentTimeMillis();
        RankVO result = doQuery(userId, query);
        // 指标埋点（M-10）：记录请求总耗时与最终降级状态，失败不影响业务
        metricService.recordStageCost("total", System.currentTimeMillis() - start);
        metricService.recordDegrade(result.getDegraded());
        return result;
    }

    /**
     * 榜单查询主体
     *
     * @param userId 用户标识；为 null 表示游客
     * @param query  查询参数
     * @return 榜单结果
     */
    private RankVO doQuery(Long userId, RankQuery query) {
        long start = System.currentTimeMillis();

        // 身份判定：游客直接返回热门榜单，不进入个性化流程
        if (userId == null) {
            return guestRank(query);
        }

        // 快照命中判定：四个条件同时满足才复用
        RankSnapshot cached = snapshotService.read(userId);
        if (isSnapshotReusable(cached, query)) {
            return slice(cached, query);
        }

        // 未命中：编排召回 → 过滤 → 打分
        RankSnapshot snapshot;
        try {
            snapshot = buildSnapshot(userId, start);
        } catch (Exception e) {
            log.error("榜单编排异常，返回兜底名单, userId: {}", userId, e);
            snapshot = buildFallbackSnapshot(userId, DegradeState.FALLBACK_LIST);
        }

        if (snapshot == null || snapshot.getItems().isEmpty()) {
            snapshot = buildFallbackSnapshot(userId, DegradeState.FALLBACK_LIST);
        }
        if (snapshot == null || snapshot.getItems().isEmpty()) {
            // 兜底名单也为空：返回空榜单并由 M-08 对外暴露「上游兜底」，
            // 由商城后端切换到原生热销榜（详细设计 3.8 异常分支「最终仍无商品」）
            RankVO empty = new RankVO();
            empty.setSnapshotId(null);
            empty.setDegraded(DegradeState.UPSTREAM_FALLBACK.getText());
            empty.setPageNum(query.normalizePageNum());
            empty.setPageSize(query.normalizePageSize());
            empty.setTotal(0);
            empty.setHasMore(false);
            empty.setItems(Collections.emptyList());
            log.error("榜单与兜底名单均为空，触发上游兜底, userId: {}", userId);
            return empty;
        }

        snapshotService.save(userId, snapshot);
        return slice(snapshot, query);
    }

    /**
     * 构建榜单快照：编排三段链路并裁决降级状态
     *
     * @param userId 用户标识
     * @param start  请求开始时间（用于超时保护）
     * @return 快照
     */
    private RankSnapshot buildSnapshot(Long userId, long start) {
        RecallResult recall = recallService.recall(userId);
        if (isTimeout(start)) {
            return buildFallbackSnapshot(userId, DegradeState.FALLBACK_LIST);
        }

        FilterResult filter = fusionFilterService.fuseAndFilter(recall, userId);
        if (isTimeout(start)) {
            return buildFallbackSnapshot(userId, DegradeState.FALLBACK_LIST);
        }

        RankResult rank = rankService.rank(filter, userId);
        if (rank.getItems().isEmpty()) {
            return null;
        }

        // 按优先级裁决最终降级状态：取各环节上报条件中最严重者
        DegradeState state = DegradeState.mostSevere(recall.getDegradeState(), filter.getDegradeState());
        state = DegradeState.mostSevere(state, rank.getDegradeState());

        RankSnapshot snapshot = new RankSnapshot();
        snapshot.setSnapshotId(snapshotService.generateSnapshotId(userId, rank.getItems()));
        snapshot.setGeneratedAt(System.currentTimeMillis());
        snapshot.setDegraded(state.getText());
        snapshot.setItems(rank.getItems());
        return snapshot;
    }

    /**
     * 构建游客榜单：直接返回热门榜单
     *
     * @param query 查询参数
     * @return 榜单结果，状态为「游客」
     */
    private RankVO guestRank(RankQuery query) {
        List<RankedItem> hotItems = hotRankItems();
        RankVO vo = new RankVO();
        vo.setSnapshotId(null);
        // 热门榜单也为空（热度榜与兜底名单均不可用，如计算侧尚未产出热度）时标记上游兜底，
        // 由商城后端切换到原生热销榜——本系统不允许对外返回空列表
        vo.setDegraded(hotItems.isEmpty()
                ? DegradeState.UPSTREAM_FALLBACK.getText()
                : DegradeState.GUEST.getText());
        vo.setPageNum(query.normalizePageNum());
        vo.setPageSize(query.normalizePageSize());
        vo.setTotal(hotItems.size());
        int from = Math.min((vo.getPageNum() - 1) * vo.getPageSize(), hotItems.size());
        int to = Math.min(from + vo.getPageSize(), hotItems.size());
        vo.setHasMore(to < hotItems.size());
        vo.setItems(buildItemVOs(hotItems.subList(from, to)));
        return vo;
    }

    /**
     * 构建兜底榜单快照
     *
     * <p>快照标识按兜底结果同样生成，保证降级期间翻页顺序一致（详细设计 6.5(5)）。
     *
     * @param userId 用户标识
     * @param state  降级状态
     * @return 快照；无可推荐商品时返回 null
     */
    private RankSnapshot buildFallbackSnapshot(Long userId, DegradeState state) {
        List<RankedItem> items = hotRankItems();
        if (items.isEmpty()) {
            return null;
        }
        RankSnapshot snapshot = new RankSnapshot();
        snapshot.setSnapshotId(snapshotService.generateSnapshotId(userId, items));
        snapshot.setGeneratedAt(System.currentTimeMillis());
        snapshot.setDegraded(state.getText());
        snapshot.setItems(items);
        return snapshot;
    }

    /**
     * 取热门榜单（兜底与游客共用）
     *
     * <p>优先直接读热度榜（带真实热度分）；热度榜不可用时退到兜底名单
     * （其本身即热度榜前若干名的进程内副本，但只有商品标识、没有分值）。
     *
     * @return 榜单元素列表；均不可用时返回空列表
     */
    private List<RankedItem> hotRankItems() {
        try {
            List<ZSetOperations.TypedTuple<String>> tuples =
                    recRedis.zrevrangeWithScores(RecRedisKeys.HOT_ITEM, 0, HOT_RANK_SIZE - 1L);
            List<RankedItem> items = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() == null) {
                    continue;
                }
                // 走热度榜的是热门召回结果，标 HOT；只有退到兜底名单时才标 FALLBACK
                items.add(new RankedItem(parseLong(tuple.getValue()),
                        RecallCandidate.SOURCE_HOT,
                        tuple.getScore() == null ? 0D : tuple.getScore(),
                        false));
            }
            if (!items.isEmpty()) {
                return items;
            }
        } catch (Exception e) {
            log.warn("热度榜读取失败，改用兜底名单", e);
        }

        List<RankedItem> fallback = new ArrayList<>();
        for (Long itemId : itemDataService.getFallbackItems()) {
            if (fallback.size() >= HOT_RANK_SIZE) {
                break;
            }
            fallback.add(new RankedItem(itemId, RecallCandidate.SOURCE_FALLBACK, 0D, false));
        }
        return fallback;
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

    /**
     * 判断快照是否可复用（详细设计 6.5(3) 的四个条件）
     *
     * <p>有效期由 Redis TTL 保证，因此此处校验标识一致、快照存在与页码未超总页数。
     *
     * @param snapshot 已读取的快照
     * @param query    查询参数
     * @return 可复用返回 true
     */
    private boolean isSnapshotReusable(RankSnapshot snapshot, RankQuery query) {
        if (snapshot == null || snapshot.getSnapshotId() == null) {
            return false;
        }
        if (!snapshot.getSnapshotId().equals(query.getSnapshotId())) {
            return false;
        }
        int pageSize = query.normalizePageSize();
        int totalPages = (snapshot.getItems().size() + pageSize - 1) / pageSize;
        return query.normalizePageNum() <= Math.max(totalPages, 1);
    }

    /**
     * 按分页参数切割快照并补充展示信息
     *
     * @param snapshot 快照
     * @param query    查询参数
     * @return 榜单结果
     */
    private RankVO slice(RankSnapshot snapshot, RankQuery query) {
        int pageNum = query.normalizePageNum();
        int pageSize = query.normalizePageSize();
        List<RankedItem> items = snapshot.getItems();
        int from = Math.min((pageNum - 1) * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());

        RankVO vo = new RankVO();
        vo.setSnapshotId(snapshot.getSnapshotId());
        vo.setDegraded(snapshot.getDegraded());
        vo.setPageNum(pageNum);
        vo.setPageSize(pageSize);
        vo.setTotal(items.size());
        vo.setHasMore(to < items.size());
        vo.setItems(buildItemVOs(items.subList(from, to)));
        return vo;
    }

    /**
     * 组装展示项：批量补充商品名称、主图与价格
     *
     * <p>批量查询避免逐商品查询造成的 N+1 问题；商城数据查询失败时保留商品标识，
     * 由前端按缺省样式渲染，不因展示信息缺失导致整批榜单失败。
     *
     * @param items 榜单元素
     * @return 展示项列表
     */
    private List<RankItemVO> buildItemVOs(List<RankedItem> items) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, MallProductDisplayDO> displayMap = loadDisplayInfo(items);
        List<RankItemVO> vos = new ArrayList<>(items.size());
        for (RankedItem item : items) {
            RankItemVO vo = new RankItemVO();
            vo.setItemId(item.getItemId());
            vo.setRecallSource(item.getRecallSource());
            vo.setScore(item.getScore());
            MallProductDisplayDO display = displayMap.get(item.getItemId());
            if (display != null) {
                vo.setItemName(display.getName());
                vo.setMainImage(display.getMainImage());
                vo.setPrice(display.getMinPrice());
            }
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 批量读取商品展示信息
     *
     * @param items 榜单元素
     * @return 商品标识到展示信息的映射；查询失败返回空映射
     */
    private Map<Long, MallProductDisplayDO> loadDisplayInfo(List<RankedItem> items) {
        List<Long> itemIds = items.stream().map(RankedItem::getItemId).collect(Collectors.toList());
        try {
            return mallProductMapper.selectDisplayInfo(itemIds).stream()
                    .collect(Collectors.toMap(MallProductDisplayDO::getId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("商品展示信息查询失败，榜单仅返回商品标识", e);
            return new HashMap<>();
        }
    }

    /**
     * 判断是否已逼近总耗时上限
     *
     * @param start 请求开始时间
     * @return 超时返回 true
     */
    private boolean isTimeout(long start) {
        return System.currentTimeMillis() - start > TOTAL_TIMEOUT_MILLIS;
    }
}
