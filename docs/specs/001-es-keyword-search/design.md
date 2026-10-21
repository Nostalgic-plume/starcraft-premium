# 001 关键词路升级 Elasticsearch + IK —— 设计文档

> 状态：已确认（2026-10-21）
> 需求：`spec.md`
> 前置评估：`docs/rag-elasticsearch-feasibility.md`（§8.1 的五条硬约束继续有效）

## 一、架构与数据流

```
                        ┌─ 向量路：TEI/BGE-M3 → Qdrant ─────────────┐
RagSearchService ──┬───┘                                            ├→ 加权 RRF → 候选清洗 → 富化
                   └─→ KeywordSearchRouter ─┬→ EsKeywordSearchService（首选）
                                            └→ KeywordSearchService（MySQL FULLTEXT，兜底）
```

- `RagSearchService` 只依赖 `KeywordSearchRouter`，不再直接依赖 MySQL 实现。
- Router 按「配置开关 + 熔断状态」选择实现，**对外签名与 `KeywordSearchService` 完全一致**。
- 两路实现都只产出 `RagCandidate(productId, score)`，展示数据仍由 `RagHitEnricher` 回查 MySQL。

## 二、硬约束（踩过即废）

| # | 约束 | 原因 |
|---|------|------|
| H1 | ES Java Client 版本必须等于服务端版本 **8.11.4**，且必须覆盖 Spring Boot BOM 的 `elasticsearch.version` | 8.x 客户端带 product check，minor 版本不一致直接拒绝请求 |
| H2 | IK 版本必须等于 ES 版本 | 插件加载失败，中文退化为单字切分，方案不成立 |
| H3 | **索引期 `ik_max_word`、查询期 `ik_smart`** | 索引细切保证召回，查询粗切保证精度；反过来会命中大量噪声 |
| H4 | 过滤字段（`firstCategoryId` / `shopId` / `status` / `deleted`）必须是 `long`/`integer`，**不能是 `text`** | `text` 会被分词，`term` 查询永远不命中 |
| H5 | 价格字段用 `scaled_float(scaling_factor=100)` | 金额两位小数用 float 会有精度误差；scaled_float 内部按分存储 |
| H6 | ES 失败**不能返回空列表**，必须抛异常给 Router 触发回退 | 空列表 = 「没搜到」，与「ES 挂了」语义不同；混为一谈会让用户看到空结果 |
| H7 | 增量同步失败只 WARN，不阻断商品主流程 | 与现有 Qdrant 索引同步一致；一致性由全量重建兜底 |
| H8 | 重建索引时先删后建，重建窗口内检索必须能回退 FULLTEXT | 删索引后到 bulk 完成前 ES 返回 404 |

## 三、配置项（`rag.elasticsearch.*`）

| 键 | 默认值 | 说明 |
|----|--------|------|
| `enabled` | `true` | 关闭后完全不碰 ES，直接走 FULLTEXT |
| `scheme` / `host` / `port` | `http` / `localhost` / `9200` | 与 compose 的 `127.0.0.1:9200` 对齐 |
| `index-name` | `shop_products` | 索引名 |
| `connect-timeout-ms` | `500` | 连不上时快速失败，避免拖慢检索 |
| `socket-timeout-ms` | `1500` | 读超时 |
| `recovery-interval-ms` | `30000` | 熔断后多久半开重试一次 |
| `bulk-batch-size` | `200` | 全量导入批量大小，与 Qdrant 的 25 条分批独立（ES bulk 可更大） |

## 四、索引定义

```json
{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "1s" },
  "mappings": {
    "properties": {
      "productId":       { "type": "long" },
      "name":            { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "description":     { "type": "text", "analyzer": "ik_smart" },
      "brandName":       { "type": "text", "analyzer": "ik_smart" },
      "categoryName":    { "type": "text", "analyzer": "ik_smart" },
      "firstCategoryId": { "type": "long" },
      "shopId":          { "type": "long" },
      "minPrice":        { "type": "scaled_float", "scaling_factor": 100 },
      "maxPrice":        { "type": "scaled_float", "scaling_factor": 100 },
      "status":          { "type": "integer" },
      "deleted":         { "type": "integer" },
      "updateTime":      { "type": "date", "format": "epoch_millis" }
    }
  }
}
```

字段来源：

| 字段 | 来源 | 备注 |
|------|------|------|
| `productId` | `product.id` | 文档 `_id` 也用它，天然幂等 |
| `name` | `product.name` | 主检索字段，查询时 boost 3 |
| `description` | `product.description` | 截断至 2000 字符，防文档过大 |
| `brandName` / `categoryName` | 批量查 `brand` / `category` | 与 `ProductIndexer` 同源，但**不复用**其五段式文本（见 §八.1） |
| `firstCategoryId` | `product.first_category_id` | 分类过滤 |
| `shopId` | `product.shop_id` | 预留过滤 |
| `minPrice` / `maxPrice` | `SkuPriceResolver` | 价格过滤 |
| `status` / `deleted` | `product` | 检索过滤兜底 |
| `updateTime` | `product.update_time` → epoch millis | 与 Qdrant payload 口径一致 |

