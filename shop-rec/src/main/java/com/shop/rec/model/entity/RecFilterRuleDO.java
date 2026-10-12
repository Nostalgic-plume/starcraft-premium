package com.shop.rec.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐过滤规则实体（M-09 写入，M-06 读取）
 *
 * <p>
 * 承载黑名单、会员限定、地域限制、低质商品四类规则。其中会员限定与地域限制
 * 依赖商城侧当前不存在的字段、低质商品判定口径未定，此三项本期只保证规则通路
 * 可配置可热更新，不纳入验收；黑名单为已生效规则。
 *
 * @author shop
 */
@Data
@TableName("t_rec_filter_rule")
@Schema(description = "推荐过滤规则")
public class RecFilterRuleDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键标识")
    private Long id;

    @Schema(description = "规则类型：blacklist/member_only/region_limit/low_quality")
    private String ruleType;

    @Schema(description = "作用对象：item/category")
    private String targetType;

    @Schema(description = "对象取值：商品标识、类目标识或地区编码")
    private String targetValue;

    @Schema(description = "状态：0 停用，1 启用")
    private Integer status;

    @Schema(description = "规则说明")
    private String remark;

    @Schema(description = "维护人")
    private String operator;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
