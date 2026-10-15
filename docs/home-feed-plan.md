# 首页信息流改造计划书

- **日期**：2026-10-14
- **配套设计**：[home-feed-design.md](home-feed-design.md)
- **分派原则**：先做后端（可独立验收、风险低），再做前端（依赖后端契约已定）

---

## 任务列表

| # | 任务 | 触及范围 | 依赖 | 验收方式 |
|---|---|---|---|---|
| **T1** | 新增配置项 `rank.head.personalized.size`（默认 5） | `RecConfigKeys`（常量 + DEFAULTS） | — | 编译通过；`t_rec_config` 插入对应行 |
| **T2** | `RankService` 实现首位个性化配额 | `RankService`（新增 `applyHeadQuota`，插在 `toRankedItems` 与 `injectFixedSlots` 之间） | T1 | 编译通过；`rank()` 中调用顺序为 配额 → 坑位 |
| **T3** | 快照 TTL 由 300s 提到 1800s | `SnapshotService`（兜底默认值）+ `t_rec_config.cache.result.ttl` | — | 编译通过；DB 值已更新 |
| **T4** | `RecRankList` 由横向卡片行改为竖向无限滚动列表 | `frontend/src/components/RecRankList.vue` | T2（后端返回带来源标记） | `npm run build` 通过；代码审查确认无残留 rAF/scrollLeft |
| **T5** | `Home.vue` 商品网格去分页、改触底追加 | `frontend/src/views/Home.vue` | — | `npm run build` 通过；分页组件已移除、`page` 递增且 concat |
| **T6** | 设计文档同步（详细设计说明书 3.7 补配额步骤） | `docs/recsys/2026-09-24-...详细设计说明书.md` | T2 | 文档中步骤编号与实现一致 |
| **T7** | 编译与构建验证（**不含集成测试**） | — | T1~T6 | `mvn -pl shop-rec compile` + `npm run build` 均退出 0 |

---

## 执行顺序与分批

**批次一（后端，T1 → T2 → T3 → T6）**：改动互相独立于前端，可先完成并编译验证。

**批次二（前端，T4 → T5）**：依赖批次一定下的后端契约（`recallSource` 语义、`hasMore`）。

**批次三（T7）**：统一编译与构建。

---

## 验收与限制

### 本轮能验收的

- T1/T2/T3/T6 编译通过
- T4/T5 前端构建通过（`npm run build` 能抓语法与类型错误）
- T2 的调用顺序（配额在坑位之前）可由代码审查确认

### 本轮**不能**验收的（必须在容器启动后补）

集成测试与端到端验收都依赖 **Redis（Docker 容器）**，本轮容器被关闭，故以下全部顺延：

| 顺延项 | 需要的环境 | 对应设计验收项 |
|---|---|---|
| `mvn -pl shop-rec clean test` | Redis（集成本身不依赖 Flink） | 设计 §七-8 |
| 首位配额生效/可关闭/冷启动降级 | Redis + MySQL + 推荐服务 | 设计 §七-1~3 |
| 坑位优先级 | 同上 + `t_rec_fixed_slot` 造数 | 设计 §七-4 |
| 无重复 / 顺序稳定 / 终止态 | 同上 + 前端页面 | 设计 §七-5~7 |

> ⚠️ **不要把「编译通过」记作「验证通过」。** 本轮交付的是「可用代码 + 可复核的设计」，不是「已验收的功能」。

---

## 回滚预案

| 变更 | 回滚方式 |
|---|---|
| T1/T2 配额逻辑 | 配置项 `rank.head.personalized.size` 置 **0** 即关闭，无需回滚代码 |
| T3 快照 TTL | `t_rec_config.cache.result.ttl` 改回 300 |
| T4/T5 前端 | `git revert` 对应提交（不涉及数据与后端契约） |
| T6 文档 | 与代码同批 revert |

**配额逻辑做成「置 0 即关闭」是刻意设计**：它让线上出问题时无需发版即可止血。
