# 商品新增 / 编辑补全 — 设计文档

> 2026-10-13 · 分支 `feat/eval-dataset-regen` · 状态：待实施

## 一、问题背景

商品新增功能（`POST /api/merchant/product/add`）只能写入 6 个字段，`description`（描述）、`images`（轮播图）、`detail`（详情富文本）**没有任何入口可以填写**；且系统中**不存在商品编辑接口**，这三个字段一旦建出来就永久为 null，无法补救。

### 实测证据

新增一个商品后，从用户端公开接口 `/api/product/detail/{id}` 读回：

```json
{
  "name": "DIAG-TEST-please-delete",
  "description": null,
  "images": null,
  "detail": null,
  "status": 1
}
```

### 数据证据

全库 303 个商品（`deleted = 0`）：

| 指标 | 数量 | 说明 |
|---|---|---|
| `detail IS NULL` | **3** | 全部创建于 2026-10-13（当天手工测试） |
| `images IS NULL` | **3** | 同上 |
| `description IS NULL` | **3** | 同上 |
| mock 数据（2026-05~07 创建） | 300 | 三字段**均有值** |

即：**凡走「新增商品」功能建出来的商品，三字段必定全空**。

### 影响

- 用户端商品详情页永远显示「暂无详情」（`ProductDetail.vue:149` 的 `product.detail || '暂无详情'`）
- 轮播图区不渲染（`v-if="product.images && product.images.length > 1"` 恒为假）
- 商品详情页只剩主图 + 一句「暂无详情」

### 旁证

