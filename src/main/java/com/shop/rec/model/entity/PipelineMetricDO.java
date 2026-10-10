package com.shop.rec.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 推荐链路指标实体（M-10 写入，全模块上报）
 *
 * <p>
 * 保存特征覆盖率、特征新鲜度、两路召回命中率、降级次数、各环节耗时等观测数据。
 * 写入约定：明细指标按事件写入，汇总指标每小时整点后 5 分钟写入一次；
 * 同一指标同一维度同一时间点仅保留一条汇总记录。
 *
 * @author shop
 */
@Data
@TableName("t_pipeline_metric")
@Schema(description = "推荐链路指标")
public class PipelineMetricDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键标识")
    private Long id;

    @Schema(description = "指标名称")
    private String metricName;

    @Schema(description = "维度取值，如降级状态、召回路由、丢弃原因")
    private String metricDim;

    @Schema(description = "指标值；计数类存次数，比率类存百分比")
    private BigDecimal metricValue;

    @Schema(description = "统计时间点，汇总指标取整点")
    private LocalDateTime statTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "入库时间")
    private LocalDateTime createTime;
}
