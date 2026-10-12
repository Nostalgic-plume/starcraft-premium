package com.shop.rag.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 离线评估指标公式测试（不依赖任何外部服务，CI 可跑）
 */
class RagMetricsTest {

    @Test
    void recallAtK_shouldCountExpectedInTopK() {
        List<Long> expected = List.of(3L, 5L);
        List<Long> retrieved = List.of(1L, 3L, 2L, 5L, 7L);

        assertEquals(1.0, RagMetrics.recallAtK(expected, retrieved, 5), 1e-9);   // top-5 两个期望都命中
        assertEquals(0.5, RagMetrics.recallAtK(expected, retrieved, 3), 1e-9);   // top-3 只命中 3
        assertEquals(0.0, RagMetrics.recallAtK(expected, List.of(9L), 5), 1e-9); // 无命中
    }

    @Test
    void mrr_shouldAverageReciprocalRanks() {
        List<Long> expected = List.of(3L, 5L);
        List<Long> retrieved = List.of(1L, 3L, 2L, 5L, 7L);

        // 3 排第 2 → 1/2；5 排第 4 → 1/4；平均 (0.5+0.25)/2 = 0.375
        assertEquals(0.375, RagMetrics.mrr(expected, retrieved), 1e-9);
        // 全部未命中 → 0
        assertEquals(0.0, RagMetrics.mrr(expected, List.of(9L, 8L)), 1e-9);
    }

    @Test
    void emptyOrNullExpected_shouldReturnZero() {
        assertEquals(0.0, RagMetrics.recallAtK(List.of(), List.of(1L), 5), 1e-9);
        assertEquals(0.0, RagMetrics.mrr(List.of(), List.of(1L)), 1e-9);
        assertEquals(0.0, RagMetrics.recallAtK(null, null, 5), 1e-9);
        assertEquals(0.0, RagMetrics.mrr(null, null), 1e-9);
    }
}
