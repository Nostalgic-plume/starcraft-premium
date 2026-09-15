# AI 服务 Python 化架构设计

> 状态:待评审
> 日期:2026-09-15
> 范围:把 `com.shop.rag` 整个 AI 域从 Java 单体抽离为独立 Python 服务,Java 通过 HTTP 调用

---

## 1. 背景与目标

### 现状

智能客服与 RAG 能力全部实现在 Java 侧,`src/main/java/com/shop/rag/` 共 23 个类:

| 层次 | 类 |
|------|-----|
| 对外接口 | `ChatController`、`RagSearchController`、`RagTraceController` |
| 检索 | `RagSearchService`、`KeywordSearchService`、`RrfMerger`、`QueryRewriter`、`ProductIndexer`、`QdrantCollectionHelper` |
| 对话编排 | `ChatService`、`ChatIntentResolver`、`Intent`、`IntentWorkflow`、`CancelOrderWorkflow`、`OrderQueryWorkflow`、`WorkflowContext` |
| 工具 | `OrderQueryTool`(原 `OrderTool`) |
| 记忆 | `MySqlChatMemoryStore`、`ChatHistoryService` |
| 韧性 | `CircuitBreaker`、`ToolCallLogger` |
| 配置 | `RagConfig`、`RagProperties`、`AiAssistant`、`RagHitVO` |

### 动机

Java 侧 LangChain4j 生态能力受限 —— 横切关注点(模型调用拦截、工具调用拦截、上下文压缩、人工中断)缺少开箱机制,需手写切面。改用 Python 生态以获得成熟的 middleware hook 体系。

### 目标

1. AI 域抽离为独立可部署的 Python 服务,Java 通过 HTTP 调用
2. 用 LangGraph 的 middleware hook 体系补齐横切切面
3. 前端零改动(SSE 事件契约保持原样)
4. AI 侧数据所有权完全移交 Python

### 非目标

- 不改动前端交互契约与视觉
- 不做双跑灰度(个人项目,收益不抵复杂度)
- 不做 AI 侧以外的服务拆分

---

## 2. 总体架构

```
┌─ Java :8080 (Spring Boot) ──────────────────────┐
│ ChatController /api/chat/*                      │
│   ① Sa-Token 鉴权 → userId                      │  ← 身份只在 Java 解析
│   ② SSE 透传(消费 Python 流 → 推前端)          │
│                                                  │
│ InternalOrderController /internal/order/*       │  ← 仅内网,订单唯一入口
│ 商品变更事件 → 异步通知 Python 更新索引          │
└──────────────────────────────────────────────────┘
              ↓ HTTP / SSE      ↑
┌─ Python :8000 (FastAPI) ────────────────────────┐
│ POST /chat/ask → StateGraph                     │
│   route 节点(规则优先 + LLM 兜底)              │
│     ├→ order_cancel 子图【确定性铁轨】          │
│     ├→ order_query  子图【agent 车厢】          │
│     └→ product_qa   子图【agent 车厢】          │
│ checkpointer: Redis                             │
│ BGE-m3 (本地,1024 维) + Qdrant + MySQL         │
└──────────────────────────────────────────────────┘
```

### 职责边界

| 关注点 | Java | Python |
|--------|------|--------|
| 用户身份解析 | ✅ 唯一来源 | ❌ 绝不自行解析 |
| SSE 对前端 | ✅ 透传 | 产出流 |
| 订单读写 | ✅ 唯一入口 | 经内部 API |
| 会话/消息/反馈 | ❌ 不再访问 | ✅ 独占 |
| 商品读 | ✅ 业务接口 | ✅ 只读(建索引+检索) |
| 向量检索 | ❌ | ✅ |
| 意图/编排/LLM | ❌ | ✅ |

**核心安全约束**:`userId` 由 Java 在同步层解析后传给 Python。Python 侧所有工具从 `contextvars` 取 userId,绝不从请求体或 LLM 参数中获取身份。

---

## 3. 数据所有权

