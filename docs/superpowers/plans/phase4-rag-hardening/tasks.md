# Phase 4 RAG 加固——任务规划

> 依据 `requirements.md` 拆分。仅任务规划，不含代码。按依赖拓扑排序，自上而下执行。
> 主题 A：写操作安全；主题 B：RAG 四建议（引用溯源 / 索引联动 / 可观测性 / 行为反馈）。
>
> **状态：2026-08-30 全部完成**，验证结果见文末。

- [x] T1: 写操作安全——`cancelOrder` 确认机制 + 工具 few-shot 示例（C-A1/C-A2/C-A3/C-A4/C-A5）（依赖：无）
    验收要点：① SystemPrompt 增加"取消订单必须先确认"规则 + 工具调用 few-shot 示例（**已实现**）；② `OrderTool.cancelOrder` description 补充"已先用 getOrderStatus 确认 + 用户明确确认后才调用"（**已实现**）；③ LLM 行为验证：prompt 引导生效（依赖 LLM，运行时观察）；④ `OrderService` 归属校验兜底不变。

- [x] T2: 引用溯源——LLM 引用编号 + 前端卡片编号徽标（C-B1-1/C-B1-2/C-B1-3）（依赖：T1）
    验收要点：① SystemPrompt 引导"引用第 n 个推荐商品标 [n]"（**已实现**）；② 前端商品卡片左上角珊瑚红编号徽标 `idx+1`（**已实现，前端 build 通过**）；③ LLM 编号行为依赖模型，prompt 约束生效；④ 编号顺序 = products 事件商品顺序（固定）。

- [x] T3: 索引联动——商品变更自动增量索引（C-B2-1~C-B2-4）（依赖：无）
    验收要点：① **确认代码已存在**（`ProductServiceImpl.addProduct` 与 `updateStatus` 均已调 `productIndexer.addOrUpdate/remove` + try-catch，本项目**无改价/删除方法**）；② 下架→remove、上架→addOrUpdate（已实现）；③ 联动失败吞异常不阻塞主流程（已实现）；④ 既有全量重建不受影响（增量逻辑）。

- [x] T4: 可观测性——检索/生成指标采集（C-B3-1~C-B3-4）（依赖：无）
    验收要点：① `RagSearchService.search()` 埋点 query/耗时/命中数（**已实现**）；② `ChatService` deepSeek + qwen 分支 LLM token 消耗埋点（**已实现**）；③ 不记录 PII 消息内容（只记 query/会话ID）；④ 埋点轻量（log.info），不阻塞主链路。

- [x] T5: 行为反馈埋点——搜索→点击记录（C-B4-1~C-B4-4）（依赖：无）
    验收要点：① 新增 `rag_trace` 表 + `RagTraceDO` + `RagTraceMapper`（**已实现**）；② 新增 `POST /api/rag/trace`（query/productId/action，登录可用，失败不影响主链路，**已实现**）；③ 前端 `ProductCard` emit `detail-click` + `Home.traceClick` 异步上报（**已实现，前端 build 通过**）；④ 数据可导出评估集格式。

- [x] T6: 集成回归验证——Phase 4 全量改动后验证（依赖：T1,T2,T3,T4,T5）
    验收要点：① 后端编译 + 前端 build 通过；② `mvn test` 177 测试仅预存 1+6 失败（无新增）；③ 客服 SSE products 事件正常；④ **实测可观测指标**：`[RAG指标] query=手机 耗时391ms 向量命中3`、`[LLM指标] 会话=7001 输入token=1352 输出token=195`；⑤ **实测行为反馈**：`/api/rag/trace` 返回 200，`rag_trace` 表记录 (手机, product1, SEARCH_CLICK, user10003)；⑥ 更新文档。

---

## 验证结果记录（2026-08-30）

| 验证项 | 结果 |
|--------|------|
| 后端编译 / 前端 build | BUILD SUCCESS / ✓ built |
| `mvn test` | 177 测试，仅预存 `ProductServiceImplTest` 1+6 失败（无新增） |
| 客服 SSE | products 事件正常（3 商品） |
| **可观测指标（T4）** | ✅ `[RAG指标] query=手机 topK=3 耗时391ms 向量命中3 关键词命中9`；`[LLM指标] 会话=7001 输入token=1352 输出token=195` |
| **行为反馈（T5）** | ✅ `/api/rag/trace` 200，`rag_trace` 表落库 (手机, 1, SEARCH_CLICK, 10003) |
| **索引联动（T3）** | ✅ 代码已存在（addProduct/updateStatus 已调 addOrUpdate/remove），运行时验证可选 |
| 写操作安全（T1） | ✅ prompt + few-shot + description 已实现，运行时 LLM 行为依赖模型 |

### 说明
- T3（索引联动）在实施时发现**已由之前开发实现**（`ProductServiceImpl` 已注入 `ProductIndexer` 并联动），本阶段仅确认 + 归档，无需新代码。
- 引用编号（T2）与写操作确认（T1）为 prompt 层引导，LLM 行为因模型而异，代码层面（prompt/description/few-shot）已满足。
