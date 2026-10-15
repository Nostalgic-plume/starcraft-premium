# 商品图片上传（本地磁盘） — 设计文档

> 2026-10-13 · 分支 `feat/eval-dataset-regen` · 状态：待实施
> 关联：[product-audit-design.md](./product-audit-design.md)

## 一、背景与定位

商品的主图 / 轮播图目前是**手填 URL**（`Products.vue` 的 `form.mainImage`、`form.imagesText`）。mock 数据全部使用外链（`picsum.photos`），所以"填 URL"在演示时可行，但商家实际经营时**无处上传图片**。

本次做一个**本地磁盘上传**闭环：后端收文件 → 落盘 → 返回可访问 URL → 前端贴进商品表单。

> **⚠️ 定位声明：这是本地/单机演示方案。**
> 生产环境必须换成对象存储（OSS/COS/MinIO），原因：单机磁盘无冗余、扩容需停机、多实例部署时各节点磁盘不共享。
> 本方案的接口契约（`POST /api/upload/image` 返回一个 URL 字符串）与 OSS 方案**完全一致**，将来替换只需改 `FileStorageService` 的实现，前端零改动。这是刻意做的抽象。

## 二、目标

1. 后端提供图片上传接口，文件落本地磁盘，返回可直接访问的相对 URL
2. Nginx 能把这些图片作为静态资源对外提供
3. 商家端 / 管理后台的商品表单，把「主图 URL」「轮播图」输入框升级为**上传 + 手填并存**
4. 安全：拒绝非图片、限制大小、防路径穿越

## 三、存储与访问路径设计

```
┌─────────────┐   POST /api/upload/image    ┌──────────────────┐
│  前端表单    │ ──────────────────────────► │  UploadController │
│ (multipart) │ ◄────────────────────────── │  FileStorageService│
└─────────────┘   {"data":"/uploads/..."}   └────────┬─────────┘
                                                     │ 写盘
                                                     ▼
                                       D:/claudecode/shop/uploads/2026/10/13/<uuid>.jpg
                                                     │
                                                     │ docker-compose 挂载
                                                     ▼
                                       Nginx 容器 /usr/share/nginx/html/uploads/
                                                     │
       浏览器 GET /uploads/2026/10/13/<uuid>.jpg ────┘
```

**为什么用相对路径 `/uploads/...` 而不是完整 URL**：
- 前端在局域网 IP、localhost、将来域名下访问都正确，无需改配置
- 与现有 `Long→String` 序列化一样，是"让前端无感知"的思路

## 四、硬约束

| # | 约束 | 说明 |
|---|---|---|
| C1 | **文件名必须重新生成** | 绝不使用用户上传的原始文件名（含 `../`、`..\\`、`%2e%2e` 等路径穿越载荷）。用 `UUID + 白名单扩展名` |
| C2 | **扩展名白名单** | 只允许 `jpg/jpeg/png/gif/webp`。**不看 `Content-Type`** —— 它由客户端伪造，只作辅助 |
| C3 | **大小限制** | 单文件 ≤ 5MB，Service 层校验（Nginx `client_max_body_size 20m` 是外层兜底） |
| C4 | **需要登录** | 上传接口加 `@SaCheckLogin`，不允许匿名上传（否则成了免费图床） |
| C5 | **目录按日期分片** | `uploads/yyyy/MM/dd/`，避免单目录文件过多导致文件系统性能下降 |
| C6 | **路径外置为配置** | `app.upload.dir`（默认 `./uploads`），`app.upload.url-prefix`（默认 `/uploads`），不写死在代码里 |
| C7 | **写盘用绝对路径** | 由配置路径 + 规范化后拼接，并校验最终路径仍在根目录内（防穿越的第二道防线） |
| C8 | **Nginx 只读挂载** | 容器挂载 `:ro`，Nginx 只负责读，写只发生在后端 |

## 五、接口契约

