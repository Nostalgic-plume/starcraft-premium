# 检索结果与业务数据解耦设计（只召回 ID + 回查）

> 状态：设计稿（2026-10-10）
> 关联：`docs/rag-stock-filter-design.md`、`docs/rag-hybrid-tuning-design.md`、`docs/rag-code-map.md`
> 目标：向量库只承担「检索」，不承担「存储」——召回阶段只产出商品 ID，展示数据统一回查 MySQL

## 一、现状与问题

### 1.1 两条路的展示数据来源不同

| | 候选来源 | 展示字段来源 |
|---|---|---|
| 向量路 | Qdrant payload | **Qdrant payload**（`text_content` + metadata） |
| 关键词路 | MySQL FULLTEXT | **MySQL 行本身** |

同一个 `RagHitVO` 被两条路用两种方式填充，字段语义因此并不一致。

### 1.2 问题一：`excerpt` 语义不一致（有实证）

- 向量路：`truncate(segment.text(), 120)` —— 索引文本（带 `名称：/描述：/品牌：/分类：/详情：` 标签）
- 关键词路：`p.getDescription()` —— 纯描述

真实响应（端到端验证抓取）：

```json
"excerpt":"名称：华为 Mate 60 Pro 12GB+512GB 雅丹黑 手机\n描述：搭载麒麟芯片与卫星通话功能，超可靠玄武架构，全焦段影像旗舰。\n品牌：华为\n分类：手机\n详情：华为 Mate 60 Pro 采用玄武架构机身，内置 5000mAh ..."
```

`ChatService.buildProductsJson()` 会把 excerpt 再截到 **40 字**给前端卡片，于是卡片上显示的是：

```
名称：华为 Mate 60 Pro 12GB+512GB 雅丹…
```

**标签与标题占满全部配额，有效描述一个字都没进**。而关键词路命中的卡片显示的是干净的商品描述。

`buildProductContext()` 喂给 LLM 的「摘要」同样带着这层标签，属于冗余（名称已作为独立字段给出）。

### 1.3 问题二：向量路的展示数据是「索引时的快照」

`name` / `mainImage` / `minPrice` / `maxPrice` 都来自写索引那一刻。
商品改名、换图、调价后，**只要不重建索引，向量路就会一直用旧值**。

当前无「编辑商品」接口，该问题尚未暴露；但它是**结构性**的——一旦补上改价/编辑功能就会立即踩中，
且因为过滤逻辑是「缺失即放行」，出错时没有任何声响。

### 1.4 问题三：payload 承载了本不该它管的数据

Qdrant payload 里存着完整索引文本（名称/描述/品牌/分类/详情，最长 2000 字符），
而检索阶段真正用到的只有：**向量**（算相似度）、**`firstCategoryId`/价格**（下推过滤）。

其余全是展示数据，属于「存储」而非「索引」。

## 二、方案

### 2.1 分层原则

| 数据 | 用途 | 存放位置 |
|------|------|---------|
| 向量 | 相似度计算 | **Qdrant**（必须） |
| `productId` | 回查的钥匙 | **Qdrant**（必须） |
| `firstCategoryId`、价格区间 | **检索时下推过滤** | **Qdrant**（保留） |
| 名称、主图、描述 | 展示 | **回查 MySQL** |
| 库存、实时价格 | 交易（高频变化） | **回查 MySQL** |

**判据**：过滤字段是「检索契约」，必须留在向量库；展示与交易数据是「业务数据」，一律回查。
这条分界线同时解释了为什么库存不走下推（`rag-stock-filter-design.md` §7）。

### 2.2 数据流改造

```
现状：
  向量路  → RagHitVO(score, excerpt=文本前120字, name, image, minPrice, maxPrice)  ← 展示数据来自 payload
  关键词路 → RagHitVO(score, excerpt=description, name, image, minPrice, maxPrice)
  → 库存过滤 → RRF 合并 → topK

改造后：
  向量路  → RagCandidate(productId, score)   ← 只认 ID；metadata 仅用于过滤，不出检索层
  关键词路 → RagCandidate(productId, score)
  → 库存过滤（按 productId 批量查 sku）→ RRF 合并 → topK
  → ★ 回查 MySQL（名称/主图/描述）+ SkuPriceResolver（价格）→ 组装 RagHitVO
```

**关键**：RRF 融合只需要 `productId` + `score`，展示数据在**取到 topK 之后**才回查 ——
回查量从候选池（50~200 条）降到 **topK（3~10 条）**，比把 200 条完整 payload 取回来更省。

