# LangChain4j 0.36.1 → 1.x 升级实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 LangChain4j 从 `0.36.1` 升级到当前最新稳定 `1.19.0`（qdrant 集成模块用 `1.19.0-beta29`，见 Task 2），修复编译迁移，保持现有手动 RAG 链路（双路检索 + RRF + 手写 Qdrant REST）**功能不变**，并实测确认 qdrant-client 从 1.11.0 → 1.17.0 后 gRPC 兼容坑是否消失。

**Architecture:** 纯依赖升级 + 编译迁移，**不重构业务代码**。升级后 qdrant-client 与服务端 1.18.3 只差 1 个 minor（官方声明跨 minor 兼容），预计 gRPC 坑自愈；是否切换回官方 `QdrantEmbeddingStore.search()` 由 Task 6 实测裁决，**Phase 1 内保留手写 REST 绕道代码不动**。官方 RAG 链路迁移（AiServices/RetrievalAugmentor/记忆恢复）属 Phase 2，独立成后续计划，本计划不涉及。

**Tech Stack:** LangChain4j 1.x + Spring Boot 3.2.12 + JDK 22（编译 release 17）+ Qdrant 服务端 1.18.3（Docker，不动）+ DashScope（OpenAI 兼容接口）

**Spec:** 升级决策依据见会话结论（无独立 spec 文档）：
- 0.36.1 是 0.x 末期版本（0.36.2 后直接跳 1.0.0-alpha1），已停更。
- 0.36.1 依赖 qdrant-client 1.11.0，与服务端 1.18.3 差 7 个 minor，超出官方兼容范围 → gRPC 报 `Length of vector a (0)`，项目被迫手写 REST 绕道（`RagSearchService.searchViaQdrantRest`）。
- langchain4j 当前 `langchain4j-qdrant` 使用 qdrant-client **1.17.0**，与 1.18.3 只差 1 个 minor，官方保证跨 minor 双向兼容。
- 目标：只升级、不换库、不升服务端（除非 Task 6 实测失败触发次选）。

## Global Constraints

- **只改版本号与 import/API 适配，禁止改任何业务逻辑**（检索、合并、SSE、记忆、索引构建逻辑一字不动）。
- **保留手写 Qdrant REST 检索路径**（`RagSearchService.searchViaQdrantRest` 及其调用链），Phase 1 不删除、不切换。
- 不新增依赖（除明确必要的版本锁定）；不加新功能。
- 所有改动在 `feat/langchain4j-upgrade` 分支，每 Task 可独立提交、随时可回滚（git 回退 pom 一行即可）。
- 环境变量、`application.yml`、`.env`、Docker、Nginx 均不动。
- 代码注释保持中文，遵循项目既有风格。
- 配置文件中不出现任何明文凭证（api-key 继续走 `${DASHSCOPE_API_KEY}`）。

---

## File Structure

本次升级只触碰 **1 个构建文件 + 5 个源码文件**（`langchain4j` import 全部集中在这 5 个文件，已确认无其他引用点）：

| 文件 | 涉及 LangChain4j API | 预期改动 |
|------|----------------------|----------|
| `pom.xml` | 版本属性 + 4 个依赖 | 改版本号；可能加 qdrant-client / Netty 显式锁定 |
| `src/main/java/com/shop/rag/RagConfig.java` | OpenAiEmbeddingModel / OpenAiStreamingChatModel / QdrantEmbeddingStore | import + builder 签名微调 |
| `src/main/java/com/shop/rag/ProductIndexer.java` | Embedding / Metadata / TextSegment / OpenAiEmbeddingModel / EmbeddingMatch / QdrantEmbeddingStore | import + `addAll`/`removeAll` 签名微调 |
| `src/main/java/com/shop/rag/RagSearchService.java` | Embedding / TextSegment / OpenAiEmbeddingModel | import 微调（逻辑不动） |
| `src/main/java/com/shop/rag/ChatService.java` | AiMessage / SystemMessage / UserMessage / MessageWindowChatMemory / OpenAiStreamingChatModel / Response | import + `generate` 回调签名微调 |
| `src/main/java/com/shop/rag/QueryRewriter.java` | AiMessage / ChatMessage / SystemMessage / UserMessage / StreamingResponseHandler / OpenAiStreamingChatModel / Response | import + `generate` 回调签名微调 |