`ProductDTO` 的类注释写的是「新增/**修改**商品参数」，说明当初设计时planned 了修改功能，接口只写了一半。

---

## 二、目标与非目标

### 目标

1. **新增可填全字段**：前端表单补齐 `description` / `images` / `detail` 输入并提交
2. **商品信息可编辑**：新增后端编辑接口 + 前端编辑表单解锁
3. **校验补强**：堵住后端三处校验缺口

### 非目标（本次明确不做，避免范围蔓延）

| 事项 | 理由 |
|---|---|
| **商品审核流程** | 属产品决策：`STATUS_PENDING`(0) 目前是死状态，全库无一处写入，仅 mock 数据有 12 条。要真做需配套管理端审核页面，是独立需求，本次只记录不实现 |
| **图片上传** | 现状是 URL 输入框；引入文件上传涉及存储（本地/OSS）、裁剪、防盗链，独立话题 |
| **SKU 规格选择器（规格值 JSON）** | `specs` 字段维持现有文本输入，不做规格矩阵 |
| **商家端状态筛选下推到后端** | `/merchant/product/page` 无 status 参数导致筛选只作用于当前页（`Products.vue:29` 注释自述）。独立小修，本次不动 |

---

## 三、硬约束

| # | 约束 | 说明 |
|---|---|---|
| C1 | **shop_id 数据隔离** | 商家编辑商品必须校验 `product.shopId == 当前登录 shop_id`，非本店商品一律拒绝，不得依赖前端不传参数 |
| C2 | **权限注解** | 商家端接口用 `@SaCheckPermission(value = PRODUCT_EDIT, type = "shop")`；管理端用 `type = "admin"`。注意 Sa-Token 1.45 属性名是 `type` |
| C3 | **详情缓存主动失效** | 任何商品信息变更后必须 `redisCache.delete(CACHE_PRODUCT + id)`，否则用户端看到旧数据（逻辑过期缓存物理永不过期，不删不更新） |
| C4 | **库存缓存失效** | SKU 库存被修改时必须 `stockCacheService.evictStock(skuId)`，采用 Cache Aside 删缓存让读侧重建，禁止双写 SET |
| C5 | **RAG 索引同步** | 商品信息变更后 `productIndexer.addOrUpdate(product)`；非上架状态则 `remove(id)`。用 try-catch 包裹，索引失败不阻断主流程（与 `updateStatus` 一致） |
| C6 | **不改订单快照** | `order_item` 存的是下单时的名称/价格/图片快照，商品编辑**不影响历史订单**；但 SKU 被删后「再次购买」会失败（`MyOrders.vue:162` 用 `skuId` 加购），这是可接受的——下单时后端会校验 SKU 有效性并给出明确错误 |
| C7 | **分类层级重算** | 编辑时若 `categoryId` 变更，`firstCategoryId` 必须跟着重算为新的 `category.getParentId()`，不得沿用旧值 |
| C8 | **images 存储形态** | DB 存 JSON 字符串，DTO/VO 是 `List<String>`，进出都要经 `JSONUtil` 转换（新增逻辑已有，编辑需复用） |

---

## 四、入参 / 出参

### 后端接口

| 方法 | 路径 | 权限 | 入参 | 出参 |
|---|---|---|---|---|
| `PUT` | `/api/merchant/product/{id}` | `product:edit`（shop） | body: `ProductDTO` | `Result<Void>` |
| `PUT` | `/api/product/{id}` | `product:edit`（admin） | body: `ProductDTO` | `Result<Void>` |

> 商家端与管理端**共用同一套 Service 方法**，归属校验在 Service 内按登录态分支：商家校验 shop_id，管理员放行。

### ProductDTO（复用现有 DTO，仅补校验注解）

| 字段 | 类型 | 新增校验 | 说明 |
|---|---|---|---|
| `name` | String | `@NotBlank`（已有） | |
| `description` | String | — | 本次真正可用 |
| `categoryId` | Long | `@NotNull`（已有） | 必须二级分类 |
| `brandId` | Long | — | 可选，传了必须存在 |
| `mainImage` | String | — | |
| `images` | `List<String>` | — | 轮播图，本次真正可用 |
| `detail` | String | — | 富文本，本次真正可用 |
| `skuList` | `List<SkuDTO>` | `@NotNull` → **`@NotEmpty`** | 堵住传 `[]` 建空壳商品 |
| `skuList[].name` | String | `@NotBlank`（已有） | |
| `skuList[].price` | BigDecimal | **`@DecimalMin("0.01")`** | 堵住 0 / 负数价格 |
| `skuList[].originalPrice` | BigDecimal | **`@DecimalMin("0")`** | 可为 0 |
| `skuList[].stock` | Integer | **`@Min(0)`** | 堵住负数库存 |
| `skuList[].id` | Long | 新增字段 | 编辑时标识已有 SKU；新增时为空 |

### SKU 增删改策略（编辑）

前端提交**完整**的 `skuList`：

| 情况 | 后端动作 |
|---|---|
| 带 `id` 且属于该商品 | `UPDATE` 该 SKU 的 name/price/originalPrice/stock/image/specs |
| 带 `id` 但不属于该商品 | 抛 `BusinessException("SKU 不属于该商品")` |
| 不带 `id` | `INSERT`，绑定 `productId` |
| DB 中存在但提交列表未含 | 逻辑删除（`deleted = 1`，不物理删除，保留审计痕迹） |

### 错误返回

统一走 `BusinessException` → `GlobalExceptionHandler` → `Result.error(code, msg)`：

| 场景 | 提示 |
|---|---|
| 商品不存在 | 商品不存在 |
| 商家改他人商品 | 无权操作该商品 |
| 分类不存在 | 分类不存在 |
| 分类非叶子 | 请选择具体的子分类（如「手机」而非「手机数码」） |
| 品牌不存在 | 品牌不存在 |
| SKU 列表为空 | SKU列表不能为空（Bean Validation） |
| 价格非法 | 价格必须大于 0 |
| 库存为负 | 库存不能为负 |
| SKU 越权 | SKU 不属于该商品 |

---

## 五、容易踩的坑

1. **`BeanUtil.copyProperties(dto, product)` 会用 null 覆盖已有值**
   `BeanUtil.copyProperties` 默认**拷贝 null**。编辑时若前端没传 `brandId`，会把原来的品牌清空。
   → 对策：编辑用「选择性拷贝」或逐字段显式赋值 + `null` 判断。**不要**直接复用新增那行的全量拷贝。

2. **`images` 的双向转换**
   DB 是 JSON 字符串、DTO 是 `List<String>`。新增用 `JSONUtil.toJsonStr(dto.getImages())`；**读取回填前端**时要在 `ProductVO` 侧解析。当前 `ProductVO` 是否有解析逻辑需在实施时确认，否则编辑表单回填不了轮播图。

3. **SKU 的 `specs` 字段容易漏**
   `ProductDTO.SkuDTO` 有 `specs`，但**前端提交时没传**（`Products.vue:394-399` 只映射了 4 个字段）。补全时要一并带上，否则编辑 SKU 会把已有 `specs` 清空（踩坑 1 的实例）。

4. **缓存删除漏一处就会「改了看不到」**
   商品信息变更 → 删 `CACHE_PRODUCT`；SKU 库存变更 → 额外 `evictStock(skuId)`。两处都要做。

5. **编辑时 `status` 不应被 DTO 携带**
   `ProductDTO` 没有 `status` 字段（改状态是独立接口 `updateStatus`）。编辑商品信息时**不要**顺手改状态，否则会和 `updateStatus` 的 RAG 索引逻辑打架。

6. **前端 `:disabled="isEdit"` 遍布整个表单**
   编辑解锁要逐项去掉（`Products.vue` 第 63/68/76/84/112/119/127/135 行），漏一个就会出现「有的能改有的不能改」的怪异体验。

7. **`@NotEmpty` 要引对包**
   `jakarta.validation.constraints.NotEmpty`（不是 Hibernate 的实现包），与其他注解保持一致。

8. **RAG 索引重建是异步/可能失败**
   `productIndexer.addOrUpdate` 要包 try-catch，失败了打 warn 日志不抛异常——否则一次索引抖动会让整个商品编辑事务回滚。

---

## 六、验收标准

| # | 验收项 | 方式 |
|---|---|---|
| V1 | 新增商品可填 description/images/detail 并落库 | 实测调接口 + 查 DB |
| V2 | 用户端详情页显示真实详情与轮播图 | `GET /api/product/detail/{id}` 三字段非 null |
| V3 | 编辑接口能改全部 SPU 字段 | 实测 PUT 后回读比对 |
| V4 | 编辑 SKU：改价格 / 新增 / 删除三条路径都正确 | 实测 + 查 DB（删除应为 `deleted=1`） |
| V5 | 商家无法编辑他人商品 | 用 merchant1 改 shop_id≠1 的商品，应返回「无权操作该商品」 |
| V6 | 传空 `skuList` 被拒 | 直接调 API 传 `[]`，应返回校验错误 |
| V7 | 传负价格 / 负库存被拒 | 同上 |
| V8 | 编辑后用户端立即看到新数据（缓存已失效） | 改完立刻查 detail 接口 |
| V9 | 后端编译通过、前端 build 通过 | `mvn compile` + `npm run build` |
