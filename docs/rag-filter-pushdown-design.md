# 向量路过滤下推（Qdrant payload filter pushdown）设计

> 状态：设计稿（2026-10-17）
> 关联：`docs/rag-hybrid-tuning-design.md`（候选池对齐）、`docs/rag-candidate-cleanup-design.md`（候选池清洗）
> 目标：把分类/价格过滤从「Qdrant 返回后应用层遍历」下推为「Qdrant 侧过滤」，消除向量路候选池因过滤而缩水的问题

## 一、现状与问题

### 1.1 应用层过滤导致向量路候选池缩水

当前 `RagSearchService.searchViaQdrant` 的流程：

```
Qdrant 取 maxResults = queryLimit 条  →  逐条 passMetadataFilter（分类/价格）  →  剩余入候选池
```

Qdrant 返回的 `queryLimit` 条里，不满足过滤条件的会被应用层丢掉，**且没有补取**。而关键词路有 `OVER_FETCH_FACTOR = 2` 的缓冲（多取一倍再过滤）。

结果：**窄筛选（如 `minPrice=1000&maxPrice=1100`）时向量路候选可能缩到个位数，关键词路仍能满额** → 两路名义上限相同、实际有效规模不同，向量路在 RRF 中的有效参与度被削弱。

### 1.2 为什么不能靠"向量路也 over-fetch"解决

over-fetch 是固定倍数，过滤选择性变化时无法自适应（淘汰 90% 时 2 倍仍不够）。且多取的候选要经网络传输到应用层再丢弃，纯属浪费。

**根因是过滤发生在数据源之外。下推让 Qdrant 先过滤再排序取数，`maxResults` 的语义从「取 N 条」变成「取 N 条满足条件的」。**

## 二、方案

### 2.1 给过滤字段建 payload 索引（前置条件）

**没有 payload 索引时，Qdrant 的过滤是全量扫描**，下推不会更快、甚至更慢。

在 `QdrantCollectionHelper` 新增 `ensurePayloadIndexes(name)`：

| 字段 | Qdrant schema | 对应过滤 |
|---|---|---|
| `firstCategoryId` | `integer` | 分类等值 |
| `minPrice` | `float` | 价格区间 |
| `maxPrice` | `float` | 价格区间 |

调用时机：
1. `createCollection()` 成功后（新集合）；
2. `ensureCollection()` 在集合**已存在**时也调用（覆盖存量集合无索引的情况）。

Qdrant 的 `PUT /collections/{name}/index` 对已存在索引是幂等的；在已有数据上创建索引由 Qdrant 后台完成，**不需要删集合重建**。

### 2.2 构造 Filter 并下推

`EmbeddingSearchRequest` 有 `filter(Filter)`（LangChain4j 1.19.0 已确认），用 `MetadataFilterBuilder` 表达与 `passMetadataFilter` **完全相同**的语义：

```java
firstCategoryId == categoryId                                  // 分类
AND maxPrice >= minPrice(查询)                                  // 价格区间相交
AND minPrice  <= maxPrice(查询)
```

三个条件按入参是否为 null 动态拼接（全部为空 → `filter = null`，即不下推，行为与改造前一致）。

### 2.3 保留应用层过滤作为兜底（**硬约束**）

下推生效时 `passMetadataFilter` 是空转；下推未生效（LangChain4j→Qdrant 的 filter 转换有问题 / 字段类型不匹配）时它兜住正确性。**不做二选一。**

### 2.4 加「下推是否生效」的证据日志

只看结果无法区分「下推生效」与「应用层兜住了」——两者返回的结果一样。必须观测 **Qdrant 返回条数**：

- 下推生效 → `matches.size()` 通常 **< maxResults**（Qdrant 侧已过滤）
- 未生效 → `matches.size()` 恒等于 maxResults（或仅受 minScore 影响）

带 filter 时输出一条 INFO 日志，作为验证依据。

## 三、硬约束

