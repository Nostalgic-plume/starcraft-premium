# Phase 3 AI 客服工具链——任务规划

> 依据 `requirements.md` 拆分。仅任务规划，不含代码。按依赖拓扑排序，自上而下执行。
>
> **状态：2026-08-28 全部完成**，验证结果见文末。

- [x] T1: LangChain4j 工具调用能力验证（依赖：无）
    验收：① 从本地 maven 仓库确认 1.19.0 关键 API 包名与签名（javap 确认）；② 身份传递定为「每请求 `new OrderTool(userId, orderService)` 绑定实例」。
    **API 备忘**：`@Tool`/`@P`=`dev.langchain4j.agent.tool.*`；`AiServices`=`dev.langchain4j.service.AiServices`；`TokenStream`=`service.TokenStream`；`OpenAiChatModel`=`model.openai.OpenAiChatModel`；`ChatRequest`=`model.chat.request.ChatRequest`；`JsonObjectSchema`=`model.chat.request.json.JsonObjectSchema`；`ToolExecutionResultMessage`=`data.message.ToolExecutionResultMessage`。**坑**：`@P` 的 `value` 与 `description` 不能同时设置（抛 IllegalArgumentException），用 `@P(name=..., description=...)`。

- [x] T2: `OrderService` 新增显式 userId 查询方法（依赖：无）
    验收：新增 `List<OrderVO> myOrdersByUserId(Long,Integer,int)` 与 `OrderVO getOrderDetailByUserId(Long,Long)`（内部零 StpUtil），现有方法零改动，编译通过。

- [x] T3: 新增 `OrderTool` 工具类（依赖：T2）
    验收：2 个工具 + description 触发场景；归属校验在 Service 层强制、工具层 catch `BusinessException` 转固定文案"该订单不存在或不属于您"；返回人话化、列表 LIMIT 10；异常 catch 降级文案。

- [x] T4+T5: `ChatService` 接入 AiServices 工具链 + SSE 流式适配（依赖：T1,T2,T3）
    **实现路径（含踩坑与最终方案）**：
    1. 先试 `AiServices` 流式 agent loop（`streamingChatModel` + `chatMemory` + `.tools(orderTool)`）→ 实测**死循环**（LLM 反复调同一工具撞 `maxToolCallingRoundTrips`）。
    2. 排查过程（curl 直调 DashScope/DeepSeek 验证 + 加日志）：
       - 排除 `accumulateToolCallId` 配置（qwen/DeepSeek 均"首个 chunk 发完整 id、后续空"，默认 true 正确）；
       - 排除 system prompt 内容、工具返回内容、QueryRewriter；
       - 排除 memory token 裁剪（TOKEN_BUDGET 调到 100000 仍循环）；
       - **定位根因**：AiServices 的 agent loop **每轮往返从 `ChatMemoryStore.getMessages` 重新读 memory**（非 TokenWindowChatMemory 内存窗口），而 `MySqlChatMemoryStore` 直接查 DB（只含 user/assistant，不含工具往返消息 `ToolExecutionResultMessage`）→ 每轮 LLM 看不到工具结果 → 死循环。**与模型无关**（qwen/DeepSeek 均复现，最初"qwen 不兼容"为误判）。
    3. **最终修复**：`MySqlChatMemoryStore` 加 **agent loop 活跃期内存缓存**——`updateMessages` 更新缓存（含工具消息）、`getMessages` 优先读缓存、`deleteMessages` 清理；`ChatService` 每轮对话开始 `deleteMessages` 清缓存、system prompt 写入缓存（`updateMessages`）确保 AiServices 首轮能读到。重启恢复仍靠 DB 兜底。
    4. **对话模型从 DashScope qwen-plus 切换 DeepSeek deepseek-chat**（用户决策：DeepSeek 兼容且效果/成本考量；embedding 仍 DashScope text-embedding-v1，DeepSeek 无 embedding API）。
    验收：① 工具单次调用、不循环；② 无工具意图走 RAG 不回归；③ SSE 逐字流式；④ 记忆/重启恢复不回归。

- [x] T6: 集成回归验证（依赖：T4,T5）
    验收：A1~A6 实测通过（见下），176 测试仅预存 7 失败。

- [x] T7: 文档更新（依赖：T6）
    验收：CLAUDE.md 智能客服段（工具链 + 死循环根因）+ requirements.md 实现偏差 + 本文件。

---

## 验证结果记录（2026-08-28）

| 验收项 | 结果 |
|--------|------|
| `mvn clean package` | BUILD SUCCESS |
| `mvn test` | 176 测试，仅预存 7 失败（ProductServiceImplTest 未 mock），零新增失败 |
| A1 问"我的订单" | ✅ AiServices agent loop 调 `getMyOrders` **1 次**，DeepSeek 流式输出"6 个订单梳理"人话 |
| A2 问具体订单 | ✅ `getOrderStatus` 归属校验（非本人/不存在 → "该订单不存在或不属于您"） |
| A3 非本人/不存在订单 | ✅ 固定文案 + 人话化 |
| A4 未登录 | ✅ `{"code":401,"msg":"未登录，请先登录"}`（项目统一风格） |
| A5 商品咨询 | ✅ 走 RAG（推荐手机），**未调用工具** |
| A6 工具异常兜底 | ✅ 代码级 catch-all（`OrderTool` 内部 catch → 降级文案，不断 SSE） |

### 本次踩坑记录（写代码前必读）

1. **AiServices agent loop 死循环（本项目最大坑）**：根因是 `MySqlChatMemoryStore`——AiServices 每轮从 `store.getMessages` 重读 memory，直接查 DB 会丢工具往返消息 → LLM 看不到工具结果反复调工具。**修复**：store 加 agent loop 活跃期缓存（详见 MySqlChatMemoryStore 类注释）。此问题与模型无关（qwen/DeepSeek 均复现）。
2. **`@P` 注解**：`value` 与 `description` 不能同时设置，用 `@P(name=..., description=...)`。
3. **`accumulateToolCallId`**：qwen/DeepSeek 均为"首个 chunk 发完整 tool-call id、后续空"（OpenAI 标准），保持默认 `true`；误设 `false` 会把 id 覆盖成空。
4. **DeepSeek 无 embedding API**：RAG 向量化必须留 DashScope（或第三方 embedding），对话模型换 DeepSeek 是混合架构。
5. **安全日志**：工具日志只记录工具名/id，不记录 args/result（含订单号等 PII）。
