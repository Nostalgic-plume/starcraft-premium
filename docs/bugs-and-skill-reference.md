# Shop 电商系统 — Bug 清单 & Skill 修改参考

> 用于修改 java-base-style / java-springboot-style / frontend-design 等 skill 时参考

---

## 一、后端 Java 问题（影响 java-springboot-style skill）

### 1.1 数据库表结构修复

#### 1.1.1 orders 表缺失商家ID字段

**症状：** 商家后台订单管理无数据，无法按商家隔离订单

**修复 SQL：**
```sql
ALTER TABLE orders ADD COLUMN shop_id BIGINT NOT NULL DEFAULT 0 COMMENT '商家ID' AFTER user_id;
ALTER TABLE orders ADD INDEX idx_shop_id (shop_id);
```

**影响：** OrderDO +shopId，OrderServiceImpl 创建订单时从商品所属商家回填 shop_id

#### 1.1.2 product 表缺失分类层级字段

**症状：** 用户端点击一级分类无法正确筛选该分类下商品，分类联动失效

**原因：** product 只有 `category_id`（二级分类），没有一级分类字段，无法按一级分类批量查商品

**修复 SQL：**
```sql
ALTER TABLE product ADD COLUMN first_category_id BIGINT DEFAULT NULL COMMENT '一级分类ID' AFTER category_id;
ALTER TABLE product ADD COLUMN shop_id BIGINT DEFAULT NULL COMMENT '商家ID' AFTER detail;
ALTER TABLE product ADD INDEX idx_category_ids (category_id, first_category_id);
ALTER TABLE product ADD INDEX idx_shop_id (shop_id);
```

**注意：** 不要加 `second_category_id`！`category_id` 本身已经就是二级分类ID，加 `second_category_id` 会导致字段名与 `category_id` 重复冗余，MyBatis-Plus 查询时列名冲突报语法错误。

**影响：** ProductDO +firstCategoryId +shopId，ProductServiceImpl 新增商品时自动从 `category.parent_id` 回填 first_category_id

#### 1.1.3 cart 表缺失下单状态字段

**症状：** 用户下单后购物车仍然显示已结算商品

**修复 SQL：**
```sql
ALTER TABLE cart ADD COLUMN is_ordered TINYINT NOT NULL DEFAULT 0 COMMENT '是否已下单：0未下单 1已下单' AFTER checked;
```

**影响：** CartDO +isOrdered，CartServiceImpl.list() 加 `.eq(CartDO::getIsOrdered, 0)` 过滤，OrderServiceImpl.createOrder() 下单后批量更新 cart.is_ordered=1

### 1.2 Result<T> 泛型类型推断冲突

**症状：** `Result.success("支付成功")` 在方法返回 `Result<Void>` 时报类型推断错误

**修复：** 新增 `success(String msg)` 重载

```java
public static <T> Result<T> success(String msg) {
    return new Result<>(200, msg, null);
}
```

### 1.3 缺少 import 语句

**症状：** `java: 找不到符号 符号: 类 List`

**原因：** 方法返回 `List<T>` 但缺少 `import java.util.List`

### 1.4 JDK 22 兼容性

**修复：** Spring Boot 3.4.4 + MyBatis-Plus 3.5.16 + Sa-Token 1.45.0 + SpringDoc 2.8.13 + Hutool 5.8.35

### 1.5 Spring Boot 静态资源 404

**修复：** WebConfig 将 `/` 和 `/favicon.ico` 重定向

### 1.6 StatsMapper 缺少 import

**症状：** `java: 找不到符号 符号: 类 DashboardStatsVO`

**原因：** StatsMapper.java 新增 `selectMerchantDashboard(Long shopId)` 返回 `DashboardStatsVO`，但缺少 `import com.shop.model.vo.DashboardStatsVO`

**教训：** 每次在 Mapper 接口中新增返回自定义 VO 的方法后，检查 import 是否完整

### 1.7 MyBatis-Plus 读取已删除列

**症状：** 数据库执行报错 `Unknown column 'second_category_id'`

**原因：** 数据库已执行 `ALTER TABLE product DROP COLUMN second_category_id`，但 `ProductServiceImpl.java` 某处仍用 `ProductDO::getSecondCategoryId`，MyBatis-Plus 通过 Lambda 方法引用反射推导出列名 `second_category_id` 导致 SQL 报错

**修复：** 全局搜 `SecondCategoryId` / `second_category_id`，改为 `getCategoryId` / `category_id`

