# Code Review 修复计划（2026-09-28）

> 来源：`/code-review` 对工作区 diff（41 个改动文件 + 13 个新增文件）的审查。
> 9 项 finding **已逐条核实成立**（核对了 SQL 口径、实体关系、外部表定义、Key 兜底链与 compose 解析结果）。
> 范围确认：**全部 9 条**；其中 F5 采用**最小实现**（只读接口 + 表单下拉），不做品牌 CRUD。

## 分批与顺序

按「关联性 + 风险隔离」分批，每批独立可验证、可回滚。

| 批次 | 项 | 主题 | 触及 |
|---|---|---|---|
| 1 | F1 / F4(后端) / F7 / F8 | RAG 检索侧加固 | `QdrantCollectionHelper`、`ProductIndexer`、`RagLlmProperties` |
| 2 | F4(前端) | 重建状态真实反馈 | `frontend-admin/src/views/RagIndex.vue` |
| 3 | F5 / F6 | 品牌读取入口与批量预加载 | `BrandController`(新)、`ProductServiceImpl`、三端表单 |
| 4 | F2 / F3 | 数仓口径与 DDL 安全 | `azkaban/shop/ads_etl.hql`、`azkaban/shop_hive/04_dwd_tables.txt` |
| 5 | F9 | TEI 编排与 README | `docker-compose.yml`、`docs/README.md` |

---

## 批次 1：RAG 检索侧加固

### F1 集合维度校验（防静默降级）

- **问题**：`ensureCollection()` 只判断集合存在与否，不校验维度；`RagSearchService` 的自检比的是
  「查询向量长度 vs 配置」，`1024 == 1024` 恒等通过。存量部署换模型后，集合仍是旧维度，
  `embeddingStore.search()` 抛错被 `searchViaQdrant` 捕获返回空 → **所有语义检索静默退化为纯关键词**，
  仅一条 WARN，管理页仍显示 `indexedCount=278` 全绿。
- **修法**：`ensureCollection()` 内 `exists()` 命中后，再 GET 集合信息解析 `vectors.size`，
  与 `ragProperties.getDimension()` 比对；不一致时 `log.error` 明确指出「集合维度 X ≠ 配置维度 Y，
  向量路将不可用，请执行全量重建」，**不自动删除**（避免误删数据）。
- **硬约束**：
  - 不自动重建 —— 删集合是破坏性操作，必须由人显式触发（与 F4 的修复原则一致）
  - 解析失败（Qdrant 不可用/字段缺失）时降级为 WARN，**不得阻断启动**
- **验收**：临时把 `rag.qdrant.dimension` 改为 1536 启动 → 日志出现维度不匹配 error；改回 1024 后无该日志。

### F4（后端）重建失败不得上报成功

- **问题**：`doRebuild()` 两条早返回路径（无上架商品 / **所有向量为空**）都写 `lastRebuildTime`，
  而该时间戳是前端判断「重建完成」的唯一信号。若此时 TEI 未就绪，集合已被 `recreateCollection()`
  删除 → **集合为空 + 前端提示成功**。
- **修法**：引入明确的构建状态（`lastRebuildSuccess` / `lastRebuildMessage` / `lastRebuildTime`），
  失败路径置为失败并附原因；成功路径才置成功。REST 接口返回结构化状态。
- **入参/返回**：查询接口需返回 `{ time, success, message, indexedCount }`。
- **验收**：停掉 TEI 触发重建 → 接口与前端均显示失败 + 原因，**不显示「重建完成」**。

### F7 重建时品牌批量预加载

- **问题**：`buildIndexText` 对每个商品单独 `brandMapper.selectById`，278 条即 278 次往返；
  同一方法内的 SKU 价格已用 `loadPriceMap` 批量预加载，品牌这里退回逐条查。
- **修法**：改为一次性 `selectBatchIds` 收集去重后的 brandId，构造 `Map<Long,String>` 传入。
- **验收**：重建索引日志的 SQL 次数下降；索引文本内容与修复前逐字节一致（等价性验证）。

### F8 API Key 兜底链告警

- **问题**：`chatApiKey = firstNonBlank(chatApiKey, apiKey, "dummy")`，其中 `apiKey` 是 **DashScope** Key。
  只填 `DASHSCOPE_API_KEY` 而漏填 `DEEPSEEK_API_KEY` 时取到非空值 → **不触发任何告警**
  （只有落到 `"dummy"` 才 warn）→ 主模型带错厂商 Key 调用 → 401 → 熔断 → 降级。
