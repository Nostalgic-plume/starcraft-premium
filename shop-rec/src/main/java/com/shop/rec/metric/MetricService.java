package com.shop.rec.metric;

import com.shop.rec.mapper.PipelineMetricMapper;
import com.shop.rec.model.entity.PipelineMetricDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 链路指标采集与汇总（M-10）
 *
 * <p>
 * 对应《详细设计说明书》3.10：各环节上报耗时与业务指标，明细按事件写入，
 * 汇总指标每小时整点后 5 分钟写入一次（cron {@code 0 5 * * * ?}，与详细设计 8.2 任务 5 一致）。
 *
 * <p>
 * <b>失败不影响业务</b>：指标写入失败仅记录日志并计数，绝不向上抛异常——
 * 可观测性缺失不应导致推荐链路失败。
 *
 * <p>
 * <b>指标清单</b>（预警阈值见详细设计 3.10）：
 * <ul>
 *     <li>特征覆盖率：有实时特征的请求数 ÷ <b>有身份且非冷启动的请求数</b>
 *         —— 分母排除冷启动用户，否则新用户增多时必然误报；</li>
 *     <li>特征新鲜度、两路召回命中率、降级触发次数、快照命中率；</li>
 *     <li>各环节耗时（召回 / 过滤 / 打分 / 总耗时）、画像任务成功率、上报无效事件数。</li>
 * </ul>
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class MetricService {

    // ==================== 指标名 ====================

    /** 特征覆盖率 */
    public static final String FEATURE_COVERAGE = "feature_coverage";

    /** 特征新鲜度（毫秒） */
    public static final String FEATURE_FRESHNESS = "feature_freshness";

    /** 类目偏好召回命中率 */
    public static final String RECALL_CATEGORY_HIT_RATE = "recall_category_hit_rate";

    /** 热门召回命中率 */
    public static final String RECALL_HOT_HIT_RATE = "recall_hot_hit_rate";

    /** 降级触发次数 */
    public static final String DEGRADE_COUNT = "degrade_count";

    /** 快照命中率 */
    public static final String SNAPSHOT_HIT_RATE = "snapshot_hit_rate";

    /** 画像任务成功率 */
    public static final String PROFILE_TASK_SUCCESS_RATE = "profile_task_success_rate";

    /** 上报无效事件数 */
    public static final String INVALID_EVENT_COUNT = "invalid_event_count";

    /** 环节耗时前缀：后接环节名，如 stage_cost_recall */
    public static final String STAGE_COST_PREFIX = "stage_cost_";

    /** 指标维度：降级状态（用于降级次数按状态分组） */
    public static final String DIM_DEGRADE_STATE = "degrade_state";

    /** 指标维度：召回路由 */
    public static final String DIM_RECALL_ROUTE = "recall_route";

    /** 指标维度：丢弃原因 */
    public static final String DIM_DROP_REASON = "drop_reason";

    // ==================== 预警阈值（详细设计 3.10） ====================

    /** 特征覆盖率预警阈值（低于该值告警） */
    private static final double THRESHOLD_FEATURE_COVERAGE = 80D;

    /** 特征新鲜度预警阈值（毫秒）：超过该值告警 */
    private static final long THRESHOLD_FEATURE_FRESHNESS_MS = 30_000L;

    /** 类目偏好召回命中率预警阈值 */
    private static final double THRESHOLD_CATEGORY_HIT_RATE = 60D;

    /** 热门召回命中率预警阈值 */
    private static final double THRESHOLD_HOT_HIT_RATE = 95D;

    /** 环节耗时预警阈值（毫秒）：召回超过 150ms 或总耗时超过 800ms */
    private static final long THRESHOLD_RECALL_COST_MS = 150L;

    private static final long THRESHOLD_TOTAL_COST_MS = 800L;

    private final PipelineMetricMapper pipelineMetricMapper;

    /**
     * 记录指标明细
     *
     * <p>统计时间取当前整点，便于按小时聚合；写入失败仅记日志。
     *
     * @param metricName 指标名，取值见本类常量
     * @param metricDim  维度取值，可为空
     * @param value      指标值：计数类存次数，比率类存百分比
     */
    public void record(String metricName, String metricDim, double value) {
        try {
            PipelineMetricDO metric = new PipelineMetricDO();
            metric.setMetricName(metricName);
            metric.setMetricDim(metricDim);
            metric.setMetricValue(BigDecimal.valueOf(value));
            metric.setStatTime(LocalDateTime.now().withMinute(0).withSecond(0).withNano(0));
            pipelineMetricMapper.insert(metric);
        } catch (Exception e) {
            log.warn("指标写入失败（不影响业务）, metric: {}, dim: {}", metricName, metricDim, e);
        }
    }

    /**
     * 记录环节耗时并做阈值预警
     *
     * @param stage  环节名，如 recall / filter / rank / total
     * @param costMs 耗时（毫秒）
     */
    public void recordStageCost(String stage, long costMs) {
        record(STAGE_COST_PREFIX + stage, null, costMs);
        if ("recall".equals(stage) && costMs > THRESHOLD_RECALL_COST_MS) {
            log.warn("召回环节耗时超阈值, 实际: {}ms, 阈值: {}ms", costMs, THRESHOLD_RECALL_COST_MS);
        }
        if ("total".equals(stage) && costMs > THRESHOLD_TOTAL_COST_MS) {
            log.warn("榜单请求总耗时超阈值, 实际: {}ms, 阈值: {}ms", costMs, THRESHOLD_TOTAL_COST_MS);
        }
    }

    /**
     * 记录降级触发
     *
     * @param degradeState 降级状态文案
     */
    public void recordDegrade(String degradeState) {
        record(DEGRADE_COUNT, degradeState, 1D);
    }

    /**
     * 记录特征覆盖率并预警
     *
     * @param coveredRequests 有实时特征的请求数
     * @param eligibleRequests 有身份且非冷启动的请求数
     */
    public void recordFeatureCoverage(long coveredRequests, long eligibleRequests) {
        if (eligibleRequests <= 0L) {
            return;
        }
        double rate = coveredRequests * 100D / eligibleRequests;
        record(FEATURE_COVERAGE, null, rate);
        if (rate < THRESHOLD_FEATURE_COVERAGE) {
            log.warn("特征覆盖率低于阈值, 实际: {}%, 阈值: {}%", Math.round(rate), THRESHOLD_FEATURE_COVERAGE);
        }
    }

    /**
     * 记录特征新鲜度并预警
     *
     * @param latestEventTimeMs 最近一次特征写入时间（毫秒时间戳）
     */
    public void recordFeatureFreshness(long latestEventTimeMs) {
        if (latestEventTimeMs <= 0L) {
            return;
        }
        long freshness = System.currentTimeMillis() - latestEventTimeMs;
        record(FEATURE_FRESHNESS, null, freshness);
        if (freshness > THRESHOLD_FEATURE_FRESHNESS_MS) {
            log.warn("特征新鲜度超阈值, 实际: {}ms, 阈值: {}ms", freshness, THRESHOLD_FEATURE_FRESHNESS_MS);
        }
    }

    /**
     * 记录召回命中率并预警
     *
     * @param route       召回路由：category / hot
     * @param hitRequests 命中该路的请求数
     * @param totalRequests 总请求数
     */
    public void recordRecallHitRate(String route, long hitRequests, long totalRequests) {
        if (totalRequests <= 0L) {
            return;
        }
        double rate = hitRequests * 100D / totalRequests;
        String metricName = "category".equals(route) ? RECALL_CATEGORY_HIT_RATE : RECALL_HOT_HIT_RATE;
        record(metricName, route, rate);

        double threshold = "category".equals(route) ? THRESHOLD_CATEGORY_HIT_RATE : THRESHOLD_HOT_HIT_RATE;
        if (rate < threshold) {
            log.warn("召回命中率低于阈值, route: {}, 实际: {}%, 阈值: {}%", route, Math.round(rate), threshold);
        }
    }

    /**
     * 指标汇总：每小时整点后 5 分钟执行
     *
     * <p>把上一小时的明细按「指标名 + 维度」聚合为一条汇总记录写入，明细保留由归档清理任务负责。
     */
    @Scheduled(cron = "0 5 * * * ?")
    public void aggregateHourly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime hourEnd = now.withMinute(0).withSecond(0).withNano(0);
        LocalDateTime hourStart = hourEnd.minusHours(1);
        try {
            List<PipelineMetricDO> aggregated = pipelineMetricMapper.aggregateBetween(hourStart, hourEnd);
            if (aggregated.isEmpty()) {
                return;
            }
            for (PipelineMetricDO metric : aggregated) {
                PipelineMetricDO summary = new PipelineMetricDO();
                summary.setMetricName(metric.getMetricName());
                summary.setMetricDim(metric.getMetricDim());
                summary.setMetricValue(metric.getMetricValue());
                summary.setStatTime(hourEnd);
                pipelineMetricMapper.insert(summary);
            }
            log.info("指标汇总完成, 区间: {} ~ {}, 汇总条数: {}", hourStart, hourEnd, aggregated.size());
        } catch (Exception e) {
            log.error("指标汇总失败，由下一周期补偿", e);
        }
    }
}
