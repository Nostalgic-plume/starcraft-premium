# 推荐子系统 — 实现说明

本文件记录**实现期对设计文档的补充决策与踩坑**。设计文档（需求分析 / 概要设计 / 详细设计）保持稳定不被实现细节污染，凡实现时需要额外知道的约定与教训记于此。

---

## 1 进程与工程结构

推荐子系统由**两个独立进程 + 一个独立 Flink 作业**构成：

| 部署单元 | 启动类 | 端口 | 构建产物 | 承载模块 |
|---|---|---|---|---|
| 商城后端 | `com.shop.ShopApplication` | 8080 | `shop-1.0.0.jar` | 商城业务 + M-01 行为采集 |
| 推荐服务 | `com.shop.rec.RecApplication` | 8082（仅绑定 127.0.0.1） | `shop-1.0.0-rec.jar` | M-03~M-10 |
| 计算作业 | `com.shop.rec.flink.FeatureJob` | — | `shop-rec-flink-1.0.0.jar` | M-02 + M-04 商品热度算子 |

**打包方式**：`spring-boot-maven-plugin` 配两个 execution。默认 execution 产出 `shop-1.0.0.jar`（商城后端，沿用原有部署方式不变），`repackage-rec` execution 带 `rec` classifier 产出 `shop-1.0.0-rec.jar`。

**组件隔离约定**（关键，漏了会导致定时任务在两个进程重复执行）：

| 组件类别 | Profile | 原因 |
|---|---|---|
| 推荐服务侧（M-03~M-10，含全部定时任务） | `@Profile("rec")` | 商城后端不得加载，否则批任务重复执行 |
| 商城后端侧（M-01 行为采集） | `@Profile("!rec")` | 推荐服务不承载行为采集入口 |
| 基础设施（数据源、RecRedis、RecConfigService） | 不加 | 两侧都需要：商城后端写行为明细，推荐服务读全部 |

`RecApplication` 只扫描 `com.shop.rec` 包，不加载商城业务组件（Controller / Service / Mapper 均不在其扫描范围内）。

**连带约束**：推荐服务进程内看不到商城 `config` 包下的组件（如 `MyMetaObjectHandler`）。凡推荐侧需要商城通用组件处，一律用 `ObjectProvider` 可选注入或自建，禁止硬依赖——首次启动即因此报错（`MetaObjectHandler` 找不到）。

**连带约束（测试）**：工程内有两个 `@SpringBootApplication`，而 `@SpringBootTest` 会从测试类所在包向上查找启动类。位于 `com.shop.rec` 包下的测试会被解析为推荐服务上下文（`RecApplication`），该上下文不加载商城侧 Mapper，导致依赖注入失败。凡测试 M-01 等商城侧组件，必须显式声明上下文：`@SpringBootTest(classes = ShopApplication.class)`；测试推荐服务侧组件则用 `RecApplication`。

**连带约束（Sa-Token）**：Sa-Token 的 `StpLogic.login()` 依赖 Web 上下文，在测试线程中直接调用会抛 `SaTokenContextException`。测试内模拟登录须用官方工具包裹：`SaTokenContextMockUtil.setMockContext(() -> { ... })`。

---

## 2 数据源

推荐库为**独立 database `shop_rec`**，与商城业务库 `shop` 物理分离；推荐服务对推荐库读写，对商城业务库只读。两库共用同一 MySQL 实例，账号可通过 `REC_DB_USERNAME` / `REC_DB_PASSWORD` 独立配置（未配置时回退到商城库账号）。

数据源参数前缀为 `rec.datasource`（独立于 `spring.datasource`，避免干扰 Spring Boot 的属性绑定）。

### 2.1 多数据源的连锁影响（重要教训）

Spring Boot 生态的自动配置普遍带 `@ConditionalOnMissingBean` 条件——含义是「容器里没有该**类型**的 Bean，我才创建」。**它是按类型退避，不按名字退避**：只要注册了任何同类型 Bean，对应自动配置就整体让位。

推荐库引入时注册的三个 Bean，恰好各自撞上一处主数据源链路的自动配置：

| 注册的 Bean | Bean 类型 | 被抑制的自动配置 | 未修复时的后果 |
|---|---|---|---|
| `recDataSource` | `DataSource` | Druid starter 主数据源 | 容器内只剩推荐库数据源，商城 Mapper 全部连到 `shop_rec`，启动即报 `Table 'shop_rec.product' doesn't exist` |
| `recSqlSessionFactory` | `SqlSessionFactory` | MyBatis-Plus 主 SqlSessionFactory | 商城 Mapper 落到推荐库的会话工厂 |
| `recTransactionManager` | `DataSourceTransactionManager` | 主事务管理器 | 商城 `@Transactional` 注入到推荐库事务管理器，事务边界与数据源错配 |

