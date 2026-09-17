# RAG 同义词扩展（坑②）— 设计文档

> 日期：2026-09-17　范围：`com.shop.rag.RagSearchService`、`com.shop.rag.QueryRewriter`、
> 新增 `com.shop.rag.SynonymExpander`、`com.shop.rag.SynonymProperties`、`application.yml`

## 一、问题（代码实测）

| # | 现状 | 代码位置 | 后果 |
|---|------|---------|------|
| 1 | `QueryRewriter` 的 prompt 只要求补全指代、保留属性，**无同义词扩展要求**，也无词表 | `QueryRewriter.java:41-57` | 用户用词与商品名不一致时，检索不到 |
| 2 | `QueryRewriter` 仅被智能客服链路调用 | `ChatService.java:156` | `/api/product/rag-search` 完全没有 query 改写 |
| 3 | 关键词路是 ngram=2 的**纯字面** `MATCH AGAINST` | `ProductMapper.java:22-26`、`KeywordSearchService.java:40` | 2-gram 无交集即必然漏召 |

**实测样例**：商品表内只有「移动电源」（id 124/216）、「剃须刀」（id 34/56/60）、「运动鞋 / 跑步鞋」（id 20/145/161/205/236/258）。
用户说「充电宝」「刮胡刀」「球鞋」时，`"充电宝"` 切分为 `充电 / 电宝`，与 `"移动电源"` 的 `移动 / 动电 / 电源` 无任何交集 → 字面路命中为 0。
（`"充电宝"` 仅能命中 id 255「罗马仕 30000mAh 充电宝」这一条恰好同名的商品。）

## 二、方案

**确定性领域同义词表（关键词路扩展） + LLM prompt 补全（表外兜底），两层互补。**

1. **同义词表**：`application.yml` 的 `rag.synonym.groups` 以「词组」形式配置，`SynonymProperties` 承载，
   `SynonymExpander` 启动时构建 **双向索引**（`Map<词, Set<同义词>>`）。
2. **扩展点**：`RagSearchService.search()` 内，仅把扩展后的 query 交给**关键词路**；
   **向量路保持原 query 不变**——扩展词会稀释 embedding 的语义重心，反而降低向量召回质量。
   两路各取所长后仍由 `RrfMerger` 融合，天然互补。
3. **LLM 兜底**：`QueryRewriter` 的 prompt 增加一条同义词补全指令，覆盖词表未收录的说法。
   LLM 输出不可复现、不可评估，因此**只作兜底，不作为主要手段**。
4. **开关**：`rag.synonym.enabled`（默认 `true`），用于 A/B 对比与线上回滚。

### 为什么扩展只作用于关键词路

| | 向量路（Qdrant） | 关键词路（FULLTEXT） |
|---|---|---|
| 能力 | 语义泛化，天然容忍近似表述 | 精确字面匹配，2-gram 无交集即漏 |
| 扩展后 | `"充电宝 移动电源"` 的 embedding 重心偏移，可能挤掉原本命中的商品 | `MATCH AGAINST` 按词加权 OR，扩展词直接带来召回 |
| 结论 | **不扩展** | **扩展** |

### 为什么词表是「词组」而不是「别名对」

词组 `[充电宝, 移动电源, 充电电池]` 天然支持一词多义与多方同义，且新增词只需加入组内；
别名对（`A→B`）在有 3 个以上同义表述时需要维护 N 条记录，且方向易错。

## 三、硬约束

1. **扩展仅用于关键词路**。向量路的 embedding 输入必须是原始 query。
2. **匹配必须是整词相等**：query 整串等于词表词，或按空白/标点切段后**某段完全相等**。
   禁止子串匹配——若用 `contains`，`"手机"` 会命中 `"手机壳"`，产生错误的词义扩散。
3. **不做上位词/下位词泛化**。同义词组粒度限定在**同品类等价表述**（「球鞋=运动鞋=跑鞋」），
   禁止把「手机」扩到「华为/小米」（那是品牌，属召回泛化，会稀释 `MATCH AGAINST` 权重）。
