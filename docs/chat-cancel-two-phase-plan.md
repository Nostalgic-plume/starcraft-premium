# 客服取消订单两阶段改造 — 计划书

> 对应设计：[chat-cancel-two-phase-design.md](chat-cancel-two-phase-design.md)
> 日期：2026-10-18

## 任务拆分与顺序

按依赖关系排序：先做底层能力（pending / 查询 / 执行），再做工具，最后改路由和接入层。

### T1 pending 状态服务与三态判定

**改动**
- 新增 `CancelPendingService`：
  - `write(conversationId, userId, orderId)` / `get(conversationId)` / `clear(conversationId)`；
  - `judge(message)` → `CONFIRM / REVERSE / OTHER`（确定性规则，反悔优先）。
- 新增单测 `CancelPendingServiceTest`：确认 / 反悔 / 其他各覆盖，重点覆盖「不要取消」「取消要手续费吗」。

**验收**：单测全绿；Redis key 格式为 `chat:pending:cancel:{conversationId}`。

### T2 OrderService 查询与取消改造

**改动**
- `OrderService` 新增：
  - `myOrdersByUserIdAndTime(userId, status, startTime, endTime, limit)`；
  - 复用 `cancelOrderByUserId`，内部改条件更新。
- `OrderServiceImpl.cancelOrderByUserId`：
  - 条件更新 `WHERE id = ? AND user_id = ? AND status = 待支付`；
  - 影响行数为 0 → 抛业务异常；
  - 更新成功后再回滚库存。
- 单测：构造并发取消（两个线程），断言库存只回滚一次。

**验收**：`mvn test -Dtest=OrderServiceImplTest` 通过；并发用例库存回滚次数 = 1。

### T3 工具扩展

**改动**
- `OrderQueryTool.getMyOrders` 增加 `relativeDay` 参数，返回文本补下单时间；
- 新增 `RequestCancelTool`：
  - 构造器注入 `userId` + `OrderService` + `CancelPendingService`；
  - `requestCancelOrder(orderId)` 只校验 + 写 pending + 返回确认话术；
  - 不调用 `cancelOrderByUserId`。
- 单测：`RequestCancelToolTest` 覆盖非法号 / 非本人 / 状态不符 / 成功写 pending。

**验收**：单测证明工具不会触发取消；pending 写入正确。

### T4 ChatService 路由改造

**改动**
- `ask()` 中，在意图识别**之前**插入 pending 判定：
  - `CONFIRM` → 调 `CancelOrderWorkflow.execute()`，审计，返回；
  - `REVERSE` → 清 pending，回复；
  - `OTHER` → 清 pending，继续。
- `ORDER_CANCEL` 不再直接进 `cancelOrderWorkflow.tryHandle`，改为进入 agent loop；
- 工具注册按意图收窄：
  - `ORDER_CANCEL` → `OrderQueryTool` + `RequestCancelTool`；
  - `ORDER_QUERY` → `OrderQueryTool`；
  - 其他 → 无。
- 候选订单上下文（V1）：`getMyOrders` 结果写 Redis `chat:candidate:orders:{conversationId}`（TTL 10 分钟），下一轮注入 system prompt；LLM 只能从候选列表选择。

**验收**：手工验证「取消昨天的订单 → 确认取消」链路；`ORDER_QUERY` 与闲聊不受影响。

### T5 CancelOrderWorkflow 收敛为执行器

**改动**
- 移除「无订单号列候选」「pending 三态判定」逻辑（分别由 agent 和 `CancelPendingService` 接管）；
- 保留并公开 `execute(userId, conversationId, orderId, emitter)`：
  - 执行前再校验归属 + 状态；
  - 调 `cancelOrderByUserId`；
  - 清 pending；
  - 写审计日志。

**验收**：workflow 只做执行；原有单测调整后通过。

### T6 接入层守卫（低成本项）

**改动**
- `ChatController`：
  - 消息长度上限 2000 字符，超长返回错误事件；
  - `conversationId` 缺省时不再用 `"default"`，改为服务端生成（`UUID`）并随 SSE 首个事件返回，或直接拒绝；
  - 会话状态检查：`status != 0` 拒绝。
