# shop 评测数据集重造 — 计划书

> 配套文档：[eval-dataset-regen-design.md](eval-dataset-regen-design.md)
> 状态：执行中（2026-10-13）

## 已确认的决策

| 决策项 | 结论 | 确认时间 |
|---|---|---|
| 用途 | AI 应用评测基线（RAG / 智能客服 / 推荐） | 2026-10-13 |
| 删除范围 | 只清 shop 主库业务表 | 2026-10-13 |
| Publisher | 允许改，支持真 REPLACE | 2026-10-13 |
| chat_* 表 | 旧数据删除，与主链路一并重造 | 2026-10-13 |
| `rag_trace` | **保留不动**（评测结果记录，非被评测数据） | 2026-10-13 |
| 生成 LLM | 真实 LLM（DeepSeek） | 2026-10-13 |
| 量级 | 中等档：商品 300 / SKU 1200 / 用户 500 / 订单 8000（约 2.8 万行） | 2026-10-13 |

## 批次原则

按**风险递增**分三批。批次 A 全程只改代码 / 配置，不碰数据；批次 B 才动目标库；批次 C 处理派生数据。

> **顺序说明**：原计划将"修 analyze 落盘缺口"放在 T3，但它是 T4（重新分析拿到正确计划）的**硬前置**——analyze 响应体不返回 `parent` / `rowsPerParent`，无法手工拼出计划文件。故与 Publisher 改造合并为同一次代码改动、同一次构建。

---

## 批次 A — 准备（零数据风险）

### T1. 直连真实库重新分析，核实结构偏差 ✅ 已完成

**结果**（`POST /api/analyze`，mock 模式，98ms）：

| 验收项 | 结果 |
|---|---|
| 分析表数 = 20（含 `brand`） | ✅ |
| `shop` 表列与真实库一致（无 `user_id`） | ✅ `username, password, shop_name, shop_logo, shop_desc, contact_name, contact_phone, status, deleted` |
| `brand` 表产出列规则 | ✅ `name, logo, description, sort, status, deleted` |
| 与旧计划 diff | 旧计划 18 表（缺 brand、shop 表多 `user_id`） |

**同时暴露的三个新问题**：

- **A（阻塞项）**：`/api/analyze` 只落盘 `project_rules.json`，**不落盘 `generation-plan.json`**（`DataForgeApiController:307`）。分析结果正确但存不下来，后续生成会加载旧计划。
- **B**：mock 模式的行数推荐是占位值（清一色 10/15/23，总计 246 行），未参考真实库分布，无参考价值。
- **C**：`rag_trace` 被纳入分析范围 → 已决策保留不动，需在 REPLACE 表集合中排除。

**附带修复**：`DataForgeApplication:37-40` 的 `--port` 参数处理会向 Spring 传入 null 导致启动 NPE（`Arrays.copyOf(args, len+2)` 但只填了一个位置），已修为 `len+1`。

---

### T2. 代码改动：修 analyze 落盘缺口 + Publisher 支持真 REPLACE

**做什么**

1. **修问题 A**：`/api/analyze` 端点补落盘 `generation-plan.json`（写 `result.plan()`，与 `project_rules.json` 同处落盘）
2. **Publisher REPLACE**：按 design §4.3 实现逐表流程 —— 白名单校验 → 备份 `_dfbak_<table>_<ts>` → TRUNCATE → INSERT → 失败自动恢复
3. **回归红线**：`KEEP`（默认）与 `APPEND`（现有行为）必须逐行等价，不变更语义
4. **接口契约**：`POST /api/publish` 响应新增 `backups` 字段（design §4.4）
5. **修正文案**：预检分支返回每表策略并高亮 REPLACE 表（design §4.6）

**验收**
- analyze 后 `generation-plan.json` 覆盖 20 表、`shop` 表无 `user_id`
- 代码审查：`KEEP` / `APPEND` 分支与原实现等价
- 无任何路径能在 `confirmed=false` 时执行写操作
- 备份失败时该表不走删除路径

**风险**：中——碰的是目标库写操作代码。缓解：T3 先写测试，再确认实现

---

### T3. 单元测试

**做什么**
- 参照既有测试风格（mock LLM + 本地 MySQL）
- 用例覆盖：REPLACE 正常路径 / KEEP 不动 / APPEND 追加 / 备份失败拒绝删除 / INSERT 失败自动恢复 / 恢复失败标记
- 跑全量回归

**验收**
- 新增用例全绿，既有 35+ 用例零回归
- 至少一条用例验证「TRUNCATE 后自增 ID 从 1 开始」

---

### T4. 重新分析并固化计划配置

