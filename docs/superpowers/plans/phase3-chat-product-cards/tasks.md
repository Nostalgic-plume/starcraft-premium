# Phase 3 客服对话商品卡片——任务规划

> 依据 `requirements.md` 拆分。仅任务规划，不含代码。按依赖拓扑排序，自上而下执行。
> 用户已确认：**不展示相关性分数**；参考业界 Rich Card / Carousel 设计；**新标签打开详情页**（C14）。
>
> **状态：2026-08-29 全部完成**，验证结果见文末。

- [x] T1: 后端 SSE `products` 事件推送——`ChatService` 在 hits 非空时，于首个 token 前推送 3 个商品的结构化数组（C1/C2/C4/C5）（依赖：无）
    验收要点：① RAG 命中时 SSE 先出 `{"type":"products","content":[...3 个商品...]}`，再出 token（**实测通过**）；② 无命中时不推送；③ 字段仅 `productId/name/image/minPrice/maxPrice/excerpt`，**无 score**；④ `excerpt` 截断 ≤40 字；⑤ 现有 token/end/error 事件语义不变；⑥ SystemPrompt 已有"引用商品列表"人设约束（R1）。

- [x] T2: 前端 SSE 解析 products 事件 + 状态管理——`FloatingChat` 解析新增事件并存入商品列表状态（依赖：T1）
    验收要点：① `chat.ts` 增加 `ProductCard` 类型 + `onProducts` 回调 + products 解析（含类型收窄，**修复 TS2345 联合类型报错**）；② `FloatingChat` 加 `recommendProducts` 状态 + `onProducts` 回调；③ 每轮新消息清空上一轮卡片；④ 兼容旧事件（token/end/error 不受影响）。

- [x] T3: 前端商品卡片组件渲染——新增商品卡片区域，在消息列表底部竖排渲染 3 卡（依赖：T2）
    验收要点：① 卡片 = 左缩略图（64px）+ 右侧名称/价格/摘要；② 样式对齐设计系统（深墨蓝 #1a1a2e / 珊瑚红 #e94560 / 16px 圆角 / 品牌阴影）；③ 3 卡竖排；④ 图片缺失/加载失败显示占位图（SVG data URI，C9）；⑤ 价格 null → "询价"、区间相等 → 单值（C8）；⑥ 无 recommendProducts 不渲染卡片区。

- [x] T4: 卡片点击新标签打开详情页——`window.open('/product/'+id, '_blank')`（C14）（依赖：T3）
    验收要点：① **确认用户端详情页路由为 `/product/:id`**（`router/index.ts:26`，C12，**实测匹配**）；② 点击整卡 `window.open` 新标签打开；③ 该路由无 `requiresAuth`，未登录也能看详情（符合商品公开设计）；④ 当前页与对话保留，AI 客服可继续使用。

- [x] T5: 集成回归验证——Phase 3 全量改动后验证（依赖：T1,T2,T3,T4）
    验收要点：① 后端编译 + 前端 build 通过；② **实测 SSE：products 事件先于 token，3 商品无 score，价格 null/单值处理正确**；③ 更新文档（CLAUDE.md + tasks.md）。

---

## 验证结果记录（2026-08-29）

| 验证项 | 结果 |
|--------|------|
| 后端 `mvn compile` | BUILD SUCCESS |
| 前端 `npm run build` | ✓ built in 13.85s（修复 TS2345 后） |
| SSE 事件顺序 | ✅ products → token → end |
| products 内容 | ✅ 3 商品（华为Mate60Pro/小米14Ultra/华为MatePadPro），**无 score** |
| 价格处理 | ✅ null → 前端"询价"；单值 54737.65 → 显示单值 |
| 路由匹配 | ✅ `/product/:id` 与 `window.open` 一致 |

### 回归发现并修复的 1 个 TS 问题
- `chat.ts` ChatStreamFrame.content 联合类型 `string | ProductCard[]` 在 token/error 分支未收窄 → TS2345 → 加 `typeof parsed.content === 'string'` 类型判断修复。