---

### Task 1: 基线备份与前置检查

**Files:**
- Modify: 无
- Create: `docs/superpowers/plans/dependency-tree-baseline.txt`（临时基线快照，提交时可删除）

**Interfaces:**
- Consumes: 无
- Produces: 升级前的依赖树基线 + Qdrant 服务端版本确认，供 Task 2 对比、回滚参考。

- [ ] **Step 1: 建特性分支**

```bash
cd "D:/claudecode/shop"
git checkout -b feat/langchain4j-upgrade
```

- [ ] **Step 2: 保存当前依赖树基线**

```bash
export JAVA_HOME="C:/Users/26373/.jdks/openjdk-22.0.1"
export PATH="$JAVA_HOME/bin:D:/software/maven/apache-maven-3.9.9/bin:$PATH"
mvn dependency:tree -Dincludes=dev.langchain4j,io.qdrant -DoutputFile=docs/superpowers/plans/dependency-tree-baseline.txt
```

Expected: 基线文件内容含 `langchain4j:0.36.1`、`langchain4j-qdrant:0.36.1`、`io.qdrant:client:1.11.0`。

- [ ] **Step 3: 确认 Qdrant 服务端版本**

```bash
curl -s http://localhost:6333/collections/products | head -c 400
# 或 docker 方式（以你现有部署为准）：
docker ps --format '{{.Names}} {{.Image}}'
```

Expected: Qdrant 服务端运行中，集合 `products` 存在，服务端版本 1.18.3（记录到 Task 6 判断依据）。

- [ ] **Step 4: 提交基线**

```bash
git add docs/superpowers/plans/dependency-tree-baseline.txt
git commit -m "chore(rag): 记录 LangChain4j 升级前依赖树基线"
```

---

### Task 2: pom.xml 版本升级

**Files:**
- Modify: `pom.xml:33`（版本属性）、`pom.xml:116-136`（4 个依赖块）

**Interfaces:**
- Consumes: Task 1 基线
- Produces: 升级后的 pom；`mvn dependency:tree` 应解析出 `langchain4j:1.19.0`、`langchain4j-qdrant:1.19.0-beta29`、`io.qdrant:client:1.17.0`。

- [ ] **Step 1: 修改版本属性**

已确认 Maven Central 最新稳定版为 `1.19.0`（2026-08）。将 `pom.xml:33` 的 `<langchain4j.version>` 改为：

```xml
<langchain4j.version>1.19.0</langchain4j.version>
```

**⚠️ 关键发现：`langchain4j-qdrant` 模块在 1.x 从未发布稳定版**（0.36.2 为其最后稳定版，1.x 仅发 beta），最新为 `1.19.0-beta29`，其 pom 依赖 `langchain4j-core:1.19.0` + `io.qdrant:client:1.17.0`，与核心稳定版配套。故 `pom.xml:133-136` 的 `langchain4j-qdrant` 依赖必须**单独指定版本**（覆盖 `${langchain4j.version}`）：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-qdrant</artifactId>
    <version>1.19.0-beta29</version>
</dependency>
```

其余 `langchain4j` / `langchain4j-ollama` / `langchain4j-open-ai` 三个依赖继续用 `${langchain4j.version}`（1.19.0）。

- [ ] **Step 2: 校验 4 个依赖解析版本**

```bash
mvn dependency:tree -Dincludes=dev.langchain4j,io.qdrant -Dverbose
```

Expected: `langchain4j:1.19.0`、`langchain4j-open-ai:1.19.0`、`langchain4j-qdrant:1.19.0-beta29`、`io.qdrant:client:1.17.0`。

- [ ] **Step 3: 处理 qdrant-client 传递依赖冲突（Netty/Protobuf）**

社区已知 qdrant-client 会带出 Netty/Protobuf 与 Spring Boot 3.2 冲突。在 `pom.xml` 的 `<dependencies>` 内（RocketMQ 依赖块附近）加入显式锁定（**仅在 `mvn dependency:tree -Dverbose` 显示版本冲突时执行**，若无冲突跳过本步）：

```xml
<!-- qdrant-client 传递依赖版本锁定（防 Netty/Protobuf 与 Boot 3.2 冲突） -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
    <version>4.1.115.Final</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 4: 提交**

