<template>
  <div class="page-container fade-in" ref="pageRef">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 面包屑 -->
    <nav v-if="product" class="breadcrumb">
      <router-link to="/home" class="crumb-link">首页</router-link>
      <SvgIcon name="chevron-right" :size="14" class="crumb-sep" />
      <span class="crumb-link">{{ product.categoryName || '分类' }}</span>
      <SvgIcon name="chevron-right" :size="14" class="crumb-sep" />
      <span class="crumb-current">{{ product.name }}</span>
    </nav>

    <n-spin :show="loading" class="detail-spin">
      <template v-if="product">
        <!-- ====== 左图右信息 ====== -->
        <div class="detail-hero">
          <!-- 左侧：图片画廊 -->
          <div class="gallery">
            <div class="gallery-main">
              <img
                v-if="activeImageUrl"
                :src="activeImageUrl"
                class="main-img"
                :alt="product.name"
              />
              <div v-else class="main-placeholder">
                <div class="ph-ring">
                  <SvgIcon name="box" :size="52" class="ph-icon" />
                </div>
                <span class="ph-text">商品图片即将上架</span>
              </div>
              <StatusTag v-if="product.status === 1" type="on-sale" class="on-sale-badge" />
            </div>

            <div v-if="product.images && product.images.length > 1" class="thumb-strip">
              <button
                v-for="(img, i) in product.images"
                :key="i"
                type="button"
                class="thumb"
                :class="{ active: activeImage === i }"
                :aria-label="'查看第 ' + (i + 1) + ' 张图片'"
                @click="activeImage = i"
              >
                <img :src="img" class="thumb-img" alt="" loading="lazy" />
              </button>
            </div>
          </div>

          <!-- 右侧：信息区 -->
          <div class="meta">
            <h1 class="meta-title">{{ product.name }}</h1>
            <div class="meta-sub">
              <span class="meta-rating">
                <span class="stars">
                  <span v-for="i in 5" :key="i" class="star" :class="{ on: starOn(i, ratingAvg) }">
                    <svg viewBox="0 0 24 24" class="star-svg" aria-hidden="true"><path d="M12 2.6l2.9 5.88 6.5.94-4.7 4.58 1.11 6.47L12 17.42 6.19 20.47 7.3 14 2.6 9.42l6.5-.94z" /></svg>
                  </span>
                </span>
                <span class="rating-score">{{ ratingAvg }}</span>
              </span>
              <span class="meta-divider" />
              <span class="meta-sold">已售 {{ product.soldCount || 0 }}</span>
            </div>

            <!-- 价格卡 -->
            <div class="price-card">
              <div class="price-main">
                <span class="price-symbol">¥</span>
                <span class="price-value">{{ formatPrice(displayPrice) }}</span>
                <span v-if="originalPrice > 0" class="price-original">¥{{ formatPrice(originalPrice) }}</span>
                <span v-if="saveAmount > 0" class="save-tag">立省 ¥{{ formatPrice(saveAmount) }}</span>
              </div>
              <div class="price-meta">
                <span class="pm-item">库存 {{ selectedSku?.stock ?? product.totalStock ?? 0 }} 件</span>
                <span v-if="product.skuList && product.skuList.length" class="pm-item">{{ product.skuList.length }} 种规格可选</span>
              </div>
            </div>

            <!-- SKU 规格选择 -->
            <div v-if="product.skuList && product.skuList.length" class="sku-block">
              <div class="block-label">选择规格</div>
              <div class="sku-pills">
                <button
                  v-for="sku in product.skuList"
                  :key="sku.id"
                  type="button"
                  class="sku-pill"
                  :class="{ active: selectedSku && selectedSku.id === sku.id, soldout: sku.stock === 0 }"
                  :disabled="sku.stock === 0"
                  @click="selectSku(sku)"
                >
                  <span class="pill-name">{{ sku.name }}</span>
                  <span class="pill-price">¥{{ formatPrice(sku.price) }}</span>
                </button>
              </div>
            </div>

            <!-- 数量 + 操作 -->
            <div class="action-card">
              <div class="qty-row">
                <span class="qty-label">数量</span>
                <n-input-number
                  v-model:value="quantity"
                  :min="1"
                  :max="maxQty"
                  button-placement="both"
                  :update-value-on-input="false"
                  class="qty-input"
                />
              </div>
              <div v-if="product.status !== 1" class="off-shelf-tip">该商品已下架，暂不支持购买</div>
              <div class="action-buttons">
                <button type="button" class="btn-add-cart" :disabled="!canBuy" @click="addToCart">
                  <SvgIcon name="cart" :size="18" />
                  加入购物车
                </button>
                <button type="button" class="btn-buy-now" :disabled="!canBuy" @click="buyNow">
                  立即购买
                </button>
                <button
                  type="button"
                  class="btn-fav"
                  :class="{ active: isFav }"
                  :aria-label="isFav ? '取消收藏' : '收藏该商品'"
                  @click="toggleFav"
                >
                  <svg viewBox="0 0 24 24" class="fav-heart" :class="{ filled: isFav }" aria-hidden="true">
                    <path d="M12 20.5s-7.5-4.6-9.2-9.4C1.5 7 4 4.5 6.8 4.5c1.9 0 3.4.9 4.2 2.3.8-1.4 2.3-2.3 4.2-2.3 2.8 0 5.3 2.5 4 6.6-1.7 4.8-9.2 9.4-9.2 9.4Z" />
                  </svg>
                  <span>{{ isFav ? '已收藏' : '收藏' }}</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- ====== 底部面板 ====== -->
        <div class="detail-lower">
          <div class="lower-left">
            <!-- 商品详情 -->
            <section class="panel panel-detail">
              <h3 class="panel-title">
                <SvgIcon name="box" :size="16" class="panel-icon" />
                商品详情
              </h3>
              <div class="detail-html" v-html="product.detail || '暂无详情'" />
            </section>

            <!-- 价格走势 -->
            <section class="panel panel-trend">
              <h3 class="panel-title">
                <SvgIcon name="clock" :size="16" class="panel-icon" />
                价格走势
              </h3>
              <div ref="trendChartRef" class="trend-chart" />
              <p class="trend-note">基于同类商品价格区间模拟，仅供参考</p>
            </section>
          </div>

          <!-- 评价区（P0 占位） -->
          <section class="panel panel-review">
            <h3 class="panel-title">
              <SvgIcon name="star" :size="16" class="panel-icon" />
              用户评价
            </h3>

            <div class="review-summary">
              <div class="avg-block">
                <span class="avg-num">{{ ratingAvg }}</span>
                <div class="avg-stars stars">
                  <span v-for="i in 5" :key="i" class="star" :class="{ on: starOn(i, ratingAvg) }">
                    <svg viewBox="0 0 24 24" class="star-svg" aria-hidden="true"><path d="M12 2.6l2.9 5.88 6.5.94-4.7 4.58 1.11 6.47L12 17.42 6.19 20.47 7.3 14 2.6 9.42l6.5-.94z" /></svg>
                  </span>
                </div>
                <span class="avg-count">共 {{ reviewCount }} 条评价</span>
              </div>
              <div class="rate-block">
                <span class="rate-num">{{ goodRate }}%</span>
                <span class="rate-label">好评率</span>
              </div>
            </div>

            <div class="review-list">
              <div v-for="r in reviews" :key="r.id" class="review-item">
                <div class="review-avatar">
                  <SvgIcon name="user" :size="18" />
                </div>
                <div class="review-main">
                  <div class="review-head">
                    <span class="review-user">{{ r.user }}</span>
                    <span class="review-time">{{ r.time }}</span>
                  </div>
                  <div class="stars">
                    <span v-for="i in 5" :key="i" class="star" :class="{ on: starOn(i, r.rating) }">
                      <svg viewBox="0 0 24 24" class="star-svg" aria-hidden="true"><path d="M12 2.6l2.9 5.88 6.5.94-4.7 4.58 1.11 6.47L12 17.42 6.19 20.47 7.3 14 2.6 9.42l6.5-.94z" /></svg>
                    </span>
                  </div>
                  <p class="review-content">{{ r.content }}</p>
                  <span class="review-sku">规格：{{ r.sku }}</span>
                </div>
              </div>
            </div>

            <div class="review-action-row">
              <button type="button" class="review-action" @click="goWriteReview">
                <SvgIcon name="star" :size="15" />
                去评价
              </button>
            </div>
          </section>
        </div>
      </template>

      <EmptyState v-else-if="!loading" text="商品不存在" description="该商品可能已下架或链接有误">
        <button type="button" class="empty-btn" @click="router.push('/home')">返回首页</button>
      </EmptyState>
    </n-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NInputNumber, NSpin, useMessage } from 'naive-ui'
