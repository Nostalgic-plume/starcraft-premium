package com.shop.rec.flink.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 偏好分结果项
 *
 * <p>
 * 表示「某维度取值（类目 / 品牌 / 商品）的偏好分」，用于在算子内部传递窗口汇总结果，
 * 以及写入内存库有序集合时的成员与分值。
 *
 * @author shop
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrefScore implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 维度取值：类目标识 / 品牌标识 / 商品标识 */
    private long dimValue;

    /** 偏好分 */
    private double score;
}
