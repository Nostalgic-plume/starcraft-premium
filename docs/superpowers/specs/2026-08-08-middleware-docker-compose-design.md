# 中间件 Docker Compose 一键编排 — 设计文档

- 日期：2026-08-08
- 状态：已确认
- 范围：Redis、Nginx、Qdrant、RocketMQ 四个中间件统一 Docker Compose 编排，后端保持本地运行

## 背景与目标

当前四个中间件启动方式割裂：Redis（本地命令行）、Nginx（本地 `nginx.exe`）、Qdrant 和 RocketMQ（Docker 手动 `docker run`）。每次开发前要逐个启动，步骤繁琐。

**目标：** 一个 `docker-compose.yml`，`docker compose up -d` 一键拉起全部四个中间件；端口映射与现状完全一致，后端本地跑，`application.yml` 仅新增一行 Redis 密码配置。

**范围外（明确不做）：**
- 后端 Spring Boot **不** Docker 化（高频改代码 + 断点调试，维持本地运行）
- MySQL 保持本地 Windows 服务（不在本任务范围）
- Qdrant 产品向量 collection / 索引 **本轮一并构建**（用户确认：容器启动后由后端启动时自动建 collection + 全量索引，依赖 `.env` 中 `DASHSCOPE_API_KEY` 有效；另有手动接口 `POST /api/admin/rag/rebuild`）
- 本地 Redis / 本地 Nginx **退役**（当前均未运行，端口空闲，无冲突）

## 现状确认（2026-08-08 实测）

| 项目 | 状态 |
|------|------|
| 端口 6379/80/9876/10909/10911/10912/6333/6334/8080 | 全部空闲，无监听 |
| 本地 Redis 8.8.0（`d:\software\redis-8.8.0`） | 未运行，非系统服务，靠命令行手动启动 |
| 本地 Nginx 1.20.2（`D:\software\nginx-1.20.2`） | 未运行 |
| 三个前端 dist（frontend / frontend-admin / frontend-merchant） | 已存在，**需重新 `npm run build`**（用户要求） |
| Docker Desktop | 未启动（实施时需先启动） |
| `application.yml` 连接 | Redis `localhost:6379`（无密码）、Qdrant `localhost:6334`、RocketMQ `${ROCKETMQ_NAMESRV:127.0.0.1:9876}`、Sa-Token `dao-type: redis`；Docker 化后 Redis 加 `requirepass`，`application.yml` 同步加 `password` 配置 |

## 架构

```
宿主机（后端本地跑 :8080）
 │
 ├── localhost:6379   ──► redis 容器
 ├── localhost:6333/6334 ──► qdrant 容器
 ├── localhost:9876   ──► rocketmq-namesrv 容器
 │                        └── rocketmq-broker 容器（namesrv↔broker 内部互联；10911 映射宿主机供后端 Remoting 直连）
 └── localhost:80     ──► nginx 容器（挂载三个 dist + Docker 版配置）
```

容器之间（namesrv ↔ broker）通过 compose 默认网络互相解析；宿主机后端只走端口映射，`application.yml` 不需改动。

## 新增文件

```
D:/claudecode/shop/
├── docker-compose.yml              # 新增：编排 5 个容器
├── .env.example                    # 修改：追加 REDIS_PASSWORD（Redis 密码；brokerIP1 用 127.0.0.1，不再需要 HOST_IP）
├── docker/
│   ├── rocketmq/broker.conf        # 新增：broker 配置（含 brokerIP1=${HOST_IP}）
│   └── nginx/nginx.conf            # 新增：Docker 版 Nginx 配置（静态路径改为容器内路径，其余照抄现有 nginx.conf）
└── src/main/resources/application.yml  # 修改：spring.data.redis 增加 password: ${REDIS_PASSWORD:}（仅一行）
```

## 服务清单

