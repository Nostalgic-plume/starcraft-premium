# 客服取消订单两阶段改造 — 设计文档

> 状态：已实施（2026-10-18）
> 日期：2026-10-18
> 范围：`com.shop.rag.chat` 订单取消链路 + `OrderService` 取消执行

## 1. 背景

当前取消订单链路（`CancelOrderWorkflow`）存在三个必须修的问题：

1. **意图 ≠ 确认**：pending 存在时，只要消息含「取消」就执行取消。用户说「不要取消」「取消要手续费吗」会被当成确认，直接取消订单。
2. **新订单号被忽略**：pending 是订单 A，用户说「取消订单 B」，代码仍取消 A。
3. **执行非幂等**：`cancelOrderByUserId` 先查状态、再回滚库存、最后改状态，并发时可能重复回滚库存。

同时，当前「无订单号」的处理只列最近 5 条待支付订单，无法按用户描述（如「昨天的订单」）定位，体验缺失。

## 2. 目标架构

```
请求进入
  ↓
① 接入层守卫：登录 / 消息长度 / 会话 ID 合法性
  ↓
② 会话层守卫：会话归属 / 会话状态 / 同会话串行
  ↓
③ pending 前置判定（优先于意图识别）
   ├─ 有 pending
   │   ├─ 确认 → 代码执行取消 → 审计 → 清 pending
   │   ├─ 反悔 → 清 pending → 回复不取消
   │   └─ 其他 → 清 pending → 继续 ④
   └─ 无 pending → 继续 ④
  ↓
④ 意图识别
   └─ ORDER_CANCEL → agent loop（读工具 + requestCancelOrder 请求工具）
         ├─ 用户已给订单号 → 直接请求取消
         ├─ 有候选上下文 → 从候选选择
         └─ 无 → getMyOrders 查询（时间/状态过滤）→ 选择
               ↓
         requestCancelOrder（代码校验 + 写 pending + 返回确认话术）
```

**分层原则**：

- workflow（代码）管：意图路由、工具白名单、pending 状态机、确认判定、执行、幂等、审计；
- agent（LLM）管：理解用户描述、决定是否查单、从候选选择、组织话术；
- 工具内部（代码）管：订单号解析、归属校验、状态校验、pending 写入。

## 3. 硬约束

| # | 约束 | 说明 |
|---|---|---|
| C1 | 写操作不经 LLM | `requestCancelOrder` 只写 pending，不调用 `cancelOrderByUserId` |
| C2 | userId 服务端注入 | 工具构造器传入 userId，绝不接收模型生成的 userId 参数 |
| C3 | pending 优先于意图识别 | 有 pending 时先三态判定，确认/反悔短路，其他清 pending 后继续 |
| C4 | 三态判定确定性 | 反悔词优先于确认词；不依赖 LLM |
| C5 | 确认必须明确 | 只有「确认取消 / 确定取消 / 是的取消」等明确表达才执行；「取消要手续费吗」不执行 |
| C6 | 执行幂等 | `UPDATE ... WHERE status = 待支付`，影响行数为 0 不重复回滚库存 |
| C7 | 失败文案统一 | 「该订单不存在或不属于您」，不区分不存在/非本人，防枚举 |
| C8 | 不记录 PII | 审计只记 userId / conversationId / orderId / 结果，不记消息原文 |

## 4. pending 状态机

存储：Redis `chat:pending:cancel:{conversationId}` → JSON `{orderId, userId}`，TTL 5 分钟。

| 当前状态 | 用户消息判定 | 动作 | 下一状态 |
|---|---|---|---|
| 无 pending | 取消意图 | agent loop 查单 → requestCancel | 有 pending |
| 有 pending | 确认 | 条件更新执行取消 | 无 pending |
| 有 pending | 反悔 | 清 pending，回复「好的，不取消」 | 无 pending |
| 有 pending | 其他（含新订单号） | 清 pending，继续走意图识别 | 无 pending |
| 有 pending | TTL 到期 | Redis 自动删除 | 无 pending |

### 三态判定规则

**反悔优先**（命中即反悔）：

```
不要取消 / 不想取消 / 不打算取消 / 别取消 / 不取消 / 不退了 / 先不 / 暂不 / 再想想 / 算了 / 不用了
```

**确认**（命中才执行）：

```
确认取消 / 确定取消 / 是的，取消 / 对，取消 / 同意取消 / 确认 / 确定
```

确认词要求：整句长度 ≤ 10 且命中上述明确表达；「确认」单独出现时才视为确认，长句（>10 字）一律归「其他」。

