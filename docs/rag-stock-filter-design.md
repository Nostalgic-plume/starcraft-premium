# 检索链路库存过滤设计（售罄剔除 + 缺货话术）

> 状态：设计稿（2026-10-10）
> 关联：`docs/rag-hybrid-tuning-design.md`、`docs/rag-code-map.md`
> 目标：消除「AI 客服推荐已售罄商品」的体验缺陷，并区分「缺货」与「没搜到」两种空结果

## 一、现状与问题（证据）

### 1.1 现象

客服链路检索到售罄商品后，LLM 会正常推荐（「这款性价比很高，才 1999 元」），
SSE `products` 事件推给前端的商品卡片**不含任何库存信息**，用户点进详情页才发现买不了。

### 1.2 证据链

| # | 事实 | 代码位置 |
|---|------|---------|
| 1 | 索引侧只按 `status = 1` 选品，**从不读库存** | `ProductIndexer.doRebuild()` / `addOrUpdate()` |
| 2 | 向量库 metadata 的 7 个字段中**没有库存** | `ProductIndexer.toMetadataMap()` |
| 3 | 两条检索路的过滤（分类/价格）**都不看库存** | `RagSearchService.passMetadataFilter()`、`KeywordSearchService.passFilter()` |
| 4 | `RagHitVO` **没有库存字段** | `RagHitVO` |
| 5 | 客服链路以 `topK=3` 调用检索 | `ChatService.ask()` |
| 6 | RAG 空结果的分类回退路径也只过滤 `status = 1` | `ChatService.fallbackSearchByCategory()` |

**决定性的一条是 #4**：`RagHitVO` 没有库存字段，所以下游（拼 prompt、推卡片、前端渲染）
即便想补一道过滤也拿不到数据。

### 1.3 与商品列表页的对照（⚠️ 勿误判）

商品列表页 `ProductServiceImpl.doPage()` **同样只过滤 `status = 1`，不过滤库存**；
但 `ProductVO` 有 `totalStock` 字段，前端能据此标记「无货 / 仅剩 X 件」。

| | 展示售罄商品 | 带库存信息 | 前端能否标记 |
|---|---|---|---|
| 商品列表页 | ✅ | ✅ `totalStock` | ✅ |
| RAG 商品卡片 | ✅ | ❌ 无 | ❌ |

**结论**：两条路径在「展示售罄商品」上是一致的，**真正的缺口是列表页有库存信息、RAG 没有**。
本次改动**不是**「让 RAG 与列表页保持一致」（列表页展示售罄是既定行为），而是
「客服推荐场景不推荐买不到的商品」——这是**场景差异，不是一致性问题**。

## 二、方案

**A+：剔除售罄商品 + 区分缺货话术。**

### 2.1 过滤位置：RRF 合并**前**的候选池层

在 `RagSearchService.search()` 中，两路候选（`vectorHits` / `keywordHits`）取到之后、
`RrfMerger.merge()` **之前**，对两路 productId 的**并集**做一次批量库存查询，剔除售罄项。

**为什么必须在合并前**：若在合并取 `topK` 之后再过滤，返回条数会不足（客服链路 `topK=3`，
若过滤掉 2 条则只剩 1 条）；合并前过滤保证候选池干净，RRF 产出仍可满额。

**为什么对并集查一次**：两路候选有重叠，去重后一次 `IN` 查询即可，避免两路各查一次。

### 2.2 售罄判定语义

`售罄 ⟺ 该商品所有 SKU 的 stock 之和 <= 0`

- 用**总量**而非「任意 SKU 有货」：两者在 `stock >= 0` 的业务约束下等价，总量可复用为未来的库存展示值。
- `stock` 为 `null` 的 SKU 按 0 计入。

### 2.3 缺货信号传递

新增结果类型（`RagSearchResult`），携带「是否因全部售罄而被过滤空」：

```
hits          过滤后的命中列表
allOutOfStock true = 候选非空但全部售罄被剔除（区别于「真的没搜到」）
```

`RagSearchService` **新增** `searchWithAvailability(...)` 方法返回该类型；
**保留** `search(...)` 原签名不变（内部委托新方法、丢弃缺货标记）。
—— 新增而非改签名，故 `RagSearchController` 与对外契约**不受影响**。

