package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发表评价参数
 * <p>
 * 只接收订单项 ID：商品、用户、订单归属全部由服务端反查与校验，
 * 不接受前端传 productId / userId，避免伪造评价对象。
 *
 * @author shop
 */
@Data
@Schema(description = "发表评价参数")
public class ReviewCreateDTO {

    @NotNull(message = "订单项不能为空")
    @Schema(description = "订单项 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderItemId;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    @Schema(description = "评分 1-5", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer rating;

    @Size(max = 500, message = "评价内容不能超过 500 个字符")
    @Schema(description = "评价内容")
    private String content;

    @Size(max = 6, message = "最多上传 6 张图片")
    @Schema(description = "晒图 URL 列表")
    private List<String> images;

    @Schema(description = "是否匿名：0否 1是，默认 0")
    private Integer isAnonymous;
}