**教训：** ALTER DROP 列后，必须同步搜索所有 Java 代码中的 Lambda getter 引用，MyBatis-Plus 的 `LambdaQueryWrapper` 不会在编译时报错（是通过运行时反射推导列名），只能靠 grep 搜

### 1.8 雪花 ID 超出 JavaScript 安全整数

**症状：** WHERE 条件传参时 ID 值被四舍五入，DELETE 无效

**原因：** 雪花算法生成的 ID（如 2072182589618548700）超出 `Number.MAX_SAFE_INTEGER`（9007199254740991），MCP 工具传参时被 JS 引擎截断精度

**解决：** 用 `CAST(id AS CHAR)` 做字符串比较

**教训：** JSON 中小于 `-(2^53-1)` 或大于 `2^53-1` 的数值会丢失精度，处理大数 ID 时统一用字符串方式传参

---

## 二、后端架构与接口设计要点

### 2.1 商家数据权限隔离模式

商家端所有接口强制从 `StpUtil.getLoginIdAsLong()` 自动注入 `shop_id`，前端无需传参，防越权：

```java
// MerchantController.java
private Long getShopId() {
    return StpUtil.getLoginIdAsLong(); // 登录时已校验角色为 merchant
}
```

关键校验点：
- `merchantGetDetail(orderId, shopId)`: `shopId.equals(order.getShopId())`
- `merchantDeliver(orderId, shopId)`: 同上
- `merchantPageOrders(page, pageSize, status, shopId)`: WHERE shop_id = ?

### 2.2 商品分类联动查询

```java
// 一级分类 → 查该一级下所有二级分类的商品
if (query.getFirstCategoryId() != null) {
    List<CategoryDO> subCategories = categoryMapper.selectList(
        eq(CategoryDO::getParentId, query.getFirstCategoryId()));
    List<Long> subIds = subCategories.stream().map(CategoryDO::getId).toList();
    if (!subIds.isEmpty()) {
        wrapper.in(ProductDO::getCategoryId, subIds);
    } else {
        wrapper.eq(ProductDO::getFirstCategoryId, query.getFirstCategoryId());
    }
}
```

### 2.3 订单多条件筛选（管理员端）

`AdminController` → `OrderService.adminPageOrders()` 支持：
- status（订单状态）
- shopId（商家ID）
- startTime / endTime（时间范围）
- keyword（订单号/收货人/电话模糊匹配）

### 2.4 购物车下单后标记已结算

OrderServiceImpl.createOrder() 在事务末尾：
```java
for (OrderCreateDTO.OrderItemDTO item : items) {
    CartDO cartItem = cartMapper.selectOne(
        eq(CartDO::getUserId, userId).eq(CartDO::getSkuId, item.getSkuId()).eq(CartDO::getIsOrdered, 0));
    if (cartItem != null) {
        cartItem.setIsOrdered(1);
        cartMapper.updateById(cartItem);
    }
}
```

---

## 三、前端 Vue 问题（影响 frontend-design / 通用 UI 规范）

### 3.1 Element Plus 图标名错误

**症状：** `npm run build` 报错：`"Info" is not exported by "@element-plus/icons-vue"`

**修复：** `Info` → `InfoFilled`，使用图标时优先用 `<el-icon><component :is="iconName" /></el-icon>` 字符串式引用

### 3.2 手机号校验拦截空值

**修复：** 自定义 `validator`，空值时直接 `callback()` 放行（选填字段）

### 3.3 URL 参数未编码

```js
// 错误
router.push('/order/create?items=' + ids)
// 正确
router.push('/order/create?items=' + encodeURIComponent(ids))
```

### 3.4 立即购买信息不全

**原因：** ProductDetail 的 buyNow() 只传了 skuId-quantity，未传商品名称/价格/图片
**修复：** 通过 query 参数传递完整商品信息

### 3.5 模拟支付不用 axios

**原因：** `OrderDetail.vue` 直接用 `fetch('/api/pay/mock-pay?orderNo=xxx')` 不走拦截器
**修复：** 封装到 `orderApi.mockPay(orderNo)` 统一走 axios

### 3.6 购物车乐观更新无回滚

**修复：** 缓存旧数据，catch 时回滚

### 3.7 catch 无参

**修复：** `catch {}` → `catch (e) {}`（无参 catch 里不能 throw）

### 3.8 登录后不按角色跳转

**修复：** 调 `/api/user/role` 获取角色编码 → admin/admin → /admin/，merchant → /merchant/，user → /home

### 3.9 Router base 配置

**修复：** 管理后台和商家中心使用 `createWebHistory('/admin/')` / `createWebHistory('/merchant/')`，否则刷新 404

