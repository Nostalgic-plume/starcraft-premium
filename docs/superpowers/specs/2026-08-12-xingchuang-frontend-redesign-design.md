# 星创优品 · 三端前端重构设计文档（全新设计）

- 日期：2026-08-12
- 状态：待评审
- 范围：用户端（frontend）/ 管理后台（frontend-admin）/ 商家中心（frontend-merchant）

---

## 1. 定位与原则

**推倒重来，全新设计。** 不参考旧设计语言、不沿用旧页面布局、不受旧代码样式约束。

- 保留的只有：**功能与数据契约**（后端接口、字段、会话、商家隔离、登录机制）。
- 放弃的：旧视觉（深墨蓝/珊瑚红/暖灰）、旧布局、旧组件样式、旧 emoji 装饰——全部重新定义。
- 技术栈升级：**Vue 3 + TypeScript + Vite + Naive UI（themeOverrides 深度定制）+ Pinia + ECharts + GSAP**。
- 品牌：**星创优品（XingChuang Premium）**。

### 验收标准
- 三端视觉语言完全统一（同色板、同组件、同圆角/阴影/间距体系）。
- 新代码全部 TypeScript，`npm run build`（含类型检查）三端通过。
- 全部页面按本文档规范重写；功能回归通过（登录/下单/购物车/看板统计/商家隔离）。

---

## 2. 设计方向

**中性基底 + 蓝色克制点缀。**

这是「好看且不单调」的核心解法：不是用一个颜色铺满页面，而是大面积中性色（白/浅灰/墨色文字）建立干净底子，蓝色（品牌主色）只在按钮、价格、选中态、高亮等关键点出现，形成视觉焦点与节奏。

| 原则 | 说明 |
|------|------|
| 中性优先 | 页面 70%+ 面积中性色（白、浅灰、墨蓝黑文字） |
| 蓝做点缀 | 蓝色仅用于：主 CTA、价格、选中态、当前 tab、关键高亮 |
| 语义色克制 | 绿（成功/完成）、橙（待付款/促销）、红（错误/危险）、灰（禁用）只表达状态 |
| 高信息密度 | 商品 4 列网格、卡片紧凑、不做大面积留白 |
| 圆润亲和 | 卡片 16px 圆角、柔和阴影、按钮胶囊圆角 |
| 无 emoji | 图标统一 SVG 线性风格（stroke） |
| 导航完整 | 每个子页面有「返回上一级」入口 |

---

## 3. 设计令牌（Design Tokens）

统一在 `shared/css/tokens.css`，三端 `main.ts` 引入，并覆盖 Naive UI 主题。

```css
:root {
  /* 中性基底 */
  --ds-bg: #F6F7FA;  --ds-bg-2: #EEF0F4;  --ds-card: #FFFFFF;
  --ds-line: #E7E9EE; --ds-line-2: #D8DCE4;
  /* 文字 */
  --ds-text: #1E2632; --ds-text-2: #5A6474; --ds-text-3: #98A1AF;
  /* 品牌蓝 */
  --ds-primary: #2D7FF9; --ds-primary-strong: #1E63D6;
  --ds-primary-grad: linear-gradient(135deg,#5AA3FF,#2D7FF9);
  --ds-primary-tint: rgba(45,127,249,0.08);
  --ds-primary-border: rgba(45,127,249,0.25);
  --ds-price: #2D7FF9;               /* 价格色（用户偏好蓝） */
  /* 语义色 */
  --ds-green: #12A77D; --ds-orange: #E08600; --ds-red: #E0404E; --ds-gray: #98A1AF;
  /* 字体/圆角/阴影 */
  --ds-sans: "PingFang SC","Microsoft YaHei","Noto Sans SC",-apple-system,sans-serif;
  --ds-mono: "JetBrains Mono","SF Mono",Consolas,monospace;
  --ds-r-lg:16px; --ds-r:12px; --ds-r-sm:9px; --ds-r-pill:999px;
  --ds-shadow:0 2px 10px rgba(31,38,48,.05);
  --ds-shadow-hover:0 12px 30px rgba(31,38,48,.10);
  --ds-shadow-brand:0 4px 14px rgba(45,127,249,.25);
}
```

### Naive UI 主题定制（themeOverrides）

Naive UI 通过 `NConfigProvider :theme-overrides` 注入主题对象（取代 CSS 变量覆盖）：

```ts
// shared/naive-theme.ts
import type { GlobalThemeOverrides } from 'naive-ui'
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2D7FF9', primaryColorHover: '#5AA3FF',
    primaryColorPressed: '#1E63D6', primaryColorSuppl: '#2D7FF9',
    borderRadius: '10px', borderRadiusSmall: '8px',
    textColorBase: '#1E2632', textColor1: '#1E2632',
    textColor2: '#5A6474', textColor3: '#98A1AF',
    borderColor: '#E7E9EE', dividerColor: '#E7E9EE',
    bodyColor: '#F6F7FA', cardColor: '#FFFFFF', popoverColor: '#FFFFFF',
  },
  Button: { borderRadiusMedium: '10px', fontWeight: '600' },
  Tag: { borderRadius: '999px' },
}
```
（中性/蓝完整映射与 tokens.css 保持一致；具体组件覆盖随实施细化。）

