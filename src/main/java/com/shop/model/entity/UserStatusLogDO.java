package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号状态变更日志实体
 *
 * @author shop
 */
@Data
@TableName("user_status_log")
public class UserStatusLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被操作的用户 ID */
    private Long userId;

    /** 操作人 ID */
    private Long operatorId;

    /** 变更前状态：0正常 1停用 2注销中 3封禁中 */
    private Integer fromStatus;

    /** 变更后状态：0正常 1停用 2注销中 3封禁中 */
    private Integer toStatus;

    /** 变更原因 */
    private String reason;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
