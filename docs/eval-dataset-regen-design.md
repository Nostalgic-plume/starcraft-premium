# shop 评测数据集重造 — 设计文档

> 目标：清空 shop 主库业务表，用 DataForge 重造一批**语义真实**的数据，作为 RAG / 智能客服 / 推荐系统的 AI 应用评测基线。
> 状态：待确认（2026-10-13）

---

## 一、背景与目标

现状：shop 主库现有 26338 笔订单、481 个用户、300 个商品，由 `shop-data-mocker`（纯 JDBC + 硬编码业务规则）生成。数据量够，但**语义失真**——商品名、描述、聊天内容都是模板拼接，拿它评测 RAG 召回质量、客服回答质量，结论站不住。

DataForge 是 DDL 驱动 + LLM 提炼规则 + 校验修正收敛的通用造数平台，其验收目标库本来就是 shop（P0–P8 全闭环，实测 12527 行 / 3 分 41 秒）。本次要做的是把这条链路真正用起来。

**交付目标**：

1. shop 主库业务表被一批新数据替换（旧数据可恢复）
2. 新数据通过 shop 应用的全部业务校验（能登录、能下单、能购物车、能被检索）
3. 数据语义真实，可支撑 RAG / 客服 / 推荐评测
4. DataForge 获得可复用的"目标库重建"能力

---

## 二、硬约束

| # | 约束 | 说明 |
|---|------|------|
| C1 | **只动 shop 主库** | `shop_rec`（7 张 t_* 表）、`shop_staging`、`shop_ads` 本次不清理，仅作为连带影响评估对象 |
| C2 | **目标库永不静默删数据** | DataForge 现有的"安全红线"（目标库只追加）不得变成默认行为。REPLACE 必须是**逐表显式声明 + 二次确认**才生效 |
| C3 | **删除必须可恢复** | 任何 DELETE / TRUNCATE 前必须有可用的备份路径。TRUNCATE 是 DDL 隐式提交，执行后无法回滚 |
| C4 | **自增 ID 必须重置** | DataForge 全量生成的主键从 1 开始，目标表若残留旧自增值会导致主键冲突 |
| C5 | **密码必须是可登录的 BCrypt** | shop 的 admin / 商家 / 用户三个登录入口全走 BCrypt 校验，占位符会导致前端 demo 全部登不进去 |
| C6 | **REPLACE 表集合必须闭合** | 父表被子表引用（`user` ← `user_role` / `address` / `cart` / `orders`），只清父表会产生孤儿数据 |
| C7 | **凭证不进代码库** | 数据库连接信息走 `.env`，提交的配置文件中只允许 `${ENV_KEY}` 占位 |

---

## 三、数据范围

### 3.1 待清空并重建（业务表，10 张）

| 表 | 现有行数 | 备注 |
|---|---|---|
| `user` | 481 | 含 4 个固定账号，密码须重建为 BCrypt("123456") |
| `category` | 24 | 分类 ID 100-123 被 `shop-data-mocker` 冻结引用，需保持稳定 |
| `brand` | 155 | **DataForge 现有计划中缺失此表**，而 `product.brand_id` 引用它 |
| `product` | 300 | 含 `brand_id`、`shop_id`、`category_id`、`first_category_id` 四个外键列 |
| `sku` | 1200 | |
| `shop` | 5 | 商家独立账号，**无 user_id 字段**（与 DataForge 现有计划不符） |
| `address` | 596 | |
| `cart` | 801 | 唯一键 `uk_user_sku(user_id, sku_id)` |
| `orders` | 26338 | 唯一键 `uk_order_no`；状态与三个时间列需时序一致 |
| `order_item` | 28349 | |

### 3.2 连带受影响的表（必须一并处理，否则产生孤儿数据）

| 表 | 行数 | 为什么受牵连 |
|---|---|---|
| `user_role` | 500 | `user_id` 引用 `user.id`，清 user 不清它 → 500 行孤儿 |
| `user_status_log` | 0 | 同上（当前为空，影响小） |
| `cart` | 801 | 已在重建清单内 |

### 3.3 保留不动

`admin`(2) / `role`(3) / `permission`(6) / `role_permission`(9) / `rag_trace`(11) / `chat_conversation`(53) / `chat_message`(220) / `chat_feedback`(0)

> ⚠️ **待确认**：用途是"AI 应用评测基线"，那么 `chat_message`(220) 恰恰是智能客服评测的核心语料。是保留现有、还是连它一起重造，需要拍板（见 §7 待决项）。

### 3.4 删除与插入顺序

shop 主库**没有任何物理外键**（已实测确认），所以：
- **删除顺序无关紧要**——不会触发外键约束报错
- **插入顺序必须按拓扑序**——DataForge 的 TopologicalSorter 已处理，但需要保证父表在子表之前发布

---

