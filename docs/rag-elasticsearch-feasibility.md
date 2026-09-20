# 关键词路升级 BM25 的可行性评估（MySQL 8.0 vs Elasticsearch）

> 状态：评估稿（2026-09-20）
> 触发问题：「MySQL 8.0 以上有倒排索引，是不是能提供 BM25？想采用 BM25 + 自定义分词器，难度高不高？要不要加 Elasticsearch？」
> 关联：`docs/rag-hybrid-tuning-design.md`（本轮已落地的加权 RRF 与过滤修复）

## 一、结论摘要

| 方案 | 结论 | 依据 |
|------|------|------|
| 在 MySQL 8.0 内使用 BM25 | **不可行** | 本机实测：内核无任何 BM25 参数，倒排表缺文档长度统计（见 §二） |
| 给 MySQL 加自定义中文分词器（IK 那种） | **不推荐** | MySQL 全文 parser 只能以 C++ server 插件形式加载，与版本强绑定、需重启、线上不可维护 |
| 引入 Elasticsearch（IK + 真 BM25 + 原生 RRF retriever） | **可行，推荐作为关键词路替换方案** | 见 §三 版本矩阵与 §四 接入方案 |
| 保留现有 MySQL FULLTEXT + 应用层加权 RRF | **先做（成本最低）** | 本轮已落地，见 `docs/rag-hybrid-tuning-design.md` |

一句话：**「倒排索引存在」不等于「BM25 可用」**。BM25 需要文档长度、平均文档长度与可调参数 k1/b，
InnoDB 的倒排表只存词、词频、文档频率与位置，内核不暴露打分函数，因此无法在 MySQL 内实现 BM25。

## 二、实测证据（本机 2026-09-20，MySQL 8.0.36）

```sql
SHOW VARIABLES LIKE '%bm25%';        -- 零结果：内核无 BM25 相关参数
SHOW VARIABLES LIKE 'innodb_ft%';    -- 只有缓存/停用词/分词长度，无 k1、b 等打分参数
SHOW VARIABLES LIKE 'ngram%';        -- ngram_token_size = 2
SHOW CREATE TABLE product;           -- FULLTEXT KEY ft_product_search(name,description,brand) WITH PARSER ngram
SET GLOBAL innodb_ft_aux_table='shop/product';
SELECT WORD, DOC_COUNT, DOC_ID, POSITION FROM information_schema.innodb_ft_index_table LIMIT ...;
```

倒排表实际结构：`WORD / DOC_COUNT / DOC_ID / POSITION`，全表 9080 条 posting、4237 个词元。
**没有文档长度字段，也没有平均文档长度** —— BM25 的长度归一化项在数据层面就不存在，故非「没开开关」，
而是内核不具备该能力。

关于「书里说 MySQL 8.0 以上有倒排索引能提供 BM25」的三种可能出处（本次无法核对原文）：
1. MySQL HeatWave（云端增值服务）的 BM25 排序 —— 属云服务特性，不是社区版 InnoDB 内核；
2. MariaDB 的 Mroonga 全文引擎插件 —— 那是另一个数据库发行版；
3. 把「倒排索引 + 词频类打分」笼统称作 BM25 的通俗写法。

**通用裁决方法**：任何声称提供 BM25 的 MySQL 发行版，跑 `SHOW VARIABLES LIKE '%bm25%'` 即可当场验证。

## 三、Elasticsearch 接入的版本矩阵（含本次未确证项）

