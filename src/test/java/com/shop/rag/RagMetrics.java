package com.shop.rag;

import java.util.List;

/**
 * RAG 检索评估指标工具（离线评估集用）
 * <ul>
 *   <li>Recall@K：期望商品在检索 top-K 中被命中的比例（衡量召回）</li>
 *   <li>MRR（Mean Reciprocal Rank）：期望商品在结果中倒数排名的均值（衡量排序质量）</li>
 * </ul>
 * 公式独立、无外部依赖，供 {@link RagMetricsTest}（离线）与 {@link RagEvaluationTest}（真实）共用。
 */
public final class RagMetrics {

    private RagMetrics() {
    }

    /** Recall@K：期望商品命中检索 top-K 的比例 */
    public static double recallAtK(List<Long> expectedIds, List<Long> retrievedIds, int k) {
        if (expectedIds == null || expectedIds.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, retrievedIds == null ? 0 : retrievedIds.size());
        List<Long> topK = retrievedIds == null ? List.of() : retrievedIds.subList(0, limit);
        long hit = expectedIds.stream().filter(topK::contains).count();
        return (double) hit / expectedIds.size();
    }

    /** MRR：期望商品在检索结果中的倒数排名均值 */
    public static double mrr(List<Long> expectedIds, List<Long> retrievedIds) {
        if (expectedIds == null || expectedIds.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Long id : expectedIds) {
            int rank = retrievedIds == null ? -1 : retrievedIds.indexOf(id);
            if (rank >= 0) {
                sum += 1.0 / (rank + 1);
            }
        }
        return sum / expectedIds.size();
    }
}
