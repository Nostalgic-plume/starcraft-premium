# Phase 2 RAG 工程化增强——任务规划

> 依据 `requirements.md` 拆分。仅任务规划，不含代码。按依赖拓扑排序，自上而下执行。
> **全局原则（C17）**：优先使用 LangChain4j 官方 API 与扩展点；虚拟线程改造（C7）已实施，不在任务列表内。
>
> **状态：2026-08-26 全部完成（T6 可选，未实施）**，验证结果见文末。

- [x] T1: 记忆持久化——实现 `MySqlChatMemoryStore implements ChatMemoryStore`（官方扩展点），作为 `MessageWindowChatMemory` 的持久化后端（`.chatMemoryStore(...)`），替换默认 `InMemoryChatMemoryStore`（依赖：无）
    验收要点：① `getMessages(id)` 从 MySQL 读最近 N 条（时间倒序 LIMIT 再正序）；② `updateMessages(id, list)` 全量写回、`deleteMessages(id)` 清空；③ 重启应用后同一 conversationId 再对话能引用重启前历史（**已实测通过**：第二轮问答 LLM 正确引用"推荐一款手机"）；④ DB 异常降级为空列表 + `log.warn`。

- [x] T2: 上下文防 token 爆炸——改用官方 `TokenWindowChatMemory`（`.maxTokens(token预算, TokenCountEstimator)`）替代 `MessageWindowChatMemory`，并修复 SystemMessage 累积（依赖：T1）
    验收要点：① SystemMessage 每轮单条最新（generate 时拼装，不落库不累积）；② 多轮后 token 受 `TOKEN_BUDGET=3000` 约束；③ `TokenCountEstimator` 用官方 `OpenAiTokenCountEstimator("gpt-4o")`（**注意**：qwen-plus 名不识别 jtokkit，已实测修复）；④ **builder 必须 `.id(conversationId)`**（否则 store 按 null 隔离读取 → 记忆失效，已实测发现并修复）。

- [x] T3: 离线评估集——新增 `RagEvaluationTest`，30 条手工标注 `(query → 期望商品ID)`，计算 Recall@K（K=3/5）与 MRR（依赖：T2）
    验收要点：① 覆盖 5 类 query（品牌/品类/模糊/冷门/错别字）；② 离线 `RagMetricsTest`（3 用例）不依赖外部服务；③ 真实评估 `RagEvaluationTest` 依赖 Qdrant，环境缺失自动跳过；④ **实测指标：Recall@3=0.425 / Recall@5=0.686 / MRR=0.368**；暴露商品 3/6/9 向量漂移与错别字容错失效问题。

- [x] T4: 去手写化 + 降级可观测——删除 `searchViaQdrantRest`（手写 REST 约 150 行），改走官方 `QdrantEmbeddingStore.search(EmbeddingSearchRequest)`；向量路失败自动降级纯关键词路并输出降级标记日志（依赖：T3）
    验收要点：① `RagSearchService` 不再有手写 HttpClient 调 `:6333/points/search`；② 官方 `search()` 路径实测正常（rag-search 返回华为/小米商品）；③ 向量路异常降级日志 `"Qdrant 不可用，已降级为纯关键词检索"`；④ gRPC 裁决测试继续通过。

- [x] T5: 索引文本截断——`buildTextSegment` 中 stripHtml 后的 `detail` 超长截断（依赖：无）
    验收要点：① `MAX_DETAIL_LENGTH=500` 字符截断 + 省略号；② 只截 detail，不碰 metadata。

- [ ] T6: 行为反馈采集（**可选，未实施**）——记录 `(query, 用户最终交互商品ID)`，来源为搜索后加购/下单/收藏（依赖：T3）
    验收要点（若实施）：① 内部方法记录 VIEW/CART/ORDER 三类行为，异步 + 吞异常；② 采集数据可导出为评估集格式；③ 不改变现有接口返回语义。

- [x] T7: 集成回归验证——Phase 2 全量改动后全链路回归（依赖：T1,T2,T3,T4,T5,T6）
    验收要点：① `mvn clean package` BUILD SUCCESS；② `mvn test` 176 个测试，仅预存 `ProductServiceImplTest` 7 个失败（Guava BloomFilter 未 mock，与本次无关）；③ 启动回归：`rag-search` / `login` / `chat/ask` SSE 全部正常；④ **重启应用验证记忆恢复生效**（实测：第二轮引用历史"推荐一款手机"）；⑤ 文档更新完成。

---

## 验证结果记录（2026-08-26）

| 验证项 | 结果 |
|--------|------|
| `mvn clean compile` | BUILD SUCCESS |
| `mvn test` | 176 测试，仅预存 7 失败（与本次无关） |
| 真实评估（30 条） | **Recall@3=0.425 / Recall@5=0.686 / MRR=0.368** |
| rag-search（官方 search 路径） | 正常，命中华为/小米 |
| 客服 SSE 第一轮 | 正常，命中商品并流式返回 |
| **记忆恢复（重启后第二轮）** | **成功，引用"推荐一款手机"** |

### 回归抓到并修复的 2 个 T2 bug
1. `OpenAiTokenCountEstimator("qwen-plus")` 不认识 jtokkit 模型名 → 改 `"gpt-4o"`（估算近似）
2. `TokenWindowChatMemory.builder()` 缺 `.id(conversationId)` → store 按 null 读取记忆失效 → 补 id