import * as echarts from 'echarts'
import gsap from 'gsap'
import { productApi } from '@/api/product'
import { favoriteApi } from '@/api/favorite'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import BackBar from '@/components/BackBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { CHART_LINE, CHART_TEXT, CHART_ACCENT } from '@shared/js/echarts-theme'
import { startViewTimer, trackAddCart, trackFavorite } from '@/utils/behaviorTracker'
import type { ProductVO, SkuVO } from '@/api/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const cartStore = useCartStore()
const userStore = useUserStore()

const product = ref<ProductVO | null>(null)
const loading = ref(true)
const selectedSku = ref<SkuVO | null>(null)
const quantity = ref<number | null>(1)
const activeImage = ref(0)

const trendChartRef = ref<HTMLDivElement | null>(null)
let trendChart: echarts.ECharts | null = null
const pageRef = ref<HTMLElement | null>(null)
let ctx: gsap.Context | null = null

/**
 * 浏览行为上报回调
 *
 * 进入详情页开始计时，组件卸载时上报停留时长。详情页浏览是用户画像最主要的信号来源，
 * 停留时长用于服务端识别「误点即走」的无效浏览。
 */
let reportView: (() => void) | null = null

/* ====== 收藏（后端接口，userId 由服务端会话注入） ====== */
const isFav = ref(false)

