<template>
  <div class="page-container">
    <!-- 顶部栏：筛选 + 批量操作 + 新增 -->
    <div class="card-elevated mb-16">
      <div class="flex-between" style="flex-wrap: wrap; gap: 12px">
        <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
          <el-input
            v-model="keyword"
            placeholder="搜索商品名称"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-select v-model="selectedCategoryId" placeholder="商品分类" clearable style="width: 140px" @change="handleFilterChange">
            <el-option v-for="c in flatCategories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-select v-model="statusFilter" placeholder="商品状态" clearable style="width: 120px" @change="handleFilterChange">
            <el-option label="上架" :value="1" />
            <el-option label="下架" :value="2" />
            <el-option label="售罄" :value="3" />
          </el-select>
        </div>
        <div style="display: flex; align-items: center; gap: 8px">
          <el-button
            type="success"
            :disabled="selectedIds.length === 0"
            @click="batchUpdateStatus(1)"
          >
            批量上架
          </el-button>
          <el-button
            type="warning"
            :disabled="selectedIds.length === 0"
            @click="batchUpdateStatus(2)"
          >
            批量下架
          </el-button>
          <el-button type="primary" @click="openAddDialog">
            <el-icon><Plus /></el-icon>新增商品
          </el-button>
        </div>
      </div>
    </div>

    <!-- 主体布局：左侧分类树 + 右侧商品表格 -->
    <div class="product-layout">
      <!-- 左侧分类 -->
      <div class="category-side card">
        <h4 style="margin-bottom: 12px; font-size: 15px">商品分类</h4>
        <el-input
          v-model="categoryKeyword"
          placeholder="搜索分类"
          size="small"
          clearable
          class="mb-16"
        />
        <div style="max-height: 500px; overflow-y: auto">
          <el-tree
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            :filter-node-method="filterCategory"
            ref="categoryTreeRef"
            highlight-current
            @node-click="handleCategoryClick"
          >
            <template #default="{ node, data }">
              <span class="category-node" :class="{ active: selectedCategoryId === data.id }">
                <el-icon v-if="data.children && data.children.length > 0"><FolderOpened /></el-icon>
                <el-icon v-else><CollectionTag /></el-icon>
                <span>{{ node.label }}</span>
              </span>
            </template>
          </el-tree>
        </div>
        <el-button
          v-if="selectedCategoryId"
          text
          type="primary"
          size="small"
          class="mt-16"
          @click="clearCategoryFilter"
        >
          清除筛选
        </el-button>
      </div>

      <!-- 右侧商品表格 -->
      <div class="product-main card-elevated" style="flex: 1; overflow: hidden">
        <el-table
          :data="list"
          v-loading="loading"
          stripe
          style="width: 100%"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="45" />
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="商品" min-width="200">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 10px">
                <el-image
                  :src="row.mainImage"
                  style="width: 48px; height: 48px; border-radius: var(--ds-radius-sm, 8px); flex-shrink: 0"
                  fit="cover"
                >
                  <template #error>
                    <div class="img-placeholder">无图</div>
                  </template>
                </el-image>
                <div>
                  <div style="font-weight: 500; font-size: 14px">{{ row.name }}</div>
                  <div v-if="row.brand" style="font-size: 12px; color: var(--ds-text-muted, #999); margin-top: 2px">{{ row.brand }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="categoryName" label="分类" width="100" show-overflow-tooltip />
          <el-table-column label="价格" width="130">
            <template #default="{ row }">
              ¥{{ row.minPrice || 0 }} ~ ¥{{ row.maxPrice || 0 }}
            </template>
          </el-table-column>
          <el-table-column prop="totalStock" label="库存" width="80" align="center" />
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="productStatusTypeMap[row.status] || 'info'" size="small" effect="dark">
                {{ productStatusMap[row.status] || '未知' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 1"
                text
                type="warning"
                size="small"
                @click="toggleStatus(row, 2)"
              >
                下架
              </el-button>
              <el-button
                v-else
                text
                type="success"
                size="small"
                @click="toggleStatus(row, 1)"
              >
                上架
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-if="!loading && list.length === 0" description="暂无商品" />

        <!-- 分页 -->
        <div v-if="total > 0" class="flex-center mt-16">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            @current-change="loadProducts"
          />
        </div>
      </div>
    </div>

    <!-- 新增商品对话框 -->
    <el-dialog v-model="showAdd" title="新增商品" width="680px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px" v-loading="submitting">
        <el-form-item label="商品名称" required>
          <el-input v-model="form.name" placeholder="请输入商品名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请输入商品描述" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="品牌">
              <el-input v-model="form.brand" placeholder="请输入品牌" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分类" required>
              <el-select v-model="form.categoryId" placeholder="请选择分类（如「手机」而非「手机数码」）" style="width: 100%">
                <el-option
                  v-for="cat in leafCategories"
                  :key="cat.id"
                  :label="cat.name"
                  :value="cat.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="主图URL">
          <el-input v-model="form.mainImage" placeholder="请输入商品主图URL" />
        </el-form-item>
        <el-form-item v-if="form.mainImage" label="预览">
          <el-image :src="form.mainImage" style="width: 100px; height: 100px; border-radius: var(--ds-radius-sm, 8px)" fit="cover" />
        </el-form-item>

        <el-divider>SKU 规格</el-divider>
        <div v-for="(sku, idx) in form.skuList" :key="idx" class="sku-row">
          <el-row :gutter="8" align="middle">
            <el-col :span="7">
              <el-input v-model="sku.name" placeholder="规格名" size="small" />
            </el-col>
            <el-col :span="6">
              <el-input v-model.number="sku.price" placeholder="价格" type="number" size="small" />
            </el-col>
            <el-col :span="5">
              <el-input v-model.number="sku.originalPrice" placeholder="原价" type="number" size="small" />
            </el-col>
            <el-col :span="4">
              <el-input v-model.number="sku.stock" placeholder="库存" type="number" size="small" />
            </el-col>
            <el-col :span="2">
              <el-button
                text
                type="danger"
                size="small"
                :disabled="form.skuList.length <= 1"
                @click="form.skuList.splice(idx, 1)"
              >
                删除
              </el-button>
            </el-col>
          </el-row>
        </div>
        <el-button size="small" @click="addSku" class="mt-16">
          <el-icon><Plus /></el-icon>添加规格
        </el-button>
      </el-form>
      <template #footer>
        <el-button @click="showAdd = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleAdd">确定新增</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, FolderOpened, CollectionTag } from '@element-plus/icons-vue'
import { productApi } from '@/api/product'
import request from '@/api/request'

const route = useRoute()

// 商品列表
const list = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

// 筛选
const keyword = ref('')
const selectedCategoryId = ref(null)
const statusFilter = ref('')
const selectedIds = ref([])

// 分类
const categories = ref([])
const flatCategories = ref([])
// 只取二级分类用于新增表单，防止选一级分类导致 SQL 报错
const leafCategories = computed(() =>
  flatCategories.value.filter(c => c.parentId && c.parentId > 0)
)
const categoryTree = ref([])
const categoryKeyword = ref('')
const categoryTreeRef = ref(null)
const clickedCategoryType = ref('') // 'first' 或 'second'

// 新增商品
const showAdd = ref(false)
const submitting = ref(false)
const form = ref({
  name: '',
  description: '',
  brand: '',
  categoryId: null,
  mainImage: '',
  skuList: [{ name: '标准版', price: 0, originalPrice: 0, stock: 0 }]
})

// 商品状态映射
const productStatusMap = { 1: '上架', 2: '下架', 3: '售罄' }
const productStatusTypeMap = { 1: 'success', 2: 'warning', 3: 'danger' }

// 构建分类树
function buildCategoryTree(list) {
  const map = {}
  const tree = []
  list.forEach(item => {
    map[item.id] = { ...item, children: [] }
  })
  list.forEach(item => {
    if (item.parentId && map[item.parentId]) {
      map[item.parentId].children.push(map[item.id])
    } else {
      tree.push(map[item.id])
    }
  })
  return tree
}

// 加载分类
async function loadCategories() {
  try {
    const res = await request.get('/category/list')
    const data = (res && res.data) || res || []
    categories.value = data
    flatCategories.value = data
    categoryTree.value = buildCategoryTree(data)
  } catch (e) {
    console.error('加载分类失败:', e)
    categoryTree.value = []
  }
}

// 分类筛选
function filterCategory(value, data) {
  if (!value) return true
  return data.name.toLowerCase().includes(value.toLowerCase())
}

watch(categoryKeyword, (val) => {
  categoryTreeRef.value?.filter(val)
})

function handleCategoryClick(data) {
  selectedCategoryId.value = data.id
  // 判断分类层级：parentId=0 为一级，否则为二级
  clickedCategoryType.value = data.parentId == 0 || data.parentId === null ? 'first' : 'second'
  page.value = 1
  loadProducts()
}

function clearCategoryFilter() {
  selectedCategoryId.value = null
  clickedCategoryType.value = ''
  categoryTreeRef.value?.setCurrentKey(null)
  page.value = 1
  loadProducts()
}

// 表格选择
function handleSelectionChange(selection) {
  selectedIds.value = selection.map(item => item.id)
}

// 加载商品列表
async function loadProducts() {
  loading.value = true
  try {
    const params = { page: page.value, pageSize: pageSize.value }
    if (keyword.value) params.keyword = keyword.value
    if (selectedCategoryId.value) {
      if (clickedCategoryType.value === 'first') {
        params.firstCategoryId = selectedCategoryId.value
      } else {
        params.categoryId = selectedCategoryId.value
      }
    }
    if (statusFilter.value) params.status = statusFilter.value
    const res = await productApi.page(params)
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
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

// 新增商品
function openAddDialog() {
  form.value = {
    name: '',
    description: '',
    brand: '',
    categoryId: flatCategories.value.length > 0 ? flatCategories.value[0].id : null,
    mainImage: '',
    skuList: [{ name: '标准版', price: 0, originalPrice: 0, stock: 0 }]
  }
  showAdd.value = true
}

function addSku() {
  form.value.skuList.push({ name: '', price: 0, originalPrice: 0, stock: 0 })
}

async function handleAdd() {
  if (!form.value.name) {
    ElMessage.warning('请输入商品名称')
    return
  }
  if (!form.value.categoryId) {
    ElMessage.warning('请选择商品分类')
    return
  }
  const invalidSku = form.value.skuList.some(s => !s.name)
  if (invalidSku) {
    ElMessage.warning('请填写完整SKU规格信息')
    return
  }
  submitting.value = true
  try {
    await productApi.add({ ...form.value })
    ElMessage.success('新增商品成功')
    showAdd.value = false
    loadProducts()
  } catch (e) {
    console.error('新增商品失败:', e)
    ElMessage.error('新增失败')
  } finally {
    submitting.value = false
  }
}

// 切换状态
async function toggleStatus(row, newStatus) {
  const actionText = newStatus === 1 ? '上架' : '下架'
  try {
    await ElMessageBox.confirm(`确定${actionText}该商品吗？`, '确认操作', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'info'
    })
    await productApi.updateStatus(row.id, newStatus)
    ElMessage.success(`已${actionText}`)
    loadProducts()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(`操作失败`)
    }
  }
}

// 批量上架/下架
async function batchUpdateStatus(status) {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请先选择商品')
    return
  }
  const actionText = status === 1 ? '上架' : '下架'
  try {
    await ElMessageBox.confirm(
      `确定将选中的 ${selectedIds.value.length} 个商品${actionText}吗？`,
      '批量操作',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' }
    )
    for (const id of selectedIds.value) {
      await productApi.updateStatus(id, status)
    }
    ElMessage.success(`批量${actionText}成功`)
    selectedIds.value = []
    loadProducts()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(`批量${actionText}失败`)
    }
  }
}

