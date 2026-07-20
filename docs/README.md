# Shop 电商系统

一个完整的 B2C 电商系统，**单体后端 + 三端分离前端**，Nginx 托管静态资源并反代 API。

| 组件 | 技术栈 | 版本 |
|------|--------|------|
| 后端 | Spring Boot + MyBatis-Plus + Sa-Token | 3.4.4 / 3.5.5 / 1.45.0 |
| 数据库 | MySQL 8 + Redis | — |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + ECharts | 5.x |
| 反向代理 | Nginx | 1.20.2 |
| JDK | OpenJDK 22 | 22.0.1 |
| Mock 工具 | 纯 Java（独立项目） | Hutool JDBC |

---

## 项目架构

```
D:/claudecode/shop/
├── pom.xml                         # Maven 父项目
├── CLAUDE.md                       # Claude Code 指引
├── docs/
│   ├── README.md                     # 本文档
│   └── bugs-and-skill-reference.md   # Bug 清单 & Skill 参考
│
├── deploy/                           # 内嵌 Nginx + 预构建静态文件
│   ├── nginx.exe                     # Nginx 可执行文件
│   ├── start.bat                     # 一键启动脚本
│   ├── stop.bat
│   ├── mime.types
│   ├── conf/
│   │   └── nginx.conf                # Nginx 配置（端口 8088）
│   ├── logs/                         # 运行时日志（自动生成）
│   ├── temp/                         # 运行时临时文件（自动生成）
│   └── html/
│       ├── admin/                    # 管理后台预构建文件
│       ├── merchant/                 # 商家中心预构建文件
│       └── user/                     # 用户端预构建文件
│
├── src/main/java/com/shop/
│   ├── ShopApplication.java        # 启动类（含 @EnableScheduling）
│   ├── controller/                 # REST 接口层
│   │   ├── AdminController.java    # 管理员（用户/订单管理）
│   │   ├── AdminStatsController.java # 管理员统计看板
│   │   ├── MerchantController.java # 商家（订单/商品，shop_id 隔离）
│   │   ├── OrderController.java    # 用户端订单
│   │   ├── ProductController.java  # 商品浏览/管理
│   │   ├── CartController.java     # 购物车
│   │   ├── CategoryController.java # 分类列表
│   │   ├── UserController.java     # 注册/登录/信息
│   │   └── PaymentController.java  # 支付回调
│   ├── service/ + impl/            # 业务接口与实现
│   ├── mapper/                     # MyBatis-Plus Mapper
│   ├── model/
│   │   ├── entity/                 # 数据库实体（DO）
│   │   ├── dto/                    # 请求参数
│   │   ├── vo/                     # 返回视图
│   │   └── query/                  # 分页查询参数
│   ├── common/                     # Result, PageResult, BusinessException, StpInterfaceImpl
│   ├── config/                     # 全局配置/异常处理/定时任务
│   └── constant/                   # 枚举/常量
│
├── frontend/                       # 用户端 Vue 3（用户商城）
├── frontend-admin/                 # 管理后台 Vue 3（管理员面板）
└── frontend-merchant/              # 商家中心 Vue 3（商家面板）

D:/claudecode/shop-data-mocker/          # Mock 数据工具（纯 Java，独立项目）
D:/claudecode/shop-dashboard/            # 数据看板后端（Spring Boot，port 8081）
D:/software/nginx-1.20.2/conf/nginx.conf # 外部 Nginx 配置（端口 80，生产用）
```

### 架构模式

```
用户浏览器 → Nginx (:8088 内嵌 / :80 生产)
               ├── /api/dashboard/* → 反向代理 → 看板后端 (:8081) → shop_ads 数据库
               ├── /api/* → 反向代理 → 主后端 (:8080) → shop 数据库
               ├── /admin/* → 管理后台 SPA（alias → html/admin/）
               ├── /merchant/* → 商家中心 SPA（alias → html/merchant/）
               └── /* → 用户端 SPA（root → html/user/）
```

Nginx 前缀匹配按**最长匹配优先**：
- `/api/dashboard/` 比 `/api/` 更具体，dashboard 请求优先匹配到看板后端（:8081）
- `/admin/`、`/merchant/` 比 `/` 更具体，分别映射到各自前端目录

---

## 部署方式