| 组件 | 现状 / 建议 | 证据 |
|------|------------|------|
| Spring Boot | 3.2.12（`pom.xml`） | 本地 pom |
| Java | pom 声明 22；实际编译 `release 21`（JDK 22 编译通过） | `mvn compile` 输出 |
| Spring Data Elasticsearch | 随 Boot 3.2.x 的受管版本（5.2.x 系列）；**确切版本号需 `mvn dependency:tree` 实测** | 本次未能核实：`repo1.maven.org` 被网络策略拦截（http=000），本地 `.m2` 无 ES 依赖缓存 |
| Maven 依赖下载 | **无障碍**：`~/.m2/settings.xml` 已配置阿里云镜像，镜像可达（http=200） | `curl https://maven.aliyun.com/...` |
| Elasticsearch | 以 **8.11+** 为目标：8.11 的 `rrf.html` 官方文档可达（http=200） | 8.8 同名页面本次不可达（http=000），无法确证 8.8 是否已含 RRF retriever |
| IK 分词器 | 需按目标 ES 版本选对应 release（版本强绑定）；**具体对应关系待实测** | GitHub API 本次仅返回 `"tag_name": "Latest"`，未取到版本列表 |
| 原生 RRF retriever | ES 8.11+ 提供；注意**它是等权的**，要加权仍需应用层融合 | 官方文档可达；参数名（`rank_constant` / `rank_window_size`）本次未从页面正文确证 |

**建议的实测裁决流程**（符合团队「版本兼容性以本地构建实测裁决」规范）：
1. docker 起单机 ES（8.11 或 8.12），装对应版本 IK；
2. `_analyze` 验证中文分词效果，`_search` 验证 BM25 分数分布与 `k1`/`b` 生效；
3. 加 `spring-boot-starter-data-elasticsearch`，`mvn compile` + 启动自测裁决版本漂移；
4. 用现有评估集跑「MySQL FULLTEXT vs ES BM25」对照。

## 四、接入方案（不动向量路）

- 保持 Qdrant 向量路与加权 RRF 不变，**只替换关键词路**为 ES BM25 检索。
- **双消费方改造**：Java `KeywordSearchService` 与 shop-ai `app/retrieval/keyword_search.py` 共享同一份
  `ft_product_search` 索引，换引擎意味着两端都要改（这是本项目接入 ES 的主要成本乘数）。
- 数据同步：复用现有 `IndexAdminController` 的全量重建入口 + 商品增删改时的增量写入；需新增 ES 与 MySQL 的一致性校验。
- 同义词：可迁移到 ES 的 synonym filter（比手写 `SynonymExpander` 更标准，且支持索引期/查询期两种模式）；
  迁移前需保持现有「整词匹配、宁少勿滥」的约束，否则子串匹配会带来词义错误扩散。
- 过滤：分类/价格过滤在 ES 侧用 filter context 更自然，能顺带修掉当前「关键词路不过滤」的契约缺口
  （本轮已在 MySQL 路径上补齐，见设计文档 §2.3）。

## 五、成本与风险

| 项 | 说明 |
|----|------|
| 资源 | 新增一个 JVM 中间件（堆内存 2~4G），与现有 Qdrant / Redis / RocketMQ 并存；本机曾因 8GB 显存不足排除 BGE-m3 sparse 模式（`ai-service-python-plan.md` 记录），资源余量需先确认 |
| 一致性 | MySQL 与 ES 双写，需处理失败重试与漂移校验 |
| 运维 | 分片/副本/JVM 堆/segment merge/reindex 别名切换 |
| 学习曲线 | 上手（analyzer 配置、BM25 参数、`_analyze` 调试）约 1~2 周；生产级约 1~2 个月 |
| 收益 | 真 BM25（可调 k1/b）、可插拔分词器、词干/拼音/纠错、横向扩展、面试可讲的调优素材 |

## 六、决策建议（ROI 倾向明确）

**先完成低成本三项，再决定是否上 ES。** 顺序理由：本轮的「候选池对齐 + 加权 RRF + 关键词路过滤补齐」
成本约 1 天，且能用现有评估集量化；而当前检索质量的真实瓶颈是**标注质量、单字/长尾召回、候选池不对称**，
这三项 ES 一个都不会自动修好。

满足以下任一判据再启动 ES 接入：
1. 评估集显示单字 / 长尾 / 错别字 query 占比高，而向量路泛化不足；
2. 需要词干化、拼音、纠错等 analyzer 能力（MySQL 侧结构性不可达）；
3. 需要 BM25 的 k1/b 在线调优；
4. 求职需要「BM25 原理 + analyzer 调试 + 混合检索调优」的完整叙事（本项目已有评估脚手架，是很好的载体）。