- 新增简单频率限制：Redis 计数 `chat:rate:{userId}`，每分钟 20 条，超限返回错误事件。

**验收**：超长消息被拒；未传会话 ID 时不与其他用户串会话；连续发送 21 条触发限流。

### T7 回归与端到端验收

**改动**
- 跑 `mvn test`（注意既有 `ProductServiceImplTest` 18 个失败属基线，非回归）；
- 端到端手工用例：
  1. 取消昨天的订单 → 确认 → 成功、库存回滚；
  2. 「确认取消」→ 执行一次；
  3. 「不要取消」→ 不执行；
  4. 「取消要手续费吗」→ 不执行、清 pending；
  5. pending 期间给新订单号 → 切换；
  6. 非本人订单 → 统一文案。

**验收**：设计文档第 9 节全部通过。

### T8 文档与债务登记

**改动**
- 更新 `CLAUDE.md` 中「cancelOrder 写操作（Function Calling）」的旧描述；
- 未实现项打标记：
  - `// TODO: 多实例部署时把本地会话锁换成 Redis 分布式锁`
  - `// TODO: 内容安全 / Prompt 注入检测 / PII 脱敏`
  - `// TODO: 订单确认卡片（SSE order 事件）`
- 更新设计文档状态为「已实施」。

**验收**：`grep -rn "TODO\|FIXME"` 能捞出上述标记；CLAUDE.md 与代码一致。

## 工作量与风险

| 任务 | 预计改动文件数 | 风险 |
|---|---|---|
| T1 | 1 新增 + 1 测试 | 低 |
| T2 | 2 + 1 测试 | 中（并发条件更新） |
| T3 | 1 新增 + 1 改 + 1 测试 | 低 |
| T4 | 1 | 中（路由顺序） |
| T5 | 1 + 测试调整 | 中（行为收敛） |
| T6 | 1 + 新增限流 | 低 |
| T7/T8 | 文档与验证 | 低 |

**总风险点**：T4 的路由顺序与 T5 的职责收敛必须一起验证，否则可能出现「确认消息仍走 agent」或「请求取消被直接执行」。

## 实施记录（2026-10-18）

| 任务 | 状态 | 说明 |
|---|---|---|
| T1 | ✅ | `CancelPendingService` + `CancelPendingServiceTest`（7 个用例，覆盖反悔/提问/确认） |
| T2 | ✅ | `myOrdersByUserIdAndTime`；`cancelOrderByUserId` 改条件更新；`OrderServiceImplTest` 通过 |
| T3 | ✅ | `OrderQueryTool` 支持 `relativeDay` + 返回下单时间；`RequestCancelTool` + 测试 |
| T4 | ✅ | pending 前置判定 + `ORDER_CANCEL` 进入 agent loop + `OrderCandidateStore` 跨轮指代 |
| T5 | ✅ | `CancelOrderWorkflow` 收敛为 `execute`，`tryHandle` 不再消费消息 |
| T6 | ✅ | 消息长度 2000、服务端生成会话 ID、`ChatRateLimiter`（默认 20 条/分钟） |
| T7 | ✅（相关） | 订单链路相关测试全绿；全量测试中的 Controller 失败为既有问题（`shop-rec` 的 `InternalTokenFilter` 缺 `RecProperties`），与本次改动无关 |
| T8 | ✅ | `CLAUDE.md`（本地，已 gitignore）已更新；TODO 已登记 |

### 验证命令

```bash
mvn -pl shop-server -am clean test \
  -Dtest=CancelPendingServiceTest,RequestCancelToolTest,CancelOrderWorkflowTest,ChatServiceClearConversationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：全部通过（EXIT=0）。

> ⚠️ 必须带 `clean`：IDE 的增量编译会把带 `Unresolved compilation problem` 的 class 写进 `target/`，
> 导致测试运行期报 `Flexible Constructor Bodies` 之类的假错误（本项目此前也踩过 IDE 污染 target 的坑）。

### 未完成（已登记 TODO）

- 多实例部署改为 Redis 分布式锁；
- 内容安全 / Prompt 注入检测 / PII 脱敏；
- 订单确认卡片（SSE `order` 事件）；
- 全量回归需在干净环境下 `mvn clean test` 复跑并对照既有失败基线。
