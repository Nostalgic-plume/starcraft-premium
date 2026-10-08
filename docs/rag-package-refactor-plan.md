# RAG 包结构重构（分包）— 计划书

> 日期：2026-10-08　设计依据：`docs/rag-package-refactor-design.md`
> 目标：`com.shop.rag` 28 类 → 6 子包；`com.shop.config` 2 类错位归位。**零行为变更**

## 顶层子任务（7 个）

> **执行修正（2026-10-08）**：原计划给 Task 1–4 各设一个编译验收点，**该方式不成立** —— Java 包间引用是
> 原子性的：搬走 `rag.search` 后，包内类仍要引用尚在 `rag/` 根的 `RagProperties`、`ProductIndexer` 等，
> 跨包即缺 import，编译必失败；要让它通过就得先写指向旧位置的临时 import，等该类搬走再改一遍（同一行改两次）。
> 因此 28 个类的搬迁**一次做完再编译**，Task 1–4 只体现为操作步骤，验证点合并到「主代码搬迁完成」后。

| # | 顶层子任务 | 触及文件 | 验收方式 |
|---|-----------|---------|---------|
| 1 | 建骨架 + `rag.search`（9 类）+ 改 `RagSearchController` 的 2 处 import | 9 主 + 1 外部 | 累计至 #4 后一次编译 |
| 2 | `rag.index`（2 类）+ 改 `IndexAdminController` / `ProductServiceImpl` / `ProductServiceImplTest` 的 3 处 import | 2 主 + 3 外部 | 累计至 #4 后一次编译 |
| 3 | `rag.chat`（9 类）+ `rag.memory`（3 类）+ 改 `ChatController` 的 3 处 import | 12 主 + 1 外部 | 累计至 #4 后一次编译 |
| 4 | `rag.resilience`（2 类）+ `rag.config`（3 类） | 5 主 | `mvn -o compile` 无错误 |
| 5 | 测试目录归位（18 文件） | 18 测试 | `mvn -o test-compile` 无错误 + 离线单测全绿 |
| 6 | `config` 搬 2 个类（+ `BusinessException:11` 的 `@see` 更新） | 2 主 + 1 注释 | `mvn -o compile` 无错误 |
| 7 | 文档同步：`CLAUDE.md` 架构树 + 新增 `docs/rag-code-map.md` + 启动自测 | 2 文档 | 人工复核；`mvn spring-boot:run` 启动无 Bean 装配错误 |

**顺序理由**：先搬「被依赖最少」的 `search`（无下游 rag 子包依赖它之外的东西），再 `index`，再 `chat`/`memory`（依赖前两者），最后 `resilience`/`config`（装配层收尾）。`config` 类单独收尾，避免中途 `RagConfig` 找不到刚搬走的 `QdrantCollectionHelper`。

---

## Task 1：骨架 + `rag.search`

**动作**

1. 建目录 `src/main/java/com/shop/rag/search/`
2. `git mv` 以下 9 个文件到 `search/`，并把每个文件的 `package com.shop.rag;` 改为 `package com.shop.rag.search;`
   - `RagSearchService`、`KeywordSearchService`、`RrfMerger`、`RrfProperties`、`QueryRewriter`、`SynonymExpander`、`SynonymProperties`、`SkuPriceResolver`、`RagHitVO`
3. 本批内部引用（`RagSearchService` ↔ `RrfMerger` / `QueryRewriter` / `SynonymExpander` / `KeywordSearchService` / `RagHitVO`）同在新包，**无需新增 import**
4. 改外部 2 处：`RagSearchController:5-6` 的 `com.shop.rag.RagHitVO` / `com.shop.rag.RagSearchService` → 加 `.search`

**验收**：`mvn -o compile` → BUILD SUCCESS

---

## Task 2：`rag.index`

**动作**

1. 建目录 `src/main/java/com/shop/rag/index/`
2. `git mv` + 改 `package`：`ProductIndexer`、`QdrantCollectionHelper`
3. `ProductIndexer` 引用 `SkuPriceResolver`（已在 `search` 包）→ **新增** `import com.shop.rag.search.SkuPriceResolver;`
4. 改外部 3 处：
   - `IndexAdminController:5`、`ProductServiceImpl:35`、`ProductServiceImplTest:24` 的 `com.shop.rag.ProductIndexer` → `com.shop.rag.index.ProductIndexer`

**验收**：`mvn -o compile` → BUILD SUCCESS

---

## Task 3：`rag.chat` + `rag.memory`

