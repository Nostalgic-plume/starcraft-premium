# Phase 4 客服取消订单工具——任务规划

> 依据 `requirements.md` 拆分。按依赖拓扑排序。改动小、复用多，共 3 步。
>
> **状态：待开始**

- [ ] T1: `OrderService` 新增 `cancelOrderByUserId(Long orderId, Long userId)`（依赖：无）
    动作：`OrderService` 接口 + `OrderServiceImpl` 新增方法，逻辑与现有 `cancelOrder` 完全一致，仅 userId 由入参显式传入（替换 `StpUtil.getLoginIdAsLong()`）。
    验收：① 内部零 StpUtil；② 现有 `cancelOrder` 及 Controller 路径零改动；③ `@Transactional` 保留（回滚一致性，R3）；④ `mvn compile` 通过。

- [ ] T2: `OrderTool` 新增 `cancelOrder(Long orderId)` 工具（依赖：T1）
    动作：新增 `@Tool` 方法（description 写清"仅用户明确要求取消时调用；只能取消自己待支付订单"），调 `cancelOrderByUserId`；catch `BusinessException` → 按消息返回人话（"该订单不存在或不属于您" / "当前订单状态不允许取消"）；其他异常 → 降级文案；userId 来自构造器（C1 安全边界）。
    验收：① 编译通过；② AiServices 自动发现新工具（ChatService 不改，启动后 tools 列表含 cancelOrder）。

- [ ] T3: 集成回归验证（依赖：T2）
    动作：启动应用 + 手动/接口验证 B1~B7。
    验收：① B1 取消本人待支付订单成功且 DB 状态变已取消、库存回滚；② B2/B3 边界文案正确；③ B4 未登录 401；④ B5 商品咨询不触发取消工具；⑤ B6 `mvn clean package` + 测试无新增失败；⑥ B7 只读查询回归。
    **注**：取消订单是真实 DB 写操作，测试用一条**待支付测试订单**（避免影响真实数据），验证后状态已变即可。

---

## 验证结果记录（2026-08-28）

| 验收项 | 结果 |
|--------|------|
| B1 取消本人待支付订单 | ✅ 下单造待支付订单 2093236126066569217 → 客服"取消订单 xxx" → cancelOrder 工具执行 1 次（无循环）→ 订单状态变**已取消** |
| B2 取消不存在/超范围订单 | ✅ 超范围订单号 9999999999999999999（>Long.MAX_VALUE）→ parseOrderId 捕获 → "订单编号无效"（曾因 AiServices DefaultToolExecutor 参数校验在工具方法前抛异常导致 onError，改 String 参数修复） |
| B3 取消已取消订单（幂等） | ✅ "当前订单状态不允许取消" → 友好话术 |
| B4 未登录 | ✅ `{"code":401,"msg":"未登录，请先登录"}` |
| B5 商品咨询 | ✅ 走 RAG，不触发取消工具 |
| B6 编译 + 测试 | ✅ 177 测试仅预存 7 失败（ProductServiceImplTest），零新增 |
| B7 只读回归 | ✅ getOrderStatus 正常雪花 ID 工作 |

### 本次新增踩坑

**工具 Long 参数超范围 → AiServices 参数校验在工具方法前抛异常**：`DefaultToolExecutor.getBoundedLongValue` 对超出 `Long.MAX_VALUE` 的入参（如 9999999999999999999）在**工具方法执行前**抛 `IllegalArgumentException`（不走工具内 catch），导致整轮对话 onError"AI 回复生成失败"。**修复**：`getOrderStatus`/`cancelOrder` 的 orderId 参数改 **String**，工具内 `parseOrderId` 转 Long 并捕获非法值 → 返回友好文案。

### 附带改进

**system prompt 加工具调用规则**：DeepSeek 曾"声称要取消但未调工具"（幻觉），引导"订单操作必须先调工具再回答、不提前承诺、不编造订单信息"。