## 4. 排版 / 图标 / 组件

### 4.1 字重层级
H1 28–40/800（页面标题） · H2 20/800（区块标题，蓝色竖条前缀） · H3 16/800（卡片标题） · Body 13–14 · Caption 11–12 · Mono（价格/订单号/KPI）。

### 4.2 SVG 线性图标
`fill:none; stroke:currentColor; stroke-width:1.7; round`。集合：手机/耳机/手表/笔记本/鞋/护肤/家居/食品/搜索/返回/购物车/订单/用户/看板/商品/分类/设置/删除等。通过 `SvgIcon` 组件按 name 渲染。

### 4.3 组件体系
- Naive UI 直接使用（themeOverrides 定制）：Button/Input/Select/DataTable/Form/Pagination/Dialog/Message/Tabs/Tag 等。
- 自定义业务组件（`shared/components` 或各端）：`AppHeader`、`BackBar`（返回）、`ProductCard`、`OrderCard`、`StatusTag`、`KpiCard`、`ChartCard`、`SvgIcon`。
- 统一约束：主 CTA 用蓝色渐变 + 光晕；价格一律 `--ds-price`；状态一律 `StatusTag`；卡片白底 + 中性描边 + 柔和阴影，hover 上浮 + 蓝描边。

---

## 5. 页面清单（全部重写）

### 5.1 用户端（现有 8 页 + §11 新功能页）
| 页面 | 布局要点 |
|------|---------|
| 首页 Home | 顶部导航 → Hero（中性渐变 + 蓝关键词 + 蓝 CTA + 数据条）→ 分类侧栏 + 4 列商品网格 → 分页 |
| 商品列表/搜索 | 筛选栏 + 商品卡网格 |
| 商品详情 | 返回 → 面包屑 → 左图右信息（价格卡/SKU/数量/加购+立即购买）→ 详情卡 |
| 购物车 | 返回 → 商品行 → 结算栏 |
| 确认订单 | 返回 → 收货信息 → 商品清单 → 金额汇总 → 提交 |
| 我的订单 | 返回 → 状态 tabs → 订单卡（状态标签右对齐） |
| 订单详情 | 返回 → 状态 + 进度条 → 收货 → 商品 → 金额明细 |
| 登录 / 注册 | 居中卡片（Logo + 表单 + 蓝按钮） |

### 5.2 管理后台（现有 5 页 + §11 新功能页）
Layout（左 180px 导航）· 数据看板（蓝色总览横幅 + KPI + 图表 + 表格）· 商品管理 · 订单管理 · 用户管理。

### 5.3 商家中心（现有 4 页 + §11 新功能页）
Layout · 数据看板 · 商品管理 · 订单管理（发货）。

---

## 6. ECharts 配色

```ts
// shared/js/echarts-theme.ts
export const CHART_TEXT = '#5A6474'
export const CHART_LINE = '#2D7FF9'
export const CHART_BARS = ['#2D7FF9', '#5AA3FF', '#8CBFFF', '#C4E0FF']
export const CHART_ACCENT = '#E08600'  // 少量强调
```
柱状/趋势/饼图统一蓝系 + 中性坐标轴。

---

## 7. 动效与装饰 UI
- 页面切换：GSAP fade + translateY（200–300ms）。
- 卡片 hover：上浮 + 蓝描边 + 阴影加深（0.25s）。
- 装饰 UI（氛围增强）：
  - Hero 背景：浅色光晕 / 细网格 / 粒子点缀（CSS 或 GSAP）。
  - 数字动画：KPI 数字 count-up（GSAP）。
  - 骨架屏：列表 / 详情加载占位。
  - 空状态插画：自定义 SVG 插画。
  - 收藏 / 评价等交互：轻量弹性反馈动画。
- 原则：装饰服务于氛围，克制不炫技。

---

## 8. 技术栈与工程化

| 项 | 选型 |
|----|------|
| 框架 | Vue 3.4 + TypeScript 5 + Vite 5 |
| UI | Naive UI 2.x（themeOverrides 定制）+ 自定义业务组件 |
| 状态/路由 | Pinia / Vue Router 4 |
| 图表 | ECharts 5 + vue-echarts |
| 动画 | GSAP（三端通用，用户端为主） |
| HTTP | Axios（shared `createRequest` 封装） |

### TS 引入
- 三端新增 `typescript`、`vue-tsc`、`tsconfig.json`（strict），`vite.config.ts`。
- 全部新页面/组件 `<script setup lang="ts">`；`shared`（createRequest/createRouter/useAuth/tokens/echarts-theme）迁移 TS。
- API 类型化：为 `Result<T>`、`PageResult<T>`、各 VO 定义 interface（对照后端）。
- `npm run build` = `vue-tsc --noEmit && vite build`。

### 目录（用户端示例）
```
frontend/src/{ main.ts, App.vue, router/index.ts, stores/*.ts, api/*.ts, views/*.vue, components/*.vue, styles/ }
shared/{ css/tokens.css, js/*.ts, icons/*.svg }
```

---