**动作**

1. 建目录 `rag/chat/`、`rag/memory/`
2. `git mv` + 改 `package`：
   - `chat`：`ChatService`、`ChatIntentResolver`、`Intent`、`IntentWorkflow`、`OrderQueryWorkflow`、`CancelOrderWorkflow`、`WorkflowContext`、`AiAssistant`、`OrderQueryTool`
   - `memory`：`MySqlChatMemoryStore`、`ChatHistoryService`、`ChatFeedbackService`
3. **新增跨子包 import（本批工作量最大，集中在 `ChatService`）**：
   - `ChatService` 需新增：`rag.search.RagSearchService`、`rag.search.QueryRewriter`、`rag.resilience.CircuitBreaker`、`rag.resilience.ToolCallLogger`、`rag.memory.*`（`MySqlChatMemoryStore`/`ChatHistoryService`）等（以编译器实际报错为准）
   - `rag.chat` 内部互相引用（`ChatService` ↔ `ChatIntentResolver`/`Intent`/`WorkflowContext`/两个 workflow/`AiAssistant`/`OrderQueryTool`）同包，无需 import
   - `memory` 包内的三者互相引用同包，无需 import
4. 改外部 3 处：`ChatController:8-10` → `rag.memory.ChatFeedbackService`、`rag.memory.ChatHistoryService`、`rag.chat.ChatService`

**验收**：`mvn -o compile` → BUILD SUCCESS

---

## Task 4：`rag.resilience` + `rag.config`

**动作**

1. 建目录 `rag/resilience/`、`rag/config/`
2. `git mv` + 改 `package`：
   - `resilience`：`CircuitBreaker`、`ToolCallLogger`
   - `config`：`RagConfig`、`RagProperties`、`RagLlmProperties`
3. **新增跨子包 import**：`RagConfig` 需 `rag.index.QdrantCollectionHelper`；`ProductIndexer`（`index`）若引用 `RagProperties`（维度）需 `rag.config.RagProperties` —— 以编译器报错为准
4. **不动 `rag.llm` 配置前缀**（`@ConfigurationProperties(prefix = "rag.llm")` 与包名无关，`application.yml` 无需改动）

**验收**：`mvn -o compile` → BUILD SUCCESS

---

## Task 5：测试目录归位

**动作**

1. 建 `src/test/java/com/shop/rag/{search,index,chat}/`
2. `git mv` + 改 `package`：
   - `search/`：`KeywordSearchServiceTest`、`RagSearchServiceTest`、`RagFallbackTest`、`RrfMergerTest`、`RrfPropertiesBindingTest`、`SynonymExpanderTest`、`SynonymPropertiesBindingTest`、`RagMetricsTest`、`RagMetrics`、`RagEvaluationTest`
   - `index/`：`ProductIndexerTest`、`ProductIndexerRebuildTest`、`QdrantGrpcCompatibilityTest`
   - `chat/`：`ChatIntentResolverTest`、`CancelOrderWorkflowTest`、`OrderQueryWorkflowTest`、`BackupModelSmokeTest`、`DeepSeekAiServicesSmokeTest`
3. **包可见性检查**：访问 `RagLlmProperties.normalizeAndLog()`（包可见）的测试必须落在 `rag.config`；若无此类测试则忽略。**以 `mvn -o test-compile` 报错为准**，报错则把该测试移到 `rag.config`。
4. `RagMetrics` 是辅助类不是用例，勿漏搬。

**验收**：`mvn -o test-compile` 无错误；离线单测全绿：
`mvn -o test -Dtest=RrfMergerTest+SynonymExpanderTest+ProductIndexerTest+ChatIntentResolverTest+RrfPropertiesBindingTest+SynonymPropertiesBindingTest`

---

## Task 6：`config` 搬 2 个类

**动作**

1. 建 `src/main/java/com/shop/web/`、`src/main/java/com/shop/task/`
2. `git mv` + 改 `package`：
   - `GlobalExceptionHandler` → `com.shop.web`
   - `ScheduledTasks` → `com.shop.task`
3. 改 `BusinessException:11` 的 `@see com.shop.config.GlobalExceptionHandler` → `com.shop.web.GlobalExceptionHandler`
4. 二者均**零代码引用**（已核实，只有 javadoc/注释提及），无 import 需要改

**验收**：`mvn -o compile` → BUILD SUCCESS

---

## Task 7：文档同步 + 启动自测

**动作**