### 方式一：内嵌 Nginx（deploy/ 目录 — 开发/演示）

项目 `deploy/` 目录包含独立 Nginx + 预构建静态文件，双击 `start.bat` 即可：

| 文件 | 作用 |
|------|------|
| `nginx.exe` | Nginx 可执行文件（Windows 1.20.2） |
| `conf/nginx.conf` | 监听端口 8088，三端前端 + API 反向代理 + 静态缓存 |
| `start.bat` | 一键启动：停止旧 Nginx → 启动 Nginx → `mvn spring-boot:run` |
| `stop.bat` | 优雅停止 Nginx |
| `html/{admin,merchant,user}/` | 三端前端构建产物（`npm run build` 输出） |

**启动：**

```bash
# 方式 A：双击 deploy/start.bat
# 方式 B：手动启动
cd "D:/claudecode/shop"
mvn spring-boot:run                    # 终端 1：后端
"D:/claudecode/shop/deploy/nginx.exe" \
  -c "D:/claudecode/shop/deploy/conf/nginx.conf" \
  -p "D:/claudecode/shop/deploy"       # 终端 2：Nginx
```

**重载配置：**

```bash
"D:/claudecode/shop/deploy/nginx.exe" \
  -c "D:/claudecode/shop/deploy/conf/nginx.conf" \
  -p "D:/claudecode/shop/deploy" -s reload
```

> **注意：** Windows 上 `-s reload` 可能残留旧进程。若配置更改后不生效，先用 `taskkill /f /im nginx.exe` 完全杀掉再重启。

### 方式二：外部 Nginx（:80 — 生产）

生产环境使用独立安装的 Nginx（`D:/software/nginx-1.20.2/`），配置内容与内嵌一致但监听 80 端口。

---

## Nginx 配置要点

### 三端 SPA 共存

三个 Vue 3 前端共用同一域名，通过路径前缀区分：

```nginx
# 三端的 base 配置：
# 用户端   → vite.config.js base: '/'           → HTML 引用 /assets/*
# 管理后台 → vite.config.js base: '/admin/'     → HTML 引用 /admin/assets/*
# 商家中心 → vite.config.js base: '/merchant/'  → HTML 引用 /merchant/assets/*
```

### 静态资源缓存

每个前端使用独立的 `location ~*` 正则匹配 assets：

| 前端 | Nginx 规则 | 磁盘路径 |
|------|-----------|---------|
| 用户端 | `~* ^/assets/.*\.(js\|css\|...)$` + `root html/user` | `html/user/assets/` |
| 管理后台 | `~* ^/admin/assets/.*\.(js\|css\|...)$` + `root html` | `html/admin/assets/` |
| 商家中心 | `~* ^/merchant/assets/.*\.(js\|css\|...)$` + `root html` | `html/merchant/assets/` |

> **说明：** 正则（`~*`）location 优先于前缀 location。用户端 `/assets/*` 请求虽然也匹配 `location /`，但正则优先级更高，由缓存规则处理。管理后台和商家中心的 alias 块 + 正则缓存使用 `root`（而非 `alias`），因为正则 location 用完整 URI 拼路径。

### SPA 回退

每个前端都有 `try_files $uri $uri/ /index.html`（或 `/admin/index.html`、`/merchant/index.html`），确保前端路由刷新时不 404。

### Windows 兼容

```nginx
sendfile      off;   # Windows 上 sendfile 可能导致大文件 404，必须关闭
```

---

## 快速开始

### 1. 环境准备

确保 MySQL（localhost:3306）和 Redis（localhost:6379）已启动，数据库 `shop` 已创建。

### 2. 设置 JDK

```bash
export JAVA_HOME="C:/Users/26373/.jdks/openjdk-22.0.1"
export PATH="$JAVA_HOME/bin:D:/software/maven/apache-maven-3.9.9/bin:$PATH"
```

### 3. 初始化 Mock 数据

```bash
cd "D:/claudecode/shop-data-mocker"
mvn compile exec:java -Dexec.mainClass="com.shop.mocker.DataMockerApplication"
```

### 4. 运行

```bash
# 终端 1：启动后端（或在 IntelliJ 直接 Run ShopApplication）
cd "D:/claudecode/shop" && mvn spring-boot:run

# 终端 2：启动 Nginx 内嵌服务
cd "D:/claudecode/shop/deploy" && ./nginx.exe -c conf/nginx.conf -p .
```

