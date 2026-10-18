# 检索候选池清洗与向量库 payload 精简设计

> 状态：设计稿（2026-10-16）
> 关联：`docs/rag-stock-filter-design.md`（库存过滤，同构前例）、`docs/rag-id-only-retrieval-design.md`（ID-only 契约）、`docs/rag-code-map.md`
> 目标：① 修掉「管理员新增商品价格筛选失效」的真实缺陷 ② 让向量库 payload 只留有人消费的字段 ③ 补上「下架 / 已删除商品的幽灵索引」在候选池层的前置过滤

## 一、现状与问题（证据）

### 1.1 payload 存了 3 个无人消费的字段

全 `com.shop.rag` 包 grep 后，payload 字段的**消费点只有 4 个**（均在 `RagSearchService`）：

| payload 字段 | 写入（`ProductIndexer.toMetadataMap`） | 消费点 | 判定 |
|---|---|---|---|
| `productId` | ✓ | ✓ 构造候选（L340） | 必存 |
| `firstCategoryId` | ✓ | ✓ 分类过滤（L360） | 必存 |
| `minPrice` / `maxPrice` | ⚠️ 见 1.2 | ✓ 价格过滤（L368 / L376） | 必存（写入待修） |
| `shopId` | ✓ | **零消费** | 预留（多商家过滤） |
| `name` | ✓ | **零消费**（回查已覆盖） | 冗余 → 删 |
| `mainImage` | ✓ | **零消费**（回查已覆盖） | 冗余 → 删 |
| `text_content`（仅商品名） | ✓ | **零消费**（注释自陈"仅供排查认点"） | 保留 |

`ChatService` 中的 `item.put("name", ...)` 取的是回查后的 `RagHitVO.getName()`，**不是**读 payload。

### 1.2 管理员新增商品的价格字段缺失（真实缺陷）

`ProductServiceImpl.addProduct` 的执行顺序：

```
productMapper.insert(product)            // L140
productBloomFilter.put(...)              // L144
productIndexer.addOrUpdate(product)      // L148  ← 此时 SKU 尚未落库
skuMapper.insert(sku)                    // L153-159  ← SKU 在此之后插入
```

`addOrUpdate` 在索引期要读 SKU 价格区间（`SkuPriceResolver.resolveOne`），此时查不到 SKU
→ 返回 `null` → `toMetadataMap` 的 `if (prices != null && prices.length == 2)` 不成立
→ **payload 中完全没有 `minPrice` / `maxPrice`**。

而 `passMetadataFilter` 对**缺失键按「不过滤」放行**（`if (maxP != null && ...)`），
故该商品在价格区间筛选时**永远不会被淘汰**。

**影响面**：仅**管理员新增**路径（商家新增时 `status = 0`，`addOrUpdate` 首行的
`status != 上架 → return` 直接拦掉，属无害的无用调用；管理员新增时 `status = 1`，缺陷生效）。
**症状是静默的**——无异常、无日志，只能靠比对筛选结果发现。
关键词路不受影响（其价格过滤走 SQL 实时查 sku），故同时构成**两路过滤语义不一致**。

### 1.3 下架 / 已删除商品的幽灵索引缺前置防线

向量库与 MySQL 的一致性靠「索引更新尽力而为」维持：

- `productIndexer` 的调用一律包在 try-catch 中，**失败只 WARN 不阻断主流程**
  （`ProductServiceImpl` L149-151 / L233-235 / L470-472）
- DB 写入与索引更新**非事务**，中间失败或进程崩溃即不同步

故向量库可能残留**已下架商品的幽灵索引**（`ProductIndexer.remove` 的注释自身即提到此风险）。
payload 里**没有 `status` 字段**，Qdrant 侧无法过滤；现存唯一防线是 topK 之后的
`RagHitEnricher.enrich` 强制 `status = 上架` 剔除。

**问题在于这是「合并后过滤」**：`enrich` 只对 topK 做过滤且不补位，客服链路 `topK=3`
若剔除 2 条就只剩 1 条返回 —— 与库存过滤当初必须前置到 RRF 合并前**是同一个理由**
（见 `rag-stock-filter-design.md` §2.1）。

