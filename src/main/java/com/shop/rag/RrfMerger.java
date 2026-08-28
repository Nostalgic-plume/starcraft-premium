package com.shop.rag;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF（Reciprocal Rank Fusion）合并工具
 * <p>
 * 将多路检索结果按 RRF 公式合并排序，双路独立排序后按 rank 位置加权：
 * score = 1 / (k + rank_vector) + 1 / (k + rank_keyword)
 * <p>
 * k=60 为业界常用常数，对 rank 位置变化不敏感，适合向量+关键词异源融合。
 *
 * @author shop
 */
public class RrfMerger {

    private static final int K = 60;

    /**
     * 合并双路检索结果，按 RRF 分数降序排列
     *
     * @param list1 第一路结果（向量检索，已按相关性排序）
     * @param list2 第二路结果（关键词检索，已按相关性排序）
     * @param topK  返回前 K 条
     * @return 合并后按 RRF 分数降序排列的结果
     */
    public static List<RagHitVO> merge(List<RagHitVO> list1, List<RagHitVO> list2, int topK) {
        if (list1 == null || list1.isEmpty()) {
            return list2 == null ? List.of() : list2.stream().limit(topK).collect(Collectors.toList());
        }
        if (list2 == null || list2.isEmpty()) {
            return list1.stream().limit(topK).collect(Collectors.toList());
        }

        // productId → RagHitVO（保留第一个出现的，即向量结果优先）
        Map<Long, RagHitVO> voMap = new LinkedHashMap<>();
        // productId → RRF 累加分数
        Map<Long, Double> rrfScores = new HashMap<>();

        // 第一路：向量检索结果（rank = index）
        for (int i = 0; i < list1.size(); i++) {
            RagHitVO vo = list1.get(i);
            Long id = vo.getProductId();
            if (id == null) continue;
            voMap.putIfAbsent(id, vo);
            rrfScores.merge(id, 1.0 / (K + i), Double::sum);
        }

        // 第二路：关键词检索结果
        for (int i = 0; i < list2.size(); i++) {
            RagHitVO vo = list2.get(i);
            Long id = vo.getProductId();
            if (id == null) continue;
            voMap.putIfAbsent(id, vo);
            rrfScores.merge(id, 1.0 / (K + i), Double::sum);
        }

        // 按 RRF 分数降序排列，取 Top-K
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RagHitVO vo = voMap.get(entry.getKey());
                    if (vo != null) {
                        // 保留两位小数
                        vo.setScore((double) Math.round(entry.getValue() * 100) / 100);
                    }
                    return vo;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