| 数据 | 归属 | Python 访问方式 |
|------|------|----------------|
| `chat_conversation` | Python | 直连 MySQL 读写 |
| `chat_message` | Python | 直连 MySQL 读写 |
| `chat_feedback` | Python | 直连 MySQL 读写 |
| `rag_trace` | Python | 直连 MySQL 读写 |
| `product` / `sku` / `category` | Java | **只读**直连(建索引 + 检索) |
| `orders` / `order_item` | Java | **全部走内部 API**(读写均是) |

### 消息真相源

**`chat_message` 表为长期真相**,checkpointer 只负责图执行状态。

- 前端会话列表/历史消息 → 从 `chat_message` 读
- 重启后上下文恢复 → 从 `chat_message` 重建
- checkpointer(Redis)→ 仅图执行状态,可过期、丢失不影响历史

代价:消息存在两份(表 + checkpointer 状态)。接受此冗余以换取职责清晰与历史可靠性。

### 3.1 迁移完整性要求

`chat_*` 表的全部访问逻辑现位于 `ChatHistoryService` / `ChatFeedbackService`,**必须逐项在 Python 侧重新实现**,不可遗漏:

| 现有逻辑 | 迁移要求 |
|---------|---------|
| 会话获取或创建 | 不存在则创建,`status=0`、`total_msgs=0` |
| **会话所有权校验** | 非本人访问抛"无权访问该会话" —— 这是 `chat_*` 的安全边界,不可只在接口层做 |
| 会话标题生成 | 取首条 `role=user` 消息前 30 字,超出加省略号 |
| 用户会话列表 | 按 `status=0` 过滤,`update_time` 倒序,带 limit |
| 消息保存 | 插消息 + 会话 `total_msgs` 计数 +1(同一事务) |
| 消息列表 | 按 `create_time` 正序 |

反馈:`submitFeedback` 写入 `chat_feedback`,rating 语义 `1=有用 / 0=无用 / -1=错误`,提交前校验消息归属。

---

## 4. 详细设计

### 4.1 StateGraph 图结构

意图识别沿用现有策略(规则优先 + LLM 兜底),作为 `route` 节点实现:

```python
class ChatState(TypedDict):
    conversation_id: str
    user_id: int
    message: str
    intent: str
    reply: str
    products: list[dict]
    trace_id: str

graph = StateGraph(ChatState)
graph.add_node("route", route_node)                  # 规则优先 + LLM 兜底
graph.add_node("order_cancel", cancel_subgraph)      # 确定性,无 LLM 工具
graph.add_node("order_query", order_query_subgraph)  # agent 车厢
graph.add_node("product_qa", product_qa_subgraph)    # agent 车厢
graph.add_conditional_edges("route", lambda s: s["intent"])
```

**写操作安全设计延续**:`order_cancel` 子图不含任何 LLM 工具,全部为确定性节点,取消动作由代码直接调 Java 内部 API。与现有 `CancelOrderWorkflow` 的设计意图一致 —— LLM 永远拿不到写工具。

跨轮确认用 `HumanInTheLoopMiddleware` + checkpointer 实现,替代现有的 Redis pending 手写机制。

**语义映射的注意点(A 类迁移中最不确定的一块)**:现有实现是"下一轮用户消息触发确认"(Redis pending 标记 + 关键词判断确认/反悔/转话题);LangGraph 的 `interrupt` 是"图执行暂停,等待 `Command(resume=...)` 恢复"。语义相近但路径不同:

- `conversationId` 直接作为 checkpointer 的 `thread_id`
- 用户下一轮消息到达时,若该 thread 存在未完成的 interrupt,则作为 resume 输入而非新请求
- 反悔词 / 转话题的清 pending 逻辑仍需保留(可置于 `route` 节点前的判断)

实施时需单独验证跨轮确认的三种走向:确认 / 反悔 / 转话题。

### 4.1.1 评估集

现有 30 条人工标注 `(query → 期望商品ID)` 硬编码于 `RagEvaluationTest`,直接复制为 Python 测试数据即可,无外部依赖。

**阶段 ② 卡点重点观察用例**:评估集中 `"手鸡"`(错别字)一条标注为测 MySQL FULLTEXT ngram 容错,属关键词路能力。换用语义模型 BGE-m3 后该条表现可能变化,是检索质量退化的首要信号。