/** 拉取收藏状态：未登录 / 接口异常时按未收藏处理，不阻断详情页渲染 */
async function loadFavState() {
  if (!product.value) return
  try {
    const res = await favoriteApi.check(product.value.id)
    isFav.value = res.data === true
  } catch {
    isFav.value = false
  }
}

async function toggleFav() {
  if (!product.value) return
  const id = product.value.id
  const nowFav = !isFav.value
  try {
    if (nowFav) {
      await favoriteApi.add(id)
      // 收藏行为埋点：仅收藏时上报，取消收藏不作为正向兴趣信号
      trackFavorite(id)
    } else {
      await favoriteApi.remove(id)
    }
    isFav.value = nowFav
    message.success(nowFav ? '已收藏' : '已取消收藏')
  } catch {
    // 未登录 / 网络异常：全局拦截器已提示，保持原状态
  }
}

/* ====== 价格 / 规格 ====== */
const displayPrice = computed(() => selectedSku.value?.price ?? product.value?.minPrice ?? 0)

const originalPrice = computed(() => {
  const sku = selectedSku.value
  if (sku && sku.originalPrice > sku.price) return sku.originalPrice
  const p = product.value
  if (p && p.maxPrice > displayPrice.value) return p.maxPrice
  return 0
})

const saveAmount = computed(() => {
  const d = originalPrice.value - displayPrice.value
  return d > 0 ? Math.round(d * 100) / 100 : 0
})

const maxQty = computed(() => Math.max(1, selectedSku.value?.stock ?? 99))

// 已下架商品（status≠1）或所选规格无库存均不可购买：前者后端下单必被拒（"商品已下架"），
// 提前在前端置灰，避免用户点下去才看到报错
const canBuy = computed(
  () => product.value?.status === 1 && !!selectedSku.value && selectedSku.value.stock > 0
)

const activeImageUrl = computed(() => {
  const imgs = product.value?.images || []
  return imgs[activeImage.value] || product.value?.mainImage || ''
})

function selectSku(sku: SkuVO) {
  selectedSku.value = sku
  quantity.value = 1
}

