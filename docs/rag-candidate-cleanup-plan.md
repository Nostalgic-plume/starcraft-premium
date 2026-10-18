# 检索候选池清洗与向量库 payload 精简 —— 计划书

> 状态：执行计划（2026-10-16）
> 配套设计：`docs/rag-candidate-cleanup-design.md`（下文简称「设计文档」）
> 验收方式逐项写明，完成后按本表逐项勾验

## 子任务与批次

按关联性分三批：**批一（代码，T1-T4）→ 批二（测试，T5）→ 批三（验证，T6-T7）**。
T3 是 T4 的前置（T4 调用 T3 新增的方法）；T1、T2 独立于其余。

---

### T1 修复 `addProduct` 索引同步顺序

**文件**：`shop-server/src/main/java/com/shop/service/impl/ProductServiceImpl.java`
**改动**：把 `productIndexer.addOrUpdate(product)` 从 SKU 插入之前（L146-151）移到之后，
并加注释把「必须先于 SKU 落库完成」的隐式顺序契约显式化。
**依据**：设计文档 §1.2 / §2.1
**验收**：代码审查——`addOrUpdate` 调用行的行号 > `skuMapper.insert` 调用行的行号；
注释中明确写出「顺序不可调换」及其后果（价格区间为空 → 价格筛选失效）。

---

### T2 精简 `toMetadataMap` 的 payload 字段

**文件**：`shop-server/src/main/java/com/shop/rag/index/ProductIndexer.java`
**改动**：删除 `putIfNotNull(meta, "name", ...)` 与 `putIfNotNull(meta, "mainImage", ...)`；
`shopId` 保留并加注释说明「预留：多商家过滤」。
**依据**：设计文档 §1.1 / §2.2
**验收**：
- grep 全 `com.shop.rag` 包确认 `"name"` / `"mainImage"` 无任何 `metaLong` / `meta.get` 消费点；
- `toMetadataMap` 剩余写入字段为 `productId` / `firstCategoryId` / `shopId` / `minPrice` / `maxPrice`；
- 编译通过（无残留 import 失效）。

---

### T3 `RagHitEnricher` 新增 `findDisplayableIds`

**文件**：`shop-server/src/main/java/com/shop/rag/search/RagHitEnricher.java`
**改动**：新增 `public Set<Long> findDisplayableIds(Collection<Long> productIds)`，
`select id` + `status = 上架`（`@TableLogic` 自动加 `deleted = 0`），**异常向上抛出不吞**；
同步更新类 javadoc（职责由「topK 富化」扩展为「MySQL 侧商品真相查询」，含候选池清洗时机）。
**依据**：设计文档 §四
**验收**：
- javadoc 明确写出「异常向上抛出，调用方按不过滤处理」与不吞异常的理由（正集语义下空集合有歧义）；
- 方法体内**没有** try-catch；
- 查询只 `select(ProductDO::getId)`，不读大字段（与 `loadProducts` 区分）。

---

### T4 `RagSearchService` 接入可展示性前置过滤

**文件**：`shop-server/src/main/java/com/shop/rag/search/RagSearchService.java`
**改动**：
1. 新增私有 `findNotDisplayableIds(vectorHits, keywordHits)`：收集候选 id 并集 →
   调 `ragHitEnricher.findDisplayableIds` → 取补集；**查询异常 catch 后返回空集合（放行）**；
2. `removeOutOfStock` 重命名为 `removeByIds`（通用剔除，签名行为不变），同步更新 javadoc；
3. 候选池清洗段改为：两个集合（不可展示 ∪ 售罄）合并后一次剔除，`[RAG指标]` 日志新增「状态剔除」计数。
**依据**：设计文档 §2.3 / §三.3
**验收**：
- 过滤调用位置在 `RrfMerger.merge` **之前**（代码顺序审查）；
- 异常路径返回空集合（放行），不返回"全部候选"；
- 两处剔除复用同一个 `removeByIds`（无重复实现）；
- `search()` / `searchWithAvailability()` 签名未变。

---

### T5 同步既有测试 + 新增过滤测试