### 4.2 工具注册中心

现有 Java 侧工具注册是硬编码三元表达式,新增意图需改 `ChatService`。Python 侧改为注册表:

```python
from contextvars import ContextVar

# 请求入口设置,未设置时工具 fail-fast
current_user_id: ContextVar[int] = ContextVar("current_user_id")

@tool
async def query_my_orders(status: str | None = None) -> str:
    """查询当前登录用户自己的订单列表,可按状态过滤。"""
    user_id = require_user_id()   # 未设置则抛异常,绝不默认 None
    ...

TOOL_REGISTRY: dict[Intent, list[BaseTool]] = {
    Intent.ORDER_QUERY:  [query_my_orders, get_order_status],
    Intent.ORDER_CANCEL: [],   # 写操作不经 agent
    Intent.PRODUCT:      [],
}
```

**设计要点**

- 工具为**无状态模块级单例**,身份经 `contextvars` 注入 —— 消除 Java 侧"每请求 new 工具"的必要性
- `require_user_id()` 必须 fail-fast。默认 `None` 会导致查询条件缺失,等价于越权
- asyncio 下 contextvar 天然协程安全;若引入线程池需显式 `copy_context()`

### 4.3 Hook 体系

#### 内置 middleware(替代现有手写机制)

| middleware | 替代 | 配置要点 |
|---|---|---|
| `HumanInTheLoopMiddleware` | Redis pending 跨轮确认 | 与 checkpointer 配合 |
| `ModelFallbackMiddleware` | `CircuitBreaker` + qwen 降级 | 备用模型仅纯 RAG,不带工具 |
| `ToolCallLimitMiddleware` | `maxToolCallingRoundTrips(5)` | — |
| `SummarizationMiddleware` | `TokenWindowChatMemory(3000)` | 见 4.4,必须显式配 trigger |
| `PIIMiddleware` | `ToolCallLogger` 的脱敏约束 | 对话内容脱敏 |

#### 自定义 hook

| hook | 用途 | 替代 |
|------|------|------|
| `before_agent` | 注入 `trace_id` 到 contextvar | **新增**,解决日志串不成链 |
| `wrap_model_call` | 计时 + token 统计 | `[LLM指标]` |
| `wrap_tool_call` | 工具调用日志(工具名/耗时/成败,不记参数与结果) | `ToolCallLogger` |

检索埋点(`[RAG指标]`)不在 agent middleware 范围内,由检索节点的装饰器承担。

### 4.4 上下文压缩

```python
SummarizationMiddleware(
    model=summary_model,        # 便宜模型(如 qwen-turbo),不用主模型
    trigger=("tokens", 3000),   # 对齐现有 TokenWindowChatMemory 预算
    keep=("messages", 6),
)
```

**必须显式配置 `trigger`**:框架默认依赖 model profile 的 `max_input_tokens`,而 DeepSeek 在该生态中很可能没有 profile,会导致回退到 `("tokens", 170000)` 的保守默认值 —— 对 deepseek-chat 而言等同于**永不触发**,上下文窗口无限膨胀。

**两个层次不可混淆**:
- 持久化历史:全量存 `chat_message`,供前端展示
- LLM 上下文窗口:压缩后喂给模型

### 4.5 可观测性

不引入第三方平台(LangSmith / LangFuse),全自研。但必须补齐现有三个缺口:

| 缺口 | 方案 |
|------|------|
| traceId 不贯穿 | `before_agent` 生成 trace_id 写入 contextvar,贯穿 意图→检索→LLM→工具 |
| LLM 延迟/失败率无统计 | `wrap_model_call` 打点 |
| 指标无时序、无面板 | **日志输出 JSON 结构化** |

**日志改 JSON 结构化**是"现在不引平台"与"将来能接平台"之间的最优解 —— 当前零成本,后续接 Grafana/Loki 或任何观测工具无需重写解析规则。

保留不动:`rag_trace` 表(用户行为隐式反馈,任何 LLM 观测平台都看不到此视角)、离线评估集。