1. **不改 `search()` / `searchWithAvailability()` 的签名**，对外契约不变。
2. **保留应用层 `passMetadataFilter`**（兜底），不得因为"已下推"而删除。
3. **下推的过滤语义必须与 `passMetadataFilter` 逐条一致**：分类比对 `firstCategoryId`；价格是**区间相交**（`maxPrice >= min` 且 `minPrice <= max`），不是"商品价格完全落在区间内"。
4. **filter 构造失败不得中断检索**：构造/下推抛异常时回退为 `filter = null`（等价于改造前行为），由应用层过滤兜底。
5. **沿用 REST 风格建索引**：`QdrantCollectionHelper` 现有实现统一用 REST（刻意避开 gRPC），新增的索引逻辑保持一致。
6. **不删 `minScore`**：它与 payload filter 是两层不同语义（相关性门槛 vs 业务条件）。

## 四、入参与出参

### `QdrantCollectionHelper`（新增内部方法）

```
void ensurePayloadIndexes(String collectionName)
  入参：集合名
  出参：无
  行为：对 firstCategoryId(integer) / minPrice(float) / maxPrice(float) 各发一次
        PUT /collections/{name}/index?wait=true，已存在时幂等
  异常：单个字段失败只 WARN，不影响其余字段与集合本身
```

### `RagSearchService`（内部，不对外）

```
Filter buildMetadataFilter(Long categoryId, BigDecimal minPrice, BigDecimal maxPrice)
  出参：三者都为空 → null（不下推）；否则为组合后的 Filter
```

## 五、错误返回

| 场景 | 行为 |
|---|---|
| filter 构造异常 | 捕获后置 `filter = null`，打 WARN，检索继续（应用层过滤兜底） |
| Qdrant 不支持 / 忽略 filter | 返回条数仍 ≈ maxResults，应用层过滤兜底，结果正确但无收益 |
| payload 索引未建 | 过滤仍可用但为全扫；`ensurePayloadIndexes` 启动时补建 |
| 建索引失败 | 只 WARN，不影响检索（降级为全扫） |
| 三个过滤条件都为空 | `filter = null`，请求与改造前完全一致 |

## 六、容易踩的坑

1. **价格字段类型**：payload 存的是 `double`（`BigDecimal.doubleValue()`），Qdrant schema 用 `float`。范围比较的精度误差可忽略，但**不能用 `keyword`/`integer` schema**（类型不匹配会导致过滤结果为空）。
2. **"结果一样"不能证明下推生效**：必须看 Qdrant 返回条数（§2.4）。这一步漏了，很可能上线一个静默无效的改动。
3. **窄过滤 + ANN 的质量**：Qdrant 带 filter 的近似搜索在过滤条件很窄时候选集变小，召回质量可能下降。这是下推的固有代价，不是 bug；若实测发现指标劣化，应配合适度放大 `maxResults`（而不是回退应用层过滤）。
4. **不要同时删掉 over-fetch**：关键词路的 `OVER_FETCH_FACTOR` 与本次无关（它过滤在 SQL 结果上），别顺手改。
5. **`firstCategoryId` 的 Long 拆箱**：`metadataKey("firstCategoryId").isEqualTo(categoryId)` 传入 `Long` 会走 `isEqualTo(long)` 重载，判空必须在调用前完成。

## 七、不做的事

- **不做品牌过滤**（用户明确本次不做；`brandId` 未进 payload）。
- **不做动态 over-fetch**（下推后收益边际化，暂不引入复杂度）。
- **不把关键词路的过滤下推到 SQL**：`KeywordSearchService` 的注释已论证 —— FULLTEXT 排序必须在 SQL 内完成，叠价格子查询会拖慢且需同步维护 Python 侧对齐。
- **不重建集合**：payload index 可在现有数据上创建，无需删库。
- **不改 `minScore`**。

## 八、验收标准

### 单元测试（离线）

