# 客服意图识别：新增闲聊意图（chitchat）设计稿

> 触发背景：实测「能不能陪我聊天」被识别为 product 意图，走 RAG 检索出蓝牙耳机/跳绳/扫地机器人，
> 并在正式回答之前把 3 张不相关商品卡片推给用户（见验收记录）。

## 一、问题与根因

1. `Intent.PRODUCT` 的语义里显式包含「闲聊」（原注释：商品咨询/导购/闲聊——其余所有情况），
   因此所有非订单域消息都落到 PRODUCT，必然触发 RAG 检索与商品卡片推送。
2. 商品卡片推送没有相关性闸门：`ChatService.ask` 只要 `hits` 非空就推 `products` 事件。
   而 RRF 融合分（`w/(k+rank)`，k=60）量级固定在 0.01~0.03，**跨查询不可比**，
   所以「按分数过滤低相关命中」这条路走不通，只能靠意图路由解决。
3. 实测证据（2026-09-20）：`route=product`，闲聊整句作为检索词进 RAG，命中 3 条无关商品并推送卡片。

## 二、硬约束

- 订单域规则优先级最高：`ORDER_CANCEL` 仍走受控 workflow（写操作），闲聊判定不得抢占；
  规则顺序必须是「取消 → 查订单 → 闲聊 → 商品」。
- 闲聊判定不得误伤导购：消息里出现商品信号词（买/价/推荐/手机/冰箱…）时一律按 PRODUCT 走。
- 闲聊路径**不新增 LLM 调用**：上游 DeepSeek 实测会返回 503 `Service is too busy`，链路越短越稳。
- `Intent` 新增枚举值会经 SSE `route` 事件发给前端；前端未识别的 code 显示为空状态条，不阻塞主流程。

## 三、设计

- 新增 `Intent.CHITCHAT("chitchat", "闲聊")`（放在 PRODUCT 之前，PRODUCT 仍是兜底）。
- 规则层（`ChatIntentResolver`）：`CHITCHAT_WORDS` 命中 **且** `PRODUCT_SIGNAL_WORDS` 未命中 → CHITCHAT，
  0 延迟、可单测；不扩展 LLM 四分类（理由见硬约束第三条）。
- 路由层（`ChatService.ask`）：`intent == CHITCHAT` 时
  1. 跳过 Query 改写（省一次 LLM 调用）；
  2. 跳过 RAG 检索与分类回退（`hits` 为空，故 `products` 事件不会推送）；
  3. 换用 `CHITCHAT_SYSTEM_PROMPT`（保留人设，明确「不推荐商品、不追问购物需求」）。
- 另修：`SaTokenWarmUp`（`@PostConstruct`）预热三端 StpLogic，堵掉冷启动首个鉴权请求 500 的竞态。

## 四、入参 / 错误返回

- 对外契约不变：`message`（必填）、`conversationId`（数字字符串）。闲聊分支不产生新的错误码。
- RAG 空结果、上游模型 503 的既有降级链（qwen → 模板 → 提示不可用）保持不变。

## 五、已知遗留项（本次不改，仅记录）

1. `ChatController.ask` 的 `conversationId` 默认值是字符串 `"default"`，后端 `Long.valueOf` 会抛异常并返回
   「服务异常」；前端实际总传数字串，属防御性缺口，改动需同时回传新会话 ID，单独排期。
2. 辱骂/挑衅类消息（如「你被骗了」「傻逼」）不含闲聊词，仍走 PRODUCT 搜货架——本次不引入脏词表，
   避免误伤「这手机真垃圾」这类正常商品吐槽。
3. 商品卡片相关性闸门仍缺失（原因见根因第 2 条），后续若要加，需先给 RRF 分做查询内归一化。