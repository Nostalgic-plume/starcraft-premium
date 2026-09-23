# Scoped 样式失效（渲染函数节点）修复 —— 设计文档

> 批次：2026-09-23　范围：`frontend-merchant`、`frontend-admin`（`frontend` 用户端扫描为 0 命中）

## 1. 现象

| 页面 | 表现 |
|------|------|
| 商家端 商品管理 | 缩略图撑到图片原始尺寸 400×400，行高被顶到 469px；商品名无语义（品牌行看不出层级） |
| 商家端 订单/优惠券/评价/售后/库存 | 金额没有品牌蓝、没有等宽；`cell-muted` 灰色、`buyer-*`/`goods-*` 的 flex 布局全部丢失 |
| 管理后台 同上 6 个页面 | 同症状 |

## 2. 根因

1. Vue SFC 的 `scoped` 样式编译成 `.类名[data-v-hash]`，只有**模板编译**出来的节点才会带 `data-v-hash`。
2. Naive UI `n-data-table` 的列 `render`（`h('div', { class: 'cell-price' })`）在**别的组件渲染时**创建节点，不经过本组件模板编译 → 节点上没有 `data-v-hash` → 整条规则不匹配。
3. 所以这不是"尺寸写错了"，而是**样式根本没生效**：图片只能按原图尺寸渲染，金额丢色，布局退回 block。

## 3. 影响面（脚本扫描，非人工枚举）

- 商家端 6 个文件 27 处；管理后台 6 个文件 25 处；合计 **12 文件 52 处**
- 典型类名：`cell-price / cell-muted / cell-mono / buyer-cell / buyer-name / buyer-phone / goods-cell / goods-name / goods-sub / action-cell / action-btns / product-cell / product-thumb / product-name / product-brand / cell-stock`

## 4. 硬约束

- 样式必须继续写在 `<style scoped>` 内（团队规范：全局样式只放 `assets/style.css`），不得改成全局样式。
- 不改模板结构、不改渲染函数逻辑、不碰接口契约与数据流。
- **模板与渲染函数共用的类**（`cell-price / cell-muted / cell-mono` 等）必须保留原规则，只**新增** `:deep()` 副本：抽屉 / 弹窗是 teleport 到 `body` 的，`:deep()` 编译出的 `[data-v-h] .类名` 匹配不到 teleport 内容，删掉原规则会把那部分样式弄坏。
- 预览期的临时内联样式（Codex 注解预览产生的 `style` 属性）不入库，改的是源样式 / 设计令牌。

## 5. 方案取舍

| 方案 | 说明 | 结论 |
|------|------|------|
| A `:deep()` 补规则 | `.table-card :deep(.cell-price) { …原声明… }` | **采用**：改动最小、只动选择器、规范兼容 |
| B 抽单元格子组件 | 新组件自带 scoped，天然生效 | 暂缓：要动 12 个文件的模板结构，收益低于风险 |
| C 写成非 scoped 全局样式 | 直接绕过作用域 | 否决：违反规范、污染全局、类名易冲突 |

## 6. 容易踩的坑

1. `:deep()` 要挂在**真实存在的容器**上。本次 12 个文件的表格容器统一是 `div.table-card`，编译结果为 `.table-card[data-v-h] .类名`；若写成裸 `:deep(.类名)` 会退化为 `[data-v-h] .类名`，作用面变宽（但仍不会命中 teleport 内容）。
2. 共用类必须"双写"，见硬约束第 3 条。
3. **别用正则批量改 CSS**：存在多选择器规则、`@media` 内的覆盖规则，正则容易漏改或改坏。本次用 postcss 只替换选择器、声明与格式原样保留（`git diff` 无空白噪声）。
4. 改前必须先确认模板里没有动态类名引用（`:class="'cell-price'"` 这类），否则"只在渲染函数里用"的判断会失效。本次已核查为 0。
5. 同类陷阱不止 CSS：**GSAP 动画结束后残留的 `transform: matrix(1,0,0,1,0,0)` 会创建层叠上下文 / 参与定位层**，这是用户端首页热搜浮层被统计条遮挡的原因（已在 `frontend/src/views/Home.vue` 处理）。

## 7. 验收标准

1. 扫描脚本命中数归零：`node D:\codex\_scan_vue.js`
2. 三端 `npm run build` 通过（含 `vue-tsc --noEmit` 类型检查）
3. 构建产物中存在编译后的选择器：`.table-card[data-v-*] .类名`
4. 浏览器逐页计算样式断言（见计划书的验收表）

## 8. 复现/扫描工具

- 扫描：`D:\codex\_scan_vue.js`（判断规则：类名出现在 `class:` 中，且只在 scoped 样式里以**普通选择器**定义 → 视为失效）
- 批量修复：`D:\codex\_fix_scoped.js`（默认空跑，`--apply` 才写入）
- 如需随仓库长期使用，可移到 `docs/tools/` 后再入库。