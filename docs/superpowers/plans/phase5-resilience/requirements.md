# Phase 5 系统韧性 + 工具容错 + 工具日志——需求文档

**背景**：Phase 3+4 已完成客服工具链（查订单 `getMyOrders`/`getOrderStatus`、取消订单 `cancelOrder`，AiServices + DeepSeek + store 缓存修复死循环）。本需求为工具链补齐三块工程化能力：

1. **工具调用容错**（主题一）：LLM 参数解析/值错误、幻觉工具名的兜底；
2. **系统韧性**（主题二）：主备模型（DeepSeek 主 + qwen 仅 RAG 备用）+ 熔断 + 生成降级链——对话模型挂了客服不崩；
3. **工具日志**（可观测性）：记录每次工具调用的 userId/工具名/耗时/成败/脱敏结果，异步不阻塞。

**范围**：`ChatService`（降级链 + 工具容错接入 + 工具日志）、`RagConfig`（新增 qwen 备用模型 bean）、新增 `CircuitBreaker`/`ToolCallLogger`。**embedding 固定 DashScope（DeepSeek 无 embedding，不变）**。工具调用主用 DeepSeek（实测稳定）；qwen 仅作 RAG 问答备用，**不参与工具调用**（规避 qwen 工具调用未实测风险）。

---

## ① 硬约束清单

- **C1**【文档明确规定】**主备分工**：DeepSeek 主——工具调用 + 生成（AiServices 带工具）；qwen 备用——**仅纯 RAG 问答**（不带工具，规避 qwen 工具调用未实测）。embedding 固定 DashScope text-embedding-v1。
- **C2**【文档明确规定】**熔断**：DeepSeek 连续失败 N 次（默认 3）→ 熔断打开，熔断期内不再调 DeepSeek；TTL（默认 30s）后半开重试，成功重置。熔断只影响生成层，不影响检索层（检索不依赖对话模型）。
- **C3**【文档明确规定】**生成降级链**（完整回退）：`DeepSeek(带工具)` → 熔断 → `qwen 纯 RAG 问答(不带工具)` → qwen 也失败 → `模板拼检索结果(无 LLM)` → 全挂 → 提示"AI 暂时不可用"。
- **C4**【文档明确规定】**工具参数容错**：`toolArgumentsErrorHandler` 把解析/校验错误**回填给 LLM 修正重试**（不直接降级）；`hallucinatedToolNameStrategy` 对 LLM 幻觉的工具名返回"该工具不存在"回填。
- **C5**【文档明确规定】**工具日志**：异步记录（不阻塞对话），字段含 userId、工具名、耗时、成功/失败、脱敏结果摘要；**不记录原始参数/完整结果**（含订单号等 PII，安全审查要求）。
- **C6**【文档明确规定】回归约束：正常工具调用链路（B1~B7）不变；只读/取消订单工具行为不回归；SSE `token/end/error` 事件语义不变。
- **C7**【文档明确规定】qwen 备用模型配置：DashScope OpenAI 兼容端点 + `accumulateToolCallId(true)`（与 DeepSeek 一致的流式 tool-call 行为）。
- **C8**【推导得出】QueryRewriter 依赖对话模型：熔断时 QueryRewriter 应改用 qwen 或走既有"失败回退原始消息"兜底（不阻塞检索）。
- **C9**【文档明确规定】熔断/降级状态需可观测：日志标记"DeepSeek 熔断/降级到 qwen/降级到模板"。

---

## ② 改动点 / 设计

### 2.1 `RagConfig`：新增 qwen 备用流式模型 bean

```java
@Bean
public OpenAiStreamingChatModel openAiStreamingChatModelBackup(/* rag.llm.api-key（DashScope） + rag.llm.backup-model:qwen-plus */) {
    return OpenAiStreamingChatModel.builder()
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .apiKey(dashScopeKey)         // 复用 ${rag.llm.api-key}
            .modelName("qwen-plus")
            .accumulateToolCallId(true)
            .build();
}
```

> `application.yml` 增 `rag.llm.backup-model: qwen-plus`（可配）。

### 2.2 新增 `CircuitBreaker`（简单熔断器，不引第三方）

```java
// 状态：consecutiveFailures(int) + open(boolean) + openedAt(long)
onFailure()   // 连续失败达阈值 → open=true，记 openedAt
onSuccess()   // 成功 → 计数清零、open=false
isOpen()      // open 且未过 TTL → true；已过 TTL → 半开（返回 false，允许重试）
```

### 2.3 `ChatService`：生成降级链（ask 改造）

```
正常（!circuitBreaker.isOpen()）：
  AiServices(DeepSeek + 工具) → 工具调用 → 流式生成          ← 现有逻辑
熔断（isOpen()）：
  ① qwen 纯 RAG 问答：RagSearchService 检索 → openAiStreamingChatModelBackup 流式生成（不带工具）
  ② qwen 失败 → 模板拼检索结果（buildProductContext 文本直接返回，无 LLM）
  ③ 都失败 → 发 "AI 暂时不可用" + end
```

- 熔断计数挂载点：DeepSeek `onError` → `circuitBreaker.onFailure()`；`onCompleteResponse` → `onSuccess()`。
- 降级路径日志：`log.warn("DeepSeek 熔断，降级到 qwen/模板")`（C9）。

### 2.4 工具容错（AiServices 配置）

```java
AiServices.builder(...)
    .toolArgumentsErrorHandler((req, e) -> 返回错误信息回填 LLM 修正)
    .hallucinatedToolNameStrategy(req -> ToolExecutionResultMessage 返回"该工具不存在，请使用已提供的工具")
    ...
```

### 2.5 新增 `ToolCallLogger`（工具日志，异步）

```java
// 异步 executor，记录：userId、工具名、耗时、成功/失败、脱敏结果摘要（截断 + 不含订单号）
logToolCall(userId, toolName, durationMs, success, resultSummary)
```

`ChatService.onToolExecuted` 调用（现有 info 日志升级为结构化工具日志，仍不记 PII）。

---

## ③ 风险

- **R1**：qwen 备用在纯 RAG 问答场景未实测 → T1 前置验证；若 qwen 生成也不稳，降级链自动走"模板拼检索结果"（C3 兜底）。
- **R2**：熔断误判（DeepSeek 单次抖动触发熔断）→ 阈值/半开重试机制缓解（C2）。
- **R3**：降级链判断复杂度引入新 bug → 分步验证每个降级层级。
- **R4**：工具日志异步写引入线程/堆积 → 复用虚拟线程或有界队列（异步 + 吞异常）。

---

## ④ 验收标准

| # | 场景 | 预期 |
|---|------|------|
| D1 | 正常工具调用（查/取消订单） | 与 Phase 4 一致，零回归（B1~B7 保持） |
| D2 | LLM 参数解析/值错误 | 工具参数错误回填 LLM 修正重试，最终返回正常（或合理降级文案），不整轮报错 |
| D3 | LLM 幻觉工具名 | 返回"该工具不存在"回填，不中断对话 |
| D4 | DeepSeek 熔断（模拟失败） | 降级到 qwen 纯 RAG 问答，客服仍能回答商品知识（不调工具） |
| D5 | qwen 也失败 | 降级到模板拼检索结果，直接返回商品列表文本 |
| D6 | 全挂 | 发"AI 暂时不可用" + end，SSE 不断裂 |
| D7 | 工具日志 | 每次工具调用记录 userId/工具名/耗时/成败，不记录 PII |
| D8 | 编译 + 测试 | `mvn clean package` 通过；既有测试无新增失败 |
