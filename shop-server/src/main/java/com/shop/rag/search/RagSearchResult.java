package com.shop.rag.search;

import java.util.List;

/**
 * 混合检索结果（含可售性信息）
 * <p>
 * 在命中列表之外额外携带 {@link #allOutOfStock}，供对话链路区分两种「空结果」：
 * <ul>
 *   <li>{@code hits} 为空 + {@code allOutOfStock = false} —— 真的没搜到（走「没找到，您想找什么」引导话术）</li>
 *   <li>{@code hits} 为空 + {@code allOutOfStock = true} —— 有匹配商品但全部售罄被剔除（走「暂时缺货」话术）</li>
 * </ul>
 * <p>
 * 该标记仅在「过滤前候选非空 且 过滤后为空」时为 true；候选本身为空时恒为 false，
 * 避免把「没搜到」误报成「缺货」。
 *
 * @param hits          过滤后的命中列表（按加权 RRF 分数降序，可能少于请求的 topK，属预期）
 * @param allOutOfStock 是否因候选全部售罄而被剔除为空
 * @author shop
 */
public record RagSearchResult(List<RagHitVO> hits, boolean allOutOfStock) {

    /** 紧凑构造器：命中列表归一化为空列表，避免调用方处理 null */
    public RagSearchResult {
        hits = hits == null ? List.of() : hits;
    }
}
