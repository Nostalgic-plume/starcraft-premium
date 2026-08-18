<template>
  <div class="page fade-in">
    <!-- 工具条：搜索 + 状态筛选 + 新增 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <n-input
          v-model:value="keyword"
          placeholder="搜索商品名称"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <n-button type="primary" @click="handleSearch">搜索</n-button>
        <n-select
          v-model:value="statusFilter"
          :options="productStatusOptions"
          placeholder="商品状态"
          clearable
          style="width: 140px"
          @update:value="handleFilterChange"
        />
      </div>
      <n-button type="primary" @click="openAdd">
        新增商品
      </n-button>
    </div>

    <!-- 商品表格 -->
    <div class="table-card">
      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :row-key="rowKey"
        :scroll-x="980"
      />
      <div v-if="total > 0" class="pagination-wrap">
        <n-pagination
          v-model:page="page"
          :page-size="pageSize"
          :item-count="total"
          :page-slot="5"
          @update:page="loadProducts"
        />
      </div>
    </div>

    <!-- 新增 / 编辑抽屉 -->
    <n-drawer v-model:show="showDrawer" :width="600" placement="right">
      <n-drawer-content :title="isEdit ? '编辑商品' : '新增商品'" closable>
        <n-alert v-if="isEdit" type="warning" :bordered="false" class="form-alert">
          后端暂未开放商品信息编辑接口，名称 / 分类 / 价格 / 库存仅作展示；可调整状态后保存。
        </n-alert>

        <n-form :label-placement="'left'" label-width="96" :show-feedback="false">
          <n-form-item label="商品名称">
            <n-input v-model:value="form.name" :disabled="isEdit" placeholder="请输入商品名称" />
          </n-form-item>
          <n-form-item label="商品分类">
            <n-select
              v-model:value="form.categoryId"
              :disabled="isEdit"
              :options="categoryOptions"
              placeholder="请选择叶子分类（如「手机」而非「手机数码」）"
            />
          </n-form-item>
          <n-form-item label="主图 URL">
            <n-input v-model:value="form.mainImage" :disabled="isEdit" placeholder="请输入商品主图 URL" />
          </n-form-item>
          <n-form-item label="主图预览">
            <img
              v-if="form.mainImage"
              :src="form.mainImage"
              class="preview-img"
              alt="主图预览"
              @error="onPreviewError"
            />
            <span v-else class="cell-muted">暂无图片</span>
          </n-form-item>
          <n-form-item label="状态">
            <div class="status-form-row">
              <n-select
                v-model:value="form.status"
                :disabled="!isEdit"
                :options="productStatusOptions"
                style="width: 140px"
              />
              <span v-if="!isEdit" class="form-hint">新增商品默认在售</span>
            </div>
          </n-form-item>
          <n-form-item label="SKU 规格">
            <div class="sku-list">
              <div v-for="(sku, idx) in form.skuList" :key="idx" class="sku-row">
                <n-input
                  v-model:value="sku.name"
                  :disabled="isEdit"
                  placeholder="规格名"
                  size="small"
                  style="width: 130px"
                />
                <n-input-number
                  v-model:value="sku.price"
                  :disabled="isEdit"
                  :min="0"
                  placeholder="价格"
                  size="small"
                  style="width: 100px"
                />
                <n-input-number
                  v-model:value="sku.originalPrice"
                  :disabled="isEdit"
                  :min="0"
                  placeholder="原价"
                  size="small"
                  style="width: 96px"
                />
                <n-input-number
                  v-model:value="sku.stock"
                  :disabled="isEdit"
                  :min="0"
                  placeholder="库存"
                  size="small"
                  style="width: 96px"
                />
                <n-button
                  v-if="!isEdit"
                  size="tiny"
                  quaternary
                  type="error"
                  :disabled="form.skuList.length <= 1"
                  @click="removeSku(idx)"
                >
                  删除
                </n-button>
              </div>
              <n-button v-if="!isEdit" size="small" secondary @click="addSku">添加规格</n-button>
            </div>
          </n-form-item>
        </n-form>

        <template #footer>
          <div class="drawer-footer">
            <n-button @click="showDrawer = false">取消</n-button>
            <n-button type="primary" :loading="submitting" @click="handleSubmit">
              {{ isEdit ? '保存状态' : '确定新增' }}
            </n-button>
          </div>
        </template>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NDataTable, NDrawer, NDrawerContent, NForm, NFormItem,
  NInput, NInputNumber, NPagination, NSelect, useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { productApi } from '@/api/product'
import { get } from '@/api/request'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'
import type { ProductPageQuery, ProductVO } from '@/api/types'

const message = useMessage()
const dialog = useDialog()

/* ==================== 数据状态 ==================== */

const list = ref<ProductVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

/** 搜索关键词（商品名） */
const keyword = ref('')
/** 状态筛选：null=全部，0=待审核，1=在售，2=下架 */
const statusFilter = ref<number | null>(null)

