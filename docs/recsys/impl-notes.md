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

## 4 消息队列（待 T2 确认）

设计选型为 **Kafka 3.6**（理由是 Flink 事件时间语义与连接器成熟度），而商城现网使用的是 **RocketMQ 4.9.7**（已在 pom 中锁定版本并有完整生产消费封装）。

即：推荐链路使用 Kafka，商城原有消息链路继续用 RocketMQ，两套并存。实施 T2 时需要：
1. 在 pom 引入 Kafka 客户端依赖（注意与 RocketMQ 客户端共存时的自动配置干扰）；
2. 在 `docker/` 编排中加入 Kafka 3.6（KRaft 单节点）；
3. 确认新增的 Kafka 自动配置不会影响商城现有 RocketMQ 生产消费。

> 该节在 T2 完成后补充实际结论。

---

## 5 环境与构建备忘

| 项 | 说明 |
|---|---|
| 编译 JDK | Maven 默认 `JAVA_HOME` 为 JDK 21，而工程编译目标为 Java 22；构建时需显式指定 `JAVA_HOME=<jdk-22 路径>` |
| Flink 工程 | `shop-rec-flink` 编译目标 **Java 11**（Flink 1.17.2 不支持 Java 22），不可与商城工程合并构建 |
| 依赖坐标 | Flink 1.17 起 Kafka 连接器采用外部化版本号：`flink-connector-kafka:3.0.2-1.17`（已验证可解析） |
