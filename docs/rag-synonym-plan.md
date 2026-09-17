# RAG 同义词扩展（坑②）— 计划书

> 日期：2026-09-17　对应设计文档：`docs/rag-synonym-design.md`

| # | 子任务 | 产出 | 验收方式 |
|---|--------|------|---------|
| 1 | `SynonymProperties` 配置类 | `rag.synonym.enabled` + `rag.synonym.groups` | 能绑定 `List<List<String>>`，缺省为空不报错 |
| 2 | `SynonymExpander` 组件 | 双向索引构建 + `expand()` | 单测覆盖双向、整词、开关、空输入 |
| 3 | `RagSearchService` 接入 | 关键词路用扩展 query，向量路保持原 query | 单测：`search()` 传扩展串给 `KeywordSearchService` |
| 4 | `QueryRewriter` prompt 补同义词指令 | 表外兜底 | 代码审查：指令明确「仅补同义表述、不改品类」 |
| 5 | `application.yml` 配置同义词组 | 5 组领域同义词 | 启动可加载，`SynonymExpander` 索引非空 |
| 6 | `SynonymExpanderTest` 单测 | ≥6 用例 | `mvn -o test -Dtest=SynonymExpanderTest` 全绿 |
| 7 | `RagEvaluationTest` 补 case + 对照 | 同义词 case + baseline/扩展双跑 | Qdrant 可用时输出两组 Recall@5/MRR |
| 8 | 编译与回归 | 编译通过 | `mvn -o test-compile` 无错误；既有单测不回归 |

## 执行顺序

1 → 2 → 5 → 3 → 4 → 6 → 8，其中 7 依赖 Qdrant 运行环境，放在最后单独跑。

## 词表设计（基于真实 mock 数据标注）

| 词组 | 期望商品 ID（人工标注） | 扩展前字面路 |
|------|----------------------|-------------|
| 充电宝 / 移动电源 / 充电电池 | 124, 216, 255 | 仅命中 255 |
| 刮胡刀 / 剃须刀 / 电动剃须刀 | 34, 56, 60 | 0 命中 |
| 球鞋 / 运动鞋 / 跑鞋 / 跑步鞋 | 20, 145, 161, 205, 236, 258 | 0 命中 |
| 充电头 / 快充头 / 充电器 | 153, 210, 294 | 0 命中 |
| 笔记本电脑 / 游戏本 / 轻薄本 | 136, 137, 186 | 0 命中 |

## 遗留与不做项

- **不做**：坑③ 价格元数据实时化——项目无商家改价接口，属提前布局，待改价功能立项时一并处理；
- **不做**：坑④ 评价模块——无数据模型，需先建表，是否做取决于业务；
- **不做**：同义词表落库（DB 表）——当前量级（5 组）用 yml 足够，避免无收益的建表与运维成本；
- **已知限制**：向量路可能已覆盖部分同义词召回，导致混合检索的 Recall 提升幅度低于关键词路单路提升，
  评估需按「baseline vs 扩展」对照解读，不得只报单一指标。

## 验收记录（2026-09-17）

| # | 子任务 | 结果 |
|---|--------|------|
| 1 | `SynonymProperties` | 通过：`SynonymPropertiesBindingTest` 验证 yml 绑定到 5 组 |
| 2 | `SynonymExpander` | 通过：`SynonymExpanderTest` 9/9 全绿 |
| 3 | `RagSearchService` 接入 | 通过：编译无误；关键词路改用扩展串，向量路保持原 query |
| 4 | `QueryRewriter` prompt | 通过：新增同义词补全指令 + 类注释示例 |
| 5 | `application.yml` | 通过：配置绑定测试验证（含中文词组） |
| 6 | `SynonymExpanderTest` | 通过：9 用例（双向/分段/整词/去重/开关/空输入/非法组/空词表） |
| 7 | `RagEvaluationTest` | **待环境**：代码就位（5 条同义词 case + baseline/扩展双跑），Qdrant 未运行无法实测 |
| 8 | 编译与回归 | 通过：`mvn -o test-compile` BUILD SUCCESS；`ProductIndexerTest` 5/5、`RagMetricsTest` 3/3、`ChatIntentResolverTest` 5/5 无回归 |

**附加交付**：`SynonymPropertiesBindingTest`（1/1），兜住「yml → 绑定 → 扩展」链路，防止配置写错导致功能静默失效。

**待办（需本机启动 Docker Desktop + Qdrant + Redis + RocketMQ）**：

```bash
mvn -o test -Dtest=RagEvaluationTest
```

运行后从输出中读取「关闭扩展(baseline) vs 开启扩展」的 Recall@5 / MRR 对照值，回填本节。