/** 价格展示：整数省略小数，否则保留两位 */
function formatPrice(val: number | undefined | null): string {
  const n = Number(val || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/** 第 i 颗星（1 起）是否点亮 */
function starOn(i: number, rating: number): boolean {
  return i <= Math.round(rating)
}

/* ====== 评价区（P0 占位数据，后端未就绪） ====== */
interface ReviewItem {
  id: number
  user: string
  rating: number
  time: string
  content: string
  sku: string
}

const ratingAvg = 4.9
const goodRate = 98
const reviewCount = 128

const reviews: ReviewItem[] = [
  { id: 1, user: '小***星', rating: 5, time: '2026-07-12', content: '质量很好，和描述一致，快递也很快，下次还会回购。', sku: '标配' },
  { id: 2, user: '阿***K', rating: 4, time: '2026-07-05', content: '整体不错，性价比高，就是颜色比图片稍微深一点点。', sku: '高配' },
  { id: 3, user: 'E***r', rating: 5, time: '2026-06-28', content: '客服很有耐心，物流给力，满意的一次购物体验。', sku: '标配' }
]

function goWriteReview() {
  message.info('评价功能即将上线，敬请期待')
}

/* ====== 数据加载 ====== */
async function loadProduct() {
  loading.value = true
  try {
    const res = await productApi.detail(String(route.params.id))
    product.value = res.data
    // 浏览行为埋点：商品确认存在后才开始计时（加载失败不产生浏览样本）
    if (product.value && !reportView) {
      reportView = startViewTimer(product.value.id)
    }
    const list = res.data?.skuList || []
    if (list.length) {
      selectedSku.value = list.find(s => s.stock > 0) || list[0]
    }
    // 收藏状态单独拉取：失败只影响红心，不影响详情渲染
    loadFavState()
    await nextTick()
    initTrendChart()
    initAnimations()
  } catch (e) {
    console.error('加载商品详情失败:', e)
    product.value = null
  } finally {
    loading.value = false
  }
}

/* ====== 加购 / 立即购买 ====== */
async function addToCart() {
  if (!userStore.isLoggedIn()) {
    router.push('/login')
    return
  }
  if (!canBuy.value || !selectedSku.value) {
    message.warning('请先选择有库存的规格')
    return
  }
  const qty = quantity.value ?? 1
  const skuId = selectedSku.value.id
  const existing = cartStore.items.find(i => i.skuId === skuId)
  try {
    if (existing) {
      await cartStore.updateQuantity(skuId, existing.quantity + qty)
      message.success('已增加购物车数量')
    } else {
      await cartStore.addItem({ skuId, quantity: qty })
      message.success('已加入购物车')
    }
    // 加购行为埋点（静默旁路，失败不影响主流程）
    if (product.value) {
      trackAddCart(product.value.id)
    }
  } catch {
    /* 错误提示由 axios 拦截器统一处理 */
  }
}

function buyNow() {
  if (!userStore.isLoggedIn()) {
    router.push('/login')
    return
  }
  if (!canBuy.value || !selectedSku.value) {
    message.warning('请先选择有库存的规格')
    return
  }
  const qty = quantity.value ?? 1
  const sku = selectedSku.value
  router.push({
    path: '/order/create',
    query: {
      items: `${sku.id}-${qty}`,
      productName: product.value?.name || '',
      skuName: sku.name,
      price: String(sku.price),
      image: product.value?.mainImage || '',
      // 供下单成功后的购买行为埋点使用：订单行只携带 skuId，前端无法由 sku 反推商品
      productId: product.value?.id || ''
    }
  })
}

/* ====== ECharts 价格走势图（14 日模拟 + 均价参考线） ====== */
function initTrendChart() {
  if (!trendChartRef.value || !product.value) return
  trendChart = echarts.init(trendChartRef.value)

  const minP = Number(product.value.minPrice) || 100
  const maxP = Number(product.value.maxPrice) || minP * 1.2
  const mid = (minP + maxP) / 2
  const now = new Date()
  const days = 14

  const dates: string[] = []
  const trendData: number[] = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    dates.push(`${d.getMonth() + 1}/${d.getDate()}`)
    // 带随机波动的模拟价格，围绕 mid 上下 15%
    const fluctuation = (Math.random() - 0.5) * mid * 0.3
    trendData.push(Math.round((mid + fluctuation) * 100) / 100)
  }

  trendChart.setOption({
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v: unknown) => '¥' + Number(v || 0).toFixed(2)
    },
    grid: { left: 48, right: 16, top: 20, bottom: 24 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { color: CHART_TEXT, fontSize: 10 },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      min: Math.floor(Math.min(...trendData) * 0.95),
      max: Math.ceil(Math.max(...trendData) * 1.05),
      axisLabel: { color: CHART_TEXT, fontSize: 10, formatter: '¥{value}' },
      splitLine: { lineStyle: { color: '#eef0f4', type: 'dashed' } }
    },
    series: [{
      type: 'line',
      data: trendData,
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2.5, color: CHART_LINE },
      itemStyle: { color: CHART_LINE },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(45,127,249,0.16)' },
          { offset: 1, color: 'rgba(45,127,249,0.01)' }
        ])
      },
      markLine: {
        silent: true,
        data: [{ yAxis: mid, label: { formatter: '均价 ¥' + mid.toFixed(2), color: CHART_ACCENT, fontSize: 11 } }],
        lineStyle: { color: CHART_ACCENT, type: 'dashed', width: 1 }
      }
    }]
  })
}