4. query 为 `null` / 空白 / 无命中词组时，**原样返回**，不做任何包装。
5. 扩展后的关键词串长度需有上限（`MAX_EXPANDED_LENGTH`），避免超长 query 拖慢 FULLTEXT。
6. 配置缺失或非法（空词组、单词组）不得抛异常，视为「该组无效」跳过。
7. `enabled=false` 时 `expand()` 必须原样返回 query，保证可做 baseline 对比。

## 四、传参 / 入参

| 方法 | 入参 | 说明 |
|------|------|------|
| `SynonymExpander.expand(String query)` | 原始用户 query | 返回 `原词 + 空格 + 同义词...`；无命中时返回原值 |
| `RagSearchService.search(query, topK, categoryId, minPrice, maxPrice)` | 签名**不变** | 内部对 `query` 做扩展后仅传给 `keywordSearchService` |
| `QueryRewriter.rewrite(userMessage, recentMessages)` | 签名**不变** | 仅在 prompt 内新增同义词指令 |

`RagSearchService.search` 与 `QueryRewriter.rewrite` 的对外签名一律不变，调用方（`ChatService`、`RagSearchController`）**零改动**。

## 五、错误返回

| 场景 | 行为 |
|------|------|
| query 为空 | `expand()` 原样返回 null / 空串 |
| 无同义词命中 | 原样返回 query |
| 同义词配置为空 | 等同于无命中，功能静默失效（记一条启动 warn） |
| 扩展后关键词路 SQL 异常 | `KeywordSearchService` 既有 try/catch 返回空列表，RRF 退化为纯向量路 |
| 同义词组含空串 | 构建索引时过滤，不参与匹配 |

## 六、容易踩的坑

1. **收益可能被向量路掩盖**：向量路语义泛化本就可能召回「移动电源」类商品，
   混合检索的 Recall 提升会因此被稀释。**评估必须做「关闭扩展 vs 开启扩展」对照**，
   并单独观察「向量路降级为纯关键词」时的收益——那才是该优化的下限保障场景。
2. **`MATCH AGAINST` 的 OR 语义会稀释权重**：`NATURAL LANGUAGE MODE` 对多词按词加权排序，
   扩展词越多，原词权重占比越低。因此同义词组**宁少勿滥**，且扩展串有长度上限。
3. **ngram=2 的特性**：单字词无法命中，两字词才可。同义词本身须≥2 个汉字。
4. **不要用 `LIKE`**：`LIKE '%同义词%'` 无法利用 FULLTEXT 索引，全表扫描；扩展后的串仍走
   `MATCH AGAINST IN NATURAL LANGUAGE MODE`。
5. **LLM 扩展不可评估**：`QueryRewriter` 的输出随模型/温度变化，评估集指标无法复现，
   所以确定性词表是主线，prompt 只是兜底。
6. **词表落 `yml` 不落 Java 常量**：运营可加词不改代码、不动构建；代价是启动时解析一次（可忽略）。
7. **整词匹配的代价**：自然语言长句（「我想买个充电宝」）不会被扩展，必须依赖 `QueryRewriter`
   先把句子收敛为关键词，再交给 `SynonymExpander`。这是两层的协作契约，也是「宁可漏扩展、
   不可错扩展」的取舍——子串匹配虽然能覆盖长句，但会带来错误扩散。
8. **配置失效是静默的**：yml 语法/编码写错时功能只留一条启动 warn。已用
   `SynonymPropertiesBindingTest` 兜住「yml → 绑定 → 扩展」链路。

## 七、验收标准

1. `SynonymExpanderTest` 单测全绿：双向扩展、整词匹配、`enabled=false` 原样返回、空输入、无关 query 不扩展；
2. `RagEvaluationTest` 补充同义词类 case，并支持 `enabled` 关闭跑 baseline，输出两组指标对照；
3. `mvn -o test -Dtest=SynonymExpanderTest` 通过；`mvn -o test-compile` 无错误；
4. 真实 Recall@5 / MRR 提升数字在 Qdrant 可用环境下由 `RagEvaluationTest` 输出（环境依赖，非 CI 硬门槛）。
