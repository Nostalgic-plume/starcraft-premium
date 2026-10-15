# 商品审核流程 — 计划书

> 配套设计文档：[product-audit-design.md](./product-audit-design.md)
> 2026-10-13 · 状态：**已实施并通过验收**（记录见文末）

## 任务分解

T1→T2 是后端一条链；T3、T4 前端可并行；T5/T6 收尾。

---

### T1 · 后端：`addProduct` 按登录端决定初始状态

**文件**：`shop-server/src/main/java/com/shop/service/impl/ProductServiceImpl.java`

**改动**：
- 管理员（`StpAdminUtil.INSTANCE.isLogin()`）新增 → `STATUS_ON_SHELF`（管理员即审核方，不自我审核）
- 其余（商家）→ `STATUS_PENDING`

**验收**：商家新增后查 DB `status=0`；管理员新增后 `status=1`。

---

### T2 · 后端：`updateStatus` 收紧商家上架权限

**文件**：同上

**改动**：在 `updateStatus` 开头加校验
```java
if (StpShopUtil.INSTANCE.isLogin() && status != null
        && status == ProductConstants.STATUS_ON_SHELF) {
    throw new BusinessException("商品上架需管理员审核，请提交审核后等待审核通过");
}
```
外加状态值合法性校验（必须在 {0,1,2} 内）。

**验收**：商家 `?status=1` → 400 + 文案；`?status=2` → 200。

---

### T3 · 商家端前端：提示语 + 状态选项

**文件**：`frontend-merchant/src/views/Products.vue`

**改动**：
- 抽屉内 `form-hint` 文案：「新增商品默认在售」→「新增商品将进入待审核，管理员审核通过后上架」
- `productStatusOptions` 的「在售」项加条件禁用（依据 `isEdit` 与否展示）
- 新增成功提示改为「新增成功，等待管理员审核」

**验收**：`npm run build` 通过；界面文案正确。

---

### T4 · 管理后台：待审核快捷入口 + 审核操作

**文件**：`frontend-admin/src/views/Products.vue`

**改动**：
- 行内操作增加语义化按钮：状态为 `0` 时显示「审核通过」「驳回」（底层仍调 `productApi.updateStatus`）
- 下拉筛选项保留（已支持 `status`）

**验收**：`npm run build` 通过；能一键通过与驳回。

---

### T5 · 验收实测（对照设计文档 A1–A8）

| # | 验收项 | 通过标准 |
|---|---|---|
| A1 | 商家新增落库 `status=0` | DB 查询确认 |
| A2 | 新增商品用户端不可见 | `/api/product/detail/{id}` 空 / 列表不含 |
| A3 | 商家自行上架被拒 | 400 + 「商品上架需管理员审核…」 |
| A4 | 管理员上架成功 | 200 |
| A5 | 商家下架自己商品 | 200 |
| A6 | 管理员新增直接上架 | DB `status=1` |
| A7 | 前端交互正确 | 文案/禁用/按钮 |
| A8 | 编译 + 构建双绿 | `mvn package` + `npm run build` |

**清理**：实测产生的商品全部逻辑删除。

---

### T6 · 文档收尾

- `CLAUDE.md`：「关键设计」章节补充商品审核状态机说明；「API 概览」标注两个状态接口的权限差异
- 本计划书状态改「已实施」+ 附验收记录

---

## 风险与回退

| 风险 | 应对 |
|---|---|
| 商家新增后看不到自己商品，误以为失败 | 前端提交后提示「等待管理员审核」，列表默认不过滤状态（商家端 `canViewAllStatus=true`，能看到自己的待审核商品） |
| 管理员忘记审核导致商品长期不上架 | 管理后台加待审核入口（T4）；后续可加定时提醒，本次不做 |
| 收紧权限影响既有商家操作习惯 | 明确告知：商家仍可下架；只有"上架"这一动作需要管理员 |
| `StpShopUtil.INSTANCE.isLogin()` 在非 Web 上下文抛异常 | 该方法内部无上下文时返回 false，安全（与 `isAdminLoginQuietly` 同类处理） |

---

## 验收记录（2026-10-13 实际执行）

| # | 验收项 | 实测结果 | 判定 |
|---|---|---|---|
| A1 | 商家新增落库 `status=0` | `POST /merchant/product/add` 后 detail 读回 `"status":0` | ✅ |
| A2 | 待审核商品用户端不可见 | 未登录 `GET /product/detail/{id}` → `商品不存在` | ✅ |
| A3 | 商家自行上架被拒 | `PUT /merchant/product/status/{id}?status=1` → `商品上架需管理员审核，请提交审核后等待审核通过` | ✅ |
| A4 | 管理员上架成功 | `PUT /product/status/{id}?status=1` → 200，随后用户端可见 | ✅ |
| A5 | 商家下架自己商品 | `?status=2` → 200 | ✅ |
| A6 | 管理员新增直接上架 | `POST /product/add` 落库 `status=1` | ✅ |
| A7 | 前端交互 | 商家端「在售」置灰、「待审核」提示语、管理后台「审核通过/驳回」按钮 | ✅ |
| A8 | 编译 + 构建 | `mvn package` + 两端 `npm run build` 双绿 | ✅ |

### A2 是验收过程中**发现并修复**的漏洞

首轮验收时发现：列表接口会过滤待审核商品，但 **`getDetail` 完全没有状态过滤** —— 知道 ID 就能直接访问待审核商品的详情。

**修复**：在 `getDetail` 的缓存读取**之后**加判断（放在之后是为了让缓存命中/回源两条路径都被覆盖），非管理员/非商家访问 `status=0` 商品一律返回「商品不存在」。

**边界说明**：只拦 `status=0`（待审核，尚未发布过）；`status=2`（下架）**保持可看详情**，与 `OrderServiceImpl` 下单拦截的既有设计一致（`doPage` 注释已载明这一取舍）。

### 已知债务

- 审核无通知机制：商家需自行刷新看状态，管理员需进后台看待审核列表。后续可加站内信/定时汇总，本次不做
- 审核操作未记录审批日志（谁在何时通过/驳回）。现有 `user_status_log` 只覆盖账号状态，商品侧无对应表

### 环境提示

实施过程中发现 **Qdrant 是后端启动的硬依赖**：只启动默认 profile（Redis/RocketMQ/Nginx）时，`langchain4j` 连接 Qdrant 失败会抛 `ConnectException` 导致 `Application run failed` 退出。启动命令需带 `--profile rag`。