### 2.3 中间态：新增 `RagCandidate`

```java
/** 检索候选：融合阶段只需 ID 与分数，展示数据在最终阶段回查 */
public record RagCandidate(Long productId, double score) {}
```

`RrfMerger` 的入出参由 `RagHitVO` 改为 `RagCandidate`（**语义边界显式化**，
避免中间态出现「字段为 null 的 RagHitVO」这种易误用状态）。

### 2.4 回查组件：新增 `RagHitEnricher`

```
List<RagHitVO> enrich(List<RagCandidate> candidates)
  1. 批量查商品展示字段：SELECT id, name, main_image, description FROM product WHERE id IN (...)
     —— 只取需要的列，不读 detail 等大字段
  2. 批量查 SKU 价格区间（复用 SkuPriceResolver）
  3. 组装 RagHitVO：score / productId / name / image / excerpt=truncate(description, 120) / minPrice / maxPrice
```

**两路的 excerpt 由此统一为「商品描述的截断」**，标签前缀问题消失。

### 2.5 索引侧：payload 瘦身

`ProductIndexer.buildIndexText()` 由「名称/描述/品牌/分类/详情」五段拼接改为**仅存商品名**：

- 检索阶段不再消费文本（excerpt 改由回查提供），保留文本只为排查时能认出是哪个商品
- **需要全量重建索引**（`POST /api/admin/rag/rebuild`，278 条商品，几十秒）

**注意**：向量语义**不变** —— 向量的计算输入仍是完整拼接文本，只是写进 payload 的 `text_content` 变短。
若把向量化输入也改成商品名，会显著劣化语义检索，**属禁止事项**（见 §七）。

### 2.6 顺带合并 SKU 查询

库存过滤（`SkuPriceResolver.resolveStock`）与价格回查（`resolve`）当前是**两次独立的 sku 表查询**。
本次合并为一次：`SkuPriceResolver` 提供「一次查询同时返回价格区间与总库存」的方法，
`RagHitEnricher` 与库存过滤共用该结果。

## 三、硬约束

1. **对外契约不变**：`RagHitVO` 的字段与 JSON 结构保持原样，`RagSearchController` 与前端无感。
2. **向量化输入不得改动**：索引文本的**拼接内容**（喂给 embedding 的部分）必须保持
   「名称/描述/品牌/分类/详情」，本次只改写入 payload 的 `text_content`。二者若混淆将直接劣化召回。
3. **过滤字段必须留在 Qdrant**：`firstCategoryId`、价格区间不得改为回查 ——
   一旦回查，过滤只能发生在召回之后，候选池会被浪费（详见 §2.1 判据）。
4. **回查只针对 topK**：不得对候选池全量回查（50~200 条），否则查询量放大数十倍。
5. **回查失败不致命**：商品查不到时**跳过该项**而非返回占位数据（商品可能已被删除/下架）；
   整体查询异常时保留候选但展示字段为空，并打 WARN。
6. **关键词路 SQL 的 `WHERE`/`MATCH` 结构不得改动**（与 shop-ai 侧的历史对齐约定），
   仅允许收窄 `SELECT` 列表为 `id, first_category_id`。
7. 库存过滤的既有语义（无 SKU 放行、查询异常放行）保持不变。

## 四、入参与出参

### `RagSearchService`（对外不变）

```
List<RagHitVO> search(String query, int topK, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice)
RagSearchResult searchWithAvailability(...)   // hits + allOutOfStock
```

### `RagHitEnricher`（新增）

```
List<RagHitVO> enrich(List<RagCandidate> candidates)
  入参：按 RRF 分数降序的候选（已截断至 topK）
  出参：同序的完整 RagHitVO；查不到的商品被跳过（结果可能少于入参条数）
```

### `SkuPriceResolver`（扩展）

```
Map<Long, SkuSnapshot> resolveSnapshot(List<Long> productIds)
  SkuSnapshot = [minPrice, maxPrice, totalStock]   // 一次查询同时拿到价格与库存
（保留既有 resolve / resolveStock，供其他调用方使用）
```

## 五、错误返回

| 场景 | 行为 |
|------|------|
| 商品回查查不到某个 ID | 跳过该条（不产出占位 VO） |
| 商品回查整体异常 | 返回空列表并打 WARN（宁可少展示，也不展示「有卡片却无名称/图片」的残缺数据） |
| SKU 快照查询异常 | 价格与库存字段为空；库存过滤不生效（放行），与既有语义一致 |
| 回查后结果为空 | 返回空列表，`allOutOfStock` 保持原判定 |

