# RAG 代码地图（`com.shop.rag`）

> 2026-10-08 包结构重构后生成（2026-10-15 校订类数与 `search` 包清单；2026-10-21 新增 ES 关键词路相关 5 类）。37 个类按职责分入 6 个子包，本表作为日常导航与讲项目的入口。
> 设计依据：[`rag-package-refactor-design.md`](rag-package-refactor-design.md)、[`specs/001-es-keyword-search/`](specs/001-es-keyword-search/)

## 一眼看全貌

```
com.shop.rag
├── search/       检索主链路（16 类）
├── index/        向量 / ES 索引构建与 Qdrant 集合管理（4 类）
├── chat/         智能客服编排：SSE + 意图路由 + workflow + 工具（9 类）
├── memory/       对话记忆与历史持久化（3 类）
├── resilience/   熔断与可观测（2 类）
└── config/       Bean 装配与配置绑定（5 类）
```

依赖方向（单向无环）：`config → index → search`，`chat → search / memory / resilience`

## 一、`search` — 检索（16 类）

| 类 | 职责 |
|----|------|
| `RagSearchService` | 混合检索入口：向量路 + 关键词路并行 → 加权 RRF 融合；两路候选池对齐（`topK×5` clamp 50~200）；嵌入模型故障降级为纯关键词 |
| `KeywordSearchRouter` | 关键词路路由：ES 优先，失败自动回退 MySQL FULLTEXT；连续 3 次失败熔断 30s，半开重试（功能编号 001） |
| `EsKeywordSearchService` | ES BM25 关键词路：`multi_match`（名称^3/品牌^2/分类^1.5/描述）+ 分类/价格/状态过滤下推；失败抛 `KeywordSearchUnavailableException`（功能编号 001） |
| `KeywordSearchUnavailableException` | ES 不可用异常——与「命中 0 条」严格区分，供路由触发回退 |
| `KeywordSearchService` | MySQL FULLTEXT 关键词路（同义词扩展 + 分类/价格过滤），现作为 ES 的兜底 |
| `QueryRewriter` | LLM 结合对话历史把短 query 改写为独立检索关键词（补指代 + 补同义表述） |
| `SynonymExpander` | 领域同义词扩展（只作用于关键词路，避免稀释向量语义） |
| `SynonymProperties` | `rag.synonym.*` 配置绑定 |
| `RagCandidate` | 检索候选——融合阶段的最小契约，只带商品 ID + 相关性分数，是「召回阶段只认 ID」的落点 |
| `RrfMerger` | 加权 RRF 融合（默认权重 1.0/2.0，k=60） |
| `RrfProperties` | `rag.rrf.*` 配置绑定 + 非法值兜底 |
| `RagHitEnricher` | topK 选出后统一回查 MySQL 补齐展示字段（名称/主图/描述/价格），即「回查富化」 |
| `SkuPriceResolver` | 商品 → 价格区间（min/max），检索路与索引路共用的唯一聚合来源 |
| `SkuSnapshot` | SKU 维度聚合快照（价格区间 + 总库存），一次查询同时满足「展示价格」与「判定可售」 |
| `RagSearchResult` | 混合检索结果契约：命中列表 + `allOutOfStock`（区分「真没搜到」与「全售罄被剔除」两种空结果） |
| `RagHitVO` | 检索结果视图（供 Controller / 客服链路消费） |

## 二、`index` — 索引（4 类）

| 类 | 职责 |
|----|------|
| `ProductIndexer` | 商品向量索引构建：启动全量 + 增量增删改，确定性 pointId |
| `EsProductIndexer` | ES 商品索引：mapping（ik_max_word/ik_smart）、全量重建、增量 upsert/delete、启动空索引自动导入与定时自愈（功能编号 001） |
| `ProductIndexSyncService` | 索引同步门面：一次调用扇出到 Qdrant + ES，两端失败互相隔离；商品服务/对账任务/管理接口的唯一入口 |
| `QdrantCollectionHelper` | Qdrant 集合管理（REST 6333）：建集合、维度校验、count |

## 三、`chat` — 对话编排（9 类）

| 类 | 职责 |
|----|------|
| `ChatService` | 客服主流程：心跳 → 记忆重建 → 意图路由 → Query 改写 → 检索 → 生成（含降级链）；SSE 事件推送 |
| `ChatIntentResolver` | 意图识别（规则优先 + LLM 兜底）：cancel / query / chitchat / product |
| `Intent` | 意图枚举 |
| `IntentWorkflow` | 受控 workflow 接口（`tryHandle` 语义） |
| `CancelOrderWorkflow` | 取消订单铁轨：跨轮确认 + 状态机 + 库存回滚（写操作不在 agent 工具池） |
| `OrderQueryWorkflow` | 订单查询受控流程：结构化查询确定性执行，模糊长句交回只读 agent |
| `WorkflowContext` | workflow 执行上下文（会话/用户/消息/SSE/REDIS） |
| `AiAssistant` | LangChain4j `AiServices` 接口定义 |
| `OrderQueryTool` | 只读订单工具（`getMyOrders` / `getOrderStatus`），userId 显式传入不信任 LLM 参数 |

