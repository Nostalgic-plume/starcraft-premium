<template>
  <div class="page-container fade-in" ref="cartRef">
    <h2 class="page-title">购物车</h2>

    <div v-loading="cartStore.loading || loadingStock">
      <!-- 购物车列表 -->
      <div v-if="cartStore.items.length" class="cart-items">
        <div
          v-for="item in cartStore.items"
          :key="item.id"
          class="cart-item card"
          :class="{ 'item-out-of-stock': isOutOfStock(item) }"
        >
          <el-checkbox
            :model-value="item.checked"
            :disabled="isOutOfStock(item)"
            @change="toggleCheck(item)"
            class="cart-checkbox"
          />
          <el-image :src="item.productImage || '/placeholder.png'" class="cart-img" fit="cover" />
          <div class="cart-info">
            <div class="cart-name">{{ item.productName || item.skuName }}</div>
            <div class="cart-sku">{{ item.skuName }}</div>
            <!-- 库存不足提示 -->
            <div v-if="isOutOfStock(item)" class="stock-warning">
              <el-tag type="danger" size="small">库存不足（仅剩 {{ getStock(item.skuId) }} 件）</el-tag>
            </div>
          </div>
          <div class="cart-price">¥{{ item.price }}</div>
          <el-input-number
            v-model="item.quantity"
            :min="1"
            :max="99"
            size="small"
            class="cart-qty"
            :disabled="isOutOfStock(item)"
            @change="(val) => cartStore.updateQuantity(item.skuId, val)"
          />
          <div class="cart-subtotal">¥{{ item.subtotal?.toFixed(2) }}</div>
          <el-button text type="danger" class="cart-remove" @click="cartStore.removeItem(item.skuId)">删除</el-button>
        </div>
      </div>

      <!-- 底部栏 -->
      <div class="cart-footer card" v-if="cartStore.items.length">
        <div class="footer-left">
          <el-checkbox
            :model-value="allChecked"
            :indeterminate="indeterminate"
            @change="toggleAll"
            class="check-all"
          >全选</el-checkbox>
          <span class="footer-sep">|</span>
          已选 <strong class="text-primary">{{ checkedCount }}</strong> 件，
          合计 <strong class="text-danger total-amount">¥{{ checkedTotal.toFixed(2) }}</strong>
        </div>
        <div class="footer-right">
          <span class="stock-err-hint" v-if="hasStockIssue">部分商品库存不足，请调整数量</span>
          <el-button
            type="primary"
            size="large"
            class="footer-btn"
            :disabled="checkedCount === 0 || hasStockIssue"
            @click="goCheckout"
          >去结算</el-button>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="!cartStore.loading && cartStore.items.length === 0" class="empty-wrap">
        <el-empty description="购物车是空的" class="empty-state">
          <template #image>
            <svg viewBox="0 0 100 100" width="120" height="120" xmlns="http://www.w3.org/2000/svg">
              <circle cx="50" cy="50" r="45" fill="#f0f2f5" />
              <path d="M30 35h40l-5 30H35z" fill="#dcdfe6" stroke="#c0c4cc" stroke-width="1.5" />
              <circle cx="38" cy="70" r="4" fill="#c0c4cc" />
              <circle cx="62" cy="70" r="4" fill="#c0c4cc" />
              <path d="M45 25l-5 10m15-10l5 10" stroke="#c0c4cc" stroke-width="2" stroke-linecap="round" />
            </svg>
          </template>
        </el-empty>
        <el-button type="primary" class="empty-btn" @click="$router.push('/home')">去逛逛</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useCartStore } from '@/stores/cart'
import { productApi } from '@/api/product'
import gsap from 'gsap'

const router = useRouter()
const cartStore = useCartStore()

const stockMap = ref({})
const loadingStock = ref(false)
const cartRef = ref(null)
let cartCtx = null

// 全选状态
const allChecked = computed(() => {
  const validItems = cartStore.items.filter(i => i.valid !== false)
  return validItems.length > 0 && validItems.every(i => i.checked)
})
const indeterminate = computed(() => {
  const checked = cartStore.items.filter(i => i.checked).length
  return checked > 0 && checked < cartStore.items.length
})

// 选中数量
const checkedCount = computed(() =>
  cartStore.items.filter(i => i.checked).length
)

// 选中商品合计
const checkedTotal = computed(() =>
  cartStore.items
    .filter(i => i.checked)
    .reduce((sum, i) => sum + (i.subtotal || 0), 0)
)

// 是否存在库存问题
const hasStockIssue = computed(() =>
  cartStore.items.some(i => i.checked && isOutOfStock(i))
)

function isOutOfStock(item) {
  const stock = getStock(item.skuId)
  if (stock === undefined) return false
  return stock < item.quantity
}

function getStock(skuId) {
  return stockMap.value[skuId]
}

async function checkStock() {
  loadingStock.value = true
  const newMap = {}
  for (const item of cartStore.items) {
    if (item.productId) {
      try {
        const res = await productApi.detail(item.productId)
        const product = res.data
        if (product?.skuList) {
          const sku = product.skuList.find(s => s.id === item.skuId)
          if (sku && sku.stock !== undefined) {
            newMap[item.skuId] = sku.stock
          }
        }
      } catch (e) { console.error('查询库存失败:', e) }
    }
  }
  stockMap.value = newMap
  loadingStock.value = false
}