## 六、容易踩的坑

1. **① 向量化输入 vs payload 文本，绝不能一起改**。前者影响召回质量，后者只影响 payload 体积。
   改错对象会让检索质量断崖式下跌，且**指标不会立刻报警**（召回变差是渐进的）。
   改动 `buildIndexText` 前必须先确认它被用在哪个路径。
2. **② 库存过滤必须在回查之前**。它依赖候选池的并集（50~200 条）来判定
   「是否全部售罄」；若挪到回查之后（只剩 topK），`allOutOfStock` 的判定会失真 ——
   topK 全售罄 ≠ 候选全售罄。
3. **③ `RrfMerger` 改签名会连带 `RrfMergerTest`**（5 个用例）与 `RagSearchServiceTest` 的 mock 断言，
   必须同步更新。
4. **④ 回查要保留 RRF 的排序**：批次查询返回的顺序不保证与入参一致，
   必须按 productId 映射回原序，否则融合排序白做。
5. **⑤ 关键词路收窄 SELECT 后，`passFilter` 依赖的 `firstCategoryId` 必须仍在 SELECT 列表内**，
   否则分类过滤静默失效（过滤逻辑对缺失字段是「放行」）。
6. **⑥ 重建索引期间检索降级**：`recreateCollection()` 先删后建，期间向量路为空、退化为纯关键词。
   属预期行为，但需在维护窗口执行。
7. **⑦ 评估集指标必须复测**：本改动触及检索链路核心，须跑 `RagEvaluationTest`
   与改动前基线（`0.711 / 0.753 / 0.474`）逐项比对，确认无回归。

## 七、不做的事

- **不改向量化输入**：索引文本的拼接内容保持五段式（硬约束 §2）。
- **不做「只存向量不存 text」**：LangChain4j 的 metadata 依附于 `TextSegment`，
  不传 segment 就没有 metadata，也就丧失了下推过滤能力（§2.1 判据）。
- **不给回查加 Redis 缓存**：topK 只有 3~10 条、按主键批量查，`product` 表已有主键索引；
  引入缓存反而增加一致性负担。若后续 QPS 上来再评估。
- **不动关键词路的 `WHERE`/`MATCH` 结构**（硬约束 §6）。
- **不同步 shop-ai Python 侧**：AI 服务 Python 化已暂停（2026-10-08），本次仅 Java 侧。

## 八、验收标准

### 单元测试

- `RrfMerger`：改用 `RagCandidate` 后，融合排序与权重行为不变（改造前后等价）。
- `RagHitEnricher`：
  - 正常回查：字段完整、**顺序与入参一致**；
  - 商品查不到 → 跳过该条；
  - 回查异常 → 保留候选、字段为空；
  - 只查 topK 条（Mockito 断言查询入参条数）。
- `RagSearchService`：既有用例（候选池对齐、库存过滤、降级）全部保持通过。
- `RagStockFilterTest`：8 个用例保持通过（若签名变更则同步）。

### 端到端

- **`excerpt` 不再带标签前缀**（本次核心收益，需实际调 `/api/chat/ask` 与 `/api/product/rag-search` 查看）；
- 两路命中的 excerpt 格式一致（均为商品描述截断）；
- 客服卡片文字可读。

### 评估回归

`mvn test -Dtest=RagEvaluationTest` → 基础集生产档指标与 `0.711 / 0.753 / 0.474` 逐项一致。

### 索引重建

全量重建成功，`indexedCount = 278`，且重建后检索结果与重建前一致（商品集合不变）。

## 九、验收记录（2026-10-12）

三批次全部落地，实测结论如下。

### 9.1 单元测试

| 范围 | 结果 |
|------|------|
| 批次二链路测试（RrfMerger / KeywordSearchService / RagSearchService / RagStockFilter / RagFallback / **ProductIndexerRebuild** 等 7 类） | **44 用例全绿** |
| 批次三 `ProductIndexerTest`（含新增 payload 瘦身用例） | **6 用例全绿** |
| 全量 RAG 相关回归（11 个测试类） | **63 用例全绿** |

新增用例锁定两条关键不变量：

- `ProductIndexerTest#rebuild_shouldEmbedFullTextButStoreNameOnlyInPayload`：
  **向量化输入仍是五段式**（名称/描述/品牌/分类/详情），写入 payload 的文本仅为商品名；
  同时校验 `productId`/`firstCategoryId` 仍在 payload 元数据中（下推过滤依赖）。
