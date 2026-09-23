# 商品状态可见性设计与修复记录

> 2026-09-23 · 修复「用户端列表出现已下架商品，点击下单报『商品已下架』」

## 1. 问题现象

用户端首页商品列表出现**已下架 / 待审核**商品，点击进入详情页后点「立即购买」，
后端在 `OrderServiceImpl.createOrder` 校验 `product.status != 1` 时抛出
`BusinessException("商品已下架，xxx")`。

用户直觉是对的：前台只应展示可售商品。

## 2. 根因

`ProductServiceImpl.page()` 只拼装了分类、商家、关键字条件，**没有任何 `status` 过滤**：

```java
// 修复前：无状态条件，全状态商品都会被返回
LambdaQueryWrapper<ProductDO> wrapper = new LambdaQueryWrapper<ProductDO>()
        .orderByDesc(ProductDO::getCreateTime);
```

### 证据链

| 证据 | 结果 |
|------|------|
| 数据库商品状态分布 | 上架 278 / 待审核 12 / 下架 10，共 300 |
| 运行中接口 `GET /api/product/page?pageSize=100` | 返回 100 条中混入 **4 个待审核 + 3 个下架** |
| SQL 等价验证 | 修复前可见 300 → 修复后 278，**拦掉 22 个**不可售商品 |

### 为什么当初会漏

`GET /api/product/page` 被**用户端与管理后台共用**
（`frontend-admin/src/api/product.ts` 走同一路径），而管理后台必须能看到全状态商品
（上下架操作依赖它）。因此该方法的语义一直是"全量查询"，
但用户端需要一个"只看上架"的视图，二者被压在同一个方法里，缺失了区分手段。

**连带缺陷**：管理后台 `Products.vue` 本就在传 `params.status` 做状态筛选
（`if (statusFilter.value !== null) params.status = statusFilter.value`），
但 `ProductPageQuery` 里**根本没有 `status` 字段**，后端的 `page()` 也从不读取它——
即管理后台的状态筛选**一直是失效的**。这说明字段本就是漏加的。

## 3. 硬约束

1. **管理后台必须能看全状态商品** —— 否则无法审核/上下架。
2. **商家端必须能看本店全状态商品** —— 否则看不到自己待审核/已下架的商品。
3. **用户端强制只看上架** —— 且必须是**服务端兜底**，不能只靠前端不传参；
   否则客户端伪造 `status=2` 即可看到下架商品，再点下单又会撞上"商品已下架"。
4. **售罄商品保留展示**（产品决策）—— 标记"已售罄"，不隐藏。
   隐藏需在 SQL 层加 `EXISTS` 子查询（内存过滤会破坏分页准确性），成本与回归风险更高。
5. **已下架商品详情页允许进入**（产品决策）—— 不做 404，改为按钮置灰不可购买，
   避免用户点下去才看到报错。

## 4. 方案设计

### 4.1 按调用方分流，服务端兜底

`page()` 拆出带权限位的内部实现 `doPage(query, canViewAllStatus)`：

| 入口 | canViewAllStatus | 状态行为 |
|------|------------------|----------|
| `page()` 且管理员登录 | `true` | 按 `query.status` 筛选；不传则全状态 |
| `merchantPage()` | `true` | 看本店全状态（`shop_id` 已注入，隔离不依赖状态过滤） |
| `page()` 其余（含未登录） | `false` | **强制 `status = 1`，忽略入参** |

```java
if (canViewAllStatus) {
    if (query.getStatus() != null) {
        wrapper.eq(ProductDO::getStatus, query.getStatus());
    }
} else {
    wrapper.eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF);
}
```

### 4.2 登录态判断静默降级

```java
private boolean isAdminLoginQuietly() {
    try {
        return StpAdminUtil.INSTANCE.isLogin();
    } catch (SaTokenException e) {
        log.debug("无 Sa-Token 上下文（非 Web 线程），商品分页按用户端处理：仅上架可见");
        return false;
    }
}
```

**降级方向是保守的**：取不到上下文时按"用户端"处理，宁可少展示，绝不泄漏下架商品。

## 5. 踩坑记录

### 5.1 Sa-Token 上下文缺失会让接口 500

`StpAdminUtil.INSTANCE.isLogin()` 依赖 Sa-Token 上下文（`SaTokenContextForThreadLocal`）。
在**非 Web 线程**（单元测试、后台线程）调用会抛
`SaTokenContextException: SaTokenContext 上下文尚未初始化`。

首次实现时未做防护，导致 5 个既有分页单测全部 ERROR。
→ 解决：`isAdminLoginQuietly()` 捕获 `SaTokenException` 并降级为 `false`。

### 5.2 单测里断言 wrapper 条件需先初始化 TableInfo