**做什么**
- 重新调 `/api/analyze`，确认 `generation-plan.json` 落盘生效且为 20 表
- 按中等档重配各表 `rows`：

  | 表 | 目标行数 | 表 | 目标行数 |
  |---|---|---|---|
  | `user` | 500 | `orders` | 8000 |
  | `shop` | 10 | `order_item` | 16000 |
  | `category` | 24（保持 ID 100-123 冻结） | `cart` | 500 |
  | `brand` | 40 | `address` | 500 |
  | `product` | 300 | `chat_conversation` | 150 |
  | `sku` | 1200 | `chat_message` | 800 |
  | `admin` / `role` / `permission` | 维持现有（2/3/6） | `chat_feedback` | 300 |

- 修正 `password` 列：改为真实 BCrypt("123456") 常量（三个账号表统一）
- 确认 `orders` 的 STATUS_FLOW 规则启用（状态与 `pay_time`/`deliver_time`/`receive_time` 时序一致）
- 明确 REPLACE 表集合（含 `user_role` 引用闭包；排除 `rag_trace` / `admin` / `role` / `permission` / `role_permission`）

**验收**
- 计划逐表人工确认，行数符合中等档
- `password` 列无占位符
- REPLACE 表集合已明确列出且闭合

---

## 批次 B — 执行（动目标库）

### T5. 全量生成并校验到 staging（不碰目标库）

**做什么**
- 按 T4 的计划与行数，用**真实 LLM** 全量生成
- staging 工作库内完成 SQL 校验 + 修正收敛
- 结果落 staging，暂不发布

**验收**
- FK 悬挂 = 0、唯一键冲突 = 0、金额规则（`total_amount = Σ order_item.subtotal`）差异 = 0
- `orders` 状态与时序列一致（无「已支付但 `pay_time` 为 NULL」）
- 商品/描述抽样 20 条人工可读、有语义区分度
- 软评分（生成 vs 现有真实库分布）在合理区间

**风险**：中——消耗 LLM 额度；staging 与目标库结构不符会在发布时暴露

---

### T6. 执行发布：清空目标库业务表并重建

**做什么**
- **前置**：停 shop-server 与 shop-rec 进程；从 `.env` 取连接凭证
- 调 `POST /api/publish`，先 `confirmed=false` 取预检清单 → 人工核对表清单与策略 → `confirmed=true` 执行
- 核对备份表 `_dfbak_*` 已生成

**验收**
- 返回体 `errors` 为空，`backups` 覆盖全部 REPLACE 表
- 逐表行数与目标配置一致
- `user_role` 无孤儿：`SELECT COUNT(*) FROM user_role ur LEFT JOIN user u ON ur.user_id=u.id WHERE u.id IS NULL` = 0
- 备份表可读、行数等于清空前行数

**风险**：**高**——唯一不可逆的操作窗口。缓解：备份表提供恢复路径；二次确认；执行前导出预检清单留档

---

## 批次 C — 派生数据

### T7. 应用链路验证 + 派生数据重建

**做什么**
- 清 Redis 缓存 → 启动 shop-server / shop-rec
- 验证：三个入口登录（admin / 商家 / 用户，密码 123456）、商品列表与详情、加购、下单、订单查询
- 重建 ES 索引与 BGE-M3 向量库
- 重跑数仓 ETL（`shop_staging` → `shop_ads`）
- 重算 `shop_rec` 的 `t_user_profile` / `t_item_hot_score`

**验收**
- 前端全链路可走通，无 500
- RAG 检索能命中新商品（非已删除的旧 ID）
- 推荐接口返回结果非空
- 数仓 9 张 `ads_*` 表有数据且数值合理

**风险**：中——涉及多个子系统

---

## 验收总表

| 子任务 | 验收方式 | 状态 |
|---|---|---|
| T1 真实库重分析 | 20 表含 brand；shop 表列与真实库一致 | ✅ 通过 |
| T2 代码改动 | analyze 后 plan 落盘 20 表；KEEP/APPEND 等价；confirmed=false 零写操作 | ☐ |
| T3 单元测试 | 新用例全绿 + 35 用例零回归 | ☐ |
| T4 固化配置 | 行数符合中等档；password 无占位符；REPLACE 集合闭合 | ☐ |
| T5 生成到 staging | FK=0 / 唯一=0 / 金额=0；抽样可读 | ☐ |
| T6 执行发布 | errors 空；user_role 无孤儿；备份表可读 | ☐ |
| T7 链路验证 + 派生重建 | 前端全链路通；RAG/推荐命中新数据 | ☐ |

## 回滚预案

| 阶段 | 回滚方式 |
|---|---|
| T1–T4 | 代码 / 配置 git 回退，无数据影响 |
| T5 | 丢弃 staging 工作库重建 |
| T6 | 从 `_dfbak_*` 表恢复：`INSERT INTO t SELECT * FROM _dfbak_t_<ts>` |
| T7 | 恢复数据后重建索引与缓存即可 |
