# RAG 向量路迁移设计：DashScope → 本地 BGE-M3

> 2026-09-24 · 因 DashScope 额度 10 月过期，向量化与降级备用模型须换血

## 1. 背景与驱动力

| 事实 | 影响 |
|------|------|
| DashScope API Key **10 月过期** | embedding 与 qwen-plus 备用模型同时失效 |
| 现有 embedding：`text-embedding-v1`（**1536 维**，上下文 2048） | 长商品描述被截断 |
| 本地已有 **BGE-M3**（1024 维，上下文 8192，多语言） | 可直接本地化部署 |
| 离线数仓/RAG 依赖向量路 | 过期后若不处理，检索退化为纯关键词路 |

### 迁移的额外收益

- 不消耗云额度、不受"过期"制约
- 上下文 2048 → **8192 tokens**，商品长描述不必再截断
- BGE 系列中文表现强于 text-embedding-v1
- 顺带获得「Qdrant MCP 可用」的前提（模型一致）

## 2. 硬约束

| # | 约束 | 说明 |
|---|------|------|
| C1 | **query 与库内向量必须同模型同维度** | 换模型 = 全量重建索引，旧向量作废 |
| C2 | **Qdrant 集合维度不可修改** | 只能删集合重建，且必须在**创建时**指定维度 |
| C3 | **`rebuildAll()` 启动钩子会跳过重建** | 集合有数据即跳过，维度变更后必须走 `forceRebuildAll()` |
| C4 | **chat 主模型不受影响** | DeepSeek 独立 key，与 DashScope 无关 |
| C5 | **备用模型 qwen-plus 同属 DashScope** | 必须一起换，否则主备降级链断一环 |
| C6 | 三端前端与业务接口**不受影响** | 本次只动 RAG 内部链路 |
| C7 | 密钥一律环境变量注入 | 新增端点若需 key，走 `.env`，禁止硬编码 |

## 3. 现状（已核实）

### 3.1 代码结构

```
RagConfig
├── openAiEmbeddingModel            baseUrl 硬编码 dashscope  ← 要改
├── openAiStreamingChatModel        baseUrl api.deepseek.com  ← 不动
├── openAiStreamingChatModelBackup  baseUrl 硬编码 dashscope  ← 要改（C5）
└── qdrantEmbeddingStore            读 rag.qdrant.*            ← 不动

QdrantCollectionHelper.createCollection  ← 维度取自 ragProperties.getDimension()
ProductIndexer.rebuildAll()              ← @ApplicationReadyEvent，有数据则跳过（C3）
ProductIndexer.forceRebuildAll()         ← 不检查，直接 doRebuild()
ProductIndexer.doRebuild()               ← 首步 recreateCollection()：删集合+重建+重索引
IndexAdminController  POST /api/index/rebuild  ← forceRebuildAll() 的 REST 入口
```

**结论：项目已使用 `OpenAiEmbeddingModel`（OpenAI 兼容协议）**，TEI 的 `/v1/embeddings` 同协议，**改造量极小**。

### 3.2 现有配置

```yaml
rag:
  qdrant:
    host: localhost
    port: 6334
    collection-name: products
    dimension: 1536          # ← 改 1024
  llm:
    api-key: ${DASHSCOPE_API_KEY}
    embedding-model: text-embedding-v1   # ← 改 bge-m3
    chat-model: deepseek-chat            # 不动
    backup-model: qwen-plus              # ← 需换供应商（C5）
```

## 4. 目标架构

```
                    ┌─────────────────────────────┐
  商品文本 / query ──▶│  TEI (Docker, GPU)          │
                    │  BAAI/bge-m3 → 1024 维       │
                    │  http://localhost:8090/v1    │
                    │  ⚠️ 不可用 8080（被 server.port 占）│
                    └──────────┬──────────────────┘
                               │ OpenAI 兼容 /v1/embeddings
                  ┌────────────▼─────────────┐
                  │ LangChain4j              │
                  │ OpenAiEmbeddingModel     │
                  └────────────┬─────────────┘
                               ▼
                      Qdrant collection「products」1024d
```

