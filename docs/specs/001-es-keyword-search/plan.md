# 001 关键词路升级 Elasticsearch + IK —— 实施计划

> 需求：`spec.md`　设计：`design.md`
> 功能编号：**001**（commit message 用 `feat(001): ...`，代码 TODO 标记带 `001`）

## 一、任务表

| # | 任务 | 依赖 | 并行 | 验收方式 |
|---|------|------|------|---------|
| T1 | 根 pom 覆盖 `elasticsearch.version=8.11.4`，新增 `co.elastic.clients:elasticsearch-java` 依赖 | — | | `mvn -pl shop-server dependency:tree` 中 client 与 rest-client 均为 8.11.4 |
| T2 | 新增 `RagElasticsearchProperties`（`rag.elasticsearch.*`）与 `EsClientConfig`（RestClient + Transport + ElasticsearchClient Bean） | T1 | | `mvn compile` 通过；应用启动不因 ES 未运行而失败 |
| T3 | 新增 `EsProductIndexer`：索引 mapping 常量、`ensureIndex`、`rebuildAll`（删重建 + bulk）、`upsert`、`delete`、`count`、状态字段 | T2 | | 起 ES 后启动应用，`GET /_cat/indices` 出现 `shop_products`，`_count` 等于上架商品数 |
| T4 | 新增 `EsKeywordSearchService`：multi_match + bool.filter（分类/价格/status/deleted），异常抛 `KeywordSearchUnavailableException` | T3 | | 单测覆盖「构造查询」与「异常抛出不吞」，手动 `_search` 结果与 MySQL 路抽样对照 |
| T5 | 新增 `KeywordSearchRouter`：开关 + 熔断 + 半开 + 回退；`RagSearchService` 改注入 Router，日志加关键词路标记 | T4 | | 新增 `KeywordSearchRouterTest`：ES 正常走 ES、ES 抛异常走 FULLTEXT、熔断期内不调 ES |
| T6 | 新增 `ProductIndexSyncService` 门面，接入 `ProductServiceImpl`（3 处）、`IndexAdminController`（rebuild/status）、`IndexReconcileTask`（reindex/remove） | T5 | | `mvn compile`；商品编辑后 ES 文档数/内容同步；`/api/admin/rag/status` 返回 `elasticsearch` 节点 |
| T7 | `application.yml` 增加 `rag.elasticsearch.*`；`docker-compose.yml` 注释补充词典挂载说明（不改行为） | T2 | `[P]` 与 T3~T6 并行（只动 yml） | 配置项可被 `/actuator` 或启动日志读到；`docker compose config` 通过 |
| T8 | 改造既有 4 个测试（mock 类型改为 Router）；全量回归；更新 `docs/rag-code-map.md` 与可行性文档 §8.3 状态 | T6、T7 | | `mvn -pl shop-server clean test` 全绿（314 + 新增）；文档状态改为「已接入」 |
| T9 | 重建评估集（40 条）并按实测调优：RRF 权重 2.0→1.5、ES 匹配门槛保持 OR、新增单向错别字纠正 | T8 | | 新评估集 Hit@3=1.000 / MRR=0.986；全量 325 用例全绿；见 `evaluation-2026-10-21.md` |

## 二、一致性检查

逐项对照 spec / design：

| spec / design 要求 | 承接任务 |
|-------------------|---------|
| 新增 ES 检索路 | T3、T4 |
| 可切换开关 | T2、T5、T7 |
| 故障自动回退 FULLTEXT | T5 |
| 分类/价格过滤下推 | T4 |
| 商品增删改同步 | T6 |
| 全量重建覆盖三套索引 | T6 |
| 可观测（日志 + 管理接口） | T5、T6 |
| 不迁移向量路 / 同义词 / Spring Data / Python 侧 / detail / 对账 | 无对应任务（刻意不做） |
| H1 版本锁定 | T1 |
| H2 IK 版本 | 已有 Dockerfile，不变 |
| H3 ik_smart/max_word | T3 |
| H4 过滤字段类型 | T3 |
| H5 scaled_float | T3 |
| H6 失败不返回空 | T4、T5 |
| H7 同步失败不阻断 | T6 |
| H8 重建窗口可回退 | T5（ES 404 → 回退） |

