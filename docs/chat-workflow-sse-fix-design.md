# 订单工作流「回复不显示 / 用户消息丢失」修复设计稿

> 触发背景（2026-09-20 实测）：用户问「我的订单」，界面一直转圈、看不到回复；数据库里那条回复其实是存在的。
> 同时发现同一会话 user 21 条 / assistant 22 条，少的正是用户自己发的「我的订单」。

## 一、根因（两处独立缺陷，叠在一起才表现为「没有回复」）

1. **SSE 事件契约不一致（主因）**
   - 后端 `WorkflowContext.replyAndComplete()` 只发 `end` 帧，**从不发 `token` 帧**；
   - 前端 `FloatingChat.vue` 的 `onToken` 才累积正文，`onEnd` 把累积结果写入气泡，
     而 `chat.ts` 对 `end` 帧的处理是「end 已在 done 时触发」→ **显式忽略其 content**。
   - 结果：订单查询/取消（走受控 workflow 的两条链路）的回复永远渲染不出来，气泡一直转圈。
2. **订单意图下用户消息不落库**
   - `ChatService.ask` 的路由分支在 workflow 命中后直接 `return`，而用户消息落库在第 7 步（更靠后）；
   - `WorkflowContext` 只落库助手回复 → 该轮用户消息永久丢失（刷新后用户发言消失、会话记忆少一轮、
     以订单开头的会话标题也会算错）。

## 二、修复点

| 文件 | 改动 |
|------|------|
| `WorkflowContext.replyAndComplete` | 先发 `token` 帧（整段正文）再发 `end` 帧，满足前端既有契约（前端不改也能显示） |
| `WorkflowContext.saveUserMessage`（新增私有方法） | 在落库助手回复前先落库本轮用户消息 |
| `ChatService.ask` | 订单域（`orderIntent`）即便回落到通用链路，也不推 `products` 商品卡片 |

## 三、硬约束

- **前端不改**：以「后端补齐 token 帧」修，避免为了一个显示问题重打包三端静态资源。
- 只改 workflow 的统一出口 `replyAndComplete`：两条 workflow 共 8 处调用点自动受益，不逐处改。
- 用户消息落库放在 workflow 出口而非 `ChatService` 路由分支：前者保证「先 user 后 assistant」顺序正确，
  且不会与第 7 步的落库重复（未命中 workflow 的路径根本不会走到这里）。
- 订单域不推卡片只影响 `products` 事件，商品上下文仍照旧传给模型。

## 四、Nginx SSE 通道修复（2026-09-20 17:58 已完成）

`docker/nginx/nginx.conf` 与 `deploy/conf/nginx.conf` 各新增一个精确匹配 location（`location = /api/chat/ask`；
精确匹配优先级高于 `/api/` 前缀，原有块无需调整）：

- `proxy_buffering off` + `gzip off`：token 事件逐条下发，不再被 Nginx 攒着一起发
  （实测首字延迟 **0.308s → 0.026s**）；
- `proxy_read_timeout 300s` / `proxy_send_timeout 300s`：与后端 `SseEmitter(300s)` 对齐，避免模型侧 503
  重试挂起超过 60 秒时 Nginx 直接断流（原错误日志：`upstream timed out (110) while reading upstream`）。

验收：`docker exec shop-nginx nginx -t` 通过 → `nginx -s reload` → `nginx -T` 可见新块 → 端到端
`POST http://localhost/api/chat/ask` 返回 `HTTP 200`、29 个 data 帧、TTFB 0.026s / 总 1.95s。

## 五、遗留（未做）

- 订单域模糊句回落后，agent 是否调用只读订单工具仍属概率行为（实测同一句已能引用真实订单状态，
  更早一轮曾反问要订单号）——需在提示词层继续加固。
- 后端整轮耗时超过 60 秒时（DeepSeek 503 + 降级链）现在会"静默等待"到 300 秒上限；
  如需更好的体感，可在 SSE 层加心跳帧。