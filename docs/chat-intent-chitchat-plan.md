# 客服闲聊意图 计划书（chat-intent-chitchat）

## 任务拆解与验收方式

| # | 任务 | 验收方式 |
|---|------|----------|
| 1 | 新增 `Intent.CHITCHAT` | 编译通过，枚举含 `chitchat`/闲聊 |
| 2 | `ChatIntentResolver` 加闲聊规则（词表 + 商品信号守门） | `ChatIntentResolverTest`：闲聊词→CHITCHAT；闲聊词+商品词→PRODUCT；订单规则不回归 |
| 3 | `ChatService` 闲聊分支：跳过改写/检索/卡片，换 prompt | 实测 route=chitchat 且**无** products 事件 |
| 4 | `SaTokenWarmUp` 预热三端 StpLogic | 冷启动后对 `@SaCheckLogin(type="user")` 接口的**首个**请求不再 500 |
| 5 | 离线编译 + 单测 | `mvn -o -Dtest=ChatIntentResolverTest test` 全绿 |
| 6 | 重启后端实测两条消息 | 「能不能陪我聊天」→ 无卡片；「我想买个手机」→ 有卡片（不回归） |
| 7 | 文档回填 | 设计稿与本文档同步实测结果 |

## 验收记录（2026-09-20 17:00 执行）

| # | 验收项 | 做法 | 实测结果 |
|---|--------|------|----------|
| 1 | 枚举与编译 | `mvn -o -DskipTests compile` | BUILD SUCCESS |
| 2 | 意图单测 | `mvn -o -Dtest=ChatIntentResolverTest test` | Tests run: 7, Failures: 0, Errors: 0（新增 2 项闲聊用例） |
| 3 | 预热时序 | 日志比对 | `SaTokenWarmUp` 17:00:20.573 打印预热 → 17:00:22.740 `Tomcat started on port 8080`，早于开始收请求 |
| 4 | 冷启动首个鉴权请求 | 重启后立即 `GET /api/cart/list`（无 token） | `{"code":401,"msg":"未登录，请先登录"}`；日志「未能获取对应StpLogic」0 次、「系统异常」0 次（改造前该请求返回 500） |
| 5 | 带 token 鉴权 | `GET /api/cart/list`（带 token） | `{"code":200,"msg":"操作成功","data":[]}` |
| 6 | 闲聊不检索、不推卡片 | `POST /api/chat/ask` 发「能不能陪我聊天」 | `route=chitchat`，**无 products 事件**；回答为自然闲聊；日志 `[客服意图] 闲聊，跳过商品检索`，且该轮无 `[RAG指标]` 行 |
| 7 | 商品链路不回归 | `POST /api/chat/ask` 发「我想买个手机」 | `route=product`，3 张卡片（小米14 Ultra / 华为 Mate 60 Pro / 华为 Mate 70 Pro）；日志 `[RAG指标] ... 向量命中50 关键词命中9 合并3条 RRF(k=60,wv=1.0,wk=2.0)` |

### 遗留（本次未做）

- 前端 `frontend/src/components/FloatingChat.vue` 的 route 标签表缺 `chitchat` 键 → 闲聊时状态条为空（不报错、不阻塞）；
  需要时补一行 `chitchat: '正在陪您聊天'` 并 `npm run build`（本次按「只做 Java」约定未动前端）。
- 设计稿 §五 的三项遗留（`conversationId="default"` 防御缺口、辱骂类消息、卡片相关性闸门）维持原状。