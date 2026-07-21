# 三端前端视觉翻新设计文档

**日期**：2026-07-21
**项目**：Shop 电商系统
**设计风格**：暖调轻奢（延续深墨蓝 #1a1a2e + 珊瑚红 #e94560 + 暖灰 #f5f3ef）

---

## 一、设计 Token 层（shared/style-base.css）

### 保留不变
- `--ds-navy` / `--primary` = `#1a1a2e`
- `--ds-coral` = `#e94560`（强调色）
- `--ds-gold` = `#f9a825`（图表/等级标识）
- `--ds-bg` = `#f5f3ef`（暖灰底调）
- `--ds-text` = `#1a1a2e`
- `--ds-text-secondary` = `#636e72`
- `--ds-text-inverse` = `#ffffff`
- `--ds-surface` = `#ffffff`

### 升级/新增

| Token | 值 | 用途 |
|-------|-----|------|
| `--ds-navy-light` | `#2a2a4a`（微调提亮） | 侧栏/次级背景 |
| `--ds-radius-sm` | `8px` | 按钮/小元素 |
| `--ds-radius-md` | `12px` | 表单项/标签 |
| `--ds-radius-lg` | `16px` | 卡片 |
| `--ds-radius-xl` | `24px` | 弹窗/大图 |
| `--ds-shadow-sm` | `0 1px 3px rgba(26,26,46,0.08)` | 轻悬浮 |
| `--ds-shadow-md` | `0 4px 12px rgba(26,26,46,0.1)` | 卡片悬浮 |
| `--ds-shadow-lg` | `0 8px 24px rgba(26,26,46,0.12)` | 弹窗/下拉 |
| `--ds-shadow-xl` | `0 16px 40px rgba(26,26,46,0.16)` | Modal |
| `--ds-font-family` | `Outfit, -apple-system, 'Noto Sans SC', sans-serif` | 中英文 fallback |
| `--ds-transition` | `all 0.25s cubic-bezier(0.4, 0, 0.2, 1)` | 统一动效 |
| `--ds-coral-gradient` | `linear-gradient(135deg, #e94560, #ff6b81)` | 按钮/高亮 |

### 跨端配色差异（通过各端 style.css 覆盖）
- **用户端**：珊瑚红占比高（CTA 按钮、促销标签、价格）
- **管理后台**：墨蓝占比高（严肃专业），珊瑚红仅做状态高亮
- **商家中心**：两者均衡

---

## 二、用户端（frontend/）细节

### 导航栏
- 深墨蓝背景 + `--ds-shadow-md` 底部阴影
- Logo 区加大，右侧微金分隔线
- Nav 项 hover：珊瑚红下划线动效（`::after` + scaleX）
- 购物车 badge：珊瑚红填充白字
- 登录线框 / 注册珊瑚红填充

### 首页 Home.vue
- 分类导航：小圆角卡片，hover 上浮 + 影子加深
- 商品列表：Bento Grid 错落布局（非等宽网格）
- 商品卡片：白色 + `--ds-radius-lg` + 悬浮阴影
- 图片占卡片 70%+ 面积
- 价格珊瑚红大字，原价灰删除线
- 加购按钮 hover 珊瑚红渐变

### 商品详情 ProductDetail.vue
- 大图 `--ds-radius-xl`，底部缩略图行
- 价格走势图保留（金色线 + 珊瑚红锚点）
- 购买区独立卡片，浅灰底区分
- "加入购物车"珊瑚红 / "立即购买"墨蓝

### 购物车 Cart.vue
- 每项改为圆角卡片，非纯表格
- 数量选择器：圆形加减按钮，墨蓝边框
- 底部结算栏固定 + 顶部阴影
- 结算按钮珊瑚红大按钮

### 订单页
- 状态标签：已支付→绿 / 已发货→蓝 / 已完成→灰
- 订单卡片折叠式

---

## 三、管理后台（frontend-admin/）细节

### 布局 Layout.vue
- 左侧深墨蓝侧栏 220px，紧凑菜单
- 当前菜单：珊瑚红左边框 + 微亮背景
- 顶部栏白色 + 细阴影，右侧头像+退出
- 内容区暖灰底，卡片式内容区 `--ds-radius-lg`

### 控制台 Dashboard.vue / DataDashboard.vue
- 统计卡片：白色 + 16px 圆角，图标珊瑚红/金渐变
- 4 图表保留（销售额/订单分布/销量排行/用户增长），外壳卡片化
- 图表配色：墨蓝线 + 珊瑚红强调 + 金色梯度

### 订单/商品/用户管理
- 筛选区横向紧凑排列
- 表格行 hover 浅灰高亮
- 状态列彩色标签（小圆点+文字）
- 商品支持表格/卡片视图切换
- 上架/下架用 Switch 组件

---

## 四、商家中心（frontend-merchant/）细节

- 侧栏浅色底 + 墨蓝文字（介于用户端和管理后台之间）
- 控制台 4 图表卡片化（保留现有 ECharts 配置）
- 订单管理：简洁，发货按钮珊瑚红
- 商品管理：表格为主，新增按钮珊瑚红

---

## 五、共享系统提升

- 全局过渡：导航 fade、卡片悬浮 lift、按钮微动效
- Loading 态：珊瑚红调 spinner
- 空状态：统一插画风格
- 响应式：三端各自断点适配

---

## 六、工作策略（防上下文撑爆）

### 执行顺序
```
Phase 1: 更新 shared/style-base.css（设计 Token 基底）
Phase 2: frontend/ 用户端（子 agent 独立上下文）
Phase 3: frontend-admin/ 管理后台（子 agent 独立上下文）
Phase 4: frontend-merchant/ 商家中心（子 agent 独立上下文）
```

### 核心原则
- **每次只处理一个端**，绝不三个端一起塞进同一个上下文
- 每个端用 `superpowers:subagent-driven-development` 按页面拆任务
- 共享基底改完即锁，后续端仅引用不修改 `shared/style-base.css`
- 每个端完成后 `npm run build` 验证编译

---

## 七、涉及文件清单

### 共享
- `shared/css/style-base.css` — 设计Token + 全局样式

### 用户端 frontend/
- `frontend/index.html` — meta/favicon
- `frontend/src/App.vue` — 导航栏全局模板
- `frontend/src/views/Home.vue` — 首页
- `frontend/src/views/ProductDetail.vue` — 商品详情
- `frontend/src/views/Cart.vue` — 购物车
- `frontend/src/views/MyOrders.vue` — 订单列表
- `frontend/src/views/OrderDetail.vue` — 订单详情
- `frontend/src/views/Login.vue` — 登录
- `frontend/src/views/Register.vue` — 注册

### 管理后台 frontend-admin/
- `frontend-admin/index.html`
- `frontend-admin/src/Layout.vue`
- `frontend-admin/src/views/Dashboard.vue`
- `frontend-admin/src/views/DataDashboard.vue`
- `frontend-admin/src/views/Orders.vue`
- `frontend-admin/src/views/Products.vue`
- `frontend-admin/src/views/Users.vue`
- `frontend-admin/src/views/Login.vue`

### 商家中心 frontend-merchant/
- `frontend-merchant/index.html`
- `frontend-merchant/src/Layout.vue`
- `frontend-merchant/src/views/Dashboard.vue`
- `frontend-merchant/src/views/Orders.vue`
- `frontend-merchant/src/views/Products.vue`
- `frontend-merchant/src/views/Login.vue`