### 1.4 ⚠️ 一个被推翻的方案（记录以免重蹈）

初版方案曾提出「把 `status` 写入 payload，在 Qdrant 侧过滤幽灵索引」。**这是错的**：
索引侧只建上架商品（`addOrUpdate` 首行检查 + `doRebuild` 只查上架），
故 payload 里的 `status` **恒为 1**；而幽灵索引的 payload 也是它**当年索引时**的快照（同样为 1）
—— 过滤不掉任何东西。要点：**payload 里的字段是写入时刻的快照，无法表达「之后发生的变化」**。

## 二、方案

三项改动，其中 2、3 独立，1 为顺手修复：

### 2.1 修复索引同步顺序

把 `addProduct` 中的 `productIndexer.addOrUpdate(product)` 移到 **SKU 落库之后**，
并加注释把「必须晚于 SKU」这个**隐式顺序契约**显式化（否则后续有人"优化"回前面会静默复发）。

### 2.2 精简 payload 字段

`toMetadataMap` 删除 `name` / `mainImage`（零消费），保留 `shopId` 并注明预留用途。

### 2.3 候选池清洗：新增「可展示性」前置过滤

在 `RagSearchService.searchWithAvailability` 的候选池清洗层（RRF 合并**前**），
与库存过滤**并列**新增一次「可展示 id」查询：

```
候选池清洗 = 剔除「不可展示」∪ 剔除「全部售罄」
不可展示 ⟺ 候选 id 不在「当前上架且未逻辑删除」的 id 正集内
```

用**正集**语义而非反集（查 `status != 上架`）的原因：`deleted = 1` 的记录被
MyBatis-Plus 的 `@TableLogic` 自动排除，**正集能一次覆盖「已下架」与「已逻辑删除」两种失效，
反集做不到**（反集只能查到下架的，查不到已删的）。

**为什么必须在合并前**：同库存过滤 —— 合并后过滤会导致返回条数不足 topK。

**为什么用实时查询而非 payload 快照**：见 1.4。

## 三、硬约束

1. **不改 `search()` / `searchWithAvailability()` 的签名**：`RagSearchController`、`ChatService`
   及对外契约保持原样。
2. **可展示性过滤必须在 RRF 合并前**，不得改为合并后过滤。
3. **状态查询异常一律放行**：与 `rag-stock-filter-design.md` §三.3 一致 ——
   查询抖动绝不能让候选池被清空（否则"数据库抖动"升级为"检索全空"，比现状更糟）。
4. **不把 `status` 写入 Qdrant payload**（理由见 1.4 + §七）。
5. **不给 `RagSearchService` 新增构造器依赖**：可展示 id 查询由**已注入**的
   `RagHitEnricher` 承担（它已持有 `ProductMapper`），避免触发
   `rag-stock-filter-design.md` §六.1 记录的「构造器变更 → 多个测试编译失败」坑。
6. **`addProduct` 中 SKU 落库必须先于 `addOrUpdate`**（顺序契约）。
7. 不改 `SkuPriceResolver` 的方法签名与语义。
8. 不改 `RagHitEnricher.enrich` 的行为（回查兜底保留，见 §七）。

## 四、入参与出参

### `RagHitEnricher`（新增）

```
Set<Long> findDisplayableIds(Collection<Long> productIds)

入参：候选商品 ID 集合（调用方保证非空）
出参：其中「当前上架且未逻辑删除」的 ID 集合（正集）
异常：查询异常**向上抛出** —— 由调用方决定策略（检索链路按「异常即不过滤」处理）
```

⚠️ 刻意**不在本方法内消化异常**（与同类 `loadProducts` 的内部 catch 风格不同）：
「空集合」在正集语义下等价于「全部不可展示」，若与「查询失败」共用同一返回值，
调用方会把失败误读为「全剔除」→ 检索返回空。抛异常是唯一无歧义的表达。

### `RagSearchService`（内部，不对外）

```
Set<Long> findNotDisplayableIds(List<RagCandidate> vectorHits, List<RagCandidate> keywordHits)

出参：需剔除的商品 ID 集合（= 候选并集 − 可展示正集）
      候选并集为空、或查询异常 → 返回空集合（不过滤）
```

