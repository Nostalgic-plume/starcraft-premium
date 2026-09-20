# 检索链路调优计划书（加权 RRF + 候选池对齐 + 关键词路过滤）

> 状态：计划稿（2026-09-20）
> 设计依据：`docs/rag-hybrid-tuning-design.md`
> 执行顺序按依赖关系排列，Task 1-6 为代码改动，Task 7 为评估，Task 8 为文档收口。

## Task 1：RRF 配置属性与校验

- 新增 `com.shop.rag.RrfProperties`（`@ConfigurationProperties(prefix = "rag.rrf")`）：
  `k`（默认 60）、`weightVector`（默认 1.0）、`weightKeyword`（默认 1.0）。
- `@PostConstruct` 校验：`k <= 0`、权重为负或同时为 0 → 打 WARN 并回退默认值，不抛异常。
- `application.yml` 增加 `rag.rrf` 段（默认值显式写出，便于运营改）。
- 测试：`RrfPropertiesBindingTest`（对齐 `SynonymPropertiesBindingTest` 风格）——校验 yml → 绑定 → 回退链路。

**验收**：新测试绿；`application.yml` 显式包含默认值。

## Task 2：RrfMerger 支持加权

- 新增四/五参入口 `merge(list1, list2, topK, k, wv, wk)`，保留三参 `merge(list1, list2, topK)` 委托到默认值。
- 公式改为 `wv/(K+rank)` 与 `wk/(K+rank)`。
- 测试 `RrfMergerTest`：
  - 权重 1.0/1.0 时结果与三参重载完全一致（回归保护）。
  - 构造「权重翻转排序」用例：商品 A 向量路 rank 0、关键词路缺席；商品 B 仅关键词路 rank 2 —— `wk=3.0` 时 B 应反超 A。
  - `k` 参数生效（k 增大时两路分差收窄，可断言具体数值）。

**验收**：新测试绿；三参重载行为不变。

## Task 3：候选池对齐与 topK 校验

- `RagSearchService`：`queryLimit = clamp(topK * 5, 50, 200)`；`topK` 先 clamp 到 `[1, 100]`。
- `searchViaQdrant` 去掉 `if (hits.size() >= topK) break`，改为按 `queryLimit` 收满（保留 `minScore` 过滤）。
- 注入 `RrfProperties`，融合时传权重与 K。
- 测试：扩充 `RagFallbackTest` 或新增 `RagSearchServiceTest`（Mockito，离线）：
  - 断言向量路收到的 `maxResults == queryLimit`，且返回条数不再被 `topK` 截断。
  - `topK = -1 / 0` 不抛异常，返回正常结果。

**验收**：新测试绿；`RagFallbackTest` 仍绿（构造器签名同步更新）。

## Task 4：关键词路过滤补齐

- 提取 `SkuPriceResolver`（把 `ProductIndexer.loadPriceMap` 的批量 SKU 价格聚合搬过去，`ProductIndexer` 改为委托）。
- `ProductMapper.searchByKeyword(query, limit)`：`limit` 由调用方按 `queryLimit * 2` 传入（SQL 本身不变）。
- `KeywordSearchService.searchByKeyword(query, limit, categoryId, minPrice, maxPrice)`：
  - 内存过滤分类（`firstCategoryId`）与价格区间，语义与向量路 `passMetadataFilter` 一致。
  - 过滤后截断到 `queryLimit`；VO 补 `minPrice/maxPrice`。
  - SKU 价格查询异常 → 不过滤 + WARN。
- 三参旧签名保留并委托（避免破坏其他调用点）。
- 测试：分类不匹配剔除、价格下限/上限剔除、过滤后 VO 带价格、价格查询异常时放行。

**验收**：新测试绿；`ProductIndexerTest` 仍绿。

## Task 5：Python 侧同步

- `shop-ai/app/retrieval/rrf.py`：`merge(..., k=DEFAULT_K, wv=1.0, wk=1.0)`，默认行为与现状一致；
  保留单路分支的「有意分歧」（去重、跳过 None），不得对齐成 Java 行为。
- `shop-ai/app/retrieval/keyword_search.py`：`search_by_keyword` 增加可选过滤参数并补 `min_price/max_price` 字段。
- 测试：扩充 `tests/test_rrf.py`（加权公式、默认等价、权重翻转排序）。
- `tests/test_search.py` 引用尚不存在的 `app/retrieval/search.py` → 属 shop-ai 编排层未落地，本次不改，仅在总结中记录。

