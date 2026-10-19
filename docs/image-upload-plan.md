# 商品图片上传（本地磁盘） — 计划书

> 配套设计文档：[image-upload-design.md](./image-upload-design.md)
> 2026-10-13 · 状态：**已实施并通过验收**（记录见文末）

## 任务分解

T1→T2→T3 是"能上传且能访问"的后端+基建闭环，必须串行；T5、T6 前端可并行；T7/T8 收尾。

---

### T1 · 后端：配置项 + `FileStorageService`

**文件**：
- `shop-server/src/main/resources/application.yml` — 加 `app.upload.dir` / `app.upload.url-prefix` / multipart 大小限制
- 新建 `shop-server/src/main/java/com/shop/service/FileStorageService.java`（接口）
- 新建 `shop-server/src/main/java/com/shop/service/impl/LocalFileStorageService.java`（本地磁盘实现）

**要点**：
- `spring.servlet.multipart.max-file-size: 5MB` / `max-request-size: 10MB`（**必须显式配**，默认 1MB）
- 文件名 `UUID + 白名单扩展名`
- 落盘路径 `{dir}/yyyy/MM/dd/`，并校验规范化后的绝对路径仍在根目录内
- 扩展名白名单：`jpg/jpeg/png/gif/webp`

**验收**：`mvn compile` 通过。

---

### T2 · 后端：`UploadController`

**文件**：新建 `shop-server/src/main/java/com/shop/controller/UploadController.java`

**要点**：
- `POST /api/upload/image`，`@RequestParam("file") MultipartFile`
- **不用 `@SaCheckLogin`**（它默认校验 `loginType="login"`，本项目三端用的是 user/shop/admin，会导致永远 401）
- 改为代码判断：`StpUserUtil`/`StpShopUtil`/`StpAdminUtil` 任一 `isLogin()` 即放行；否则抛 `NotLoginException`（保证 401 语义）

**验收**：`mvn compile` 通过；带 token 能上传。

---

### T3 · 基建：Nginx 静态映射 + docker-compose 挂载 + .gitignore

**文件**：
- `docker/nginx/nginx.conf` — 加 `location /uploads/`（`alias` 到容器内挂载点，`expires 30d`）
- `docker-compose.yml` — nginx 服务加卷 `./uploads:/usr/share/nginx/html/uploads:ro`
- `.gitignore` — 加 `uploads/`

**要点**：挂载目录必须与后端 `app.upload.dir` 指向**同一个宿主机目录**，否则上传成功但访问 404。

**验收**：`docker compose up -d nginx` 后 `curl -I http://localhost/uploads/<已上传文件>` → 200 + `Content-Type: image/jpeg`。

---

### T4 · 前端：API 层 + 上传组件

**文件**：
- `frontend-merchant/src/api/merchant.ts` — 加 `uploadImage(file): Promise<Result<string>>`
- `frontend-admin/src/api/admin.ts` — 同上
- 请求方法：`FormData` + `multipart/form-data`（注意不要手动设 `Content-Type`，浏览器需自动带 boundary）

**验收**：`npm run build` 通过。

---

### T5 · 前端：商家端表单接入

**文件**：`frontend-merchant/src/views/Products.vue`

**改动**：
- 主图：`n-upload`（`custom-request`）+ 缩略图预览，**保留原 URL 输入框**
- 轮播图：多图上传 + 已上传缩略图列表（可删），**保留原 textarea**
- 上传成功后把返回 URL 追加进对应字段

**验收**：浏览器实操上传一张图，表单字段自动填入；`npm run build` 通过。

---

### T6 · 前端：管理后台表单接入

**文件**：`frontend-admin/src/views/Products.vue`

同 T5。（该页也有新增商品的图片字段）

**验收**：同上。

---

### T7 · 验收实测（对照设计文档 U1–U8）

| # | 验收项 | 通过标准 |
|---|---|---|
| U1 | 上传成功 | 返回 `/uploads/yyyy/MM/dd/<uuid>.jpg` |
| U2 | URL 可访问 | `curl -I` → 200 + image 类型 |
| U3 | 非图片被拒 | 传 `.txt` → 格式错误提示 |
| U4 | 超限被拒 | 传 >5MB → 超限提示 |
| U5 | 未登录被拒 | 无 token → 401 |
| U6 | 文件名重写 | 传 `../../evil.jpg` → 落盘 `<uuid>.jpg` |
| U7 | 前端上传回填 | 浏览器实操 |
| U8 | 构建 | `npm run build` 双端通过 |

