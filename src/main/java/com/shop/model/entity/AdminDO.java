package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员实体（独立表，脱离 user 表）
 * 管理员使用独立账号密码登录，admin_id 即 Sa-Token loginId
 */
@Data
@TableName("admin")
@Schema(description = "管理员")
public class AdminDO {

    @TableId(type = IdType.AUTO)
    @Schema(description = "管理员ID")
    private Long id;

    @Schema(description = "登录用户名")
    private String username;

    @Schema(description = "登录密码（BCrypt加密）")
    private String password;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "级别：1普通 2超级")
    private Integer adminLevel;

    @Schema(description = "状态：0正常 1停用")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
