# shop 评测数据集重造 — 计划书

> 配套文档：[eval-dataset-regen-design.md](eval-dataset-regen-design.md)
> 状态：待确认（2026-10-13）

## 分批原则

按**风险递增**分三批。批次 A 全程只读 / 只改代码，不碰任何数据；批次 B 才动目标库；批次 C 处理派生数据。每批做完先验收再进下一批。

---

## 批次 A — 准备（零数据风险）

### T1. 直连真实库重新分析，产出新生成计划

**做什么**
- 备份 DataForge 现有 `generation-plan.json` / `project_rules.json`（存档，不覆盖丢历史）
- 启动 DataForge Web，调 `POST /api/analyze` 连真实 shop 库（读 information_schema）
- 与旧产物做 diff，确认 §6 三处偏差是否消除

**验收**
- 新 `generation-plan.json` 覆盖表数 = 20（含 `brand`）
- 计划中 `shop` 表的列清单与真实库一致（无 `user_id`）
- diff 报告明确列出「旧计划有 / 真实库无」「真实库有 / 旧计划无」两个方向的差异

**风险**：无（只读库结构）

---

### T2. 补全并固化计划配置

**做什么**
- 补齐 `brand` 表的列规则（`name` 有唯一索引 `uk_name`，文字模式用 llm/faker）
- 修正 `password` 列：改为真实 BCrypt("123456") 常量（C5），三个账号表统一
- 按 §7 待决项 3 的量级目标，重配各表 `rows`（不再是默认 `2-5`）
- 确认 `category` ID 100-123 冻结规则保留（被 shop-data-mocker 引用）
- 确认 `orders` 状态与 `pay_time`/`deliver_time`/`receive_time` 的 STATUS_FLOW 规则已启用

**验收**
- `generation-plan.json` 逐表人工确认，行数符合目标画像
- `project_rules.json` 中 password 列无占位符
- 计划中 REPLACE 目标的表集合已明确列出（含 `user_role` 等引用闭包）

**风险**：无（改配置文件）

---

### T3. Publisher 支持真正的 REPLACE

**做什么**
- 按 design §4.3 实现：白名单校验 → 备份 `_dfbak_*` → TRUNCATE → INSERT → 失败自动恢复
- 保持 `KEEP`（默认）与 `APPEND`（现有行为）**完全不变**——这是回归红线
- `POST /api/publish` 响应新增 `backups` 字段（design §4.4）
- 修正 `DataForgeApiController` 预检分支的文案与返回内容（design §4.6）

**验收**
- 代码审查：`KEEP` / `APPEND` 分支与原实现逐行等价
- 无任何路径能在 `confirmed=false` 时执行写操作
- 备份失败时该表不走删除路径（安全优先）

**风险**：中——碰的是"目标库写操作"的代码。缓解：先写测试（T4），再改实现

---

### T4. Publisher 改造的单元测试

**做什么**
- 参照 DataForge 既有测试风格（mock LLM + 本地 MySQL）
- 用例覆盖：REPLACE 正常路径 / KEEP 不动 / APPEND 追加 / 备份失败拒绝删除 / INSERT 失败自动恢复 / 恢复失败标记
- 跑全量回归，确认既有 35+ 用例全部通过

**验收**
- 新增用例全绿，既有用例零回归
- 至少有一条用例验证"TRUNCATE 后自增 ID 从 1 开始"

**风险**：无

---

## 批次 B — 执行（动目标库）

### T5. 全量生成并校验到 staging（不碰目标库）

**做什么**
- 按 T2 的计划与行数，用**真实 LLM**（避免 Mock 的占位内容，design §6）全量生成
- staging 工作库内完成 SQL 校验 + 修正收敛
- 生成结果落 staging，暂不发布

**验收**
- 校验报告：FK 悬挂 = 0、唯一键冲突 = 0、金额规则（`total_amount = Σ order_item.subtotal`）差异 = 0
- `orders` 状态与时序列一致（无"已支付但 pay_time 为 NULL"）
- 商品/描述抽样 20 条人工可读，有语义区分度
- 软评分（生成 vs 现有真实库分布）达到合理区间

**风险**：中——消耗 LLM 额度；staging 与目标库结构若不符会在发布时暴露

---

### T6. 执行发布：清空目标库业务表并重建

**做什么**
- **前置**：停 shop-server 与 shop-rec 进程；导出 `.env` 凭证供连接；确认 T2 的 REPLACE 表清单
- 调 `POST /api/publish`，`confirmed=false` 先看预检清单 → 人工核对表清单与策略 → `confirmed=true` 执行
- 执行后核对每张表的备份表 `_dfbak_*` 已生成

**验收**
- 返回体 `errors` 为空，`backups` 覆盖全部 REPLACE 表
- 逐表行数与目标配置一致
- `user_role` 无孤儿（`SELECT COUNT(*) FROM user_role ur LEFT JOIN user u ON ur.user_id=u.id WHERE u.id IS NULL` = 0）
- 备份表可读、行数等于清空前的行数

**风险**：**高**——这是唯一不可逆的操作窗口。缓解：备份表提供恢复路径；`confirmed` 二次确认；执行前导出预检清单留档

---

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

**风险**：中——派生重建涉及多个子系统，工作量可能超出预期

---

## 验收总表

| 子任务 | 验收方式 | 完成 |
|---|---|---|
| T1 真实库重分析 | 新计划 20 表含 brand；shop 表列与真实库一致 | ☐ |
| T2 补全计划配置 | 行数人工确认；password 无占位符 | ☐ |
| T3 Publisher REPLACE | KEEP/APPEND 逐行等价；confirmed=false 零写操作 | ☐ |
| T4 单元测试 | 新用例全绿 + 35 用例零回归 | ☐ |
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
