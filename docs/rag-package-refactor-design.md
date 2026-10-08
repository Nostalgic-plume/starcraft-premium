# RAG 包结构重构（分包）— 设计文档

> 日期：2026-10-08　范围：`com.shop.rag` 拆分为 6 个子包；顺带搬 `com.shop.config` 下 2 个错位类
> 类型：纯包结构重排（refactor），**零行为变更**

## 一、问题（代码实测）

| # | 现状 | 证据 | 后果 |
|---|------|------|------|
| 1 | `com.shop.rag` 下 **28 个类平铺**，无任何子目录 | 4131 行集中在 1 个包 | 找一类要扫全包；`ChatService`、`RagSearchService`、`RagConfig` 混在检索/记忆/韧性类之间 |
| 2 | 一个包混了 **7 种职责** | 见下表目标结构 | 包名无法自解释，新人（含面试官）无法从结构看出分层 |
| 3 | `com.shop.config` 混入 web 层与定时任务 | `GlobalExceptionHandler`（`@RestControllerAdvice`）、`ScheduledTasks`（`@Scheduled`） | 与"配置"语义不符 |

`common` / `controller` / `service` / `mapper` / `model` 不在本次范围内，理由见 §六。

## 二、硬约束

1. **零行为变更**：不改任何方法签名、接口契约、SQL、配置键。只改 `package` 声明、文件所在目录、`import` 语句。
2. **Bean 名不变**。Spring 默认 bean 名取**类名首字母小写（不含包名）**，故 `@Qualifier("openAiStreamingChatModel")`、`@Qualifier("openAiStreamingChatModelBackup")` 必须继续生效（`ChatService:56-59`、`ChatIntentResolver:34`、`QueryRewriter:37`）。
3. **不触碰的配置**：
   - `@MapperScan("com.shop.mapper")`（`ShopApplication:15`）
   - `type-aliases-package: com.shop.model.entity`、`mapper-locations: classpath:mapper/**/*.xml`（`application.yml:61-62`）
   - `lombok.config` —— 位于**项目根**（已核实），其 `copyableAnnotations`（让 `@Qualifier` 复制到 Lombok 构造器参数）按目录继承，子包仍生效。
4. **REST 路径与 SSE 事件名不变**（`/api/chat/*`、`/api/product/rag-search`、`route`/`products`/`message` 事件）。
5. **`git mv` 移动文件**，保留 git 历史（不用删除+新建）。
6. **每步编译验证**，不允许"全部改完再编译"。

## 三、目标结构

### 3.1 `com.shop.rag` → 6 个子包（28 类）

| 子包 | 类数 | 类 |
|------|------|-----|
| `rag.search` | 9 | `RagSearchService`、`KeywordSearchService`、`RrfMerger`、`RrfProperties`、`QueryRewriter`、`SynonymExpander`、`SynonymProperties`、`SkuPriceResolver`、`RagHitVO` |
| `rag.index` | 2 | `ProductIndexer`、`QdrantCollectionHelper` |
| `rag.chat` | 9 | `ChatService`、`ChatIntentResolver`、`Intent`、`IntentWorkflow`、`OrderQueryWorkflow`、`CancelOrderWorkflow`、`WorkflowContext`、`AiAssistant`、`OrderQueryTool` |
| `rag.memory` | 3 | `MySqlChatMemoryStore`、`ChatHistoryService`、`ChatFeedbackService` |
| `rag.resilience` | 2 | `CircuitBreaker`、`ToolCallLogger` |
| `rag.config` | 3 | `RagConfig`、`RagProperties`、`RagLlmProperties` |

合计 9+2+9+3+2+3 = 28 ✓

**依赖方向（已核实无环）**：

```
config ──→ index ──→ search
  │                    ↑
  └──→ (无)      chat ─┘
                 chat ──→ memory
                 chat ──→ resilience
```

- `SkuPriceResolver` 被 `KeywordSearchService`（检索路）与 `ProductIndexer`（索引路）共用 → 归 `search`，形成 `index → search` 单向依赖。
- `RagSearchService` **不**引用 `QdrantCollectionHelper`（维度自检读的是 `RagProperties.dimension`）→ **不存在 `search ↔ index` 互相依赖**（已用 grep 核实）。

