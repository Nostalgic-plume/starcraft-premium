<template>
  <div class="page-container fade-in">
    <div v-loading="loading" class="detail-wrapper">
      <template v-if="product">
        <!-- 面包屑导航 -->
        <nav class="breadcrumb">
          <span class="breadcrumb-item" @click="$router.push('/')">首页</span>
          <svg class="breadcrumb-sep" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
          <span class="breadcrumb-item" v-if="product.categoryName">{{ product.categoryName }}</span>
          <svg class="breadcrumb-sep" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
          <span class="breadcrumb-current">{{ product.name }}</span>
        </nav>

        <!-- 商品主体区域 -->
        <div class="detail-hero">
          <!-- 左侧：图片展示 -->
          <div class="gallery-section">
            <div class="gallery-main">
              <el-image :src="product.mainImage || '/placeholder.png'" class="gallery-image" fit="cover" />
              <div class="gallery-badge" v-if="product.status === 1">在售</div>
            </div>
            <div v-if="product.images?.length" class="gallery-thumbs">
              <div
                v-for="(img, idx) in product.images"
                :key="idx"
                class="gallery-thumb"
                :class="{ active: activeImage === idx }"
                @click="activeImage = idx"
              >
                <el-image :src="img" fit="cover" class="thumb-image" />
              </div>
            </div>
          </div>

          <!-- 右侧：信息区域 -->
          <div class="meta-section">
            <h1 class="meta-title">{{ product.name }}</h1>
            <p v-if="product.description" class="meta-subtitle">{{ product.description }}</p>

            <!-- 价格卡片 -->
            <div class="price-card">
              <div class="price-row">
                <span class="price-label">价格</span>
                <span class="price-value">
                  <span class="price-symbol">¥</span>
                  {{ displayPrice }}
                </span>
                <span class="price-original" v-if="hasPriceRange">¥{{ product.maxPrice }}</span>
              </div>
              <div class="price-stats">
                <span class="price-stat">库存 {{ selectedSku?.stock ?? product.totalStock ?? 0 }} 件</span>
                <span class="price-stat" v-if="product.skuList?.length">{{ product.skuList.length }} 种规格</span>
              </div>
            </div>

            <!-- 属性信息 -->
            <div class="attrs">
              <div class="attr-row">
                <span class="attr-key">品牌</span>
                <span class="attr-val">{{ product.brand || '通用' }}</span>
              </div>
              <div class="attr-row">
                <span class="attr-key">分类</span>
                <span class="attr-val">{{ product.categoryName }}</span>
              </div>
            </div>

            <!-- SKU 选择器 -->
            <div class="sku-selector" v-if="product.skuList?.length">
              <div class="selector-label">选择规格</div>
              <div class="selector-options">
                <div
                  v-for="sku in product.skuList"
                  :key="sku.id"
                  class="selector-chip"
                  :class="{
                    active: selectedSku?.id === sku.id,
                    soldout: sku.stock === 0
                  }"
                  @click="selectSku(sku)"
                >
                  <span class="chip-name">{{ sku.name }}</span>
                  <span class="chip-price">¥{{ sku.price }}</span>
                </div>
              </div>
            </div>

            <!-- 数量 + 操作 -->
            <div class="action-area">
              <div class="quantity-row">
                <span class="quantity-label">数量</span>
                <el-input-number
                  v-model="quantity"
                  :min="1"
                  :max="selectedSku?.stock || 99"
                  size="large"
                  controls-position="right"
                />
              </div>
              <div class="action-buttons">
                <el-button type="danger" size="large" class="btn-cart" @click="addToCart" :disabled="!selectedSku">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
                    <circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/>
                    <path d="M1 1h4l2.68 13.39a2 2 0 002 1.61h9.72a2 2 0 002-1.61L23 6H6"/>
                  </svg>
                  加入购物车
                </el-button>
                <el-button type="primary" size="large" class="btn-buy" @click="buyNow" :disabled="!selectedSku">
                  立即购买
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <!-- 底部：商品详情 + ECharts 价格走势 -->
        <div class="detail-panels">
          <div class="panel-left">
            <div class="detail-card">
              <h3 class="panel-title">商品详情</h3>
              <div class="panel-divider" />
              <div class="detail-html" v-html="product.detail || '暂无详情'" />
            </div>
          </div>
          <div class="panel-right">
            <div class="detail-card">
              <h3 class="panel-title">价格走势</h3>
              <div class="panel-divider" />
              <div ref="trendChartRef" class="trend-chart"></div>
              <p class="trend-note">基于同类商品价格区间模拟，仅供参考</p>
            </div>
          </div>
        </div>
      </template>
      <el-empty v-else-if="!loading" description="商品不存在" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { productApi } from '@/api/product'
