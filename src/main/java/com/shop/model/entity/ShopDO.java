package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 店铺实体
 * 独立于 user 表，商家用户关联到 shop（一个用户可以拥有多个店铺）
 * product.shop_id 和 orders.shop_id 均引用此表 id
 */
@Data
@TableName("shop")
@Schema(description = "店铺")
public class ShopDO {

    @TableId(type = IdType.AUTO)
    @Schema(description = "店铺ID")
    private Long id;

    @Schema(description = "所属用户ID（店主，历史遗留）")
    private Long userId;

    @Schema(description = "商家登录用户名")
    private String username;

    @Schema(description = "登录密码（BCrypt加密）")
    private String password;

    @Schema(description = "店铺名称")
    private String shopName;

    @Schema(description = "店铺logo URL")
    private String shopLogo;

    @Schema(description = "店铺描述")
    private String shopDesc;

    @Schema(description = "联系人")
    private String contactName;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "状态：1正常 0关闭")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
