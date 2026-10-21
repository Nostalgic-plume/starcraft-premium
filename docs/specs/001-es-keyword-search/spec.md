# 001 关键词路升级 Elasticsearch + IK —— 需求规格

> 状态：已确认（2026-10-21）
> 前置评估：`docs/rag-elasticsearch-feasibility.md`（结论：MySQL 8 无 BM25；ES + IK 可行）
> 关联设计：`docs/specs/001-es-keyword-search/design.md`

## 一、背景

RAG 混合检索的**关键词路**当前走 MySQL FULLTEXT（ngram n=2）：

- 无 BM25，排序靠 MySQL 内部相关度，不可调；
- ngram 只做 2-gram 粗切，中文语义词项质量差；
- 分类/价格过滤只能在应用层「多取一倍 + 内存过滤」，是刻意近似。

向量路（Qdrant）与加权 RRF 融合保持不变。本次只替换/并联关键词路。

## 二、做什么

1. **新增 ES 关键词检索路**：商品文档进 ES（IK 分词 + BM25），检索结果产出 `RagCandidate`。
2. **可切换**：配置项 `rag.elasticsearch.enabled` 控制启用；启用后 ES 为第一优先。
3. **故障自动回退**：ES 不可用 / 超时 / 索引缺失时，自动回退到现有 MySQL FULLTEXT，检索链路不中断。
4. **过滤下推**：分类（`firstCategoryId`）、价格区间（`minPrice`/`maxPrice`）在 ES `bool.filter` 中执行，不再依赖内存过滤。
5. **索引同步**：商品新增/编辑/上下架/删除时同步 ES；管理后台全量重建入口同时覆盖 Qdrant、FULLTEXT、ES。
6. **可观测**：检索日志标记关键词路实际走的是 `es` / `fulltext` / 降级原因；管理接口返回 ES 索引状态。

## 三、明确不做

1. **不迁移向量路**：不把 Qdrant 换成 ES kNN，双路 RRF 架构保持不变。
2. **不迁移同义词到 ES**：继续用应用层 `SynonymExpander`，减少本次改造变量。
3. **不引入 Spring Data Elasticsearch**：不使用 Repository 抽象与自动配置，直接用官方 Java API Client（与 Qdrant 手写 SDK 风格一致）。
4. **不迁移 shop-ai Python 侧**：该工程已暂停，不存在双消费方同步问题。
5. **不把商品详情 `detail` 字段放进 ES**：关键词路与 MySQL 路对齐，只索引名称/描述/品牌/分类。
6. **不做 ES 与 MySQL 的定时对账任务**：首版靠全量重建 + 增量同步尽力而为；不一致由检索侧 `status=1` 过滤兜底。记为后续 TODO。
7. **不调整 RRF 权重与候选池参数**：保持现状，避免与检索质量变量混在一起。

## 四、边界情况

| 场景 | 期望行为 |
|------|---------|
| ES 未启动 / 端口不通 | 启动不失败；首次检索快速失败（≤1s）后回退 FULLTEXT；30s 后半开重试 |
| ES 索引为空（首次部署） | 启动时自动全量导入；导入完成前检索走 FULLTEXT |
| IK 插件缺失 | 建索引失败，明确日志提示「analysis-ik 未安装」；回退 FULLTEXT |
| 查询为空 / blank | 返回空列表（与现有实现一致） |
| `limit <= 0` | 返回空列表 |
| 分类过滤命中 0 条 | 返回空列表，不回退（ES 正常但确实无结果） |
| 价格字段缺失的商品 | 与向量路同语义：价格过滤时**放行**（ES 文档缺字段 → 不被 range 命中，需在导入时保证有价格） |
| 商品下架 / 逻辑删除 | 删除 ES 文档；检索另有 `status=1 AND deleted=0` 过滤兜底 |
| 全量重建期间 | ES 索引短暂为空 → 自动回退 FULLTEXT，不中断 |
| ES 返回 `productId` 为 null 的脏文档 | 跳过该条 |

## 五、验收标准

1. `mvn -pl shop-server clean compile` 通过。
2. 新增单测通过：Router 在 ES 正常/抛异常两种情况下分别走 ES 与 FULLTEXT。
3. 现有 `shop-server` 全量测试保持通过（314 个基线）。
4. `docker compose --profile es up -d` 后：
   - `GET /_cat/plugins` 显示 `analysis-ik 8.11.4`；
   - `POST /api/admin/rag/rebuild` 后 `GET /api/admin/rag/status` 的 `elasticsearch.indexedCount` ≈ 上架商品数；
   - `GET /api/product/rag-search?query=充电宝` 返回结果，日志中关键词路标记为 `es`；
   - 停掉 ES 容器后再搜，结果仍返回且日志标记为 `fulltext(降级)`。
5. 离线评估集复跑，记录 Recall@3 / Recall@5 / MRR 与基线（0.711 / 0.753 / 0.474）的对照结果（无论升降都要记录）。
