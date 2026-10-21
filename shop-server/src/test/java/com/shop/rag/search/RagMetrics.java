package com.shop.rag.search;

import java.util.List;

/**
 * RAG 检索评估指标工具（离线评估集用）
 * <ul>
 *   <li>Recall@K：期望商品在检索 top-K 中被命中的比例（衡量召回）</li>
 *   <li>MRR（Mean Reciprocal Rank）：期望商品在结果中倒数排名的均值（衡量排序质量）</li>
 *   <li>Hit@K：top-K 中**至少命中一个**期望商品（0/1，衡量「用户在前 K 条里能不能看到对的东西」）</li>
 *   <li>MRR@FirstHit：首个命中位置的倒数（标准单 query MRR）</li>
 * </ul>
 * 公式独立、无外部依赖，供 {@link RagMetricsTest}（离线）与 {@link RagEvaluationTest}（真实）共用。
 * <p>
 * ⚠️ 期望集来源决定该用哪个指标：
 * <ul>
 *   <li>人工标注的少量精确期望（≤5 条）→ Recall@K / {@link #mrr} 都合理；</li>
 *   <li>「同类商品全集」（如「手机」对应全部 12 个手机 SPU）→ Recall@K 会被期望集大小稀释
 *       （top-5 最多召回 5 个，期望 40 个时 Recall 上限只有 0.125），此时应看
 *       {@link #hitAtK} 与 {@link #mrrAtFirstHit}。</li>
 * </ul>
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

    /**
     * Hit@K：top-K 中是否至少命中一个期望商品
     * <p>
     * 对「同类商品全集」形式的期望集鲁棒 —— 重复商品有多个 ID 时，
     * 命中其中任意一个即视为该 query 成功，不会被期望集大小稀释。
     *
     * @return 命中 1.0，未命中 0.0
     */
    public static double hitAtK(List<Long> expectedIds, List<Long> retrievedIds, int k) {
        if (expectedIds == null || expectedIds.isEmpty() || retrievedIds == null || retrievedIds.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            if (expectedIds.contains(retrievedIds.get(i))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /**
     * MRR@FirstHit：首个命中位置的倒数（标准单 query MRR）
     * <p>
     * 与 {@link #mrr} 的区别：只奖励**第一个**命中的期望商品，更适合「同类商品全集」——
     * 否则期望集越大、分母越大，指标越低，无法横向比较。
     *
     * @return 1/rank；全未命中为 0
     */
    public static double mrrAtFirstHit(List<Long> expectedIds, List<Long> retrievedIds) {
        if (expectedIds == null || expectedIds.isEmpty() || retrievedIds == null) {
            return 0.0;
        }
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (expectedIds.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