### 5. 访问

| 地址 | 页面 | 默认账号 | 密码 |
|------|------|---------|------|
| http://localhost:8088 | 🛍️ 用户商城 | zhangsan / lisi | 123456 |
| http://localhost:8088/admin/ | 📊 管理后台 | admin（超级管理员） | 123456 |
| http://localhost:8088/merchant/ | 🏪 商家中心 | merchant1 | 123456 |

> 生产环境通过外部 Nginx（:80）访问，地址去掉端口号。

---

## 业务模块

| 模块 | 关键类 | 核心逻辑 |
|------|--------|----------|
| **用户** | UserController / UserServiceImpl | BCrypt 加密、Sa-Token 登录、注册自动绑 user 角色、4 种账号状态 |
| **商品** | ProductController / ProductServiceImpl | SPU + SKU 分层、一级/二级分类联动（firstCategoryId）、shop_id 隔离、状态管理 |
| **分类** | CategoryController | 二级分类树 + 按父级查子分类（`/api/category/children`） |
| **订单** | OrderController / OrderServiceImpl | Redis 防重令牌 → MySQL 行锁扣库存 → shop_id 绑定 → 购物车标记已下单 → 幂等支付回调 → 库存回滚取消 → 30 分钟后超时自动取消 |
| **购物车** | CartController / CartServiceImpl | SKU 级别操作、is_ordered 过滤、库存校验 |
| **支付** | PaymentController | 模拟回调、幂等处理 |
| **管理员** | AdminController / AdminStatsController | 用户管理、状态变更日志、全平台订单查询、控制台 ECharts |
| **商家** | MerchantController | **shop_id 自动注入（从 Sa-Token）**、订单管理（含发货）、商品管理、本店数据统计 |
| **数据看板** | DataDashboard.vue + report/ 组件 | 独立后端（:8081）查询 shop_ads 库、6 组 ECharts 组件构成的全屏看板、非实时报表 |

---

## 管理后台路由结构

```
/login                    → Login.vue（管理员登录）
/dashboard                → Layout.vue（侧边栏布局）
  ├── /dashboard          → Dashboard.vue（控制台：5 统计卡 + 4 ECharts 图表）
  ├── /dashboard/products → Products.vue（商品管理 CRUD）
  ├── /dashboard/orders   → Orders.vue（订单管理全平台查询）
  ├── /dashboard/users    → Users.vue（用户管理 + 状态变更）
  └── /dashboard/report   → DataDashboard.vue（数据看板：6 组报表组件）
```

---

## 账号与权限

### 三级角色 + 两级管理员

| 角色 | 权限编码 | 登录端 | 说明 |
|------|---------|--------|------|
| **超级管理员** (admin_level=2) | product:add/edit/delete/status, order:all, user:manage | 管理后台 | 可操作所有账号 |
| **普通管理员** (admin_level=1) | 同上 | 管理后台 | 不可操作超级管理员 |
| **商家** (merchant) | product:add/edit/status | 商家中心 | **数据权限隔离**：仅管理自身商品和订单（shop_id），后端自动注入 |
| **用户** (user) | 无管理权限 | 用户端 | 浏览/下单/购物车 |

### 账号状态

| 值 | 名称 | 登录行为 |
|----|------|---------|
| 0 | 正常 | ✅ 允许登录 |
| 1 | 停用 | ❌ "账号已被停用" |
| 2 | 注销中 | ❌ "账号正在注销中" |
| 3 | 封禁中 | ❌ "账号已被封禁" |

状态变更记录到 `user_status_log` 表（操作人、变更前后状态、原因）。

---

## API 接口文档

### 公开接口（无需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/user/register | 用户注册 |
| POST | /api/user/login | 用户登录 |
| GET | /api/product/page | 商品分页（支持 categoryId/firstCategoryId/keyword） |
| GET | /api/product/detail/{id} | 商品详情（含 SKU） |
| GET | /api/category/list | 全部分类列表（平铺） |
| GET | /api/category/children?parentId= | 指定父级下的子分类 |
| POST | /api/pay/callback | 支付回调 |