/** 商品状态选项（后端约定：0=待审核 1=在售 2=下架） */
const productStatusOptions = [
  { label: '待审核', value: 0 },
  { label: '在售', value: 1 },
  { label: '下架', value: 2 }
]

/* ==================== 分类（新增表单用） ==================== */

interface CategoryNode {
  id: string | number
  name: string
  parentId: string | number | null
}

const categories = ref<CategoryNode[]>([])

/** 叶子分类（parentId>0）才允许作为商品分类 */
const categoryOptions = computed(() =>
  categories.value
    .filter((c) => Number(c.parentId) > 0)
    .map((c) => ({ label: c.name, value: c.id }))
)

async function loadCategories() {
  try {
    const res = await get<CategoryNode[]>('/category/list')
    categories.value = res.data || []
  } catch (e) {
    console.error('加载分类失败:', e)
    categories.value = []
  }
}

/* ==================== 新增 / 编辑表单 ==================== */

interface SkuFormItem {
  name: string
  price: number
  originalPrice: number
  stock: number
}

interface ProductFormModel {
  id: string | null
  name: string
  description: string
  brand: string
  categoryId: string | number | null
  mainImage: string
  status: number
  skuList: SkuFormItem[]
}

const showDrawer = ref(false)
const isEdit = ref(false)
const submitting = ref(false)

const form = ref<ProductFormModel>({
  id: null,
  name: '',
  description: '',
  brand: '',
  categoryId: null,
  mainImage: '',
  status: 1,
  skuList: [{ name: '标准版', price: 0, originalPrice: 0, stock: 0 }]
})

/** 无图占位（浅灰底 SVG，data URI 内联，避免外部依赖） */
const IMG_PLACEHOLDER =
  "data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='48' height='48'%3E%3Crect width='48' height='48' fill='%23EEF0F4'/%3E%3C/svg%3E"

function onPreviewError(e: Event) {
  ;(e.target as HTMLImageElement).src = IMG_PLACEHOLDER
}

function openAdd() {
  isEdit.value = false
  form.value = {
    id: null,
    name: '',
    description: '',
    brand: '',
    categoryId: null,
    mainImage: '',
    status: 1,
    skuList: [{ name: '标准版', price: 0, originalPrice: 0, stock: 0 }]
  }
  showDrawer.value = true
}

function openEdit(row: ProductVO) {
  isEdit.value = true
  const skus = row.skuList || []
  form.value = {
    id: row.id,
    name: row.name || '',
    description: row.description || '',
    brand: row.brand || '',
    categoryId: row.categoryId ?? null,
    mainImage: row.mainImage || '',
    status: row.status ?? 1,
    skuList:
      skus.length > 0
        ? skus.map((s) => ({
            name: s.name || '',
            price: Number(s.price) || 0,
            originalPrice: Number(s.originalPrice) || 0,
            stock: Number(s.stock) || 0
          }))
        : [
            {
              name: '标准版',
              price: Number(row.minPrice) || 0,
              originalPrice: 0,
              stock: Number(row.totalStock) || 0
            }
          ]
  }
  showDrawer.value = true
}

function addSku() {
  form.value.skuList.push({ name: '', price: 0, originalPrice: 0, stock: 0 })
}

function removeSku(idx: number) {
  form.value.skuList.splice(idx, 1)
}

async function handleSubmit() {
  // 编辑：仅状态走真实 updateStatus（后端无商品信息编辑接口）
  if (isEdit.value) {
    if (form.value.status === null || form.value.status === undefined) {
      message.warning('请选择商品状态')
      return
    }
    submitting.value = true
    try {
      await productApi.updateStatus(form.value.id as string, form.value.status)
      message.success('商品状态已更新')
      showDrawer.value = false
      loadProducts()
    } catch (e) {
      console.error('更新商品状态失败:', e)
      message.error('操作失败')
    } finally {
      submitting.value = false
    }
    return
  }

  // 新增：走真实 /product/add
  if (!form.value.name.trim()) {
    message.warning('请输入商品名称')
    return
  }
  if (form.value.categoryId === null || form.value.categoryId === undefined) {
    message.warning('请选择商品分类')
    return
  }
  if (form.value.skuList.length === 0 || form.value.skuList.some((s) => !s.name.trim())) {
    message.warning('请填写完整 SKU 规格信息')
    return
  }
  submitting.value = true
  try {
    await productApi.add({
      name: form.value.name.trim(),
      description: form.value.description,
      brand: form.value.brand,
      categoryId: form.value.categoryId,
      mainImage: form.value.mainImage,
      skuList: form.value.skuList.map((s) => ({
        name: s.name.trim(),
        price: Number(s.price) || 0,
        originalPrice: Number(s.originalPrice) || 0,
        stock: Number(s.stock) || 0
      }))
    })
    message.success('新增商品成功')
    showDrawer.value = false
    loadProducts()
  } catch (e) {
    console.error('新增商品失败:', e)
    message.error('新增失败')
  } finally {
    submitting.value = false
  }
}

/* ==================== 表格 ==================== */

function rowKey(row: ProductVO) {
  return row.id
}

