<template>
  <div class="page-container">
    <div class="flex-between mb-16">
      <h2>商品管理</h2>
      <el-button type="primary" @click="openAdd">
        <el-icon><Plus /></el-icon>新增商品
      </el-button>
    </div>

    <!-- 搜索栏 -->
    <div class="card-elevated mb-16">
      <div style="display: flex; gap: 12px; flex-wrap: wrap">
        <el-input v-model="keyword" placeholder="搜索商品名称" clearable style="width: 200px" @keyup.enter="handleSearch" />
        <el-select v-model="selectedCategoryId" placeholder="商品分类" clearable style="width: 140px" @change="handleSearch">
          <el-option v-for="c in subCategories" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
      </div>
    </div>

    <!-- 批量操作栏 -->
    <div class="batch-bar mb-16" v-if="selectedIds.length > 0">
      <span style="color: var(--ds-text-muted, #b2bec3); margin-right: 12px">已选 {{ selectedIds.length }} 项</span>
      <el-button size="small" type="success" plain @click="batchStatus(1)">批量上架</el-button>
      <el-button size="small" type="warning" plain @click="batchStatus(2)">批量下架</el-button>
      <el-button size="small" text @click="clearSelection">取消选择</el-button>
    </div>

    <!-- 表格 -->
    <div class="card-elevated">
      <el-table :data="list" v-loading="loading" stripe @selection-change="onSelectionChange" style="width: 100%">
        <el-table-column type="selection" width="50" />
        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 12px">
              <el-image v-if="row.mainImage" :src="row.mainImage" style="width: 60px; height: 60px; border-radius: var(--ds-radius-sm, 8px)" fit="cover" />
              <div v-else style="width:60px;height:60px;background:var(--ds-bg, #f5f3ef);border-radius:var(--ds-radius-sm, 8px);display:flex;align-items:center;justify-content:center;color:var(--ds-text-muted, #b2bec3);font-size:12px">暂无</div>
              <div>
                <div style="font-weight: 500; margin-bottom: 4px">{{ row.name }}</div>
                <div style="color: var(--ds-text-muted, #b2bec3); font-size: 12px">{{ row.brand || '-' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="100">
          <template #default="{ row }">{{ row.categoryName || '-' }}</template>
        </el-table-column>
        <el-table-column label="价格区间" width="140">
          <template #default="{ row }">
            <span style="color: var(--ds-coral, #e94560); font-weight: 500">¥{{ row.minPrice || 0 }} ~ ¥{{ row.maxPrice || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="totalStock" label="库存" width="80" align="center" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 0" type="info" effect="plain" size="small">待审核</el-tag>
            <el-tag v-else-if="row.status === 1" type="success" effect="plain" size="small">上架</el-tag>
            <el-tag v-else-if="row.status === 2" type="warning" effect="plain" size="small">下架</el-tag>
            <el-tag v-else type="info" effect="plain" size="small">未知</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button text :type="row.status === 1 ? 'warning' : 'success'" size="small" @click="toggleStatus(row)">
              {{ row.status === 1 ? '下架' : '上架' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="text-center" style="margin-top: 20px" v-if="total > 0">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑商品' : '新增商品'" width="640px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px">
        <el-form-item label="商品名称">
          <el-input v-model="form.name" placeholder="请输入商品名称" />
        </el-form-item>
        <el-form-item label="商品描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请输入商品描述" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="品牌">
              <el-input v-model="form.brand" placeholder="品牌名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="商品分类">
              <el-select v-model="form.categoryId" placeholder="请选择分类（如「手机」而非「手机数码」）" style="width: 100%">
                <el-option v-for="cat in subCategories" :key="cat.id" :label="cat.name" :value="cat.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="主图链接">
          <el-input v-model="form.mainImage" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="SKU 列表">
          <div v-for="(s, i) in form.skuList" :key="i" style="display: flex; align-items: center; gap: 8px; margin-bottom: 10px">
            <el-input v-model="s.name" placeholder="规格名称" style="width: 130px" />
            <el-input v-model.number="s.price" placeholder="价格" type="number" min="0" style="width: 110px" />
            <el-input v-model.number="s.stock" placeholder="库存" type="number" min="0" style="width: 90px" />
            <el-button type="danger" :icon="Delete" circle size="small" @click="form.skuList.splice(i, 1)" />
          </div>
          <el-button size="small" @click="addSku">+ 添加 SKU</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import request from '@/api/request'

/* ---------- 分类数据（从API加载） ---------- */
const allCategories = ref([])
const loadingCats = ref(false)

// 只取二级分类（parent_id > 0），防止选择一级分类时 SQL 报错
const subCategories = computed(() =>
  allCategories.value.filter(c => c.parentId && c.parentId > 0)
)

async function loadCategories() {
  loadingCats.value = true
  try {
    const res = await request.get('/category/list')
    allCategories.value = (res && res.data) || res || []
  } catch (e) {
    console.error('加载分类失败:', e)
    allCategories.value = []
  } finally {
    loadingCats.value = false
  }
}

/* ---------- 表格数据 ---------- */
const list = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const keyword = ref('')
const selectedCategoryId = ref('')

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, pageSize: pageSize.value }
    if (keyword.value) params.keyword = keyword.value
    if (selectedCategoryId.value) params.categoryId = selectedCategoryId.value
    // 调用商家商品 API，后端自动注入 shop_id
    const res = await request.get('/merchant/product/page', { params })
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
  } catch (e) {
    console.error('加载商品列表失败:', e)
    list.value = []
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  load()
}

/* ---------- 多选 ---------- */
const selectedIds = ref([])

function onSelectionChange(rows) {
  selectedIds.value = rows.map(r => r.id)
}

function clearSelection() {
  selectedIds.value = []
}

async function batchStatus(targetStatus) {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请先选择商品')
    return
  }
  for (const id of selectedIds.value) {
    try {
      await request.put('/merchant/product/status/' + id, null, { params: { status: targetStatus } })
    } catch (e) { console.error('批量更新状态失败:', e) }
  }
  ElMessage.success('批量操作完成')
  selectedIds.value = []
  load()
}

/* ---------- 状态切换 ---------- */
async function toggleStatus(row) {
  try {
    await request.put('/merchant/product/status/' + row.id, null, { params: { status: row.status === 1 ? 2 : 1 } })
    ElMessage.success('操作成功')
    load()
  } catch (e) { console.error('切换状态失败:', e) }
}

/* ---------- 新增/编辑 ---------- */
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)
const submitting = ref(false)

const defaultForm = () => ({
  name: '',
  description: '',
  brand: '',
  mainImage: '',
  categoryId: null,
  skuList: [{ name: '标准版', price: 99, stock: 100 }]
})

const form = ref(defaultForm())

function addSku() {
  form.value.skuList.push({ name: '', price: 0, stock: 0 })
}

function openAdd() {
  isEdit.value = false
  editId.value = null
  form.value = defaultForm()
  form.value.categoryId = allCategories.value.length > 0 ? allCategories.value[0].id : null
  dialogVisible.value = true
}

async function openEdit(row) {
  isEdit.value = true
  editId.value = row.id
  try {
    const res = await request.get('/product/detail/' + row.id)
    const data = res.data || res
    form.value = {
      name: data.name || '',
      description: data.description || '',
      brand: data.brand || '',
      mainImage: data.mainImage || '',
      categoryId: data.categoryId || (allCategories.value.length > 0 ? allCategories.value[0].id : null),
      skuList: (data.skuList && data.skuList.length > 0)
        ? data.skuList.map(s => ({ name: s.name, price: s.price, stock: s.stock }))
        : [{ name: '标准版', price: 99, stock: 100 }]
    }
  } catch (e) {
    console.error('加载商品详情失败:', e)
    form.value = {
      name: row.name || '',
      description: row.description || '',
      brand: row.brand || '',
      mainImage: row.mainImage || '',
      categoryId: row.categoryId || (allCategories.value.length > 0 ? allCategories.value[0].id : null),
      skuList: [{ name: '标准版', price: 99, stock: 100 }]
    }
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.value.name) {
    ElMessage.warning('请输入商品名称')
    return
  }
  if (!form.value.categoryId) {
    ElMessage.warning('请选择商品分类')
    return
  }
  submitting.value = true
  try {
    await request.post('/merchant/product/add', form.value)
    ElMessage.success(isEdit.value ? '更新成功' : '新增成功')
    dialogVisible.value = false
    load()
  } catch (e) { console.error('提交商品失败:', e) } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadCategories()
  load()
})
</script>

<style scoped>
.batch-bar {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  background: var(--ds-bg, #f5f3ef);
  border-radius: var(--ds-radius-sm, 8px);
}

/* Table header & row hover */
:deep(.el-table) {
  --el-table-header-bg-color: var(--ds-bg, #f5f3ef);
  --el-table-row-hover-bg-color: var(--ds-bg, #f5f3ef);
}

/* Dialog title */
:deep(.el-dialog__title) {
  color: var(--ds-navy, #1a1a2e);
}
</style>
