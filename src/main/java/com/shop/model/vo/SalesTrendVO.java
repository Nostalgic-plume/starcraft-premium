package com.shop.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 销售趋势 VO
 *
 * @author shop
 */
@Data
@Schema(description = "销售趋势")
public class SalesTrendVO {

    @Schema(description = "日期")
    private String date;

    @Schema(description = "金额/数量")
    private Object amount;

    @Schema(description = "数量（用于订单分布/用户增长）")
    private Object cnt;

    public void setAmount(Object amount) { this.amount = amount; }
    public void setCnt(Object cnt) { this.cnt = cnt; }
}
