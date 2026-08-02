# RAG 智能客服系统改造计划书

> 基于现有架构（Qdrant 向量检索 + DashScope qwen-plus + LLM 流式生成）的全面升级路线

---

## 当前架构快照

```
用户 query → DashScope embedding → Qdrant (1536d Cosine) → TOP-3 → LLM 生成回答
                                                                   └─ 无结果 → MySQL 分类名回退
```

| 组件 | 当前实现 | 定位 |
|------|---------|------|
| 向量检索 | Qdrant REST API，单路语义搜索 | 检索层 |
| Query 处理 | 原始 query 直接检索 | 输入层 |
| 排序 | Qdrant 原生 cosine score，无重排 | 排序层 |
| 知识源 | 仅商品索引（product 表） | 知识层 |
| 效果评估 | 仅 `chat_feedback` 表存数据，无分析 | 评估层 |

---

## 一路：Hybrid Search（向量 + 关键词混合检索）

### 现状痛点

| 场景 | 问题 | 用户问法 |
|------|------|---------|
| SKU/型号匹配 | 向量语义漂移，排不到第一 | 「iPhone 16 256G」「SKU:10086」 |
| 精确短语匹配 | 商品名含特定词时漏召 | 「红色真丝连衣裙」→ 向量可能把「真丝」语义化丢了 |
| 长尾商品 | 向量分布稀疏，相似度 < 0.3 | 冷门小众品类 |

### 方案

**不引入 ES**，直接在 MySQL 建 FULLTEXT 索引做关键词检索，与 Qdrant 向量结果 RRF 合并：

```
用户 query
  ├─ DashScope embedding → Qdrant 向量检索 → Top-N（N=50, score>0.3）
  ├─ 分词/去停用词 → MySQL FULLTEXT 检索 → Top-N
  └─ RRF 合并取 Top-K → LLM
```

**RRF 公式**：
```
score = 1 / (60 + rank_vector) + 1 / (60 + rank_keyword)
```

### 执行计划

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 1.1 | product 表加 FULLTEXT INDEX(name, description, brand) | SQL |
| 1.2 | 新建 `KeywordSearchService.java` — 关键词分词、MySQL 全文检索、结果转 RagHitVO | `rag/KeywordSearchService.java` |
| 1.3 | 新建 `RrfMerger.java` — RRF 合并双路结果 | `rag/RrfMerger.java` |
| 1.4 | 改造 `RagSearchService.search()` — 并行调用双路 + RRF 合并 | `rag/RagSearchService.java` |
| 1.5 | 新增 `FULLTEXT` 索引重建机制，与 `ProductIndexer` 联动 | `rag/ProductIndexer.java` |

### 工作量

**~3 天**（含测试）

---

## 二路：Query 理解与改写

### 现状痛点

| 场景 | 用户输入 | 实际意图 |
|------|---------|---------|
| 口语化短 query | 「便宜的手机」 | 价格低的手机 |
| 指代消解 | 第一轮「有什么手机推荐？」第二轮「红色的」 | 红色的手机 |
| 复杂意图 | 「送女朋友的生日礼物」 | 适合送礼的商品（高颜值/礼盒装） |

### 方案

在检索前加一道 LLM query 改写：

```
用户 query + 最近 2 轮对话历史
  → LLM（qwen-plus）改写为独立检索 query
  → 改写后的 query 送 Hybrid Search
```

### 执行计划

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 2.1 | 新建 `QueryRewriter.java` — LLM 改写 prompt + 调用 | `rag/QueryRewriter.java` |
| 2.2 | 改造 `ChatService.ask()` — 检索前调用改写 | `rag/ChatService.java` |
| 2.3 | 改写日志记录到 `chat_message.rag_context` JSON 扩展 | `rag/ChatService.java` |

### 工作量

**~1 天**

---

## 三路：Re-ranking（重排序）

### 现状痛点

