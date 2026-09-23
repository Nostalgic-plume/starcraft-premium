# 数据看板「未登录，请先登录」根因与修复

> 2026-09-23 · 管理员登录后进入数据看板，仍提示「未登录，请先登录」

## 1. 现象

管理员正常登录管理后台，进入数据看板页面弹出 `未登录，请先登录`，
页面部分数值为空，但其他管理页面（用户/订单管理）一切正常。

## 2. 根因

`DashboardController.all()` 的注解把 loginType 写成了 `user`：

```java
@SaCheckLogin(type = "user")   // ← 错配
@GetMapping("/all")
public Result<DashboardAllVO> all() { ... }
```

三端独立会话重构后，用户/商家/管理员分别使用 `user` / `shop` / `admin`
三个**相互隔离**的 loginType（`StpUserUtil` / `StpShopUtil` / `StpAdminUtil`）。
管理后台持有的是 `admin` 会话，而该接口校验的是 `user` 会话 ——
**永远查不到登录态**，于是管理员已登录仍被判为未登录。

### 证据

| 步骤 | 结果 |
|------|------|
| 登录 `POST /api/admin/login`（admin/123456） | 200，返回 token |
| 该 token 请求 `/api/admin/stats/dashboard` | **200**（正常，注解是 `type="admin"`） |
| 同一 token 请求 `/api/dashboard/all` | **`{"code":401,"msg":"未登录，请先登录"}`** ← 完全复现 |

### 为什么只有看板出问题

`/api/dashboard/all` 只被管理后台调用（`frontend-admin/src/api/report.ts`），
且 `Dashboard.vue` 用 `Promise.allSettled` 并发 6 个接口 ——
它单独失败不会让整页进入错误态，只弹一条拦截器错误消息并让相关数值回退空态，
所以表现为「看板部分数据空了 + 弹未登录」，而非整页崩溃。

### 消息来源

后端未登录时返回 **HTTP 200 + `{"code":401,"msg":"未登录，请先登录"}`**，
由 `shared/js/createRequest.ts` 响应拦截器在 `code !== 200` 分支
`ElMessage.error(data.msg)` 弹出。

## 3. 修复

```java
@SaCheckLogin(type = "admin")
@GetMapping("/all")
```

## 4. 全量排查（同类错配）

排查了全部控制器的鉴权注解 type 与调用端是否匹配：

| 控制器 | type | 调用端 | 判定 |
|--------|------|--------|------|
| AdminController / AdminStatsController / IndexAdminController | admin | 管理后台 | ✅ |
| MerchantController | shop | 商家中心 | ✅ |
| Cart / Order / Payment / UserController | user | 用户端 | ✅ |
| **DashboardController** | **user** | **管理后台** | ❌ 已修复 |

结论：这是**唯一**一处错配，无其他漏网。

## 5. 验证状态

| 项 | 结果 |
|----|------|
| 编译 | ✅ `mvn clean compile` EXIT=0 |
| `DashboardControllerTest` | ✅ 2/2 通过 |
| `AdminStatsControllerTest` | ✅ 7/7 通过 |
| 端到端 | ⏳ **未执行** —— 服务正在被使用，未重启；需重启后端后确认 |

重启后自测命令：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

curl -s http://localhost:8080/api/dashboard/all -H "token: $TOKEN" | head -c 120
# 修复前：{"code":401,"msg":"未登录，请先登录",...}
# 修复后：{"code":200,"msg":"操作成功",...}
```

## 6. 复盘：为什么测试没拦住

`DashboardControllerTest` 是 `@WebMvcTest`，且测试内 `@MockBean SaTokenConfig`
把注册 `SaInterceptor` 的配置类替换成了 mock —— **注解鉴权整体失效**，
所有请求都会放行。因此该测试对 `type` 写 `user` 还是 `admin` **完全不敏感**，
只能覆盖业务返回结构，覆盖不了鉴权。

同类问题（loginType 错配）在本项目里**无法用现有 mock 工具验证**：
`SaTokenMockUtil` 为了让任意 loginType 都能取到登录态，
把 `SaManager.getStpLogic(anyString())` 统一返回 `StpUserUtil.INSTANCE`，
恰好抹平了 loginType 之间的差异。

→ 建议的兜底手段（待评估）：对每个控制器的鉴权注解做「调用端 ↔ loginType」一致性断言，
或补一个不 mock `SaTokenConfig` 的鉴权切片测试。

## 7. 顺带发现（未改动）

`createRequest.ts` 的响应拦截器在业务 `code === 401` 时只弹消息，
**不清 token、不跳登录页**（跳转逻辑写在 HTTP 401 的 error 分支，
但后端用 HTTP 200 承载业务 401，该分支不会被触发）。
因此会话真过期时用户会停在原页面反复看到提示，需手动刷新/退出。