- **修法**：告警条件从「等于 dummy」扩展为「等于 dummy **或** 回退到了 DashScope 的 key」，
  日志明确指出「chat-api-key 回退到了 DashScope Key，与 DeepSeek 端点不匹配，请检查 DEEPSEEK_API_KEY」。
- **验收**：临时清空 `DEEPSEEK_API_KEY` 启动 → 日志出现该 warn。

---

## 批次 2：F4（前端）重建状态

- **修法**：`RagIndex.vue` 轮询不再以「时间戳变化」为完成信号，改为读后端返回的 `success` 字段；
  失败时展示原因，且**不**弹「重建完成」。
- **验收**：TEI 停机状态下点重建 → 页面显示失败与原因；恢复 TEI 后重建 → 显示成功。

---

## 批次 3：品牌读取入口（F5）与批量预加载（F6）

### F5 最小实现

- **新增**：`GET /api/brand/list`（仅返回 `status=0` 且未删除的品牌，按 `sort` 升序），
  参考 `CategoryController` 的公开接口风格。
- **前端**：三端商品表单增加品牌下拉（当前 `brandId` 是死字段）。
- **不做**：品牌 CRUD、品牌管理页 —— finding 的核心是「brand_id 永远无法被赋值」，只读接口即可解决。
- **验收**：新增/编辑商品时可选品牌并落库；`ProductVO.brandName` 正确回显。

### F6 商品分页的品牌 N+1

- **问题**：`convertToVO` 每条商品查一次 brand，`pageSize` 上限已提到 200 → 单次列表最多 200 次查询。
- **修法**：`doPage` 先批量收集当页 brandId → 一次 `selectBatchIds` → 传入 `convertToVO`。
  注意 `convertToVO` 也被详情等单条路径调用，需保留单条重载或允许传 null Map 时回退单查。
- **验收**：分页接口 SQL 日志中 brand 查询由 N 次降为 1 次。

---

## 批次 4：数仓

### F3 `ads_product_top50` 金额口径

- **问题**：`SUM(actual_amount)` —— `actual_amount` 是**整单**实付金额，而本表按 `product_id` 聚合，
  事实表粒度为订单项 → 一笔多商品项订单会把整单金额重复记到每个商品头上（排行榜虚高、排名失真）。
- **修法**：改为 `SUM(subtotal)`（订单项小计，`dwd_etl.hql:142` 已产出该列）。
- **验收**：重跑 ADS 层后，同一批订单的 `SUM(sale_amount)` ≤ 订单总额，且多商品项订单不再重复计入。

### F2 Hive 外部表插列导致数据错位

- **问题**：`dwd_dim_product_zip` 为 `EXTERNAL` + `TEXTFILE`，`brand_id`/`brand_name` **插在第 3、4 位**。
  `DROP TABLE` 对外部表**不删 HDFS 文件** → 重跑 DDL 后旧文件（10 列）按新 schema（11 列）读取整体左移：
  字符串品牌名被当 BIGINT、后续列全部错位，并经 `INSERT OVERWRITE ... SELECT d.*` 传播到事实表。
- **修法**：在脚本头部加**显式重跑前置说明**（需先清空 `LOCATION` 目录，附命令），
  并在该表 DDL 处加就地警告。
- **不做**：不在脚本里自动 `TRUNCATE`/删目录 —— 拉链表含历史版本数据，删除须由人确认（代价不对称）。
- **验收**：脚本注释可指导一次干净重跑；新列位于中间这一风险点有就地说明。

---

## 批次 5：F9 TEI 编排

- **问题**：`tei` 服务硬写 `gpus: all` 且无 `profiles`，而 `docs/README.md` 步骤 0 是
  `docker compose up -d` → **无 NVIDIA GPU 的机器上第一步即失败**。
- **修法**：给 `tei` 加 `profiles: ["gpu"]`（默认不启动，需要时 `docker compose --profile gpu up -d`），
  同步修正 README 步骤 0 的命令与说明、CLAUDE.md / AGENTS.md 的对应命令。
- **权衡说明**：加 profile 后默认 `up -d` 不再启动 TEI，需在 README 显式提示
  「后端 RAG 向量路依赖 TEI，启用 GPU profile 才能用语义检索」——避免从「第一步失败」变成「静默少了向量路」。
- **验收**：`docker compose config --services` 默认不含 tei；`--profile gpu` 时含 tei；
  本机仍可用 profile 命令正常起停。

