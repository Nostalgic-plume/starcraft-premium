package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车实体
 * 一对多关系：一个用户可以有多个 SKU 的购物车记录
 * checked 字段用于批量结算时选择
 */
@Data
@TableName("cart")
@Schema(description = "购物车")
public class CartDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "SKU ID")
    private Long skuId;

    @Schema(description = "数量")
    private Integer quantity;

    @Schema(description = "是否选中：0未选 1选中")
    private Integer checked;

    @Schema(description = "是否已下单：0未下单 1已下单")
    private Integer isOrdered;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
