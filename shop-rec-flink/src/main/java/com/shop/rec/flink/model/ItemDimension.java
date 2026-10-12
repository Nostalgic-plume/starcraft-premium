package com.shop.rec.flink.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 商品维度映射
 *
 * <p>
 * 由商品标识关联出二级类目标识与品牌标识，是「一条行为事件展开为类目维度与品牌维度两条记录」
 * 的依据（对应详细设计 3.2 处理步骤 3）。
 *
 * <p>
 * 数据源为内存库的商品可售状态键（{@code rec:item:state:{itemId}}，由 M-04 维护）；
 * 作业内按周期刷新的本地缓存持有，避免每条事件都访问内存库。
 *
 * @author shop
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemDimension implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品标识 */
    private Long itemId;

    /** 二级类目标识 */
    private Long categoryId;

    /** 品牌标识 */
    private Long brandId;
}
