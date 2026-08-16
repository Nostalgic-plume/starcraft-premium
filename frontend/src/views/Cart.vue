<template>
  <div class="page-container fade-in" ref="cartRef">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 标题 + 件数 -->
    <div class="cart-head">
      <h2 class="cart-title">我的购物车</h2>
      <span v-if="cartStore.items.length" class="cart-count">
        共 {{ cartStore.items.length }} 件商品
      </span>
    </div>

    <n-spin :show="cartStore.loading || loadingStock">
      <template v-if="cartStore.items.length">
        <!-- 商品行列表 -->
        <div class="cart-list">
          <div
            v-for="item in cartStore.items"
            :key="item.id"
            class="cart-row"
            :class="{ 'row-invalid': item.valid === false }"
          >
            <!-- 勾选（圆框 ✓） -->
            <button
              type="button"
              class="check-circle"
              :class="{ checked: item.checked }"
              :disabled="item.valid === false"
              :aria-label="item.checked ? '取消选择该商品' : '选择该商品'"
              @click="toggleCheck(item)"
            >
              <SvgIcon name="check" :size="13" />
            </button>

            <!-- 商品图 -->
            <div class="row-thumb">
              <img
                v-if="item.productImage"
                :src="item.productImage"
                class="thumb-img"
                :alt="item.productName"
                loading="lazy"
              />
              <div v-else class="thumb-placeholder">
                <SvgIcon name="box" :size="26" class="thumb-icon" />
              </div>
            </div>

            <!-- 名称 / 规格 -->
            <div class="row-info">
              <p class="row-name" :title="item.productName">{{ item.productName || item.skuName }}</p>
              <p class="row-sku">{{ item.skuName }}</p>
              <span v-if="isOutOfStock(item)" class="stock-warn">
                库存不足（仅剩 {{ getStock(item.skuId) }} 件）
              </span>
              <span v-else-if="item.valid === false" class="stock-warn">该商品已失效</span>
            </div>

            <!-- 单价（蓝） -->
            <div class="row-price">
              <span class="price-symbol">¥</span>{{ formatPrice(item.price) }}
            </div>

            <!-- 数量步进器（Naive） -->
            <div class="row-qty">
              <n-input-number
                :value="item.quantity"
                :min="1"
                :max="maxQtyFor(item)"
                size="small"
                button-placement="both"
                :update-value-on-input="false"
                :disabled="isOutOfStock(item)"
                @update:value="(v) => onQtyChange(item, v)"
              />
            </div>

            <!-- 小计（蓝） -->
            <div class="row-subtotal">
              <span class="price-symbol">¥</span>{{ formatPrice(lineSubtotal(item)) }}
            </div>

            <!-- 删除 -->
            <button
              type="button"
              class="row-remove"
              :aria-label="'删除 ' + (item.productName || '商品')"
              title="删除"
              @click="removeItem(item)"
            >
              <SvgIcon name="close" :size="16" />
            </button>
          </div>
        </div>

        <!-- 底部结算栏 -->
        <div class="checkout-bar">
          <div class="checkout-left">
            <button type="button" class="check-all" :class="{ checked: allChecked }" @click="toggleAll">
              <span class="check-circle check-all-circle" :class="{ checked: allChecked }">
                <SvgIcon name="check" :size="13" />
              </span>
              全选
            </button>
            <span class="checkout-info">
              已选 <strong class="count-num">{{ checkedCount }}</strong> 件
            </span>
          </div>

          <div class="checkout-right">
            <span v-if="hasStockIssue" class="stock-hint">部分商品库存不足，请调整数量</span>
            <div class="checkout-total">
              <span class="total-label">合计</span>
              <span class="total-value">
                <span class="total-symbol">¥</span>{{ formatPrice(checkedTotal) }}
              </span>
            </div>
            <button
              type="button"
              class="checkout-btn"
              :disabled="checkedCount === 0 || hasStockIssue"
              @click="goCheckout"
            >去结算</button>
          </div>
        </div>
      </template>

      <!-- 空购物车 -->
      <EmptyState
        v-else-if="!cartStore.loading"
        text="购物车还是空的"
        description="快去挑选心仪的商品吧"
        icon="box-open"
      >
        <button type="button" class="go-shop-btn" @click="router.push('/home')">去逛逛</button>
      </EmptyState>
    </n-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { NInputNumber, NSpin, useMessage } from 'naive-ui'
import gsap from 'gsap'
import { productApi } from '@/api/product'
import { useCartStore } from '@/stores/cart'
import BackBar from '@/components/BackBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import type { CartItemVO } from '@/api/types'

const router = useRouter()
const message = useMessage()
const cartStore = useCartStore()

const stockMap = ref<Record<string, number>>({})
const loadingStock = ref(false)
const cartRef = ref<HTMLElement | null>(null)
let ctx: gsap.Context | null = null