1. 更新 `CLAUDE.md`：
   - 「项目架构」树中 `rag/` 的 6 个子包与职责
   - `config/` 中移除 `GlobalExceptionHandler`、`ScheduledTasks`，标注新位置
2. 新增 `docs/rag-code-map.md`：28 类「类 → 子包 → 一句话职责」索引表
3. 启动自测（依赖 MySQL/Redis/Qdrant 就绪）：
   - `mvn spring-boot:run` 启动，确认无 Bean 装配错误（`@Qualifier` 双 bean 注入、`@ConfigurationProperties` 绑定、Qdrant 集合初始化）
   - Swagger `/swagger-ui.html` 可访问
   - 智能客服一问一答 + `/api/product/rag-search` 返回正常（依赖 TEI + Qdrant）

**验收**：启动日志无 ERROR；上述两条链路人工验证通过

---

## 验收记录

| # | 顶层子任务 | 完成日期 | 验收命令 | 实际输出摘要 | 结论 |
|---|-----------|---------|---------|-------------|------|
| 1 | 骨架 + `rag.search` | 2026-10-08 | 累计编译（含 #2–#4） | 28 类 `git mv` + `package` 声明改写，无旧包名残留 | ✅ 通过 |
| 2 | `rag.index` | 2026-10-08 | 同上 | 同上 | ✅ 通过 |
| 3 | `rag.chat` + `rag.memory` | 2026-10-08 | 同上 | 同上 | ✅ 通过 |
| 4 | `rag.resilience` + `rag.config` | 2026-10-08 | `mvn -o compile` | EXIT=0，1.968s BUILD SUCCESS；补齐 6 处跨子包 import + 4 处外部 import 改写 | ✅ 通过 |
| 5 | 测试目录归位（18 文件） | 2026-10-08 | `mvn -o test-compile` + 离线单测 | test-compile EXIT=0；`Tests run: 31, Failures: 0, Errors: 0, Skipped: 0` | ✅ 通过 |
| 6 | `config` 搬 2 类 | 2026-10-08 | `mvn -o test-compile` | EXIT=0，10.670s BUILD SUCCESS | ✅ 通过 |
| 7 | 文档同步 + 启动自测 | 2026-10-08 | 人工复核 + `mvn spring-boot:run` | `CLAUDE.md` 架构树 + `docs/rag-code-map.md` 已更新；**启动自测未执行** | ⚠️ 部分 |

**#7 未覆盖的验证缺口（如实登记）**：启动自测依赖的中间件均未运行（Docker 未启动，Redis 6379 / Qdrant 6333·6334 / TEI 9090 均未监听，仅 MySQL 3306 在跑），故以下三项**只有推理依据、没有实测证据**：

1. `@Qualifier("openAiStreamingChatModel")` / `("openAiStreamingChatModelBackup")` 双 bean 注入 —— 依据：Spring 默认 bean 名取类名（不含包），`lombok.config` 在项目根未被移动；
2. `@ConfigurationProperties(prefix = "rag.llm")` / `RagProperties` / `RrfProperties` / `SynonymProperties` 绑定 —— 依据：按 prefix 绑定与包名无关，`application.yml` 未改动；
3. `@RestControllerAdvice`（现 `com.shop.web.GlobalExceptionHandler`）与 `@Scheduled`（现 `com.shop.task.ScheduledTasks`）被扫描到 —— 依据：`@SpringBootApplication` 位于 `com.shop`，两个新包均为其子包，组件扫描覆盖。

**补齐方式**：`docker compose up -d` 起 Redis/Qdrant/ES/RocketMQ 后跑 `mvn spring-boot:run`，确认启动无 Bean 装配错误、`/swagger-ui.html` 可访问、客服一问一答正常。

## 回滚方式

每批结束若无法通过验收，`git checkout -- .` 回到上一批的提交点；建议每批单独提交，便于定点回滚。

## 已知风险

| 风险 | 影响 | 应对 |
|------|------|------|
| 漏改某个 `package` 声明或 import | 编译失败（**不会静默**） | 每批 `mvn -o compile` |
| 包可见成员访问断裂 | 编译失败 | Task 5 Step 3 |
| `lombok.config` 被误移 | 启动期 `@Qualifier` 丢失 → 双 bean 注入歧义 | 该文件已在项目根，全程不动 |
| 启动自测依赖 Qdrant/TEI/MySQL 未启 | 无法完成 Task 7 第 3 步 | 用 `docker compose up -d` 起中间件；未就绪则登记为「待环境」 |