| 方法 | 路径 | 权限 | 入参 | 出参 |
|---|---|---|---|---|
| `POST` | `/api/upload/image` | 需登录（任意 loginType） | `multipart/form-data`，字段名 `file` | `Result<String>`，data 为 `/uploads/2026/10/13/<uuid>.jpg` |

### 错误返回

| 场景 | 提示 |
|---|---|
| 文件为空 | 请选择要上传的文件 |
| 扩展名不允许 | 仅支持 jpg / jpeg / png / gif / webp 格式的图片 |
| 超过大小限制 | 图片大小不能超过 5MB |
| 写盘失败 | 图片上传失败，请稍后重试 |
| 未登录 | 未登录，请先登录（由 `@SaCheckLogin` 统一抛） |

## 六、前端改动

### 商家端 `frontend-merchant/src/views/Products.vue`

| 项 | 现状 | 改成 |
|---|---|---|
| 主图 | `<n-input>` 填 URL | 上传按钮 + 缩略图预览 + 保留手填输入（并存） |
| 轮播图 | textarea 每行一个 URL | 多图上传 + 已上传列表（可删/排序）+ 保留 textarea |

**"上传与手填并存"的理由**：mock 数据全是外链 URL，若只留上传，编辑既有商品时无法保留原外链。两者并存成本极低。

### 管理后台 `frontend-admin/src/views/Products.vue`

同样处理（它的新增表单也有图片字段）。

### 新增共享组件

`shared/js/` 下加一个上传方法（三端可能复用），或各自在 `api/` 里加一个 `uploadImage(file)`。**选择后者**——各端 api 层已有独立的 `request` 实例，抽共享反而增加耦合。

## 七、容易踩的坑

1. **Nginx 挂载路径与后端写盘路径必须是同一个宿主机目录**
   后端跑在**宿主机**（`java -jar`），写 `D:/claudecode/shop/uploads/`；Nginx 跑在**容器**里，需要 `./uploads:/usr/share/nginx/html/uploads:ro` 才能读到。漏了挂载 → 上传成功但图片 404。

2. **`uploads/` 必须加进 `.gitignore`**
   否则商家上传的图片会被提交进仓库。

3. **`MultipartFile.getOriginalFilename()` 不可信**
   Spring 只是把客户端给的名字原样带过来，可含路径。必须只用它取**扩展名**，且扩展名本身要过白名单。

4. **Spring Boot 默认 multipart 限制是 1MB**
   即便 Nginx 放到 20m，Spring 侧 `spring.servlet.multipart.max-file-size` 默认 1MB 会先拒。**必须显式配置**，否则 2MB 的图就传不上去，且报错信息晦涩。

5. **逻辑删除的商品图片是否要清理？**
   本次**不做**（无引用计数，盲删有风险）。在文档标注为已知债务。

6. **上传接口的 loginType**
   三端 loginType 不同（user/shop/admin），上传接口若只写 `@SaCheckLogin` 不带 `type`，Sa-Token 默认校验 `loginType="login"`，而项目里**没有**用这个 loginType 登录过 —— 会永远 401。
   → 必须用 `@SaCheckLogin(type = "shop")` + 另一个 admin 入口，或自定义校验逻辑。**这是最容易踩的一个坑。**

## 八、验收标准

| # | 验收项 | 方式 |
|---|---|---|
| U1 | 上传成功返回 URL | `curl -F "file=@x.jpg" /api/upload/image` |
| U2 | 返回的 URL 能直接访问到图片 | `curl -I http://localhost/uploads/...` → 200 + `image/jpeg` |
| U3 | 非图片被拒 | 传 `.txt` → 提示仅支持图片格式 |
| U4 | 超限被拒 | 传 >5MB 文件 → 提示超限 |
| U5 | 未登录被拒 | 不带 token → 401 |
| U6 | 文件名被重写 | 上传 `../../evil.jpg` → 落盘为 `<uuid>.jpg`，无路径穿越 |
| U7 | 前端能上传并回填 | 商家端表单上传后 URL 自动填入 |
| U8 | 三端构建通过 | `npm run build` |