`LambdaQueryWrapper.getSqlSegment()` 要把方法引用解析成列名，依赖 MyBatis-Plus 的
`TableInfo` 缓存；纯 Mockito 测试无 Spring 上下文，会抛
`MybatisPlus can not find lambda cache for this entity`。
→ 解决：`@BeforeEach` 中 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProductDO.class)`（幂等）。
范式抄自 `OrderServiceImplTest`。

### 5.3 条件值是惰性求值的

MyBatis-Plus 的 `eq()` **不会立即**把参数值写入 `paramNameValuePairs`——
必须调用一次 `getSqlSegment()` 触发 SQL 片段构建，否则断言取到空集合，
测试会以"条件不存在"的假象失败。
→ 解决：测试辅助方法 `captureWrapper()` 内先 `wrapper.getSqlSegment()` 再返回。

### 5.4 `status` 是 Integer，不受 Long→String 序列化影响

项目全局把 `Long` 序列化为字符串（`WebConfig.jacksonCustomizer`），
但 `ProductVO.status` / `totalStock` 均为 `Integer`，前端 `status === 1` 比较**是正常的**。
既有文档中"status 被转成字符串"的说法对商品字段不成立（那条描述针对
`total`/`parentId` 这类 `Long` 字段）。本次修复避开了这个误判。

### 5.5 RAG 搜索结果的 `totalStock` 是占位 0

`Home.vue` 的语义搜索结果只映射了 `productId/name/minPrice` 等字段，
`totalStock` 硬编码为 `0`、`skuList` 为空数组。
若"已售罄"角标仅以 `totalStock === 0` 判断，会把**整页搜索结果误标为已售罄**。
→ 解决：角标要求 `skuList` 非空且全部规格库存为 0。

## 6. 改动清单

### 后端

| 文件 | 改动 |
|------|------|
| `model/query/ProductPageQuery.java` | 新增 `Integer status` 字段 |
| `service/impl/ProductServiceImpl.java` | `page()` → `doPage(query, isAdminLoginQuietly())`；新增 `isAdminLoginQuietly()`；`merchantPage()` → `doPage(query, true)` |

### 前端（用户端）

| 文件 | 改动 |
|------|------|
| `api/types.ts` | `ProductPageQuery` 补 `status?: number` |
| `components/ProductCard.vue` | 新增"已售罄"角标（要求 `skuList` 非空）；"在售"角标改为 `v-else-if` |
| `views/ProductDetail.vue` | `canBuy` 增加 `product.status === 1` 判断；新增下架提示条 |

> 管理后台前端**无需改动** —— 它本就在传 `status`，此前被后端忽略，修复后自动生效。

## 7. 验收记录

| 项 | 方式 | 结果 |
|----|------|------|
| 后端编译 | `mvn clean compile` | ✅ EXIT=0 |
| 分页单测 | `mvn test -Dtest='ProductServiceImplTest$PageTest'` | ✅ **8/8 通过**（5 既有 + 3 新增） |
| 新增用例有效性 | 断言捕获 wrapper 的 SQL 条件与参数值 | ✅ 用户端强制 `status=1`（传 2 被忽略）、管理员按 2 筛选、商家端无 status 条件 |
| 全量回归 | `mvn test`（基线对比：stash 改动前后各跑一次） | ✅ **零新增失败**；既有 6 个失败（`AddProductTest`/`GetDetailTest`/`UpdateStatusTest`）修复前后数量一致 |
| 前端构建 | `npm run build` | ✅ built in 10.89s |
| 问题复现 | `GET /api/product/page?pageSize=100`（旧代码） | ✅ 100 条中混入 7 个非上架商品 |
| 过滤效果 | SQL 等价验证 | ✅ 用户端可见 300 → **278**，拦掉 22 个 |

### 遗留（既有缺陷，非本次引入）

`ProductServiceImplTest` 中 `AddProductTest`(3E) / `GetDetailTest`(1F+2E) /
`UpdateStatusTest`(1E) 共 6 个失败**在修复前就存在**，根因是测试类缺少
`BloomFilter<Long>` 与 `RedisCache` 两个 `@Mock`，导致 `@InjectMocks` 注入 null：

- `addProduct` → `productBloomFilter.put(...)` NPE
- `getDetail` → `productBloomFilter.mightContain(...)` NPE
- `updateStatus` → `redisCache.delete(...)` NPE

补 `@Mock` 后 `getDetail_ShouldReturnVO` 还需追加
`when(productBloomFilter.mightContain(100L)).thenReturn(true)` 的 stub（mock 默认返回 false 会走"商品不存在"分支）。

## 8. 需要人工确认的收尾

后端改动需**重启服务**生效（前端 dist 已重建，Nginx 直接生效）。
当前运行中的实例仍是旧代码。