### 用户端接口（需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/user/info | 当前用户信息 |
| GET | /api/user/role | 当前用户角色列表 |
| POST | /api/user/logout | 退出登录 |
| GET | /api/order/token | 下单防重令牌（Redis 5 分钟） |
| POST | /api/order/create | 创建订单（含 shop_id 绑定 + 购物车标记） |
| GET | /api/order/my?page=&pageSize=&status= | 我的订单（分页+状态筛选） |
| GET | /api/order/detail/{id} | 订单详情（含商品快照，归属校验） |
| POST | /api/order/cancel/{id} | 取消订单（仅待支付，回滚库存） |
| POST | /api/order/receive/{id} | 确认收货 |
| GET | /api/cart/list | 购物车列表（自动过滤 is_ordered=1） |
| POST | /api/cart/add | 添加商品 |
| PUT | /api/cart/quantity/{skuId}?quantity= | 修改数量 |
| DELETE | /api/cart/remove/{skuId} | 删除购物车项 |
| PUT | /api/cart/check/{skuId}?checked= | 选中/取消 |
| POST | /api/pay/mock-pay | 模拟支付 |

### 管理后台接口（需 `@SaCheckPermission`）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | /api/admin/users | user:manage | 用户分页（keyword/status） |
| PUT | /api/admin/user/{id}/status | user:manage | 修改用户状态（含日志+权限校验） |
| PUT | /api/admin/user/batch-status | user:manage | 批量修改状态 |
| GET | /api/admin/order/page | order:all | 订单分页（status/shopId/keyword/startTime/endTime） |
| GET | /api/admin/order/detail/{id} | order:all | 订单详情（全平台可查） |
| GET | /api/admin/stats/dashboard | — | 控制台概览 |
| GET | /api/admin/stats/sales-trend?days= | — | 销售额趋势 |
| GET | /api/admin/stats/product-sales | — | 商品销量排行 TOP10 |
| GET | /api/admin/stats/user-growth | — | 用户增长趋势 |
| GET | /api/admin/stats/order-distribution | — | 订单状态分布 |

### 商家端接口（shop_id 自动注入，无需传参）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/merchant/stats/dashboard | 本店控制台 |
| GET | /api/merchant/order/page?status= | 本店订单分页 |
| GET | /api/merchant/order/detail/{id} | 本店订单详情（shop_id 校验） |
| POST | /api/merchant/order/deliver | 发货（shop_id 校验） |
| GET | /api/merchant/product/page | 本店商品列表（自动 shop_id 过滤） |
| POST | /api/merchant/product/add | 新增商品（自动绑定本店） |
| PUT | /api/merchant/product/status/{id}?status= | 更新商品状态 |

### 数据看板接口（独立后端 :8081）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/dashboard/all | 获取数据看板全部 9 类数据 |

**返回结构：**

```json
{
  "overview": { "totalGmv", "totalOrders", "totalUsers", "totalProducts", "totalShops" },
  "gmvDaily": [ { "date", "gmv" } ],
  "orderStats": [ { "status", "count" } ],
  "categorySales": [ { "categoryName", "salesAmount" } ],
  "productTop": [ { "productName", "salesAmount", "salesCount" } ],
  "weeklyTrend": [ { "dayOfWeek", "gmv", "orderCount" } ],
  "userActive": { "dau", "orderUserCount" },
  "userNewRetain": { "newUsers", "day1RetainUsers", "day1RetainRate", "day7RetainUsers", "day7RetainRate", "day30RetainUsers", "day30RetainRate" },
  "shopDaily": { "shopName", "gmv", "orderCount", "productCount" }
}
```

**说明：**
- `/api/dashboard/*` 由 Nginx 代理转发到 `:8081`（独立 Spring Boot 看板后端）
- Nginx 配置中 `/api/dashboard/` 必须在 `/api/` 之前（Nginx 按最长前缀匹配，但明确写出更安全）
- 数据来源为 `shop_ads` 数据库（9 张报表表，与主库 `shop` 分离）

---

## 数据库表（12 张业务 + RBAC + 日志）

