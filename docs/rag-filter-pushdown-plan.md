# 向量路过滤下推 —— 计划书

> 状态：执行计划（2026-10-17）
> 配套设计：`docs/rag-filter-pushdown-design.md`

## 批次

**批一（代码，T1–T2）→ 批二（验证，T3–T5）**

---

### T1 新增 payload 索引

**文件**：`shop-server/src/main/java/com/shop/rag/index/QdrantCollectionHelper.java`
**改动**：
1. 新增字段定义表 `PAYLOAD_INDEXES`：`firstCategoryId→integer`、`minPrice→float`、`maxPrice→float`；
2. 新增 `ensurePayloadIndexes(String name)` + 私有 `createPayloadIndex(...)`（REST `PUT /collections/{name}/index?wait=true`，与类内既有 REST 风格一致）；
3. 调用点：`createCollection()` 成功后；`ensureCollection()` 集合已存在分支（补建存量集合索引）。
**依据**：设计文档 §2.1 / §三.5
**验收**：`GET /collections/products` 的 `payload_schema` 含三个字段；重复启动不报错（幂等）。

---

### T2 构造并下推 Filter

**文件**：`shop-server/src/main/java/com/shop/rag/search/RagSearchService.java`
**改动**：
1. 新增私有 `buildMetadataFilter(categoryId, minPrice, maxPrice)`：全空返回 null；否则按「分类等值 + 价格区间相交」用 `MetadataFilterBuilder` / `Filter.and` 组合；
2. `searchViaQdrant` 中把 filter 设进 `EmbeddingSearchRequest`；**构造/下推异常时回退 null**（不中断检索）；
3. 带 filter 时输出 INFO：Qdrant 返回条数 / maxResults（下推生效证据）；
4. **保留** `passMetadataFilter` 作为兜底（不删）。
**依据**：设计文档 §2.2–2.4 / §三.2–3.4
**验收**：代码审查——语义与 `passMetadataFilter` 逐条一致；异常路径回退 null；应用层过滤仍在。

---

### T3 编译 + 离线回归

```bash
mvn -q clean test-compile
mvn test -Dtest='RagSearchServiceTest,RagFallbackTest,RagStockFilterTest,RagDisplayableFilterTest,RagHitEnricherTest,RrfMergerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
**验收**：编译通过；上列测试全绿（既有 33 用例）。

---

### T4 真实链路验证（需 Qdrant / TEI / MySQL）

1. 重启后端 → `ensureCollection` 触发补建索引 → `GET /collections/products` 确认 `payload_schema` 三个字段存在；
2. 带 `categoryId=2` 检索 → 日志中 Qdrant 返回条数 **< maxResults**（下推生效的硬证据）；
3. 带价格区间检索 → 结果全部满足区间相交，与改造前一致；
4. 不带过滤检索 → 行为不变；
5. 观察 `[RAG指标]` 与响应耗时无劣化。

**验收**：以上 5 项均通过，记录实际数值。

---

### T5 验收记录回填

**文件**：`docs/rag-filter-pushdown-design.md`（追加「九、验收记录」）、`CLAUDE.md`（关键设计补一句）
**验收**：记录为实测数据，非「预期」措辞。

---

## 风险

| 风险 | 应对 |
|---|---|
| LangChain4j 的 Filter 未被 Qdrant 正确转换 | 保留应用层过滤兜底 + 观察返回条数证据；若恒等于 maxResults 则判定未生效并回退 |
| 价格 double vs float 精度 | 用范围比较（非等值），误差可忽略；schema 必须为 float |
| 窄过滤下 ANN 质量下降 | 实测评估集对照；劣化则适度放大 maxResults，不回退下推 |
| 改坏既有过滤语义 | 保留应用层过滤 + 既有 33 用例全绿作为回归网 |
