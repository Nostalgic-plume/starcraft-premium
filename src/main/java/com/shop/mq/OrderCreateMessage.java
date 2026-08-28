package com.shop.mq;

import lombok.Data;

import java.util.List;

/**
 * 异步落单消息体
 * <p>
 * 下单接口验证通过后，将订单数据发到 RocketMQ，由消费端异步完成 DB 写入。
 * orderToken 作为幂等键，保证消息重复投递时不会生成重复订单。
 * </p>
 *
 * @author shop
 */
@Data
public class OrderCreateMessage {

    /** 下单防重令牌（幂等键，消费端用 SETNX 去重） */
    private String orderToken;
    /** 用户 ID */
    private Long userId;
    /** 商品明细 */
    private List<ItemDTO> items;
    /** 收货人 */
    private String receiverName;
    /** 收货电话 */
    private String receiverPhone;
    /** 收货地址 */
    private String receiverAddress;
    /** 备注 */
    private String remark;

    @Data
    public static class ItemDTO {
        private Long skuId;
        private Integer quantity;
    }
}