### 3.2 `com.shop.config` 搬走 2 个类

| 类 | 迁往 | 依据 |
|----|------|------|
| `GlobalExceptionHandler` | `com.shop.web` | `@RestControllerAdvice`，web 层职责 |
| `ScheduledTasks` | `com.shop.task` | `@Scheduled` 定时任务 |

### 3.3 测试目录同步（`src/test/java/com/shop/rag/`）

| 目标包 | 测试文件 |
|--------|---------|
| `rag.search` | `KeywordSearchServiceTest`、`RagSearchServiceTest`、`RagFallbackTest`、`RrfMergerTest`、`RrfPropertiesBindingTest`、`SynonymExpanderTest`、`SynonymPropertiesBindingTest`、`RagMetricsTest`、`RagMetrics`（辅助类）、`RagEvaluationTest` |
| `rag.index` | `ProductIndexerTest`、`ProductIndexerRebuildTest`、`QdrantGrpcCompatibilityTest` |
| `rag.chat` | `ChatIntentResolverTest`、`CancelOrderWorkflowTest`、`OrderQueryWorkflowTest`、`BackupModelSmokeTest`、`DeepSeekAiServicesSmokeTest` |

> `RagMetrics` 是测试辅助类（非测试用例），跟着 `RagMetricsTest` 走。

## 四、改动清单

### 4.1 主代码

| 动作 | 文件数 | 说明 |
|------|-------|------|
| 改 `package` 声明 + `git mv` 目录 | 28（rag）+ 2（config） | 机械操作 |
| **新增跨子包 import** | 约 60~100 处 | ⚠️ 见 §五.2，这是主要工作量 |

### 4.2 外部 import 改写（8 处 / 5 文件，已逐一核实）

| 文件 | 现 import | 改为 |
|------|-----------|------|
| `ChatController:8` | `com.shop.rag.ChatFeedbackService` | `com.shop.rag.memory.ChatFeedbackService` |
| `ChatController:9` | `com.shop.rag.ChatHistoryService` | `com.shop.rag.memory.ChatHistoryService` |
| `ChatController:10` | `com.shop.rag.ChatService` | `com.shop.rag.chat.ChatService` |
| `IndexAdminController:5` | `com.shop.rag.ProductIndexer` | `com.shop.rag.index.ProductIndexer` |
| `RagSearchController:5` | `com.shop.rag.RagHitVO` | `com.shop.rag.search.RagHitVO` |
| `RagSearchController:6` | `com.shop.rag.RagSearchService` | `com.shop.rag.search.RagSearchService` |
| `ProductServiceImpl:35` | `com.shop.rag.ProductIndexer` | `com.shop.rag.index.ProductIndexer` |
| `ProductServiceImplTest:24` | `com.shop.rag.ProductIndexer` | `com.shop.rag.index.ProductIndexer` |

`RagTraceController` 不引用 `com.shop.rag.*`（直接用 mapper），无需改动。

### 4.3 注释级引用

| 文件 | 现内容 | 处理 |
|------|-------|------|
| `BusinessException:11` | `@see com.shop.config.GlobalExceptionHandler` | 改为 `com.shop.web.GlobalExceptionHandler`（javadoc 全限定名，必须改） |
| `ChatController:42,68` | 注释提到 `GlobalExceptionHandler 统一返回 401` | 自然语言，**不改** |
| `OrderDelayCancelConsumer:26` | javadoc 提到 `ScheduledTasks` | 自然语言，**不改** |

### 4.4 文档同步

- `CLAUDE.md`：架构树中 `rag/` 的分层描述、`config/` 中 `GlobalExceptionHandler`/`ScheduledTasks` 的位置。
- 新增 `docs/rag-code-map.md`：28 个类的「类 → 所属子包 → 职责」索引表，作为日常导航与面试讲项目的入口。
- `docs/` 下既有 6+ 份 rag 设计文档含 `QueryRewriter.java:41-57` 这类**行号级引用**：**历史文档不追溯改写**（它们记录的是当时的实现），仅在新文档标注路径变迁。