**文件**：
- `shop-server/src/test/java/com/shop/rag/search/RagSearchServiceTest.java`
- `shop-server/src/test/java/com/shop/rag/search/RagFallbackTest.java`
- `shop-server/src/test/java/com/shop/rag/search/RagStockFilterTest.java`
- **新增** `shop-server/src/test/java/com/shop/rag/search/RagDisplayableFilterTest.java`

**改动**：
1. 三个既有测试的 `@BeforeEach` 各补一条放行 stub：
   `lenient().when(ragHitEnricher.findDisplayableIds(anyCollection())).thenAnswer(inv -> new HashSet<>(inv.getArgument(0)))`
   —— 不补则 Mockito 默认返回空集合 → 全部候选被剔除 → 既有断言全挂（设计文档 §六.1）；
2. 新增 `RagDisplayableFilterTest`（照 `RagStockFilterTest` 结构），覆盖设计文档 §八 的 7 条单测。
**依据**：设计文档 §六.1 / §八
**验收**：
- 三个既有测试类的 `@BeforeEach` 均含该 stub；
- 新测试含「已删除商品被剔除」「查询异常放行」「并集只查一次」「合并前过滤（可补位满 topK）」四个关键用例；
- `mvn test -Dtest=RagDisplayableFilterTest -Dsurefire.failIfNoSpecifiedTests=false` 全绿。

---

### T6 编译 + 回归

**命令**：
```bash
export JAVA_HOME="C:/Users/26373/.jdks/openjdk-22.0.1"
export PATH="$JAVA_HOME/bin:D:/software/maven/apache-maven-3.9.9/bin:$PATH"
cd "D:/claudecode/shop" && mvn clean compile -q
cd "D:/claudecode/shop" && mvn test -Dtest='RagSearchServiceTest,RagFallbackTest,RagStockFilterTest,RagHitEnricherTest,KeywordSearchServiceTest,RrfMergerTest,ProductServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```
**验收**：编译通过；上列测试类全绿（关键看三个直接 `new RagSearchService` 的类未因 stub 缺失而失败）。

---

### T7 重建索引 + 真实链路验证（需 MySQL / Qdrant / TEI 就绪）

**前置**：`docker compose --profile rag --profile gpu up -d`，确认 TEI healthy。
⚠️ 重建会**先删集合**，TEI 未就绪会导致集合被清空（设计文档 §六.3）。

**步骤**：
1. `POST /api/admin/rag/rebuild`（管理后台）→ `GET /api/admin/rag/status` 确认 `lastRebuildSuccess=true`
   且 `indexedCount` 与在售商品数一致；
2. 价格筛选验证（修 1.2）：带 `minPrice` / `maxPrice` 调 `/api/product/rag-search`，
   确认返回结果价格均落在区间内 —— **重点检查管理员新增的商品不再穿透**；
3. 下架剔除验证（修 1.3）：将一个商品下架 → 调检索确认其消失，且返回条数仍满 topK（前置过滤补位）；
4. 观察 `[RAG指标]` 日志出现「状态剔除」计数。

**验收**：三项验证均通过，记录实际数值（对照 `rag-evaluation-report.md` §9.3 基线确认零回归）。

---

### T8 验收记录回填

**文件**：`docs/rag-candidate-cleanup-design.md`（追加「九、验收记录」章节，
格式参照 `rag-stock-filter-design.md` §九）、`docs/README.md`（索引补两行）
**验收**：设计文档含实际执行结果（命令 + 结果 + 数值），非「预期」措辞。

---

## 风险与降级

| 风险 | 应对 |
|---|---|
| T5 漏改某个直接 `new RagSearchService` 的测试 | T6 的编译+回归会暴露；grep `new RagSearchService(` 复核 |
| T2 删字段后仍有人读（grep 漏判） | T6 编译期不会有信号（Map 取值为字符串 key），故 T2 验收强制 grep 复核 |
| T7 重建失败导致集合清空 | 重建前确认 TEI healthy；失败时 `lastRebuildSuccess=false` 可区分，重跑重建即可 |
| 历史缺失的价格字段未补齐 | T7 步骤 2 专门验证；若未重建则**该缺陷仍存在于历史数据**（代码路径已修，数据未补） |
