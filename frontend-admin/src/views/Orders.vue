<template>
  <div class="page-container">
    <div class="card-elevated">
      <!-- 搜索筛选栏 -->
      <div class="filter-bar mb-16">
        <el-input v-model="keyword" placeholder="订单号/收货人/电话" clearable style="width: 220px" @keyup.enter="handleSearch" />
        <el-input v-model="shopId" placeholder="商家ID" clearable style="width: 130px" type="number" @keyup.enter="handleSearch" />
        <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" style="width: 260px" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <!-- 状态筛选标签页 -->
      <el-tabs v-model="activeTab" @tab-change="handleTabChange" class="mb-16">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="待支付" name="0" />
        <el-tab-pane label="已支付" name="1" />
        <el-tab-pane label="已发货" name="2" />
        <el-tab-pane label="已完成" name="4" />
        <el-tab-pane label="已取消" name="5" />
        <el-tab-pane label="售后" name="6" />
      </el-tabs>

      <!-- 订单表格 -->
      <div class="table-wrap">
        <el-table
          :data="list"
          v-loading="loading"
          stripe
          style="width: 100%"
          @row-click="openDetail"
        >
          <el-table-column prop="orderNo" label="订单号" width="200" show-overflow-tooltip />
          <el-table-column label="用户ID" width="80">
            <template #default="{ row }">{{ row.userId || '-' }}</template>
          </el-table-column>
          <el-table-column label="总金额" width="110">
            <template #default="{ row }">¥{{ (row.totalAmount || 0).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="实付金额" width="110">
            <template #default="{ row }">
              <span style="font-weight: 600; color: var(--ds-coral, #e94560)">¥{{ (row.actualAmount || 0).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTypeMap[row.status] || 'info'" size="small" effect="dark">
                {{ statusMap[row.status] || '未知' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ row.createTime || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click.stop="openDetail(row)">
                查看
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-if="!loading && list.length === 0" description="暂无订单数据" />
      </div>

      <!-- 分页 -->
      <div v-if="total > 0" class="flex-center mt-16">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadOrders"
        />
      </div>
    </div>

    <!-- 订单详情抽屉 -->
    <el-drawer
      v-model="showDetail"
      title="订单详情"
      size="500px"
      direction="rtl"
    >
      <template v-if="detailData">
        <div class="detail-section">
          <h4 class="detail-title">基本信息</h4>
          <div class="detail-item"><span class="detail-label">订单号</span><span>{{ detailData.orderNo }}</span></div>
          <div class="detail-item"><span class="detail-label">用户ID</span><span>{{ detailData.userId }}</span></div>
          <div class="detail-item">
            <span class="detail-label">状态</span>
            <el-tag :type="statusTypeMap[detailData.status] || 'info'" size="small">{{ statusMap[detailData.status] || '未知' }}</el-tag>
          </div>
          <div class="detail-item"><span class="detail-label">总金额</span><span>¥{{ (detailData.totalAmount || 0).toFixed(2) }}</span></div>
          <div class="detail-item"><span class="detail-label">实付金额</span><span style="color: var(--ds-coral, #e94560)">¥{{ (detailData.actualAmount || 0).toFixed(2) }}</span></div>
          <div class="detail-item"><span class="detail-label">创建时间</span><span>{{ detailData.createTime }}</span></div>
        </div>

        <div class="detail-section">
          <h4 class="detail-title">收货信息</h4>
          <div class="detail-item"><span class="detail-label">收件人</span><span>{{ detailData.receiverName || '-' }}</span></div>
          <div class="detail-item"><span class="detail-label">电话</span><span>{{ detailData.receiverPhone || '-' }}</span></div>
          <div class="detail-item"><span class="detail-label">地址</span><span>{{ detailData.receiverAddress || '-' }}</span></div>
        </div>

        <div class="detail-section">
          <h4 class="detail-title">商品明细</h4>
          <div v-if="detailData.items && detailData.items.length > 0">
            <div v-for="(item, idx) in detailData.items" :key="idx" class="order-item">
              <div style="display: flex; align-items: center; gap: 10px; flex: 1">
                <el-image
                  v-if="item.productImage"
                  :src="item.productImage"
                  style="width: 50px; height: 50px; border-radius: var(--ds-radius-sm, 8px); flex-shrink: 0"
                  fit="cover"
                />
                <div style="flex: 1">
                  <div style="font-size: 14px">{{ item.productName }}</div>
                  <div v-if="item.skuName" style="font-size: 12px; color: var(--ds-text-muted, #999); margin-top: 2px">
                    规格: {{ item.skuName }}
                  </div>
                </div>
              </div>
              <div style="text-align: right; white-space: nowrap; margin-left: 12px">
                <div>¥{{ (item.price || 0).toFixed(2) }}</div>
                <div style="font-size: 12px; color: var(--ds-text-muted, #999)">x{{ item.quantity || 0 }}</div>
                <div style="font-size: 12px; font-weight: 600">¥{{ (item.subtotal || 0).toFixed(2) }}</div>
              </div>
            </div>
          </div>
          <el-empty v-else description="暂无商品明细" :image-size="60" />
        </div>
      </template>
      <div v-else v-loading="detailLoading" style="min-height: 200px" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/api/request'

const list = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const activeTab = ref('')

// 搜索筛选
const keyword = ref('')
const shopId = ref('')
const dateRange = ref(null)

// 详情抽屉
const showDetail = ref(false)
const detailData = ref(null)
const detailLoading = ref(false)

// 订单状态映射
const statusMap = {
  0: '待支付', 1: '已支付', 2: '已发货', 3: '已收货',
  4: '已完成', 5: '已取消', 6: '已退款'
}

const statusTypeMap = {
  0: 'info', 1: 'primary', 2: 'warning', 3: 'primary',
  4: 'success', 5: 'danger', 6: 'info'
}

// 加载订单列表
async function loadOrders() {
  loading.value = true
  try {
    const params = { page: page.value, pageSize: pageSize.value }
    if (activeTab.value !== '') {
      params.status = parseInt(activeTab.value)
    }
    if (keyword.value) params.keyword = keyword.value
    if (shopId.value) params.shopId = parseInt(shopId.value)
    if (dateRange.value && dateRange.value.length === 2) {
      params.startTime = dateRange.value[0]
      params.endTime = dateRange.value[1]
    }
    // 调用管理员订单 API
    const res = await request.get('/admin/order/page', { params })
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
  } catch (e) {
    console.error('加载订单列表失败:', e)
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleTabChange() {
  page.value = 1
  loadOrders()
}

function handleSearch() {
  page.value = 1
  loadOrders()
}

function handleReset() {
  keyword.value = ''
  shopId.value = ''
  dateRange.value = null
  page.value = 1
  loadOrders()
}

// 打开订单详情
async function openDetail(row) {
  showDetail.value = true
  detailLoading.value = true
  detailData.value = null
  try {
    const res = await request.get('/admin/order/detail/' + row.id)
    detailData.value = res.data
  } catch (e) {
    console.error('加载订单详情失败:', e)
    ElMessage.error('加载订单详情失败')
  } finally {
    detailLoading.value = false
  }
}

onMounted(loadOrders)
</script>

<style scoped>
.page-container {
  height: 100%;
  max-width: none;
  display: flex;
  flex-direction: column;
}
.page-container > .card-elevated {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.el-tabs { flex-shrink: 0; }
.table-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.table-wrap .el-table {
  flex: 1;
  min-height: 0;
  height: 100%;
}
.detail-section {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--ds-border-light, #f0f0f0);
}
.detail-section:last-child { border-bottom: none; }
.detail-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
  color: var(--ds-navy, #1a1a2e);
}
.detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  font-size: 14px;
}
.detail-label {
  color: var(--ds-text-muted, #999);
  flex-shrink: 0;
  margin-right: 16px;
}
.order-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--ds-border-light, #f0eee9);
}
.order-item:last-child { border-bottom: none; }

/* ====== Table :deep overrides ====== */
.table-wrap :deep(.el-table) {
  --el-table-header-bg-color: var(--ds-bg, #f5f3ef);
  --el-table-row-hover-bg-color: var(--ds-bg, #f5f3ef);
}

/* ====== Drawer title ====== */
:deep(.el-drawer__header) {
  color: var(--ds-navy, #1a1a2e);
  font-weight: 600;
}
</style>
