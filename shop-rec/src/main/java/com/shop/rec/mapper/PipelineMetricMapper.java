package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.PipelineMetricDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 链路指标 Mapper（推荐库）
 * 提供指标表（t_pipeline_metric）的基础 CRUD，供 M-10 指标落库与看板查询使用
 *
 * @author shop
 */
public interface PipelineMetricMapper extends BaseMapper<PipelineMetricDO> {

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
}