## 四、`memory` — 记忆与历史（3 类）

| 类 | 职责 |
|----|------|
| `MySqlChatMemoryStore` | LangChain4j `ChatMemoryStore` 实现（`chat_message` 表）+ **agent loop 活跃期内存缓存**（防工具往返消息丢失导致死循环） |
| `ChatHistoryService` | 会话/消息仓储 |
| `ChatFeedbackService` | 对话反馈（点赞/点踩）写入 |

## 五、`resilience` — 韧性（2 类）

| 类 | 职责 |
|----|------|
| `CircuitBreaker` | 熔断器：连续 3 次失败熔断 30s，半开重试（只作用于生成层） |
| `ToolCallLogger` | 工具调用异步日志（userId / 工具名 / 耗时 / 成败，不记 PII） |

## 六、`config` — 配置（5 类）

| 类 | 职责 |
|----|------|
| `RagConfig` | Bean 装配：嵌入模型、主/备流式对话模型、Qdrant 向量存储；启动确保集合存在 |
| `EsClientConfig` | ES 官方 Java Client 装配（RestClient + Transport，懒连接，ES 未启动不阻断应用启动） |
| `RagElasticsearchProperties` | `rag.elasticsearch.*` 绑定（开关/连接/超时/半开间隔/批量） |
| `RagProperties` | `rag.qdrant.*` 绑定（`dimension` 是**全链路维度唯一事实来源**） |
| `RagLlmProperties` | `rag.llm.*` 绑定 + 空值归一化 + 启动端点回显与告警 |

## 常用入口速查

| 我要找… | 去哪 |
|---------|------|
| 检索为什么召回不到 | `search/RagSearchService` → `search/KeywordSearchRouter`（ES/MySQL）→ `search/SynonymExpander` |
| 关键词路走的是 ES 还是 FULLTEXT | 日志 `[RAG指标] ... 关键词路=`；`search/KeywordSearchRouter`；管理接口 `/api/admin/rag/status` 的 `keywordRoute` |
| ES 索引怎么建 / 为什么没数据 | `index/EsProductIndexer`（mapping/自动导入/自愈）；`POST /api/admin/rag/rebuild` |
| 用户说的词和商品名不一致 | `search/SynonymExpander`（词表）+ `search/QueryRewriter`（LLM 兜底） |
| 客服回答慢 / 首字延迟 | `chat/ChatService`（心跳、Query 改写、检索、生成四段） |
| 工具调用死循环 | `memory/MySqlChatMemoryStore`（agent loop 缓存） |
| 模型挂了怎么办 | `resilience/CircuitBreaker` + `chat/ChatService` 的降级链 + `config/RagConfig` 的两个模型 Bean |
| 改配置不生效 | `config/RagLlmProperties.normalizeAndLog()` 的启动回显与告警 |
| 向量维度不一致 | `config/RagProperties.dimension`（唯一事实来源） |

## 测试分布（`src/test/java/com/shop/rag/`）

| 子包 | 测试 |
|------|------|
| `search/` | `RagSearchServiceTest`、`RagFallbackTest`、`KeywordSearchServiceTest`、`KeywordSearchRouterTest`（ES/MySQL 路由与熔断）、`RrfMergerTest`、`RrfPropertiesBindingTest`、`SynonymExpanderTest`、`SynonymPropertiesBindingTest`、`RagHitEnricherTest`、`RagStockFilterTest`、`SkuPriceResolverTest`、`RagMetricsTest`、`RagMetrics`（辅助类）、`MyBatisPlusTestSupport`（辅助类）、`RagEvaluationTest`（`@SpringBootTest`，需真实 Qdrant/BGE-m3）、`RagSearchExcerptContractTest`（`@SpringBootTest`，需真实检索链路） |
| `index/` | `ProductIndexerTest`、`ProductIndexerRebuildTest`、`QdrantGrpcCompatibilityTest` |
| `chat/` | `ChatIntentResolverTest`、`CancelOrderWorkflowTest`、`OrderQueryWorkflowTest`、`ChatServiceClearConversationTest`、`BackupModelSmokeTest`、`DeepSeekAiServicesSmokeTest` |
