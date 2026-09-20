# 订单数据注入 + SSE 心跳 计划书

## 任务拆解与验收方式

| # | 任务 | 验收方式 |
|---|------|----------|
| 1 | 订单域注入确定性订单上下文（`buildOrderContext`） | 日志出现「订单上下文注入: N 笔订单」，回答引用真实订单号/状态 |
| 2 | 订单域跳过商品检索 | 订单意图 `products` 帧 = 0、`[RAG指标]` 行消失 |
| 3 | SSE 心跳（`startHeartbeat` + `rag.chat.heartbeat-seconds`） | 临时把间隔调成 1s，流中出现 `ping` 帧 |
| 4 | 回归：闲聊/商品 | 闲聊不推卡片、商品仍推卡片 |
| 5 | 编译与离线构建 | `mvn -o -DskipTests compile` BUILD SUCCESS |
| 6 | 恢复默认配置复验 | 默认 15s 下功能正常、快回答不产生 ping 帧 |

## 验收记录（2026-09-20 19:45—19:55 实测）

| # | 用例 | 实测结果 |
|---|------|----------|
| 1 | 「我订单的进度怎么样」（走兜底链路的订单句） | 回答列出真实数据：「一共 9 笔订单…倍思 15W 无线充电器——已发货…香奈儿炫亮魅力口红 ×2 已取消」；日志 `[客服意图] 订单上下文注入: 9 笔订单` |
| 2 | 「订单上有个人信息，好笨」 | `route=order_query`、`products` 帧 = 0、无 `[RAG指标]` 行 |
| 3 | 心跳（临时 `-Drag.chat.heartbeat-seconds=1`） | 订单轮 `ping=2`；商品轮 `ping=1`；闲聊轮 `ping=1`（证明心跳真实下发、且被前端忽略） |
| 4 | 「我想买个手机」 | `route=product`、`products=1`（不回归） |
| 5 | 「能不能陪我聊天」 | `route=chitchat`、`products=0`（不回归） |
| 6 | 编译 | BUILD SUCCESS |
| 7 | 默认配置（15s）复验「我的订单」 | `route=order_query`、`token=1`、`ping=0`（快回答不产生多余帧） |

### 备注

- 验证期间发现整套中间件容器 2 小时前已 Exited（Docker 整体停过），已 `docker compose up -d` 恢复，
  与本次代码改动无关。
- 心跳间隔默认 15s，配置项在 `application.yml` 的 `rag.chat.heartbeat-seconds`。