| 服务 | 镜像 | 端口映射（宿主机→容器） | 数据卷（named volume） |
|------|------|------------------------|----------------------|
| redis | `redis:8.8-alpine` | `127.0.0.1:6379`（仅本机可达） | `redis-data:/data`；启动命令见「关键设计点 1. Redis」 |
| qdrant | `qdrant/qdrant` | 6333, 6334 | `qdrant-data:/qdrant/storage` |
| rocketmq-namesrv | `apache/rocketmq:5.3.2` | 9876 | `rmq-logs:/home/rocketmq/logs` |
| rocketmq-broker | `apache/rocketmq:5.3.2` | 10909, 10911, 10912 | `rmq-logs:/home/rocketmq/logs`、`rmq-store:/home/rocketmq/store` |
| nginx | `nginx:1.20.2-alpine` | 80 | 挂载 dist + `docker/nginx/nginx.conf` |

## 关键设计点

### 1. Redis

`redis:8.8-alpine` 容器启动命令：

```yaml
command: redis-server --appendonly yes --appendfsync everysec \
  --maxmemory 512mb --maxmemory-policy allkeys-lru \
  --requirepass ${REDIS_PASSWORD}
```

- **数据淘汰 `allkeys-lru` + `maxmemory 512mb`**：Redis 里存的全是可重建数据（Sa-Token 会话 + 逻辑过期商品缓存）。其中逻辑过期缓存的 key **物理无 TTL**（[RedisCache.java](../../../src/main/java/com/shop/common/RedisCache.java) 在 value 内存逻辑过期时间戳），因此**不能用 `volatile-lru`**——它只淘汰有 TTL 的 key，无 TTL 的缓存会永远占内存直到 OOM。`allkeys-lru` 从所有 key 中淘汰最久未使用，淘汰后下次读走「缓存不存在 → 同步重建」分支自动从 DB 恢复。必须配 `maxmemory`，否则默认不限制、淘汰永不触发。
- **AOF 持久化 `everysec`**：官方推荐档位，最多丢 1 秒数据（会话/缓存可重建，可接受）。`--appendonly yes` 开启 AOF 日志落盘到 `/data`（映射 `redis-data` 卷）。Redis 内置的「惰性删除 + 定期删除」过期机制无需配置，与应用层逻辑过期互不干扰。
- **密码 `requirepass ${REDIS_PASSWORD}`**：值由根目录 `.env` 提供（`spring-dotenv` 自动加载）。后端 `application.yml` 的 `spring.data.redis` 增加一行 `password: ${REDIS_PASSWORD:}`；若 `.env` 漏配，后端连 Redis 直接报认证错误，快速暴露问题（防呆优于静默失败）。
- **端口绑定 `127.0.0.1:6379`**：只允许本机访问，局域网不可达（Docker 默认 `6379:6379` 绑 `0.0.0.0`，无密码时局域网裸奔）。

### 2. RocketMQ broker（最易踩坑）

- **`brokerIP1=127.0.0.1`（单机开发场景）**：bridge 模式下 broker 默认向 namesrv 广播容器内网 IP（172.x），宿主机后端连不上，必须显式指定。后端明确跑宿主机，`127.0.0.1` 即可经 `10911` 端口映射直连 broker，且免去 DHCP 换网（家用路由 IP 会变）改配置的维护成本。直接写死在 `docker/rocketmq/broker.conf`，**不需要 `.env` 变量**。若将来后端容器化，需改为宿主机可达 IP。
- **内存限制**：官方镜像默认堆很大，小内存机器易崩溃，broker 加 `JAVA_OPT_EXT=-server -Xms256m -Xmx512m -XX:+UseG1GC`。
- **配置挂载路径版本敏感**：`apache/rocketmq:5.3.2` 镜像内目录为 `/home/rocketmq/rocketmq-5.3.2/conf/broker.conf`，broker.conf 需挂载到该路径，启动命令 `sh mqbroker -n namesrv:9876 -c /home/rocketmq/rocketmq-5.3.2/conf/broker.conf`。
- broker 其余关键项：`brokerId=0`（master）、`autoCreateTopicEnable=true`（简化开发）、`deleteWhen=04`、`fileReservedTime=48`、`brokerRole=ASYNC_MASTER`、`flushDiskType=ASYNC_FLUSH`。
- 后端走 Remoting 直连 broker（10911），**不启用** `--enable-proxy`，避免额外 8080/8081 端口。

### 3. Nginx