## 5. 改动清单

### 5.1 配置项（`application.yml`）

| 键 | 现值 | 目标值 | 说明 |
|----|------|--------|------|
| `rag.qdrant.dimension` | 1536 | **1024** | BGE-M3 维度 |
| `rag.llm.embedding-base-url` | （硬编码） | `http://localhost:8080/v1` | **新增**，TEI 端点 |
| `rag.llm.embedding-api-key` | （硬编码） | `${TEI_API_KEY:dummy}` | **新增**，TEI 不校验但 SDK 要求非空 |
| `rag.llm.embedding-model` | text-embedding-v1 | `bge-m3` | TEI 忽略该字段，SDK 必填 |
| `rag.llm.backup-base-url` | （硬编码） | 新供应商端点 | **新增**（C5） |
| `rag.llm.backup-api-key` | `${rag.llm.api-key}` | `${BACKUP_LLM_API_KEY}` | 改（C5） |

> `rag.llm.api-key`（DashScope）在 embedding 与 backup 都切走后**可退役**，但保留不删更安全（回滚用）。

### 5.2 代码（2 个文件）

| 文件 | 改动 |
|------|------|
| `RagProperties.java` | `dimension` 默认值 1536→1024（**不要在这里加 llm 字段**：其前缀是 `rag.qdrant`，加 `embeddingBaseUrl` 会绑到 `rag.qdrant.embedding-base-url`，与 yml 的 `rag.llm.*` 对不上） |
| `RagLlmProperties.java`（新建） | `@ConfigurationProperties(prefix="rag.llm")`，承载 embedding / backup / chat 端点与 Key；`@PostConstruct` 做空值归一化 + 启动期回显 |
| `RagConfig.java` | `openAiEmbeddingModel` 与 `openAiStreamingChatModelBackup` 的 `baseUrl` 由硬编码改为读配置 |

**不改**：`QdrantCollectionHelper`（维度已配置化）、`ProductIndexer`（重建逻辑通用）、`RagSearchService`、`ProductIndexer` 的索引字段、三端前端。

### 5.3 数据侧

| 项 | 处理 |
|----|------|
| Qdrant 集合 | **删除重建**（由 `doRebuild()` 的第一步自动完成，无需手工删） |
| 商品索引 | 全量重建（当前 278 个上架商品） |
| 评估集 `RagEvaluationTest` | **必须重建**（商品数据经 DataForge 重新生成后，旧标注的 id/名称全部失效） |

## 6. 容易踩的坑

### 6.1 ⚠️ 重启不会自动重建索引（C3）

`rebuildAll()` 发现集合已有数据即跳过。改完 `dimension` 后**必须手动**：

```bash
curl -X POST http://localhost:8080/api/admin/rag/rebuild -H "token: <admin token>"
```

否则集合停留在 1536 维，TEI 返回 1024 维 → 写入/检索报维度不匹配。

### 6.2 ⚠️ 重建前必须确认 TEI 已就绪

`doRebuild()` 首步 `recreateCollection()` 会**先删掉现有集合**。若此刻 TEI 不可用，集合被删而数据补不回来 → **检索直接不可用**。

**正确顺序**：TEI `/health` 通过 → 再触发重建。

### 6.3 ⚠️ 降级链会断一环（C5）

主备链：`DeepSeek(带工具) → 熔断 → qwen(纯 RAG) → 模板兜底`。

qwen-plus 走 DashScope，过期后该环失效，熔断时**直接掉到模板兜底**。必须同步换备用模型供应商。

### 6.4 TEI 返回的向量维度要实测

TEI `/info` 或实际请求确认返回 **1024** 维。若与配置不一致（例如挂载了别的模型），重建出的集合与向量不匹配。

### 6.5 apiKey 非空约束

TEI 不校验鉴权，但 `OpenAiEmbeddingModel` 要求 `apiKey` 非空 → 给占位值（如 `dummy`），**不要留空**。

### 6.6 若启用 Qdrant MCP：一致性必须验证

