# Phase 2 RAG 工程化增强——需求文档

**背景**：LangChain4j 已从 0.36.1 升级至 1.19.0（qdrant 集成 1.19.0-beta29），gRPC 兼容坑已修复，官方 `QdrantEmbeddingStore.search()` 已可用。本需求文档沉淀本次会话规划的 RAG 工程化增强项（记忆恢复、上下文防 token 爆炸、离线评估、降级可观测、文本截断、行为反馈），作为 Phase 2 实施蓝图。

**范围**：所有改动均在现有手写混合检索链路（向量 REST + MySQL FULLTEXT + RRF）基础上**增强**，不推翻既有检索逻辑。虚拟线程改造已完成（见 C7），本次文档中标记状态。

---

## ① 硬约束清单

> 编号 C 开头。标注【文档明确规定】= 来自 CLAUDE.md / 升级计划 / 会话明确结论；【推导得出】= 从代码与运行观测推断；❓不确定 = 存在歧义需查证。

- **C1**【文档明确规定】LangChain4j 锁定 `1.19.0`；`langchain4j-qdrant` 依赖单独锁定 `1.19.0-beta29`（1.x 该模块仅发 beta，pom 已实现）。
- **C2**【文档明确规定】记忆恢复：实现 `MySqlChatMemoryStore implements ChatMemoryStore`（官方扩展点）作为 `MessageWindowChatMemory` 的持久化后端（`.chatMemoryStore(store)`），从 MySQL 读写窗口消息，解决服务重启后上下文丢失；**禁止**手动重建窗口的旁路实现。
- **C3**【文档明确规定】上下文防 token 爆炸：优先用官方 `TokenWindowChatMemory`（`.maxTokens(token预算, TokenCountEstimator)`）按 token 预算裁剪窗口；同时 `SystemMessage` 只在会话创建时 `add` 一次（商品上下文拼 `UserMessage`），禁止每轮累积 SystemMessage。
- **C4**【文档明确规定】离线评估集：手工标注 30 条 `(query → 期望商品ID)` 数据，计算 `Recall@K` 与 `MRR`；评估集数据必须可复现、可解释。
- **C5**【文档明确规定】检索改走官方 `QdrantEmbeddingStore.search()`（gRPC 已修复），**删除手写 REST 绕道**（`RagSearchService.searchViaQdrantRest` 约 150 行）；向量路失败 → 自动降级纯关键词路 + 可观测日志，禁止静默失败。
- **C6**【文档明确规定】`buildTextSegment` 中商品详情（`detail`，stripHtml 后）超长必须截断，防止 embedding 单条超限。
- **C7**【文档明确规定】虚拟线程：`ChatService` 使用 `Executors.newVirtualThreadPerTaskExecutor()`（已实施）；`RedisCache` 的 `ThreadPoolExecutor`（有界 + DiscardPolicy）**禁止**替换为虚拟线程。
- **C8**【文档明确规定】所有新增配置/代码不得含明文凭证，api-key 继续走 `${DASHSCOPE_API_KEY}` 等环境变量注入。
- **C9**【文档明确规定】不改变既有检索逻辑：双路混合、RRF 合并、元数据过滤、分类名回退、SSE 推送的**行为语义**保持原样，仅做工程增强。
- **C10**【推导得出】qdrant-client `1.17.0` 与本地服务端 `1.19.0` 差 2 个 minor，触发客户端版本兼容 WARN；裁决测试实测 `search()` 正常（功能可用），WARN 非阻塞，可后续 `checkCompatibility=false` 或升级客户端消除。
- **C11**【推导得出】`conversationId` 在 DB 为 `Long` 类型；传入非数字字符串会抛 `NumberFormatException`（客服返回"服务异常"），接口需在入口做类型校验或容错。
- **C12**【推导得出】Windows 下 curl/非 UTF-8 客户端发送中文 JSON 体为 GBK 编码，后端 UTF-8 解析失败（`Invalid UTF-8 middle byte`）；测试与联调用 `-d @utf8文件` 规避。
- **C13**【推导得出】虚拟线程每任务一个、无并发上限，高并发需限流保护（低成本 ≠ 无成本）。
- **C14**【已查证 1.19.0】`MessageWindowChatMemory.ensureCapacity` 裁剪时用 `instanceof SystemMessage` 保留窗口最前的 SystemMessage（字段 `alwaysKeepSystemMessageFirst`），**SystemMessage 不被窗口容量裁剪**；但 `messages()` 返回**全部**累积的 SystemMessage → 多轮后 prompt 中 SystemMessage 线性累积（token 浪费 + 旧商品上下文污染模型判断），T2 修复仍必要（改单条 SystemMessage + 商品上下文拼 UserMessage）。
- **C15**【推导得出】离线评估集测试不应依赖外部服务（Qdrant/DashScope）才能跑，否则 CI/答辩环境不可复现；评估向量化数据需本地可构造（假 1536 维向量或固定快照）。
- **C16**【推导得出】`detail` 超长阈值：`text-embedding-v1` 单条文本有 token 上限（约为 512 token，具体以 DashScope 文档为准），截断阈值需保守设置。
- **C17**【文档明确规定】Phase 2 实现**优先使用 LangChain4j 官方 API 与扩展点**：`ChatMemoryStore` / `ChatMemoryProvider` / `TokenWindowChatMemory` / `TokenCountEstimator` / `QdrantEmbeddingStore.search()`；仅当框架无对应能力时才允许手写（如评估指标——`langchain4j-evaluator` 无 1.19.0 发布，已确认 HTTP 404，Recall@K/MRR 手写）。

