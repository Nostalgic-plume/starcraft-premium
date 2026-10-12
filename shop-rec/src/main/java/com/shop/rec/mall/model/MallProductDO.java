package com.shop.rec.mall.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商城商品只读视图
 *
 * <p>
 * 推荐侧读取商城商品数据的最小字段集：仅包含推荐所需的类目、品牌、上下架状态与展示字段，
 * 不读取商品描述、详情等与推荐无关的内容（对应概要设计 2.2「仅采集推荐必需的字段」）。
 *
 * @author shop
 */
@Data
public class MallProductDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品标识 */
    private Long id;

    /** 商品名称（榜单展示用） */
    private String name;

    /** 二级类目标识（类目偏好聚合维度） */
    private Long categoryId;

    /** 品牌标识（品牌偏好聚合维度） */
    private Long brandId;

    /** 店铺标识 */
    private Long shopId;

    /** 商品主图（榜单展示用） */
    private String mainImage;

    /** 上下架状态：0 待审核，1 上架，2 下架 */
    private Integer status;

    /** 商品更新时间（对账用） */
    private LocalDateTime updateTime;
}
