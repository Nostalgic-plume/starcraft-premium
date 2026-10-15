package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 * 密码采用 BCrypt 加密存储，不保存明文
 */
@Data
@TableName("user")
@Schema(description = "用户")
public class UserDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    /**
     * 密码（BCrypt 加密存储）。
     * <p>
     * WRITE_ONLY：只允许反序列化写入、序列化时一律不输出。
     * 目的是防止 {@code /api/user/info}、{@code /api/user/list}、{@code /api/admin/users}
     * 等直接返回 UserDO 的接口把密码哈希泄露到前端。
     * 该实体不参与 Redis/JSON 反序列化，故不影响任何读回逻辑。
     * </p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Schema(description = "密码（BCrypt加密）")
    private String password;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像URL")
    private String avatar;

    @Schema(description = "状态：0正常 1停用 2注销中 3封禁中")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "逻辑删除：0未删 1已删")
    private Integer deleted;
}
