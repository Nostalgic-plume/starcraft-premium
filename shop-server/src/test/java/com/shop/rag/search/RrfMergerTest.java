package com.shop.rag.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RrfMerger 加权融合单元测试（纯离线，不依赖 Spring / Qdrant / MySQL / 嵌入服务）
 * <p>
 * 覆盖：默认权重与旧三参重载等价（回归保护）、权重可翻转排序、k 参数生效、
 * k 非法值兜底、单路也按 RRF 公式给分（score 尺度统一）。
 *
 * @author shop
 */
@DisplayName("RrfMerger - 加权 RRF 融合")
class RrfMergerTest {

    private static RagCandidate candidate(long id) {
        return new RagCandidate(id, 0.0);
    }

    private static RagCandidate candidate(long id, double score) {
        return new RagCandidate(id, score);
    }

    private static List<Long> ids(List<RagCandidate> candidates) {
        return candidates.stream().map(RagCandidate::productId).toList();
    }

    @Test
    @DisplayName("默认权重与三参重载结果完全一致（回归保护）")
    void defaultWeightsShouldMatchLegacyOverload() {
        List<RagCandidate> vector = List.of(candidate(1), candidate(2), candidate(3));
        List<RagCandidate> keyword = List.of(candidate(3), candidate(4));

        List<RagCandidate> legacy = RrfMerger.merge(vector, keyword, 5);
        List<RagCandidate> explicit = RrfMerger.merge(vector, keyword, 5,
                RrfMerger.DEFAULT_K, RrfMerger.DEFAULT_WEIGHT, RrfMerger.DEFAULT_WEIGHT);

        assertEquals(ids(legacy), ids(explicit), "三参重载必须与显式默认参数等价");
        // 3 在两路都命中（rank 2 + rank 0），必然排第一；1（向量 rank 0）次之
        assertEquals(List.of(3L, 1L), ids(legacy).subList(0, 2));
        assertEquals(4, legacy.size());
    }

    @Test
    @DisplayName("调高关键词权重后，仅关键词路命中的商品反超向量路头部")
    void keywordWeightShouldFlipOrdering() {
        List<RagCandidate> vector = List.of(candidate(1));
        // 关键词路 rank 2 才是商品 2（rank 0/1 为占位商品），故等权时 1/60 > 1/62
        List<RagCandidate> keyword = List.of(candidate(8), candidate(9), candidate(2));

        // 断言相对位次而非绝对第一名：rank 0/1 的占位商品同样在关键词路计分，必然排在商品 2 之前
        List<Long> equalIds = ids(RrfMerger.merge(vector, keyword, 5, 60, 1.0, 1.0));
        assertTrue(equalIds.indexOf(1L) < equalIds.indexOf(2L), "等权时向量路 rank0 应领先关键词路 rank2");

        List<Long> keywordHeavierIds = ids(RrfMerger.merge(vector, keyword, 5, 60, 1.0, 1.5));
        assertTrue(keywordHeavierIds.indexOf(2L) < keywordHeavierIds.indexOf(1L),
                "关键词权重 1.5 时应反超（1.5/62 > 1/60）");
    }

    @Test
    @DisplayName("k 参数生效：k 越小同名次分数越高")
    void kShouldChangeScoreMagnitude() {
        List<RagCandidate> vector = List.of(candidate(1));
        List<RagCandidate> keyword = List.of(candidate(2));

        // 两路各命中一条且 rank 均为 0，分数同为 w/(k+0)
        assertEquals(0.02, RrfMerger.merge(vector, keyword, 5, 60, 1.0, 1.0).get(0).score(), 1e-9);
        assertEquals(0.05, RrfMerger.merge(vector, keyword, 5, 20, 1.0, 1.0).get(0).score(), 1e-9);
    }

    @Test
    @DisplayName("k 非法（0）时兜底，不产生 Infinity / NaN 分数")
    void illegalKShouldFallback() {
        List<RagCandidate> result = RrfMerger.merge(List.of(candidate(1)), List.of(candidate(2)),
                5, 0, 1.0, 1.0);

        double score = result.get(0).score();
        assertTrue(Double.isFinite(score) && score > 0, "k=0 时应兜底为 1，分数有限且为正");
    }

    @Test
    @DisplayName("单路也按 RRF 公式给分：顺序不变，分数不再透传余弦相似度")
    void singlePathShouldUseRrfScore() {
        RagCandidate onlyVector = candidate(1, 0.87);

        List<RagCandidate> result = RrfMerger.merge(List.of(onlyVector), List.of(), 5, 60, 1.0, 1.5);

        assertEquals(List.of(1L), ids(result), "单路顺序保持不变");
        // 向量路权重 1.0 / (60 + rank 0) = 0.0167 → 四舍五入 0.02
        assertEquals(0.02, result.get(0).score(), 1e-9,
                "单路必须与双路同一尺度；透传 0.87 会让 score 在降级时跳变一个数量级");
    }

    @Test
    @DisplayName("单路分数只由名次决定，与原始分数无关")
    void singlePathScoreDependsOnRankOnly() {
        List<RagCandidate> high = RrfMerger.merge(List.of(candidate(1, 0.95)), List.of(), 5, 60, 1.0, 1.0);
        List<RagCandidate> low = RrfMerger.merge(List.of(candidate(1, 0.31)), List.of(), 5, 60, 1.0, 1.0);

        assertEquals(high.get(0).score(), low.get(0).score(), 1e-9,
                "原始分数（余弦 / 位次模拟）不再参与，避免两路尺度混用");
    }
}
