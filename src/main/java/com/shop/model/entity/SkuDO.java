package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品SKU实体
 * 规格维度：同一商品不同规格（颜色、尺寸等）
 */
@Data
@TableName("sku")
@Schema(description = "商品SKU")
public class SkuDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "SKU ID")
    private Long id;

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "SKU名称（如：红色-M码）")
    private String name;

    @Schema(description = "规格值")
    private String specs;

    @Schema(description = "价格")
    private BigDecimal price;

    @Schema(description = "原价")
    private BigDecimal originalPrice;

    @Schema(description = "库存")
    private Integer stock;

    @Schema(description = "图片URL（覆盖主图）")
    private String image;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