### 3.10 管理后台订单页面调用 API 不对

**症状：** Admin Orders.vue 调用 `orderApi.myOrders()`（用户端接口），返回空数据

**原因：** 管理员订单需要调用 `/api/admin/order/page`（全平台数据+多条件筛选），而非 `/api/order/my`（仅当前用户）

**修复：** 改为 `request.get('/admin/order/page', { params })`

### 3.11 管理后台商品分类点击不分层级

**症状：** 点击一级分类时只传了 `categoryId`，没有区分一级/二级

**修复：** 点击时判断 `data.parentId`：
- `parentId === 0` → 一级，传 `firstCategoryId`
- `parentId > 0` → 二级，传 `categoryId`

### 3.12 商家后台分类写死

**症状：** Merchant Products.vue 的分类下拉列表是硬编码的 19 个静态选项

**修复：** 改为从 `/api/category/list` 动态加载

### 3.13 商家后台商品 API 不对

**症状：** 商家商品列表调用 `/api/product/page`，没有 shop_id 过滤

**修复：** 改为 `/api/merchant/product/page`（后端自动注入 shop_id）

### 3.14 商家后台订单页面不完整

**症状：** Merchant Orders.vue 调用 `getMyOrders()`（用户端接口），没有发货功能

**修复：** 重写为调用 `/api/merchant/order/*`，新增发货弹窗、订单详情描述

### 3.15 用户商城首页分类查询不对

**症状：** 展开一级分类点击"全部"后，没有传 `firstCategoryId`，查的是所有商品

**修复：** `expandedCategoryId` 不为空时传 `params.firstCategoryId`

### 3.16 用户商城分页组件显示条件不对

**症状：** `v-if="total > pageSize"` 当 total=12 pageSize=12 时不显示分页

**修复：** 总页数取决于 total/pageSize 的倍数关系

### 3.17 用户我的订单页面跳转传参

**症状：** `router.push('/order/' + order.id)` 中的 order.id 在订单ID为雪花大数时丢失

**修复：** 确保始终传数字 ID，路由参数从 `route.params.id` 取

### 3.18 下单后订单ID提取兼容

**症状：** `orderApi.create()` 返回的 data 可能是直接数值、`{id:xxx}` 或 `{orderId:xxx}`

**修复：** 兼容三种格式：
```js
const orderData = res.data ?? res
const orderId = typeof orderData === 'number' ? orderData : (orderData?.id || orderData?.orderId)
```

---

## 四、Nginx 部署问题

### 4.1 Windows 路径带空格

**修复：** 配置路径加双引号：
```nginx
alias "D:/claudecode/shop/frontend-admin/dist/";
```

### 4.2 正则 location 覆盖前缀匹配

**原因：** `location ~* \.(js|css|png|...)$` 优先级高于 `/admin/`、`/merchant/` 前缀 location

**修复：** 删除全局正则缓存，或放在各静态目录内部

### 4.3 缺少 logs 目录和 mime.types

**修复：** 从 Nginx 安装目录复制 mime.types，创建 logs 目录

### 4.4 最佳实践

把配置直接写入 `D:\software\nginx-1.20.2\conf\nginx.conf`，双击 `nginx.exe` 就能启动

---

## 五、数据库数据一致性问题

### 5.1 product.shop_id 和 orders.shop_id 为 NULL 或 0

**原因：** 新增字段后已有数据未回填

**修复：**
```sql
-- 商品绑定商家（所有商品属于 merchant1）
UPDATE product SET shop_id = 10002 WHERE shop_id IS NULL;

-- 分类层级回填（从 category 表 parent_id 推导）
UPDATE product p JOIN category c ON p.category_id = c.id
SET p.first_category_id = c.parent_id
WHERE p.first_category_id IS NULL AND c.parent_id > 0;

-- 订单商家ID回填（从 order_item → product 推导）
UPDATE orders o JOIN (
    SELECT oi.order_id, MIN(p.shop_id) AS shop_id
    FROM order_item oi JOIN product p ON oi.product_id = p.id
    GROUP BY oi.order_id
) t ON o.id = t.order_id
SET o.shop_id = t.shop_id
WHERE o.shop_id = 0 OR o.shop_id IS NULL;
```

### 5.2 雪花 ID JSON 精度丢失

**症状：** 删除大数 ID 行时 MCP 工具传参丢失精度，DELETE 影响 0 行

**解决：** 用 `CAST(id AS CHAR) = 'xxx'` 条件删除

---

## 七、本次大修新增的问题记录（2026-07-01）

### 7.1 Nginx 方案回退：项目内嵌 → 外部独立 Nginx

