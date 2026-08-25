# 星创优品 B2C 电商系统

> Spring Boot 单体后端 + Vue 3 三端分离前端 + RAG 智能搜索 + AI 客服

## 技术栈

| 后端 | 数据库 & 中间件 | 前端 | AI / 大数据 |
|------|---------------|------|------------|
| Spring Boot 3.2.12 | MySQL 8 + Redis | Vue 3 + Vite | LangChain4j + Qdrant |
| MyBatis-Plus 3.5.5 | Nginx 1.20.2 | Naive UI + Pinia | DashScope qwen-plus |
| Sa-Token 1.45.0 | — | ECharts | Hive + Sqoop + Azkaban |

## 功能概览

- **商品系统** — SPU/SKU 分层、二级分类联动、商家隔离、RAG 语义搜索
- **订单系统** — 7 态状态机、MySQL 行锁防超卖、Redis 令牌防重、超时自动取消
- **购物车** — 未下单/已下单双态，下单后自动标记
- **支付系统** — 模拟支付回调，幂等处理
- **AI 智能客服** — SSE 流式对话、RAG 知识库增强、元数据过滤回退
- **RBAC 权限** — 三级角色（管理员/商家/用户）+ 2 级管理员
- **数据看板** — ECharts 图表：销售额趋势、商品排行、用户增长
- **离线数仓** — Hive 分层（ODS → DWD → DWS → ADS），Azkaban 每日调度

## 快速启动

```bash
# 环境准备
cp .env.example .env    # 填写数据库密码和 API Key
mvn clean package -DskipTests

# 构建前端
cd frontend          && npm run build
cd frontend-admin    && npm run build
cd frontend-merchant && npm run build

# 启动后端
mvn spring-boot:run

# 启动 Nginx（部署用）
cd deploy && nginx.exe -c conf/nginx.conf -p .
```

## 访问地址

| 端 | 地址 | 默认账号 |
|----|------|---------|
| 用户端 | `http://localhost` | zhangsan |
| 管理后台 | `http://localhost/admin/` | admin |
| 商家中心 | `http://localhost/merchant/` | merchant1 |

> 所有账号密码统一 `123456`

## 项目结构

```
shop/
├── src/main/java/com/shop/
│   ├── controller/    # REST 接口
│   ├── service/       # 业务逻辑
│   ├── mapper/        # MyBatis-Plus Mapper
│   ├── model/         # entity / dto / vo / query
│   ├── common/        # 统一返回、异常、分页
│   ├── config/        # 全局配置、定时任务
│   ├── constant/      # 状态枚举、常量
│   └── rag/           # RAG 语义搜索 + AI 客服
├── frontend/          # 用户端（Vue 3）
├── frontend-admin/    # 管理后台（Vue 3）
├── frontend-merchant/ # 商家中心（Vue 3）
└── azkaban/           # 离线数仓调度
```

## 核心设计

- **防超卖** — `UPDATE sku SET stock = stock - n WHERE id = ? AND stock >= n`（MySQL 行锁）
- **幂等** — Redis Token 防重下单 + 支付回调条件更新
- **订单快照** — 下单时 name/price/image 写入 order_item，历史订单不受商品变更影响
- **数据隔离** — 商家 loginId 直接映射 shop_id，Service 层自动 `WHERE shop_id = ?`
- **缓存** — 商品详情逻辑过期缓存 + ID 布隆过滤（防穿透/击穿），库存 Redis Lua 原子预扣
- **长整型 ID** — Jackson Long→String 全局序列化，解决 JS 精度丢失

## 在线文档

详细文档见 [docs/README.md](./docs/README.md)