Qdrant cosine 分数高的结果，在业务上不一定最相关：
- 「苹果手机」→ 向量最近的是「苹果果汁」（苹果=水果）
- 「长裙」→ 向量排第一的是「连衣裙」（裙长不同）

### 方案

Qdrant 召回 Top-20 → Cross-encoder 重排 → Top-3：

```
Qdrant TOP-20 → Cross-encoder 逐对打分 → 按业务分重排 → Top-3 → LLM
```

**模型选择**（二选一）：

| 方案 | 部署方式 | 延迟 | 成本 |
|------|---------|------|------|
| **DashScope Rerank API** | 远程调用，无需本地部署 | ~200ms | 按量计费 |
| **BAAI/bge-reranker-v2-m3** | 本地 ONNX 推理 | ~50ms | 免费，需 ~1GB 内存 |

推荐先用 DashScope Rerank API（零部署，接入快）。

### 执行计划

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 3.1 | 配置 DashScope Rerank API endpoint | `application.yml` |
| 3.2 | 新建 `ReRanker.java` — 调用 rerank 接口 | `rag/ReRanker.java` |
| 3.3 | 改造 `RagSearchService.search()` — Qdrant 改查 Top-20，过 rerank 取 Top-3 | `rag/RagSearchService.java` |

### 工作量

**~1.5 天**

---

## 四路：知识库扩展

### 现状痛点

目前只索引了商品数据，用户问这些就完全没结果：
- 「你们用什么快递发货？」
- 「退换货政策是什么？」
- 「怎么联系客服？」

### 方案

新增 `knowledge_base` 表 + 独立索引器，检索时商品 + 知识双路召回，RRF 合并：

```sql
CREATE TABLE knowledge_base (
    id          BIGINT PRIMARY KEY COMMENT '雪花ID',
    category    VARCHAR(50)  NOT NULL COMMENT 'FAQ / POLICY / AFTER_SALE / SHIPPING',
    title       VARCHAR(200) NOT NULL,
    content     TEXT         NOT NULL,
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=启用 1=停用',
    create_time DATETIME     NOT NULL,
    update_time DATETIME,
    deleted     TINYINT      NOT NULL DEFAULT 0
);
```

### 执行计划

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 4.1 | 创建 `knowledge_base` 表（MCP 或 SQL） | SQL |
| 4.2 | 新建 `KnowledgeBaseDO` 实体 | `model/entity/KnowledgeBaseDO.java` |
| 4.3 | 新建 `KnowledgeBaseMapper` | `mapper/KnowledgeBaseMapper.java` |
| 4.4 | 新建 `KnowledgeIndexer.java` — 启动时索引知识库到 Qdrant（同集合，加 type=knowledge payload） | `rag/KnowledgeIndexer.java` |
| 4.5 | 改造 `RagSearchService` — Qdrant 支持按 type 过滤检索商品/知识 | `rag/RagSearchService.java` |
| 4.6 | 管理后台知识库 CRUD（可选） | `controller/AdminController.java` |

### 工作量

**~2.5 天**（含管理后台 CRUD）

---

## 五路：效果评估与监控

### 现状

- `chat_feedback` 表已建
- 有 `rating`（1/0/-1）
- **缺**：聚合看板、A/B 对比、失败 case 分析

### 方案

```sql
-- 周维度反馈趋势
SELECT DATE(create_time) AS day,
       SUM(rating = 1) / COUNT(*) AS positive_rate,
       COUNT(*) AS total
FROM chat_feedback
GROUP BY DATE(create_time)
ORDER BY day;

-- 低分消息关联检索日志
SELECT m.content AS user_query,
       f.rating,
       f.feedback_text,
       m.rag_context  -- 当时检索到的商品
FROM chat_feedback f
JOIN chat_message m ON f.message_id = m.id
WHERE f.rating <= 0
ORDER BY f.create_time DESC
LIMIT 50;
```

