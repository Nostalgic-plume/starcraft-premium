# RAG 商品索引确定性 pointId 修复 — 计划书

> 日期：2026-09-17　对应设计文档：`docs/rag-index-pointid-design.md`

| # | 子任务 | 产出 | 验收方式 |
|---|--------|------|---------|
| 1 | 备份受影响文件 | `.bak-rag-index-20260917/` 下两份原文件 | 文件存在、字节数与源文件一致 |
| 2 | `QdrantCollectionHelper` 增加 `countPoints()` | REST count 方法与正则常量 | 编译通过；集合名取自 `ragProperties` |
| 3 | `ProductIndexer` 删除进程内映射与计数 | `pointIdMap`、`indexedCount` 字段消失 | `rg pointIdMap|indexedCount` 在 rag 包内无命中 |
| 4 | 引入确定性 point id 写入与删除 | `pointIdOf()` + `addAll(ids,...)` + `remove(id)` | 单测断言两次写入 id 相同、删除按 id 直删 |
| 5 | 修正全量重建的 ids/segments 错位 | `doRebuild()` 三列表严格对齐 | 单测：空向量被跳过后 id 与文本仍对应 |
| 6 | 元数据空值防护（同路径既有缺陷） | `toMetadataMap` 改用 `putIfNotNull` | 单测：字段为 null 的商品仍能成功写入，且元数据不含该键 |
| 7 | 新增 `ProductIndexerTest` | 5 个用例 | `mvn -o test -Dtest=ProductIndexerTest` 全绿 |
| 8 | 编译与回归 | 编译通过 | `mvn -o test-compile` 无错误 |
| 9 | 上线动作说明 | 设计文档「容易踩的坑」第 1 条 | 已写明需手动触发一次 rebuild |

## 执行顺序

1 → 2 → 3 → 4 → 5 → 6 → 7 → 8，其中 4、5、6 属同一次文件改动，合并实施；9 随文档交付。

## 遗留与不做项

- **不做**：价格元数据实时化（SKU 改价后索引内价格会过期）——需先补商家改价接口，另行立项；
- **不做**：同义词 / 电商黑话扩展——属检索质量优化，另行立项；
- **不做**：历史脏数据清理脚本——一次全量重建即可覆盖；
- **已知既有失败（与本次无关，已 A/B 验证）**：`ProductServiceImplTest` 中 6 个用例在改动前后以完全相同方式失败（SaTokenContext 未初始化、`productBloomFilter` / `redisCache` 为 null），属测试脚手架问题，本次不修。