- 三个过滤条件都为空 → 不下推（`filter == null`）；
- 仅分类 → 下推且结果正确；
- 仅价格 / 分类+价格 → 下推且结果正确；
- 下推结果与应用层过滤结果**一致**（构造"Qdrant 返回了不符合条件的点"场景，验证兜底生效）；
- filter 下推抛异常 → 检索不中断、结果正确。

### 编译与回归

- `mvn clean test-compile` 通过；
- `RagSearchServiceTest` / `RagFallbackTest` / `RagStockFilterTest` / `RagDisplayableFilterTest` / `RagHitEnricherTest` 全绿。

### 真实链路（需 Qdrant + TEI + MySQL）

1. `GET /collections/products` 的 `payload_schema` 含 `firstCategoryId` / `minPrice` / `maxPrice`；
2. 带 `categoryId` 检索，日志中 Qdrant 返回条数 **< maxResults**（下推生效证据）；
3. 带价格区间检索，返回结果全部满足区间相交；
4. 不带过滤检索，行为与改造前一致；
5. `RagEvaluationTest` 指标与基线（0.711 / 0.753 / 0.474）对照，确认无劣化。

## 九、验收记录（2026-10-17）

### 已执行

| 项 | 结果 |
|---|---|
| 编译 | ✅ `mvn -q clean test-compile` 通过 |
| 离线回归 | ✅ **38 用例全绿**（`RagSearchServiceTest` 3 / `RagFallbackTest` 3 / `RagStockFilterTest` 8 / `RagDisplayableFilterTest` 7 / `RagHitEnricherTest` 12 / `RrfMergerTest` 5） |
| payload 索引补建 | ✅ 重启时 `ensureCollection` 幂等补建，日志三条「就绪」；`GET /collections/products` 的 `payload_schema` 含 `minPrice`(float, points=670) 等，**未删集合** |
| 下推生效证据 | ✅ 见下条（构造了选择性极高的条件） |
| 无过滤对照 | ✅ `filter=null` 时不输出下推日志，请求与改造前一致 |

### 下推生效的判定过程（⚠️ 这一节的方法论比结论更重要）

第一次验证用「窄价格区间 1000~1100」，日志显示 **50/50 条**，一度误判为下推未生效。
**实际原因是过滤语义是「区间相交」**：mock 商品价格跨度普遍为几百~上万，
`maxPrice >= 1000 AND minPrice <= 1100` 几乎人人满足，满足条件数 > maxResults(50)。

改用**选择性极高**的条件重测，得到决定性证据：

| 测试条件 | 满足条件的商品数 | Qdrant 返回 | 结论 |
|---|---|---|---|
| `maxPrice=1`（商品 minPrice 均远大于 1） | 0 | **0/50** | ✅ 下推生效 |
| `categoryId=8`（该分类在售 9 个） | 9 | **9/50** | ✅ 下推生效，数值精确吻合 |
| 窄价格 1000~1100 | > 50 | 50/50 | ⚠️ 条件太宽松，**不能作为判据** |
| 无过滤 | — | 无下推日志 | ✅ 行为不变 |

**教训**：验证「下推是否生效」必须构造**满足条件数 < maxResults** 的场景。
50/50 既可能是「未生效」，也可能是「条件太宽松」，光看结果无法区分 —— 这正是 §2.4 要求打「Qdrant 返回条数」日志的价值。

### LangChain4j 支持性确认

`langchain4j-qdrant-1.19.0-beta29` 的 `QdrantEmbeddingStore.class` 常量池含
`QdrantFilterConverter` / `qdrantFilter` / `setFilter`，确认 `EmbeddingSearchRequest.filter()`
会被转换为 Qdrant 侧过滤条件（不是被忽略）。

### 实际收益观察

窄筛选下向量路不再缩水：`categoryId=8` 时 Qdrant 只返回该分类的 9 条，
候选池里不再混入其余 650+ 条将被应用层丢弃的候选。
