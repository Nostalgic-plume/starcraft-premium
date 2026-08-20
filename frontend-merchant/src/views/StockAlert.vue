<template>
  <div class="page fade-in">
    <!-- TODO(占位): 库存预警为前端占位，未接后端 API；低库存商品列表为静态示例。
         待后端提供本店低库存查询接口（/api/merchant/stock/alert，shop_id 后端注入）后替换为真实请求。 -->

    <!-- 页头 -->
    <div class="page-head">
      <div class="head-text">
        <h2 class="page-title">库存预警</h2>
        <p class="page-desc">本店低库存商品监控，低于安全阈值自动进入预警列表</p>
      </div>
    </div>

    <!-- 看板角标说明 -->
    <n-alert type="info" :bordered="false" class="alert-tip">
      预警逻辑：商品总库存 ≤ 安全阈值时进入本列表，「本店看板」导航角标同步显示待处理预警数量。
      当前为占位数据，接入接口后按本店 SKU 实时统计。
    </n-alert>

    <!-- 低库存商品列表（占位数据） -->
    <div class="table-card">
      <n-data-table :columns="columns" :data="list" :row-key="rowKey" :scroll-x="900" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { h, ref } from 'vue'
import { NAlert, NButton, NDataTable, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'

const message = useMessage()

/** 占位提示：补货等操作待后端接口接入后替换为真实逻辑 */
function todo() {
  message.info('占位功能：待后端接口接入')
}

/* ==================== 低库存商品列表（占位数据） ==================== */

interface StockAlertRow {
  id: string
  name: string
  skuName: string
  /** 当前总库存 */
  stock: number
  /** 安全阈值 */
  threshold: number
  /** 0严重（≤0.5×阈值） 1一般（≤阈值） */
  level: number
}

const list = ref<StockAlertRow[]>([
  { id: '1', name: '智能蓝牙耳机 Pro', skuName: '曜石黑', stock: 3, threshold: 20, level: 0 },
  { id: '2', name: '陶瓷保温杯 500ml', skuName: '奶油白', stock: 12, threshold: 20, level: 1 },
  { id: '3', name: '无线充电器', skuName: '标准版', stock: 8, threshold: 15, level: 1 }
])

function levelType(level: number): StatusType {
  return level === 0 ? 'failed' : 'pending-pay'
}

const levelTextMap: Record<number, string> = { 0: '严重缺货', 1: '预警' }

function rowKey(row: StockAlertRow) {
  return row.id
}

/* ==================== 表格列 ==================== */

const columns: DataTableColumns<StockAlertRow> = [
  {
    title: '商品',
    key: 'name',
    minWidth: 220,
    render: (row) =>
      h('div', { class: 'goods-cell' }, [
        h('div', { class: 'goods-name' }, row.name),
        row.skuName ? h('div', { class: 'goods-sub' }, `规格：${row.skuName}`) : null
      ])
  },
  {
    title: '当前库存',
    key: 'stock',
    width: 120,
    align: 'center',
    render: (row) => h('span', { class: 'cell-stock' }, String(row.stock))
  },
  {
    title: '安全阈值',
    key: 'threshold',
    width: 120,
    align: 'center',
    render: (row) => h('span', { class: 'cell-muted' }, String(row.threshold))
  },
  {
    title: '预警等级',
    key: 'level',
    width: 120,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: levelType(row.level),
        label: levelTextMap[row.level] || '未知'
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 120,
    align: 'center',
    render: () =>
      h(
        NButton,
        { size: 'small', quaternary: true, type: 'primary', onClick: todo },
        { default: () => '去补货' }
      )
  }
]
</script>

<style scoped>
.page {
  padding: 20px;
}

/* ====== 页头 ====== */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.head-text {
  min-width: 0;
}
.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  line-height: 1.3;
}
.page-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}

/* ====== 看板角标说明 ====== */
.alert-tip {
  margin-bottom: 16px;
}

/* ====== 表格卡 ====== */
.table-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px;
}

/* ====== 单元格 ====== */
.goods-cell {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.goods-name {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.goods-sub {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 2px;
}
.cell-stock {
  color: var(--ds-red, #e0404e);
  font-weight: 700;
  font-family: var(--ds-mono, Consolas, monospace);
}
.cell-muted {
  color: var(--ds-text-3, #98a1af);
}
</style>