**修复方式**：新增 `com.shop.config.PrimaryDataSourceConfig`，显式声明主数据源三元组并标记 `@Primary`——`dataSource`（DruidDataSourceWrapper）、`sqlSessionFactory`（对齐 MyBatis-Plus 自动配置的装配内容）、`transactionManager`。商城侧配置取值完全不变，仅从"依赖自动配置"改为"显式声明"。

**结论**：多数据源不是"多配一个数据源"，而是必须接管主数据源的全套三元组。这是引入第二个数据源的固定成本，做架构选型时应计入。

### 2.2 两个附属坑

| 坑 | 现象 | 原因与解法 |
|---|---|---|
| Druid 数据源绑定静默失败 | 启动成功但 `jdbcUrl=null`、`username=null`，连接时才报错 | 不能把 `@ConfigurationProperties` 直接挂在 `DruidDataSource` 上（Boot 3 下需经 Druid 官方的 `DruidDataSourceWrapper` 承载）。解法：绑定到自定义 POJO `RecDataSourceProperties` 再显式装配 |
| `MybatisPlusProperties.getConfiguration()` 类型不符 | 编译报 `CoreConfiguration 无法转换为 MybatisConfiguration` | 该方法返回内部类 `CoreConfiguration`。解法：`new MybatisConfiguration()` 后调 `coreConfiguration.applyTo(configuration)`（与 AutoConfiguration 的做法一致） |

---

## 3 数据表与初始化

| 脚本 | 用途 | 执行顺序 |
|---|---|---|
| `src/main/resources/db/rec-schema.sql` | 建库 `shop_rec` + 7 张表 | 1 |
| `src/main/resources/db/rec-init-data.sql` | 25 项推荐策略配置（对应详细设计 9.2，含新增的 `limit.report.itemPerMinute` 与 `exclude.recent.days`） | 2 |

初始化脚本全部使用 `INSERT IGNORE`，可重复执行且不会覆盖运营已修改的配置值。

配置的运行时链路：推荐服务启动时从 `t_rec_config` 全量加载到进程内缓存，并同步写入内存库副本（`rec:cfg:{key}`）；之后每 30 秒比对 `rec:cfg:version`，发现变化即重载。配置不可读时回退到 `RecConfigKeys.DEFAULTS` 内置默认值。

---

## 4 消息队列

推荐链路使用 **Kafka**（选型理由见概要设计 5.1：事件时间语义与 Flink 集成成熟度），商城原有订单/消息链路继续使用 **RocketMQ**，两套并存。

**依赖选择**：使用原生 `kafka-clients`，不用 `spring-kafka`。后者会引入 `KafkaAutoConfiguration`，与商城现网的 RocketMQ 自动配置并存时徒增干扰面；而行为采集只需要生产者，原生客户端足够且能精确控制 acks、幂等与投递超时。实测：引入后商城后端启动与原有 RocketMQ 生产消费均无异常。

**部署**：`apache/kafka:3.7.0`，KRaft 单节点，归入 compose 的 `rec` profile（`docker compose --profile rec up -d`）。双 listener 配置——宿主机客户端走 `127.0.0.1:9092`，compose 网络内客户端走 `kafka:9093`；主题由 `kafka-init` 容器幂等创建。

### 4.1 幂等生产者与 acks 的硬约束（设计文档已修正）

Kafka 的幂等生产者**强制要求 `acks=all`**，配置为 `acks=1` 会在生产者构造时直接抛
`ConfigException: Must set acks to all in order to use the idempotent producer`，导致应用启动失败。

原设计同时要求「开启幂等」与「确认模式单副本确认（acks=1）」，二者不可共存。已按以下方式修正（详细设计 3.1、4.3 与精简版同步更新）：

- **保留幂等**（避免重试导致重复写入，更契合"事件不重复"的系统要求）；
- **确认模式改为 `acks=all`**。单机单副本部署下，`all` 就是等待唯一副本确认，**与 acks=1 等价，不额外增加等待时间**，因此不违背原设计对延迟的考量。

### 4.2 消费者并不在本工程

行为事件与商品变更事件的消费端均在独立 Flink 作业（`shop-rec-flink`）中，
商城工程只承担生产端，因此 `user-behavior` 主题在本工程内没有消费者组。

---

## 5 环境与构建备忘

| 项 | 说明 |
|---|---|
| 编译 JDK | Maven 默认 `JAVA_HOME` 为 JDK 21，而工程编译目标为 Java 22；构建时需显式指定 `JAVA_HOME=<jdk-22 路径>` |
| Flink 工程 | `shop-rec-flink` 编译目标 **Java 11**（Flink 1.17.2 不支持 Java 22），不可与商城工程合并构建 |
| 依赖坐标 | Flink 1.17 起 Kafka 连接器采用外部化版本号：`flink-connector-kafka:3.0.2-1.17`（已验证可解析） |
