# Scoped 样式失效修复 —— 执行计划书

> 设计依据：`docs/scoped-css-dead-styles-design.md`

## 子任务

| # | 任务 | 验收方式 |
|---|------|----------|
| 1 | 全量扫描三端，产出失效清单（类名 / 文件 / 数量） | 扫描脚本输出与人工抽查一致（商家端商品页图片 400px 现象能被解释） |
| 2 | 动态类名引用核查（避免误判"仅渲染函数使用"） | `rg ':class="[^"]*(cell-\|product-\|buyer-\|goods-\|action-)'` 结果为空 |
| 3 | 写批量修复脚本（postcss 改选择器，空跑先行） | 空跑输出与任务 1 清单逐条对应，无"未匹配到规则" |
| 4 | 应用改动（12 文件） | `git diff` 仅选择器变化，声明与格式无噪音 |
| 5 | 重跑扫描 | 失效类命中 0 |
| 6 | 构建三端 | `npm run build` 全部通过（含类型检查） |
| 7 | 浏览器逐页验收（商家端 6 页实机 + 管理后台产物核验） | 见下表断言全部命中 |

## 验收断言（商家端实机，`localhost/merchant/dashboard/*`）

| 页面 | 断言 | 实测 |
|------|------|------|
| orders | `.cell-price` 色 rgb(45,127,249) / 字重 600 / 等宽字体；`.buyer-name` 14px；`.goods-name` nowrap；`.action-cell` inline-flex | 全部命中 |
| coupons | `.cell-price` 蓝 + 600；`.action-btns` flex | 全部命中 |
| reviews | `.cell-mono` 等宽；`.cell-muted` rgb(152,161,175)；`.cell-price` 蓝 | 全部命中 |
| after-sales | `.cell-mono` 等宽；`.cell-price` 蓝 | 全部命中 |
| stock-alert | `.goods-name` 14px；`.cell-muted` 灰 | 全部命中 |
| products | `.product-thumb` 100×100；`.product-name` 字重 500 | 全部命中 |

管理后台：未在本机登录 admin 账号做实机验收，改用**构建产物核验**——两端的 `dist/assets/*.css` 中均存在 `.table-card[data-v-*] .类名` 编译结果（商家端 35 条 / 管理后台 32 条选择器命中）。如需实机验收，登录 `admin/123456` 后同表复跑一遍即可。

## 执行记录

- 2026-09-23：完成 1~7；改动 12 文件 / 选择器 49 处（含共用类新增副本 8 条）；扫描归零；三端构建通过。
- 遗留决策：`frontend-admin/src/views/Products.vue` 的 `.product-thumb` 按其**原设计值 48×48** 生效（商家端此前已按你的要求改为 100×100）。是否统一为 100 待确认，一行可改。
- 未做：`git commit`（按我的工作约定不代你提交，可自行提交）。