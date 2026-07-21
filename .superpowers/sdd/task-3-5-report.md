# Task 3-5 Report: Products.vue Visual Refresh

## 修改文件列表
- `frontend-admin/src/views/Products.vue`

## 变更摘要

### Template 变更
1. **顶部筛选栏**：`class="card mb-16"` → `class="card-elevated mb-16"`
2. **右侧商品表格容器**：`class="product-main card"` → `class="product-main card-elevated"`
3. **商品图片圆角**：`border-radius: 4px` → `border-radius: var(--ds-radius-sm, 8px)`
4. **品牌文字颜色**：`color: #999` → `color: var(--ds-text-muted, #999)`
5. **预览图片圆角**：`border-radius: 4px` → `border-radius: var(--ds-radius-sm, 8px)`

### CSS 变更 (`<style scoped>`)
6. **`.category-side`**：新增 `background: var(--ds-surface, #fff)`
7. **`.category-side h4`**：新增规则，`color: var(--ds-navy, #1a1a2e)`、`font-weight: 600`
8. **表格样式注入**：新增 `.product-main :deep(.el-table)` 设置 `--el-table-header-bg-color` 和 `--el-table-row-hover-bg-color` 为 `var(--ds-bg, #f5f3ef)`
9. **`.category-node`**：新增 `border-radius: 4px`
10. **`.category-node:hover`**：新增 `background-color: var(--ds-bg, #f5f3ef)`
11. **`.category-node .el-icon`**：`color: #bbb` → `color: var(--ds-text-muted, #999)`
12. **`.img-placeholder`**：`background: #f5f5f5` → `var(--ds-bg, #f5f3ef)`；`color: #ccc` → `var(--ds-text-muted, #999)`；`border-radius: 4px` → `var(--ds-radius-sm, 8px)`
13. **`.sku-row`**：`background: #fafafa` → `var(--ds-bg, #f5f3ef)`；`border: 1px solid #f0f0f0` → `var(--ds-border-light, #f0f0f0)`
14. **对话框标题**：新增 `:deep(.el-dialog__title)` 设置 `color: var(--ds-navy, #1a1a2e)`
15. **表单标签**：新增 `:deep(.el-form-item__label)` 设置 `color: var(--ds-text, #333)`

## 自我审查结果

| Brief 要求 | 状态 |
|------------|------|
| 顶部筛选栏卡片改为 card-elevated | 已完成 |
| 左侧分类树容器保留 card 但添加全局变量风格 | 已完成 |
| 右侧商品表格容器改为 card-elevated | 已完成 |
| 分类树卡片背景 var(--ds-surface) | 已完成 |
| 分类树标题 h4 使用 --ds-navy、font-weight 600 | 已完成 |
| el-tree 节点 hover 态使用 var(--ds-bg) | 已完成 |
| el-tree 节点图标颜色 #bbb → var(--ds-text-muted) | 已完成 |
| el-table stripe 保留 | 已完成（未改动） |
| 表头背景 :deep() 注入 --ds-bg | 已完成 |
| 行 hover 背景 :deep() 注入 --ds-bg | 已完成 |
| 商品图片 border-radius 4px → var(--ds-radius-sm) | 已完成 |
| 品牌文字色 #999 → var(--ds-text-muted) | 已完成 |
| 状态标签保留 el-tag effect="dark" 不变 | 已完成（未改动） |
| el-dialog 标题使用 --ds-navy 色 | 已完成 |
| el-form-item label 色使用 --ds-text | 已完成 |
| SKU 行 background #fafafa → var(--ds-bg) | 已完成 |
| SKU 行 border #f0f0f0 → var(--ds-border-light) | 已完成 |
| .img-placeholder background/color 替换 | 已完成 |
| 不修改 script setup 中的 JS 逻辑 | 已完成 |
| 所有 --ds-* 变量使用带 fallback 值 | 已完成 |

## npm run build 结果
**通过**（7.76s，0 错误）。

## Git commit hash