**变更过程：**
1. **第一版**：外部 Nginx `D:\software\nginx-1.20.2\`，指向 `frontend/*/dist/`
2. **第二版**：项目内嵌 `deploy/nginx.exe`，端口 8088，使用 `deploy/html/` 预构建文件（方便无需前端构建即可运行）
3. **第三版（最终）**：回退到外部 Nginx，端口 80，指向 `frontend/*/dist/`

**回退原因：** 企业级开发要求"源码即真理"，`deploy/html/` 预构建文件与 Vue 源码脱节，修改 Vue 后容易忘记重新同步。

**当前配置：**
```nginx
# 配置位置：D:\software\nginx-1.20.2\conf\nginx.conf
# 启动：cd /d/software/nginx-1.20.2 && ./nginx.exe
# 用户端 → root "D:/claudecode/shop/frontend/dist";
# 管理后台 → alias "D:/claudecode/shop/frontend-admin/dist/";
# 商家中心 → alias "D:/claudecode/shop/frontend-merchant/dist/";
```

**关键点：**
- 前端必须 `npm run build` 后 Nginx 才能提供服务
- HTML 加 `add_header Cache-Control "no-cache, must-revalidate";` 确保新构建立即生效
- JS/CSS 文件 hash 变化自动更新，强缓存 7 天

### 7.2 订单表加物流单号字段

**症状：** 商家发货后物流单号丢失（代码接收了参数但没存库）

**修复 SQL：**
```sql
ALTER TABLE orders ADD COLUMN logistics_no VARCHAR(100) DEFAULT '' COMMENT '物流单号' AFTER deliver_time;
```

**代码影响：** `OrderDO +logisticsNo`，`OrderServiceImpl.merchantDeliver()` 加 `order.setLogisticsNo(logisticsNo)`

### 7.3 全局 Long → String 序列化（根治雪花ID精度问题）

**症状：** 雪花ID（如 2072224811785306000）超出 JS `Number.MAX_SAFE_INTEGER`，JSON 解析时精度丢失，导致"订单不存在"等连锁问题

**修复：**
```java
// WebConfig.java
@Bean
public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
    return builder -> {
        builder.serializerByType(Long.class, ToStringSerializer.instance);
        builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
    };
}
```

**效果：** 前端收到的所有 Long 字段均为字符串，后端自动反序列化为 Long。无需在每处手动 `CAST(id AS CHAR)`

**注意：** 前端 `route.params.id` 取到的是字符串，传给后端 API 时 URL path 参数自动匹配。`orderApi.detail(route.params.id)` 正常。

### 7.4 模拟支付参数对齐

**症状：** 前端传 `{ orderId, payType }`（request body），后端接收 `@RequestParam String orderNo`（query param），字段名也不一致

**修复：**
```js
// order.js
mockPay(orderNo) {
  return request.post('/pay/mock-pay', null, { params: { orderNo, payType: 1 } })
}
```

**OrderDetail.vue 调用：** `orderApi.mockPay(order.value.orderNo)`（传 orderNo 而非 id）

### 7.5 订单详情页图标未注册

**症状：** 订单详情页顶部状态图标不显示（使用 `<component :is="statusIcon" />` 但图标未 import）

**修复：** 在 script 中显式 import 所有用到的图标：
```js
import { Warning, CircleCheck, InfoFilled, Success, CircleClose, Timer, Location, Goods } from '@element-plus/icons-vue'
```

### 7.6 商品新增分类层级校验

**症状：** 选择一级分类（如"手机数码"）新增商品时，`categoryId` 设置为一级分类ID，但数据库二级分类字段 NOT NULL，插入报错

**修复：** `ProductServiceImpl.addProduct()` 增加校验，拒绝一级分类：
```java
if (category.getParentId() == null || category.getParentId() == 0) {
    throw new BusinessException("请选择具体的子分类（如「手机」而非「手机数码」）");
}
```

**前端联动：** 管理后台和商家中心的商品新增/分类下拉，均改为只显示二级分类

### 7.7 user.status 注释修正

**变更：** `user.status` 数据库注释从"0正常 1禁用"改为"0正常 1停用 2注销中 3封禁中"，与实际代码逻辑一致

### 7.8 唯一索引补全

**变更：** `role_permission` 和 `user_role` 表各加联合唯一索引，防止重复关联数据

### 7.9 字段命名统一

**变更：** `order_item.product_image` → `product_main_image`（与 product.main_image 对应），通过 `@JsonProperty("productImage")` 保持前端兼容

### 7.10 MySQL 连接 Public Key Retrieval 错误

**症状：** Hikari 连接池初始化失败，报 `Public Key Retrieval is not allowed`

**原因：** MySQL 8.0+ 使用 `caching_sha2_password` 加密，JDBC 驱动默认不允许从服务端获取公钥

**修复：** JDBC URL 追加 `&allowPublicKeyRetrieval=true`

### 7.11 Jackson Long→String 序列化导致前端 parentId 比较失效

**症状：** 分类树不显示一级分类，`topCategories` 始终为空数组

**原因：** 全局 Jackson Long→String（7.3 解决雪花ID精度）导致 `parentId` 从 `0` 变为 `"0"`，`=== 0` 永远 false

**修复：** 所有 `parentId` 比较改为 `== 0`

### 7.12 分页 total 为字符串不显示

**症状：** 列表无分页组件，API 返回 `total: "50"`

**原因：** Long→String 序列化，`total > pageSize` 字符串比较异常

**修复：** `total.value = Number(res.data?.total) || 0` + 模板 `:total="Number(total)"`

### 7.13 管理后台响应数据解构不一致

**症状：** Dashboard/Users/Products 数据空白，统计显示 `0`

**原因：** 响应拦截器返回 `{code,data,msg}`，页面直接取 `res.list` 而非 `res.data.list`

**修复：** 统一 `res.data?.list` / 图表数据 `.data || []`

### 7.14 前端统一品牌设计语言重构

**变更：** 三端统一品牌色 `#1a1a2e` 深墨蓝 + `#e94560` 珊瑚红，暖灰背景 `#f5f3ef`，16px 圆角

