# 三端前端视觉翻新 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标**：将三端前端统一升级为"暖调轻奢"视觉风格，延续深墨蓝 + 珊瑚红 + 暖灰品牌色，精细化设计 Token、组件与布局

**架构**：四阶段递进——Phase 1 共享 Token 基底 → Phase 2 用户端全面翻新 → Phase 3 管理后台全面翻新 → Phase 4 商家中心全面翻新。每个阶段在独立 subagent 上下文中执行，彻底避免上下文撑爆

**技术栈**：Vue 3 + Vite + Element Plus + GSAP（页面转场）+ ECharts（图表）

## 全局约束

- 每次只处理一个端，绝不同时处理多个端
- 共享基底 `shared/css/style-base.css` 改完即锁定，后续端仅引用不修改
- 所有 CSS 变量优先使用 `--ds-*` Token，fallback 写具体值以兼容旧代码
- 每个端完成后必须 `npm run build` 验证编译通过
- 前端比较用 `==` 而不是 `===`（后端 Long→String 序列化的副作用）
- 用户端页面转场已引入 GSAP，其他两端维持 CSS transition 不动
- 当前工作目录下已有部分修改（shared/style-base.css + frontend/ 四个文件），基于此继续

---

### Phase 0：准备工作

**说明**：确认基线状态，锁定当前修改不丢失

- [ ] Stash 或提交当前工作区修改确保不丢
  ```bash
  cd "d:/claudecode/shop"
  git stash
  ```
  → 然后依次完成各 Phase，每完成一个 Phase commit 一次

> 实际执行时可以选择不 stash，直接基于当前工作区继续。这里只是确保安全。

---

### Phase 1：共享设计 Token 基底

**文件**：
- 修改：`shared/css/style-base.css`

**说明**：当前已新增了 shadow-coral、spring、radius-square、card-elevated、badge-square、GSAP 字体引用、loading spinner 等。本阶段在此基础上做最终补全和校验。

- [ ] **Step 1：补全缺失的 Token 定义**

检查以下 Token 是否已定义，缺失则补充：

| Token | 值 | 用途 |
|-------|-----|------|
| `--ds-border` | `#e0ddd5` | 标准边框 |
| `--ds-border-light` | `#f0eee9` | 浅边框（已有引用） |
| `--ds-coral-gradient` | `linear-gradient(135deg, #e94560, #ff6b81)` | 按钮/高亮渐变 |

- [ ] **Step 2：补全 Element Plus 全局覆盖**

添加以下覆盖（如已存在则跳过）：
- `.el-card` 圆角 `--ds-radius-lg` + 阴影 `var(--ds-shadow-sm)`
- `.el-table` 表头样式（墨蓝背景白字，或保持干净浅灰）
- `.el-pagination` 按钮圆角统一

- [ ] **Step 3：build 验证**

```bash
cd "d:/claudecode/shop/frontend" && npm run build 2>&1 | tail -5
```

预期输出：`✓ built in X.Xs` 或类似成功信息

- [ ] **Step 4：提交 Phase 1**

```bash
cd "d:/claudecode/shop"
git add shared/css/style-base.css
git commit -m "style: 补全设计 Token 基底（边框/渐变/Element Plus 覆盖）"
```

---

### Phase 2：用户端 frontend/ 全面翻新

