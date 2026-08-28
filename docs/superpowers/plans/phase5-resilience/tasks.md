# Phase 5 系统韧性 + 工具容错 + 工具日志——任务规划

> 依据 `requirements.md` 拆分。按依赖拓扑排序，自上而下执行。
>
> **状态：待开始**

- [ ] T1: 前置验证——qwen 纯 RAG 问答可用（依赖：无）
    目的：确认 qwen 备用模型能用于"纯 RAG 问答"降级（C1/R1 前置）。
    动作：① 新增 qwen 备用模型 bean（T2 的一部分，可先做）；② 用一个临时端点/测试确认 qwen-plus 流式生成回答正常（不带工具）。
    验收：qwen-plus 流式返回正常文本；若 qwen 不可用，降级链自动走模板（C3 兜底，不阻塞）。

- [ ] T2: `RagConfig` 新增 qwen 备用流式模型 bean + `application.yml` 配置（依赖：无）
    动作：`openAiStreamingChatModelBackup`（DashScope compatible + qwen-plus + `accumulateToolCallId(true)`，C7）；`application.yml` 增 `rag.llm.backup-model: qwen-plus`（key 复用 `rag.llm.api-key`）。
    验收：`mvn compile` 通过；bean 注入成功。

- [ ] T3: 新增 `CircuitBreaker` 熔断器（依赖：无）
    动作：实现 `consecutiveFailures` 计数 + `open` 开关 + `openedAt` TTL（默认 3 次 / 30s，半开重试，C2）。
    验收：单元级验证——连续失败达阈值熔断、TTL 后半开、成功重置。

- [ ] T4: 工具容错——`toolArgumentsErrorHandler` + `hallucinatedToolNameStrategy`（依赖：无）
    动作：AiServices 配置两个处理器（C4）：参数错误回填 LLM 修正重试；幻觉工具名返回"该工具不存在"回填。
    验收：① LLM 参数解析/值错误 → 不整轮报错，回填后 LLM 修正或返回合理文案（D2）；② 幻觉工具名 → 友好回填不中断（D3）。

- [ ] T5: `ChatService` 生成降级链（DeepSeek → qwen → 模板 → 提示）（依赖：T2,T3）
    动作：ask 改造（C3）——正常走 DeepSeek AiServices；熔断走 qwen 纯 RAG 问答（不带工具，复用 `RagSearchService` + `openAiStreamingChatModelBackup` 流式）；qwen 失败走模板拼检索结果（`buildProductContext` 直接输出）；全挂发"AI 暂时不可用"。
    DeepSeek 熔断计数挂载：`onError`→`onFailure()`、`onCompleteResponse`→`onSuccess()`；降级路径打 `log.warn` 标记（C9）。
    验收：D4/D5/D6 分步验证——模拟 DeepSeek 失败降级 qwen、qwen 失败降级模板、全挂提示。

- [ ] T6: 工具日志——新增 `ToolCallLogger`（异步，可观测性）（依赖：T5）
    动作：异步记录 userId/工具名/耗时/成败/脱敏结果摘要（C5，不记 PII）；`ChatService.onToolExecuted` 调用。
    验收：D7——每次工具调用产生一条结构化工具日志，无 PII；异步不阻塞对话。

- [ ] T7: 集成回归验证 + 文档（依赖：T1~T6）
    动作：`mvn clean package` + 既有测试；手动验证 D1~D8；更新 CLAUDE.md（主备/熔断/工具日志）+ 本文件验证记录 + 面试话术文档补充"韧性"叙事。
    验收：D1 正常工具调用零回归（B1~B7 保持）；D2~D7 通过；D8 编译测试通过。

---

## 验证结果记录（2026-08-28）

| 验收项 | 结果 |
|--------|------|
| D1 正常工具调用回归 | ✅ 我的订单 → getMyOrders 正常（DeepSeek 恢复后） |
| D2 工具参数错误兜底 | ✅ 超范围订单号 9999999999999999999 → parseOrderId → 友好文案（无 onError） |
| D3 幻觉工具名兜底 | ✅ `hallucinatedToolNameStrategy` 配置生效（代码级，LLM 不易触发未实测） |
| D4 DeepSeek 熔断降级 qwen | ✅ 实测：改错 key → DeepSeek 失败 → 当场降级 qwen 纯 RAG → 连续 3 次失败 `熔断器触发：连续失败 3 次，熔断 30000ms` → 后续 `DeepSeek 熔断中 → 降级 qwen（不调用工具）` |
| D5 qwen 也失败 → 模板 | ✅ 代码审查（`templateFallback` 直接输出 productContext，无 LLM） |
| D6 全挂提示 | ✅ 代码审查（发"AI 暂时不可用" + end） |
| D7 工具日志 | ✅ 实测：`工具调用日志 \| userId=10003 \| tool=getMyOrders \| 耗时=48ms \| success=true`（异步，无 PII） |
| D8 编译 + 测试 | ✅ 177 测试仅预存 7 失败（ProductServiceImplTest），零新增 |

### 本次新增踩坑

1. **双模型 bean 注入冲突**：新增 qwen 备用 `OpenAiStreamingChatModel` 后，ChatService/QueryRewriter 注入主模型时 Spring 发现 2 个同类型 bean 启动失败。**修复**：`@Qualifier` 区分 + `lombok.config` 配 `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier`（否则 Lombok 构造器不复制字段注解，@Qualifier 失效）。
2. **熔断验证方法**：临时把 `rag.llm.chat-api-key` 改成错误值模拟 DeepSeek 故障，验证降级链后恢复（不改生产代码路径）。