**其他**：以上都不命中。

## 5. 工具契约

### 5.1 getMyOrders（读）

```java
String getMyOrders(
    @P(name = "status") String status,          // 可选：待付款/待发货/待收货/已完成/已取消
    @P(name = "relativeDay") String relativeDay // 可选：TODAY/YESTERDAY/LAST_3_DAYS/LAST_7_DAYS
)
```

- 返回文本包含：订单号、状态、下单时间、商品摘要、金额；
- 时间范围由代码换算成 `create_time` 区间，LLM 不参与日期计算；
- 查询结果最多 10 条，超出提示「仅展示最近 10 条」。

### 5.2 getOrderStatus（读）

保持现状：订单号 → 详情，归属校验在 `OrderService`。

### 5.3 requestCancelOrder（半写）

```java
String requestCancelOrder(@P(name = "orderId") String orderId)
```

执行步骤：

1. `orderId` 字符串 → Long，非法直接返回「订单编号无效」；
2. `orderService.getOrderDetailByUserId(orderId, userId)` 校验归属；
3. 校验状态 == 待支付，否则返回「当前状态不允许取消」；
4. 写 pending（Redis，TTL 5 分钟）；
5. 返回确认话术：「您要取消订单 xxx（¥金额）对吗？回复「确认取消」完成操作。」

**禁止**：调用 `cancelOrderByUserId`。

### 5.4 工具注册策略

| 意图 | 工具集 |
|---|---|
| ORDER_CANCEL | `OrderQueryTool` + `RequestCancelTool` |
| ORDER_QUERY | `OrderQueryTool` |
| 其他 | 无 |

## 6. 错误返回

| 场景 | 返回文案 |
|---|---|
| 订单号格式非法 | 订单编号无效，请确认后重试 |
| 订单不存在 / 非本人 | 该订单不存在或不属于您 |
| 状态非待支付 | 订单 xxx 当前状态【已支付】，仅待支付订单可取消 |
| 无符合条件订单 | 您没有符合条件的待支付订单 |
| 服务异常 | 订单服务暂时不可用，请稍后再试 |
| pending 确认时订单已变化 | 该订单当前不可取消，请重新查询 |

## 7. 执行幂等

`OrderServiceImpl.cancelOrderByUserId` 改造：

```java
// 1. 查订单项（回滚库存需要）
List<OrderItemDO> items = ...;

// 2. 条件更新：只有待支付且属于本人才更新
int rows = orderMapper.update(null,
    new LambdaUpdateWrapper<OrderDO>()
        .eq(OrderDO::getId, orderId)
        .eq(OrderDO::getUserId, userId)
        .eq(OrderDO::getStatus, STATUS_PENDING_PAY)
        .set(OrderDO::getStatus, STATUS_CANCELLED));

if (rows == 0) {
    throw new BusinessException("当前订单状态不允许取消");
}

// 3. 更新成功后再回滚库存
for (item : items) { restoreStock(...); }
```

## 8. 坑与边界

| 坑 | 处理 |
|---|---|
| LLM 重复调用 requestCancelOrder | pending 覆盖写，幂等 |
| LLM 传超范围 Long | 参数用 String，工具内 parse，捕获异常 |
| 用户 pending 期间给新订单号 | 三态判定为「其他」→ 清 pending → 意图识别 → 重新请求 |
| 模型熔断 | 确认轮不依赖模型；提案轮提示稍后再试 |
| pending 期间订单已支付 | 执行前条件更新，状态不符直接失败 |
| 多实例部署 | 本地会话锁失效 → 后续用 Redis 分布式锁（本次标记 TODO） |
| 会话 ID 默认 "default" | 改为必传或服务端生成，避免多用户串会话 |
| 消息超长 | 接入层限制 2000 字符 |

## 9. 验收标准

1. 单测覆盖：
   - 三态判定：确认 / 反悔 / 其他（含「不要取消」「取消要手续费吗」）；
   - `requestCancelOrder` 只写 pending、不执行取消；
   - 条件更新：并发取消只成功一次，库存只回滚一次；
   - 时间过滤：昨天 / 最近三天查询正确；
   - 越权：非本人订单统一文案。
2. 端到端：
   - 「取消昨天的订单」→ 查出订单 → 确认 → 取消成功、库存回滚；
   - 「确认取消」→ 执行；
   - 「不要取消」→ 不执行、清 pending；
   - 「取消要手续费吗」→ 不执行、清 pending。
3. 回归：订单查询、商品咨询、闲聊、模型降级链路不受影响。