/* ====== 选中状态 ====== */
const allChecked = computed(() => {
  const validItems = cartStore.items.filter(i => i.valid !== false)
  return validItems.length > 0 && validItems.every(i => i.checked)
})

const checkedCount = computed(() => cartStore.items.filter(i => i.checked).length)

const checkedTotal = computed(() =>
  cartStore.items
    .filter(i => i.checked)
    .reduce((sum, i) => sum + lineSubtotal(i), 0)
)

const hasStockIssue = computed(() =>
  cartStore.items.some(i => i.checked && isOutOfStock(i))
)

/* ====== 库存判断 ====== */
function getStock(skuId: string): number | undefined {
  return stockMap.value[skuId]
}

function isOutOfStock(item: CartItemVO): boolean {
  const stock = getStock(item.skuId)
  if (stock === undefined) return false
  return stock < item.quantity
}

function maxQtyFor(item: CartItemVO): number {
  const stock = getStock(item.skuId)
  if (stock !== undefined && stock > 0) return stock
  return 99
}

/** 行小计：以实时数量为准（避免数量改动后旧 subtotal 残留） */
function lineSubtotal(item: CartItemVO): number {
  return Math.round(item.price * item.quantity * 100) / 100
}

/** 价格展示：整数省略小数，否则保留两位 */
function formatPrice(val: number | undefined | null): string {
  const n = Number(val || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/* ====== 库存核对（复用 productApi.detail 查询，与旧逻辑一致） ====== */
async function checkStock() {
  loadingStock.value = true
  const newMap: Record<string, number> = {}
  try {
    await Promise.all(cartStore.items.map(async (item) => {
      if (!item.productId) return
      try {
        const res = await productApi.detail(item.productId)
        const sku = res.data?.skuList?.find(s => s.id === item.skuId)
        if (sku && sku.stock !== undefined) {
          newMap[item.skuId] = sku.stock
        }
      } catch {
        /* 单个商品库存查询失败不影响整体 */
      }
    }))
  } finally {
    stockMap.value = newMap
    loadingStock.value = false
  }
}

/* ====== 勾选 / 全选 ====== */
function toggleCheck(item: CartItemVO) {
  if (item.valid === false) return
  cartStore.checkItem(item.skuId, !item.checked)
}

function toggleAll() {
  const target = !allChecked.value
  cartStore.items.forEach(i => {
    if (i.valid !== false && i.checked !== target) {
      cartStore.checkItem(i.skuId, target)
    }
  })
}

/* ====== 数量改动（防抖，避免输入过程频繁请求） ====== */
const qtyTimers: Record<string, number> = {}

function onQtyChange(item: CartItemVO, val: number | null) {
  if (val === null || val < 1) return
  item.quantity = val
  const skuId = item.skuId
  window.clearTimeout(qtyTimers[skuId])
  qtyTimers[skuId] = window.setTimeout(() => {
    cartStore.updateQuantity(skuId, val).catch(() => {
      /* 错误提示由 axios 拦截器统一处理 */
    })
  }, 400)
}

/* ====== 删除 ====== */
async function removeItem(item: CartItemVO) {
  try {
    await cartStore.removeItem(item.skuId)
    message.success('已删除')
  } catch {
    /* 错误提示由 axios 拦截器统一处理 */
  }
}

/* ====== 结算 ====== */
function goCheckout() {
  const selected = cartStore.selectedItems()
  if (selected.length === 0) {
    message.warning('请选择要结算的商品')
    return
  }
  for (const item of selected) {
    if (isOutOfStock(item)) {
      message.warning(`"${item.productName}" 库存不足，请调整数量`)
      return
    }
  }
  const ids = selected.map(i => `${i.skuId}-${i.quantity}`).join(',')
  router.push('/order/create?items=' + encodeURIComponent(ids))
}

/* ====== GSAP 入场动画 ====== */
function initCartAnimation() {
  if (!cartRef.value) return
  ctx = gsap.context(() => {
    gsap.from('.cart-row', {
      y: 18,
      opacity: 0,
      duration: 0.4,
      stagger: 0.05,
      ease: 'back.out(1.3)'
    })
    gsap.from('.checkout-bar', {
      y: 12,
      opacity: 0,
      duration: 0.35,
      delay: 0.3,
      ease: 'power2.out'
    })
  }, cartRef.value)
}

/* ====== 数据加载 ====== */
async function loadCart() {
  await cartStore.fetchList()
  checkStock()
  nextTick(() => initCartAnimation())
}

onMounted(loadCart)

watch(() => cartStore.items.length, () => {
  if (cartStore.items.length > 0) checkStock()
})

onUnmounted(() => {
  Object.values(qtyTimers).forEach(t => window.clearTimeout(t))
  ctx?.revert()
})
</script>

<style scoped>
.cart-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 20px;
}
.cart-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0;
}
.cart-count {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}

