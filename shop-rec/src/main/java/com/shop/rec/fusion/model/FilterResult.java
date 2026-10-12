package com.shop.rec.fusion.model;

import com.shop.rec.enums.DegradeState;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合过滤结果
 *
 * @author shop
 */
@Data
public class FilterResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 召回候选池：已去重、已过滤、必要时已用兜底名单补位 */
    private List<FilteredCandidate> candidates = Collections.emptyList();

    /** 各过滤原因的命中数量，供链路指标与排查使用 */
    private Map<String, Integer> filterStats = new LinkedHashMap<>();

    /** 本环节命中的降级状态；候选池为空时为「兜底名单」 */
    private DegradeState degradeState = DegradeState.NORMAL;
}
