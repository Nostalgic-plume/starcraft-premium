# 订单数据注入 + SSE 心跳 设计稿

> 背景（2026-09-20 实测）：用户问订单时客服反复答「我这边看不到你的订单，得你报订单号」。
> 排查后确认**不是提示词弱**，而是「agent 有工具」这件事只在上游正常时成立——降级链根本不带工具。

## 一、根因

1. `ChatService.qwenRagFallback` 只发 system + user 两条消息、**不挂任何工具**；DeepSeek 一旦 503 或熔断，
   订单问题就落到这条无工具链路，模型只能凭印象答「看不到订单」。
2. 订单域此前还会照常执行 RAG 商品检索，把与订单无关的商品塞进上下文。
3. 与之对照：`OrderQueryWorkflow` 的确定性路径（「我的订单」这类可解析表达）一直正常，
   问题只出现在「意图=订单域、但 workflow 解析不了而回落」的那些句子。

## 二、设计

- **数据前置（核心）**：订单域（ORDER_QUERY / ORDER_CANCEL）在 Java 侧用 `OrderService.myOrdersByUserId`
  确定性取回用户订单，拼成「===== 用户订单信息 =====」块注入 prompt。任何一层模型
  （DeepSeek / qwen / 模板兜底）拿到的都是同一份真实数据，不再依赖工具调用。
- **订单域不检索商品**：`skipRetrieval = chitchat || orderIntent`，避免订单问题被塞回商品上下文
  （商品卡片此前已禁止推送）。模板兜底文案同步改为中性表述，不再写死「相关商品」。
- **SSE 心跳**：每 `rag.chat.heartbeat-seconds`（默认 15s，≤0 关闭）下发一次 `{"type":"ping"}`，
  `onCompletion/onTimeout/onError` 时取消；用于防止代理在模型思考期判定连接空闲。

## 三、硬约束

- **只读**：只调 `OrderService` 的只读方法，订单归属由 service 按 userId 校验，不新增写路径。
- **不超范围收集 PII**：注入内容与 `OrderQueryWorkflow` 的确定性输出同构（订单号/状态/商品名/件数），
  不额外查询收货人、电话、地址。
- 心跳线程为守护线程单例；`sendEvent` 已捕获 `IllegalStateException/IOException`，心跳不会污染主流程。
- 前端零改动：心跳复用既有 SSE 通道，前端只识别 token/products/route/error/end，未知类型自动忽略。

## 四、验收结论

见 `chat-order-context-heartbeat-plan.md`（含实测数据）。

## 五、遗留

- 模型仍可能追加「要不要我列全部订单」这类追问，属措辞层面，数据正确性已保证。
- 心跳只解决「连接被判空闲」；上游 503 导致的整轮变慢仍要靠熔断与降级链继续优化。