import { cartApi } from '@/api/cart'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

const product = ref(null)
const loading = ref(true)
const selectedSku = ref(null)
const quantity = ref(1)
const activeImage = ref(0)
const trendChartRef = ref(null)
let trendChart = null

const displayPrice = computed(() => {
  if (selectedSku.value) return selectedSku.value.price
  return product.value?.minPrice || '0'
})

const hasPriceRange = computed(() => {
  if (!product.value) return false
  return product.value.maxPrice && +product.value.maxPrice > +product.value.minPrice
})

async function loadProduct() {
  try {
    const res = await productApi.detail(route.params.id)
    product.value = res.data
    if (res.data?.skuList?.length) {
      selectedSku.value = res.data.skuList[0]
    }
    await nextTick()
    initTrendChart()
  } finally {
    loading.value = false
  }
}

function selectSku(sku) {
  selectedSku.value = sku
  quantity.value = 1
}

// ECharts 价格走势图（基于当前价格生成模拟曲线）
function initTrendChart() {
  if (!trendChartRef.value || !product.value) return
  trendChart = echarts.init(trendChartRef.value)

  const minP = Number(product.value.minPrice) || 100
  const maxP = Number(product.value.maxPrice) || minP * 1.2
  const mid = (minP + maxP) / 2
  const now = new Date()
  const days = 14

  const dates = []
  const trendData = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    dates.push((d.getMonth() + 1) + '/' + d.getDate())
    // 带随机波动的模拟价格，围绕 mid 上下 15%
    const fluctuation = (Math.random() - 0.5) * mid * 0.3
    trendData.push(Math.round((mid + fluctuation) * 100) / 100)
  }

  trendChart.setOption({
    tooltip: {
      trigger: 'axis',
      valueFormatter: v => '¥' + (v || 0).toFixed(2)
    },
    grid: { left: 50, right: 16, top: 20, bottom: 24 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { color: '#b2bec3', fontSize: 10 },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      min: Math.floor(Math.min(...trendData) * 0.95),
      max: Math.ceil(Math.max(...trendData) * 1.05),
      axisLabel: {
        color: '#b2bec3',
        fontSize: 10,
        formatter: '¥{value}'
      },
      splitLine: { lineStyle: { color: '#f0eee9', type: 'dashed' } }
    },
    series: [{
      type: 'line',
      data: trendData,
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2.5, color: '#e94560' },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(233, 69, 96, 0.15)' },
          { offset: 1, color: 'rgba(233, 69, 96, 0.01)' }
        ])
      },
      markLine: {
        silent: true,
        data: [{ yAxis: mid, label: { formatter: '均价 ¥' + mid.toFixed(2), color: '#636e72', fontSize: 11 } }],
        lineStyle: { color: '#b2bec3', type: 'dashed', width: 1 }
      }
    }]
  })
}

function handleResize() {
  trendChart?.resize()
}