### 业务表

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| user | 用户表 | username, password(BCrypt), phone, nickname, avatar, status(0~3), admin_level(0~2) |
| category | 商品分类表（二级，parent_id=0 为一级） | name, parent_id, sort, icon |
| product | 商品表（SPU） | name, description, **category_id**, **first_category_id**, brand, main_image, **shop_id**, status(1/2/3) |
| sku | 商品 SKU 表 | product_id, name, specs(JSON), price, original_price, stock |
| orders | 订单表 | order_no(雪花ID), user_id, **shop_id**, total_amount, actual_amount, status(0~6), 收货信息快照 |
| order_item | 订单商品项（订单快照） | order_id, product_id, sku_id, price, quantity, subtotal, product_name/image |
| cart | 购物车表 | user_id, product_id, sku_id, quantity, checked, **is_ordered**(0=未下单/1=已下单) |
| address | 收货地址表 | user_id, receiver_name, phone, province/city/district/detail, is_default |

### RBAC 表（4 张）

| 表名 | 说明 |
|------|------|
| role | 角色表（admin/merchant/user） |
| permission | 权限表（6 项：product:add/edit/delete/status, order:all, user:manage） |
| user_role | 用户-角色关联 |
| role_permission | 角色-权限关联 |

### 日志表

| 表名 | 说明 |
|------|------|
| user_status_log | 账号状态变更日志（user_id, operator_id, from_status, to_status, reason） |

### 报表库：shop_ads（9 张表，独立数据库）

数据看板专用数据库，与主库 `shop` 分离：

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| ads_overview | 平台概览汇总（单行，按天分区） | dt, total_users, total_products, total_orders, total_gmv, avg_order_price |
| ads_gmv_daily | 每日 GMV 趋势（按天分区） | dt, gmv, order_count, paid_order_count, paid_user_count, avg_order_amount |
| ads_order_stats_daily | 订单状态分布（按天分区） | dt, total_orders, paid_orders, shipped_orders, completed_orders, cancelled_orders, refund_orders |
| ads_user_active | 用户活跃指标（按天分区） | dt, dau, order_user_count |
| ads_user_new_retain | 新增用户留存（按天分区） | dt, new_users, day1～30_retain_users, day1～30_retain_rate |
| ads_shop_daily | 店铺日汇总（按天分区） | dt, shop_id, shop_name, gmv, order_count, product_count |
| ads_category_sales | 分类销售排行（全量覆盖） | dt, first_category_id, category_name, sale_count, sale_amount |
| ads_product_top50 | 商品销量 TOP 50（全量覆盖） | dt, product_id, product_name, category_name, sale_quantity, sale_amount |
| ads_trend_weekly | 近 8 周趋势（全量覆盖） | week_start, week_end, gmv, order_count, user_count, gmv_mom |

> 数据来源：Hive ADS 层，通过 Sqoop 每日导出（全量表 TRUNCATE + 全量导出，分区表按 dt 增量导出）。

---

## 核心设计

### 并发控制

```sql
UPDATE sku SET stock = stock - #{quantity} WHERE id = #{skuId} AND stock >= #{quantity}
```
MySQL 行锁，影响行数为 0 时表示库存不足，事务回滚。

### 幂等设计

| 场景 | 方案 |
|------|------|
| 下单防重 | Redis Token：先获取令牌，提交时原子 delete（一次有效） |
| 支付回调 | 状态机校验：仅"待支付"状态才更新 |
| 重复请求 | Sa-Token 保证同一用户同时只有一个请求通过 |

### 商家数据隔离

商家端所有接口强制从 `StpUtil.getLoginIdAsLong()` 自动注入 `shop_id`，前端不传参：
- 订单管理 WHERE shop_id = ?
- 商品管理 WHERE shop_id = ?
- 详情/发货校验 `order.shop_id == shopId`

### 订单超时取消

`ScheduledTasks.java` 每 5 分钟扫描，取消下单超过 30 分钟未支付的订单，自动回滚 SKU 库存。

### 商品快照

order_item 记录下单时的名称、价格、图片，后续商品信息变更不影响历史订单。

### 购物车下单标记

下单成功后批量更新 `cart.is_ordered = 1`，购物车列表自动过滤 `is_ordered = 0`，用户看到的永远只有未结算商品。

### 统一返回格式

```json
{ "code": 200, "msg": "success", "data": { ... } }
```

所有异常由 `GlobalExceptionHandler` 接管，404 也返回 JSON 而非 HTML。

---

## Mock 数据工具