function handleResize() {
  trendChart?.resize()
}

/* ====== GSAP 入场动画 ====== */
function initAnimations() {
  if (!pageRef.value) return
  ctx = gsap.context(() => {
    gsap.from('.gallery-main', { y: 16, opacity: 0, duration: 0.5, ease: 'power2.out' })
    gsap.from('.thumb', { y: 12, opacity: 0, duration: 0.35, stagger: 0.06, ease: 'power2.out' })
    gsap.from('.meta-title', { y: 12, opacity: 0, duration: 0.4, ease: 'power2.out' })
    gsap.from('.meta-sub', { y: 10, opacity: 0, duration: 0.35, ease: 'power2.out' })
    gsap.from('.price-card', { y: 14, opacity: 0, duration: 0.45, ease: 'power2.out' })
    gsap.from('.sku-block', { y: 14, opacity: 0, duration: 0.4, ease: 'power2.out' })
    gsap.from('.action-card', { y: 14, opacity: 0, duration: 0.45, ease: 'power2.out' })
    gsap.from('.panel', { y: 20, opacity: 0, duration: 0.45, stagger: 0.1, ease: 'power2.out' })
  }, pageRef.value)
}

/* ====== 生命周期 ====== */
onMounted(() => {
  loadProduct()
  window.addEventListener('resize', handleResize)
})

// 同路由切换商品（/product/1 → /product/2）时组件实例被复用、不会触发卸载：
// 需手动结算上一个商品的浏览，再由 loadProduct 为新商品重新计时
watch(() => route.params.id, () => {
  reportView?.()
  reportView = null
  loadProduct()
})

onUnmounted(() => {
  // 结算浏览行为并上报停留时长（内部有重复上报保护，与路由切换的结算不会叠加）
  reportView?.()
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  ctx?.revert()
})
</script>

<style scoped>
.detail-spin {
  min-height: 360px;
}

