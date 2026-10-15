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
        <n-select
          v-model:value="statusFilter"
          :options="productStatusOptions"
          placeholder="商品状态"
          clearable
          style="width: 140px"
          @update:value="handleFilterChange"
        />
        <n-button type="primary" @click="handleSearch">搜索</n-button>
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
        :scroll-x="1000"
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
        <n-form :label-placement="'left'" label-width="96" :show-feedback="false">
          <n-form-item label="商品名称">
            <n-input v-model:value="form.name" placeholder="请输入商品名称" />
          </n-form-item>
          <n-form-item label="商品分类">
            <n-select
              v-model:value="form.categoryId"
              :options="categoryOptions"
              placeholder="请选择叶子分类（如「手机」而非「手机数码」）"
            />
          </n-form-item>
          <n-form-item label="商品品牌">
            <n-select
              v-model:value="form.brandId"
              :options="brandOptions"
              clearable
              filterable
              placeholder="请选择品牌（品牌参与 RAG 语义检索，建议填写）"
            />
          </n-form-item>
          <n-form-item label="主图">
            <div class="upload-row">
              <n-input v-model:value="form.mainImage" placeholder="粘贴图片 URL，或点右侧上传" />
              <n-upload
                :show-file-list="false"
                :custom-request="uploadMainImage"
                accept="image/*"
              >
                <n-button size="small" secondary :loading="uploading">上传</n-button>
              </n-upload>
            </div>
          </n-form-item>
          <n-form-item label="商品描述">
            <n-input
              v-model:value="form.description"
              type="textarea"
              :rows="2"
              placeholder="一句话卖点，展示在商品列表与详情页顶部"
            />
          </n-form-item>
          <n-form-item label="轮播图">
            <div class="upload-col">
              <n-input
                v-model:value="form.imagesText"
                type="textarea"
                :rows="3"
                placeholder="每行一个图片 URL（第一张建议与主图一致），留空则不显示轮播"
              />
              <n-upload
                :show-file-list="false"
                :custom-request="uploadCarouselImage"
                accept="image/*"
                multiple
              >
                <n-button size="small" secondary :loading="uploading">上传轮播图</n-button>
              </n-upload>
            </div>
          </n-form-item>
          <n-form-item label="商品详情">
            <n-input
              v-model:value="form.detail"
              type="textarea"
              :rows="5"
              placeholder="支持 HTML 富文本，展示在详情页「商品详情」区块，留空则显示「暂无详情」"
            />
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
              <span v-if="!isEdit" class="form-hint">新增后进入待审核，管理员通过后上架</span>
            </div>
          </n-form-item>
          <n-form-item label="SKU 规格">
            <div class="sku-list">
              <div v-for="(sku, idx) in form.skuList" :key="sku.id ?? idx" class="sku-row">
                <n-input
                  v-model:value="sku.name"
                  placeholder="规格名"
                  size="small"
                  style="width: 130px"
                />
                <n-input-number
                  v-model:value="sku.price"
                  :min="0"
                  placeholder="价格"
                  size="small"
                  style="width: 100px"
                />
                <n-input-number
                  v-model:value="sku.originalPrice"
                  :min="0"
                  placeholder="原价"
                  size="small"
                  style="width: 96px"
                />
                <n-input-number
                  v-model:value="sku.stock"
                  :min="0"
                  placeholder="库存"
                  size="small"
                  style="width: 96px"
                />
                <n-button
                  size="tiny"
                  quaternary
                  type="error"
                  :disabled="form.skuList.length <= 1"
                  @click="removeSku(idx)"
                >
                  删除
                </n-button>
              </div>
              <n-button size="small" secondary @click="addSku">添加规格</n-button>
            </div>
          </n-form-item>
        </n-form>

        <template #footer>
          <div class="drawer-footer">
            <n-button @click="showDrawer = false">取消</n-button>
            <n-button type="primary" :loading="submitting" @click="handleSubmit">
              {{ isEdit ? '保存修改' : '确定新增' }}
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
  NButton, NDataTable, NDrawer, NDrawerContent, NForm, NFormItem,
  NInput, NInputNumber, NPagination, NSelect, NUpload, useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns, UploadCustomRequestOptions } from 'naive-ui'
import { merchantApi } from '@/api/merchant'
import { get } from '@/api/request'
import StatusTag, { type StatusType } from '@/components/StatusTag.vue'
import type { ProductVO } from '@/api/types'

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

/**
 * 商品状态选项（后端约定：0=待审核 1=在售 2=下架）
 * 「在售」对商家置灰：上架须经管理员审核，商家只能送审（0）或下架（2）。
 * 后端 Service 层有同样的拦截，此处仅为体验优化，不构成防护。
 */