若上，按 §三 的实测裁决流程先做 POC，再全量迁移；**不要为了 BM25 在 MySQL 内自造倒排/统计表或写 C++ 插件**。

## 七、待核实清单（本次未确证，勿当结论使用）

1. spring-data-elasticsearch 在 Spring Boot 3.2.12 下的确切受管版本号（需 `mvn dependency:tree` 或可联网环境查 Maven Central）；
2. ES RRF retriever 的最低可用版本（8.8 页面本次不可达，仅确证 8.11 存在该文档）；
3. RRF retriever 的参数名与是否支持权重（文档正文本次抓取失败，仅确证页面可达）；
4. ~~IK 分词器各 release 与 ES 8.11 / 8.12 的对应关系~~ **已确证（2026-09-20）**：IK 按 ES 版本同名发布，8.11.4 包实测可用，下载源见 §八。

## 八、一键启动落地（2026-09-20 实测）

ES 以「本地构建镜像 + compose 编排」方式并入 `docker-compose.yml`，`docker compose up -d` 即随其他中间件一并启动，
无需任何手工建容器或手动装插件步骤。

### 8.1 硬约束（踩过即废，勿改）

1. **镜像必须自带 IK**：无 IK 时 ES 中文只做单字切分（本机实测 `精华` → `精` / `华`），BM25 词项失去语义，方案不成立。
   故用 `docker/elasticsearch/Dockerfile` 在构建期预装，而非容器起来后再手装。
2. **IK 版本号必须等于 ES 版本号**：8.11.4 配 8.11.4，不匹配则插件加载失败。版本号唯一事实来源是 Dockerfile 顶部两个 ARG，
   compose 侧不再重复声明版本，只引用构建产物标签 `shop-elasticsearch:8.11.4`。
3. **下载源不是 GitHub Releases**：`infinilabs/analysis-ik` 已不在 GitHub 逐版本打 tag（`v8.11.4` → 404，API 查 releases 只返回 `Latest`）。
   正确地址为 `https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-<版本>.zip`（实测 200 / 4617313 字节）。
4. **端口只绑 `127.0.0.1:9200`**：与既有编排一致，不暴露局域网。
5. **堆内存必须显式锁 512m**：ES 默认取宿主内存一半，本机余量不足；同时关闭 `xpack.security`（靠端口只绑本机兜底），仅限本地开发。

### 8.2 验收方式与实测证据

| 验收项 | 命令 | 实测结果 |
|---|---|---|
| 镜像构建 | `docker compose build elasticsearch` | `Installed analysis-ik` 成功；构建期自检 `plugins/analysis-ik` 存在，缺失即构建失败 |
| 启动与健康 | `docker compose up -d elasticsearch` | 15 秒进入 `healthy`（healthcheck 用镜像自带 curl 探 `_cluster/health`，单节点副本分片必然 unassigned，故 yellow 即算可用） |
| 插件加载 | `GET /_cat/plugins?v` | `analysis-ik 8.11.4` |
| 中文分词 | `POST /_analyze {"analyzer":"ik_max_word"}` | `雅诗兰黛小棕瓶精华第七代50ml` → `雅诗兰黛 / 小 / 棕 / 瓶 / 精华 / 第七代 ...`；同句 standard 分词器切成 `精` `华` 两字，对比明显 |
| 不回归 | `docker compose up -d` | 6 个容器齐活，既有 5 个容器均 `Running`，未被重建或重启 |

### 8.3 尚未完成：Java 侧接入

**compose 就绪 ≠ 检索可切换**。当前 Java 代码未引用 ES，仍需：新增 ES 依赖与连接配置 → 建索引（`ik_max_word` 映射，字段对齐
`ft_product_search`）→ 实现 ES 检索并替换/并联关键词路 → 用现有评估集回归对比（权重基线 1.0/2.0）。属跨模块改造，按 L3 先出设计文档与计划书再动手。