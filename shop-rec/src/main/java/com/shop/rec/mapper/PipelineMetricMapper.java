package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.PipelineMetricDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 链路指标 Mapper（推荐库）
 * 提供指标表（t_pipeline_metric）的基础 CRUD，供 M-10 指标落库与看板查询使用
 *
 * @author shop
 */
public interface PipelineMetricMapper extends BaseMapper<PipelineMetricDO> {

    /** 运行看板单次查询的条数上限：防止一次拉取过多明细把响应撑大 */
    int MAX_QUERY_LIMIT = 500;

    /**
     * 按时间区间聚合指标（每小时汇总任务使用）
     *
     * <p>对应详细设计 3.10「指标每小时汇总一次」：把区间内的明细按「指标名 + 维度」聚合为一条，
     * 计数类指标求和、比率类指标由调用方结合样本数换算。
     *
     * @param from 起始时间（含）
     * @param to   结束时间（不含）
     * @return 聚合后的指标列表
     */
    @Select("SELECT metric_name, metric_dim, SUM(metric_value) AS metric_value "
            + "FROM t_pipeline_metric WHERE stat_time >= #{from} AND stat_time < #{to} "
            + "GROUP BY metric_name, metric_dim")
    List<PipelineMetricDO> aggregateBetween(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    /**
     * 按时间区间查询指标明细（运行看板用）
     *
     * <p>看板要看的是「最近 N 小时内各指标随时间的变化」，故返回按统计时间降序的明细：
     * 明细本身已是每小时一条（写入时统计时间截到整点），区间内条数可控。
     *
     * @param from       起始时间（含）
     * @param to         结束时间（不含）
     * @param metricName 指标名，为空表示不限
     * @param limit      返回条数上限，内部钳制到 [1, MAX_QUERY_LIMIT]
     * @return 指标明细列表（按统计时间降序）
     */
    default List<PipelineMetricDO> selectByRange(LocalDateTime from, LocalDateTime to,
                                                 String metricName, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        return selectList(new LambdaQueryWrapper<PipelineMetricDO>()
                .ge(PipelineMetricDO::getStatTime, from)
                .lt(PipelineMetricDO::getStatTime, to)
                .eq(StringUtils.hasText(metricName), PipelineMetricDO::getMetricName, metricName)
                .orderByDesc(PipelineMetricDO::getStatTime)
                // LIMIT 取值已钳制为区间内的整数，不构成注入面
                .last("LIMIT " + size));
    }
}
