# 商品新增 / 编辑补全 — 计划书

> 配套设计文档：[product-edit-design.md](./product-edit-design.md)
> 2026-10-13 · 状态：**已实施并通过验收**（验收记录见文末）

## 任务分解

按**依赖关系**排序：T1→T2→T3 是后端的一条链（必须先改 DTO 才能实现 Service），T4 依赖 T3；T5→T6 是前端一条链，依赖 T4 的接口契约；T7/T8 收尾。

---

### T1 · 后端：DTO 校验补强 + SKU 加 id 字段

**文件**：`shop-server/src/main/java/com/shop/model/dto/ProductDTO.java`

**改动**：
- `skuList`：`@NotNull` → `@NotEmpty`
- `SkuDTO.price`：加 `@DecimalMin(value = "0.01", message = "价格必须大于 0")`
- `SkuDTO.originalPrice`：加 `@DecimalMin(value = "0", message = "原价不能为负")`
- `SkuDTO.stock`：加 `@Min(value = 0, message = "库存不能为负")`
- `SkuDTO` 新增 `private Long id;`（编辑时标识已有 SKU）

**验收**：`mvn compile` 通过；直接调 API 传 `"skuList": []` 返回 400 校验错误。

---

### T2 · 后端：ProductService 接口加 updateProduct

**文件**：`shop-server/src/main/java/com/shop/service/ProductService.java`

**改动**：新增方法声明
```java
void updateProduct(Long id, ProductDTO dto);
```
含完整 Javadoc（复用设计文档「错误返回」表）。

**验收**：`mvn compile` 通过（此时因无实现会编译失败，与 T3 一起验）。

---

### T3 · 后端：ProductServiceImpl 实现 updateProduct

**文件**：`shop-server/src/main/java/com/shop/service/impl/ProductServiceImpl.java`

**逻辑**：
1. 查商品，不存在抛「商品不存在」
2. **归属校验**：`StpShopUtil` 登录时校验 `product.shopId == loginId`，不符抛「无权操作该商品」；`StpAdminUtil` 登录时放行
3. 校验分类存在 + 为叶子分类，重算 `firstCategoryId`
4. 校验品牌存在（传了才校验）
5. **选择性更新 SPU**（避开 `BeanUtil` 拷 null 的坑）：name / description / categoryId / firstCategoryId / brandId / mainImage / images / detail
6. **SKU diff**：按 `id` 分三类走 update / insert / 逻辑删除；库存有变的调 `evictStock`
7. `redisCache.delete(CACHE_PRODUCT + id)`
8. RAG 索引 `addOrUpdate`（try-catch 包裹）

**验收**：`mvn compile` 通过。

---

### T4 · 后端：暴露编辑接口

**文件**：
- `shop-server/src/main/java/com/shop/controller/MerchantController.java` → `PUT /api/merchant/product/{id}`，`@SaCheckPermission(PRODUCT_EDIT, type="shop")`
- `shop-server/src/main/java/com/shop/controller/ProductController.java` → `PUT /api/product/{id}`，`@SaCheckPermission(PRODUCT_EDIT, type="admin")`

**验收**：后端启动，`/swagger-ui.html` 能看到两个新接口；`curl` 调通。

---

### T5 · 前端：API 层

**文件**：
- `frontend-merchant/src/api/merchant.ts` → 加 `productUpdate(id, data)`
- `frontend-merchant/src/api/types.ts` → `ProductForm` 补 `description` / `images` / `detail`；SKU 项补 `id` / `specs`

**验收**：`npm run build` 通过（类型检查会覆盖）。

---

### T6 · 前端：表单补字段 + 编辑解锁

**文件**：`frontend-merchant/src/views/Products.vue`

**改动**：
- 表单新增三项：商品描述（textarea）、轮播图（textarea 每行一个 URL）、商品详情（textarea）
- 逐项去掉编辑态的 `:disabled="isEdit"`（第 63/68/76/84/112/119/127/135 行）
- `openEdit` 回填新字段（含 `images` 数组 ↔ 文本互转）
- `handleSubmit` 编辑分支：改调 `productUpdate` 而非只更新状态
- 移除「后端暂未开放商品信息编辑接口」提示条

**验收**：`npm run build` 通过；浏览器实操新增 + 编辑各一次。

---

### T7 · 验收实测（对照设计文档 V1–V9）