- `RagSearchExcerptContractTest`：经完整 MVC 栈（含 Jackson 序列化）调真实 `/api/product/rag-search`，
  断言所有命中的 `excerpt` 不含「名称：/描述：/品牌：/分类：/详情：」任一标签，
  且 `name`/`productId` 为独立字段。Qdrant 不可用时自动跳过（与 `RagEvaluationTest` 同策略）。

### 9.2 评估回归（零回归）

`mvn test -Dtest=RagEvaluationTest`，重建**前后**各跑一次，逐项比对：

| 指标 | 基线（重建前） | 重建后 | 结论 |
|------|--------------|--------|------|
| 基础集 Recall@3（wv=1.0 wk=2.0 生产档） | 0.711 | 0.711 | 一致 |
| 基础集 Recall@5 | 0.753 | 0.753 | 一致 |
| 基础集 MRR | 0.474 | 0.474 | 一致 |
| 同义词集 Recall@5 | 0.867 | 0.867 | 一致 |
| 同义词集 MRR | 0.529 | 0.529 | 一致 |

**这是本次改动最关键的一道证据**：payload 文本从「五段式全文」瘦身为「仅商品名」后指标一字未动，
反证了「向量化输入未被误改」与「payload 文本不参与召回」两点同时成立。

### 9.3 索引重建

`ProductIndexerRebuildTest`（走 `forceRebuildAll()`，与 `POST /api/admin/rag/rebuild`
同一代码路径 —— 该接口在 `IndexAdminController` 中异步调用同一方法）：

```
===== 商品向量索引完成: 278 条, 耗时 2602ms =====
```

断言「索引条数 = 在售商品数」通过，`indexedCount = 278` 与计划书预期一致。

### 9.4 端到端（excerpt 标签前缀消失）

实测响应（`GET /api/product/rag-search?q=手机&topK=3`，节选一条）：

```json
{"productId":"1","name":"华为 Mate 60 Pro 12GB+512GB 雅丹黑 手机",
 "image":"https://picsum.photos/seed/3331/400/400",
 "minPrice":6999.00,"maxPrice":7999.00,"score":0.05,
 "excerpt":"搭载麒麟芯片与卫星通话功能，超可靠玄武架构，全焦段影像旗舰。"}
```

与改造前同一条商品的 `excerpt` 对比：

| | excerpt |
|---|---|
| 改造前（向量路） | `名称：华为 Mate 60 Pro 12GB+512GB 雅丹黑 手机\n描述：搭载麒麟芯片与卫星通话功能，…` |
| 改造后 | `搭载麒麟芯片与卫星通话功能，超可靠玄武架构，全焦段影像旗舰。` |

`name` / `image` / `minPrice` / `maxPrice` 均为独立字段且取自回查结果，标签前缀彻底消失，
客服卡片 40 字配额不再被标签与标题挤占。查询 `vivo` 的响应同样为纯描述，两路格式已一致。

### 9.5 环境备注（多模块重构带来的连带影响）

多模块化后 `surefire` 与 `spring-boot:run` 的工作目录变为模块目录（`shop-server/`），
而 `.env` 仍在仓库根目录，**`spring-dotenv` 读不到**，`@SpringBootTest` 会因
`Access denied for user 'root'@'localhost'` 加载上下文失败。本次验证采用临时注入规避：

```bash
set -a; . <(tr -d '\r' < .env); set +a   # 再跑 mvn test
```

**属未修复的环境问题**（根因是多模块重构尚未收尾，见 §9.6），不是本次改动的缺陷。
彻底的修法是给 `shop-server` 的 surefire 配 `workingDirectory` 指向仓库根，
或把 `.env` 纳入构建期资源解析 —— 待多模块重构收尾时一并处理。

### 9.6 遗留

- **多模块重构改动尚未提交**（暂存区 255 个文件的 rename/新增）。本次批次三的文件位于
  `shop-server/` 新路径，其提交**依赖**该重构先落地 —— 否则 HEAD 中旧路径
  `src/main/java/com/shop/rag/**` 与新路径并存，同一 FQCN 会编译冲突。
- `ChatController./clear` 缺 `@SaCheckLogin`：当前 `clearConversation` 为空实现故无实际危害，
  但 V2 实现后会变成越权删除漏洞，建议届时补注解。

