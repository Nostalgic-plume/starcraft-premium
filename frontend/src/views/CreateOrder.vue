<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <h2 class="co-title">确认订单</h2>

    <n-spin :show="loading">
      <!-- 收货信息（地址簿占位选择） -->
      <div class="co-card">
        <div class="card-head">
          <SvgIcon name="location" :size="18" />
          <h3 class="card-title">收货信息</h3>
        </div>

        <div class="addr-picker">
          <!-- 选中摘要 / 未选择提示（点击展开地址列表） -->
          <button type="button" class="addr-summary" @click="addrOpen = !addrOpen">
            <div class="addr-main">
              <template v-if="selectedAddress">
                <div class="addr-line1">
                  <strong class="addr-name">{{ selectedAddress.receiverName }}</strong>
                  <span class="addr-phone">{{ selectedAddress.receiverPhone }}</span>
                  <span v-if="selectedAddress.isDefault" class="addr-tag">默认</span>
                </div>
                <div class="addr-line2">{{ selectedAddress.receiverAddress }}</div>
              </template>
              <div v-else class="addr-placeholder">
                <SvgIcon name="location" :size="15" class="addr-placeholder-icon" />
                <span>请选择收货地址</span>
              </div>
            </div>
            <SvgIcon
              class="addr-arrow"
              :class="{ open: addrOpen }"
              name="chevron-down"
              :size="16"
            />
          </button>

          <!-- 地址列表（单选展开/收起） -->
          <transition name="addr-slide">
            <div v-if="addrOpen" class="addr-list">
              <button
                v-for="addr in addressList"
                :key="addr.id"
                type="button"
                class="addr-option"
                :class="{ active: selectedAddress?.id === addr.id }"
                @click="selectAddress(addr)"
              >
                <span
                  class="addr-radio"
                  :class="{ checked: selectedAddress?.id === addr.id }"
                >
                  <SvgIcon v-if="selectedAddress?.id === addr.id" name="check" :size="12" />
                </span>
                <span class="addr-opt-body">
                  <span class="addr-opt-line">
                    <strong>{{ addr.receiverName }}</strong>
                    <span class="addr-phone">{{ addr.receiverPhone }}</span>
                    <span v-if="addr.isDefault" class="addr-tag">默认</span>
                  </span>
                  <span class="addr-opt-addr">{{ addr.receiverAddress }}</span>
                </span>
              </button>
            </div>
          </transition>
        </div>
        <!-- TODO(P0): 后端地址簿接口未就绪，以上 addressList 为占位数据，对接后替换为真实地址列表 -->
      </div>

      <!-- 商品清单 -->
      <div class="co-card">
        <div class="card-head">
          <SvgIcon name="box" :size="18" />
          <h3 class="card-title">商品清单</h3>
          <span v-if="orderItems.length" class="card-sub">共 {{ orderItems.length }} 件</span>
        </div>

        <div v-if="orderItems.length" class="goods-list">
          <div v-for="item in orderItems" :key="item.skuId" class="goods-row">
            <div class="goods-thumb">
              <img v-if="item.productImage" :src="item.productImage" alt="" loading="lazy" />
              <SvgIcon v-else name="box" :size="22" class="thumb-icon" />
            </div>
            <div class="goods-info">
              <p class="goods-name">{{ item.productName }}</p>
              <p class="goods-sku">{{ item.skuName }}</p>
            </div>
            <div class="goods-qty">× {{ item.quantity }}</div>
            <div class="goods-price">
              <span class="price-symbol">¥</span>{{ formatPrice(item.price) }}
            </div>
          </div>
        </div>

        <!-- 备注 -->
        <div class="remark-row">
          <input
            v-model="remark"
            class="remark-input"
            type="text"
            maxlength="100"
            placeholder="订单备注（选填）"
          />
        </div>
      </div>

      <!-- 金额汇总 -->
      <div class="co-card summary-card">
        <div class="sum-row">
          <span>商品总额</span>
          <span class="sum-val">¥{{ formatPrice(totalAmount) }}</span>
        </div>
        <div class="sum-row">
          <span>运费</span>
          <span class="sum-val sum-free">免运费</span>
        </div>

        <!-- 优惠券满减占位（P0） -->
        <button type="button" class="sum-row sum-coupon" @click="onCouponClick">
          <span>优惠券</span>
          <span class="sum-coupon-right">
            <span class="sum-val coupon-empty">暂无可用优惠券</span>
            <SvgIcon name="chevron-right" :size="14" class="coupon-arrow" />
          </span>
        </button>
        <!-- TODO(P0): 优惠券接口未就绪，满减金额恒为 0；对接后按所选券计算 discountAmount -->

        <div class="sum-divider"></div>

        <div class="sum-row sum-payable">
          <span>应付总额</span>
          <span class="payable-value">
            <span class="price-symbol">¥</span>{{ formatPrice(payableTotal) }}
          </span>
        </div>
      </div>
    </n-spin>

    <!-- 提交栏（粘底） -->
    <div class="submit-bar">
      <div class="submit-total">
        <span class="submit-label">应付</span>
        <span class="submit-price">
          <span class="price-symbol">¥</span>{{ formatPrice(payableTotal) }}
        </span>
      </div>
      <button
        type="button"
        class="submit-btn"
        :disabled="submitting"
        @click="submitOrder"
      >
        {{ submitting ? '正在提交...' : '提交订单' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NSpin, useMessage } from 'naive-ui'
import { orderApi } from '@/api/order'
import { useCartStore } from '@/stores/cart'
import BackBar from '@/components/BackBar.vue'
import SvgIcon from '@/components/SvgIcon.vue'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const cartStore = useCartStore()

const loading = ref(true)
const submitting = ref(false)
const remark = ref('')

/** 地址簿占位数据（P0）——后端地址接口未就绪 */
interface AddressVO {
  id: string
  receiverName: string
  receiverPhone: string
  receiverAddress: string
  isDefault: boolean
}

// TODO(P0): 对接后端地址簿接口后，改由 API 拉取真实地址列表
const addressList: AddressVO[] = [
  { id: 'addr-1', receiverName: '张三', receiverPhone: '13800138000', receiverAddress: '北京市朝阳区望京街道 1 号院 8 号楼 302 室', isDefault: true },
  { id: 'addr-2', receiverName: '李四', receiverPhone: '13900139000', receiverAddress: '上海市浦东新区张江高科园区 88 号 1001 室', isDefault: false },
  { id: 'addr-3', receiverName: '王五', receiverPhone: '13700137000', receiverAddress: '广州市天河区体育西路 12 号 501 室', isDefault: false }
]

const addrOpen = ref(false)
const selectedAddress = ref<AddressVO | null>(null)

function selectAddress(addr: AddressVO) {
  selectedAddress.value = addr
  addrOpen.value = false
}

/** 确认订单商品行 */
interface OrderLineItem {
  skuId: string
  quantity: number
  productName: string
  skuName: string
  price: number
  productImage: string
}

const orderItems = ref<OrderLineItem[]>([])

const totalAmount = computed(() =>
  orderItems.value.reduce((sum, i) => sum + (i.price || 0) * i.quantity, 0)
)

/** 应付总额：运费 0（免运费）+ 优惠券 0（P0 占位） */
const payableTotal = computed(() => totalAmount.value)

function formatPrice(val: number | undefined | null): string {
  const n = Number(val || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/** 解析 items query：`${skuId}-${qty}` 逗号分隔。skuId 保持字符串（雪花ID不丢精度），仅 qty 转 Number */
function parseItemsQuery(raw: string): Array<{ skuId: string; quantity: number }> {
  return raw
    .split(',')
    .filter(Boolean)
    .map(pair => {
      const idx = pair.indexOf('-')
      if (idx < 0) return { skuId: pair, quantity: 1 }
      const skuId = pair.slice(0, idx)
      const qty = Number(pair.slice(idx + 1))
      return { skuId, quantity: Number.isFinite(qty) && qty > 0 ? qty : 1 }
    })
}

/**
 * 商品来源加载
 * ① 修复：route.query.items 存在（立即购买 / 购物车结算）时优先解析 query，
 *    不再被 cartStore.selectedItems() 截胡覆盖（旧逻辑会丢失立即购买商品）。
 *    购物车结算的 items 与立即购买同格式，但勾选项携带完整展示信息，按 skuId 匹配补全；
 *    立即购买商品不在购物车时，回退使用 query 中的名称/价格/图片。
 * ② 修复：skuId 保持字符串传递，仅 quantity 转 Number。
 */
function loadItems() {
  const rawItems = typeof route.query.items === 'string' ? route.query.items : ''
  const hasItemsQuery = rawItems.trim().length > 0

  if (hasItemsQuery) {
    const parsed = parseItemsQuery(rawItems)
    const selected = cartStore.selectedItems()
    orderItems.value = parsed.map(p => {
      const cart = selected.find(s => String(s.skuId) === p.skuId)
      if (cart) {
        return {
          skuId: p.skuId,
          quantity: p.quantity,
          productName: cart.productName,
          skuName: cart.skuName,
          price: cart.price,
          productImage: cart.productImage
        }
      }
      return {
        skuId: p.skuId,
        quantity: p.quantity,
        productName: String(route.query.productName || '商品'),
        skuName: String(route.query.skuName || '默认规格'),
        price: Number(route.query.price) || 0,
        productImage: String(route.query.image || '')
      }
    })
    return
  }

  // 无 items query（直接进入本页兜底）：使用购物车勾选项
  orderItems.value = cartStore.selectedItems().map(i => ({
    skuId: String(i.skuId),
    quantity: i.quantity,
    productName: i.productName,
    skuName: i.skuName,
    price: i.price,
    productImage: i.productImage
  }))
}

/** 优惠券入口占位（P0） */
function onCouponClick() {
  // TODO(P0): 优惠券选择弹层未实现，占位提示
  message.info('优惠券功能开发中，敬请期待')
}

/* ====== 异步下单：getToken → create → 轮询 /order/status/{processingId} ====== */
const MAX_POLL_TIMES = 20
const POLL_INTERVAL = 1500

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/**
 * 轮询异步落单状态，返回订单ID（字符串）。
 * 状态值：PROCESSING 继续轮询 | 订单ID 成功 | FAILED:原因 业务失败 | EXPIRED 键过期
 */
async function waitForOrderId(processingId: string): Promise<string> {
  for (let i = 0; i < MAX_POLL_TIMES; i++) {
    await sleep(POLL_INTERVAL)
    let status: string | undefined
    try {
      const res = await orderApi.getStatus(processingId)
      status = res.data?.status
    } catch {
      // 单次轮询失败（网络抖动）不立即判死，继续轮询
      continue
    }
    if (!status || status === 'PROCESSING') continue
    if (status.startsWith('FAILED')) {
      const reason = status.slice(status.indexOf(':') + 1) || '下单失败'
      throw new Error(reason)
    }
    if (status === 'EXPIRED') {
      throw new Error('下单处理超时，请稍后重试')
    }
    // 其余情况：status 即订单ID字符串（雪花ID，前端保持字符串不丢精度）
    return status
  }
  throw new Error('下单处理超时，请稍后到订单列表查看')
}

async function submitOrder() {
  if (orderItems.value.length === 0) {
    message.warning('暂无商品可提交')
    return
  }
  if (!selectedAddress.value) {
    message.warning('请选择收货地址')
    return
  }
  submitting.value = true
  try {
    // ① 下单防重令牌
    const tokenRes = await orderApi.getToken()
    const token = tokenRes.data?.token
    if (!token) {
      message.error('获取订单令牌失败，请重试')
      return
    }

    // ② 异步创建订单，返回 processingId
    const createRes = await orderApi.create({
      token,
      receiverName: selectedAddress.value.receiverName,
      receiverPhone: selectedAddress.value.receiverPhone,
      receiverAddress: selectedAddress.value.receiverAddress,
      remark: remark.value,
      items: orderItems.value.map(i => ({ skuId: i.skuId, quantity: i.quantity }))
    })
    const processingId = createRes.data
    if (!processingId) {
      throw new Error('下单请求未返回处理编号，请重试')
    }

    // ③ 轮询直到拿到订单ID
    const orderId = await waitForOrderId(processingId)
    message.success('下单成功！请尽快支付')
    router.push('/order/' + orderId)
  } catch (e) {
    const err = e as Error
    message.error(err?.message || (e as { msg?: string })?.msg || '下单失败，请重试')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  // 购物车数据未加载（如刷新本页）时先拉取一次，便于购物车结算补全展示
  if (cartStore.items.length === 0) {
    try {
      await cartStore.fetchList()
    } catch {
      /* 错误提示由拦截器统一处理 */
    }
  }
  loadItems()
  loading.value = false
})
</script>

<style scoped>
.co-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0 0 20px;
}

/* ====== 卡片 ====== */
.co-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 20px 22px;
  margin-bottom: 16px;
  transition: all 0.25s ease;
}
.co-card:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  color: var(--ds-primary, #2d7ff9);
}
.card-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 0;
}
.card-sub {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-left: auto;
}