onMounted(async () => {
  await loadCategories()
  await loadProducts()
  if (route.query.showAdd === '1') {
    openAddDialog()
  }
})
</script>

<style scoped>
.product-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.category-side {
  width: 210px;
  flex-shrink: 0;
  background: var(--ds-surface, #fff);
}

.category-side h4 {
  color: var(--ds-navy, #1a1a2e);
  font-weight: 600;
}

.product-main {
  flex: 1;
  min-width: 0;
}

/* el-table 表头 & 行 hover */
.product-main :deep(.el-table) {
  --el-table-header-bg-color: var(--ds-bg, #f5f3ef);
  --el-table-row-hover-bg-color: var(--ds-bg, #f5f3ef);
}

.category-node {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  padding: 3px 0;
  border-radius: var(--ds-radius-sm, 8px);
}

.category-node:hover {
  background-color: var(--ds-bg, #f5f3ef);
}

.category-node.active {
  color: var(--ds-navy, #1a1a2e);
  font-weight: 600;
}

.category-node .el-icon {
  font-size: 16px;
  color: var(--ds-text-muted, #999);
}

.category-node.active .el-icon {
  color: var(--ds-navy, #1a1a2e);
}

.img-placeholder {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ds-bg, #f5f3ef);
  color: var(--ds-text-muted, #999);
  font-size: 12px;
  border-radius: var(--ds-radius-sm, 8px);
}

.sku-row {
  margin-bottom: 10px;
  padding: 10px;
  background: var(--ds-bg, #f5f3ef);
  border-radius: 6px;
  border: 1px solid var(--ds-border-light, #f0f0f0);
}

/* el-dialog 标题 */
:deep(.el-dialog__title) {
  color: var(--ds-navy, #1a1a2e);
}

/* el-form 标签 */
:deep(.el-form-item__label) {
  color: var(--ds-text, #333);
}
</style>
