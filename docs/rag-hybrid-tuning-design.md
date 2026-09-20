# 检索链路调优设计（加权 RRF + 候选池对齐 + 关键词路过滤补齐）

> 状态：设计稿（2026-09-20）
> 关联：`docs/rag-evaluation-report.md`、`docs/rag-synonym-design.md`、`docs/rag-fallback-design.md`
> 目标：让 RRF 权重可配置、消除两路候选池不对称、补齐关键词路的过滤契约

## 一、现状与问题（证据）

| # | 现状 | 代码位置 | 后果 |
|---|------|---------|------|
| 1 | 关键词路不过滤 `categoryId/minPrice/maxPrice`，向量路过滤 | `RagSearchService.java:79` vs `:88` | 同一接口两路语义不一致；带筛选的请求会返回越界商品。前端目前只传 `q/topK`（`frontend/src/api/product.ts:19`），客服链路传 null（`ChatService.java:160`），故未爆发，但接口契约已破 |
| 2 | 向量路候选池被 `topK` 截断，关键词路取 `max(topK*5,50)` | `RagSearchService.java:161`、`:76` | 两路候选池不对称；客服链路 `topK=3` 时向量路仅 3 个候选，结构性被压制 |
| 3 | RRF 等权、K 硬编码，Java/Python 各一份 | `RrfMerger.java:18`、`:47`、`:56`；`shop-ai/app/retrieval/rrf.py:12` | 无法按评估结论调权；改一次要同步两处 |
| 4 | 关键词路 VO 不设 `minPrice/maxPrice` | `KeywordSearchService.java:44` | 纯关键词降级态前端价格为空，与向量路字段不一致 |
| 5 | `topK` 无上下限校验 | `RagSearchController.java:51` | 负数触发 `Stream.limit(-1)` 抛异常返回 500；超大值放大 MySQL `LIMIT` 与 Qdrant `maxResults` |

评估依据（`docs/rag-evaluation-report.md` §6.3）：基础集上关键词单路 Recall@3 = 0.678 > 混合 0.639，
典型如「运动鞋」0.50 → 0.00、「冰箱」1.00 → 0.00；而同义词集上向量路显著有益（0.333 → 0.567）。
原文结论：「后续可通过加权 RRF 或按 query 类型动态调权改善」。

## 二、方案

### 2.1 候选池对齐

两路统一取 `queryLimit = clamp(topK * 5, 50, 200)`，向量路去掉 `topK` 截断，融合后统一截断 `topK`。

- 保留向量路的 `minScore=0.3`：阈值过滤是相关性判断，与候选池截断是两回事，不得混淆。
- 代价：Qdrant `maxResults` 由 `topK` 提升到 `queryLimit`（`topK=10` 时 50 条），可接受。
- 收益：向量路排名靠后的候选不再因 `topK` 被人为丢弃。

### 2.2 加权 RRF

```
score = wv / (K + rank_vector) + wk / (K + rank_keyword)
```

- 权重与 `K` 全部外置为配置（`rag.rrf`）。默认 `wv = 1.0`、`wk = 2.0`、`K = 60` —— 该档位由 2026-09-20 的真实评估裁决（基础集 Recall@3 0.628 → 0.711），详见 `docs/rag-evaluation-report.md` §8；权重配成 1.0/1.0 即退化为改造前的等权行为。
- 配置形状（`application.yml`）：

```yaml
rag:
  rrf:
    k: 60
    weight-vector: 1.0
    weight-keyword: 2.0   # 评估推荐档位；配 1.0 即退化为改造前等权行为
```

- 权重之和固定为 2.0 便于对照实验读数（不强制归一化，RRF 只做相对比较）。

### 2.3 关键词路过滤补齐

- 分类：按 `firstCategoryId` 过滤，与向量路 payload 字段一致（不是 `categoryId`）。
- 价格：复用 SKU 价格聚合。`ProductIndexer.loadPriceMap` 目前是 private，提取为共享组件 `SkuPriceResolver`，
  关键词路按命中的 `productId` 批量查一次 SKU 取 min/max 后过滤。
- **over-fetch**：SQL 的 `LIMIT` 用 `queryLimit * 2`，内存过滤后再截断到 `queryLimit`。
  原因：FULLTEXT 排序在 SQL 层确定，过滤只能发生在取回之后；若沿用 `queryLimit` 会因过滤而候选不足。
  这是有意的近似，不追求与向量路逐条等价。
- 顺带修问题 4：关键词路 VO 补齐 `minPrice/maxPrice`。

### 2.4 双端一致性

- `shop-ai/app/retrieval/rrf.py` 同步加权参数（`wv/wk`）；该侧默认仍为 1.0/1.0，**尚未同步 1.0/2.0 档位**（本轮只做 Java 侧）。
- `shop-ai/app/retrieval/keyword_search.py` 的 `search_by_keyword` 增加可选过滤参数，与 Java 侧签名语义对齐。
- 注意：`shop-ai/app/retrieval/search.py` 尚不存在（`tests/test_search.py` 已先行落地、未跟踪），
  编排层实现由 shop-ai 自己的任务承接，本次不越界实现。