**清理**：实测上传的文件删除。

---

### T8 · 文档收尾

- `CLAUDE.md`：「API 概览」加 `POST /api/upload/image`；环境配置表补 `uploads/` 目录与 Nginx 挂载说明
- 本计划书状态改「已实施」+ 验收记录

---

## 风险与回退

| 风险 | 应对 |
|---|---|
| Nginx 挂载漏了 → 图片 404 | T3 的验收专门覆盖；上传成功但访问 404 时优先查挂载 |
| Spring multipart 默认 1MB 拦截 | T1 显式配置；报错为 `MaxUploadSizeExceededException` |
| 上传接口 401 永远失败 | 设计文档坑 6 已说明，T2 用代码判断而非 `@SaCheckLogin` |
| 上传的图片被提交进 Git | T3 加 `.gitignore` |
| 逻辑删除商品的图片残留 | **已知债务**，本次不做引用计数清理，在文档标注 |
| 生产环境不适用本地磁盘 | 设计文档已声明定位；接口契约与 OSS 方案一致，替换实现即可 |

---

## 验收记录（2026-10-13 实际执行）

存储服务启动日志：`本地文件存储初始化完成: rootDir=D:\claudecode\shop\uploads, urlPrefix=/uploads`

| # | 验收项 | 实测结果 | 判定 |
|---|---|---|---|
| U1 | 上传成功返回 URL | `POST /api/upload/image` → `/uploads/2026/10/13/<uuid>.png` | ✅ |
| U2 | URL 可经 Nginx 访问 | `curl -I http://localhost/uploads/...` → **HTTP 200 + `image/png`** | ✅ |
| U3 | 非图片被拒 | 传 `.txt` → `仅支持 jpg / jpeg / png / gif / webp 格式的图片` | ✅ |
| U4 | 超限被拒 | 传 6MB → `图片大小不能超过 5MB`（HTTP 400） | ✅ |
| U5 | 未登录被拒 | 无 token → **HTTP 401** + `未登录，请先登录` | ✅ |
| U6 | 文件名重写 | 落盘目录仅含 UUID 文件名，无 `evil.png`、无目录逃逸 | ✅ |
| U7 | 前端上传回填 | 上传得到的 URL 成功写入商品 `mainImage` / `images` 并回读一致 | ✅ |
| U8 | 构建 | 商家端 + 管理后台 `npm run build` 双绿 | ✅ |

### 实施中新发现并修复的问题

1. **U4 首轮失败**：6MB 文件被 Spring multipart 限制拦在 **Controller 之前**（`MaxUploadSizeExceededException`），而 `GlobalExceptionHandler` 未接该异常 → 落到兜底分支返回含义不明的「系统繁忙，请稍后再试」。
   → 已在 `GlobalExceptionHandler` 补 `MaxUploadSizeExceededException` 处理，返回明确的「图片大小不能超过 5MB」。
   （设计文档坑 4 只提到了要配大小，没预料到**异常也需要单独接**——业务层的 `FileStorageService` 校验代码在这一场景下根本执行不到。）

2. **`shop-rec` 测试代码编译失败**（非本次改动引入，被 Maven 增量编译长期掩盖）：
   `OfflineEvaluator.java` 把 Jedis `zrevrange()` 的 `List<String>` 返回值赋给了 `Set<String>` 变量。
   因本次修改了 `shop-common`（shop-rec 依赖它）触发全量重编而暴露。已修正类型声明（行为不变）。

### 已知债务

- **图片无引用计数清理**：商品逻辑删除后，其图片仍留在磁盘。盲删有风险（可能被其他商品引用），需引入引用表，本次不做
- **无图片压缩/裁剪**：原图直存，大图（<5MB）会直接进前端。生产建议接入压缩
- **本地磁盘不适合多实例**：容器化多副本部署时各节点磁盘不共享，需切 OSS（接口契约已预留）
