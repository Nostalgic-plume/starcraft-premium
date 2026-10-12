package com.shop.rag.search;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线评估集（真实混合检索）：人工标注 (query → 期望商品ID)，计算 Recall@K 与 MRR。
 *
 * 依赖：Qdrant 运行 + 商品向量索引存在 + 向量化服务（本地 TEI / BGE-M3）可用（真实混合检索链路）。
 * 环境缺失时自动跳过（assumeTrue），不污染 CI。答辩环境启动 Qdrant 后运行即可复现指标。
 *
 * 两个评估维度：
 * 1) {@link #evaluateHybridSearch()}：30 条基础评估集，覆盖商品名/品牌/口语化/错别字；
 * 2) {@link #evaluateSynonymExtension()}：同义词 case，做「关闭扩展(baseline) vs 开启扩展」对照，
 *    量化坑②（同义词/黑话）修复带来的 Recall@5 / MRR 变化。
 */
@SpringBootTest
class RagEvaluationTest {

    @Autowired
    private RagSearchService ragSearchService;

    @Autowired
    private SynonymProperties synonymProperties;

    @Autowired
    private RrfProperties rrfProperties;

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

    /**
     * 同义词评估集：query 均为「用户口语说法」，商品名中不存在该字面（或仅部分存在），
     * 期望值 = 同义词组覆盖的商品 ID（人工标注，基于当前 mock 数据）。
     * <p>
     * 字面路在关闭扩展时的预期表现：除「充电宝」可命中 id 255 外，其余基本漏召。
     */
    private static final List<TestCase> SYNONYM_CASES = List.of(
            new TestCase("充电宝", List.of(124L, 216L, 255L)),
            new TestCase("刮胡刀", List.of(34L, 56L, 60L)),
            new TestCase("球鞋", List.of(20L, 145L, 161L, 205L, 236L, 258L)),
            new TestCase("充电头", List.of(153L, 210L, 294L)),
            new TestCase("笔记本电脑", List.of(136L, 137L, 186L))
    );

    @BeforeAll
    static void requireQdrant() {
        Assumptions.assumeTrue(qdrantReachable(), "Qdrant 未运行，跳过真实评估");
    }

    @Test
    void evaluateHybridSearch() {
        double[] metrics = runEvaluation("基础评估集（真实混合检索）", CASES);
        // 评估不设硬性通过线（指标供答辩/对比），仅断言链路能跑通且有命中
        assertTrue(metrics[1] > 0, "评估集应至少命中部分期望商品");
    }

    @Test
    @DisplayName("同义词扩展对照：关闭(baseline) vs 开启，输出 Recall@5 / MRR 变化")
    void evaluateSynonymExtension() {
        boolean originalEnabled = synonymProperties.isEnabled();
        try {
            synonymProperties.setEnabled(false);
            double[] baseline = runEvaluation("baseline（同义词扩展关闭）", SYNONYM_CASES);

            synonymProperties.setEnabled(true);
            double[] expanded = runEvaluation("扩展（同义词扩展开启）", SYNONYM_CASES);

            System.out.printf("%n========== 同义词扩展对照（%d 条）==========%n", SYNONYM_CASES.size());
            System.out.printf("%-26s Recall@3=%.3f Recall@5=%.3f MRR=%.3f%n",
                    "关闭扩展(baseline)", baseline[0], baseline[1], baseline[2]);
            System.out.printf("%-26s Recall@3=%.3f Recall@5=%.3f MRR=%.3f%n",
                    "开启扩展", expanded[0], expanded[1], expanded[2]);
            System.out.printf("Recall@5 变化 = %+.3f, MRR 变化 = %+.3f%n",
                    expanded[1] - baseline[1], expanded[2] - baseline[2]);
        } finally {
            synonymProperties.setEnabled(originalEnabled);
        }
    }

    /**
     * 加权 RRF 对照：在同一评估集上依次跑三档权重，输出可对比的表格。
     * <p>
     * 权重通过直接改写 {@link RrfProperties} 生效（无需重启上下文），结束后还原。
     * 读数字的前提：先看 1.0/1.0 这一档（候选池对齐后的新基线），再与加权档位比较；
     * 不要与 docs/rag-evaluation-report.md §5 的历史数字直接比——旧数字是在向量路候选
     * 被 topK 截断的条件下测出的（见该报告 §8）。
     */
    @Test
    @DisplayName("加权 RRF 对照：1.0/1.0(新基线) vs 1.0/1.5 vs 1.0/2.0")
    void evaluateWeightedRrf() {
        double originalVectorWeight = rrfProperties.getWeightVector();
        double originalKeywordWeight = rrfProperties.getWeightKeyword();
        double[][] weightPairs = {{1.0, 1.0}, {1.0, 1.5}, {1.0, 2.0}};

        System.out.printf("%n========== 加权 RRF 对照（基础集 %d 条 / 同义词集 %d 条）==========%n",
                CASES.size(), SYNONYM_CASES.size());
        try {
            for (double[] pair : weightPairs) {
                rrfProperties.setWeightVector(pair[0]);
                rrfProperties.setWeightKeyword(pair[1]);

                double[] basic = runEvaluation(
                        String.format("基础集 wv=%.1f wk=%.1f", pair[0], pair[1]), CASES);
                double[] synonym = runEvaluation(
                        String.format("同义词集 wv=%.1f wk=%.1f", pair[0], pair[1]), SYNONYM_CASES);

                System.out.printf("[加权对照] 权重 %-10s | 基础集 Recall@3/@5/MRR = %.3f / %.3f / %.3f"
                                + " | 同义词集 Recall@3/@5/MRR = %.3f / %.3f / %.3f%n",
                        String.format("%.1f/%.1f", pair[0], pair[1]),
                        basic[0], basic[1], basic[2], synonym[0], synonym[1], synonym[2]);
            }
        } finally {
            rrfProperties.setWeightVector(originalVectorWeight);
            rrfProperties.setWeightKeyword(originalKeywordWeight);
        }
    }

    /**
     * 跑一组 case 并输出逐条明细与汇总指标
     *
     * @param title 打印标题
     * @param cases 评估用例
     * @return [Recall@3, Recall@5, MRR]
     */
    private double[] runEvaluation(String title, List<TestCase> cases) {
        double recall3 = 0, recall5 = 0, mrr = 0;
        System.out.printf("%n---------- %s ----------%n", title);
        for (TestCase tc : cases) {
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
        recall3 /= cases.size();
        recall5 /= cases.size();
        mrr /= cases.size();
        System.out.printf("%s 汇总: Recall@3=%.3f Recall@5=%.3f MRR=%.3f%n", title, recall3, recall5, mrr);
        return new double[]{recall3, recall5, mrr};
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
