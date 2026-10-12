package com.shop.rec.recall.model;

import com.shop.rec.enums.DegradeState;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 多路召回结果
 *
 * <p>
 * 携带两路候选与本次召回过程命中的降级状态。降级状态在 M-08 统一按优先级裁决
 * （见详细设计 7.2），本类只如实记录本环节观察到的情况。
 *
 * @author shop
 */
@Data
public class RecallResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 类目偏好路候选（按类目偏好分取偏好类目后，逐类目取类目内热度 TopN） */
    private List<RecallCandidate> categoryCandidates = Collections.emptyList();

    /** 热门路候选（全局热度榜 TopN） */
    private List<RecallCandidate> hotCandidates = Collections.emptyList();

    /** 本环节命中的降级状态；无异常时为「正常」 */
    private DegradeState degradeState = DegradeState.NORMAL;

    /** 是否处于冷启动（无实时特征且无画像）：此时仅执行热门召回 */
    private boolean coldStart;

    /**
     * 两路候选总数
     *
     * @return 候选条数
     */
    public int totalSize() {
        return categoryCandidates.size() + hotCandidates.size();
    }
}