无「spec/design 未提过但 plan 凭空增加」的任务。**一致性检查已通过**。

## 三、执行记录

| # | 状态 | 验收结果 |
|---|------|---------|
| T1 | ✅ | `dependency:tree`：`elasticsearch-java:8.11.4` + `elasticsearch-rest-client:8.11.4`，未被 Boot BOM 压回 8.10.x |
| T2 | ✅ | `mvn compile` 通过；ES 未运行时应用正常启动（RestClient 懒连接） |
| T3 | ✅ | 启动后 ES 出现 `shop_products`（1 分片 0 副本），自动导入 670 条；`_cat/plugins` 显示 `analysis-ik 8.11.4`；`ik_max_word` 切出「小米/移动/电源/20000/毫安/毫/安」，`ik_smart` 切出前 5 个 |
| T4 | ✅ | `_search` 查「剃须刀」命中 279（松下剃须刀）BM25=20.3；查「口红」返回 0（库中无该商品，索引准确）；异常路径抛 `KeywordSearchUnavailableException` 不吞 |
| T5 | ✅ | `KeywordSearchRouterTest` 5/5 通过（ES 优先 / 异常回退 / 开关关闭 / 熔断 / 半开恢复）；端到端日志 `关键词路=es` |
| T6 | ✅ | 商品服务、对账任务、管理接口改走 `ProductIndexSyncService`；`/api/admin/rag/status` 新增 `elasticsearch` 与 `keywordRoute` 节点 |
| T7 | ✅ | `rag.elasticsearch.*` 配置就位；compose 补充 IK 词典挂载说明（未改变行为） |
| T8 | ✅ | `mvn -pl shop-server clean test`：**319 个用例全绿**（基线 314 + 新增 5） |
| T9 | ✅ | 评估集重建为 40 条；调优后 **Hit@3=1.000 / MRR=0.986**；全量 **325 个用例全绿** |

## 四、端到端验收记录（2026-10-21）

| 场景 | 操作 | 结果 |
|------|------|------|
| 正常链路 | `GET /api/product/rag-search?q=剃须刀&topK=3` | 第一名 279「松下剃须刀 ES-ST29」；日志 `关键词路=es`，耗时 385~430ms |
| 故障降级 | `docker stop shop-es` 后检索 | HTTP 200 正常返回结果；日志 `关键词路=fulltext(降级)`，WARN 记录 `Connection refused` |
| 重启窗口 | ES 启动中（分片未就绪） | 返回 503 `no_shard_available_action_exception` → 计为第 3 次失败 → 熔断 30s |
| 半开恢复 | 熔断窗口过后再次检索 | 自动恢复 `关键词路=es`，无需重启应用 |

## 五、评估集重建与结果（2026-10-21）

旧评估集（2026-08 标注）已与当前商品数据完全脱节：改动前基线实跑 Recall@3=0.033（30 条命中 1 条），
「口红」期望的是小米手机/南极人被芯，「剃须刀」期望的是联想笔记本。**本次改造未造成退化，是评估集失效。**

已按当前 670 件上架商品重建 **40 条**评估集（37 正样本 + 3 负样本），期望集取同类商品全集，
指标改为 Hit@K + MRR@FirstHit（对重复商品鲁棒）。结果：

| 维度 | 结果 |
|------|------|
| 基础集（37 条） | Hit@3=**0.973** / Hit@5=0.973 / MRR=0.959 |
| 关键词引擎 | ES Hit@3=0.973 vs FULLTEXT 0.946；MRR 0.959 vs 0.926 |
| 同义词扩展 | 关闭 MRR=0.840 → 开启 **1.000**（+0.160） |
| RRF 权重 | 1.0/1.0 与 1.0/1.5 的 MRR=0.973，当前生产档 1.0/2.0 为 0.959 |

详细数据、负样本行为与三项遗留建议见 [`evaluation-2026-10-21.md`](evaluation-2026-10-21.md)。
⚠️ 期望 ID 依赖商品数据，**商品重构后必须重新生成评估集**。