```bash
git add pom.xml
git commit -m "build(rag): 升级 LangChain4j 0.36.1 → 1.19.0（qdrant 集成 1.19.0-beta29）"
```

---

### Task 3: 尝试 OpenRewrite 自动迁移

**Files:**
- Modify: 5 个 rag 源码文件（由 recipe 自动改写）
- Test: 无（编译验证在 Task 4）

**Interfaces:**
- Consumes: Task 2 的 pom
- Produces: 若 recipe 成功，import 与 API 自动迁移到位；`git diff` 可见改动清单。

- [ ] **Step 1: 运行官方迁移 recipe**

官方 recipe 针对 1.0.0-rc1/beta4，对更高版本不保证全量覆盖，先尝试：

```bash
cd "D:/claudecode/shop"
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.7.0:run \
  -Drewrite.recipeArtifactCoordinates=dev.langchain4j:langchain4j-openrewrite-recipes:1.0.0-rc1 \
  -Drewrite.activeRecipes=dev.langchain4j.UpgradeToRc1AndBeta4
```

- [ ] **Step 2: 审查 recipe 改动**

```bash
git diff --stat
```

Expected: 若 recipe 成功，`pom.xml` + 5 个 rag 文件有改动。**审查每个改动**，确认没有误改业务逻辑（尤其 `RagSearchService` 的 REST 检索体、`ChatService` 的 SSE 逻辑）。recipe 失败（网络/版本不匹配）则跳过本 Task，直接进 Task 4 手动修复，不算失败。

- [ ] **Step 3: 提交 recipe 结果**

```bash
git add -A
git commit -m "chore(rag): OpenRewrite 自动迁移 LangChain4j API"
```

（若 recipe 无改动，跳过本步）

---

### Task 4: 手动编译修复（核心迁移）

**Files:**
- Modify: `RagConfig.java` / `ProductIndexer.java` / `RagSearchService.java` / `ChatService.java` / `QueryRewriter.java`

**Interfaces:**
- Consumes: Task 2 的 pom；Task 3 的 recipe 结果（若有）
- Produces: 编译通过；5 个文件 import 与 API 调用对齐 1.x。

- [ ] **Step 1: 编译，收集全部错误**

```bash
export JAVA_HOME="C:/Users/26373/.jdks/openjdk-22.0.1"
export PATH="$JAVA_HOME/bin:D:/software/maven/apache-maven-3.9.9/bin:$PATH"
cd "D:/claudecode/shop"
mvn clean compile -q 2>&1 | grep -E "ERROR|error:" | head -60
```

Expected: 编译错误清单（新 API 报错集中在 import 与模型 builder / generate / addAll 调用点）。

- [ ] **Step 2: 修复 import 包路径（对照表）**

按编译错误，逐个修正 5 个文件的 import。1.x 已知迁移（0.36.1 → 1.x）对照：

| 0.36.1 位置 | 1.x 位置 | 涉及文件 |
|-------------|----------|----------|
| `dev.langchain4j.model.openai.OpenAiEmbeddingModel` | 包路径保留（OpenAI SDK v4，**需编译确认**） | RagConfig / ProductIndexer / RagSearchService |
| `dev.langchain4j.model.openai.OpenAiStreamingChatModel` | 同上，接口由 `StreamingChatLanguageModel` 改名 `StreamingChatModel` | RagConfig / ChatService / QueryRewriter |
| `dev.langchain4j.memory.chat.MessageWindowChatMemory` | 包路径大概率保留，`ChatMemory` 接口迁至 `dev.langchain4j.memory.chat.ChatMemory` | ChatService |
| `dev.langchain4j.model.StreamingResponseHandler` | 包路径大概率保留 | QueryRewriter / ChatService |
| `dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore` | 包路径保留，`findRelevant` 移除（项目未用） | RagConfig / ProductIndexer |

