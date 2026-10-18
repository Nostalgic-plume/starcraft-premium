package com.shop.rag.search;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF（Reciprocal Rank Fusion）合并工具
 * <p>
 * 将多路检索结果按 RRF 公式合并排序，双路独立排序后按 rank 位置加权：
 * score = wv / (k + rank_vector) + wk / (k + rank_keyword)
 * <p>
 * k=60 为业界常用常数，对 rank 位置变化不敏感，适合向量+关键词异源融合。
 * 权重与 k 由 {@link RrfProperties} 外置配置，默认等权（1.0 / 1.0）时与加权改造前行为完全一致；
 * {@link #merge(List, List, int)} 三参重载保留历史签名，供既有调用与测试使用。
 * <p>
 * 单路为空时同样按 RRF 公式给分（{@code w/(k+rank)}），只是不加另一路的贡献。
 * 这样 {@link RagCandidate#score()} 在**所有检索路径上语义一致**（RRF 排名分数），
 * 不会出现「双路命中是 0.02、单路透传却是 0.87 余弦相似度」的尺度跳变。
 * 顺序保持原路顺序（RRF 分数随 rank 单调递减，等价于顺序不变）。
 * <p>
 * （2026-10-18 前的行为是「单路原样透传原始分数」，shop-ai Python 侧曾记为有意分歧；
 * 该 Python 工程已暂停，本次统一为同一尺度。）
 * <p>
 * 融合阶段只需要 {@link RagCandidate}（商品 ID + 分数）——名称、图片、描述等展示数据
 * 不参与排序，由 {@link RagHitEnricher} 在选出 topK 之后统一回查。
 *
 * @author shop
 */
public class RrfMerger {

    /** 默认 RRF 平滑常数 */
    public static final int DEFAULT_K = 60;

    /** 默认权重（两路等权） */
    public static final double DEFAULT_WEIGHT = 1.0;

    /**
     * 合并双路检索结果，按 RRF 分数降序排列（默认 k=60、两路等权）
     *
     * @param list1 第一路结果（向量检索，已按相关性排序）
     * @param list2 第二路结果（关键词检索，已按相关性排序）
     * @param topK  返回前 K 条
     * @return 合并后按 RRF 分数降序排列的候选
     */
    public static List<RagCandidate> merge(List<RagCandidate> list1, List<RagCandidate> list2, int topK) {
        return merge(list1, list2, topK, DEFAULT_K, DEFAULT_WEIGHT, DEFAULT_WEIGHT);
    }

    /**
     * 合并双路检索结果，按加权 RRF 分数降序排列
     *
     * @param list1         第一路结果（向量检索，已按相关性排序）
     * @param list2         第二路结果（关键词检索，已按相关性排序）
     * @param topK          返回前 K 条
     * @param k             RRF 平滑常数（&lt;= 0 时兜底为 1，避免除零）
     * @param weightVector  向量路权重（&gt;= 0）
     * @param weightKeyword 关键词路权重（&gt;= 0）
     * @return 合并后按加权 RRF 分数降序排列的候选
     */
    public static List<RagCandidate> merge(List<RagCandidate> list1, List<RagCandidate> list2, int topK,
                                           int k, double weightVector, double weightKeyword) {
        // 兜底：k <= 0 会让 1/(k+rank) 除零或取负，配置层已校验，此处再防一层
        int safeK = Math.max(k, 1);

        // 单路时也按 RRF 公式给分（而非透传原始分数），保证 score 语义跨路径一致
        if (list1 == null || list1.isEmpty()) {
            return singlePath(list2, topK, safeK, weightKeyword);
        }
        if (list2 == null || list2.isEmpty()) {
            return singlePath(list1, topK, safeK, weightVector);
        }

        // productId → 候选（保留第一个出现的，即向量路结果优先）
        Map<Long, RagCandidate> candidateMap = new LinkedHashMap<>();
        // productId → 加权 RRF 累加分数
        Map<Long, Double> rrfScores = new HashMap<>();

        // 第一路：向量检索结果（rank = index）
        for (int i = 0; i < list1.size(); i++) {
            RagCandidate candidate = list1.get(i);
            if (candidate == null || candidate.productId() == null) continue;
            candidateMap.putIfAbsent(candidate.productId(), candidate);
            rrfScores.merge(candidate.productId(), weightVector / (safeK + i), Double::sum);
        }

        // 第二路：关键词检索结果
        for (int i = 0; i < list2.size(); i++) {
            RagCandidate candidate = list2.get(i);
            if (candidate == null || candidate.productId() == null) continue;
            candidateMap.putIfAbsent(candidate.productId(), candidate);
            rrfScores.merge(candidate.productId(), weightKeyword / (safeK + i), Double::sum);
        }

        // 按 RRF 分数降序排列，取 Top-K，并以融合后的分数重建候选
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> new RagCandidate(entry.getKey(),
                        (double) Math.round(entry.getValue() * 100) / 100))
                .collect(Collectors.toList());
    }

    /**
     * 单路结果的 RRF 打分
     * <p>
     * 该路候选已按自身相关性排好序（向量路按相似度、关键词路按 MATCH 相关度），
     * 因此按 rank 计算 {@code weight/(k+rank)} 即可 —— 顺序不变，只统一分数尺度。
     * <p>
     * 与双路分支的差异：不去重（单路本就没有重复）、跳过 null 候选（下游富化也会跳过）。
     *
     * @param list   已排序的单路候选（可为 null / 空）
     * @param topK   返回条数上限
     * @param safeK  已兜底的 RRF 常数（≥1）
     * @param weight 该路的权重
     * @return 按原顺序、以 RRF 分数重建的候选
     */
    private static List<RagCandidate> singlePath(List<RagCandidate> list, int topK, int safeK, double weight) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RagCandidate> result = new ArrayList<>(Math.min(list.size(), topK));
        for (int i = 0; i < list.size() && result.size() < topK; i++) {
            RagCandidate candidate = list.get(i);
            if (candidate == null) {
                continue;
            }
            double score = weight / (safeK + i);
            result.add(new RagCandidate(candidate.productId(),
                    (double) Math.round(score * 100) / 100));
        }
        return result;
    }
}