`removeOutOfStock` 重命名为 `removeByIds`（通用剔除，两处复用），签名与行为不变。

### 保持不变的对外契约

`RagSearchService.search(...)` → `List<RagHitVO>`；`searchWithAvailability(...)` → `RagSearchResult`；
`RagSearchController` 的 `hits` / `total` / `query` 不变；`RagHitVO` 字段不变。

## 五、错误返回

| 场景 | 行为 |
|------|------|
| 可展示 id 查询异常 | 打 WARN，`findNotDisplayableIds` 返回空集合 → 该过滤不生效（放行），检索照常返回 |
| 候选商品已下架 | 从候选池剔除（RRF 合并前），不再进入 topK |
| 候选商品已逻辑删除 | 同上（正集自动覆盖） |
| 候选并集为空 | 跳过查询，返回空集合 |
| 可展示查询与库存查询同时生效 | 两者取并集一次剔除，各自独立降级 |
| 全部候选都不可展示 | `hits` 为空，且 `allOutOfStock` **必须为 false**（下架 ≠ 缺货）—— 由新增的 `notDisplayableIds.isEmpty()` 条件保证，见 §六.8 |

## 六、容易踩的坑

1. **3 个既有测试会因 Mockito 默认值而全挂**：`RagSearchServiceTest`、`RagFallbackTest`、
   `RagStockFilterTest` 均直接 `new RagSearchService(...)` 且把 `ragHitEnricher` 整体 mock。
   Mockito 对 `Set` 返回类型的默认值是**空集合**（非 null），而空集合在正集语义下 =
   「全部不可展示」→ 所有候选被剔除 → 测试断言全失败。
   **必须在每个测试的 `@BeforeEach` 补一条放行 stub**（返回入参原样）。
2. **payload 删字段需重建索引才生效**：不重建不会报错（无人读），只是 Qdrant 里仍留着旧字段。
3. **历史数据的价格字段缺失也要重建索引才能补上**：本次改的只是代码路径，
   已存在的「管理员新增」商品在 payload 里仍缺价格，必须 `POST /api/admin/rag/rebuild` 重建一次。
   ⚠️ 重建会**先删集合**（`recreateCollection`），故必须确认 TEI 就绪 ——
   否则向量化全空会导致集合被清空（`markRebuildFinished` 的注释已警示此点）。
4. **`addProduct` 的顺序是隐式契约**：删掉注释或调整位置会让 1.2 的缺陷静默复发。
5. **别用「反集」查状态**：`status != 上架` 查不到 `deleted = 1` 的记录，会漏掉已删除商品。
6. **`findDisplayableIds` 不能吞异常**：见 §四 的警告。
7. **日志计数要分开**：状态剔除数与售罄剔除数分别输出，否则线上无法区分两类清洗。
8. **`allOutOfStock` 必须把「不可展示」的剔除排除在外**（实现期才发现，§九 有完整记录）：
   该标记原为 `merged.isEmpty() && 候选非空`，而候选被整体下架剔除时 `merged` 同样为空 →
   会误报为「缺货」，让客服答出「这款暂时缺货」，真实原因却是商品已下架不可售。
   已补第三个条件 `notDisplayableIds.isEmpty()`。**这类缺陷单看实现代码不易发现，
   是靠先写测试用例暴露的** —— 新增用例 `offShelfRemovalShouldNotBeFlaggedAsOutOfStock` 锁定。

## 七、不做的事

- **不把 `status` / 库存写入 Qdrant payload**：高频变化字段适合回查而非下推
  （`rag-stock-filter-design.md` §七 已论证一次）；且 `status` 快照恒为 1，写进去也无意义。
- **不删 `RagHitEnricher.enrich` 的回查**：它承担**索引与主库不一致时的真相校验**
  （强制 `status = 上架` + 跳过已删除），本设计的前置过滤解决的是「条数不足」，
  两者是**两层防护而非替代**。
- **不删 `shopId`**：它是过滤语义字段（非展示冗余），预留多商家检索。
- **不删 `text_content`**：保留排查认点能力（删除后无法从 Qdrant 侧人工辨认点）。
- **不做 reranker**：经评估，当前瓶颈不在排序（Recall@5=0.753 / MRR=0.474，命中平均落在第 1.6 位），
  且改造需放开 payload 文本 + 契约变更，成本高于收益 —— 独立评估，不在本次范围。