async function addToCart() {
  if (!userStore.isLoggedIn()) { router.push('/login'); return }
  const existing = cartStore.items.find(i => i.skuId === selectedSku.value.id)
  if (existing) {
    try {
      await ElMessageBox.confirm('该商品已在购物车中，是否增加数量？', '提示',
        { confirmButtonText: '增加数量', cancelButtonText: '取消', type: 'warning' })
      await cartStore.updateQuantity(selectedSku.value.id, existing.quantity + quantity.value)
      ElMessage.success('已增加购物车数量')
      return
    } catch (e) { console.error('用户取消操作:', e); return }
  }
  await cartStore.addItem({ skuId: selectedSku.value.id, quantity: quantity.value })
  ElMessage.success('已加入购物车')
}

function buyNow() {
  if (!userStore.isLoggedIn()) { router.push('/login'); return }
  router.push({
    path: '/order/create',
    query: {
      items: `${selectedSku.value.id}-${quantity.value}`,
      productName: product.value.name,
      skuName: selectedSku.value.name,
      price: selectedSku.value.price,
      image: product.value.mainImage || ''
    }
  })
}

onMounted(() => {
  loadProduct()
  window.addEventListener('resize', handleResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
})
</script>

<style scoped>
/* ====== 面包屑 ====== */
.breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 20px;
  font-size: 13px;
  color: var(--ds-text-muted, #b2bec3);
}
.breadcrumb-item { cursor: pointer; transition: color 0.2s; }
.breadcrumb-item:hover { color: var(--ds-navy, #1a1a2e); }
.breadcrumb-sep { width: 12px; height: 12px; }
.breadcrumb-current { color: var(--ds-text, #1a1a2e); font-weight: 500; }

/* ====== 主体 ====== */
.detail-hero {
  display: flex;
  gap: 44px;
  margin-bottom: 28px;
}

/* ====== 图片画廊 ====== */
.gallery-section { flex: 0 0 460px; }
.gallery-main {
  position: relative;
  border-radius: var(--ds-radius-lg, 16px);
  overflow: hidden;
  background: #f8f7f4;
  border: 1px solid var(--ds-border-light, #f0eee9);
}
.gallery-image { width: 100%; aspect-ratio: 1; display: block; }
.gallery-badge {
  position: absolute; top: 14px; left: 14px;
  background: var(--ds-coral, #e94560); color: #fff;
  font-size: 11px; font-weight: 600;
  padding: 3px 12px; border-radius: 4px;
}
.gallery-thumbs {
  display: flex; gap: 8px; margin-top: 12px;
}
.gallery-thumb {
  width: 64px; height: 64px;
  border-radius: var(--ds-radius, 10px);
  overflow: hidden; cursor: pointer;
  border: 2px solid transparent;
  transition: border-color 0.2s;
  background: #f8f7f4;
}
.gallery-thumb:hover { border-color: var(--ds-text-muted, #b2bec3); }
.gallery-thumb.active { border-color: var(--ds-coral, #e94560); }
.thumb-image { width: 100%; height: 100%; }

/* ====== 信息区 ====== */
.meta-section { flex: 1; min-width: 0; }
.meta-title {
  font-size: 24px; font-weight: 700;
  color: var(--ds-text, #1a1a2e);
  margin-bottom: 6px;
  line-height: 1.3;
}
.meta-subtitle {
  font-size: 14px;
  color: var(--ds-text-secondary, #636e72);
  margin-bottom: 20px;
  line-height: 1.6;
}

/* 价格卡片 */
.price-card {
  background: linear-gradient(135deg, #fff5f5 0%, #fff 100%);
  border: 1px solid #fde8e8;
  border-radius: var(--ds-radius-lg, 16px);
  padding: 20px;
  margin-bottom: 20px;
}
.price-row { display: flex; align-items: baseline; gap: 6px; margin-bottom: 8px; }
.price-label { font-size: 13px; color: var(--ds-text-muted, #b2bec3); margin-right: 8px; }
.price-value {
  font-size: 32px; font-weight: 800;
  color: var(--ds-coral, #e94560);
  line-height: 1;
}
.price-symbol { font-size: 18px; }
.price-original {
  font-size: 15px; color: var(--ds-text-muted, #b2bec3);
  text-decoration: line-through; margin-left: 6px;
}
.price-stats { display: flex; gap: 16px; }
.price-stat { font-size: 12px; color: var(--ds-text-muted, #b2bec3); }

/* 属性 */
.attrs { margin-bottom: 20px; }
.attr-row {
  display: flex; gap: 12px;
  padding: 6px 0;
  font-size: 14px;
  border-bottom: 1px solid var(--ds-border-light, #f0eee9);
}
.attr-row:last-child { border-bottom: none; }
.attr-key { color: var(--ds-text-muted, #b2bec3); min-width: 48px; }
.attr-val { color: var(--ds-text, #1a1a2e); }

/* SKU 选择器 */
.sku-selector { margin-bottom: 20px; }
.selector-label {
  font-size: 14px; font-weight: 600;
  color: var(--ds-text, #1a1a2e);
  margin-bottom: 10px;
}
.selector-options { display: flex; flex-wrap: wrap; gap: 8px; }
.selector-chip {
  padding: 10px 18px;
  border: 1.5px solid var(--ds-border, #e8e6e1);
  border-radius: var(--ds-radius, 10px);
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
  display: flex; flex-direction: column; align-items: center;
  min-width: 80px;
}
.selector-chip:hover {
  border-color: var(--ds-coral, #e94560);
  box-shadow: 0 2px 8px rgba(233, 69, 96, 0.08);
}
.selector-chip.active {
  border-color: var(--ds-coral, #e94560);
  background: rgba(233, 69, 96, 0.04);
  box-shadow: 0 0 0 2px rgba(233, 69, 96, 0.15);
}
.selector-chip.soldout {
  opacity: 0.4; cursor: not-allowed;
  text-decoration: line-through;
}
.chip-name { font-size: 14px; font-weight: 500; color: var(--ds-text, #1a1a2e); }
.chip-price { font-size: 12px; color: var(--ds-coral, #e94560); margin-top: 2px; }
.selector-chip.active .chip-name { color: var(--ds-coral, #e94560); }

/* 操作 */
.action-area { margin-top: 20px; }
.quantity-row {
  display: flex; align-items: center;
  gap: 12px; margin-bottom: 20px;
}
.quantity-label { font-size: 14px; color: var(--ds-text, #1a1a2e); }
.action-buttons { display: flex; gap: 12px; }
.btn-cart, .btn-buy { flex: 1; height: 52px; border-radius: var(--ds-radius, 10px); font-size: 16px; font-weight: 600; }

/* ====== 底部面板 ====== */
.detail-panels { display: flex; gap: 20px; }
.panel-left { flex: 1; min-width: 0; }
.panel-right { flex: 0 0 380px; }

.detail-card {
  background: var(--ds-surface, #fff);
  border: 1px solid var(--ds-border-light, #f0eee9);
  border-radius: var(--ds-radius-lg, 16px);
  padding: 24px;
  margin-bottom: 16px;
}
.panel-title {
  font-size: 17px; font-weight: 700;
  color: var(--ds-text, #1a1a2e);
  margin-bottom: 0;
}
.panel-divider {
  height: 1px;
  background: var(--ds-border-light, #f0eee9);
  margin: 16px 0;
}
.detail-html { line-height: 1.8; color: var(--ds-text-secondary, #636e72); font-size: 14px; }

/* 价格走势 */
.trend-chart { height: 220px; width: 100%; }
.trend-note {
  margin-top: 8px;
  font-size: 11px;
  color: var(--ds-text-muted, #b2bec3);
  text-align: center;
}

/* ====== 响应式 ====== */
@media (max-width: 960px) {
  .detail-hero { flex-direction: column; }
  .gallery-section { flex: none; max-width: 480px; }
  .detail-panels { flex-direction: column; }
  .panel-right { flex: none; }
}
@media (max-width: 500px) {
  .action-buttons { flex-direction: column; }
  .price-card { padding: 16px; }
  .price-value { font-size: 28px; }
}
</style>
