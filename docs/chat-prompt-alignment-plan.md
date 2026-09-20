# 智能客服提示词收口计划书（意图分类 4 类 + 订单段落）

> 日期：2026-09-20　配套设计稿：`chat-prompt-alignment-design.md`

## 一、任务拆解与验收

| # | 子任务 | 触及文件 | 验收方式 | 状态 |
|---|---|---|---|---|
| 1 | 分类器提示词扩为 4 类（cancel/query/chitchat/product） | `ChatIntentResolver.java` | 读源码确认 4 类齐全、chitchat 有独立条目 | ✅ |
| 2 | 解析链补 `chitchat`，顺序排在 `product` 前 | `ChatIntentResolver.java` | 源码确认短路顺序 cancel→query→chitchat→product→null | ✅ |
| 3 | 新增 `AMBIGUOUS_MAX_LEN`，规则③ 改「订单域词 或 短句无信号」触发 | `ChatIntentResolver.java` | 源码确认 `ambiguousShort` 同时排除商品信号词与闲聊词 | ✅ |
| 4 | 主 prompt 插「## 当有订单信息时」段落 + 边界说明 | `ChatService.java` | 源码确认段落位置与 5 条约束 | ✅ |
| 5 | 编译（离线） | — | `mvn -o -DskipTests compile` 退出码 0 | ✅ |
| 6 | 单测（含闲聊守门 / LLM 失败兜底边界） | `ChatIntentResolverTest.java` | 7 项全绿 | ✅ |
| 7 | 重启后端并实测 7 条用例 | — | 见下表实测记录 | ✅ |
| 8 | 设计稿 + 计划书落盘 `docs/` | `docs/*.md` | 文件存在且入库 | ✅ |

## 二、执行顺序与依赖

1 → 2 → 3 属同一文件（`ChatIntentResolver.java`），必须按序改，否则中间态编译不过。
4 独立（`ChatService.java`），可与 1~3 并行编辑。
5、6 依赖 1~4 全部完成；7 依赖 6 通过；8 最后落盘。

## 三、实测记录

环境：后端 `mvn -o spring-boot:run`，启动 4.226s，无 ERROR；
账号 `zhangsan`；请求头 `token`；`conversationId` 用数字串。

| # | 输入 | `route` | 商品帧 | 回复要点 |
|---|---|---|---|---|
| 1 | 在干嘛 | `chitchat` | 0 | 「正在这儿等你来聊天呢~😊」 |
| 2 | 你是deepseek吧 | `chitchat` | 0 | 自我介绍，不推销 |
| 3 | 能不能陪我聊天 | `chitchat` | 0 | 陪聊语气，无商品 |
| 4 | 我想买个手机 | `product` | 1 | 追问价位/用途 |
| 5 | 我的订单 | `order_query` | 0 | 9 笔真实订单列表 |
| 6 | 我订单的进度怎么样 | `order_query` | 0 | 按状态分类，**未追问「想找什么类型」** |
| 7 | 我那个单子咋样了 | `order_query` | 0 | LLM 兜底 + 订单段落，真实数据 |

## 四、验收结论

- 两处目标冲突全部消除：闲聊不再被 LLM 分类成 product；问订单不再被反问「想找什么类型的东西」。
- 无回归：`product` 链路仍推商品卡片，确定性订单路径（「我的订单」）仍是 0 延迟。
- 延迟未劣化：仅「订单域词」与「≤12 字无信号词」两类输入多一次 LLM 分类，正常购物句不触发。

## 五、复现命令

```powershell
# 编译
$env:JAVA_HOME='C:\Users\26373\.jdks\openjdk-22.0.1'
& 'D:\software\maven\apache-maven-3.9.9\bin\mvn.cmd' -o -DskipTests compile

# 单测
& 'D:\software\maven\apache-maven-3.9.9\bin\mvn.cmd' -o -Dtest=ChatIntentResolverTest test

# 重启 + 实测（脚本：D:\codex\run-backend.ps1、D:\codex\verify-chat.ps1）
```
