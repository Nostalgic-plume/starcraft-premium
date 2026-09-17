# RAG 商品索引确定性 pointId 修复 — 设计文档

> 日期：2026-09-17　范围：`com.shop.rag.ProductIndexer`、`com.shop.rag.QdrantCollectionHelper`

## 一、问题（代码实测）

`ProductIndexer` 用进程内的 `ConcurrentHashMap<Long, String> pointIdMap` 保存 `productId → Qdrant pointId`，
而 `remove()` 完全依赖该映射定位待删点。进程重启后映射为空，由此产生三个缺陷：

| # | 缺陷 | 触发路径 | 后果 |
|---|------|---------|------|
| 1 | 下架商品删不掉 | 重启 → 商家下架商品 → `remove()` 找不到 pointId → 空操作 | 商品已下架仍能被 RAG 检索到（幽灵索引），业务结果错误 |
| 2 | 编辑商品重复累积向量 | 重启 → 编辑商品 → `addOrUpdate()` 内部先 `remove()`（失效）再 `add()` 生成新点 | 同一商品在 Qdrant 内存在多条向量且永不清理，RRF 合并时重复占位、挤掉真正多样的结果 |
| 3 | 统计值漂移 | `indexedCount` 仅在 `remove()` 命中映射时递减 | `/api/admin/rag/status` 的 `indexedCount` 只增不减 |
| 4 | 元数据空值导致索引整体失败 | 商品 `mainImage` / `firstCategoryId` / `shopId` 任一为 null → `Metadata.from()` 抛 IllegalArgumentException | 增量更新时该商品静默丢索引（异常被 `ProductServiceImpl` 吞掉，只记一条 warn）；全量重建时整批失败 |

另发现一处**重建错位**缺陷：`doRebuild()` 先过滤掉 0 维空向量得到 `validSegments`，随后却用
`products.get(i)` 下标回查商品 ID 建立映射。一旦中间有商品被跳过，ids 与 segments 便整体错位，
表现为「点 A 的向量挂着点 B 的文本」。

## 二、方案

**以 productId 作为确定性 Qdrant point id，彻底删除进程内映射。**

- 点 id 规则：`String.valueOf(productId)`；
- 写入：`embeddingStore.addAll(ids, embeddings, segments)`，同 id 重复写入即覆盖（upsert），`addOrUpdate` 不再「先删后加」；
- 删除：`embeddingStore.remove(String.valueOf(productId))`，与进程状态无关，重启后依然生效；
- 统计：`getIndexedCount()` 改为实时查询 Qdrant 集合内 point 数量。

同时修复**同一条写入路径上的既有缺陷：元数据空值防护**。`toMetadataMap()` 改为跳过所有 null 字段
（LangChain4j 的 `Metadata` 不接受 null 值），消除「一个字段为空 → 该商品丢索引 / 整批重建失败」的隐患。
缺失字段不参与元数据过滤，`passMetadataFilter` 对缺失键按「不过滤」处理，行为安全。

### 为什么点 id 可以直接用数字字符串

已用 `javap` 核对 `langchain4j-qdrant:1.19.0-beta29` 字节码：
`QdrantEmbeddingStore.toPointId(String)` 先按 `Long` 解析并调用 `PointIdFactory.id(long)`，非数字才回退 `UUID.fromString`。
因此纯数字 productId 会映射为 Qdrant 的 uint64 点 id，无需构造 UUID。
同版本 `addAll(List<String>, List<Embedding>, List<TextSegment>)` 与 `remove(String)` 均为重写实现，可直接使用。

## 三、硬约束

1. `productId` 不可为 null；`remove(null)` 直接返回，不抛异常。
2. 点 id 规则一旦确定不可再变，否则历史点既无法被覆盖也无法被删除。
3. 集合名必须取自 `ragProperties.getCollectionName()`，禁止硬编码（原 `hasExistingData()` 硬编码 `products`，一并修正）。
4. `ids / embeddings / segments` 三个集合长度必须严格相等且顺序一致。
5. 空向量（`vector().length == 0`）绝不写入 Qdrant，避免污染检索结果。
6. Qdrant 不可用时不得抛出异常打断商品主流程——调用方 `ProductServiceImpl` 已 try/catch 兜底。

## 四、传参 / 入参

| 方法 | 入参 | 说明 |
|------|------|------|
| `addOrUpdate(ProductDO)` | 商品实体；`status` 非上架直接 return | 内部查 SKU 价格区间写入元数据 |
| `remove(Long)` | 商品 ID；null 直接 return | 幂等：点不存在时删除为空操作 |
| `forceRebuildAll()` | 无 | 全量重建，先删集合再重建 |
| `getIndexedCount()` | 无 | 实时读取 Qdrant count |

## 五、错误返回

| 场景 | 行为 |
|------|------|
| Qdrant 不可用（count 查询失败） | `countPoints()` 记 warn 返回 0；`rebuildAll()` 视为「无数据」继续尝试重建 |
| 嵌入服务返回空向量 | 记 warn 跳过该商品，不写入 |
| 全部商品向量化失败 | 记 warn，更新 `lastRebuildTime` 后返回，不抛异常 |
| Qdrant 写入失败 | 由调用方 `ProductServiceImpl` 的 try/catch 捕获记 warn，不影响商品主流程 |

## 六、容易踩的坑

1. **上线必须重建一次**：历史点在 Qdrant 内是随机 UUID id，与新的数字 id 不是同一批，旧点不会被自动覆盖。必须调用一次 `POST /api/admin/rag/rebuild`（内部先删集合再重建）。
2. **`add(String, Embedding)` 不带元数据**：LangChain4j 的单条自定义 id 重载只有 `add(String, Embedding)`，没有带 `TextSegment` 的版本；要同时带元数据必须用 `addAll` 三参重载。
3. **不要再「先删后加」**：upsert 已覆盖旧点，先删多一次网络往返，且删除失败会留下空洞。
4. **`indexedCount` 语义变了**：由「进程内计数」变为「Qdrant 实时计数」，字段更准确，但 `status` 接口会增加一次 REST 调用（管理端点，可接受）。
5. **`embeddings.size()` 可能小于 `products.size()`**：分批调用嵌入服务时若某批失败，`embeddings` 会短于 `products`，循环必须以 `embeddings.size()` 为上界，否则越界。
6. **元数据不能有 null**：`Metadata.from()` 遇 null 直接抛异常，且异常会被上层 try/catch 吞成一条 warn。后续新增商品字段时必须走 `putIfNotNull`，不要直接 `meta.put(key, null)`。

## 七、验收标准

- 新增单测 `ProductIndexerTest` 5 个用例全绿（不依赖 Qdrant / MySQL / 嵌入服务）；
- 代码中不再出现 `pointIdMap`、`indexedCount` 字段；
- `addOrUpdate` 两次写入同一个点 id；
- 重建场景下点 id 与文本严格对应。