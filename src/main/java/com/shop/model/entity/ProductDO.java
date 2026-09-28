package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体（SPU）
 * 图片列表以 JSON 字符串存储，详情为富文本 HTML
 */
@Data
@TableName("product")
@Schema(description = "商品")
public class ProductDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "商品ID")
    private Long id;

    @Schema(description = "商品名称")
    private String name;

    @Schema(description = "商品描述")
    private String description;

    @Schema(description = "分类ID（所属二级分类）")
    private Long categoryId;

    @Schema(description = "一级分类ID")
    private Long firstCategoryId;

    @Schema(description = "品牌ID（关联 brand 表）")
    private Long brandId;

    @Schema(description = "主图URL")
    private String mainImage;

    @Schema(description = "轮播图（JSON数组）")
    private String images;

    @Schema(description = "详情内容（富文本）")
    private String detail;

    @Schema(description = "商家ID")
    private Long shopId;

    @Schema(description = "状态：0待审核 1上架 2下架")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