## 五、检索逻辑

```java
bool
  must: multi_match
          query: <同义词扩展后的 query>
          fields: ["name^3", "brandName^2", "categoryName^1.5", "description"]
          type: best_fields
          operator: OR
  filter:
          term status = 1
          term deleted = 0
          term firstCategoryId = ?        // 可选
          range maxPrice >= minPrice      // 可选：商品最高价须 >= 查询最低价
          range minPrice <= maxPrice      // 可选：商品最低价须 <= 查询最高价
size: limit
_source: ["productId"]                   // 只回传 ID，展示数据由富化层回查
```

- 价格语义与 `RagSearchService#passMetadataFilter` **逐字对齐**，不能改成「商品价格区间与查询区间相交」。
- `score` 直接用 BM25 原始分。RRF 只消费 rank，不消费 score，故量纲差异不影响融合。
- 空结果（命中 0 条）是正常返回值；ES 异常才抛 `KeywordSearchUnavailableException`。

## 六、错误返回与降级

| 失败点 | 处理 | 对外表现 |
|--------|------|---------|
| `enabled=false` | 直接走 FULLTEXT | 正常 |
| 连接失败 / 超时 / 5xx | Router 记录失败 → 熔断 → 本次走 FULLTEXT | 结果正常，日志 `关键词路=fulltext(降级)` |
| 索引不存在（404） | 同上，并标记索引缺失 | 结果正常 |
| 检索结果为空 | 直接返回空，**不降级** | 空结果 |
| 熔断期内 | 跳过 ES | 走 FULLTEXT |
| 超过 `recovery-interval-ms` | 半开：放行一次 ES 请求 | 成功则恢复，失败则重新计时 |
| 全量重建失败 | 记录 `lastRebuildSuccess=false` + 原因 | 管理接口可见 |

熔断状态复用现有 `CircuitBreaker` 的语义（连续 3 次失败 / 30s 半开），但**不复用同一个 bean** —— 现有 bean 是给对话模型用的单例，共用会互相干扰。Router 内部自带计数。

## 七、同步链路

| 时机 | 动作 | 实现 |
|------|------|------|
| 商品新增 | 上架商品 upsert；非上架跳过 | `ProductIndexSyncService.addOrUpdate` |
| 商品编辑 | 上架 upsert，否则 delete | 同上 |
| 上下架 | 上架 upsert，否则 delete | 同上 |
| 启动 | 索引不存在则创建；为空且库中有上架商品则全量导入 | `EsProductIndexer` 监听 `ApplicationReadyEvent` |
| 管理后台重建 | 删除索引 → 重建 mapping → bulk 导入 | `/api/admin/rag/rebuild` |

`ProductIndexSyncService` 是门面：Qdrant 与 ES 各自 try-catch 隔离，一个失败不影响另一个。

## 八、容易踩的坑

1. **不能复用 `ProductIndexer.buildEmbeddingText`**：那是「五段式拼成一段文本」喂给 embedding 的输入，ES 需要的是**按字段分开索引**。共用会让 ES 的所有字段混进同一个 `text` 字段，boost 失效、过滤字段也无法工作。两边文本构建刻意独立。
2. **`elasticsearch.version` 覆盖必须放在根 pom 的 `<properties>`**：Spring Boot 的 BOM 用这个 property 管理 `elasticsearch-rest-client` 与 `elasticsearch-java`，子模块单独写版本号只覆盖一个坐标，传递依赖仍会漂到 8.10.x。
3. **`_source` 只取 `productId`**：ES 里再存一份名称/图片属冗余，且会造成「ES 与 MySQL 展示不一致」的错觉；展示数据统一由 `RagHitEnricher` 回查。
4. **商品下架要删文档，不是只改 status**：虽然检索侧有 `status=1` 过滤，但残留文档会持续占用索引与磁盘；增量路径统一「非上架即删除」。
5. **`refresh_interval` 默认 1s**：商品刚改完立即搜索可能搜不到，属正常近实时行为；不接受就写后 `refresh=wait_for`，但会拖慢写入，本次不做。
6. **全量重建的删除窗口**：删索引后到 bulk 完成前，所有 ES 检索都会 404 → 回退 FULLTEXT。这是预期行为，不要试图用「别名切换」优化（首版不做，记 TODO）。
7. **IK 自定义词典未挂卷**：`plugins/analysis-ik/config` 在容器重建后丢失。首版不依赖自定义词典；需要时在 compose 加 volume 并重建镜像。
8. **ES 内存**：512m 堆 + 系统开销约 1GB，与 rec profile 的 Flink（3G）不要同时全开。

## 九、遗留 TODO

- ES 与 MySQL 的定时对账（对齐 `IndexReconcileTask` 对 Qdrant 的做法）。
- 零停机重建：临时索引 + 别名切换。
- IK 自定义词典（品牌名、型号、口语化词）与热更新。
- 同义词迁移到 ES `synonym_graph` filter（查询期）。