MCP 用 FastEmbed 的量化 ONNX BGE-M3，项目用 TEI 的 FP16/ONNX 导出，**同模型不同导出可能不一致**。启用前用同一段文本两边各算一次，余弦相似度 **>0.99** 才可用，否则调试会被假象误导。

### 6.7 商品详情截断值可放宽

`ProductIndexer.MAX_DETAIL_LENGTH = 500` 是为适配 DashScope 2048 上下文设的防护。BGE-M3 支持 8192，可评估放宽（**本次不必须**，作为独立优化）。

## 7. 回滚方案

| 层 | 回滚动作 |
|----|----------|
| 配置 | `dimension` 改回 1536、`embedding-base-url` 指回 DashScope（key 未删） |
| 索引 | 再次触发 `POST /api/index/rebuild` 重建 1536 维集合 |
| 前提 | DashScope key 在有效期内 —— **过期后此回滚路径失效**，故迁移需在过期前完成 |

## 8. 验收标准

| # | 验收项 | 方式 |
|---|--------|------|
| 1 | TEI 返回 1024 维 | `curl POST /v1/embeddings` 数长度 |
| 2 | 集合维度为 1024 | `curl GET /collections/products` 看 `size` |
| 3 | 索引条数 = 上架商品数 | 对比 `countPoints()` 与 `SELECT COUNT(*) FROM product WHERE status=1` |
| 4 | 向量路检索可用 | Swagger `/api/product/rag-search` 返回合理结果 |
| 5 | **检索质量不劣于基线** | 重跑评估集，对比 Recall@3=0.425 / Recall@5=0.686 / MRR=0.368 |
| 6 | 降级链完整 | 熔断主模型，确认备用模型生效 |
| 7 | 关键词路未受影响 | 关闭向量路，确认 MySQL FULLTEXT 仍工作 |

**第 5 项是决策点**：若新指标显著低于基线，需评估是否回退或调整（BGE-M3 未必在所有语料上优于 text-embedding-v1，已有公开实验出现回退案例）。

> ⚠️ **基线取值更正（2026-09-27 复评时发现）**：上表第 5 项的 `0.425 / 0.686 / 0.368` 引自 Phase 2 时期
> （`docs/superpowers/plans/phase2-rag/tasks.md`），当时尚无「候选池对齐 / 同义词扩展 / 加权 RRF」三项改动，
> 拿它对比会把这三项收益误算到 embedding 迁移头上。**唯一可比基线是 `docs/rag-evaluation-report.md` §8 的表**
> （2026-09-20，同评估集、同检索代码，仅 embedding 不同）。

### 验收执行记录（2026-09-27）

| # | 验收项 | 结果 | 证据 |
|---|--------|------|------|
| 1 | TEI 返回 1024 维 | ✅ | `POST /v1/embeddings` 实测 `dim = 1024` |
| 2 | 集合维度为 1024 | ✅ | `GET /collections/products` → `size=1024, distance=Cosine` |
| 3 | 索引条数 = 上架商品数 | ✅ | Qdrant `points_count=278` = `SELECT COUNT(*) FROM product WHERE status=1` |
| 4 | 向量路检索可用 | ✅ | 评估全程 **0 次 `[RAG降级]`**（降级日志是向量路失效的唯一信号） |
| 5 | **检索质量不劣于基线** | ✅ **持平，不回退** | 生产档基础集 `Recall@3 0.711 = 0.711`；详见报告 §9 |
| 6 | 降级链完整 | ✅ | 备用模型（智谱 glm-4-flash）Spring 上下文真实调用通过：`BackupModelSmokeTest` |
| 7 | 关键词路未受影响 | ⚠️ | 受品牌迁移影响：FULLTEXT 由 `(name,description,brand)` 变为 `(name,description)`，97.1% 商品品牌词仍在文本中；详见报告 §9.5 |

第 5 项的完整数据、混淆变量与统计显著性分析见 `docs/rag-evaluation-report.md` §9；
备用模型由 qwen-plus 换成智谱 glm-4-flash（§6.3 的 C5 约束）。