独立项目 `D:\claudecode\shop-data-mocker\`，纯 Java + Hutool JDBC：

```bash
cd "D:/claudecode/shop-data-mocker"
mvn compile exec:java -Dexec.mainClass="com.shop.mocker.DataMockerApplication"
```

**DataGenerator 内部方法：**

| 方法 | 生成数据 |
|------|---------|
| insertRoles() | 3 角色（admin/merchant/user） |
| insertPermissions() | 6 项权限编码 |
| insertRolePermissions() | 绑定角色权限关系 |
| insertUsers() | 4 用户（BCrypt 加密密码） |
| insertUserRoles() | 绑定用户角色 |
| insertShop() | 创建商家店铺 |
| insertAddresses() | 5 条收货地址 |
| insertCategories() | 5 个一级 + 19 个子分类 |
| insertProductsAndSkus() | 50 商品 + 200 SKU |
| insertOrders() | 5 条带商品项订单（事务写入） |

**生成数据量：** 4 用户 + 3 角色 + 6 权限 + 5 一级分类 + 19 子分类 + 50 商品 + 200 SKU + 5 地址 + 5 订单。

---

## 构建命令

```bash
# 后端
cd "D:/claudecode/shop"
mvn clean package -DskipTests -q

# 前端
cd "D:/claudecode/shop/frontend"        && npm run build
cd "D:/claudecode/shop/frontend-admin"  && npm run build
cd "D:/claudecode/shop/frontend-merchant" && npm run build

# 部署到 deploy/html（构建产物自动输出到各 dist/，手动复制到 deploy/html/ 对应目录）
```

---

## 前端设计系统

三端统一品牌设计语言（2026-07-01 重构）：

| 维度 | 值 |
|------|-----|
| 品牌色 | 深墨蓝 `#1a1a2e` + 珊瑚红 `#e94560` |
| 背景 | 暖灰 `#f5f3ef` |
| 字体 | system-ui / PingFang SC |
| 卡片 | 16px 圆角 + 暖阴影 |
| 图表 | ECharts（深墨蓝/珊瑚红/金色配色） |

**ECharts 图表分布：**

| 页面 | 图表 |
|------|------|
| 用户端 - 商品详情 | 价格走势图（14日模拟 + 均价参考线） |
| 管理后台 - 控制台 | 销售额趋势、订单分布饼图、商品销量TOP10、用户增长 |
| 管理后台 - 数据看板 | KPI 指标卡 5 项、GMV 折线图 + 订单分布环形图、分类销售水平柱状图、商品排行垂直柱状图、周趋势双轴折线图、用户指标卡片 |
| 商家中心 - 概览 | 销售额趋势、订单分布饼图、商品销量TOP5、每日订单数 |

---

## 数据看板组件体系（report/）

管理端 `frontend-admin/src/components/report/` 下的 6 个 ECharts 组件：

| 组件 | 文件 | 可视化类型 | 数据来源 |
|------|------|-----------|---------|
| KPI 指标卡 | KpiCards.vue | 数字卡 5 项（GMV/订单/用户/商品/店铺） | data.overview |
| GMV + 订单分布 | GmvAndOrderCharts.vue | 折线图 + 环形图 | data.gmvDaily + data.orderStats |
| 分类销售排行 | CategorySales.vue | 水平柱状图 | data.categorySales |
| 商品排行 | ProductTop.vue | 垂直柱状图 | data.productTop |
| 周趋势 | WeeklyTrend.vue | 双轴折线图（GMV + 订单量） | data.weeklyTrend |
| 用户指标 | UserMetrics.vue | 数字卡片（DAU/留存率/店铺信息） | data.userActive + data.userNewRetain + data.shopDaily |

**状态处理：** 每个组件均覆盖 加载骨架屏 → 错误重试 → 空数据占位 → 正常渲染 四种状态。

**数据流：** DataDashboard.vue → `api/report.js` → Axios → `/api/dashboard/all` → Nginx → 看板后端 :8081 → shop_ads 数据库

---

## 已知坑点

### 前端

| 问题 | 原因 | 修复 |
|------|------|------|
| `parentId === 0` 永远 false | Jackson Long→String 序列化，`parentId` 变成字符串 | 改为 `== 0` |
| 分页不显示 | `total` 是字符串 `"50"`，`total > pageSize` 始终 false | `Number(res.data.total)` |
| 管理后台数据空白 | 响应拦截器返回 `{code,data,msg}`，页面直接取 `res.list` | 改为 `res.data?.list` |