## 四、DataForge Publisher 改造设计

### 4.1 现状问题

`Publisher.java:56` 当前实现：

```java
// 安全红线：目标库绝不 DROP/清空——只追加（APPEND），主键冲突由 INSERT 报错提示
insertRows(jdbc, targetSchema, t, gd);
```

`REPLACE` / `APPEND` / `KEEP` 三种策略中，前两种走**完全相同**的 INSERT 路径，REPLACE 名存实亡。

更严重的是 [DataForgeApiController.java:222](file:///D:/claudecode/dataforge/src/main/java/com/dataforge/web/DataForgeApiController.java) 的返回文案已经承诺：

> "请确认保留策略后再次发布（REPLACE 会清空重建目标表）"

**UI 承诺了清空，后端不清空**——用户按提示操作会以为数据已重建，实际是追加，结果要么主键冲突报错，要么新旧数据混杂。这是必须先修的既有缺陷。

### 4.2 改造后的策略语义

| 策略 | 行为 | 适用场景 |
|---|---|---|
| `KEEP` | 完全不动目标表（默认） | 未勾选的表 |
| `APPEND` | 保持现有行为：仅 INSERT | 增量追加 |
| `REPLACE` | **备份 → 清空 → 重置自增 → INSERT → 失败则回滚** | 全量重建 |

### 4.3 REPLACE 执行流程（逐表）

```
for each table where policy == REPLACE:
    1. 白名单校验：表名必须存在于当前 schema 的 TableMeta 集合中
    2. 备份：CREATE TABLE `_dfbak_<table>_<yyyyMMddHHmmss>` AS SELECT * FROM `<table>`
       └─ 备份失败 → 记录 error，跳过该表，绝不执行删除
    3. 清空：TRUNCATE TABLE `<table>`
       └─ 选 TRUNCATE 而非 DELETE 的理由：自动重置 AUTO_INCREMENT（满足 C4），
          且是原子操作、比 DELETE 快；回滚能力已由第 2 步的备份表提供
    4. 写入：复用现有 insertRows（批量 INSERT）
    5. 失败处理：INSERT 中途抛异常 → 执行恢复
         INSERT INTO `<table>` SELECT * FROM `_dfbak_<table>_<ts>`
       └─ 恢复失败 → 该表记入 errors，并在返回体中高亮（人工介入）
```

### 4.4 接口契约变更

`POST /api/publish` 请求体：

```jsonc
{
  "dbHost": "...", "dbPort": "3306", "dbUser": "...", "dbPassword": "...",
  "dbSchema": "shop",
  "confirmed": true,              // 二次确认，false 时只返回预检清单
  "policies": {                   // 逐表策略；未声明的表默认 KEEP
    "orders": "REPLACE",
    "order_item": "REPLACE"
  }
}
```

响应体新增 `backups` 字段：

```jsonc
{
  "confirmed": true,
  "published": 10,
  "skipped": ["admin", "role"],
  "backups": { "orders": "_dfbak_orders_20261013153000" },
  "errors": []
}
```

### 4.5 错误返回

| 场景 | 行为 |
|---|---|
| `confirmed=false` | 返回 `{confirmed:false, tables:{表名:行数}, message:"..."}`，不执行任何写操作 |
| 策略为 REPLACE 但表不在 schema 中 | 记入 `errors`，跳过，继续其他表 |
| 备份失败 | 记入 `errors`，**跳过该表的清空**（安全优先） |
| 清空成功但 INSERT 失败 | 自动从备份恢复；恢复成功记 `errors`（数据已还原）；恢复失败额外高亮 |
| 恢复失败 | 表处于空状态 → 返回体 `errors` 中标注 `"RECOVERY FAILED"`，备份表名保留供人工恢复 |

### 4.6 必须一并修正的门禁

`DataForgeApiController` 的预检分支（`confirmed=false`）需要补充：
- 返回每张表的**策略**而非只返回行数
- 对 `REPLACE` 表**显式高亮**"该表旧数据将被清空（已自动备份为 `_dfbak_*`）"

---

### 4.7 实现细节（评审用）

#### 4.7.1 分派逻辑

```java
for (TableMeta t : tables) {
    String policy = policies.getOrDefault(t.name(), "KEEP");
    GeneratedData gd = data.get(t.name());
    if (gd == null || gd.rows().isEmpty()) continue;

    switch (policy) {
        case "KEEP"   -> skipped.add(t.name());
        case "APPEND" -> { insertRows(jdbc, targetSchema, t, gd); published++; }  // 与原实现逐字等价
        case "REPLACE"-> { replaceTable(jdbc, targetSchema, t, gd, backups, errors); published++; }
        default       -> errors.add(t.name() + ": 未知策略 " + policy);
    }
}
```

> `APPEND` 分支保持与现有代码**完全相同的调用**，这是回归红线——现有测试与行为不受影响。

#### 4.7.2 replaceTable 实现

```java
private void replaceTable(MysqlJdbcDataSource jdbc, String schema, TableMeta t,
                          GeneratedData gd, Map<String, String> backups, List<String> errors) {
    String table = t.name();
    // 标识符白名单：表名只允许字母数字下划线（防注入；表名本身来自 information_schema）
    if (!table.matches("[A-Za-z0-9_]+")) {
        errors.add(table + ": 表名非法，已跳过");
        return;
    }
    String backup = "_dfbak_" + table + "_" + timestamp();   // yyyyMMddHHmmssSSS

    // ① 备份 —— 失败则绝不进入清空流程（安全优先）
    try {
        jdbc.execute("CREATE TABLE " + q(schema) + "." + q(backup)
                   + " AS SELECT * FROM " + q(schema) + "." + q(table));
        backups.put(table, backup);
    } catch (Exception e) {
        errors.add(table + ": 备份失败，已跳过清空 — " + e.getMessage());
        return;
    }

    // ② 清空 + ③ 写入
    try {
        jdbc.execute("TRUNCATE TABLE " + q(schema) + "." + q(table));
        insertRows(jdbc, schema, t, gd);
    } catch (Exception e) {
        // ④ 恢复
        try {
            jdbc.execute("TRUNCATE TABLE " + q(schema) + "." + q(table));
            jdbc.execute("INSERT INTO " + q(schema) + "." + q(table)
                       + " SELECT * FROM " + q(schema) + "." + q(backup));
            errors.add(table + ": 写入失败，已从备份恢复 — " + e.getMessage());
        } catch (Exception re) {
            errors.add(table + ": RECOVERY FAILED（备份表 " + backup + " 已保留，需人工恢复）"
                       + " — " + re.getMessage());
        }
    }
}
```

#### 4.7.3 事务边界（关键限制，必须理解）

| 语句 | 类型 | 能否回滚 |
|---|---|---|
| `CREATE TABLE ... AS SELECT` | DDL | ❌ 隐式提交 |
| `TRUNCATE TABLE` | DDL | ❌ 隐式提交 |
| `INSERT` | DML | ✅ 可事务 |

**结论：清空与写入无法放在同一个事务里**——TRUNCATE 一执行就隐式提交，数据库层面的事务回滚救不了。所以容错**只能靠备份表 + 补偿逻辑**，这是本方案唯一的恢复路径，不可省略。

#### 4.7.4 Result 结构变更（影响调用点）

```java
// 旧
public record Result(int published, List<String> skipped, List<String> errors) {}
// 新：新增 backups
public record Result(int published, List<String> skipped, List<String> errors,
                     Map<String, String> backups) {}
```

调用点仅一处：[DataForgeApiController.java:225](file:///D:/claudecode/dataforge/src/main/java/com/dataforge/web/DataForgeApiController.java)，需同步加 `resp.put("backups", r.backups())`。`confirmed=false` 的拒绝分支构造 `Result` 处也要补空 Map。

#### 4.7.5 落地风险（实施时逐条验证）

| # | 风险 | 应对 |
|---|---|---|
| R1 | `MysqlJdbcDataSource.execute()` 走 `JdbcTemplate.update`（**PreparedStatement**），DDL 能否经 PreparedStatement 执行需实测 | MySQL 8 支持 `TRUNCATE` / `CREATE TABLE ... AS SELECT` 的 prepare；实施时先跑一次验证，若失败则加原生 `Statement.execute()` 通道 |
| R2 | 备份表 = 目标表全量副本，占用磁盘 | `order_item` 1.6 万行量级可控；确认无误后清理 |
| R3 | 备份表名冲突（同秒重复执行） | 时间戳精确到毫秒；并有 `CREATE TABLE` 重名报错兜底（该表记 error，安全） |
| R4 | `TRUNCATE` 需要 DROP 权限（比 DELETE 高） | 当前用 root，满足；若换账号需确认权限 |
| R5 | `TRUNCATE` 加元数据锁，可能阻塞在线查询 | 发布前停止 shop-server / shop-rec（T6 前置条件） |
| R6 | 单表失败不应中断整批 | 沿用现有「失败表隔离」，逐表 try/catch，继续下一张 |

---

## 五、容易踩的坑

| # | 坑 | 后果 | 对策 |
|---|----|------|------|
| P1 | TRUNCATE 是 DDL，隐式提交、**不可回滚** | 清空后 INSERT 失败 → 表空了，数据没了 | 先备份成 `_dfbak_*` 表，失败自动恢复（§4.3） |
| P2 | DELETE 不重置自增，TRUNCATE 才重置 | 主键从旧 MAX+1 开始，与 DataForge 从 1 生成的 ID 冲突 | 用 TRUNCATE，或显式 `ALTER TABLE ... AUTO_INCREMENT=1` |
| P3 | 只清父表不清子表 | `user_role`(500) 引用已成孤儿，用户角色全部失效 | REPLACE 表集合必须包含引用闭包（C6） |
| P4 | 现有生成计划与真实库结构**不符** | 见 §6，直接跑必然出错 | 先直连真实库重新 `--analyze` 产计划 |
| P5 | `password` 列是 `const $2a$10$placeholder...` | 造完数据前端三个入口全部登不进去 | 改为真实 BCrypt("123456") 常量（C5） |
| P6 | 行数配置是启发式默认值 `rowsPerParent: 2-5` | category 24 × (2~5) 只推出 48~120 个商品，远达不到目标量级 | 按目标画像手工配 `rows` |
| P7 | shop 应用运行中，JVM 连接池 / Redis 持有旧数据 | 清库后应用仍返回旧数据或空指针 | 清库前停应用，清库后清 Redis 缓存再启动 |
| P8 | ES 索引 / BGE-M3 向量库基于旧数据构建 | RAG 检索命中的是已不存在的商品 ID | 数据重建后必须重建索引与向量库 |
| P9 | 已签发 JWT 内含旧 userId | 前端 token 全部失效 | 预期行为，前端重新登录即可 |
| P10 | Mock LLM 模式产出确定性占位内容 | 生成"商品1/商品2"这类无区分度的数据，**撑不起语义评测** | 评测基线必须走真实 LLM（见 §6 成本） |

---

## 六、前置事实核查：现有生成计划与真实库的偏差

DataForge 仓库根目录的 `generation-plan.json` 与 `project_rules.json` 是此前分析产物，但**依据的是 `src/test/resources/schema/shop_schema.sql`——该 DDL 是从实体类反推重建的，不是真实 DDL**。已实测确认的三处不符：

| 偏差 | 证据 |
|---|---|
| 计划里 `shop` 表含 `user_id` 外键列 | 真实库 `shop` 表字段为 `id, username, password, shop_name, shop_logo, shop_desc, contact_name, contact_phone, status, create_time, update_time, deleted`，**无 user_id** |
| 计划覆盖 18 表，**缺 `brand`** | 真实库 20 表，`brand` 有 155 行；`product.brand_id` 引用它 → 生成的商品 brand_id 必然悬挂 |
| 全表 `rowsPerParent` 均为默认 `2-5` | 未按 shop 实际分布调优 |

**结论**：必须先直连真实库重新分析，产出新计划，再谈生成。

DataForge 的 `/api/analyze` 端点已支持直连库（`SchemaReader.readTables(schema)` 读 information_schema，P6 已实现），不需要 DDL 文件。

### 真实 LLM vs Mock 的成本权衡

| 模式 | 产出 | 是否可用于评测 |
|---|---|---|
| `--mock-llm` | 确定性占位内容，零成本、秒级 | ❌ 无区分度，只能验证链路 |
| 真实 LLM（DeepSeek） | 语义真实的商品名/描述/对话 | ✅ 评测基线必须用 |

参考基准：DataForge 实测全量 12527 行 / 3 分 41 秒（真实 LLM）。目标量级下成本可控。

---

## 七、待决事项（需人工拍板）

1. **`chat_conversation` / `chat_message` / `chat_feedback` 是否一起重造？**
   用途是 AI 评测基线，而智能客服评测恰恰需要这批语料。保留现有 220 条（语义来自旧数据）还是重造，直接影响评测有效性。

2. **是否使用真实 LLM？**
   不用则数据撑不起评测目标；用则消耗 DeepSeek API 额度（参考量级：1.2 万行 / 20 分钟内）。

3. **目标数据量级？**
   现有 orders 26338 / order_item 28349。重造是维持同量级，还是调整（评测通常不需要这么大量，但推荐系统冷启动需要）。

4. **备份表 `_dfbak_*` 的保留期限？**
   它们会留在 shop 库内占用空间，确认无误后需手工清理（或由 DataForge 提供清理入口）。

---

## 八、连带影响清单（本方案范围外，但必须知道）

清空 shop 主库业务表后，以下**派生数据全部失效**，需要另行重建：

| 对象 | 依赖关系 |
|---|---|
| Redis 缓存 | 商品详情、购物车、会话 |
| ES 索引 | RAG 商品检索 |
| BGE-M3 向量库 | RAG 语义检索 |
| `shop_rec` 7 张 t_* 表 | `t_user_profile` / `t_item_hot_score` 基于旧行为计算 |
| `shop_ads` 9 张聚合表 | 全部基于旧订单数据统计 |
| `shop_staging` ODS/DWD 层 | 数仓中间层 |

> 若评测目标是 RAG / 推荐系统，这些重建**不是可选项**——否则检索和推荐结果仍指向已删除的商品。