/* ====== 收货信息（地址簿占位） ====== */
.addr-summary {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border: 1.5px solid var(--ds-line-2, #d8dce4);
  border-radius: var(--ds-r, 12px);
  background: var(--ds-card, #fff);
  cursor: pointer;
  text-align: left;
  transition: all 0.2s ease;
}
.addr-summary:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
}
.addr-main {
  flex: 1;
  min-width: 0;
}
.addr-line1 {
  display: flex;
  align-items: center;
  gap: 10px;
}
.addr-name {
  font-size: 15px;
  color: var(--ds-text, #1e2632);
}
.addr-phone {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  font-variant-numeric: tabular-nums;
}
.addr-tag {
  padding: 1px 8px;
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  color: var(--ds-primary, #2d7ff9);
  font-size: 11px;
  font-weight: 600;
}
.addr-line2 {
  margin-top: 6px;
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  line-height: 1.6;
}
.addr-placeholder {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ds-text-3, #98a1af);
  font-size: 14px;
}
.addr-placeholder-icon {
  color: var(--ds-primary, #2d7ff9);
}
.addr-arrow {
  color: var(--ds-text-3, #98a1af);
  flex-shrink: 0;
  transition: transform 0.25s ease;
}
.addr-arrow.open {
  transform: rotate(180deg);
  color: var(--ds-primary, #2d7ff9);
}

/* 地址列表 */
.addr-list {
  margin-top: 10px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
}
.addr-option {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 16px;
  border: none;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
  background: var(--ds-card, #fff);
  cursor: pointer;
  text-align: left;
  transition: background 0.2s ease;
}
.addr-option:last-child {
  border-bottom: none;
}
.addr-option:hover {
  background: var(--ds-bg, #f6f7fa);
}
.addr-option.active {
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.addr-radio {
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 50%;
  border: 1.5px solid var(--ds-line-2, #d8dce4);
  background: var(--ds-card, #fff);
  color: transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.2s;
}
.addr-radio.checked {
  background: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
  color: #fff;
}
.addr-opt-body {
  flex: 1;
  min-width: 0;
}
.addr-opt-line {
  display: flex;
  align-items: center;
  gap: 10px;
}
.addr-opt-line strong {
  font-size: 14px;
  color: var(--ds-text, #1e2632);
}
.addr-opt-addr {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  line-height: 1.5;
}

/* 地址列表展开收起动画 */
.addr-slide-enter-active { transition: all 0.25s ease; }
.addr-slide-leave-active { transition: all 0.2s ease; }
.addr-slide-enter-from { opacity: 0; transform: translateY(-6px); }
.addr-slide-leave-to { opacity: 0; transform: translateY(-6px); }

/* ====== 商品清单 ====== */
.goods-list {
  display: flex;
  flex-direction: column;
}
.goods-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.goods-row:last-child {
  border-bottom: none;
}
.goods-thumb {
  width: 60px;
  height: 60px;
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
  flex-shrink: 0;
  background: var(--ds-bg-2, #eef0f4);
  display: flex;
  align-items: center;
  justify-content: center;
}
.goods-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.thumb-icon {
  color: var(--ds-primary, #2d7ff9);
  opacity: 0.5;
}
.goods-info {
  flex: 1;
  min-width: 0;
}
.goods-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 0 0 4px;
}
.goods-sku {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}
.goods-qty {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
  white-space: nowrap;
}
.goods-price {
  width: 96px;
  text-align: right;
  color: var(--ds-price, #2d7ff9);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.price-symbol {
  font-size: 11px;
  font-weight: 700;
}

/* 备注 */
.remark-row {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--ds-line, #e7e9ee);
}
.remark-input {
  width: 100%;
  height: 40px;
  padding: 0 14px;
  border: 1px solid var(--ds-line-2, #d8dce4);
  border-radius: var(--ds-r-sm, 9px);
  background: var(--ds-card, #fff);
  color: var(--ds-text, #1e2632);
  font-size: 13px;
  outline: none;
  transition: all 0.2s ease;
}
.remark-input::placeholder {
  color: var(--ds-text-3, #98a1af);
}
.remark-input:focus {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: 0 0 0 3px var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}

/* ====== 金额汇总 ====== */
.summary-card {
  text-align: right;
}
.sum-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  color: var(--ds-text-2, #5a6474);
  padding: 7px 0;
}
.sum-val {
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  font-variant-numeric: tabular-nums;
}
.sum-free {
  color: var(--ds-green, #12a77d);
  font-weight: 600;
}
.sum-coupon {
  width: 100%;
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 7px 0;
}
.sum-coupon:hover .sum-coupon-right {
  color: var(--ds-primary, #2d7ff9);
}
.sum-coupon-right {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ds-text-3, #98a1af);
  transition: color 0.2s ease;
}
.coupon-empty {
  font-size: 13px;
  font-weight: 400;
}
.coupon-arrow {
  color: inherit;
}
.sum-divider {
  border-top: 1px solid var(--ds-line, #e7e9ee);
  margin: 8px 0;
}
.sum-payable {
  font-size: 15px;
  color: var(--ds-text, #1e2632);
}
.payable-value {
  font-size: 28px;
  font-weight: 800;
  color: var(--ds-price, #2d7ff9);
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.payable-value .price-symbol {
  font-size: 15px;
}

/* ====== 提交栏（粘底） ====== */
.submit-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  position: sticky;
  bottom: 12px;
  z-index: 10;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 14px 22px;
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.submit-total {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.submit-label {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
}
.submit-price {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-price, #2d7ff9);
  letter-spacing: -0.5px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.submit-price .price-symbol {
  font-size: 13px;
}
.submit-btn {
  height: 48px;
  padding: 0 40px;
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
.submit-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(45, 127, 249, 0.32);
}
.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 560px) {
  .submit-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .submit-btn {
    width: 100%;
  }
}
</style>