### Nginx

| 问题 | 原因 | 修复 |
|------|------|------|
| 静态资源 404（全局正则拦截） | `location ~* \.(js\|css\|...)$` 无 root/alias，请求被匹配到该规则但无文件源 | 按前端拆分独立的 regex location，分别指定 root |
| `-s reload` 后新配置不生效 | Windows 上旧 worker 进程未完全退出 | 先 `taskkill /f /im nginx.exe` 再重启 |
| 管理后台/商家中心 JS/CSS 301 重定向 | regex location 内使用 `alias` 导致路径拼接异常 | regex location 统一使用 `root`（而非 alias） |
| Windows sendfile 导致大文件 404 | Nginx 1.20.2 on Windows sendfile 实现有 bug | `sendfile off` |
| `-p` 路径不对导致 log/temp 目录找不到 | `-p` 指向了项目根而非 `deploy/` 目录 | 改为 `-p "D:/claudecode/shop/deploy"` |

---

## 离线数仓管线（已实施）

每日定时从 MySQL shop 采集数据，经 Hive 分层建模后导出至 MySQL `shop_ads` 库，供数据看板后端（:8081）查询：

```
shop 数据库 ──→ Sqoop 增量/全量采集 ──→ Hive ODS 层 ──→ Hive DWD 层 ──→ Hive DWS 层 ──→ Hive ADS 层 ──→ Sqoop 导出 ──→ MySQL shop_ads 库
                      (增量+全量)            (原始数据)      (清洗拉链)        (轻度聚合)        (报表宽表)
```

### 架构组件

| 组件 | 版本 | 用途 |
|------|------|------|
| Hadoop HDFS | 3.x | 分布式存储（虚拟机集群） |
| Hive | 3.x | 数据仓库分层建模（ODS/DWD/DWS/ADS），Spark 执行引擎 |
| Sqoop | 1.4.x | MySQL ↔ HDFS 双向数据迁移 |
| Azkaban | 3.x | 定时调度（日任务 DAG） |

### Hive 分层建模

| 层 | 说明 | 典型表 |
|----|------|--------|
| **ODS** | 原始数据，与 MySQL 结构一致（按增量/全量分表） | ods_orders_inc, ods_order_item_inc, ods_product_full/inc, ods_user_full/inc, ods_sku_inc, ods_address_full（共 15 张） |
| **DWD** | 清洗去重 + 拉链表（SCD Type 2） | dwd_fact_order_detail（订单事实宽表）, dwd_dim_user_zip（用户拉链）, dwd_dim_product_zip（商品拉链）, dwd_dim_sku_zip（SKU 拉链） |
| **DWS** | 轻度聚合（天级） | dws_order_daily, dws_product_sale_daily, dws_shop_daily, dws_user_active_daily |
| **ADS** | 报表宽表（Sqoop 导出至 MySQL shop_ads） | ads_overview, ads_gmv_daily, ads_order_stats_daily, ads_user_active, ads_user_new_retain, ads_shop_daily, ads_category_sales, ads_product_top50, ads_trend_weekly（共 9 张） |

### 调度管线（Azkaban）

```
每日两条 DAG：
  ┌─ shop_warehouse_full (全量表，1 次/d)：address, admin, cart, category 等 10 张低频变化表
  └─ shop_warehouse_daily (增量 + 计算，1 次/d)：
       01:00 Sqoop 全量采集（10 张全量表，并行 3 表/批）
       01:15 Sqoop 增量采集（5 张增量表，并行 3 表/批）
       01:30 DWD 层 ETL：订单事实宽表 + 维度拉链表
       02:00 DWS 层 ETL：订单/商品/店铺/用户日汇总
       02:20 ADS 层 ETL：9 张报表宽表（Spark 引擎）
       02:40 Sqoop 导出至 MySQL shop_ads（分区表增量 + 全量表全量）
```

> **代码仓库：** `D:\claudecode\shop\azkaban\shop_warehouse\` 包含全部 HQL 脚本、Shell 脚本、Azkaban Job 定义和打包后的 .zip 文件。