## 五、容易踩的坑

1. **包可见成员会因移包断裂**。`RagLlmProperties.normalizeAndLog()` 是**包可见**的（类注释明写"包可见以便单测在无 Spring 上下文时手动调用"）。访问它的测试**必须与它同在 `rag.config`** —— 若该测试被归到别的子包，编译期即报错（可发现，但需按目标包归位）。
2. **同包免 import 的红利消失**。28 个类原本同包、互相引用**不需要 import**；拆包后所有跨子包引用都要**新增 import**。`ChatService` 单文件引用 `CircuitBreaker`/`ToolCallLogger`/`RagSearchService`/`ChatHistoryService`/`QueryRewriter`/`MySqlChatMemoryStore`/`ChatIntentResolver`/两个 workflow 等 10+ 个类，是最大改动点。编译器会全部暴露，无静默失败路径。
3. **`@Qualifier` 依赖 `lombok.config` 的 `copyableAnnotations`**。已核实该文件在**项目根**（非包级），移包后仍继承生效。若误将它放到某个包目录下，移包就会静默丢失（构造器参数缺 `@Qualifier` → 双 bean 注入歧义 → 启动失败）。
4. **MyBatis 全限定类名**：已核实 `src/main/resources` 下**零** `com.shop.rag/config/common` 引用（`mapper/**/*.xml` 不涉及本次移包），无运行期隐性断裂。
5. **日志级别配置**：`application.yml` **未配置** `logging.level.com.shop.*`，移包不会丢失自定义级别。
6. **`RagMetrics` 不是测试用例**，是辅助类（无 `@Test`）；只按目录搬会漏掉它，或误当测试跑。
7. **不要漏改测试的 `package` 声明**：测试与被测类的包一旦不一致，包可见成员访问失败。

## 六、不做的事（决策记录）

| 方案 | 不做的理由 |
|------|-----------|
| **Maven 多模块拆分** | ① `rag` 与主业务**双向耦合**（`ChatService` 调 `OrderService`/`ProductMapper`，`RagSearchController` 调 `RagSearchService`），拆模块要先抽接口定契约；② 单体应用不会独立部署该模块，模块化的核心收益（依赖隔离/独立部署）用不上；③ 无第二个消费者复用，典型 YAGNI；④ 还要额外处理组件扫描、`@ConfigurationProperties` 绑定、测试装配 |
| **拆 `common`（11 类）** | `import com.shop.common.*` 共 **84 处 / 48 个文件**，而 `Result`/`BusinessException` 是全项目基础设施——移动它只产生大面积 churn，不能解决"找不到代码"（没人找不到 `Result`） |
| **垂直按业务域切分**（product/order/user/ai 各一域） | 18 张表、三端接口、MyBatis 全限定引用全要改；与 Spring 既有横向分层约定冲突；收益（域内聚）小于「面试讲不清为什么重建结构」的成本 |
| **`controller` 分域子包** | 只把 4 个 AI 域 Controller 挪进 `controller.ai`，会与其余 11 个平铺 Controller 风格不一致 |
| **`rag.chat.tool` 单独一层** | `OrderQueryTool` 仅 1 个类（写操作已剥离出 agent），单类包过薄 → 并入 `rag.chat` |

## 七、验收方式

| 层级 | 手段 |
|------|------|
| 编译期 | `mvn -o compile` / `mvn -o test-compile` 无错误（覆盖全部 import/package 问题） |
| 单测 | `src/test` 下可离线执行的 rag 单测全绿（`RrfMergerTest`、`SynonymExpanderTest`、`ProductIndexerTest`、`ChatIntentResolverTest`、`RrfPropertiesBindingTest`、`SynonymPropertiesBindingTest` 等） |
| 启动自测 | `mvn spring-boot:run` 启动无 Bean 装配错误（`@Qualifier` 双 bean、`@ConfigurationProperties` 绑定、`@RestControllerAdvice` 生效），Swagger 可访问 —— **依赖 MySQL/Redis/Qdrant 就绪** |
| 端到端 | 智能客服一问一答 + `/api/product/rag-search` 返回正常（依赖 TEI/Qdrant） |
