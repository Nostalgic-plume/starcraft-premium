<template>
  <div class="page-container fade-in" v-loading="submitting">
    <h2 class="page-title">确认订单</h2>

    <!-- 收货信息 -->
    <div class="card">
      <h3 class="section-title"><el-icon><Location /></el-icon> 收货信息</h3>
      <el-form :model="addressForm" label-width="80px">
        <el-row :gutter="16">
          <el-col :span="8"><el-input v-model="addressForm.receiverName" placeholder="收货人" class="info-input" /></el-col>
          <el-col :span="8"><el-input v-model="addressForm.receiverPhone" placeholder="收货电话" class="info-input" /></el-col>
        </el-row>
        <el-form-item label="收货地址" class="mt-16">
          <el-input v-model="addressForm.receiverAddress" placeholder="详细地址" type="textarea" :rows="2" class="info-input" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="remark" placeholder="订单备注（选填）" class="info-input" />
        </el-form-item>
      </el-form>
    </div>

    <!-- 商品列表 -->
    <div class="card">
      <h3 class="section-title"><el-icon><Goods /></el-icon> 商品信息</h3>
      <div v-for="item in orderItems" :key="item.skuId" class="order-item">
        <el-image :src="item.productImage || '/placeholder.png'" class="item-img" fit="cover" />
        <div class="item-info">
          <div class="item-name">{{ item.productName || item.skuName }}</div>
          <div class="item-sku">{{ item.skuName }}</div>
        </div>
        <div class="item-detail">¥{{ item.price }} × {{ item.quantity }}</div>
        <div class="item-total">¥{{ (item.price * item.quantity).toFixed(2) }}</div>
      </div>
    </div>

    <!-- 金额汇总 -->
    <div class="card summary-card">
      <div class="summary-row">共 <strong>{{ orderItems.length }}</strong> 件商品</div>
      <div class="summary-row total-row">
        <span class="summary-label">合计</span>
        <span class="summary-price">¥{{ totalAmount.toFixed(2) }}</span>
      </div>
    </div>

    <!-- 提交 -->
    <div class="submit-wrap">
      <el-button type="primary" size="large" class="submit-btn" :loading="submitting" @click="submitOrder">提交订单</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Location, Goods } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { orderApi } from '@/api/order'
import { useCartStore } from '@/stores/cart'

const route = useRoute()
const router = useRouter()
const cartStore = useCartStore()

const submitting = ref(false)
const remark = ref('')
const addressForm = ref({
  receiverName: '张三',
  receiverPhone: '13800138000',
  receiverAddress: '北京市朝阳区xxx街道xxx号'
})
const orderItems = ref([])

const totalAmount = computed(() =>
  orderItems.value.reduce((sum, i) => sum + (i.price || 0) * i.quantity, 0)
)

async function loadItems() {
  // 优先从购物车获取选中的商品
  const selected = cartStore.selectedItems()
  if (selected.length > 0) {
    orderItems.value = selected.map(i => ({
      skuId: i.skuId,
      productName: i.productName,
      skuName: i.skuName,
      price: i.price,
      quantity: i.quantity,
      productImage: i.productImage
    }))
    return
  }
  // 从 URL 查询参数解析（立即购买跳转）
  const items = route.query.items || ''
  const pairs = items.split(',').filter(Boolean)
  for (const pair of pairs) {
    const [skuId, quantity] = pair.split('-').map(Number)
    orderItems.value.push({
      skuId,
      quantity,
      productName: route.query.productName || '商品',
      skuName: route.query.skuName || '默认规格',
      price: route.query.price ? Number(route.query.price) : 0,
      productImage: route.query.image || ''
    })
  }
}

async function submitOrder() {
  if (orderItems.value.length === 0) {
    ElMessage.warning('暂无商品可提交')
    return
  }
  // 校验收货信息
  if (!addressForm.value.receiverName || !addressForm.value.receiverPhone || !addressForm.value.receiverAddress) {
    ElMessage.warning('请填写完整的收货信息')
    return
  }
  submitting.value = true
  try {
    const tokenRes = await orderApi.getToken()
    const token = tokenRes.data?.token
    if (!token) {
      ElMessage.error('获取订单令牌失败，请重试')
      submitting.value = false
      return
    }
    const res = await orderApi.create({
      token,
      receiverName: addressForm.value.receiverName,
      receiverPhone: addressForm.value.receiverPhone,
      receiverAddress: addressForm.value.receiverAddress,
      remark: remark.value,
      items: orderItems.value.map(i => ({ skuId: i.skuId, quantity: i.quantity }))
    })
    // 兼容多种返回格式：res.data 可能是直接数值 ID 或 { id }
    const orderData = res.data ?? res
    const orderId = typeof orderData === 'number' || typeof orderData === 'string'
      ? Number(orderData) : (orderData?.id || orderData?.orderId)
    if (orderId && orderId > 0) {
      ElMessage.success('下单成功！请尽快支付')
      router.push(`/order/${orderId}`)
    } else {
      ElMessage.success('下单成功！请尽快支付')
      router.push('/orders')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message || '下单失败，请重试')
  } finally {
    submitting.value = false
  }
}

onMounted(loadItems)
</script>

<style scoped>
.section-title { font-size: 17px; font-weight: 700; color: #1a1a2e; margin-bottom: 20px; display: flex; align-items: center; gap: 8px; }
.info-input { --el-input-height: 42px; }
.info-input :deep(.el-input__wrapper) { border-radius: 8px; }
.order-item { display: flex; align-items: center; gap: 16px; padding: 14px 0; border-bottom: 1px solid #f5f5f5; }
.order-item:last-child { border-bottom: none; }
.item-img { width: 60px; height: 60px; border-radius: 8px; flex-shrink: 0; }
.item-info { flex: 1; }
.item-name { font-size: 15px; font-weight: 600; color: #1a1a2e; margin-bottom: 2px; }
.item-sku { font-size: 13px; color: #999; }
.item-detail { width: 140px; text-align: right; color: #666; }
.item-total { width: 100px; text-align: right; color: var(--danger); font-weight: 600; }

.summary-card { text-align: right; }
.summary-row { margin-bottom: 8px; color: #666; }
.total-row { margin-top: 12px; padding-top: 12px; border-top: 1px solid #f0f0f0; }
.summary-label { font-size: 16px; color: #333; }
.summary-price { font-size: 28px; font-weight: 700; color: var(--danger); margin-left: 12px; }

.submit-wrap { text-align: center; margin-top: 8px; }
.submit-btn { height: 50px; border-radius: 10px; padding: 0 48px; font-size: 17px; font-weight: 600; }
</style>