**验收**：`pytest tests/test_rrf.py` 全绿（本地 .venv）。

## Task 6：回归与编译

- `mvn -q -DskipTests compile` 通过。
- 离线单测全绿：`RrfMergerTest`、`RrfPropertiesBindingTest`、`RagFallbackTest`、`SynonymExpanderTest`、`SynonymPropertiesBindingTest`、`RagMetricsTest`、`ProductIndexerTest`。

**验收**：命令输出贴进总结，失败项说明原因。

## Task 7：评估对照（需外部依赖）

- 前置：MySQL（已在跑）+ Redis + Qdrant + DashScope Key 四件套在线；商品向量索引存在。
- 跑 `RagEvaluationTest`，三档权重：`1.0/1.0`（新基线）、`1.0/1.5`、`1.0/2.0`；基础集与同义词集分别记录 Recall@3/@5、MRR。
- 权重通过 `application.yml` 或启动参数覆盖实现（不新增代码分支）。
- 结论与逐条明细回写 `docs/rag-evaluation-report.md`（新增「加权 RRF 对照」小节，并标注新基线取代旧基线的原因）。

**验收**：对照表落文档；若依赖不可用，明确记录「未执行 + 原因」，不伪造数据。

## Task 8：文档收口

- `docs/rag-hybrid-tuning-design.md` / `docs/rag-hybrid-tuning-plan.md` 入库。
- 若 `CLAUDE.md` / `AGENTS.md` 中「RAG 搜索」段落需补充加权 RRF 与候选池对齐描述，同步更新。
- 本次顺带确认的缺陷（关键词路过滤缺口、候选池不对称、topK 无校验）在文档中留痕。

**验收**：文档入库；`git status` 无遗漏。

## 风险与依赖

| 风险 | 影响 | 应对 |
|------|------|------|
| Qdrant / Redis / DashScope 任一不可用 | Task 7 无法执行 | 单测与编译先行；评估另行安排，不阻塞代码交付 |
| 沙箱对 `D:\claudecode` 只读 | 代码与文档写入需提权 | 按需发起提权确认 |
| 历史基线不可比 | 评估结论被误读 | 明确以重跑的 1.0/1.0 为新基线 |
| `shop-ai` 独立仓库 | 双端改动需两次提交 | Task 5 单独提交，提交信息标注与 Java 侧对齐 |

## 附：Task 7 执行记录与复现方式（已于 2026-09-20 实际执行）

前置：MySQL + Redis + Qdrant（`products` 集合已索引）在线，`DASHSCOPE_API_KEY` 有效且外网可达。

```powershell
# 1) 启动中间件（仓库已带 compose，仅起需要的两个）
docker compose -f D:\claudecode\shop\docker-compose.yml up -d qdrant redis

# 2) 用 JDK 22 跑评估（-o 离线，依赖已在本地仓库）
$env:JAVA_HOME='C:\Users\26373\.jdks\openjdk-22.0.1'

# 2.1 新基线：默认等权 1.0 / 1.0
mvn -f D:\claudecode\shop\pom.xml -o -DfailIfNoSpecifiedTests=false -Dtest=RagEvaluationTest test

# 2.2 加权对照已固化在 RagEvaluationTest#evaluateWeightedRrf 中：
#     同一次运行即依次跑 1.0/1.0、1.0/1.5、1.0/2.0 三档并打印对照表，无需改配置或分别传参。
#     若需临时指定其它档位（如线上灰度），仍可用系统属性覆盖 yml（系统属性优先级更高）：
#     mvn -f D:\claudecode\shop\pom.xml -o -DfailIfNoSpecifiedTests=false -Dtest=RagEvaluationTest "-DargLine=-Drag.rrf.weight-keyword=2.0" test
```

**读数字时的三条注意**：
1. 先看 2.1 的「新基线」，再与 2.2 的两档比较；**不要**直接与 `rag-evaluation-report.md` §5 的旧数字比
   （旧数字是在向量路候选被 topK 截断的条件下测出的，见该报告 §8）；
2. 基础集与同义词集分开看：抬高关键词权重对基础集有利、对语义类 query 不利；
3. 权重只影响融合排序，若某查询两路召回集合都为 0（如单字 query），调权重无效 —— 那是召回层问题。

实际结果已回写 `docs/rag-evaluation-report.md` §8：1.0/1.0 新基线 0.628/0.761/0.487，1.0/2.0 档 0.711/0.764/0.504（基础集），同义词集三档一致。