### 4.6 工具错误边界

**三层分离,不可混用**(框架不会替你处理执行层):

| 层 | 错误类型 | 处理方式 |
|---|---------|---------|
| Schema | LLM 参数不符 schema | `handle_tool_errors=True` → 自动回填 `ToolMessage`,LLM 自我修正 |
| 执行 | 工具内异常(Java API 超时/500) | **工具内自行 catch** → 返回错误文案让 LLM 决策 |
| 网络 | 瞬时故障 | `ToolRetryMiddleware` 重试 |

**关键认知**:`handle_tool_errors` 只捕获**调用错误**(参数不符 schema),对**执行错误**(工具内部抛出的异常)**会 re-raise** —— 设了它也挡不住工具内 HTTP 超时炸掉整个图。

**订单号参数用 String 接收 + 内部解析**,不用数值类型:
- Java 侧踩过的坑是 `Long` 溢出(`9999999999999999999 > Long.MAX_VALUE`)导致工具执行前抛异常
- Python 的 int 是任意精度,不会崩,但会退化为**静默失败**(查不到而非报错),更隐蔽
- 用 String 可显式校验并返回可读错误("订单号格式不正确"),且与 Java 侧行为可比对

**跨切面逻辑不写在工具里**:审计日志、鉴权走 `wrap_tool_call`,避免在每个工具内重复。

### 4.7 检索层设计

检索能力完整迁移供客服内部使用(`/api/product/rag-search` 公开接口下线,但检索实现保留)。检索路数与现有实现保持一致:

| 路 | 实现 | 说明 |
|---|------|------|
| 向量路 | BGE-m3 **Dense**(1024 维)→ Qdrant | **不使用** BGE-m3 的 Sparse / ColBERT 模式(社区报告在 8GB 显存下 Sparse 模式会 OOM) |
| 关键词路 | MySQL FULLTEXT | 沿用现有 `KeywordSearchService` 逻辑 |
| 融合 | RRF | 沿用现有 `RrfMerger` |

检索结果有两个消费方:作为 LLM 上下文,以及经 `products` 事件推送前端商品卡片。

**索引重建**:Qdrant collection 从 1536 维(`text-embedding-v1`)改为 1024 维(BGE-m3),必须**新建 collection 全量重建**,不可原地迁移。

**新集合名必须与 Java 侧区分**(如 `products_bge_m3_1024`)。Java 侧集合名 `products`(见 `RagProperties.java` 默认值与 `ProductIndexer.java` 的 REST 地址)指向的是 1536 维索引 —— 若 Python 沿用它,只有两种结局:写入时维度不匹配报错,或重建时抹掉 Java 的索引。后者会破坏阶段 ①② 的回滚前提(Java 侧保持可用)。

---

## 5. 接口契约

### 5.1 Java 新增内部 API(仅供 Python 调用)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/internal/order/list?userId=&status=` | 订单列表 |
| GET | `/internal/order/{id}?userId=` | 订单详情(归属校验在 Java) |
| POST | `/internal/order/{id}/cancel` | 取消订单(校验 + 库存回滚在 Java) |

**安全要求(强制)**:

1. Nginx **必须排除 `/internal/*`**,不得反代到公网 —— 否则任何人构造 `userId` 即可越权查单
2. 内部 API 校验内网密钥头,密钥经环境变量注入(如 `${INTERNAL_API_KEY}`),不得明文写入配置文件
3. 归属校验保持在 `OrderService` 单点强制,Python 侧不复刻

### 5.2 Python 对外 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat/ask` | SSE 流式问答 |
| POST | `/chat/clear` | 清除对话记忆 |
| GET | `/chat/conversations` | 会话列表 |
| GET | `/chat/conversations/{id}/messages` | 消息列表 |
| POST | `/chat/feedback` | 反馈提交 |
| POST | `/rag/trace` | 行为埋点 |
| POST | `/index/product/{id}` | 单商品索引更新(内部) |
| POST | `/index/rebuild` | 全量重建(内部) |

会话归属校验在 Python 侧实现(会话归属判定所需数据 Python 已独占)。

