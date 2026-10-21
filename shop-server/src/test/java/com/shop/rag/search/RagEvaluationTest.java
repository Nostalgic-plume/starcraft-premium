package com.shop.rag.search;

import com.shop.rag.config.RagElasticsearchProperties;
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
 * 离线评估集（真实混合检索）：query → 期望商品 ID 集合，计算 Hit@K / MRR。
 *
 * <p>依赖：Qdrant 运行 + 商品向量索引存在 + 向量化服务（本地 TEI / BGE-M3）可用。
 * 环境缺失时自动跳过（assumeTrue），不污染 CI。
 *
 * <p><b>2026-10-21 重建说明（功能编号 001）</b>：上一版评估集（2026-08 标注）的期望 ID
 * 已与当前商品数据完全脱节 —— 「口红」期望「小米手机/南极人被芯」，「剃须刀」期望「联想笔记本」，
 * 基线代码实跑 Recall@3 也只有 0.033。本次按当前库中**真实上架商品**重新标注。
 *
 * <p><b>标注口径</b>：
 * <ol>
 *   <li>期望集 = 该 query 相关的**全部上架商品 ID**（mock 数据同一商品有多个重复 SPU，
 *       只取代表会造成「召回同名商品却判不中」的低估）；</li>
 *   <li>因此主指标用 {@link RagMetrics#hitAtK}（前 K 条是否至少命中一个）与
 *       {@link RagMetrics#mrrAtFirstHit}（首个命中位置），Recall@5 仅作参考 ——
 *       期望集 39 个时 Recall@5 上限只有 5/39≈0.128，不能横向比较；</li>
 *   <li>ID 由 SQL 按商品名聚合生成（查询语句见 docs/specs/001-es-keyword-search/plan.md §六），
 *       数据变更后需重新生成。</li>
 * </ol>
 */
@SpringBootTest
class RagEvaluationTest {

    @Autowired
    private RagSearchService ragSearchService;

    @Autowired
    private SynonymProperties synonymProperties;

    @Autowired
    private RrfProperties rrfProperties;

    /** 关键词路开关：用于在同一上下文内对照 ES 与 MySQL FULLTEXT（功能编号 001） */
    @Autowired
    private RagElasticsearchProperties esProperties;

    private record TestCase(String query, String type, List<Long> expectedIds) {
    }

    /**
     * 37 条正样本，按检索难点分层（2026-10-21 基于当时上架商品标注）
     */
    private static final List<TestCase> CASES = List.of(
            // ---------- A. 精确型号 / 商品名（6） ----------
            new TestCase("戴森 V12 吸尘器", "精确型号", List.of(
                    5L, 100L, 185L, 267L, 290L, 320L, 349L, 380L, 409L, 470L, 530L, 579L,
                    590L, 620L, 649L, 680L, 744L, 770L, 813L, 830L, 860L, 890L, 920L, 988L)),
            new TestCase("松下剃须刀", "精确型号", List.of(279L)),
            new TestCase("华为 Mate 60", "精确型号", List.of(
                    1L, 61L, 91L, 151L, 211L, 256L, 286L, 376L, 466L, 496L, 556L,
                    586L, 646L, 676L, 706L, 736L, 766L, 826L, 856L, 916L, 946L, 976L)),
            new TestCase("飞利浦电动牙刷", "精确型号", List.of(
                    11L, 76L, 102L, 150L, 170L, 191L, 216L, 273L, 375L, 394L, 475L,
                    541L, 602L, 650L, 690L, 729L, 743L, 792L, 892L)),
            new TestCase("农夫山泉", "精确型号", List.of(
                    27L, 176L, 236L, 284L, 483L, 611L, 671L, 685L, 780L, 851L, 864L, 939L)),
            new TestCase("乐高 消防救援直升机", "精确型号", List.of(
                    15L, 46L, 112L, 171L, 231L, 304L, 328L, 361L, 392L, 417L, 491L,
                    522L, 547L, 604L, 631L, 686L, 785L, 814L, 848L, 935L, 1000L)),

            // ---------- B. 品类词（8） ----------
            new TestCase("手机", "品类", List.of(
                    61L, 151L, 256L, 286L, 347L, 376L, 437L, 466L, 467L, 586L, 916L, 976L)),
            new TestCase("吸尘器", "品类", List.of(
                    5L, 100L, 185L, 267L, 290L, 320L, 349L, 380L, 409L, 470L, 530L, 579L,
                    590L, 620L, 649L, 680L, 744L, 770L, 813L, 830L, 860L, 890L, 920L, 988L)),
            new TestCase("电饭煲", "品类", List.of(80L, 119L, 282L, 332L, 447L, 656L)),
            new TestCase("跑步鞋", "品类", List.of(
                    8L, 87L, 166L, 189L, 260L, 384L, 449L, 477L, 511L, 571L,
                    597L, 623L, 687L, 775L, 815L, 956L, 998L)),
            new TestCase("纸尿裤", "品类", List.of(
                    52L, 115L, 198L, 230L, 454L, 573L, 610L, 633L, 693L, 844L)),
            new TestCase("牙膏", "品类", List.of(23L, 210L, 302L, 358L, 521L, 601L, 781L)),
            new TestCase("坚果", "品类", List.of(
                    82L, 219L, 262L, 300L, 357L, 413L, 456L, 481L, 538L, 575L,
                    628L, 654L, 757L, 821L, 863L, 901L, 923L, 964L, 993L)),
            new TestCase("中性笔", "品类", List.of(
                    30L, 60L, 174L, 200L, 335L, 583L, 614L, 639L, 701L,
                    734L, 764L, 787L, 878L, 907L, 934L, 975L)),

            // ---------- C. 品牌词（6） ----------
            new TestCase("华为", "品牌", List.of(
                    1L, 61L, 90L, 91L, 151L, 211L, 256L, 286L, 376L, 466L, 496L, 556L,
                    586L, 646L, 676L, 706L, 736L, 766L, 826L, 856L, 916L, 946L, 976L)),
            new TestCase("小米", "品牌", List.of(
                    2L, 20L, 32L, 62L, 101L, 152L, 177L, 182L, 257L, 285L, 287L, 313L, 317L,
                    347L, 377L, 407L, 437L, 467L, 497L, 503L, 557L, 608L, 617L, 637L, 647L,
                    677L, 697L, 713L, 765L, 767L, 805L, 827L, 836L, 857L, 884L, 887L, 917L,
                    971L, 987L)),
            new TestCase("苹果 iPhone", "品牌", List.of(
                    3L, 33L, 63L, 93L, 153L, 183L, 213L, 318L, 348L, 378L, 442L,
                    498L, 558L, 588L, 708L, 738L, 798L, 858L, 888L, 918L, 948L, 978L)),
            new TestCase("戴森", "品牌", List.of(
                    5L, 100L, 185L, 267L, 290L, 320L, 349L, 380L, 409L, 470L, 530L, 579L,
                    590L, 620L, 649L, 680L, 744L, 770L, 813L, 830L, 860L, 890L, 920L, 988L)),
            new TestCase("飞利浦", "品牌", List.of(
                    11L, 76L, 102L, 118L, 170L, 191L, 216L, 273L, 375L, 394L,
                    475L, 541L, 602L, 650L, 690L, 729L, 743L, 792L, 892L)),
            new TestCase("乐高", "品牌", List.of(
                    15L, 46L, 112L, 171L, 231L, 304L, 328L, 361L, 392L, 417L, 491L,
                    522L, 547L, 604L, 631L, 686L, 785L, 814L, 848L, 935L, 1000L)),

            // ---------- D. 口语化 / 同义词（6） ----------
            new TestCase("充电宝", "同义词", List.of(147L, 608L, 765L, 971L)),
            new TestCase("刮胡刀", "同义词", List.of(279L)),
            new TestCase("球鞋", "同义词", List.of(
                    8L, 87L, 166L, 189L, 260L, 384L, 449L, 477L, 511L, 571L,
                    597L, 623L, 687L, 775L, 815L, 956L, 998L)),
            new TestCase("笔记本电脑", "同义词", List.of(
                    4L, 184L, 214L, 258L, 289L, 367L, 379L, 426L, 440L, 499L, 500L,
                    529L, 559L, 560L, 589L, 619L, 662L, 679L, 740L, 829L, 889L, 919L, 949L, 979L)),
            new TestCase("口红", "同义词", List.of(54L)),
            new TestCase("婴儿车", "同义词", List.of(114L, 603L)),

            // ---------- E. 错别字 / 长句（5） ----------
            new TestCase("手鸡", "错别字", List.of(
                    61L, 151L, 256L, 286L, 347L, 376L, 437L, 466L, 467L, 586L, 916L, 976L)),
            new TestCase("洗依机", "错别字", List.of(
                    161L, 201L, 263L, 336L, 401L, 474L, 691L, 807L)),
            new TestCase("打游戏手机", "长句", List.of(
                    61L, 151L, 256L, 286L, 347L, 376L, 437L, 466L, 467L, 586L, 916L, 976L)),
            new TestCase("给刚出生的宝宝买奶粉", "长句", List.of(48L, 517L, 574L, 883L)),
            new TestCase("降噪耳机", "长句", List.of(
                    19L, 37L, 66L, 96L, 121L, 156L, 195L, 283L, 331L, 348L,
                    543L, 681L, 711L, 741L, 790L, 803L, 831L, 891L, 982L)),

            // ---------- F. 属性组合（4） ----------
            new TestCase("4L 电饭煲", "属性组合", List.of(80L, 119L, 282L, 332L, 447L, 656L)),
            new TestCase("L 码纸尿裤", "属性组合", List.of(
                    115L, 198L, 230L, 454L, 573L, 610L, 633L, 693L, 844L)),
            new TestCase("550ml 矿泉水", "属性组合", List.of(
                    27L, 176L, 236L, 284L, 483L, 611L, 671L, 685L, 780L, 851L, 864L, 939L)),
            new TestCase("轻薄本", "属性组合", List.of(560L, 679L)),

            // ---------- G. 补充品类（2） ----------
            new TestCase("帐篷", "品类", List.of(241L, 480L, 551L)),
            new TestCase("冰箱", "品类", List.of(
                    280L, 292L, 351L, 382L, 473L, 531L, 564L, 715L, 746L, 772L, 838L))
    );

    /**
     * 3 条负样本：库中确实没有对应商品。
     * <p>
     * 不计入 Hit/MRR —— 没有期望集就无法定义「命中」。只做两件事：
     * ① 链路能正常返回（不 500）；② 打印 top-3 供人工判断是否「硬凑结果」。
     * 语义检索天然会返回「相似但不相关」的商品，这条记录的是既有行为，不是通过线。
     */
    private static final List<String> NEGATIVE_QUERIES = List.of("吉他", "钢琴", "墨镜");

    /**
     * 同义词对照集：query 为口语说法，期望 = 同义词组覆盖的商品。
     */
    private static final List<TestCase> SYNONYM_CASES = List.of(
            new TestCase("充电宝", "同义词", List.of(147L, 608L, 765L, 971L)),
            new TestCase("刮胡刀", "同义词", List.of(279L)),
            new TestCase("球鞋", "同义词", List.of(
                    8L, 87L, 166L, 189L, 260L, 384L, 449L, 477L, 511L, 571L,
                    597L, 623L, 687L, 775L, 815L, 956L, 998L)),
            new TestCase("轻薄本", "同义词", List.of(560L, 679L)),
            new TestCase("笔记本电脑", "同义词", List.of(
                    4L, 184L, 214L, 258L, 289L, 367L, 379L, 426L, 440L, 499L, 500L,
                    529L, 559L, 560L, 589L, 619L, 662L, 679L, 740L, 829L, 889L, 919L, 949L, 979L))
    );

    @BeforeAll
    static void requireQdrant() {
        Assumptions.assumeTrue(qdrantReachable(), "Qdrant 未运行，跳过真实评估");
    }

    @Test
    @DisplayName("基础评估集：Hit@3 / Hit@5 / MRR@FirstHit")
    void evaluateHybridSearch() {
        double[] metrics = runEvaluation("基础评估集（37 条，真实混合检索）", CASES);
        assertTrue(metrics[1] > 0, "评估集应至少命中部分 query");
    }

    @Test
    @DisplayName("负样本：库中无对应商品时的返回行为")
    void evaluateNegativeQueries() {
        System.out.printf("%n---------- 负样本（%d 条，不计指标）----------%n", NEGATIVE_QUERIES.size());
        for (String query : NEGATIVE_QUERIES) {
            List<RagHitVO> hits = ragSearchService.search(query, 3, null, null, null);
            String topNames = hits.stream()
                    .map(h -> h.getName() == null ? "?" : h.getName())
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("（空）");
            System.out.printf("[负样本] query=%-8s 返回 %d 条: %s%n", query, hits.size(), topNames);
        }
    }

    @Test
    @DisplayName("同义词扩展对照：关闭(baseline) vs 开启")
    void evaluateSynonymExtension() {
        boolean originalEnabled = synonymProperties.isEnabled();
        try {
            synonymProperties.setEnabled(false);
            double[] baseline = runEvaluation("baseline（同义词扩展关闭）", SYNONYM_CASES);

            synonymProperties.setEnabled(true);
            double[] expanded = runEvaluation("扩展（同义词扩展开启）", SYNONYM_CASES);

            System.out.printf("%n========== 同义词扩展对照（%d 条）==========%n", SYNONYM_CASES.size());
            System.out.printf("%-26s Hit@3=%.3f Hit@5=%.3f MRR=%.3f%n",
                    "关闭扩展(baseline)", baseline[0], baseline[1], baseline[2]);
            System.out.printf("%-26s Hit@3=%.3f Hit@5=%.3f MRR=%.3f%n",
                    "开启扩展", expanded[0], expanded[1], expanded[2]);
            System.out.printf("Hit@5 变化 = %+.3f, MRR 变化 = %+.3f%n",
                    expanded[1] - baseline[1], expanded[2] - baseline[2]);
        } finally {
            synonymProperties.setEnabled(originalEnabled);
        }
    }

    /**
     * 关键词引擎对照：ES（BM25 + IK） vs MySQL FULLTEXT（ngram n=2）
     * <p>
     * 向量路保持不变，只切换关键词路开关 —— 差异全部来自关键词引擎。
     * 重点观察两类 query：
     * <ul>
     *   <li><b>精确/品类/品牌</b>：ES 的 IK 分词 + BM25 应更强；</li>
     *   <li><b>错别字</b>（如「手鸡」）：ngram 靠 2-gram 单字重叠有天然容错，IK 把一个不存在的词
     *       当整体词项，可能 0 命中 —— 这是换引擎的已知取舍，必须量化而不是假设。</li>
     * </ul>
     */
    @Test
    @DisplayName("关键词引擎对照：ES（BM25+IK） vs MySQL FULLTEXT（ngram）")
    void evaluateKeywordEngine() {
        boolean original = esProperties.isEnabled();
        try {
            esProperties.setEnabled(false);
            double[] fulltext = runEvaluation("关键词路=MySQL FULLTEXT（ngram）", CASES);

            esProperties.setEnabled(true);
            double[] es = runEvaluation("关键词路=ES（BM25+IK）", CASES);

            System.out.printf("%n========== 关键词引擎对照（%d 条，向量路不变）==========%n", CASES.size());
            System.out.printf("%-30s Hit@3=%.3f Hit@5=%.3f MRR=%.3f%n",
                    "MySQL FULLTEXT(ngram)", fulltext[0], fulltext[1], fulltext[2]);
            System.out.printf("%-30s Hit@3=%.3f Hit@5=%.3f MRR=%.3f%n",
                    "Elasticsearch(BM25+IK)", es[0], es[1], es[2]);
            System.out.printf("Hit@3 变化 = %+.3f, MRR 变化 = %+.3f%n",
                    es[0] - fulltext[0], es[2] - fulltext[2]);
        } finally {
            esProperties.setEnabled(original);
        }
    }

    /**
     * ES 匹配门槛对照：纯 OR vs 条件语法 2&lt;70% vs AND(100%)
     * <p>
     * 背景：IK 把未登录词（错别字）切成单字，纯 OR 下「手鸡」会命中「手口湿巾」等噪声；
     * 纯 AND 又会误伤长句。条件语法 {@code 2<70%} 对 ≤2 词要求全命中、长句放宽到 70%。
     * <p>
     * 三档都跑完整评估集 —— 不能只看「手鸡」一条，必须确认门槛提高没有牺牲正常 query 的召回。
     */
    @Test
    @DisplayName("ES 匹配门槛对照：OR vs 2<70% vs AND")
    void evaluateEsMatchMode() {
        String original = esProperties.getMinimumShouldMatch();
        String[][] modes = {
                {"", "OR（无门槛，回滚档）"},
                {"2<70%", "2<70%（默认）"},
                {"100%", "AND（100%）"}
        };
        double[][] results = new double[modes.length][];
        try {
            for (int i = 0; i < modes.length; i++) {
                esProperties.setMinimumShouldMatch(modes[i][0]);
                results[i] = runEvaluation("ES 匹配模式=" + modes[i][1], CASES);
            }
        } finally {
            esProperties.setMinimumShouldMatch(original);
        }

        System.out.printf("%n========== ES 匹配门槛对照（%d 条）==========%n", CASES.size());
        for (int i = 0; i < modes.length; i++) {
            System.out.printf("%-24s Hit@3=%.3f Hit@5=%.3f MRR=%.3f%n",
                    modes[i][1], results[i][0], results[i][1], results[i][2]);
        }
    }

    /**
     * 加权 RRF 对照：在同一评估集上依次跑三档权重，输出可对比的表格。
     * <p>
     * 权重通过直接改写 {@link RrfProperties} 生效（无需重启上下文），结束后还原。
     */
    @Test
    @DisplayName("加权 RRF 对照：1.0/1.0 vs 1.0/1.5 vs 1.0/2.0")
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

                System.out.printf("[加权对照] 权重 %-10s | 基础集 Hit@3/@5/MRR = %.3f / %.3f / %.3f"
                                + " | 同义词集 Hit@3/@5/MRR = %.3f / %.3f / %.3f%n",
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
     * @return [Hit@3, Hit@5, MRR@FirstHit, Recall@5（仅参考）]
     */
    private double[] runEvaluation(String title, List<TestCase> cases) {
        double hit3 = 0, hit5 = 0, mrr = 0, recall5 = 0;
        System.out.printf("%n---------- %s ----------%n", title);
        for (TestCase tc : cases) {
            List<RagHitVO> hits = ragSearchService.search(tc.query(), 5, null, null, null);
            List<Long> retrieved = hits.stream().map(RagHitVO::getProductId).toList();
            double h3 = RagMetrics.hitAtK(tc.expectedIds(), retrieved, 3);
            double h5 = RagMetrics.hitAtK(tc.expectedIds(), retrieved, 5);
            double m = RagMetrics.mrrAtFirstHit(tc.expectedIds(), retrieved);
            double r5 = RagMetrics.recallAtK(tc.expectedIds(), retrieved, 5);
            hit3 += h3;
            hit5 += h5;
            mrr += m;
            recall5 += r5;
            System.out.printf("[评估] %-14s %-6s Hit@3=%.0f Hit@5=%.0f MRR=%.3f Recall@5=%.3f 命中=%s%n",
                    tc.query(), tc.type(), h3, h5, m, r5, retrieved);
        }
        int n = cases.size();
        hit3 /= n;
        hit5 /= n;
        mrr /= n;
        recall5 /= n;
        System.out.printf("%s 汇总: Hit@3=%.3f Hit@5=%.3f MRR=%.3f （Recall@5=%.3f，期望集含全部同类商品，仅参考）%n",
                title, hit3, hit5, mrr, recall5);
        return new double[]{hit3, hit5, mrr, recall5};
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