**文件**：
- 修改：`frontend/index.html`
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/views/Home.vue`
- 修改：`frontend/src/views/ProductDetail.vue`
- 修改：`frontend/src/views/Cart.vue`
- 修改：`frontend/src/views/MyOrders.vue`
- 修改：`frontend/src/views/OrderDetail.vue`
- 修改：`frontend/src/views/Login.vue`
- 修改：`frontend/src/views/Register.vue`

**说明**：当前 App.vue 已做了 GSAP 转场和导航栏翻新，Home/Cart/ProductDetail 已有部分改动。本阶段补全并完成所有页面。

#### Task 2.1：补全 App.vue 导航栏

**当前状态**：GSAP 转场已完成，导航栏样式已更新。本步骤做最终检查和微调。

- [ ] 检查导航栏设计稿对齐：
  - 深墨蓝背景？当前是白色 → 设计是深墨蓝
  - 登录注册按钮配色
  - 购物车 badge 珊瑚红
- [ ] 登录/注册按钮改为：登录→线框 / 注册→珊瑚红填充

#### Task 2.2：完成 Home.vue 首页

**当前状态**：已有部分改动。本步骤完成：
- [ ] 分类导航：卡片化，hover 上浮 + 阴影加深
- [ ] 商品列表：Bento Grid 错落布局（不等宽网格，交错排列）
- [ ] 商品卡片：白色 + 16px 圆角 + `card-elevated` 类
- [ ] 图片占卡片 70%+ 面积
- [ ] 价格珊瑚红大字，原价灰删除线
- [ ] 加购按钮 hover 珊瑚红渐变

#### Task 2.3：完成 ProductDetail.vue 商品详情

- [ ] 主图 `--ds-radius-xl`(24px) 圆角，底部缩略图行
- [ ] 购买区独立卡片，浅灰底区分
- [ ] "加入购物车"珊瑚红填充 / "立即购买"墨蓝填充
- [ ] 价格走势图保留（金色线 + 珊瑚红锚点）

#### Task 2.4：完成 Cart.vue 购物车

- [ ] 每个商品项改为圆角卡片，非纯表格行
- [ ] 数量选择器圆形加减按钮，墨蓝边框
- [ ] 底部结算栏固定 + 顶部阴影
- [ ] 结算按钮珊瑚红大按钮

#### Task 2.5：翻新 MyOrders.vue 订单列表

- [ ] 状态标签彩色：已支付→绿 / 已发货→蓝 / 已完成→灰
- [ ] 订单卡片折叠式，点击展开详情

#### Task 2.6：翻新 OrderDetail.vue 订单详情

- [ ] 与订单列表风格一致
- [ ] 商品项卡片化，图片圆角

#### Task 2.7：翻新 Login.vue / Register.vue

- [ ] 表单卡片居中，`--ds-radius-lg` + 阴影
- [ ] 登录按钮珊瑚红 / 注册按钮墨蓝
- [ ] 品牌 Logo 展示区

#### Task 2.8：build 验证 + 提交

- [ ] `npm run build` 编译通过
- [ ] commit Phase 2

---

### Phase 3：管理后台 frontend-admin/ 全面翻新

**文件**：
- 修改：`frontend-admin/index.html`
- 修改：`frontend-admin/src/views/Layout.vue`
- 修改：`frontend-admin/src/views/Dashboard.vue`
- 修改：`frontend-admin/src/views/DataDashboard.vue`
- 修改：`frontend-admin/src/views/Orders.vue`
- 修改：`frontend-admin/src/views/Products.vue`
- 修改：`frontend-admin/src/views/Users.vue`
- 修改：`frontend-admin/src/views/Login.vue`

**说明**：管理后台目前未做任何改动，完全从基线开始。

#### Task 3.1：翻新 Layout.vue 布局

- [ ] 左侧深墨蓝侧栏，宽度 220px，菜单紧凑排列
- [ ] 当前菜单项：珊瑚红左边框(3px) + 微亮背景
- [ ] 顶部栏白色 + 底部 `--ds-shadow-md`
- [ ] 右侧管理员头像 + 退出

#### Task 3.2：翻新 Dashboard.vue + DataDashboard.vue 控制台

- [ ] 统计卡片白色 + 16px 圆角，图标珊瑚红/金渐变
- [ ] 4 个图表外壳卡片化，保留现有 ECharts 配置
- [ ] 图表配色对齐：墨蓝线 + 珊瑚红强调 + 金色梯度

#### Task 3.3：翻新 Orders.vue 订单管理

- [ ] 筛选区横向紧凑排列
- [ ] 表格行 hover 浅灰高亮
- [ ] 状态列彩色标签（圆点 + 文字）

#### Task 3.4：翻新 Products.vue 商品管理

- [ ] 缩略图列 60x60 圆角
- [ ] 上架/下切用 Switch 组件
- [ ] 表格行 hover 效果

#### Task 3.5：翻新 Users.vue 用户管理

- [ ] 状态列彩色标签
- [ ] 操作按钮珊瑚红

#### Task 3.6：翻新 Login.vue

- [ ] 居中卡片式，管理后台品牌标识
- [ ] 登录按钮墨蓝

#### Task 3.7：build 验证 + 提交

- [ ] `npm run build` 编译通过
- [ ] commit Phase 3

---

### Phase 4：商家中心 frontend-merchant/ 全面翻新

**文件**：
- 修改：`frontend-merchant/index.html`
- 修改：`frontend-merchant/src/views/Layout.vue`
- 修改：`frontend-merchant/src/views/Dashboard.vue`
- 修改：`frontend-merchant/src/views/Orders.vue`
- 修改：`frontend-merchant/src/views/Products.vue`
- 修改：`frontend-merchant/src/views/Login.vue`

**说明**：商家中心目前未做任何改动，完全从基线开始。风格介于用户端和管理后台之间。

#### Task 4.1：翻新 Layout.vue

- [ ] 侧栏浅色底 + 墨蓝文字（比管理后台轻，比用户端正式）
- [ ] 顶部栏简洁白色

#### Task 4.2：翻新 Dashboard.vue 控制台

- [ ] 4 图表卡片化（销售额趋势/订单分布/商品销量TOP5/每日订单数）
- [ ] 统计卡片 16px 圆角，图标珊瑚红

#### Task 4.3：翻新 Orders.vue 订单管理

- [ ] 简洁订单列表，发货按钮珊瑚红强调
- [ ] 状态标签彩色

#### Task 4.4：翻新 Products.vue 商品管理

- [ ] 表格为主，新增商品按钮珊瑚红
- [ ] 缩略图圆角

#### Task 4.5：翻新 Login.vue

- [ ] 居中卡片式，商家品牌标识

#### Task 4.6：build 验证 + 提交

- [ ] `npm run build` 编译通过
- [ ] commit Phase 4

---

### 全部完成后的验证

- [ ] 三个前端均 `npm run build` 通过
- [ ] Nginx 重载配置后页面正常显示
