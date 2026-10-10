# 检索链路库存过滤计划书

> 关联：[`rag-stock-filter-design.md`](rag-stock-filter-design.md)
> 目标：售罄商品从检索结果中剔除，并区分「缺货」与「没搜到」两种空结果
> 分批原则：检索层 → 对话层 → 端到端验证，前两批可独立编译

## 任务清单

### 批次一：检索层（可独立编译，不触碰对话链路）

#### T1 · `SkuPriceResolver` 新增 `resolveStock()`

- 新增方法 `Map<Long, Integer> resolveStock(List<Long> productIds)`
- 单次查询 `.select(SkuDO::getProductId, SkuDO::getStock)`，按 productId 分组求和
- 无 SKU 的商品**不放进 Map**；`stock` 为 null 按 0 计入；异常返回空 Map
- 同步更新类注释：该类由「价格解析器」扩展为「SKU 维度聚合」（价格 + 库存）

**验收**：新增 `SkuPriceResolverTest` 覆盖——正常求和、无 SKU 不在 Map、stock 为 null 按 0、查询异常返回空 Map。

#### T2 · 新增 `RagSearchResult` + `searchWithAvailability()` 骨架

- 新增结果类型（`hits` + `allOutOfStock`）
- `RagSearchService` 新增 `searchWithAvailability(...)`，语义与 `search()` 一致
- `search()` 改为**委托**新方法并返回 `hits`（签名、行为、clamp 全部不变）
- 本步**不引入库存过滤**，先把骨架跑通

**验收**：`mvn clean compile` 通过；既有 `RagSearchServiceTest` 三用例仍全绿（证明确实是等价重构）。

#### T3 · 候选池库存过滤

- 在 `RrfMerger.merge()` **之前**，取两路 productId 并集 → **一次** `resolveStock()` → 剔除售罄项
- 判定：商品**在 Map 中**且总库存 `<= 0` 才剔除；**不在 Map 中**（无 SKU）放行
- `allOutOfStock` = 「过滤前候选非空」且「过滤后为空」
- 降级态（向量路为空、纯关键词）同样走这段逻辑

**验收**：`RagSearchServiceTest` 新增用例——售罄剔除、部分售罄、全部售罄（含标记）、候选本身为空（标记为 false）、
无 SKU 放行、异常放行、`verify` 库存查询**只调用一次**。

#### T4 · 修既有测试构造器

- `RagSearchServiceTest`、`RagFallbackTest` 直接 `new RagSearchService(...)`，需补 `SkuPriceResolver` mock 参数
- 两个测试的断言逻辑不得改动（只补依赖）

**验收**：两个测试全绿，且改动仅限构造器一行。

### 批次二：对话层

#### T5 · `ChatService` 接入缺货信号

- 检索改调 `searchWithAvailability()`
- `hits` 为空 且 `allOutOfStock = true` → 向 `productContext` 注入「缺货提示」段落
  （参照 `buildOrderContext()` 的既有做法，不新增 LLM 调用）
- 话术要求：如实告知「暂时缺货」，**不得**说「没有这个商品」，**不得**转去推荐其他商品

**验收**：起服务后，把某商品的 SKU 库存全部置 0，向客服提问该商品，回答为「缺货」类话术
（而非「没搜到，您具体想找什么」）。

#### T6 · 分类回退路径补库存过滤

- `ChatService.fallbackSearchByCategory()` 查出的商品**同步剔除售罄项**
- 复用 T1 的 `resolveStock()`

**验收**：构造「RAG 无结果 → 分类回退命中但全部售罄」场景，确认不返回售罄商品。

### 批次三：端到端验证

#### T7 · 编译 + 启动自测 + 验收

- `mvn clean compile`（若需跑测试则 `mvn test`）
- 启动后端，调用 `/api/product/rag-search?q=手机`，确认结果无库存全零商品
- 用客服链路（SSE）验证缺货话术分支
- 回填实测结论到设计文档 §八

**验收**：上述三项全部通过，且 `RagSearchServiceTest` / `RagFallbackTest` / `KeywordSearchServiceTest` /
`RrfMergerTest` 回归全绿。

## 风险与回滚

| 风险 | 缓解 |
|------|------|
| 判售罄逻辑误杀无 SKU 商品 | T3 用例显式锁定「不在 Map 中即放行」 |
| 过滤导致返回条数不足 topK | 设计上已限定过滤在合并前；T3 用例锁定 |
| 库存查询拖慢检索 | 单次批量 `IN` 查询（候选池 50~200 个 id），`sku.product_id` 有索引；必要时后续加缓存 |
| 缺货话术被 LLM 忽略 | prompt 段落用与订单上下文相同的强约束写法；T7 实测把关 |

**回滚**：本改动不改数据库、不改对外接口契约。回滚只需把 `ChatService` 的调用改回 `search()`，
或直接在 `RagSearchService` 中停用过滤分支——`search()` 签名与行为始终未变。
