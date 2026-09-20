# 订单工作流「回复不显示 / 用户消息丢失」计划书

## 任务拆解

| # | 任务 | 验收方式 |
|---|------|----------|
| 1 | `replyAndComplete` 补发 `token` 帧 | 实测订单回复出现 `token` 帧且内容完整 |
| 2 | workflow 出口补存用户消息 | 实测该会话 `user`/`assistant` 条数相等 |
| 3 | 订单域不推商品卡片 | 实测订单意图 `products` 帧数 = 0 |
| 4 | 商品/闲聊链路不回归 | 商品仍推卡片、闲聊仍不推卡片 |
| 5 | 走 Nginx 的真实浏览器路径回归 | `POST http://localhost/api/chat/ask` 返回 `token` 帧 |
| 6 | 编译 | `mvn -o -DskipTests compile` BUILD SUCCESS |

## 验收记录（2026-09-20 17:14 重启后实测）

| # | 用例 | 实测结果 |
|---|------|----------|
| 1 | 「我的订单」 | `route=order_query`，**token 帧=1**、end 帧=1、products 帧=0，正文为「共 9 个订单：…」 |
| 2 | 「订单上有个人信息，好笨」 | `route=order_query`，token 帧=83，**products 帧=0**（修复前会推卡片） |
| 3 | 「我想买个手机」 | `route=product`，token 帧=119，products 帧=1（不回归） |
| 4 | 「能不能陪我聊天」 | `route=chitchat`，products 帧=0（不回归） |
| 5 | 数据库落库（会话 900000000000000003） | `user=4 / assistant=4`（修复前该场景为 21/22 不配对） |
| 6 | Nginx 路径（浏览器同链路） | `HTTP 200`、`route=order_query`、token 帧=1、正文含订单列表 |
| 7 | 编译 | BUILD SUCCESS |

### 备注

- 浏览器里那条「一直转圈」的旧气泡不会自动补上，刷新页面即可看到数据库中的订单回复。
- 历史会话中已丢失的那条用户消息属历史数据，不回溯补写。
## Nginx SSE 通道（2026-09-20 17:58）

| # | 任务 | 验收方式 |
|---|------|----------|
| 8 | 两份 `nginx.conf` 增加 `location = /api/chat/ask`（关缓冲、关压缩、超时 300s） | 配置校验 + reload + 生效确认 |
| 9 | 端到端复测（对比首字延迟） | 走 Nginx 请求 SSE，记录 TTFB |

实测：

- `docker exec shop-nginx nginx -t` → `syntax is ok` / `test is successful`
- `nginx -s reload` → 生效，`nginx -T` 可见 `location = /api/chat/ask`
- 端到端：`HTTP 200`、data 帧 29 个、**TTFB 0.308s → 0.026s**、总耗时 1.95s