**执行准则：以编译器错误为准**——编译器提示的"找不到符号 / 包不存在"即为迁移清单，逐个改 import；未被编译器报错的 import 一律不动（避免过度改动）。

- [ ] **Step 3: 修复模型 builder 与核心 API 签名**

重点核对 4 处（按编译器报错提示调整签名）：

1. `RagConfig.openAiEmbeddingModel` / `openAiStreamingChatModel`：builder 方法名如 `baseUrl`/`apiKey`/`modelName`/`temperature`/`timeout` 若报错，查该版本 `OpenAiEmbeddingModel.builder()` 方法签名替换（1.x 可能改用 `OpenAiChatModel` 的构造参数风格，以编译报错驱动）。
2. `ProductIndexer`：`embeddingModel.embedAll(batch).content()` 返回类型若由 `List<Embedding>` 变为其他，按编译报错适配；`embeddingStore.addAll(...)` / `add(...)` / `removeAll(...)` 签名微调。
3. `ChatService` / `QueryRewriter`：`streamingChatModel.generate(messages, handler)` 的 `StreamingResponseHandler` 回调方法（`onNext`/`onComplete(Response<AiMessage>)`/`onError`）若签名变化，按编译报错修正。
4. 若编译报"集合可空性"类语义变化（1.x 返回非空集合），确认现有 `null` 判空代码**不冲突**即可，不必删判空。

- [ ] **Step 4: 增量编译直到通过**

```bash
mvn clean compile -q
```

Expected: `BUILD SUCCESS`，0 个 ERROR。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/shop/rag/
git commit -m "refactor(rag): 适配 LangChain4j 1.x API 迁移"
```

---

### Task 5: 全量编译与依赖树复核

**Files:**
- Modify: 无
- Test: `mvn clean package -DskipTests`

**Interfaces:**
- Consumes: Task 4 修复结果
- Produces: 可打包产物 + 最终依赖树确认。

- [ ] **Step 1: 全量打包**

```bash
mvn clean package -DskipTests -q
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 复核最终依赖**

```bash
mvn dependency:tree -Dincludes=dev.langchain4j,io.qdrant
```

Expected: `langchain4j:1.19.0`、`langchain4j-qdrant:1.19.0-beta29`、`io.qdrant:client:1.17.0`。**确认 qdrant-client 已从 1.11.0 升到 1.17.0**——这是 gRPC 坑自愈的关键前提。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "build(rag): 复核 LangChain4j 1.x 依赖树"
```

---

### Task 6: gRPC 兼容性裁决（关键决策点）

**Files:**
- Create: `src/test/java/com/shop/rag/QdrantGrpcCompatibilityTest.java`（临时裁决测试，Task 8 决定去留）

**Interfaces:**
- Consumes: Task 4 编译通过、Task 5 依赖树确认 qdrant-client 1.17.0
- Produces: **硬性裁决**：`QdrantEmbeddingStore.search()` 是否恢复正常。此结果决定后续路径——修复则 Phase 2 可切换官方检索器；未修复则执行次选（Docker 升级 Qdrant 到 1.17/1.18 对齐）。

- [ ] **Step 1: 编写临时裁决测试**

创建 `src/test/java/com/shop/rag/QdrantGrpcCompatibilityTest.java`：

```java
package com.shop.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临时裁决测试：验证 qdrant-client 1.17.0 与 Qdrant 1.18.3 的 gRPC 兼容性。
 * 通过 = 官方 search() 可用，后续可弃用手写 REST 绕道；失败 = 触发次选方案。
 */
@SpringBootTest
class QdrantGrpcCompatibilityTest {

    @Autowired
    private QdrantEmbeddingStore embeddingStore;

    @Autowired
    private OpenAiEmbeddingModel embeddingModel;

