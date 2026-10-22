package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价视图
 *
 * @author shop
 */
@Data
@Schema(description = "商品评价")
public class ReviewVO {

    private Long id;

    @Schema(description = "订单项 ID")
    private Long orderItemId;

    @Schema(description = "商品 ID")
    private Long productId;

    @Schema(description = "商品名（下单时快照）")
    private String productName;

    @Schema(description = "商品主图（下单时快照）")
    private String productImage;

    @Schema(description = "状态：0隐藏 1显示（管理端使用）")
    private Integer status;

    @Schema(description = "评分 1-5")
    private Integer rating;

    @Schema(description = "评价内容")
    private String content;

    @Schema(description = "晒图 URL 列表")
    private List<String> images;

    @Schema(description = "是否匿名")
    private Boolean anonymous;

    @Schema(description = "评价人昵称（匿名时为「匿名用户」）")
    private String nickname;

    @Schema(description = "商家回复")
    private String reply;

    @Schema(description = "回复时间")
    private LocalDateTime replyTime;

    @Schema(description = "评价时间")
    private LocalDateTime createTime;
}