## 三、硬约束

1. 权重的「代码默认值」固定为 1.0/1.0（等权）—— 即未读到 yml 或配置非法时，行为与改造前逐字节等价、可随时回滚；1.0/2.0 只作为 `application.yml` 的显式生产档位。
2. 加权只影响融合排序，不得改变任一单路的召回集合；候选池对齐是唯一例外，且必须单独评估。
3. 向量路 embedding 输入仍是原始 query（同义词只喂关键词路），不得破坏。
4. `shop-ai/app/retrieval/rrf.py` 中单路分支的「有意分歧」（去重、跳过 None）必须保留，不得顺手对齐成 Java 行为。
5. `RrfMerger.merge(list1, list2, topK)` 三参重载保留，避免破坏既有调用与测试。
6. 关键词路 SQL 的 `WHERE deleted = 0 AND status = 1` 与 `MATCH ... AGAINST` 结构不得改动（Python 侧逐字对齐，改一处等于改两处）。

## 四、入参与出参

- `RagSearchService.search(query, topK, categoryId, minPrice, maxPrice)`
  - `topK`：clamp 到 `[1, 100]`，非正数不再抛异常。
  - `categoryId`：按 `firstCategoryId` 过滤。
  - `minPrice/maxPrice`：与向量路同语义（商品 `maxPrice >= minPrice`、`minPrice <= maxPrice`）。
- 配置：`rag.rrf.k`（int，必须 > 0）、`rag.rrf.weight-vector` / `weight-keyword`（double，>= 0，不可同时为 0）。
- 出参 `RagHitVO`：`score` 语义由「RRF 分数」变为「加权 RRF 分数」，量级随权重变化；前端不展示该字段（已确认）。

## 五、错误返回

| 场景 | 行为 |
|------|------|
| 权重配置非法（负数 / 全 0 / K <= 0） | 启动时打 WARN 并回退代码默认值 1.0/1.0/60（保守回退到等权，不套用 2.0 生产档位），不阻断启动 |
| 关键词路 SQL 异常 | 保持现有 try/catch 返回空列表，RRF 退化为纯向量路 |
| SKU 价格查询异常 | 本次过滤视为不生效（放行全部候选）并打 WARN，避免价格服务抖动导致丢结果 |
| `topK` 非法（<= 0） | clamp 到 1，不抛异常 |

## 六、容易踩的坑

1. **历史基线不可直接比**：0.639 / 0.722 / 0.482 是在「向量路候选被 topK 截断到 5」的条件下测出的
   （`RagEvaluationTest` 用 `topK=5` 调用）。候选池对齐后必须先重跑 1.0/1.0 作为**新基线**，
   再与加权档位比较；直接跟旧数字比会把两个变量混在一起。
2. **评估必须分集合看**：抬高关键词权重对基础集有利、对同义词/语义集有害（「手鸡」「打游戏手机」只有向量路能救），
   只看总平均会掩盖退化。
3. **K 与权重耦合**：K 越大两路分差越小，权重的相对影响被压缩。调权重时固定 K = 60，不要同时动。
4. **价格过滤是近似**：over-fetch 后仍可能候选不足；且 SQL 排序与内存过滤的顺序语义要写进注释。
5. **`RagFallbackTest` 会受影响**：该测试直接 new `RagSearchService`，构造器新增依赖时需同步更新。
6. **同义词扩展不受此次改动影响**，但加权后关键词路的召回会被放大，同义词表的错误扩散也会被放大，
   故同义词扩充必须与权重调整分开评估。

## 七、验收标准

- 单测（离线可跑，不依赖 Qdrant/DashScope）：
  - 加权公式正确；构造「rank 差 1 但权重翻转排序」的用例。
  - `K`、权重从配置生效；非法配置回退默认值。
  - 三参重载与旧行为等价。
  - 候选池对齐：mock 断言向量路收到的 limit 为 `queryLimit` 而非 `topK`。
  - 关键词路过滤：分类不匹配被剔除、价格区间不匹配被剔除、过滤后 VO 带价格。
  - `topK` clamp：负数/0 不抛异常。
- 评估（需 MySQL + Redis + Qdrant + DashScope 同时在线）：
  - 权重档位 `1.0/1.0`（新基线）、`1.0/1.5`、`1.0/2.0`，基础集与同义词集分别输出 Recall@3/@5、MRR。
  - 结果回写 `docs/rag-evaluation-report.md`。
- 回归：`RagFallbackTest`、`SynonymExpanderTest`、`RagMetricsTest` 全绿。

## 八、不做的事

- 不在 MySQL 内实现 BM25（实测 8.0.36：`SHOW VARIABLES LIKE '%bm25%'` 零结果；
  `innodb_ft_index_table` 只有 `WORD/DOC_COUNT/DOC_ID/POSITION`，无文档长度与平均长度统计，内核无法算 BM25）。
- 不引入自定义 MySQL 分词器插件（需写 C++ server 插件并与版本绑定）。
- 不改动同义词扩展的作用域（仍然只作用于关键词路）。
- 不实现 shop-ai 的编排层 `search.py`（属 shop-ai 自身任务）。