    @Test
    void grpcSearch_shouldNotThrowVectorLengthError() {
        // 1. 生成一个真实 1536 维查询向量（embedding 服务必须可用）
        Embedding query = embeddingModel.embedAll(List.of(TextSegment.from("手机"))).content().get(0);

        // 2. 走官方 gRPC search() 路径（0.36.1 在此处抛 "Length of vector a (0)"）
        List<EmbeddingMatch<TextSegment>> matches = assertDoesNotThrow(
                () -> embeddingStore.search(query, 5, null));

        // 3. 若返回非空，说明不仅 gRPC 通、检索也正常
        System.out.println("gRPC search 成功，命中 " + matches.size() + " 条");
        assertNotNull(matches);
    }
}
```

> 注：若编译报 `search(Embedding, int, Filter)` 签名不存在，按 1.19.0 的 `EmbeddingStore` 接口实际签名调整（可能含 `minScore` 重载）；`null` filter 表示不过滤。

- [ ] **Step 2: 运行裁决测试**

```bash
mvn test -Dtest=QdrantGrpcCompatibilityTest -Dspring.profiles.active=dev
```

Expected（三种可能，记录结果）：
- **PASS 且命中 >0**：gRPC 坑已修复 → 记录 `GRPC_FIXED`，后续可进 Phase 2。
- **PASS 且命中 =0**：gRPC 通但集合可能为空 → 重新触发索引重建后再测，仍为 0 则记录 `GRPC_OK_EMPTY`（不算失败）。
- **FAIL（仍抛 Length of vector 或连接错误）**：记录 `GRPC_BROKEN` → 执行 Step 3 次选方案。

- [ ] **Step 3（仅当 FAIL 时）：次选方案——升级 Docker Qdrant 对齐版本**

```bash
# 以你现有 Docker 部署方式为准；拉取与客户端对齐的镜像并重启
docker pull qdrant/qdrant:latest          # 若本地 1.18.3 已是新版本则优先降到 v1.17.x 对齐
docker stop <现有qdrant容器>
docker rm <现有qdrant容器>
docker run -d --name qdrant \
  -p 6333:6333 -p 6334:6334 \
  -v /path/to/qdrant_storage:/qdrant/storage \
  qdrant/qdrant:v1.17.1
```

> Qdrant 存储仅保证 1 个 minor 内兼容，`1.18.3` 数据在 `1.17.x` 可安全加载。升级后**重跑本 Task Step 2**，直到裁决为 `GRPC_FIXED`。

- [ ] **Step 4: 提交裁决结果（注释说明去留）**

```bash
git add src/test/java/com/shop/rag/QdrantGrpcCompatibilityTest.java
git commit -m "test(rag): 裁决 qdrant-client 1.17.0 与 Qdrant 1.18.3 gRPC 兼容性"
```

---

### Task 7: 全链路启动自测（功能回归）

**Files:**
- Modify: 无（纯验证）
- Test: 启动应用 + 手动 API 验证

**Interfaces:**
- Consumes: Task 4/5/6 全部结果
- Produces: 升级后全链路可用性证据（索引写入 / 语义搜索 / 智能客服 SSE / 非 RAG 业务）。

- [ ] **Step 1: 启动应用**

```bash
cd "D:/claudecode/shop"
mvn spring-boot:run
```

Expected: 启动日志中 `Qdrant 集合 'products' 已存在，跳过创建`（或 ensure 正常）；无 Bean 创建异常。

- [ ] **Step 2: 验证索引重建（若集合为空）**

若 Task 6 裁决测试显示集合为空，触发一次全量重建：

```bash
curl -s "http://localhost:8080/api/merchant/stats/dashboard" | head -c 200   # 确认服务就绪
```

在日志中观察 `ProductIndexer` 是否执行 `rebuildAll`；若集合已有数据则跳过重建（日志 `Qdrant 集合已有向量数据，跳过索引重建`）。

- [ ] **Step 3: 语义搜索回归（REST 绕道路径必须正常）**

```bash
curl -s "http://localhost:8080/api/product/rag-search?q=手机&topK=5" | head -c 500
```

Expected: 返回 `hits` 数组非空，结构含 `productId/name/score/excerpt`，无异常堆栈。

- [ ] **Step 4: 智能客服 SSE 回归**

```bash
curl -s -N "http://localhost:8080/api/chat/ask?conversationId=1&userId=1" \
  -H "Content-Type: application/json" -X POST \
  -d '{"message":"推荐一款手机"}' | head -c 1000