---

## ② 接口入参 / 出参 / 失败返回（按接口分组）

### 2.1 `POST /api/chat/ask`（SSE 流式问答）——现有，受 C2/C3 影响

- **入参**（JSON body）：
  | 字段 | 类型 | 必填 | 约束 |
  |------|------|------|------|
  | `message` | String | 是 | 非空白；超长需前端截断（服务端暂不强制） |
  | `conversationId` | String | 否 | 默认 `"default"`；**必须可转 Long**，否则 C11 风险 |
- **正常出参**：SSE 事件流，`event: message`，`data` 为 JSON `{"type": "...", "content": "..."}`
  - `type=token`：流式增量
  - `type=end`：结束（`content` 为完整回复）
- **失败返回**：
  | 场景 | 返回内容 |
  |------|----------|
  | `message` 为空 | SSE `{"type":"error","content":"消息不能为空"}` + end |
  | LLM 调用失败 | SSE `{"type":"error","content":"AI 回复生成失败，请稍后重试"}` + end |
  | conversationId 非 Long | 当前返回 SSE `{"type":"error","content":"服务异常，请稍后重试"}`（C11：应在入口改为明确错误） |
  | 未登录 | userId 取 0，不阻断（现有行为） |

### 2.2 新增：`MySqlChatMemoryStore`（内部服务，记忆恢复 C2，官方扩展点）

- **入参**：`conversationId`（Object id）、`List<ChatMessage>`（窗口消息）
- **出参**：`getMessages(id)` 返回持久化消息（MySQL 读，按时间正序，`LIMIT` 最近 N 条）；`updateMessages(id, list)` 全量写回；`deleteMessages(id)` 清空
- **接入**：`MessageWindowChatMemory.builder().chatMemoryStore(mySqlStore).maxMessages(10)`；可选再包 `ChatMemoryProvider` 供 AiServices 使用（C17 框架扩展点）
- **失败返回**：DB 异常 → `log.warn` + 返回空列表（不阻断对话）

### 2.3 修改：`ChatService.ask()` 内部流程（受 C2/C3）

- 影响点：记忆获取（改为经 ChatMemoryProvider 恢复）、SystemMessage 注入时机（改为会话首次 add 一次，后续商品上下文拼进 UserMessage）
- 行为约束：不改变 SSE 推送时序与 token 流语义（C9）

### 2.4 `RagSearchService.search()`（内部，去手写化 + 降级增强 C5/C17）

