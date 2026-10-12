package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.PipelineMetricDO;

/**
 * 链路指标 Mapper（推荐库）
 * 提供指标表（t_pipeline_metric）的基础 CRUD，供 M-10 指标落库与看板查询使用
 *
 * @author shop
 */
public interface PipelineMetricMapper extends BaseMapper<PipelineMetricDO> {
}