### 7.15 ECharts 全面集成

**新增：** ProductDetail 价格走势图、管理后台 4 图、商家中心 4 图（深墨蓝/珊瑚红/金色配色）

---

## 八、生成代码时的 checklist（用于 skill 强制规则）

每次生成代码时检查：

### 数据库相关
- [ ] ALTER TABLE 新增字段后检查已有数据是否需要回填
- [ ] DROP COLUMN 后全局 grep 确保代码中无 Lambda getter 引用
- [ ] 声明式 SQL（@Select）返回自定义 VO 时检查 import
- [ ] 雪花大数传参考虑 JSON 精度问题

### 后端相关
- [ ] 新增 Controller 接口后检查权限注解（@SaCheckPermission）
- [ ] 商家端接口必须自动注入 shop_id（禁止前端传参）
- [ ] 订单创建要绑定 shop_id + 标记购物车 is_ordered
- [ ] 分类查询区分一级/二级

### 前端相关
- [ ] 图标使用前确认在 `@element-plus/icons-vue` 中真实存在
- [ ] URL 参数用 `encodeURIComponent()`
- [ ] 表单校验：选填字段用 `validator` 放行空值
- [ ] axios 请求统一走 api 模块，不要直接 `fetch()`
- [ ] 乐观更新必须有回滚逻辑
- [ ] catch 必须有参数 `(e)`
- [ ] 登录后考虑角色跳转
- [ ] SPA 路由用 `createWebHistory()` + Nginx `try_files` fallback
- [ ] 部署路径含空格时路径加引号
- [ ] 分类树点击时判断 parentId 区分一级/二级筛选
- [ ] 管理后台 API 调用 `/api/admin/*` 非 `/api/order/my`
- [ ] 商家 API 调用 `/api/merchant/*` 非 `/api/*`
- [ ] 全局 Long 序列化为字符串（WebConfig 加 Jackson2ObjectMapperBuilderCustomizer）
- [ ] axios POST 请求传 query param 用 `{ params }`，慎用 request body
- [ ] Element Plus 图标必须在 script 中显式 import
- [ ] 新增商品时校验分类层级（禁止选一级分类）
- [ ] catch 中用户取消操作（ElMessageBox）与真实错误区分处理
- [ ] 分页组件显示条件用 `total > 0` 而非 `total > pageSize`
- [ ] ALTER 新增字段后需回填已有数据（shop_id、first_category_id、is_ordered、logistics_no）
- [ ] 商家发货操作必须存 logistics_no 到数据库
- [ ] Jackson Long→String 序列化后，前端比较数值字段用 `==` / `Number()` 而非 `===`
- [ ] API total 是字符串，前端取 `Number(res.data?.total)`
- [ ] 管理后台所有 API 解构 `.data?.xxx`
- [ ] 三端保持统一品牌色系（`--ds-navy` / `--ds-coral` / `--ds-bg`）
- [ ] 新增 ECharts 图表时使用品牌配色常量（NAVY / CORAL / GOLD / TEAL）
