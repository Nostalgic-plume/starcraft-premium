# 检索结果与业务数据解耦计划书（只召回 ID + 回查）

> 关联：[`rag-id-only-retrieval-design.md`](rag-id-only-retrieval-design.md)
> 目标：召回阶段只产出商品 ID，展示数据在 topK 阶段统一回查；顺带消除两路 excerpt 语义不一致
> 分批原则：先加新组件（不破坏现有编译）→ 再改链路（一次改完）→ 最后索引与验证

## 任务清单

### 批次一：新增类型与组件（纯新增，不影响现有代码，可独立编译）

#### T1 · 新增 `RagCandidate`

- `record RagCandidate(Long productId, double score)`
- 仅定义类型，本批不接入调用方

**验收**：`mvn clean compile` 通过；现有测试全绿（证明确实无破坏）。

#### T2 · `SkuPriceResolver` 新增 `resolveSnapshot()`

- 新增 `SkuSnapshot`（`minPrice` / `maxPrice` / `totalStock`）与
  `Map<Long, SkuSnapshot> resolveSnapshot(List<Long> productIds)`
- **一次** sku 表查询同时取 `price` 与 `stock`（取代库存过滤 + 价格回查的两次查询）
- 保留既有 `resolve()` / `resolveStock()` 不动，供其他调用方继续使用
- 空值语义与既有方法一致：`stock` 为 null 按 0；无 SKU 的商品不在 Map 中；异常返回空 Map

**验收**：`SkuPriceResolverTest` 新增用例——三字段同时返回、无 SKU 不在 Map、null 库存按 0、异常返回空 Map。

#### T3 · 新增 `RagHitEnricher`

- `List<RagHitVO> enrich(List<RagCandidate> candidates)`
- 步骤：批量查 `id, name, main_image, description`（不读 `detail` 等大字段）→
  调 `resolveSnapshot` 取价格 → 组装 `RagHitVO`（`excerpt = truncate(description, 120)`）
- **必须保持入参顺序**（按 productId 映射回原序）
- 查不到的商品**跳过**；查询异常保留候选但字段为空并打 WARN

**验收**：新增 `RagHitEnricherTest`——正常回查字段完整且**顺序与入参一致**、查不到则跳过、
回查异常保留候选、`verify` 只查入参条数（锁定「只回查 topK」）。

### 批次二：链路改造（需一次改完，否则中间态编译不过）

#### T4 · `RrfMerger` 改操作 `RagCandidate`

- 入参/出参由 `RagHitVO` 改为 `RagCandidate`；三参重载保留
- 融合公式、权重、单路为空的透传分支**行为完全不变**

**验收**：`RrfMergerTest` 改造后 5 个用例全绿，断言值不变（证明是等价重构）。

#### T5 · `KeywordSearchService` 收窄 SELECT

- SQL 由 `SELECT *` 改为 `SELECT id, first_category_id`（`WHERE`/`MATCH` 结构一字不动）
- 方法改为返回 `List<RagCandidate>`；过滤逻辑改由 `firstCategoryId` + `resolveSnapshot` 提供
- ⚠️ `firstCategoryId` **必须留在 SELECT 列表内**，否则分类过滤静默失效

**验收**：`KeywordSearchServiceTest` 全绿；新增用例锁定「分类过滤仍生效」。

#### T6 · `RagSearchService` 接入新链路

- 两路候选改为 `RagCandidate`
- **顺序不变**：库存过滤 → RRF 合并 → 截断 topK → `RagHitEnricher.enrich()`
- ⚠️ 库存过滤**必须仍在回查之前**（它依赖候选池并集判定「是否全部售罄」）
- 库存过滤改用 `resolveSnapshot`（与回查共用一次查询语义）
- `allOutOfStock` 判定逻辑与语义保持不变

**验收**：`RagSearchServiceTest`、`RagStockFilterTest` 全部通过（签名变更处同步）；
新增断言：`enrich` 收到的候选数 = topK（而非候选池大小）。

### 批次三：索引瘦身与验证

#### T7 · `ProductIndexer` payload 瘦身

- `buildIndexText()` 拆为两部分：
  - **向量化输入**：保持「名称/描述/品牌/分类/详情」五段式（**严禁改动**）
  - **写入 payload 的 `text_content`**：改为仅商品名
- 在代码注释中显著标注二者区别，防止后人误改向量化输入

**验收**：`ProductIndexerTest` 全绿；新增用例断言「向量化输入仍为五段式拼接」。

#### T8 · 重建索引 + 全量验证

- `POST /api/admin/rag/rebuild` 全量重建，确认 `indexedCount = 278`
- 跑 `RagEvaluationTest`，与基线 `0.711 / 0.753 / 0.474` **逐项比对**
- 端到端：调 `/api/product/rag-search` 与 `/api/chat/ask`，确认
  **excerpt 不含「名称：/描述：」标签前缀**、两路格式一致、卡片文字可读
- 回填验收记录到设计文档 §八

**验收**：指标无回归 + excerpt 标签消失 + 商品集合与重建前一致。

## 风险与回滚

| 风险 | 缓解 |
|------|------|
| 误改向量化输入导致召回劣化 | T7 拆函数 + 注释警示 + T8 跑评估集比对 |
| 库存过滤被挪到回查之后，`allOutOfStock` 失真 | T6 显式锁定顺序；`RagStockFilterTest` 既有用例兜底 |
| 回查打乱 RRF 排序 | T3 用例显式断言顺序 |
| 关键词路收窄 SELECT 丢掉过滤字段 | T5 用例锁定分类过滤生效 |
| 重建期间检索降级 | 属预期（先删后建），安排在维护窗口 |

**回滚**：`RagHitVO` 对外结构未变，前端与客服链路无感。
回滚只需将 `RagSearchService` 的两路恢复为直接产出 `RagHitVO`；
索引侧若已重建，重跑一次重建即可恢复（向量化输入未变，语义不受影响）。