- **入参**：`query, topK, categoryId, minPrice, maxPrice`（现有）
- **出参**：`List<RagHitVO>`（现有）
- **增强1（C5/C17）**：删除 `searchViaQdrantRest`（手写 HttpClient 调 REST），改走官方 `QdrantEmbeddingStore.search(EmbeddingSearchRequest)`；向量路抛异常 → 降级标记日志 `"Qdrant 不可用，已降级为纯关键词检索"`
- **增强2**：关键词路（MySQL FULLTEXT）照常，RRF 合并天然退化为纯关键词
- **失败返回**：两路全失败 → 返回空列表 + `log.warn`

### 2.5 修改：`ProductIndexer.buildTextSegment()`（内部，截断 C6）

- **入参**：`ProductDO product, BigDecimal[] prices`（现有）
- **出参**：`TextSegment`——`detail` stripHtml 后若超过阈值（C16）则截断并追加省略标记
- **约束**：截断只影响索引文本，不影响 `metadata`（名称/价格/分类仍完整）

### 2.6 新增：`RagEvaluationTest`（测试，非接口，C4/C15/C17）

- 评估集：30 条 `(query, expectedProductIds)` 手工标注
- 指标输出：`Recall@K`（K=3/5）、`MRR`，打印可读结果
- 框架约束：`langchain4j-evaluator` 无 1.19.0 发布（已确认 HTTP 404），Recall@K/MRR 指标手写（公式简单，属 C17 允许的合理手写）
- 约束：不依赖 Qdrant/DashScope 真实服务（C15，用固定假向量或本地快照）

### 2.7 可选：行为反馈采集（wiki 式隐式反馈，见 tasks T6）

- 若实施：新增内部方法/接口记录 `(query, 用户最终交互商品ID)`，来源为搜索后加购/下单/收藏行为
- **入参**：`query, userId, productId, action`（枚举：VIEW/CART/ORDER）
- **出参**：无（写库）；失败不影响主链路（异步、吞异常）

---

## ③ 风险翻车点清单（R 编号，写触发条件）

- **R1**：多轮对话后窗口被累积的 SystemMessage 挤占真实对话（C3 未修复时的现状）。触发条件：同一 conversationId 连续对话 > 窗口容量。
- **R2**：`conversationId` 传非数字（如前端生成 UUID 或测试传字符串）→ `NumberFormatException` → 客服返回笼统"服务异常"。触发条件：任何非数字 conversationId 入参。
- **R3**：Qdrant 服务停止/网络断开 → 向量路异常 → 当前静默降级为纯关键词但无日志标记（C5 增强前）。触发条件：Qdrant 容器停止或 6333/6334 端口不可达。
- **R4**：商品 `detail` 超长 → stripHtml 后整段塞入 TextSegment → 超出 embedding 单条 token 上限 → 索引该商品时向量化失败/截断（C6 修复前）。触发条件：存在详情超长的商品（如长图文详情页）。
- **R5**：评估集标注主观性 → 期望商品 ID 标错 → Recall@K/MRR 失真，误导后续优化方向。触发条件：标注人员对"该 query 应返回哪些商品"理解不一致。
- **R6**：虚拟线程无并发上限 → 高并发客服请求下虚拟线程数量膨胀，内存/调度压力（C13）。触发条件：并发 ask 请求数超过虚拟线程安全量级（需限流）。
- **R7**：记忆从 MySQL 恢复时，会话消息量巨大 → 全量查询并重建窗口开销大。触发条件：单会话 `chat_message` 行数极大（当前按 `LIMIT` 最近的 N 条优化）。
- **R8**：qdrant-client 1.17.0 与服务端 1.19.0 版本差 2 minor → 版本检查 WARN，虽实测功能正常但语义上"不保证"；若 Qdrant 服务端升级到 1.20+ 可能触发真实不兼容。触发条件：Qdrant 服务端版本漂移。
- **R9**：Windows 下中文 JSON 编码（GBK vs UTF-8）→ 接口/测试传中文乱码 → 检索/客服收到乱码 query。触发条件：非 UTF-8 客户端（curl 等）发送中文。
- **R10**：评估集测试若依赖真实 Qdrant 数据 → 答辩/CI 环境 Qdrant 未运行 → 测试失败，指标不可复现（C15 规避）。触发条件：评估测试在无 Qdrant 环境执行。