| # | 验收项 | 通过标准 |
|---|---|---|
| V1 | 新增可填三字段 | DB 查 `detail`/`images`/`description` 非 null |
| V2 | 用户端详情正常 | `/api/product/detail/{id}` 三字段非 null |
| V3 | 编辑改全部 SPU 字段 | PUT 后回读比对一致 |
| V4 | SKU 增删改三路径 | 改价格生效；新增 SKU 落库；删除 SKU `deleted=1` |
| V5 | 越权被拒 | merchant1 改他人商品返回「无权操作该商品」 |
| V6 | 空 skuList 被拒 | 返回校验错误 |
| V7 | 负价格 / 负库存被拒 | 返回校验错误 |
| V8 | 缓存即时失效 | 改完立刻查 detail 看到新值 |
| V9 | 编译 / 构建 | `mvn compile` + `npm run build` 双绿 |

**清理**：实测产生的测试商品全部逻辑删除（`deleted=1`）；用户此前遗留的 3 个测试商品（ID `2109920113522548737` / `2109920390929620993` / `2109932352455483393`）一并清理。

---

### T8 · 文档收尾

- `CLAUDE.md` 的「API 概览 → 商家端」表补上 `PUT /api/merchant/product/{id}`
- `CLAUDE.md` 的「管理后台」表补上 `PUT /api/product/{id}`
- 在本计划书状态改为「已实施」，附验收记录

---

## 分批与执行顺序

```
批次一（后端，串行）：T1 → T2 → T3 → T4
批次二（前端，串行）：T5 → T6
批次三（验收）：      T7 → T8
```

批次一与批次二**理论可并行**（接口契约在 T4 已定），但单人执行时按序更稳，避免契约漂移。

## 风险与回退

| 风险 | 应对 |
|---|---|
| `BeanUtil` 拷 null 导致编辑清空字段 | 用选择性赋值，T7 的 V3 专门验这个 |
| SKU diff 误删 | 只做逻辑删除，数据可恢复；V4 验证 |
| 前端 `ProductVO` 未解析 `images` JSON 串 | 实施 T6 时先确认，若无解析则补在 VO 侧 |
| 编辑接口影响 RAG 索引 | try-catch 包裹，索引失败不影响主流程 |

---

## 验收记录（2026-10-13 实际执行）

后端重新打包并重启（jar 时间戳 17:53:18），商家中心前端已 rebuild。

| # | 验收项 | 实测结果 | 判定 |
|---|---|---|---|
| V1 | 新增可填三字段 | `POST /api/merchant/product/add` 传 description/images/detail，落库成功 | ✅ |
| V2 | 用户端详情正常 | `GET /api/product/detail/{id}` 返回 `description:"desc-check"`、`images:[2项]`、`detail:"<p>detail-check</p>"` | ✅ |
| V3 | 编辑改全部 SPU 字段 | PUT 后回读：name / description / images / detail / mainImage 全部为新值 | ✅ |
| V4 | SKU 增删改三路径 | 改价 68.5→88.8、库存 50→30 生效；新增 `pro` 拿到新 ID；删除后 DB 中 `pro.deleted=1`、`standard-v2.deleted=0` | ✅ |
| V5 | 越权被拒 | merchant1 改商品 2（shop_id=3）→ 返回「商品不存在」 | ✅ |
| V6 | 空 skuList 被拒 | `skuList: SKU列表不能为空` | ✅ |
| V7 | 负价格 / 负库存被拒 | `skuList[0].price: 价格必须大于 0` / `skuList[0].stock: 库存不能为负` | ✅ |
| V8 | 缓存即时失效 | 编辑后立即回读即为新值 | ✅ |
| V9 | 编译 / 构建 | `mvn package` + `npm run build` 双绿 | ✅ |

### V5 的实测偏差（记录备查）

设计文档预期越权返回「无权操作该商品」，**实际返回「商品不存在」**。原因：`ShopDataIsolationInterceptor`
在商家会话下给 `product` 表的 SELECT 自动追加了 `AND shop_id = ?`，`selectById(2)` 在 SQL 层就查不到记录，
根本没走到 Service 层的归属校验分支。

**这个行为比预期更好**：攻击者无法区分「商品不存在」与「不是你的商品」，不泄露他人商品是否存在。
Service 层保留的 `Objects.equals(product.getShopId(), shopId)` 校验作为第二道防线，在拦截器白名单
（`SKIP_MAPPERS`）豁免的 SQL 场景下仍然生效。

### 遗留说明

- `addProduct` 未做 SKU 库存预热，依赖 `StockCacheService.tryDeduct` 的 DB 懒加载兜底（Lua 返回 -1 时回源）；
  编辑路径行为一致，非缺陷
- 商品审核流程（`ProductConstants.STATUS_PENDING`）仍未启用，属独立产品决策，本次未动
- 商家端状态筛选仍在前端做（`/merchant/product/page` 无 status 参数），本次未动