- **不给 `RagHitVO` 加状态字段**：本次只做剔除，不做「已下架」展示。
- **不同步 shop-ai Python 侧**：AI 服务 Python 化已暂停（2026-10-08），本次仅 Java 侧。

## 八、验收标准

### 单元测试（离线可跑）

- 已下架商品被剔除、上架商品保留；
- 已逻辑删除商品被剔除（正集语义的关键差异点）；
- 查询异常 → 全部放行（不丢结果）；
- 候选并集为空 → 不发起查询；
- 可展示查询与库存查询**各自只发起一次**（Mockito 校验）；
- 过滤发生在 `RrfMerger.merge` 之前（构造「过滤后仍能补位满 topK」场景锁定）；
- `findDisplayableIds` 的 SQL 条件含 `status = 上架`（`@TableLogic` 自动加 `deleted = 0`）。

### 回归

`RagSearchServiceTest`、`RagFallbackTest`、`RagStockFilterTest`、`RagHitEnricherTest`、
`KeywordSearchServiceTest`、`RrfMergerTest`、`ProductServiceImplTest` 全绿。

### 编译与启动自测

- `mvn clean compile` 通过；
- 重建索引成功后，`/api/admin/rag/status` 的 `indexedCount` 与在售商品数一致；
- `/api/product/rag-search?q=手机&minPrice=&maxPrice=` 带价格区间调用，返回结果的价格均落在区间内
  （验证 1.2 修复：管理员新增商品不再穿透价格筛选）。

### 评估集对照（可选，需 MySQL + Qdrant + TEI）

跑 `RagEvaluationTest` 与 `rag-evaluation-report.md` §9.3 的生产档基线（0.711 / 0.753 / 0.474）
逐项对照，确认零回归（预期一致：评估集商品均在上架且有库存，不会被本次过滤误杀）。

## 九、验收记录（2026-10-16）

### 已执行

| 项 | 命令 / 方式 | 结果 |
|----|------------|------|
| 编译 | `mvn -q clean test-compile`（根聚合，JAVA_HOME=openjdk-22.0.1） | ✅ 通过（主代码 + 测试代码） |
| 检索链路回归 | `mvn test -Dtest=RagDisplayableFilterTest,RagHitEnricherTest,RagSearchServiceTest,RagFallbackTest,RagStockFilterTest` | ✅ **33 用例全绿** |
| 关键词 / RRF 回归 | `mvn test -Dtest=KeywordSearchServiceTest,RrfMergerTest` | ✅ 12 用例全绿 |
| 商品服务测试 | `mvn test -Dtest=ProductServiceImplTest` | ⚠️ 18 用例红（**既有失败，非本次引入**，见下） |

各测试类明细：

| 测试类 | 用例数 | 结果 |
|--------|-------|------|
| `RagDisplayableFilterTest`（本次新增） | 7 | ✅ |
| `RagHitEnricherTest`（本次 +4 用例） | 12 | ✅ |
| `RagStockFilterTest` | 8 | ✅ |
| `RagSearchServiceTest` | 3 | ✅ |
| `RagFallbackTest` | 3 | ✅ |
| `KeywordSearchServiceTest` | 7 | ✅ |
| `RrfMergerTest` | 5 | ✅ |

### `ProductServiceImplTest` 的既有失败（对照实验证明与本次无关）

首次回归时该类的 4 个嵌套测试组全红。为区分「既有失败」与「本次引入」，
用 `git worktree add d:/tmp/shop-baseline HEAD` 建了一份**不含本次改动的干净副本**跑同一测试，逐项对照：

| 测试组 | 基线（HEAD，无本次改动） | 改动后 |
|--------|------------------------|--------|
| `AddProductTest` | 5 run / 0F / **3E** | 5 run / 0F / **3E** |
| `GetDetailTest` | 3 run / 1F / **2E** | 3 run / 1F / **2E** |
| `PageTest` | 8 run / 0F / **7E** | 8 run / 0F / **7E** |
| `UpdateStatusTest` | 2 run / 1F / **1E** | 2 run / 1F / **1E** |
| **合计** | **18 run / 2F / 13E** | **18 run / 2F / 13E** |