/* ====== 商品行 ====== */
.cart-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}
.cart-row {
  display: grid;
  grid-template-columns: 24px 76px minmax(0, 1fr) 92px 132px 104px 36px;
  gap: 16px;
  align-items: center;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 16px 18px;
  transition: all 0.25s ease;
}
.cart-row:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.cart-row.row-invalid {
  background: var(--ds-bg-2, #eef0f4);
}

/* 勾选（圆框 ✓） */
.check-circle {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 1.5px solid var(--ds-line-2, #d8dce4);
  background: var(--ds-card, #fff);
  color: transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  padding: 0;
  transition: all 0.2s;
}
.check-circle:hover { border-color: var(--ds-primary, #2d7ff9); }
.check-circle.checked {
  background: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
  color: #fff;
}
.check-circle:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 商品图 */
.row-thumb {
  width: 76px;
  height: 76px;
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
  background: linear-gradient(135deg, var(--ds-bg-2, #eef0f4), var(--ds-bg, #f6f7fa));
  flex-shrink: 0;
}
.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.thumb-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.thumb-icon {
  color: var(--ds-primary, #2d7ff9);
  opacity: 0.5;
}
.row-invalid .thumb-img,
.row-invalid .thumb-icon { opacity: 0.55; }

/* 信息 */
.row-info {
  min-width: 0;
}
.row-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-sku {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}
.stock-warn {
  display: inline-block;
  margin-top: 5px;
  padding: 1px 8px;
  border-radius: var(--ds-r-pill, 999px);
  background: rgba(224, 64, 78, 0.10);
  color: var(--ds-red, #e0404e);
  font-size: 11px;
  font-weight: 600;
}

/* 单价 / 小计（蓝） */
.row-price,
.row-subtotal {
  color: var(--ds-price, #2d7ff9);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.row-price {
  font-size: 14px;
}
.row-subtotal {
  font-size: 16px;
  font-weight: 800;
  text-align: right;
}
.price-symbol {
  font-size: 11px;
  font-weight: 700;
}

/* 数量步进器 */
.row-qty {
  width: 132px;
}

/* 删除 */
.row-remove {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ds-text-3, #98a1af);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  justify-self: end;
}
.row-remove:hover {
  color: var(--ds-red, #e0404e);
  background: rgba(224, 64, 78, 0.08);
  border-color: rgba(224, 64, 78, 0.2);
}

/* ====== 底部结算栏 ====== */
.checkout-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 16px 22px;
  position: sticky;
  bottom: 12px;
  z-index: 10;
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.checkout-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.check-all {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: transparent;
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text-2, #5a6474);
  cursor: pointer;
  padding: 0;
}
.check-all.checked { color: var(--ds-text, #1e2632); }
.checkout-info {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
.count-num {
  color: var(--ds-primary, #2d7ff9);
  font-size: 15px;
}
.checkout-right {
  display: flex;
  align-items: center;
  gap: 18px;
}
.stock-hint {
  font-size: 12px;
  color: var(--ds-red, #e0404e);
  max-width: 180px;
  text-align: right;
}
.checkout-total {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.total-label {
  font-size: 14px;
  color: var(--ds-text-2, #5a6474);
}
.total-value {
  font-size: 28px;
  font-weight: 800;
  color: var(--ds-price, #2d7ff9);
  letter-spacing: -0.5px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.total-symbol {
  font-size: 15px;
  font-weight: 700;
}
.checkout-btn {
  height: 48px;
  padding: 0 34px;
  border: none;
  border-radius: var(--ds-r, 12px);
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.checkout-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(45, 127, 249, 0.32);
}
.checkout-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ====== 空状态按钮 ====== */
.go-shop-btn {
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 9px 24px;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.go-shop-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

/* ====== 响应式 ====== */
@media (max-width: 900px) {
  .cart-row {
    grid-template-columns: 24px 64px minmax(0, 1fr) 110px 36px;
    grid-template-rows: auto auto;
    gap: 12px 16px;
  }
  .row-thumb {
    width: 64px;
    height: 64px;
  }
  .row-price {
    grid-column: 3;
    grid-row: 2;
  }
  .row-qty {
    grid-column: 4;
    grid-row: 2;
    width: 120px;
  }
  .row-subtotal {
    grid-column: 4;
    grid-row: 1;
  }
  .row-remove {
    grid-column: 5;
    grid-row: 1;
  }
  .checkout-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }
  .checkout-right {
    justify-content: space-between;
  }
}
@media (max-width: 520px) {
  .checkout-right {
    flex-wrap: wrap;
  }
  .stock-hint {
    order: 3;
    max-width: 100%;
    text-align: left;
    flex-basis: 100%;
  }
  .total-value {
    font-size: 24px;
  }
}
</style>