## 9. 明确不做（存量契约不动）
- 不**改动**现有后端接口字段/契约、现有表结构、会话机制、商家隔离、Nginx。
- **新增功能（§11）所需的新表与新接口是允许且必要的**，后端分期实现，不破坏存量契约。
- 不沿用旧设计令牌/旧布局/旧组件样式。

---

## 10. 实施里程碑

| 阶段 | 内容 | 验收 |
|------|------|------|
| M1 工程底座 | 三端 TS 工程化（tsconfig/vite/依赖）+ `shared` tokens/图标/echarts-theme/TS 化 | 三端空跑 build 通过 |
| M2 用户端 | 现有 8 页重写 + 新功能页（领券中心/收藏/评价/地址簿/售后/热搜）前端建界面 + api/store TS 化 + 组件 | http://localhost 全流程可用 |
| M3 管理后台 | 现有 5 页 + 新功能页（券管理/评价管理/售后管理） | /admin/ 可用 |
| M4 商家中心 | 现有 4 页 + 新功能页（本店券/评价回复/售后处理/库存预警） | /merchant/ 可用 |
| M5 收尾 | 动效/响应式/回归冒烟 | 三端 build + 关键链路通过 |

每阶段 `npm run build` + 对照真实后端联调（不 mock）。

---

## 11. 新功能规划（前端留好界面，后端分期实现）

以下功能随重构一并纳入前端页面清单。**前端全部先建出界面**（Naive UI + 新设计，路由/入口齐全），后端接口未实现前用静态/占位数据展示，后端就绪后逐个对接。

| # | 优先级 | 功能 | 用户端 | 管理后台 | 商家中心 | 技术点/亮点 | 阶段 |
|---|--------|------|--------|---------|---------|------------|------|
| ① | **P0** | 商品评价 | 详情评价区+列表、下单后"去评价"、评价表单、我的评价 | 评价管理（审核/删除） | 评价管理（本店评价查看/回复） | 评分 AVG、分页、晒图 | M2+M3+M4 |
| ③ | **P0** | 优惠券 | 领券中心、我的优惠券、结算页选券 | 平台优惠券管理（模板/发券/统计） | **本店优惠券管理**（shop_id 隔离） | Redis 预扣防超发 + 幂等领券 + 满减 | M2+M3+M4 |
| ④ | **P0** | 地址簿 | 地址管理 CRUD、结算页选地址（替换手填） | — | — | CRUD（表已存在）、默认地址 | M2 |
| ② | **P1** | 收藏/心愿单 | 商品卡+详情页收藏按钮、我的收藏 | —（看板可加收藏指标） | — | 唯一索引防重复 | M2 |
| ⑤ | **P1** | 售后/退款 | 我的售后+申请+详情 | 售后管理（审核/退款） | 售后处理（本店） | 状态机、退款与订单联动 | M2+M3+M4 |
| ⑩ | **P2** | 搜索历史/热搜 | 搜索框下拉历史、热搜榜、清空 | 热搜配置/数据（可选） | — | Redis ZSet 热搜 | M2 |
| ⑪ | **P2** | 库存预警 | — | 库存预警（平台，可选） | 库存预警页 + 看板低库存角标 | 定时扫描（ScheduledTasks 扩展） | M4 |
| ⑫ | **P2** | 商家客服聊天（二期） | 商品详情"联系商家"+聊天面板 | 客服记录（可选） | 客服消息工作台 | WebSocket/SSE 实时、会话、未读 | 二期（前端先留界面） |

> **分期策略（P0 / P1 / P2）**：
> - **P0 · 做透（含后端）**：评价、优惠券、地址簿 —— 前端界面 + 后端接口一期全做完，可验收、可讲透。
> - **P1 · 界面 + 简单后端**：收藏、售后 —— 界面完整 + 后端基础接口。
> - **P2 · 前端界面，后端二期**：搜索历史/热搜、库存预警、商家聊天 —— 只做界面/入口，后端明确二期。
> - 每阶段保证有可跑通交付，避免半成品堆积。

> **优惠券三端设计要点**：优惠券表需含 `shop_id` 字段，区分**平台券（null）**与**商家券（对应 shop.id）**；商家券仅限本店下单使用，受商家数据隔离约束（同 product/orders 隔离机制）。领券中心展示平台券+各店券，商家中心仅管理本店券。

**新增表（规划）**：`review`、`favorite`、`coupon`、`user_coupon`、`after_sale`；`address` 已存在待补 CRUD；聊天二期可扩展 `chat` 表。

---

## 12. 已确认 / 待确认

**已确认**：Naive UI 主组件库（替换 Element Plus）· TS 全面迁移 · 价格色品牌蓝 · 新增 8 项功能 · **分期策略 P0/P1/P2**（P0 做透含后端：评价/优惠券/地址簿；P1 界面+简单后端：收藏/售后；P2 界面后端二期：热搜/库存预警/聊天）。

**待确认**：装饰 UI 范围（Hero 光晕/粒子/数字动画/骨架屏等）按 §7 执行是否 OK；实施顺序按里程碑推进是否接受；新功能后端接口分期新增（不破坏存量契约）是否接受。