### 执行计划

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 5.1 | 管理后台加「智能客服分析」页面 — 反馈趋势折线图 + 低分 case 列表 | `frontend-admin/` |
| 5.2 | 后端加反馈统计 API | `controller/ChatController.java` 或 `AdminController.java` |
| 5.3 | 增加 `rag_context` 字段写入链路，确保每次检索的上下文完整记录 | `ChatService.java`（已有） |

### 工作量

**~1.5 天**（含前端页面）

---

## 优先级与路线图

```
短期（这周，3-4天）             中期（下周，2-3天）          长期（持续）
┌─────────────────────┐    ┌──────────────────────┐    ┌──────────────────┐
│ ✅ Hybrid Search     │    │ 📌 Re-ranking         │    │ 📌 数据驱动迭代    │
│   (3天)              │    │   (1.5天)              │    │   (持续)          │
├─────────────────────┤    ├──────────────────────┤    ├──────────────────┤
│ ✅ Query 改写        │    │ 📌 知识库扩展           │    │ 📌 自动化评测       │
│   (1天)              │    │   (2.5天)              │    │   (按需)          │
├─────────────────────┤    └──────────────────────┘    └──────────────────┘
│ 📌 效果评估页面       │
│   (1.5天)            │
└─────────────────────┘
```

### 推荐执行顺序

| 优先级 | 改造项 | 选择理由 |
|--------|--------|---------|
| **P0** | Hybrid Search + Query 改写 | 检索准确率 +30%，影响面最大，用户直接感知 |
| **P1** | 效果评估页面 | 没有数据就看不出效果，和 P0 并行推进 |
| **P2** | Re-ranking | 在检索量上去后，进一步提升准确率 |
| **P3** | 知识库扩展 | 需要管理后台配合，先等前列完成 |

---

## 改动文件全景

| 文件 | Hybrid Search | Query 改写 | Re-ranking | 知识库扩展 | 评估 |
|------|:---:|:---:|:---:|:---:|:---:|
| `rag/KeywordSearchService.java` **新** | ✅ | | | | |
| `rag/RrfMerger.java` **新** | ✅ | | | | |
| `rag/QueryRewriter.java` **新** | | ✅ | | | |
| `rag/ReRanker.java` **新** | | | ✅ | | |
| `rag/KnowledgeIndexer.java` **新** | | | | ✅ | |
| `model/entity/KnowledgeBaseDO.java` **新** | | | | ✅ | |
| `mapper/KnowledgeBaseMapper.java` **新** | | | | ✅ | |
| `rag/RagSearchService.java` **改** | ✅ | | ✅ | ✅ | |
| `rag/ChatService.java` **改** | | ✅ | | | ✅ |
| `rag/ProductIndexer.java` **改** | ✅ | | | | |
| `controller/ChatController.java` **改** | | | | | ✅ |
| `frontend-admin/` **改** | | | | ✅ | ✅ |
| `application.yml` **改** | | | ✅ | | |

---

## 技术决策记录

| 决策 | 选项 | 选择 | 原因 |
|------|------|------|------|
| 关键词检索引擎 | ES vs MySQL FULLTEXT | **MySQL FULLTEXT** | 无新增基础设施，数据量 < 10 万行足够 |
| RRF 常数 k | 60 / 100 / 自定义 | **60** | 业界标准值，向量分数分布稳定 |
| Rerank 模型 | API vs 本地 | **DashScope Rerank API** | 零部署，中文好，与现有 DashScope 统一 |
| 知识库存储 | 单独 Qdrant 集合 vs 同集合 + tag | **同集合 + type payload** | 统一管理，跨类型 RRF 合并简单 |
| 分词器 | MySQL 内置 ngram vs IK | **ngram（n=2）** | MySQL 内置无需插件，中文电商足够 |
| Query 改写 LLM | 复用 qwen-plus vs 更小模型 | **复用 qwen-plus** | 不需要额外模型调用链路，改写 prompt 很轻 |
