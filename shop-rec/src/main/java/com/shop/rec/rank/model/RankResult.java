package com.shop.rec.rank.model;

import com.shop.rec.enums.DegradeState;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 榜单重排结果
 *
 * @author shop
 */
@Data
public class RankResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 最终榜单（已含固定坑位注入），顺序稳定 */
    private List<RankedItem> items = Collections.emptyList();

    /** 本环节命中的降级状态；打分异常时为「特征缺失」 */
    private DegradeState degradeState = DegradeState.NORMAL;
}
