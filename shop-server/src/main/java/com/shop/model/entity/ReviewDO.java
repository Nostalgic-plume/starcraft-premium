package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评价实体
 * <p>
 * 一个订单项只能评价一次（uk_order_item 唯一索引）；
 * product_name / product_image 是下单时的商品快照，商品后续改名不影响已发表评价。
 *
 * @author shop
 */
@Data
@TableName("review")
@Schema(description = "商品评价")
public class ReviewDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "评价用户 ID")
    private Long userId;

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单项 ID（唯一）")
    private Long orderItemId;

    @Schema(description = "商品 ID")
    private Long productId;

    @Schema(description = "SKU ID")
    private Long skuId;

    @Schema(description = "商品名快照")
    private String productName;

    @Schema(description = "商品主图快照")
    private String productImage;

    @Schema(description = "评分 1-5")
    private Integer rating;

    @Schema(description = "评价内容")
    private String content;

    @Schema(description = "晒图 URL 数组（JSON 字符串）")
    private String images;

    @Schema(description = "是否匿名：0否 1是")
    private Integer isAnonymous;

    @Schema(description = "状态：0隐藏 1显示")
    private Integer status;

    @Schema(description = "商家回复")
    private String reply;

    @Schema(description = "回复时间")
    private LocalDateTime replyTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
