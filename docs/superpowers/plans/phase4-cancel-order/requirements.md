# Phase 4 客服取消订单工具（写操作）——需求文档

**背景**：Phase 3 客服工具链已支持**只读**查询订单（`getMyOrders`/`getOrderStatus`）。本需求新增**写操作**工具 `cancelOrder`，让客服从"能查"升级为"能做"——用户明确要求时真正执行取消订单（含库存回滚）。这是 Agent 能力跃迁的关键一步，面试可讲完整的安全边界/幂等/副作用故事。

**范围**：复用现有 `OrderServiceImpl.cancelOrder`（幂等 + 状态机 + 库存回滚逻辑已完备），仅新增**显式 userId 版本**（绕开 ThreadLocal 坑）+ `OrderTool` 新工具方法 + AiServices 自动注册（`tools(new OrderTool(...))` 自动发现新增 `@Tool`，ChatService 无需改）。

---

## ① 硬约束清单

- **C1**【文档明确规定】**写操作安全边界**：userId 显式传入（Controller 同步层 `StpUtil.getLoginIdAsLong()`），归属校验在 Service 层强制（`order.userId == userId`，非本人 → "该订单不存在或不属于您"），绝不信任 LLM 参数；工具 description 明确"仅当用户明确要求取消订单时调用"。
- **C2**【推导得出】现有 `OrderServiceImpl.cancelOrder(Long orderId)` 内部用 `StpUtil.getLoginIdAsLong()`（ThreadLocal），agent loop 异步线程拿不到 → **必须新增 `cancelOrderByUserId(Long orderId, Long userId)`**，复用现有校验/回滚逻辑（C3）。
- **C3**【文档明确规定】幂等 + 副作用复用现有逻辑：状态机校验（仅待支付可取消，`if status != PENDING_PAY throw`）+ 库存回滚（循环 order_item `skuMapper.restoreStock` + `stockCacheService.restoreStock` + 淘汰商品缓存）。
- **C4**【文档明确规定】工具异常兜底：`OrderTool.cancelOrder` catch `BusinessException` → 人话文案（如"该订单不存在或不属于您"、"当前订单状态不允许取消"）；其他异常 → "订单服务暂时不可用"；**不抛未捕获异常断 SSE**。
- **C5**【文档明确规定】回归约束：只读工具（查订单）行为不变；商品咨询仍走 RAG 不调取消工具；SSE `token/end/error` 事件语义不变。
- **C6**【文档明确规定】安全日志：工具日志只记录工具名/id，不记录参数/结果（PII）。
- **C7**【文档明确规定】V1 不做二次确认弹窗（用户明确表达取消意图即执行，由状态机兜底防误操作）；不做批量取消。

---

## ② 改动点

### 2.1 `OrderService` / `OrderServiceImpl`：新增显式 userId 取消方法

```java
// 显式 userId 版本（AI 客服工具用，内部复用现有 cancelOrder 校验 + 回滚逻辑，不依赖 StpUtil）
void cancelOrderByUserId(Long orderId, Long userId);
```

- 实现：逻辑与现有 `cancelOrder` 一致，仅把 `long userId = StpUtil.getLoginIdAsLong()` 替换为入参 `userId`。
- 现有 `cancelOrder` 与 Controller 路径零改动（C5）。

### 2.2 `OrderTool`：新增 `cancelOrder(Long orderId)` 工具

| 项 | 内容 |
|----|------|
| 触发 | 用户明确要求"取消订单 xxx / 把订单退了吧 / 不要这个订单了" |
| description | 写清"仅当用户明确要求取消订单时调用；只能取消当前登录用户自己的待支付订单" |
| 逻辑 | 调 `orderService.cancelOrderByUserId(orderId, userId)` → 成功返回"订单已取消"人话 |
| 失败返回 | `BusinessException`（非本人/状态不允许）→ 对应人话文案；其他异常 → 降级文案 |
| 安全 | userId 来自构造器（每请求绑定），归属校验在 Service 层强制（C1） |

> AiServices `tools(new OrderTool(userId, orderService))` 自动注册新增 `@Tool`，ChatService 不改。

### 2.3 `ChatService`

- **不改**（AiServices 自动发现新工具）。可选：system prompt 补一句"取消订单等操作用户明确要求才做"（增强路由，防误触发）。

---

## ③ 风险

- **R1**：LLM 误触发取消（用户说"不要了"但本意是咨询）→ description + system prompt 引导"仅明确要求取消时调用"；状态机兜底（非待支付不能取消）。
- **R2**：归属校验失败文案被 LLM 编造 → 固定文案（C1）。
- **R3**：取消成功但库存回滚失败 → 现有 `cancelOrder` 是 `@Transactional`，异常整体回滚（订单不取消，库存不扣），数据一致。
- **R4**：重复取消（已取消订单）→ 状态机抛"当前订单状态不允许取消" → 返回友好文案（C3 幂等）。

---

## ④ 验收标准

| # | 场景 | 预期 |
|---|------|------|
| B1 | 用户说"取消订单 xxx"（本人待支付订单） | 工具执行，订单状态变已取消，返回"已取消"人话 |
| B2 | 取消非本人/不存在订单 | "该订单不存在或不属于您" |
| B3 | 取消已支付/已取消订单 | 友好提示（状态不允许），订单不变 |
| B4 | 未登录 | 401 |
| B5 | 商品咨询 | 走 RAG，不调取消工具 |
| B6 | 编译 + 测试 | `mvn clean package` 通过；既有测试无新增失败 |
| B7 | 只读工具回归 | 查订单行为不变（A1~A5 不回归） |