function toggleCheck(item) {
  cartStore.checkItem(item.skuId, !item.checked)
}

function toggleAll(checked) {
  cartStore.items.forEach(i => {
    if (i.checked !== checked) {
      cartStore.checkItem(i.skuId, checked)
    }
  })
}

/* ====== GSAP 动画 ====== */
function initCartAnimation() {
  if (!cartRef.value) return
  cartCtx = gsap.context(() => {
    // 购物车列表项从底部交错入场（弹跳感）
    gsap.from('.cart-item', {
      y: 20,
      opacity: 0,
      duration: 0.4,
      stagger: 0.05,
      ease: 'back.out(1.3)'
    })
    // 底部结算栏淡入
    gsap.from('.cart-footer', {
      y: 12,
      opacity: 0,
      duration: 0.35,
      delay: 0.3,
      ease: 'power2.out'
    })
  }, cartRef.value)
}

function goCheckout() {
  const selected = cartStore.selectedItems()
  if (selected.length === 0) {
    ElMessage.warning('请选择要结算的商品')
    return
  }
  // 检查选中商品库存
  for (const item of selected) {
    if (isOutOfStock(item)) {
      ElMessage.warning(`"${item.productName}" 库存不足，请调整数量`)
      return
    }
  }
  const ids = selected.map(i => `${i.skuId}-${i.quantity}`).join(',')
  router.push('/order/create?items=' + encodeURIComponent(ids))
}

onMounted(async () => {
  await cartStore.fetchList()
  checkStock()
  await nextTick()
  initCartAnimation()
})

onUnmounted(() => {
  cartCtx?.revert()
})

// 商品列表变化时重新检查库存
watch(() => cartStore.items.length, () => {
  if (cartStore.items.length > 0) checkStock()
})
</script>

<style scoped>
.cart-items { display: flex; flex-direction: column; gap: 12px; }
.cart-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: var(--ds-surface, #fff);
  border: 1px solid var(--ds-border-light, #f0eee9);
  border-radius: var(--ds-radius-lg, 16px);
  margin-bottom: 0;
  transition: var(--ds-transition);
}
.cart-item:hover { box-shadow: var(--ds-shadow-md); }
.cart-item.item-out-of-stock { background: #fff5f5; }
.cart-checkbox { --el-checkbox-input-width: 18px; --el-checkbox-input-height: 18px; }
.cart-img { width: 80px; height: 80px; border-radius: var(--ds-radius, 10px); flex-shrink: 0; opacity: 1; }
.item-out-of-stock .cart-img { opacity: 0.6; }
.cart-info { flex: 1; min-width: 0; }
.cart-name { font-size: 15px; font-weight: 600; color: #1a1a2e; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-out-of-stock .cart-name { color: #999; }
.cart-sku { font-size: 13px; color: #999; }
.cart-price { width: 80px; color: var(--ds-coral, #e94560); font-weight: 600; }
/* 圆形加减按钮 + 墨蓝边框 */
.cart-qty { width: 140px; }
.cart-qty :deep(.el-input-number__decrease),
.cart-qty :deep(.el-input-number__increase) {
  border-radius: 50% !important;
  width: 26px;
  height: 26px;
  top: 50%;
  transform: translateY(-50%);
  border: 1.5px solid var(--ds-navy, #1a1a2e);
  background: #fff;
  color: var(--ds-navy, #1a1a2e);
  font-weight: 700;
}
.cart-qty :deep(.el-input-number__decrease) { left: 4px; right: auto; }
.cart-qty :deep(.el-input-number__increase) { right: 4px; left: auto; }
.cart-qty :deep(.el-input-number__decrease:hover),
.cart-qty :deep(.el-input-number__increase:hover) { background: var(--ds-navy, #1a1a2e); color: #fff; }
.cart-qty :deep(.el-input__wrapper) { padding-left: 32px; padding-right: 32px; }
.cart-subtotal { width: 100px; color: var(--ds-coral, #e94560); font-weight: 700; text-align: right; }
.cart-remove { font-weight: 500; }

.stock-warning { margin-top: 4px; }

.cart-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  position: sticky;
  bottom: 0;
  border-radius: var(--ds-radius-lg, 16px);
  margin-top: 4px;
  box-shadow: 0 -4px 20px rgba(26,26,46,0.06);
  z-index: 10;
}
.footer-left { display: flex; align-items: center; gap: 8px; font-size: 15px; }
.footer-sep { color: #e0e0e0; }
.footer-right { display: flex; align-items: center; gap: 12px; }
.check-all { font-size: 15px; }
.total-amount { font-size: 22px; font-weight: 700; }
.footer-btn {
  height: 48px;
  border-radius: var(--ds-radius, 10px);
  padding: 0 32px;
  font-size: 16px;
  font-weight: 600;
  background: var(--ds-coral-gradient, linear-gradient(135deg, #e94560, #ff6b81)) !important;
  border: none !important;
  color: #fff !important;
}
.footer-btn:hover { box-shadow: var(--ds-shadow-coral, 0 4px 20px rgba(233,69,96,0.2)) !important; transform: translateY(-1px); }
.stock-err-hint { font-size: 12px; color: var(--ds-danger, #e94560); max-width: 160px; text-align: right; }
.empty-wrap { text-align: center; padding: 40px 0; }
.empty-state { margin-top: 0; }
.empty-btn { margin-top: 16px; }
</style>
