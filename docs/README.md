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
- [推荐子系统](#推荐子系统)
- [离线数仓](#离线数仓)
- [前端设计系统](#前端设计系统)

---

## 技术栈

| 组件 | 技术栈 |
|------|--------|
| 后端 | Spring Boot 3.2.12 + MyBatis-Plus 3.5.5 + Sa-Token 1.45.0 |
| 数据库连接池 | Druid 1.2.25（连接池 + SQL 监控 + 防火墙），监控面板 `/druid/` |
| 异步消息 | RocketMQ 4.9.7 / starter 2.2.3（异步落单 + 延迟取消） |
| 接口文档 | springdoc-openapi 2.3.0（Swagger UI，`/swagger-ui.html`） |
| 配置加载 | spring-dotenv 4.0.0（`.env` 加载敏感凭证，已 gitignore） |
| 数据库 | MySQL 8 + Redis |
| 前端 | Vue 3 + Vite + Naive UI + Pinia + ECharts |
| 反向代理 | Nginx 1.20.2 |
| JDK | OpenJDK 22 |
| RAG 语义搜索 | LangChain4j 1.19.0 + Qdrant（1024 维）+ 本地 TEI 部署 BGE-M3 向量化 + Elasticsearch 8.11.4 + IK（BM25 关键词路，故障自动回退 MySQL FULLTEXT） |
| 智能客服 | LangChain4j AiServices + DeepSeek（主）/ 智谱 glm-4-flash（备）+ Function Calling 工具链 + RAG 检索增强 |
| 推荐子系统 | Kafka 3.7.0（行为事件流）+ Flink 1.17.2（实时特征计算）+ 独立进程 `:8082` / 独立库 `shop_rec` |
| 离线数仓 | Hive 3.x + Sqoop 1.4.x + Azkaban 3.x |

---

## 快速开始

```bash
# 0. 中间件一键启动（默认仅 Redis / RocketMQ / Nginx 三个 —— 商城核心链路必需）
cd shop && docker compose up -d

# 0b. 启用 RAG 向量检索（TEI 文本向量化，需 NVIDIA GPU + nvidia-container-toolkit）
#     TEI 归入 gpu profile 默认不启动 —— 无 GPU 机器上它会让整条 up 命令失败。
#     BGE-M3 权重目录由 .env 的 BGE_M3_MODEL_PATH 指定（默认 D:/models/bge-m3，约 2.2G，需含 1_Pooling/）
cd shop && docker compose --profile gpu up -d

# 0c. 启用 RAG 关键词路（Elasticsearch 8.11.4 + IK，归 es profile 默认不启动以省内存）
#     ES 未启动时检索自动回退 MySQL FULLTEXT，不报错；首次启动会自动建索引并导入上架商品
cd shop && docker compose --profile es up -d

# 1. 后端编译启动（Maven 多模块聚合工程：必须 -pl 指定子模块 + 显式 workingDirectory，
#    否则读不到仓库根的 .env，报 MySQL "Access denied"）
cd shop && mvn clean install -DskipTests -q
cd shop && mvn -pl shop-server spring-boot:run -Dspring-boot.run.workingDirectory="$(pwd -W)"

# 2. 前后端构建（三端）
cd frontend            && npm run build
cd frontend-admin      && npm run build
cd frontend-merchant   && npm run build

# 3. Nginx：已由步骤 0 的 compose 提供（shop-nginx 容器，监听 80，配置 docker/nginx/nginx.conf）
```

| 地址 | 页面 | 默认账号 |
|------|------|----------|
| `http://localhost` | 用户端 | user |
| `http://localhost/admin/` | 管理后台 | admin |
| `http://localhost/merchant/` | 商家中心 | merchant1 |
| `http://localhost:8080/swagger-ui.html` | 接口文档（Swagger UI） | — |

> 所有账号密码统一 **`123456`**。
> 首次运行前复制 `.env.example` 为 `.env` 并填写 DB 密码、`DEEPSEEK_API_KEY`、`BACKUP_LLM_API_KEY`
> （Compose 另需 `REDIS_PASSWORD`、`BGE_M3_MODEL_PATH`）。`.env` 已 gitignore，由 spring-dotenv 启动时加载。
> 向量化已于 2026-09 由 DashScope `text-embedding-v1` 换为本地 TEI/BGE-M3，`DASHSCOPE_API_KEY` 仅留作回滚。

---

## 项目结构

```
shop/                            # Maven 聚合工程
├── shop-common/                 # 通用能力（Result / 异常 / 分页 / 缓存 / Sa-Token 工具）
├── shop-server/                 # 商城后端 :8080（启动类 ShopApplication）
│   └── src/main/java/com/shop/
│       ├── controller/          #   REST 接口（Admin/User/Order/Product/Merchant...）
│       ├── service/ mapper/     #   业务逻辑与持久层
│       ├── model/               #   entity / dto / vo / query
│       ├── rag/                 #   语义搜索 + 智能客服
│       ├── mq/                  #   RocketMQ 异步落单 + 延迟取消
│       ├── config/ web/ task/   #   全局配置、异常处理、定时任务
│       └── constant/            #   枚举与常量
├── shop-rec/                    # 推荐服务 :8082（启动类 RecApplication，独立库 shop_rec）
├── shop-rec-flink/              # 推荐链路 Flink 作业（独立 Maven 工程，不参与主构建）
├── frontend/                    # 用户端 Vue 3
├── frontend-admin/              # 管理后台 Vue 3
├── frontend-merchant/           # 商家中心 Vue 3
├── shared/                      # 三端共享前端源码（CSS 变量 / TS 工具）
├── docker/                      # Nginx / ES / RocketMQ 容器配置
└── azkaban/                     # 离线数仓调度作业

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
| **智能客服** | SSE 流式对话，RAG 检索 TOP-3 + 分类名回退；Function Calling 工具链（查/取消真实订单，写操作状态机 + 库存回滚）；引用溯源编号 + 商品卡片推送；小星真人导购人设 |
| **RAG 搜索** | 本地 TEI/BGE-M3 向量化（1024 维）→ Qdrant 检索；关键词路 Elasticsearch BM25 + IK 分词（故障自动回退 MySQL FULLTEXT）；双路加权 RRF 融合 → 元数据过滤（分类/价格） |
| **收货地址** | 当前用户地址 CRUD、默认地址唯一、归属校验（userId 由登录会话注入，请求体不接收） |
| **商品收藏** | 收藏 / 取消收藏（幂等，唯一索引兜底）、收藏列表实时回查商品并过滤已下架 / 已删除项 |
| **商品评价** | 订单项维度评价（已收货/已完成才可评、一单一评）、匿名、商品快照；管理端显示/隐藏/删除/回复，删除走物理删除以释放订单项 |
| **主数据管理** | 管理后台分类（两级结构、删除前校验子分类与商品引用）与品牌（名称唯一、商品引用校验）CRUD |
| **角色权限** | 角色 CRUD、角色-权限分配、用户-角色分配；权限项由启动初始化器幂等维护，内置角色不可删 |
| **推荐服务** | 独立进程 + 独立库；行为采集（Kafka）→ Flink 实时特征 → 多路召回（类目偏好 + 热门）→ 首页推荐流，详见[推荐子系统](#推荐子系统) |

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

### 主库 `shop`（22 张表）

| 类别 | 表 | 说明 |
|------|----|------|
| 业务 | user / product / sku / orders / order_item / cart / category / address / brand / favorite / review | 用户/商品/订单/购物车/分类/地址/品牌/收藏/评价 |
| 账号 | admin / shop | 管理员、商家独立账号（shop 以 shop_id 为身份标识，无 user_id 关联） |
| RBAC | role / permission / user_role / role_permission | 三级角色 + 7 项权限，角色/权限分配接口已开放 |
| 日志 | user_status_log / rag_trace | 账号状态变更记录 / RAG 行为反馈埋点 |
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
| **RAG 搜索** | 本地 TEI/BGE-M3 向量化（1024 维）→ Qdrant 检索；关键词路 ES BM25 + IK（熔断回退 MySQL FULLTEXT）；双路加权 RRF 融合 + 元数据过滤（分类/价格） |
| **智能客服** | SSE 流式输出 → RAG 检索 TOP-3（空结果走分类名回退）→ DeepSeek 生成回答；主备降级链（熔断 → glm-4-flash 纯 RAG → 模板兜底）；Function Calling 工具链（AiServices 查/取消订单，userId 锁死 + 归属校验防越权）；引用溯源 [n] 编号 + 商品卡片推送 |
| **统一返回** | `{ code, msg, data }`，全局异常处理器接管所有异常 |
| **Long→String** | Jackson 全局配置，解决雪花 ID 前端 JS 精度丢失 |

---

## 推荐子系统

独立进程 `:8082`（仅绑回环），配独立数据库 `shop_rec`，与商城主库**物理隔离**；与商城后端通过内部令牌 `X-Internal-Token` 鉴权（无默认值，环境变量注入）。

**数据流**

```
用户行为上报（商城内嵌入口 @Profile("!rec")）→ Kafka user-behavior
    → Flink 实时计算（商品热度榜 + 用户画像）
    → shop_rec（热度分 / 画像 / 固定位 / 过滤规则）
    → 多路召回（类目偏好路 + 热门路）→ 融合排序 → 首页推荐流
```

**独立库 `shop_rec`（7 张表）**：`user_behavior_log`、`user_profile`、`item_hot_score`、`rec_config`、`rec_filter_rule`、`rec_fixed_slot`、`pipeline_metric`。

**三进程组件隔离约定**（商城 `:8080` / 推荐 `:8082` / Flink 作业）：推荐侧组件标 `@Profile("rec")`，商城侧行为采集标 `@Profile("!rec")`，基础设施不带 profile —— 漏标会让定时任务在两个进程重复执行。

**Flink 作业**：独立工程 `shop-rec-flink`（编译目标 Java 11，不参与主 pom 构建），打包后提交至 compose 的 Flink 集群（归 `rec` profile）。

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