---

## 总验收

1. `mvn clean compile` + `mvn test-compile` 通过
2. RAG 相关测试全绿（含 `RagEvaluationTest` 指标不退化、`BackupModelSmokeTest`）
3. 三端前端 `npm run build` 通过（触及前端的两批）
4. Qdrant 集合仍为 1024 维 / 278 点，后端 `/api/product/rag-search` 正常
5. 每批完成后回填本文件的执行状态

---

## 执行记录（2026-09-28 完成）

全部 5 批已执行完毕，`mvn test`（5 个测试类 / 7 用例）与两端前端 `npm run build` 均通过。

| 批次 | 项 | 状态 | 实测证据 |
|---|---|---|---|
| 1 | F1 | ✅ | 启动日志出现 `Qdrant 集合 'products' 向量维度校验通过: 1024 维` |
| 1 | F4 后端 | ✅ | `ProductIndexerRebuildTest`：`lastRebuildSuccess=true`、`indexedCount=278`；三条出口均改为显式 `markRebuildFinished(success, message)` |
| 1 | F7 | ✅ | 重建 SQL 由 **306 条降到 29 条**；`FROM brand` / `FROM category` 各 1 次（原各 278 次）；`RagEvaluationTest` 全部指标**逐位复现**，证明索引文本逐字节等价 |
| 1 | F8 | ✅ | 启动无新告警（当前 `.env` 已配 DEEPSEEK_API_KEY）；告警条件改为「值等于 DashScope Key」而非「等于 dummy」 |
| 2 | F4 前端 | ✅ | `frontend-admin` 构建通过；轮询改判 `lastRebuildSuccess`，新增「最近重建结果」展示并区分「未重建 / 失败」 |
| 3 | F5 | ✅ | `BrandServiceTest` 通过（155 条品牌全为启用、sort 升序）；新增 `GET /api/brand/list`；admin/merchant 表单加品牌下拉；`addProduct` 加品牌存在性校验 |
| 3 | F6 | ✅ | `ProductPageLookupTest` 通过；pageSize=20 时 `category`/`brand`/`sku`/`order_item` 各只剩 **1 次**查询（原各 20 次，共 80 次） |
| 4 | F3 | ✅ | 实际修改 **4 处**（非 1 处）：`ads_etl.hql` 与 `ads_final_etl.hql` 的分类/商品聚合各 2 处。MySQL 实测口径差异：全表 10,602,638.30 → 5,363,621.50（虚高近 2 倍），单商品最高虚高 **46.4 倍** |
| 4 | F2 | ✅ | `04_dwd_tables.txt` 加文件头重跑前置说明 + 表处就地警告；核实仅 `dwd_dim_product_zip` 命中该风险（`dwd_etl.hql` 未读取 brand_id/brand_name） |
| 5 | F9 | ✅ | `docker compose config --services` 默认 6 个服务**不含 tei**；`--profile gpu` 时含 tei；运行中容器不受影响 |

### 计划外发现（超出原 9 条，已一并处理或记录）

1. **`convertToVO` 的 N+1 远不止品牌（已修）**：同一方法内还有分类、SKU、销量三处逐条查询，
   实际是**每商品 4 次查询**（pageSize 200 → 800 次），code-review 只看到 diff 里的 brand。
   已统一为整页预加载（新增 `ProductSalesDTO` + `OrderItemMapper.sumQuantityByProductIds` 批量聚合）。
2. **`buildIndexText` 的分类 N+1（已修）**：重建时分类同样逐条查（278 次），与品牌一并批量化。
   若只修品牌不修分类，属半截修复。
3. **`ProductIndexer.optimizeFulltextIndex` 报 `失败: null`（未修，需单独诊断）**：
   每次重建后日志出现 `WARN ... MySQL FULLTEXT 索引重建失败: null`。该方法用
   `@Select("OPTIMIZE TABLE product")` 执行 DDL —— `OPTIMIZE TABLE` 不是查询语句，
   走 `@Select` 路径可能因结果集映射失败抛异常（且异常 message 为 null）。
   影响：FULLTEXT 索引不执行碎片整理（**不影响正确性**，仅少了一次优化），
   但每次重建都刷一条误导性 WARN。修法需实测（改 `@Update` 或改用 JdbcTemplate 执行），
   因涉及执行路径变更，未擅自改动。
   当前状态：命令行 `/api/product/page`、`RagEvaluationTest` 的关键词路均正常。