### 2.4 `ChatService` 侧

1. 检索改调 `searchWithAvailability()`
2. `hits` 为空且 `allOutOfStock = true` → 向 prompt 注入「缺货提示」段落（参照 `buildOrderContext()` 的既有做法），
   引导 LLM 回答「这款商品暂时缺货」，而非走「没找到，您具体想找什么」的引导话术
3. `fallbackSearchByCategory()` 的分类回退路径**同步补库存过滤**（否则缺口仍在）

## 三、硬约束

1. **不改 `search()` 签名**：`RagSearchController` 及对外契约保持原样，`topK` clamp 行为不变。
2. **过滤必须在 RRF 合并前**，不得改为合并后过滤（会导致返回条数不足 `topK`）。
3. **库存查询异常一律放行**：与现有「字段缺失即放行」语义一致，避免库存查询抖动导致整体丢结果。
4. **无 SKU 的商品放行**：本次目标是「不推荐售罄商品」，不是「只推荐有 SKU 的商品」，保持最小行为变更。
5. **不改动关键词路内部的价格查询**（`resolvePrices` 在 `KeywordSearchService` 内），避免扩大改动面。
6. **不改 `SkuPriceResolver.resolve()` 的签名与语义**，仅新增方法。
7. 库存**不写入 Qdrant payload**：高频变化字段适合回查而非下推（详见 §7）。

## 四、入参与出参

### `SkuPriceResolver`（新增）

```
Map<Long, Integer> resolveStock(List<Long> productIds)
  入参：商品 ID 列表（可为 null / 空）
  出参：productId → 该商品所有 SKU 的库存之和
        无 SKU 的商品**不在 Map 中**
        查询异常返回空 Map
```

### `RagSearchService`（新增）

```
RagSearchResult searchWithAvailability(String query, int topK,
                                       Long categoryId, BigDecimal minPrice, BigDecimal maxPrice)
  入参语义与 search() 完全一致（topK clamp 到 [1,100]）
  出参：hits          过滤后的命中列表（可能少于 topK，属预期）
        allOutOfStock true 仅当「合并前候选非空 且 过滤后为空」
```

### 保持不变的对外契约

`RagSearchService.search(...)` 返回 `List<RagHitVO>`；`RagSearchController` 的
`hits` / `total` / `query` 三个字段不变。

## 五、错误返回

| 场景 | 行为 |
|------|------|
| 库存批量查询异常 | 打 WARN，返回空 Map → 过滤不生效（全部放行），检索照常返回 |
| 商品无 SKU（不在 Map 中） | 放行 |
| SKU `stock` 为 null | 按 0 计入总库存 |
| 全部候选售罄 | `hits` 为空、`allOutOfStock = true` |
| 候选本身为空（没搜到） | `hits` 为空、`allOutOfStock = false` |
| 库存查询后部分售罄 | 正常返回剩余项，`allOutOfStock = false` |

## 六、容易踩的坑

1. **`RagSearchServiceTest` 与 `RagFallbackTest` 直接 `new RagSearchService(...)`**：
   构造器新增依赖（`SkuPriceResolver`）时**必须同步更新**这两个测试，否则编译失败。
   （同类坑在 `rag-hybrid-tuning-design.md` §六.5 已记录过一次。）
2. **别用 `getOrDefault(id, 0)` 判售罄**：无 SKU 的商品不在 Map 中，用 `getOrDefault` 会被误判为售罄而剔除，
   违反 §三.4 的「放行」约定。必须区分「不在 Map 中」（放行）与「在 Map 中且 <= 0」（剔除）。
3. **`fallbackSearchByCategory()` 容易漏改**：它是 `ChatService` 内部另一条独立的取数路径，
   不在 `RagSearchService` 内，不补则「RAG 无结果 → 分类回退 → 又推出售罄商品」。
4. **过滤后条数可能少于 `topK`**：这是预期行为，不要为了「凑满 topK」而放宽判定。
5. **`allOutOfStock` 的判定条件**：必须是「合并前候选非空 且 过滤后为空」。
   若候选本身为空也会置 true，则「没搜到」会被误报为「缺货」。
