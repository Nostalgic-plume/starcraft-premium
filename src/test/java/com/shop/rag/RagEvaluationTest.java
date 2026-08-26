package com.shop.rag;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线评估集（真实混合检索）：30 条人工标注 (query → 期望商品ID)，计算 Recall@K 与 MRR。
 *
 * 依赖：Qdrant 运行 + 商品向量索引存在 + DashScope API Key 可用（真实混合检索链路）。
 * 环境缺失时自动跳过（assumeTrue），不污染 CI。答辩环境启动 Qdrant 后运行即可复现指标。
 */
@SpringBootTest
class RagEvaluationTest {

    @Autowired
    private RagSearchService ragSearchService;

    private record TestCase(String query, List<Long> expectedIds) {
    }

    /** 30 条人工标注评估集（基于当前 mock 商品数据，2026-08 标注） */
    private static final List<TestCase> CASES = List.of(
            new TestCase("手机", List.of(1L, 12L, 16L)),
            new TestCase("华为", List.of(1L)),
            new TestCase("苹果手机", List.of(16L, 31L)),
            new TestCase("小米", List.of(12L, 27L, 14L)),
            new TestCase("vivo", List.of(25L)),
            new TestCase("微单相机", List.of(17L, 21L)),
            new TestCase("拍立得", List.of(35L)),
            new TestCase("运动鞋", List.of(20L, 47L)),
            new TestCase("羽绒服", List.of(39L)),
            new TestCase("冲锋衣", List.of(42L)),
            new TestCase("口红", List.of(2L, 29L, 30L, 43L)),
            new TestCase("面霜", List.of(7L, 36L)),
            new TestCase("精华", List.of(33L)),
            new TestCase("空调", List.of(3L, 6L)),
            new TestCase("洗衣机", List.of(4L)),
            new TestCase("冰箱", List.of(8L)),
            new TestCase("吸尘器", List.of(19L)),
            new TestCase("扫地机器人", List.of(44L)),
            new TestCase("电饭煲", List.of(14L)),
            new TestCase("跑步机", List.of(50L)),
            new TestCase("水果", List.of(10L, 18L, 52L)),
            new TestCase("车厘子", List.of(11L)),
            new TestCase("沙发", List.of(9L)),
            new TestCase("床", List.of(15L)),
            new TestCase("耳机", List.of(31L)),
            new TestCase("打游戏手机", List.of(12L, 16L, 53L)),
            new TestCase("拍照手机", List.of(1L, 16L, 25L)),
            new TestCase("进口水果", List.of(11L, 52L)),
            new TestCase("手鸡", List.of(1L, 12L, 16L)),  // 错别字，测 FULLTEXT ngram 容错
            new TestCase("剃须刀", List.of(34L))
    );

    @BeforeAll
    static void requireQdrant() {
        Assumptions.assumeTrue(qdrantReachable(), "Qdrant 未运行，跳过真实评估");
    }

    @Test
    void evaluateHybridSearch() {
        double recall3 = 0, recall5 = 0, mrr = 0;
        for (TestCase tc : CASES) {
            List<RagHitVO> hits = ragSearchService.search(tc.query(), 5, null, null, null);
            List<Long> retrieved = hits.stream().map(RagHitVO::getProductId).toList();
            double r3 = RagMetrics.recallAtK(tc.expectedIds(), retrieved, 3);
            double r5 = RagMetrics.recallAtK(tc.expectedIds(), retrieved, 5);
            double m = RagMetrics.mrr(tc.expectedIds(), retrieved);
            recall3 += r3;
            recall5 += r5;
            mrr += m;
            System.out.printf("[评估] query=%-16s Recall@3=%.2f Recall@5=%.2f MRR=%.3f 命中=%s%n",
                    tc.query(), r3, r5, m, retrieved);
        }
        recall3 /= CASES.size();
        recall5 /= CASES.size();
        mrr /= CASES.size();
        System.out.printf("%n========== 评估结果（%d 条，真实混合检索）==========%n", CASES.size());
        System.out.printf("Recall@3 = %.3f%nRecall@5 = %.3f%nMRR = %.3f%n", recall3, recall5, mrr);
        // 评估不设硬性通过线（指标供答辩/对比），仅断言链路能跑通且有命中
        assertTrue(recall5 > 0, "评估集应至少命中部分期望商品");
    }

    private static boolean qdrantReachable() {
        try {
            URI uri = URI.create("http://localhost:6333/collections/products");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