### 5.3 SSE 事件契约(不变)

事件名固定 `message`,内容为 JSON:

| type | 含义 |
|------|------|
| `route` | 意图状态条 |
| `token` | 流式文本片段 |
| `products` | 商品卡片数据 |
| `end` | 结束 |
| `error` | 错误 |

前端 `frontend/src/api/chat.ts` 与 `FloatingChat.vue` 无需改动。

### 5.4 商品索引同步

| 触发 | 方式 |
|------|------|
| 全量重建 | 手动调 `/index/rebuild`,或服务启动时(可配开关) |
| 单品更新 | Java 侧商品新增/修改/删除/上架状态变更时,异步通知 Python |

Java 侧商品变更处需新增异步通知调用,失败不影响主链路(记日志,由定时全量兜底)。

---

## 6. 迁移计划

迁移顺序按"可独立验证的先后"排列,非模块顺序:

| 阶段 | 内容 | 验证点 |
|------|------|--------|
| ① | Python 服务骨架 + `chat_*` 表映射 + 配置加载 | 服务可启动,表可读 |
| ② | 检索层:BGE-m3 + Qdrant 重建(1024 维) + 检索 + RRF | **【卡点】跑评估集建立新基线** |
| ③ | 对话编排:StateGraph + 子图 + middleware hook | 单元测试覆盖路由与工具 |
| ④ | Java 内部 API(订单)+ Python 调用打通 | 5 个端到端场景 |
| ⑤ | Java 改透传 + 删除旧代码 | 编译通过,旧测试清理 |
| ⑥ | 前端回归(零改动验证) | 交互无变化 |

**阶段 ② 设卡点**:换 BGE-m3 是检索质量唯一可能退化的地方,而现有评估集可直接复用。在此处暴露问题远优于阶段 ⑥。

**回滚策略**:阶段 ⑤ 之前 Java 旧实现保持可用。阶段 ⑤ 的删除在 git 上独立提交,回滚即 revert 该提交。不开双跑灰度。

**建议拆分为多个实施计划**:阶段 ①②(纯 Python 新建,不碰 Java)与阶段 ③④⑤⑥(涉及两端联调与删除)可各自独立成 plan,便于分次验证与回滚。

### 前端搜索框替代

删除公开语义搜索接口后,`frontend/src/views/Home.vue:469` 的 `productApi.ragSearch()` 调用改为现有普通关键词搜索(`/api/product/page?keyword=`)。

---

## 7. 删除清单

### Java 后端

| 项 | 处置 |
|----|------|
| `controller/RagSearchController.java` | **整个删除**(公开接口下线) |
| `controller/ChatController.java` | 改为透传(保留 Sa-Token 鉴权 + SSE 转发) |
| `controller/RagTraceController.java` | 删除(移交 Python) |
| `rag/ChatHistoryService.java` | **职责整体迁移**至 Python(见 §3.1),Java 侧删除 |
| `rag/ChatFeedbackService.java` | **职责整体迁移**至 Python(见 §3.1),Java 侧删除 |
| `rag/` 其余类 | 职责迁移至 Python,Java 侧删除 |
| `pom.xml` 中 langchain4j 相关依赖 | 删除 |
| `application.yml` 中 `rag.*` 配置 | 删除 |
| `test/.../RagEvaluationTest.java` 及三个 workflow 测试 | 删除(评估集在 Python 侧重写) |

**保留**:`RagSearchService` 的检索能力迁移到 Python 供客服内部使用 —— **删除的只是对 C 端的公开接口**,不是检索能力本身。客服商品卡片功能不受影响。

#### `rag/` 包逐类处置(23 个类)

**A. 职责迁移**(Python 侧重新实现)

