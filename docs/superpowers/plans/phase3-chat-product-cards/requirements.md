# Phase 3 客服对话商品卡片——需求文档

**背景**：AI 客服（ChatService）RAG 检索已返回 TOP-3 商品（`RagHitVO`），但仅拼成纯文本塞进 SystemPrompt。本需求将 3 个商品以**结构化卡片**展示在对话流中，用户点击卡片跳转商品详情页，打通"客服推荐 → 详情 → 下单"转化链路。

**设计参考**（业界通用模式）：
- **结构化输出优于 AI 自由文本**：后端用固定 JSON schema 推商品数据，前端预测性渲染（Google Business Messages RichCard / Carousel 模式）
- **推荐 2-3 个商品**：避免选择瘫痪（choice paralysis），正好匹配项目 topK=3
- **卡片 = 图 + 名称 + 价格 + 主 CTA（查看商品）**，配简短解释文字
- **fallback 兜底**：无命中/异常时不渲染空白卡片

---

## ① 硬约束清单

> 编号 C 开头。标注【文档明确规定】= 用户/本会话明确要求；【推导得出】= 从代码与业界模式推断；❓不确定 = 需查证。

- **C1**【文档明确规定】SSE 新增 `products` 事件，**在第一个 token 事件之前**推送 3 个商品的结构化数组。
- **C2**【文档明确规定】卡片展示 **3 个商品**（与 RAG `topK=3` 一致）；**仅 hits 非空时推送**，无命中不推送、前端不留空白卡片。
- **C3**【文档明确规定】**不展示相关性分数（score）**（用户明确要求去掉）。
- **C4**【文档明确规定】卡片信息字段：`productId` / `name` / `image` / `minPrice` / `maxPrice` / `excerpt`（摘要 ≤ 40 字）。**不含 detail**。
- **C5**【文档明确规定】商品数据结构由**后端生成**（序列化 `RagHitVO`），**不依赖 LLM 决定格式**（业界 best practice：AI 不负责排版，schema 固定，前端预测性渲染）。
- **C6**【文档明确规定】卡片样式对齐项目设计系统：品牌深墨蓝 `#1a1a2e`、强调珊瑚红 `#e94560`、16px 圆角、暖灰背景 `#f5f3ef`、品牌阴影。
- **C7**【文档明确规定】不改变现有 SSE `token` / `end` / `error` 事件语义，不改变对话生成逻辑（沿用 C9 既有约束）。
- **C8**【推导得出】`minPrice == maxPrice`（区间相等）时只显示单值；`minPrice`/`maxPrice` 为 null（无 SKU 价格）时不显示价格或显示"询价"，不渲染损坏布局。
- **C9**【推导得出】`image` 缺失或加载失败时显示占位图（商品主图可能为 null）。
- **C10**【推导得出】浮窗宽度有限，**3 卡竖排**（非横向 carousel——业界 carousel 适合宽屏，浮窗窄屏竖排更稳）。
- **C11**【推导得出】前端 SSE 解析需**兼容旧事件**（token/end/error），新增 products 事件不破坏既有消息流。
- **C12** ❓不确定：用户端商品详情页**路由路径**（`/product/:id` 或 `/detail/:id`）——需在 T4 前确认（`frontend/src/router/index.ts`）。
- **C13** ❓不确定：卡片在历史会话回看时是否复用 `chat_message.ragContext`（已存商品 JSON）——作为可选增强，首版不强求。
- **C14**【文档明确规定】卡片点击用**新标签打开**商品详情页（`window.open('/product/'+id, '_blank')`），**不离开当前对话**、不影响 AI 客服继续使用（用户已确认）。

---

## ② 接口入参 / 出参 / 失败返回

### 2.1 `POST /api/chat/ask`（SSE）——新增 `products` 事件

