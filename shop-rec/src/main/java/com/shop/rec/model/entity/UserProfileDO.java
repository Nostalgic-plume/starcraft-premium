package com.shop.rec.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shop.rec.model.vo.PrefItemVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户画像实体（M-03 写入）
 *
 * <p>
 * 保存用户长期偏好及其历史版本：同一用户同一 version 唯一，
 * status 标识当前有效版本（一个用户仅一个有效版本），支持版本回滚。
 * 通常保留最近 3 个版本，更早版本在画像任务写入新版本时同批裁剪。
 *
 * @author shop
 */
@Data
@TableName(value = "t_user_profile", autoResultMap = true)
@Schema(description = "用户画像（带版本）")
public class UserProfileDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键标识")
    private Long id;

    @Schema(description = "用户标识")
    private Long userId;

    @Schema(description = "版本号，取日期 yyyyMMdd")
    private Integer version;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "类目偏好列表，默认取前 5 名")
    private List<PrefItemVO> categoryPref;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "品牌偏好列表，默认取前 5 名")
    private List<PrefItemVO> brandPref;

    @Schema(description = "参与归纳的行为条数")
    private Integer behaviorCount;

    @Schema(description = "状态：0 有效，1 历史版本")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "生成时间")
    private LocalDateTime createTime;
}