6. **降级态同样要过滤**：嵌入模型故障时向量路为空、退化为纯关键词，此时候选池非空，
   库存过滤**照常生效**（过滤发生在 RRF 之前，与向量路是否降级无关）。

## 七、不做的事

- **不把库存写入 Qdrant payload**：库存高频变化（下单扣减 / 取消回滚 / 超时取消 / 商家改库存 /
  Redis 预扣回滚 / 定时兜底），挂钩点横跨 Redis Lua、RocketMQ 异步落单、延迟消息、定时扫描，
  漏挂任何一处都会让向量库里的库存标记**永久错误且无声**。用一次批量 DB 查询换掉一整类同步缺陷。
- **不做「有货优先排序」**：售罄项直接剔除，不保留排序位次。
- **不改商品列表页**：列表页展示售罄商品是既定行为（带 `totalStock` 供前端标记），本次不动。
- **不给 `RagHitVO` 加库存字段**：本次只做剔除，不做「仅剩 X 件」展示。
- **不同步 shop-ai Python 侧**：AI 服务 Python 化已暂停（2026-10-08），本次仅 Java 侧。

## 八、验收标准

### 单元测试（离线可跑）

- 售罄商品被剔除、有货商品保留；
- 部分售罄 → 只返回有货项；
- 全部售罄 → `hits` 空 + `allOutOfStock = true`；
- 候选本身为空 → `hits` 空 + `allOutOfStock = false`；
- 无 SKU 商品 → 放行；
- 库存查询异常 → 全部放行（不丢结果）；
- 两路并集只发起**一次**库存查询（Mockito 校验）；
- 库存过滤调用发生在 `RrfMerger.merge` 之前（构造「过滤后不足 topK」场景锁定）。

### 回归

`RagSearchServiceTest`、`RagFallbackTest`、`KeywordSearchServiceTest`、`RrfMergerTest` 全绿。

### 启动自测

- `mvn clean compile` 通过；
- 后端启动后调用 `/api/product/rag-search?q=手机`，返回结果中不含库存全零的商品；
- 构造「某商品全部 SKU 库存置 0」的数据，确认其从检索结果中消失。

## 九、验收记录（2026-10-10）

### 已执行

| 项 | 结果 |
|----|------|
| `mvn test -Dtest=RagStockFilterTest` | ✅ 8 用例全绿（售罄剔除 / 部分售罄 / 全部售罄标记 / 候选为空不误报 / 无 SKU 放行 / 查询异常放行 / 并集只查一次 / 合并前过滤） |
| `mvn test -Dtest=RagSearchServiceTest,RagFallbackTest,KeywordSearchServiceTest,RrfMergerTest,SynonymExpanderTest` | ✅ 27 用例全绿（含构造器变更后的既有回归） |
| `mvn test -Dtest=RagEvaluationTest`（真实 MySQL + Qdrant + TEI） | ✅ 3 用例通过，Spring 上下文装配正常（含 `ChatService` 新增依赖） |

### 评估集零差异（无回归、无误杀）

| 配置 | 本次实测 | 改动前基线（`rag-evaluation-report.md` §9.3） |
|------|---------|---------------------------------------------|
| 基础集 1.0/1.0 | 0.661 / 0.736 / 0.526 | 0.661 / 0.736 / 0.526 |
| 基础集 1.0/1.5 | 0.678 / 0.753 / 0.488 | 0.678 / 0.753 / 0.488 |
| 基础集 **1.0/2.0（生产档）** | **0.711 / 0.753 / 0.474** | **0.711 / 0.753 / 0.474** |
| 同义词集 1.0/2.0 | 0.767 / 0.867 / 0.529 | 0.767 / 0.867 / 0.529 |

指标逐项完全一致，说明评估集商品均有库存、未被误杀 —— 这是「误杀」风险的反向证据。

### 待执行（需启动后端）

- `/api/product/rag-search` 端到端调用；
- 客服链路「全部售罄 → 缺货话术」实测（需先构造某商品全部 SKU 库存置 0 的数据）。