/** 商品状态 → StatusTag 语义色：1在售蓝 / 0待审核灰 / 2下架灰红 */
function productStatusType(status: number): StatusType {
  if (status === 1) return 'on-sale'
  if (status === 2) return 'off-shelf'
  return 'disabled'
}

function formatPriceRange(row: ProductVO): string {
  const min = Number(row.minPrice) || 0
  const max = Number(row.maxPrice) || 0
  return min === max ? `¥${min.toFixed(2)}` : `¥${min.toFixed(2)} ~ ¥${max.toFixed(2)}`
}

function renderProductCell(row: ProductVO) {
  return h('div', { class: 'product-cell' }, [
    h('img', {
      class: 'product-thumb',
      src: row.mainImage || IMG_PLACEHOLDER,
      alt: row.name,
      onError: (e: Event) => {
        ;(e.target as HTMLImageElement).src = IMG_PLACEHOLDER
      }
    }),
    h('div', { class: 'product-meta' }, [
      h('div', { class: 'product-name' }, row.name),
      row.brand ? h('div', { class: 'product-brand' }, row.brand) : null
    ])
  ])
}

function renderActions(row: ProductVO) {
  return h('div', { class: 'action-cell' }, [
    h(
      NButton,
      { size: 'small', quaternary: true, type: 'primary', onClick: () => openEdit(row) },
      { default: () => '编辑' }
    ),
    row.status === 1
      ? h(
          NButton,
          { size: 'small', quaternary: true, type: 'warning', onClick: () => toggleStatus(row, 2) },
          { default: () => '下架' }
        )
      : h(
          NButton,
          { size: 'small', quaternary: true, type: 'success', onClick: () => toggleStatus(row, 1) },
          { default: () => '上架' }
        )
  ])
}

const columns: DataTableColumns<ProductVO> = [
  { title: '商品', key: 'name', minWidth: 240, render: renderProductCell },
  {
    title: '分类',
    key: 'categoryName',
    width: 120,
    render: (row) => h('span', { class: 'cell-muted' }, row.categoryName || '-')
  },
  {
    title: '价格',
    key: 'price',
    width: 160,
    render: (row) => h('span', { class: 'cell-price' }, formatPriceRange(row))
  },
  {
    title: '库存',
    key: 'totalStock',
    width: 90,
    align: 'center',
    render: (row) => h('span', {}, String(Number(row.totalStock) || 0))
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    align: 'center',
    render: (row) =>
      h(StatusTag, {
        type: productStatusType(row.status),
        label: row.status === 1 ? '在售' : row.status === 2 ? '下架' : '待审核'
      })
  },
  { title: '操作', key: 'actions', width: 150, align: 'center', render: renderActions }
]

/* ==================== 操作 ==================== */

async function loadProducts() {
  loading.value = true
  try {
    const params: ProductPageQuery = { page: page.value, pageSize: pageSize.value }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (statusFilter.value !== null) params.status = statusFilter.value
    const res = await productApi.page(params)
    list.value = res.data?.list || []
    total.value = Number(res.data?.total) || 0
  } catch (e) {
    console.error('加载商品列表失败:', e)
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadProducts()
}

function handleFilterChange() {
  page.value = 1
  loadProducts()
}

/** 上架 / 下架（走真实 /product/status/{id}） */
function toggleStatus(row: ProductVO, newStatus: number) {
  const action = newStatus === 1 ? '上架' : '下架'
  dialog.warning({
    title: '确认操作',
    content: `确定${action}商品「${row.name}」吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await productApi.updateStatus(row.id, newStatus)
        message.success(`已${action}`)
        loadProducts()
      } catch (e) {
        console.error('更新商品状态失败:', e)
        message.error('操作失败')
      }
    }
  })
}

onMounted(async () => {
  await loadCategories()
  await loadProducts()
})
</script>

<style scoped>
.page {
  padding: 20px;
}

/* ====== 工具条 ====== */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 14px 16px;
  margin-bottom: 16px;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

/* ====== 表格卡 ====== */
.table-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px;
}
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* ====== 单元格 ====== */
.cell-price {
  color: var(--ds-price, #2d7ff9);
  font-weight: 600;
  font-family: var(--ds-mono, Consolas, monospace);
}
.cell-muted {
  color: var(--ds-text-3, #98a1af);
}
.product-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.product-thumb {
  width: 48px;
  height: 48px;
  border-radius: var(--ds-r-sm, 9px);
  object-fit: cover;
  background: var(--ds-bg-2, #eef0f4);
  flex-shrink: 0;
}
.product-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--ds-text, #1e2632);
}
.product-brand {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 2px;
}
.action-cell {
  display: inline-flex;
  gap: 4px;
}

/* ====== 抽屉表单 ====== */
.form-alert {
  margin-bottom: 14px;
}
.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.status-form-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.form-hint {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.preview-img {
  width: 96px;
  height: 96px;
  object-fit: cover;
  border-radius: var(--ds-r-sm, 9px);
  background: var(--ds-bg-2, #eef0f4);
}
.sku-list {
  width: 100%;
}
.sku-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
