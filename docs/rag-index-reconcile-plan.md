# 向量索引对账 —— 计划书

> 状态：执行计划（2026-10-18）
> 配套设计：`docs/rag-index-reconcile-design.md`

## 批次

**批一（代码，T1–T3）→ 批二（测试，T4）→ 批三（验证，T5–T6）**

---

### T1 payload 增加 updateTime

**文件**：
- `shop-server/src/main/java/com/shop/rag/index/ProductIndexer.java`
- `shop-server/src/main/java/com/shop/rag/config/IndexReconcileProperties.java`（新增）

**改动**：
1. `toMetadataMap` 写入 `updateTime`（`LocalDateTime` → epoch millis）；
2. 新增 `public static Long toEpochMillis(LocalDateTime)`，供对账侧复用（**同一转换函数**，
   避免写入/比对两侧时区或精度不一致导致每轮误判）；
3. 新增 `IndexReconcileProperties`（`rag.reconcile.*`：enabled / fixedRateMs / maxAutoFixRatio / scrollBatchSize）。
**依据**：设计文档 §2.2 / §三.6
**验收**：payload 样本含 `updateTime` 且为数字（毫秒）；`toEpochMillis(null)` 返回 null。

---

### T2 Qdrant 全量 scroll

**文件**：`shop-server/src/main/java/com/shop/rag/index/QdrantCollectionHelper.java`
**改动**：新增 `Map<Long, Long> scrollAllProducts()`，REST `POST /collections/{name}/points/scroll`
分页拉取 `productId` 与 `updateTime`；用 `ObjectMapper` 解析（该类新增该依赖，Spring 自动注入）。
异常 → 返回空 Map。
**依据**：设计文档 §四 / §六.4
**验收**：单元测试覆盖分页终止（`next_page_offset` 为 null）；Qdrant 不可用返回空 Map。

---

### T3 新增对账任务

**文件**：`shop-server/src/main/java/com/shop/task/IndexReconcileTask.java`（新增）
**改动**：
1. `@Scheduled(fixedRateString = "${rag.reconcile.fixed-rate-ms:1800000}")`；
2. 拉 MySQL 应索引集（`status=上架, deleted=0`，只取 id/updateTime）与 Qdrant 现存集；
3. 计算 missing / extra / stale；
4. 阈值保护：差异 > `max(应索引数, 现存数) × maxAutoFixRatio` → 只告警并返回；
5. 修复：missing/stale → 查完整商品后 `addOrUpdate`；extra → `remove`；单个失败只计数；
6. 结构化日志输出各计数。
**依据**：设计文档 §2.1–2.4 / §三
**验收**：代码审查——阈值判断在修复之前；Qdrant/MySQL 异常提前返回；日志字段齐全。

---

### T4 单元测试

**文件**：`shop-server/src/test/java/com/shop/task/IndexReconcileTaskTest.java`（新增）
**覆盖**（设计文档 §八）：集合一致不修复 / 漏建补建 / 多余删除 / 陈旧重建 / 超阈值不修复 /
Qdrant 不可用跳过 / 单商品失败不中断。
**验收**：用例全绿。

---

### T5 编译 + 回归

```bash
mvn clean test -Dtest='IndexReconcileTaskTest,RagSearchServiceTest,RagFallbackTest,RagStockFilterTest,RagDisplayableFilterTest,RagHitEnricherTest,RrfMergerTest,ChatServiceClearConversationTest' -Dsurefire.failIfNoSpecifiedTests=false
```
**验收**：编译通过、全部用例绿。

---

### T6 真实链路验证

1. 重启后端触发启动索引检查；**手动重建一次索引**（让存量 payload 带上 `updateTime`，见设计 §六.1）；
2. 制造三类不一致：① 改 DB 内容但不动索引 ② 删一个 Qdrant 点 ③ 往 Qdrant 写一个不存在商品的点；
3. 缩短 `rag.reconcile.fixed-rate-ms`（如 10000）重启，观察对账日志与修复结果；
4. 确认日志中的 missing / extra / stale 计数与实际制造的一致。

**验收**：三类不一致均被修复；日志计数吻合。

---

## 风险

| 风险 | 应对 |
|---|---|
| 首次对账全量判陈旧 → 阈值拦截 | 升级后先手动重建索引（设计 §六.1） |
| updateTime 精度/时区导致轮轮误判 | 统一 epoch millis + 同一静态转换函数 |
| 对账与重建并发误判 | 阈值保护拦截（设计 §2.4） |
| 新增 ObjectMapper 依赖影响既有构造 | `QdrantCollectionHelper` 无直接 new 的测试（已确认） |
