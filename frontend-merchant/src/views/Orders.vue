<template>
  <div class="page-container">
    <h2 class="mb-16">订单管理</h2>

    <!-- 搜索栏 -->
    <el-card class="card mb-16">
      <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
        <el-select v-model="activeStatus" placeholder="订单状态" clearable style="width: 140px" @change="handleSearch">
          <el-option label="全部" value="" />
          <el-option label="待支付" value="0" />
          <el-option label="已支付" value="1" />
          <el-option label="已发货" value="2" />
          <el-option label="已完成" value="4" />
          <el-option label="已取消" value="5" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
      </div>
    </el-card>

    <!-- 订单表格 -->
    <el-card class="card">
      <el-table :data="list" v-loading="loading" stripe @row-click="showDetail" style="width: 100%; cursor: pointer">
        <el-table-column label="订单号" min-width="200">
          <template #default="{ row }">
            <span style="font-family: monospace">{{ row.orderNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="总金额" width="130">
          <template #default="{ row }">
            <span style="color: #f56c6c; font-weight: 600">¥{{ (row.totalAmount || 0).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTypeMap[row.status] || 'info'" effect="dark" size="small">
              {{ statusMap[row.status] || '未知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="下单时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 1"
              text
              type="success"
              size="small"
              @click.stop="openDeliver(row)"
            >
              发货
            </el-button>
            <span v-else style="color: #999; font-size: 13px">--</span>
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
    </el-card>

    <!-- 订单详情弹窗 -->
    <el-dialog v-model="detailVisible" title="订单详情" width="700px" top="8vh">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="订单号" span="2">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="总金额">
            <span style="color: #f56c6c; font-weight: 600">¥{{ (detail.totalAmount || 0).toFixed(2) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="订单状态">
            <el-tag :type="statusTypeMap[detail.status] || 'info'" effect="dark" size="small">
              {{ statusMap[detail.status] || '未知' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间" span="2">{{ detail.createTime }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.receiverName" label="收货人">{{ detail.receiverName }}</el-descriptions-item>
          <el-descriptions-item label="收货电话">{{ detail.receiverPhone }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" span="2">{{ detail.receiverAddress }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.remark" label="备注" span="2">{{ detail.remark }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 20px 0 12px; font-size: 15px">商品明细</h4>
        <el-table :data="detail.items || []" border size="small">
          <el-table-column label="商品" min-width="200">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 10px">
                <el-image v-if="row.productImage" :src="row.productImage" style="width: 50px; height: 50px; border-radius: 4px" fit="cover" />
                <div v-else style="width:50px;height:50px;background:#f5f5f5;border-radius:4px;display:flex;align-items:center;justify-content:center;color:#ccc;font-size:12px">暂无</div>
                <span>{{ row.productName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="SKU" width="120">
            <template #default="{ row }">{{ row.skuName || '-' }}</template>
          </el-table-column>
          <el-table-column label="单价" width="100">
            <template #default="{ row }">¥{{ (row.price || 0).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column label="小计" width="110">
            <template #default="{ row }">
              <span style="color: #f56c6c">¥{{ (row.subtotal || 0).toFixed(2) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <div v-else class="text-center" style="padding: 60px 0; color: #999">加载中...</div>
    </el-dialog>

    <!-- 发货弹窗 -->
    <el-dialog v-model="deliverVisible" title="发货" width="420px">
      <el-form :model="deliverForm" label-width="80px">
        <el-form-item label="物流单号" required>
          <el-input v-model="deliverForm.logisticsNo" placeholder="请输入物流单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deliverVisible = false">取消</el-button>
        <el-button type="primary" :loading="delivering" @click="handleDeliver">确认发货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/api/request'

/* ---------- 状态映射 ---------- */
const statusMap = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已收货', 4: '已完成', 5: '已取消', 6: '已退款' }
const statusTypeMap = { 0: 'info', 1: 'warning', 2: 'primary', 3: 'success', 4: 'success', 5: 'danger', 6: 'info' }

/* ---------- 数据 ---------- */
const list = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const activeStatus = ref('')

/* ---------- 加载 ---------- */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, pageSize: pageSize.value }
    if (activeStatus.value !== '') {
      params.status = parseInt(activeStatus.value)
    }
    const res = await request.get('/merchant/order/page', { params })
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
  } catch (e) {
    console.error('加载订单列表失败:', e)
    list.value = []
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  load()
}

/* ---------- 详情 ---------- */
const detailVisible = ref(false)
const detail = ref(null)

async function showDetail(row) {
  detailVisible.value = true
  detail.value = null
  try {
    const res = await request.get('/merchant/order/detail/' + row.id)
    detail.value = res.data || res
  } catch (e) {
    console.error('获取订单详情失败:', e)
    ElMessage.error('获取订单详情失败')
    detailVisible.value = false
  }
}

/* ---------- 发货 ---------- */
const deliverVisible = ref(false)
const deliverForm = ref({ orderId: null, logisticsNo: '' })
const delivering = ref(false)
const currentDeliverRow = ref(null)

function openDeliver(row) {
  currentDeliverRow.value = row
  deliverForm.value = { orderId: row.id, logisticsNo: '' }
  deliverVisible.value = true
}

async function handleDeliver() {
  if (!deliverForm.value.logisticsNo) {
    ElMessage.warning('请输入物流单号')
    return
  }
  delivering.value = true
  try {
    await request.post('/merchant/order/deliver', {
      orderId: deliverForm.value.orderId,
      logisticsNo: deliverForm.value.logisticsNo
    })
    ElMessage.success('发货成功')
    deliverVisible.value = false
    load()
  } catch (e) {
    console.error('发货失败:', e)
    ElMessage.error('发货失败')
  } finally {
    delivering.value = false
  }
}

onMounted(load)
</script>

<style scoped>
:deep(.el-tabs__item) { font-size: 14px; }
:deep(.el-tabs__header) { margin-bottom: 0; }
</style>