- **入参**：不变（`message` / `conversationId`，需登录）
- **新增出参事件**（在首个 token 前推送）：
  ```
  event: message
  data: {"type":"products","content":[
    {"productId":"1","name":"华为 Mate 60 Pro 12GB+512GB 雅丹黑 手机","image":"https://...","minPrice":5999,"maxPrice":7999,"excerpt":"搭载麒麟芯片与卫星通话功能..."},
    {"productId":"12","name":"小米14 Ultra","image":"https://...","minPrice":4999,"maxPrice":6999,"excerpt":"..."},
    ...
  ]}
  ```
  | 字段 | 类型 | 必填 | 约束 |
  |------|------|------|------|
  | `productId` | String(Long 序列化) | 是 | 跳转详情页的 key |
  | `name` | String | 是 | 商品名 |
  | `image` | String | 否 | null 时前端用占位图（C9） |
  | `minPrice` / `maxPrice` | Number | 否 | null 时不显示价或"询价"（C8） |
  | `excerpt` | String | 否 | ≤40 字摘要 |

- **失败返回**：
  | 场景 | 行为 |
  |------|------|
  | RAG 无命中（hits 空） | **不推送** products 事件，只走正常 token 流（C2） |
  | 未登录 | 现有 401 JSON（不改） |
  | 服务异常 | 现有 SSE error 事件（不改） |

### 2.2 前端跳转（卡片点击）

- **动作**：点击卡片整卡 → `router.push(<详情页路由>)`，携带 `productId`
- **约束**：详情页路由路径 C12 确认；跳转不阻塞对话（浮窗可关闭）

---

## ③ 风险翻车点清单

- **R1**：LLM 文本推荐的商品与卡片展示的商品**不一致**（LLM 可能编造商品名）。触发条件：LLM 未严格引用 hits 里的商品。→ 缓解：卡片以 RAG `hits` 为准；SystemPrompt 提示 LLM"引用下方商品卡片中的商品"，已有"当有商品信息时"人设约束。
- **R2**：商品图缺失/加载失败 → 卡片破图。触发条件：`image` 为 null 或 URL 失效。→ 缓解：占位图（C9）。
- **R3**：价格字段 null → 卡片价格区布局塌陷。触发条件：无 SKU 或 metadata 缺价格。→ 缓解：null 时不显示价/显示"询价"（C8）。
- **R4**：跳转路由路径写错 → 卡片点击 404。触发条件：详情页路由未确认就写死。→ 缓解：T4 前查 `router/index.ts`（C12）。
- **R5**：浮窗内跳转导致当前页上下文丢失。触发条件：用户正在浏览商品页点客服卡片。→ 缓解：**已定新标签打开**（C14），当前页与对话保留，客服可继续使用。
- **R6**：products 事件与 token 事件顺序颠倒 → 前端先渲染 token 后补卡片，视觉跳动。触发条件：后端事件发送顺序错误。→ 缓解：products 必须在 token 前（C1），后端单线程顺序发送。
- **R7**：历史会话回看旧消息（升级前无 products 事件）→ 前端解析缺字段。触发条件：查看升级前的历史对话。→ 缓解：前端 products 事件解析容错（缺字段不渲染该卡）。
- **R8**：excerpt 过长撑爆卡片。触发条件：excerpt 未截断。→ 缓解：后端截断 ≤40 字（C4）。
- **R9**：无命中时前端仍渲染卡片占位 → 空白卡。触发条件：前端对 products 事件无条件渲染。→ 缓解：仅收到 products 事件才渲染，无事件即无卡（C2）。

---

## 参考来源

- [Google Business Messages Rich Card / Carousel 设计样例](https://developers.google.cn/business-communications/business-messages/guides/how-to/design/samples/generic-sales)
- [Frontend Rich Cards and Streaming Responses](https://nitinksingh.com/posts/frontend-rich-cards-and-streaming-responses/)
- [Rich Chat Responses: Cards, Buttons, Carousels](https://callsphere.ai/blog/rich-chat-responses-cards-buttons-carousels-interactive-elements)
- [WooCommerce Product Recommendation Chatbot 商品卡片实现](https://nexuwp.com/woocommerce-product-recommendation-chatbothow-to-show-product-cards-inside-chat/)