`docker/nginx/nginx.conf` 与现有 `D:\software\nginx-1.20.2\conf\nginx.conf` **除静态路径外逐字一致**，保留：API 反代到 `backend`（指向 `host.docker.internal:8080`）、CORS 头、Swagger 代理、Gzip、缓存策略、`client_max_body_size`。

三个前端 dist 挂载到容器内：
- `frontend/dist` → `/usr/share/nginx/html/user`（`location /` 指向）
- `frontend-admin/dist` → `/usr/share/nginx/html/admin`（`location /admin/` alias 指向）
- `frontend-merchant/dist` → `/usr/share/nginx/html/merchant`（`location /merchant/` alias 指向）

访问地址不变：`http://localhost`、`http://localhost/admin/`、`http://localhost/merchant/`。

**注意**：容器内 Nginx 反代后端必须用 `host.docker.internal:8080`（Windows Docker Desktop 内置域名指向宿主机），不能再用 `localhost:8080`（容器内 localhost 是容器自己）。

### 4. 数据持久化

- 全部使用 **named volume**：`docker compose down` 不删数据，`docker compose down -v` 才清。
- Qdrant 全新初始化，空库启动（collection 不建、索引不跑）。

### 5. 启动流程

```bash
# 0) 启动 Docker Desktop
# 1) 构建三个前端（dist 需重新构建）
cd D:/claudecode/shop/frontend          && npm run build
cd D:/claudecode/shop/frontend-admin    && npm run build
cd D:/claudecode/shop/frontend-merchant && npm run build
# 2) 配置 .env（若缺失）：追加 REDIS_PASSWORD=<redis密码>（brokerIP1 已固定 127.0.0.1，无需 HOST_IP）
# 3) 一键启动
cd D:/claudecode/shop && docker compose up -d
# 4) 验证
docker compose ps
redis-cli -p 6379 -a "$REDIS_PASSWORD" ping   # 应返回 PONG
curl http://localhost/api/product/page
# 5) 停止（保留数据）
docker compose down
# 彻底清除（含数据卷）
docker compose down -v
```

## 风险与注意

| 风险 | 说明 | 缓解 |
|------|------|------|
| brokerIP1 配错 | 后端下单/发消息连不上 broker | 单机已固定 `127.0.0.1` 规避；`docker logs rmqbroker` 检查注册地址 |
| Nginx 容器反代失效 | 容器内 `localhost:8080` 指向容器自身 | 用 `host.docker.internal:8080` |
| 镜像拉取慢 | RocketMQ 镜像较大 | 若本地已有镜像不重拉；可配置 Docker 镜像加速 |
| 端口被占 | 将来本地 Redis/Nginx 再启动会冲突 | 保持本地版退役，仅用 Docker 版 |
| Redis 密码未配 | 后端连不上 Redis，报 NOAUTH 认证错误 | 启动前确认 `.env` 有 `REDIS_PASSWORD`，且 compose 与 `application.yml` 占位同源 |

## 验证清单

- [ ] `docker compose ps` 五个容器均 Up
- [ ] `redis-cli -p 6379 -a "$REDIS_PASSWORD" ping` 返回 PONG
- [ ] 后端启动无 Redis 认证报错（`application.yml` 密码与 `.env` 一致）
- [ ] `curl http://localhost:6333` 返回 Qdrant 版本信息
- [ ] 后端启动无报错（Redis 会话、RocketMQ 生产者连接正常）
- [ ] **RocketMQ 实测（关键）**：`POST /api/order/create` 发一单 → 前端轮询到 orderId（异步落单成功）。这是 **4.9.7 客户端 ↔ 5.3.2 broker** 兼容性的唯一实证；若消费端拉不到消息（消息积压），回退 broker 镜像 `apache/rocketmq:4.9.7`
- [ ] **Qdrant 索引已建**：后端启动后 `curl http://localhost:6333/collections/products` 的 `points_count` > 0（依赖 `.env` 的 `DASHSCOPE_API_KEY` 有效）；或手动 `POST /api/admin/rag/rebuild` 触发
- [ ] `http://localhost`、`/admin/`、`/merchant/` 页面正常（重新构建后的 dist）
- [ ] `docker compose down` 后再 `up -d`，Redis/Qdrant 数据仍在（sa-token 会话可复用）
