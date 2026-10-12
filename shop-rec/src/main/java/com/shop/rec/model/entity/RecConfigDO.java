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
 * 推荐策略配置实体（M-09 写入）
 *
 * <p>
 * 配置的权威来源：运营在管理后台提交配置后落库到本表，再同步至内存库副本
 * （rec:cfg:{configKey}）供各模块热更新读取。各模块每 30 秒比对配置版本号发现变化。
 *
 * @author shop
 */
@Data
@TableName("t_rec_config")
@Schema(description = "推荐策略配置")
public class RecConfigDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键标识")
    private Long id;

    @Schema(description = "配置键")
    private String configKey;

    @Schema(description = "配置值")
    private String configValue;

    @Schema(description = "值类型：int/decimal/string/json")
    private String valueType;

    @Schema(description = "配置分组：行为权值、窗口权值、召回、打分、缓存、开关")
    private String groupName;

    @Schema(description = "配置说明")
    private String remark;

    @Schema(description = "最后修改人")
    private String operator;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "最后修改时间")
    private LocalDateTime updateTime;
}