```

Expected: 流式返回 `type: token` 事件，最终 `type: end`；无 `error` 事件。（若该接口需登录，用已登录会话的 token 请求头）

- [ ] **Step 5: 非 RAG 业务抽查（确认升级未波及）**

```bash
curl -s "http://localhost:8080/api/product/page?pageNum=1&pageSize=5" | head -c 300
curl -s -X POST "http://localhost:8080/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"123456"}' | head -c 200
```

Expected: 商品分页正常、登录正常（返回 code=0 与 token）。

- [ ] **Step 6: 记录并提交验证结果**

```bash
git add -A
git commit -m "chore(rag): 升级后全链路自测通过（索引/搜索/客服/登录）"
```

---

### Task 8: 收尾与文档更新

**Files:**
- Modify: `CLAUDE.md`（版本记录）、`docs/README.md`（若含版本表）
- Delete: `src/test/java/com/shop/rag/QdrantGrpcCompatibilityTest.java`（临时裁决测试，**仅当 Task 6 为 PASS 且确认后续不再需要时删除**；若需保留为长期回归测试则移动至正式测试包）

**Interfaces:**
- Consumes: 全部任务结果
- Produces: 干净的最终提交 + 文档版本更新。

- [ ] **Step 1: 决定裁决测试去留**

Task 6 结果为 `GRPC_FIXED`：若 Phase 2 已规划切换官方 `search()`，**保留**该测试作为回归（移动到 `src/test/java/com/shop/rag/` 正式位置并保留注释）；若结果非 `GRPC_FIXED` 或不想保留，删除该文件。

- [ ] **Step 2: 更新 CLAUDE.md 版本记录**

将 `CLAUDE.md` 中技术栈表格的 RAG/智能客服行更新（示例）：

```markdown
| RAG/智能客服 | LangChain4j + Qdrant（1536维）+ DashScope（qwen-plus / text-embedding-v1） | 1.19.0 / 1.18.3 |
```

并检查 `pom.xml` 注释、`docs/README.md` 中提到的 LangChain4j 版本是否需同步。

- [ ] **Step 3: 清理临时文件**

删除 Task 1 的 `docs/superpowers/plans/dependency-tree-baseline.txt`（基线已随 Task 完成验证完毕，如需留存则移到 `docs/` 非 plans 目录）。

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "docs(rag): 更新 LangChain4j 版本记录，收尾升级"
```

- [ ] **Step 5: 全量回归打包**

```bash
mvn clean package -DskipTests -q
```

Expected: `BUILD SUCCESS`，升级完成。

---

## 后续计划预告（Phase 2，独立计划，不在此计划内）

本计划完成后，若 Task 6 裁决为 `GRPC_FIXED`，可另立 `docs/superpowers/plans/YYYY-MM-DD-rag-official-retrieval.md`，内容为（不在本计划执行）：

1. 删掉 `RagSearchService.searchViaQdrantRest` 手写 REST（约 150 行）与 `QdrantCollectionHelper` 中冗余部分。
2. 引入 `langchain4j-community-dashscope`（原生 qwen 模型）或保留 `langchain4j-open-ai` 兼容层，二选一决策。
3. 用 `AiServices.builder()` + 自定义 `ContentRetriever`（保留双路检索 + RRF 逻辑）替换手动拼装。
4. 加 `ChatMemoryProvider` 实现记忆从 MySQL 恢复（解决重启失忆）。
5. 每步独立验证 + 独立提交。

**Phase 2 触发前提**：本计划 Task 6 裁决 `GRPC_FIXED`；否则先解决 Qdrant 版本对齐再评估。