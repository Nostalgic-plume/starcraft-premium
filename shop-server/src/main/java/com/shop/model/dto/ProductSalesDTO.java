package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 商品销量统计结果（批量查询用）
 * <p>
 * 供商品列表页整页取销量：逐条调用 {@code sumQuantityByProductId} 会让单次分页
 * 产生 N 次查询（pageSize 上限 200 → 最多 200 次），故提供 GROUP BY 的批量版本。
 *
 * @author shop
 */
@Data
@Schema(description = "商品销量统计")
public class ProductSalesDTO {

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "累计销量（已完成订单的有效销量）")
    private Integer total;
}
