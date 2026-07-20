# Starcraft Premium — 电商系统

B2C 电商系统，Spring Boot 单体后端 + 三端分离前端（用户端/管理后台/商家中心），Nginx 托管静态资源并反代 API。

## 技术栈

| 组件 | 技术 |
|------|------|
| 后端 | Spring Boot + MyBatis-Plus + Sa-Token |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + ECharts |
| 数据库 | MySQL 8 + Redis |
| 反向代理 | Nginx 1.20.2 |
| JDK | OpenJDK 22 |

## 快速启动

```bash
# 后端编译
mvn clean package -DskipTests -q

# 后端启动
mvn spring-boot:run

# 前端构建（三端分别构建）
cd frontend            && npm run build
cd frontend-admin      && npm run build
cd frontend-merchant   && npm run build

# Nginx 启动（配置已指向 dist/）
nginx.exe -p deploy
```

## 访问地址

| 地址 | 页面 | 默认账号 |
|------|------|---------|
| http://localhost | 用户端 | zhangsan / lisi |
| http://localhost/admin/ | 管理后台 | admin |
| http://localhost/merchant/ | 商家中心 | merchant1 |

所有账号密码统一 **`123456`**。

## 项目结构

```
shop/
├── src/main/java/com/shop/   # 后端代码
│   ├── controller/           # REST 接口
│   ├── service/              # 业务逻辑
│   ├── mapper/               # MyBatis-Plus Mapper
│   ├── model/                # Entity / DTO / VO
│   ├── common/               # 通用工具
│   └── config/               # 全局配置
├── frontend/                 # 用户端 Vue 3
├── frontend-admin/           # 管理后台 Vue 3
├── frontend-merchant/        # 商家中心 Vue 3
└── deploy/                   # Nginx + 预构建文件
```

## 核心功能

- **用户端**：商品浏览/搜索、购物车、下单支付、订单管理
- **管理后台**：用户管理、订单管理、数据统计看板（ECharts）
- **商家中心**：商品管理、订单处理、发货、本店数据看板
- **权限体系**：RBAC 三级权限（admin/merchant/user），商家数据 shop_id 隔离
- **库存控制**：MySQL 行锁防超卖，取消订单自动回滚库存
- **超时订单**：定时任务每 5 分钟扫描，取消 30 分钟未支付订单

## 数据库

8 张业务表 + 4 张 RBAC 表 + 1 张日志表，MyBatis-Plus 逻辑删除 `deleted` 标记。

## 账号状态

0=正常 1=停用 2=注销中 3=封禁中，变更记录写入 `user_status_log`。

详细文档见 [docs/README.md](docs/README.md)。
