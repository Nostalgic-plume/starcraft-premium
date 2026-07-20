# 星创优品

> B2C 电商系统 · Spring Boot 单体后端 + Vue 3 三端分离前端 · Nginx 反代 + 静态托管

---

## 目录

- [技术栈](#技术栈)
- [快速启动](#快速启动)
- [访问地址](#访问地址)
- [项目结构](#项目结构)
- [核心功能](#核心功能)
- [数据库](#数据库)
- [账号状态](#账号状态)

---

## 技术栈

| 分类 | 技术 |
|------|------|
| **后端** | Spring Boot + MyBatis-Plus + Sa-Token |
| **数据库** | MySQL 8 + Redis |
| **前端** | Vue 3 + Vite + Element Plus + Pinia + ECharts |
| **反向代理** | Nginx 1.20.2 |
| **JDK** | OpenJDK 22 |
| **向量检索** | LangChain4j + Qdrant + Ollama（bge-m3 嵌入） |
| **智能客服** | SSE 流式 + Qwen3.5:0.8b（Ollama 大模型）+ RAG |
| **离线数仓** | Hive + Sqoop + Azkaban 调度 |

---

## 快速启动

```bash
# 1. 后端编译
mvn clean package -DskipTests -q

# 2. 后端启动
mvn spring-boot:run

# 3. 前端构建（三端分别构建）
cd frontend            && npm run build
cd frontend-admin      && npm run build
cd frontend-merchant   && npm run build

# 4. Nginx 启动
nginx.exe -p deploy
```

---

## 访问地址

| 地址 | 页面 | 默认账号 |
|------|------|----------|
| `http://localhost` | 用户端 | zhangsan / lisi |
| `http://localhost/admin/` | 管理后台 | admin |
| `http://localhost/merchant/` | 商家中心 | merchant1 |

所有账号密码统一 **`123456`**。

---

## 项目结构

```
shop/
├── src/                    # 后端源码（Java）
│   ├── controller/         #   REST 接口
│   ├── service/            #   业务逻辑
│   ├── mapper/             #   MyBatis-Plus 数据访问
│   ├── model/              #   Entity / DTO / VO
│   ├── rag/                #   语义搜索 + 智能客服
│   ├── common/             #   通用工具
│   └── config/             #   全局配置
├── frontend/               # 用户端 Vue 3
├── frontend-admin/         # 管理后台 Vue 3
├── frontend-merchant/      # 商家中心 Vue 3
└── deploy/                 # Nginx + 构建产物
```

---

## 核心功能

| 模块 | 功能 |
|------|------|
| **用户端** | 商品浏览 · 关键词/语义双模式搜索 · 购物车 · 下单支付 · 订单管理 · 智能客服 |
| **管理后台** | 用户管理 · 订单管理 · 商品管理 · ECharts 数据统计看板 |
| **商家中心** | 商品管理 · 订单处理/发货 · 本店 ECharts 数据看板（shop_id 隔离） |
| **数据看板** | 独立后端（:8081）+ 6 组 ECharts 报表组件 · 查询 shop_ads 报表库 |
| **RAG 语义搜索** | 向量检索替代 MySQL LIKE · 支持分类/价格过滤 |
| **智能客服** | SSE 流式响应 · 多轮对话历史 · RAG 检索增强生成 |
| **权限体系** | RBAC 三级（admin/merchant/user）+ 两级管理员 · 商家数据自动隔离 |
| **库存控制** | MySQL 行锁防超卖 · 取消/超时订单自动回滚 |
| **超时取消** | 定时每 5 分钟扫描 · 取消 30 分钟未支付订单 |
| **离线数仓** | Hive 分层（ODS/DWD/DWS/ADS）· Azkaban 每日调度 · Sqoop 双向同步 |

---

## 数据库

| 库 | 说明 |
|----|------|
| `shop` | 主库 — 8 张业务表 + 4 张 RBAC 表 + 1 张日志表 |
| `shop_ads` | 报表库 — 9 张 ADS 报表表，供看板查询 |

所有表使用 MyBatis-Plus 逻辑删除（`deleted` 标记）。

---

## 账号状态

| 值 | 状态 | 登录行为 |
|----|------|----------|
| 0 | 正常 | 允许登录 |
| 1 | 停用 | 拒绝登录 |
| 2 | 注销中 | 拒绝登录 |
| 3 | 封禁中 | 拒绝登录 |

状态变更记录写入 `user_status_log`。

---

详细文档见 [docs/README.md](docs/README.md)。