/* ====== 面包屑 ====== */
.breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 18px;
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
.crumb-link { transition: color 0.2s; }
.crumb-link:hover { color: var(--ds-primary, #2d7ff9); }
.crumb-sep { flex-shrink: 0; }
.crumb-current {
  color: var(--ds-text, #1e2632);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}

/* ====== 左图右信息 ====== */
.detail-hero {
  display: flex;
  gap: 40px;
  margin-bottom: 28px;
}

/* 画廊 */
.gallery {
  flex: 0 0 440px;
}
.gallery-main {
  position: relative;
  border-radius: var(--ds-r-lg, 16px);
  overflow: hidden;
  background: linear-gradient(135deg, var(--ds-bg-2, #eef0f4), var(--ds-bg, #f6f7fa));
  border: 1px solid var(--ds-line, #e7e9ee);
}
.main-img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  display: block;
}
.main-placeholder {
  width: 100%;
  aspect-ratio: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
}
.ph-ring {
  width: 112px;
  height: 112px;
  border-radius: 50%;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
  display: flex;
  align-items: center;
  justify-content: center;
}
.ph-icon {
  color: var(--ds-primary, #2d7ff9);
  opacity: 0.55;
}
.ph-text {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.on-sale-badge {
  position: absolute;
  top: 14px;
  left: 14px;
}
.thumb-strip {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
.thumb {
  width: 64px;
  height: 64px;
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
  cursor: pointer;
  border: 2px solid transparent;
  padding: 0;
  background: var(--ds-bg-2, #eef0f4);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.thumb:hover { border-color: var(--ds-text-3, #98a1af); }
.thumb.active {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: 0 0 0 3px var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 信息区 */
.meta {
  flex: 1;
  min-width: 0;
}
.meta-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  line-height: 1.3;
  margin: 0 0 10px;
  letter-spacing: -0.3px;
}
.meta-sub {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}
.meta-rating {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.rating-score {
  font-size: 14px;
  font-weight: 700;
  color: var(--ds-text-2, #5a6474);
}
.meta-divider {
  width: 1px;
  height: 14px;
  background: var(--ds-line-2, #d8dce4);
}
.meta-sold {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}

/* 星级 */
.stars {
  display: inline-flex;
  gap: 2px;
}
.star {
  display: inline-flex;
  color: var(--ds-line-2, #d8dce4);
  transition: color 0.2s;
}
.star.on {
  color: var(--ds-primary, #2d7ff9);
}
.star-svg {
  width: 15px;
  height: 15px;
  fill: currentColor;
}

/* 价格卡 */
.price-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 18px 20px;
  margin-bottom: 20px;
}
.price-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.price-symbol {
  font-size: 18px;
  font-weight: 700;
  color: var(--ds-price, #2d7ff9);
}
.price-value {
  font-size: 34px;
  font-weight: 800;
  color: var(--ds-price, #2d7ff9);
  line-height: 1;
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
}
.price-original {
  font-size: 15px;
  color: var(--ds-text-3, #98a1af);
  text-decoration: line-through;
}
.save-tag {
  padding: 2px 10px;
  border-radius: var(--ds-r-pill, 999px);
  background: rgba(18, 167, 125, 0.10);
  color: var(--ds-green, #12a77d);
  font-size: 12px;
  font-weight: 700;
}
.price-meta {
  display: flex;
  gap: 18px;
  margin-top: 10px;
}
.pm-item {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

/* SKU 选择 */
.sku-block {
  margin-bottom: 20px;
}
.block-label {
  font-size: 14px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin-bottom: 10px;
}
.sku-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.sku-pill {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 88px;
  padding: 9px 18px;
  border: 1.5px solid var(--ds-line-2, #d8dce4);
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-card, #fff);
  cursor: pointer;
  transition: all 0.2s;
}
.sku-pill:hover {
  border-color: var(--ds-primary, #2d7ff9);
}
.sku-pill.active {
  border-color: var(--ds-primary, #2d7ff9);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  box-shadow: 0 0 0 3px var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.sku-pill.soldout {
  opacity: 0.45;
  cursor: not-allowed;
  text-decoration: line-through;
}
.pill-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
}
.pill-price {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.sku-pill.active .pill-name { color: var(--ds-primary, #2d7ff9); }
.sku-pill.active .pill-price { color: var(--ds-primary, #2d7ff9); }

/* 操作卡 */
.action-card {
  background: var(--ds-bg-2, #eef0f4);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 18px 20px;
}
.qty-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
}
.qty-label {
  font-size: 14px;
  color: var(--ds-text-2, #5a6474);
}
/* 已下架提示：中性色，与下方置灰按钮呼应 */
.off-shelf-tip {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: var(--ds-r, 12px);
  background: rgba(152, 161, 175, 0.14);
  color: var(--ds-text-2, #5a6474);
  font-size: 13px;
}
.action-buttons {
  display: flex;
  gap: 12px;
}
.btn-add-cart,
.btn-buy-now {
  flex: 1;
  height: 50px;
  border-radius: var(--ds-r, 12px);
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.2s;
}
.btn-add-cart {
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  color: var(--ds-primary, #2d7ff9);
  border: 1px solid var(--ds-primary-border, rgba(45, 127, 249, 0.25));
}
.btn-add-cart:hover:not(:disabled) {
  background: rgba(45, 127, 249, 0.14);
  transform: translateY(-1px);
}
.btn-buy-now {
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  color: #fff;
  border: none;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
}
.btn-buy-now:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(45, 127, 249, 0.32);
}
.btn-add-cart:disabled,
.btn-buy-now:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-fav {
  flex: 0 0 auto;
  height: 50px;
  padding: 0 18px;
  border-radius: var(--ds-r, 12px);
  border: 1px solid var(--ds-line-2, #d8dce4);
  background: var(--ds-card, #fff);
  color: var(--ds-text-2, #5a6474);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s;
}
.btn-fav:hover { border-color: var(--ds-red, #e0404e); color: var(--ds-red, #e0404e); }
.btn-fav.active {
  border-color: var(--ds-red, #e0404e);
  background: rgba(224, 64, 78, 0.08);
  color: var(--ds-red, #e0404e);
}
.fav-heart {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.fav-heart.filled {
  fill: var(--ds-red, #e0404e);
}

/* ====== 底部面板 ====== */
.detail-lower {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}
.lower-left {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.panel {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 22px 24px;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 0 0 18px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.panel-icon {
  color: var(--ds-primary, #2d7ff9);
}
.detail-html {
  font-size: 14px;
  line-height: 1.8;
  color: var(--ds-text-2, #5a6474);
}
.trend-chart {
  height: 240px;
  width: 100%;
}
.trend-note {
  margin-top: 10px;
  font-size: 11px;
  color: var(--ds-text-3, #98a1af);
  text-align: center;
}

/* 评价区 */
.panel-review {
  flex: 0 0 380px;
}
.review-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}
.avg-block {
  display: flex;
  align-items: center;
  gap: 12px;
}
.avg-num {
  font-size: 44px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  line-height: 1;
  letter-spacing: -1px;
}
.avg-stars {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.avg-count {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}
.rate-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 18px;
  border-radius: var(--ds-r, 12px);
  background: var(--ds-bg-2, #eef0f4);
}
.rate-num {
  font-size: 20px;
  font-weight: 800;
  color: var(--ds-green, #12a77d);
  line-height: 1.2;
}
.rate-label {
  font-size: 11px;
  color: var(--ds-text-3, #98a1af);
}

.review-list {
  display: flex;
  flex-direction: column;
}
.review-item {
  display: flex;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.review-item:last-child {
  border-bottom: none;
}
.review-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.review-main {
  flex: 1;
  min-width: 0;
}
.review-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.review-user {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
}
.review-time {
  font-size: 11px;
  color: var(--ds-text-3, #98a1af);
}
.review-main .stars { margin-bottom: 6px; }
.review-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ds-text-2, #5a6474);
  margin: 0 0 6px;
}
.review-sku {
  font-size: 11px;
  color: var(--ds-text-3, #98a1af);
}

.review-action-row {
  margin-top: 16px;
  text-align: center;
}
.review-action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 26px;
  border-radius: var(--ds-r-pill, 999px);
  border: 1px solid var(--ds-primary-border, rgba(45, 127, 249, 0.25));
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  color: var(--ds-primary, #2d7ff9);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.review-action:hover {
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
}

/* 空状态按钮 */
.empty-btn {
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 9px 22px;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.empty-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

/* ====== 响应式 ====== */
@media (max-width: 960px) {
  .detail-hero {
    flex-direction: column;
  }
  .gallery {
    flex: none;
    max-width: 480px;
    margin: 0 auto;
    width: 100%;
  }
  .detail-lower {
    flex-direction: column;
  }
  .panel-review {
    flex: none;
    width: 100%;
  }
}
@media (max-width: 520px) {
  .action-buttons {
    flex-wrap: wrap;
  }
  .btn-add-cart,
  .btn-buy-now {
    flex: 1 1 45%;
  }
  .btn-fav {
    flex: 1 1 100%;
    justify-content: center;
  }
  .price-value {
    font-size: 28px;
  }
}
</style>