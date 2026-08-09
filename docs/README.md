# 星创优品 — 电商系统

B2C 电商系统，**Spring Boot 单体后端 + Vue 3 三端分离前端**，Nginx 托管静态资源并反代 API。

---

## 目录

- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [业务模块](#业务模块)
- [API 接口](#api-接口)
- [数据库](#数据库)
- [账号与权限](#账号与权限)
- [核心设计](#核心设计)
- [离线数仓](#离线数仓)
- [前端设计系统](#前端设计系统)

---

## 技术栈

| 组件 | 技术栈 |
|------|--------|
| 后端 | Spring Boot 3.2.12 + MyBatis-Plus 3.5.5 + Sa-Token 1.45.0 |
| 数据库连接池 | Druid（连接池 + SQL 监控 + 防火墙），监控面板 `/druid/` | 1.2.25 |
| 异步消息 | RocketMQ（异步落单 + 延迟取消） | client 4.9.7 / starter 2.2.3 |
| 接口文档 | springdoc-openapi（Swagger UI，`/swagger-ui.html`） | 2.3.0 |
| 配置加载 | spring-dotenv（`.env` 加载敏感凭证，已 gitignore） | 4.0.0 |
| 数据库 | MySQL 8 + Redis |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + ECharts |
| 反向代理 | Nginx 1.20.2 |
| JDK | OpenJDK 22 |
| RAG 语义搜索 | LangChain4j + Qdrant（1536维）+ DashScope（text-embedding-v1 嵌入） |
| 智能客服 | SSE 流式 + DashScope qwen-plus（大模型）+ RAG 检索增强 + 分类名回退 |
| 离线数仓 | Hive 3.x + Sqoop 1.4.x + Azkaban 3.x |

---

## 快速开始

```bash
# 1. 后端编译启动
cd shop && mvn clean package -DskipTests -q && mvn spring-boot:run

# 2. 前后端构建（三端）
cd frontend            && npm run build
cd frontend-admin      && npm run build
cd frontend-merchant   && npm run build

# 3. Nginx 启动
cd shop/deploy && nginx.exe -c conf/nginx.conf -p .
```

| 地址 | 页面 | 默认账号 |
|------|------|----------|
| `http://localhost:8088` | 用户端 | zhangsan / lisi |
| `http://localhost:8088/admin/` | 管理后台 | admin |
| `http://localhost:8088/merchant/` | 商家中心 | merchant1 |
| `http://localhost:8080/swagger-ui.html` | 接口文档（Swagger UI） | — |

> 所有账号密码统一 **`123456`**。
> 首次运行前复制 `.env.example` 为 `.env` 并填写 DB 密码 / DashScope Key（已 gitignore，由 spring-dotenv 启动时加载）。

---

## 项目结构

```
shop/
├── src/                        # 后端 Java 源码
│   ├── controller/             #   REST 接口（Admin/User/Order/Product/Merchant...）
│   ├── service/                #   业务逻辑
│   ├── mapper/                 #   MyBatis-Plus 数据访问
│   ├── model/entity/dto/vo     #   数据模型
│   ├── rag/                    #   语义搜索 + 智能客服
│   ├── mq/                     #   RocketMQ 异步落单 + 延迟取消
│   ├── common/                 #   通用工具 + 统一返回
│   └── config/                 #   全局配置 + 异常处理 + 定时任务
├── frontend/                   # 用户端 Vue 3
├── frontend-admin/             # 管理后台 Vue 3
├── frontend-merchant/          # 商家中心 Vue 3
└── deploy/                     # Nginx + 静态资源

独立项目：shop-data-mocker（Mock 数据工具）
```

---

## 业务模块

| 模块 | 核心逻辑 |
|------|----------|
| **用户** | BCrypt 注册登录、Sa-Token 会话、4 种账号状态 |
| **商品** | SPU + SKU 分层、二级分类联动（firstCategoryId）、shop_id 隔离、RAG 语义搜索 |
| **分类** | 二级分类树、按父级查询子分类 |
| **订单** | Redis 防重令牌 → RocketMQ 异步落单（Redis 预扣 + MySQL 行锁）→ 前端轮询落单状态 → shop_id 绑定 → 购物车标记 → 幂等支付回调 → 库存回滚取消 → 30 分钟延迟消息超时取消 |
| **购物车** | SKU 级别管理、is_ordered 过滤已结算商品 |
| **支付** | 模拟支付回调、幂等处理 |
| **管理后台** | 用户/订单/商品管理、ECharts 统计看板、全平台数据查询 |
| **商家中心** | shop_id 自动隔离、本店商品/订单管理、发货、本店 ECharts 看板 |
| **报表库看板** | 查询 shop_ads 报表库（离线数仓产出），ECharts 报表展示 |
| **智能客服** | SSE 流式对话，RAG 检索 TOP-3 + 分类名回退，小星真人导购人设 |
| **RAG 搜索** | DashScope text-embedding-v1 向量化 → Qdrant 检索 → 元数据过滤（分类/价格） |

---

## API 接口

### 公开接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/user/register | 用户注册 |
| POST | /api/user/login | 用户登录 |
| GET | /api/product/page | 商品分页（categoryId/firstCategoryId/keyword） |
| GET | /api/product/detail/{id} | 商品详情（含 SKU） |
| GET | /api/product/rag-search | 语义搜索（RAG 向量检索） |
| GET | /api/category/list | 全部分类 |
| GET | /api/category/children?parentId= | 子分类 |
| POST | /api/pay/callback | 支付回调 |

### 用户端（需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/user/info | 用户信息 |
| GET | /api/user/role | 当前用户角色 |
| GET | /api/order/token | 下单防重令牌 |
| POST | /api/order/create | 创建订单（异步削峰，返回 processingId 供轮询） |
| GET | /api/order/status/{processingId} | 查询异步落单状态（前端轮询） |
| GET | /api/order/my | 我的订单（分页+状态筛选） |
| GET | /api/order/detail/{id} | 订单详情 |
| POST | /api/order/cancel/{id} | 取消订单 |
| POST | /api/order/receive/{id} | 确认收货 |
| GET/POST/PUT/DELETE | /api/cart/** | 购物车 CRUD |
| POST | /api/pay/mock-pay | 模拟支付 |
| POST | /api/chat/ask (SSE) | 智能客服流式对话（小星人设） |
| POST | /api/chat/clear | 清除对话历史 |

### 管理后台（需 `@SaCheckPermission`）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | /api/admin/users | user:manage | 用户分页 |
| PUT | /api/admin/user/{id}/status | user:manage | 修改用户状态 |
| PUT | /api/admin/user/batch-status | user:manage | 批量修改状态 |
| GET | /api/admin/order/page | order:all | 订单分页（多条件） |
| GET | /api/admin/order/detail/{id} | order:all | 订单详情 |
| GET | /api/admin/stats/** | — | 控制台统计（5 个端点） |

### 商家端（shop_id 自动注入）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/merchant/stats/dashboard | 本店控制台 |
| GET | /api/merchant/order/page | 本店订单分页 |
| POST | /api/merchant/order/deliver | 发货 |
| GET/POST/PUT | /api/merchant/product/** | 本店商品管理 |

---

## 数据库

### 主库 `shop`（18 张表）

| 类别 | 表 | 说明 |
|------|----|------|
| 业务 | user / product / sku / orders / order_item / cart / category / address | 用户/商品/订单/购物车/分类/地址 |
| 账号 | admin / shop | 管理员、商家独立账号（shop 以 shop_id 为身份标识，无 user_id 关联） |
| RBAC | role / permission / user_role / role_permission | 三级角色 + 6 项权限 |
| 日志 | user_status_log | 账号状态变更记录 |
| 智能客服 | chat_conversation / chat_message / chat_feedback | 对话会话 / 消息 / 反馈 |

### 报表库 `shop_ads`（9 张表）

概览 / GMV 日趋势 / 订单状态分布 / 用户活跃 / 新增留存 / 店铺日汇总 / 分类销售 / 商品 TOP50 / 周趋势。

> 所有表使用 MyBatis-Plus 逻辑删除（`deleted` 标记）。

---

## 账号与权限

| 角色 | admin_level | 权限 | 登录端 |
|------|-------------|------|--------|
| 超级管理员 | 2 | product:add/edit/delete/status, order:all, user:manage | 管理后台 |
| 普通管理员 | 1 | 同上（不可操作超级管理员） | 管理后台 |
| 商家 | 0 | product:add/edit/status（shop_id 隔离） | 商家中心 |
| 用户 | 0 | 无管理权限 | 用户端 |

| 状态 | 值 | 登录 |
|------|----|------|
| 正常 | 0 | 允许 |
| 停用 | 1 | 拒绝 |
| 注销中 | 2 | 拒绝 |
| 封禁中 | 3 | 拒绝 |

---

## 核心设计

| 设计 | 方案 |
|------|------|
| **连接池监控** | Druid：连接池 + SQL 统计 + 慢 SQL（2s）日志 + wall 防火墙防注入，监控面板 `/druid/` |
| **防超卖** | MySQL 行锁 `UPDATE sku SET stock = stock - n WHERE id = ? AND stock >= n`（最终凭证）+ Redis 库存缓存 Lua 原子预扣（前置快速校验） |
| **异步下单** | RocketMQ 削峰：接口仅发消息返回 processingId，消费端事务内 Redis 预扣 + MySQL 行锁落单，前端轮询状态 |
| **下单幂等** | Redis Token 防重令牌一次有效 + MQ 消费幂等键 `order:dedup:{orderToken}`（SETNX 防重复投递） |
| **支付幂等** | 状态机校验，仅"待支付"状态可更新 |
| **库存回滚** | 取消/超时订单自动 `UPDATE sku SET stock = stock + n` + Redis 库存回滚 |
| **订单超时** | RocketMQ 延迟消息（30 分钟）主取消 + 定时任务 60 分钟兜底 |
| **商家隔离** | SQL 拦截器自动追加 `AND shop_id = ?` + Service 层校验（双保险） |
| **缓存防护** | 商品 ID 布隆过滤器（防穿透）→ 商品详情逻辑过期缓存（防击穿）→ DB |
| **商品快照** | order_item 记录下单时名称/价格/图片，不受后续变更影响 |
| **购物车标记** | 下单后批量 `cart.is_ordered = 1`，列表过滤已结算 |
| **RAG 搜索** | DashScope text-embedding-v1 向量化 → Qdrant 检索 → 元数据过滤 |
| **智能客服** | SSE 流式输出 → RAG 检索 TOP-3（空结果走分类名回退）→ DashScope qwen-plus 生成回答，小星真人导购人设 |
| **统一返回** | `{ code, msg, data }`，全局异常处理器接管所有异常 |
| **Long→String** | Jackson 全局配置，解决雪花 ID 前端 JS 精度丢失 |

---

## 离线数仓

```
shop MySQL → Sqoop 采集 → Hive ODS → DWD（清洗拉链）→ DWS（日聚合）→ ADS（报表）→ Sqoop 导出 → shop_ads
```

Azkaban 每日调度两条 DAG：
- **全量**：address/category 等 10 张低频表
- **增量**：orders/order_item/product 等 5 张高频表 → DWD/DWS/ADS 逐层 ETL

---

## 前端设计系统

| 维度 | 值 |
|------|-----|
| 品牌色 | 深墨蓝 `#1a1a2e` + 珊瑚红 `#e94560` |
| 背景 | 暖灰 `#f5f3ef` |
| 卡片 | 16px 圆角 + 暖阴影 |
| 图表 | ECharts（深墨蓝/珊瑚红/金色渐变） |

| 页面 | 可视化内容 |
|------|-----------|
| 用户端 - 商品详情 | 价格走势图 |
| 用户端 - 首页 | 语义/关键词双模式搜索 + FloatingChat 智能客服（支持放大/拖拽） |
| 管理后台 - 控制台 | 销售额趋势 / 订单分布 / 商品TOP10 / 用户增长 |
| 管理后台 - 数据看板 | 6 组 ECharts 报表组件（KPI / GMV / 分类 / 排行 / 周趋势 / 用户） |
| 商家中心 - 概览 | 销售额趋势 / 订单分布 / 商品TOP5 / 每日订单数 |

---