const productStatusOptions = [
  { label: '待审核', value: 0 },
  { label: '在售（需管理员审核）', value: 1, disabled: true },
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

/* ==================== 品牌（新增表单用） ==================== */

interface BrandNode {
  id: string | number
  name: string
}

const brands = ref<BrandNode[]>([])

const brandOptions = computed(() => brands.value.map((b) => ({ label: b.name, value: b.id })))

async function loadBrands() {
  try {
    const res = await get<BrandNode[]>('/brand/list')
    brands.value = res.data || []
  } catch (e) {
    console.error('加载品牌失败:', e)
    brands.value = []
  }
}

/* ==================== 新增 / 编辑表单 ==================== */

interface SkuFormItem {
  /** SKU ID：编辑时保留（标识要更新的已有 SKU），新增行为空 */
  id?: string
  name: string
  price: number
  originalPrice: number
  stock: number
}

interface ProductFormModel {
  id: string | null
  name: string
  description: string
  categoryId: string | number | null
  brandId: string | number | null
  mainImage: string
  /** 轮播图：表单以多行文本承载，提交时按行拆成数组 */
  imagesText: string
  detail: string
  status: number
  skuList: SkuFormItem[]
}

const showDrawer = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
/** 编辑前的原始状态：保存时若状态被改动，需额外调一次状态更新接口（状态与商品信息是两条独立接口） */
const originalStatus = ref<number | null>(null)

/** 新增表单的空白初始值（编辑时由 openEdit 按行回填） */
function emptyForm(): ProductFormModel {
  return {
    id: null,
    name: '',
    description: '',
    categoryId: null,
    brandId: null,
    mainImage: '',
    imagesText: '',
    detail: '',
    status: 1,
    skuList: [{ name: '标准版', price: 0, originalPrice: 0, stock: 0 }]
  }
}

const form = ref<ProductFormModel>(emptyForm())

/** 无图占位（浅灰底 SVG，data URI 内联，避免外部依赖） */
const IMG_PLACEHOLDER =
  "data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='48' height='48'%3E%3Crect width='48' height='48' fill='%23EEF0F4'/%3E%3C/svg%3E"

function onPreviewError(e: Event) {
  ;(e.target as HTMLImageElement).src = IMG_PLACEHOLDER
}

function openAdd() {
  isEdit.value = false
  form.value = emptyForm()
  showDrawer.value = true
}

function openEdit(row: ProductVO) {
  isEdit.value = true
  originalStatus.value = row.status ?? 1
  const skus = row.skuList || []
  form.value = {
    id: row.id,
    name: row.name || '',
    description: row.description || '',
    categoryId: row.categoryId ?? null,
    brandId: row.brandId ?? null,
    mainImage: row.mainImage || '',
    imagesText: (row.images || []).join('\n'),
    detail: row.detail || '',
    status: row.status ?? 1,
    skuList:
      skus.length > 0
        ? skus.map((s) => ({
            id: s.id,
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

/* ==================== 图片上传 ==================== */

/** 上传中标记（禁用按钮避免重复点击） */
const uploading = ref(false)

/**
 * 主图上传成功后直接覆盖 form.mainImage。
 * 「上传」与「手填 URL」并存：mock 数据全是外链，只留上传会导致编辑既有商品时原图丢失。
 */
async function uploadMainImage(options: UploadCustomRequestOptions) {
  const raw = options.file.file
  if (!raw) return
  uploading.value = true
  try {
    const res = await merchantApi.uploadImage(raw)
    form.value.mainImage = res.data
    options.onFinish()
    message.success('主图上传成功')
  } catch (e) {
    console.error('主图上传失败:', e)
    options.onError()
    message.error('图片上传失败')
  } finally {
    uploading.value = false
  }
}

/** 轮播图上传成功后追加到 imagesText 末尾（该字段是每行一个 URL 的文本） */
async function uploadCarouselImage(options: UploadCustomRequestOptions) {
  const raw = options.file.file
  if (!raw) return
  uploading.value = true
  try {
    const res = await merchantApi.uploadImage(raw)
    const lines = form.value.imagesText
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean)
    lines.push(res.data)
    form.value.imagesText = lines.join('\n')
    options.onFinish()
    message.success('轮播图上传成功')
  } catch (e) {
    console.error('轮播图上传失败:', e)
    options.onError()
    message.error('图片上传失败')
  } finally {
    uploading.value = false
  }
}

/**
 * 组装提交给后端的商品载荷（新增 / 编辑共用）。
 * 编辑时 skuList 会带上 id，后端据此做「增 / 改 / 删」diff；
 * 新增时 id 为 undefined，JSON 序列化时自动省略。
 */
function buildProductPayload() {
  return {
    name: form.value.name.trim(),
    description: form.value.description,
    categoryId: form.value.categoryId as string | number,
    brandId: form.value.brandId ?? undefined,
    mainImage: form.value.mainImage,
    images: form.value.imagesText
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean),
    detail: form.value.detail,
    skuList: form.value.skuList.map((s) => ({
      id: s.id,
      name: s.name.trim(),
      price: Number(s.price) || 0,
      originalPrice: Number(s.originalPrice) || 0,
      stock: Number(s.stock) || 0
    }))
  }
}

async function handleSubmit() {
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
    if (isEdit.value) {
      const id = form.value.id as string
      await merchantApi.productUpdate(id, buildProductPayload())
      // 状态与商品信息是两条独立接口，状态有变化时补一次调用
      if (form.value.status !== originalStatus.value) {
        await merchantApi.productUpdateStatus(id, form.value.status)
      }
      message.success('商品信息已更新')
    } else {
      await merchantApi.productAdd(buildProductPayload())
      message.success('新增成功，等待管理员审核')
    }
    showDrawer.value = false
    loadProducts()
  } catch (e) {
    console.error(isEdit.value ? '更新商品失败:' : '新增商品失败:', e)
    message.error(isEdit.value ? '更新失败' : '新增失败')
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

/** 商品图片列：只渲染缩略图（表格内样式见底部 :deep 规则） */
function renderProductThumb(row: ProductVO) {
  return h('img', {
    class: 'product-thumb',
    src: row.mainImage || IMG_PLACEHOLDER,
    alt: row.name,
    onError: (e: Event) => {
      ;(e.target as HTMLImageElement).src = IMG_PLACEHOLDER
    }
  })
}

/** 商品名列：名称 + 品牌 */
function renderProductName(row: ProductVO) {
  return h('div', { class: 'product-meta' }, [
    h('div', { class: 'product-name' }, row.name),
    row.brandName ? h('div', { class: 'product-brand' }, row.brandName) : null
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
  { title: '商品图片', key: 'mainImage', width: 140, align: 'center', render: renderProductThumb },
  { title: '商品名', key: 'name', minWidth: 220, render: renderProductName },
  {
    title: '分类',
    key: 'categoryName',
    width: 110,
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
    width: 80,
    align: 'center',
    render: (row) => h('span', {}, String(Number(row.totalStock) || 0))
  },
  {
    title: '销量',
    key: 'soldCount',
    width: 80,
    align: 'center',
    render: (row) => h('span', { class: 'cell-muted' }, String(Number(row.soldCount) || 0))
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
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
    const kw = keyword.value.trim()
    // keyword 与 status 均交由后端过滤：前端过滤会与分页冲突（只能筛当前页）
    const res = await merchantApi.productPage({
      page: page.value,
      pageSize: pageSize.value,
      ...(kw ? { keyword: kw } : {}),
      ...(statusFilter.value !== null ? { status: statusFilter.value } : {})
    })
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

/** 上架 / 下架（走真实 /merchant/product/status/{id}） */
function toggleStatus(row: ProductVO, newStatus: number) {
  const action = newStatus === 1 ? '上架' : '下架'
  dialog.warning({
    title: '确认操作',
    content: `确定${action}商品「${row.name}」吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await merchantApi.productUpdateStatus(row.id, newStatus)
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
  await loadBrands()
  await loadProducts()
})
</script>

<style scoped>
.page {
  padding: 20px;
}

.fade-in {
  animation: fadeIn 0.3s ease;
}
@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
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

/* ====== 状态筛选提示 ====== */
.filter-tip {
  margin-bottom: 14px;
}

/* ====== 表格卡 ====== */
.table-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  padding: 16px;
}
/* ====== 表格内部边框（Naive UI 默认只有外框 + 行分隔线）====== */
.table-card :deep(.n-data-table-th),
.table-card :deep(.n-data-table-td) {
  border-right: 1px solid var(--ds-line, #e7e9ee);
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.table-card :deep(.n-data-table-th:last-child),
.table-card :deep(.n-data-table-td:last-child) {
  border-right: none;
}
.table-card :deep(.n-data-table-tr:last-child .n-data-table-td) {
  border-bottom: none;
}
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* ====== 单元格 ====== */
.cell-muted {
  color: var(--ds-text-3, #98a1af);
}
/* 渲染函数 h() 生成的节点不带 scoped 属性，表格内样式必须走 :deep() 才会生效 */
.table-card :deep(.cell-price) {
  color: var(--ds-price, #2d7ff9);
  font-weight: 600;
  font-family: var(--ds-mono, Consolas, monospace);
  white-space: nowrap;
}
.table-card :deep(.cell-muted) {
  color: var(--ds-text-3, #98a1af);
}
.table-card :deep(.product-thumb) {
  display: block;
  width: 100px;
  height: 100px;
  margin: 0 auto;
  border-radius: var(--ds-r-sm, 9px);
  object-fit: cover;
  background: var(--ds-bg-2, #eef0f4);
}
.table-card :deep(.product-name) {
  font-size: 14px;
  font-weight: 500;
  color: var(--ds-text, #1e2632);
}
.table-card :deep(.product-brand) {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-top: 2px;
}
.table-card :deep(.action-cell) {
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
  width: 100px;
  height: 100px;
  object-fit: cover;
  border-radius: var(--ds-r-sm, 9px);
  background: var(--ds-bg-2, #eef0f4);
}
/* 主图：URL 输入框 + 上传按钮并排 */
.upload-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
/* 轮播图：文本框在上、上传按钮在下 */
.upload-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
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