| 类 | 迁移为 |
|----|--------|
| `ChatService` | StateGraph 编排 + agent 车厢 |
| `ChatIntentResolver` | `route` 节点(规则优先 + LLM 兜底) |
| `Intent` | Python 枚举 |
| `IntentWorkflow` | 子图 |
| `CancelOrderWorkflow` | `order_cancel` 子图(确定性) |
| `OrderQueryWorkflow` | `order_query` 子图 |
| `OrderQueryTool` | `@tool` 函数(经 contextvars 取 userId) |
| `ChatHistoryService` | §3.1 全部逻辑 |
| `ChatFeedbackService` | §3.1 反馈写入 |
| `RagSearchService` | 检索服务(能力保留,公开接口下线) |
| `KeywordSearchService` | 关键词检索路 |
| `RrfMerger` | RRF 融合 |
| `QueryRewriter` | query 改写 |
| `ProductIndexer` | 索引构建(改 BGE-m3 / 1024 维) |
| `QdrantCollectionHelper` | collection 管理 |
| `RagHitVO` | Python dataclass |
| `RagProperties` | pydantic-settings 配置模型 |

**B. 被框架机制替代**(不迁移,由 middleware / checkpointer 承担)

| 类 | 替代物 |
|----|--------|
| `CircuitBreaker` | `ModelFallbackMiddleware` |
| `ToolCallLogger` | `wrap_tool_call` hook |
| `MySqlChatMemoryStore` | checkpointer(消息真相源转为 `chat_message` 表) |
| `WorkflowContext` | `ChatState` + `contextvars` |
| `AiAssistant` | `create_agent` 产物 |
| `RagConfig` | Python 侧客户端初始化(OpenAI 兼容客户端 + Qdrant 客户端) |

### 前端

| 文件 | 处置 |
|------|------|
| `frontend/src/api/product.ts:19-20` | 删除 `ragSearch` 方法 |
| `frontend/src/api/types.ts:130` | 删除 `RagSearchResult` 类型 |
| `frontend/src/views/Home.vue:469` | 改为普通关键词搜索 |

### 前置事项

`frontend/src/api/chat.ts` 与 `frontend/src/components/FloatingChat.vue` 当前有**未提交改动**。迁移开始前需先提交或处置,避免与迁移改动混淆。

---

## 8. 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| BGE-m3 换模型导致检索质量下降 | 客服商品推荐变差 | 阶段 ② 卡点跑评估集;旧基线不可直接比,建新基线 |
| BGE-m3 依赖版本敏感 | 相似度结果异常 | 锁定 `sentence-transformers` 版本(社区实测 2.7.0 会因 tokenizers 升级导致 special token 处理异常) |
| DeepSeek 无 model profile | 上下文压缩永不触发 | 显式配置 `trigger`,不用默认值 |
| `handle_tool_errors` 不捕获执行错误 | 工具异常炸掉整图 | 三层分离,工具内自行 catch |
| `/internal/*` 暴露公网 | 越权查单 | Nginx 显式排除 + 内网密钥头校验 |
| 显存不足 | 索引构建 OOM | 只用 Dense 模式(社区报告 Sparse 模式在 8GB 显存会 OOM);控制 batch_size |
| 两个服务协同启动 | 开发环境复杂 | FastAPI `--workers 1`(避免多 worker 重复加载模型) |

---

## 9. 配置与凭证

Java 与 Python **共用仓库根 `.env`**(已 gitignore),各自用本栈惯用方式加载:

| 服务 | 加载方式 | 读取项 |
|------|---------|--------|
| Java | spring-dotenv | `DB_PASSWORD`、`DASHSCOPE_API_KEY`、`INTERNAL_API_KEY` |
| Python | pydantic-settings | `DB_*`、`DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`、`INTERNAL_API_KEY` |

凭证一律经环境变量注入,不得明文写入任何配置文件(延续项目既有安全规范)。`INTERNAL_API_KEY` 用于 Java 内部 API 的密钥头校验。

## 10. 实施前待实测项

以下不影响架构决策,实施时按本地实测裁决:

- LangChain / LangGraph 具体版本锁定(需满足 middleware 的版本要求)
- BGE-m3 全量索引耗时(有 NVIDIA 独显,预计可接受;若过慢再考虑把 embedding 拆为独立服务)
- BGE-m3 与 `sentence-transformers` / `transformers` 的版本组合(社区报告存在 tokenizers 升级导致结果异常的坑)