**两组数字逐项完全一致 → 本次改动零影响。**

红因分三类，均为测试环境 / 既有测试缺陷：

1. `SaTokenContextException: SaTokenContext 上下文尚未初始化` —— 异常栈停在
   `ProductServiceImpl.addProduct(ProductServiceImpl.java:107)`（即 `StpAdminUtil.INSTANCE.isLogin()`），
   **位于本次改动区域之前**，执行流根本到不了被改动的代码。测试未装配 SaToken 上下文
   （项目已有 `SaTokenMockUtil`，该测试类未使用）。
2. `NullPointerException: productBloomFilter is null` —— `getDetail` 路径依赖未注入（本次未触及该方法）。
3. `UnnecessaryStubbing` —— Mockito 严格模式下的既有冗余 stub（本次未触及 `doPage`）。

### T7 重建索引 + 真实链路验证（2026-10-17 已执行）

环境：`shop-tei`（healthy）、`shop-qdrant`、MySQL、后端 :8080 全部就绪。

| 步骤 | 结果 |
|---|---|
| 触发重建 `POST /api/admin/rag/rebuild`（**异步**，接口立即返回） | ✅ `lastRebuildSuccess=true`、`indexedCount=670`、消息「重建成功，索引 670 条」 |
| payload 字段核对（Qdrant `points/scroll`） | ✅ 样本仅含 `productId / shopId / firstCategoryId / minPrice / maxPrice` + `text_segment`（索引文本） |
| `name` / `mainImage` 是否残留（`points/count` + `is_empty`） | ✅ 残留点数 **0**（字段精简已生效） |
| 价格字段覆盖 | ✅ `minPrice` 非空 **670/670** —— 重建补齐了历史缺失的数据（§1.2 的数据侧） |
| 价格区间检索 `minPrice=1000&maxPrice=3000` | ✅ 返回项的价格区间均与查询区间相交 |
| **幽灵索引剔除（核心）** | ✅ 直接改 DB `status=2`（不动索引）后检索：**151 消失，586 补位，仍返回满 5 条**；随后恢复 `status=1` |
| 日志可观测 | ✅ `[RAG指标] ... 下架剔除1 库存剔除0 合并5条 富化5条` |

### 验证过程要点（可复现）

- **幽灵索引的构造方式**：不走 `updateStatus` 接口（它会同步 `productIndexer.remove()`，索引被删就验证不到过滤），
  而是直接 `UPDATE product SET status = 2 WHERE id = 151`，精确复现「DB 已下架、Qdrant 仍有索引」的不一致态。
- **补位是「过滤在合并前」的硬证据**：若过滤放在合并后，`topK=5` 只会剩 4 条。
  实测结果：下架前 `151, 61, 286, 376, 256` → 下架后 `286, 61, 376, 256, 586`，条数不变。
- **curl 传中文必须用 UTF-8 百分号编码**（如 `%E6%89%8B%E6%9C%BA`）：Git Bash 下
  `--data-urlencode "q=手机"` 会按 GBK 发送，后端收到乱码（响应里的 `query` 字段可直接看出），
  检索结果看似「语义全错」实为输入编码错。
- **`score` 字段的尺度会变（既有行为，非本次引入）**：双路都有命中时是 **RRF 分数**（约 0.02~0.06），
  单路为空时 `RrfMerger` 原样透传**余弦相似度**（0.7+）。前端若把 `score` 当「相关度百分比」展示会出现跳变，
  记录备查。

### 实现期发现的设计偏差（已回填本文档）

§五 原写「全部候选不可展示时 `allOutOfStock` 仍为 false」—— **该判断是错的**：
按原计算式会得到 `true`（候选非空 + 合并后为空），导致「商品已下架」被误报为「暂时缺货」。
实现已补 `notDisplayableIds.isEmpty()` 条件修正，并新增用例
`offShelfRemovalShouldNotBeFlaggedAsOutOfStock` 锁定；§五 表格与 §六.8 已同步更正。
