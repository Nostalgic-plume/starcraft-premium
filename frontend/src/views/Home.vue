<template>
  <div class="page-container fade-in" ref="pageRef">
    <!-- ====== Hero 区域（中性渐变底 + 蓝色强调 + 统计条） ====== -->
    <section class="hero" :class="{ 'hero--panel-open': searchPanelOpen }">
      <div class="hero-inner">
        <div class="hero-copy">
          <span class="hero-eyebrow">
            <SvgIcon name="star" :size="13" />
            星创优品 · 品质甄选
          </span>
          <h1 class="hero-title">
            发现好物，<span class="hero-highlight">优中选优</span>，品质生活从这里开始
          </h1>
          <p class="hero-desc">覆盖数码、家居、美妆全品类，精选正品，好价快物流</p>

          <!-- 搜索框 + 下拉（历史 / 热搜） -->
          <div class="hero-search-wrap" ref="searchWrapRef">
            <div class="search-mode">
              <button
                type="button"
                class="mode-item"
                :class="{ active: searchMode === 'keyword' }"
                @click="searchMode = 'keyword'"
              >关键词</button>
              <button
                type="button"
                class="mode-item"
                :class="{ active: searchMode === 'semantic' }"
                @click="searchMode = 'semantic'"
              >智能搜索</button>
            </div>

            <div class="hero-search">
              <div class="search-input-box" :class="{ focused: searchPanelOpen }">
                <SvgIcon name="search" :size="18" class="search-icon" />
                <input
                  v-model="keyword"
                  :placeholder="searchPlaceholder"
                  class="search-input"
                  @focus="openSearchPanel"
                  @keyup.enter="handleSearch"
                />
                <button
                  v-if="keyword"
                  type="button"
                  class="search-clear"
                  aria-label="清空输入"
                  @click="keyword = ''"
                >
                  <SvgIcon name="close" :size="13" />
                </button>
              </div>
              <button type="button" class="search-btn" @click="handleSearch">
                {{ searchMode === 'semantic' ? '智能搜索' : '搜索' }}
                <SvgIcon name="search" :size="16" />
              </button>
            </div>

            <p v-if="searchMode === 'semantic'" class="search-hint">
              智能搜索基于商品描述语义匹配，而非关键词精确匹配
            </p>

            <!-- 搜索下拉面板 -->
            <transition name="panel">
              <div v-if="searchPanelOpen" class="search-panel">
                <div v-if="searchHistory.length" class="sp-section">
                  <div class="sp-head">
                    <span class="sp-title">
                      <SvgIcon name="clock" :size="14" />
                      搜索历史
                    </span>
                    <button type="button" class="sp-clear" @click="clearHistory">
                      <SvgIcon name="close" :size="12" />
                      清空
                    </button>
                  </div>
                  <div class="sp-tags">
                    <span
                      v-for="h in searchHistory"
                      :key="h"
                      class="sp-tag"
                      @click="searchWord(h)"
                    >{{ h }}</span>
                  </div>
                </div>

                <div class="sp-section">
                  <div class="sp-head">
                    <span class="sp-title">
                      <SvgIcon name="star" :size="14" />
                      热搜榜
                    </span>
                  </div>
                  <div class="sp-hot">
                    <button
                      v-for="(w, i) in HOT_WORDS"
                      :key="w"
                      type="button"
                      class="sp-hot-item"
                      @click="searchWord(w)"
                    >
                      <span class="hot-rank" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                      <span class="hot-name">{{ w }}</span>
                    </button>
                  </div>
                </div>
              </div>
            </transition>
          </div>

          <!-- 数据统计条 -->
          <div class="hero-stats">
            <div class="stat-item">
              <span class="stat-num">{{ totalProducts }}</span>
              <span class="stat-label">甄选商品</span>
            </div>
            <div class="stat-divider" />
            <div class="stat-item">
              <span class="stat-num">98%</span>
              <span class="stat-label">好评率</span>
            </div>
            <div class="stat-divider" />
            <div class="stat-item">
              <span class="stat-num">48h</span>
              <span class="stat-label">极速发货</span>
            </div>
            <div class="stat-divider" />
            <div class="stat-item">
              <span class="stat-num">10w+</span>
              <span class="stat-label">活跃用户</span>
            </div>
          </div>
        </div>

        <!-- 右侧装饰（纯 CSS + SVG，aria-hidden） -->
        <div class="hero-visual" aria-hidden="true">
          <div class="hero-glow"></div>
          <div class="hero-blob blob-1"></div>
          <div class="hero-blob blob-2"></div>
          <div class="hero-card float-card">
            <div class="hero-card-head">
              <span class="hero-card-dot dot-a"></span>
              <span class="hero-card-dot dot-b"></span>
              <span class="hero-card-dot dot-c"></span>
            </div>
            <div class="hero-card-bars">
              <div class="bar bar-1"></div>
              <div class="bar bar-2"></div>
              <div class="bar bar-3"></div>
              <div class="bar bar-4"></div>
            </div>
          </div>
          <div class="hero-chip chip-1">
            <SvgIcon name="check-circle" :size="15" />
            正品保障
          </div>
          <div class="hero-chip chip-2">
            <SvgIcon name="pay" :size="15" />
            极速发货
          </div>
        </div>
      </div>
    </section>

    <!-- ====== 分类 + 商品区域 ====== -->
    <div class="shop-layout">
      <!-- 左侧分类 -->
      <aside class="category-panel">
        <div class="category-header">
          <SvgIcon name="filter" :size="16" />
          <span>全部分类</span>
        </div>

        <div class="category-list">
          <div
            v-for="cat in topCategories"
            :key="cat.id"
            class="category-item-wrap"
          >
            <button
              type="button"
              class="category-item"
              :class="{
                expanded: expandedCategoryId === cat.id,
                active: expandedCategoryId === cat.id && !currentCategory
              }"
              @click="toggleCategory(cat)"
            >
              <span class="category-name">{{ cat.name }}</span>
              <SvgIcon
                name="chevron-right"
                :size="14"
                class="category-arrow"
                :class="{ rotated: expandedCategoryId === cat.id }"
              />
            </button>
            <!-- 子分类展开在当前一级分类下 -->
            <transition name="sub-slide">
              <div v-if="expandedCategoryId === cat.id" class="subcategory-panel">
                <button
                  type="button"
                  class="subcategory-item active"
                  @click="selectSubCategory(null)"
                >全部{{ cat.name }}</button>
                <button
                  v-for="sub in subCategories"
                  :key="sub.id"
                  type="button"
                  class="subcategory-item"
                  :class="{ selected: currentCategory === sub.id }"
                  @click="selectSubCategory(sub)"
                >{{ sub.name }}</button>
              </div>
            </transition>
          </div>
        </div>

        <!-- 热门分类 -->
        <div v-if="hotTags.length" class="hot-tags">
          <div class="hot-tags-label">热门</div>
          <div class="hot-tags-list">
            <span
              v-for="tag in hotTags"
              :key="tag.id"
              class="hot-tag"
              :class="{ active: expandedCategoryId === tag.id }"
              @click="selectHotTag(tag)"
            >{{ tag.name }}</span>
          </div>
        </div>

        <div class="category-footer">
          <button
            type="button"
            class="category-all"
            :class="{ active: expandedCategoryId === null && currentCategory === null }"
            @click="selectAllCategories"
          >
            <SvgIcon name="box-open" :size="14" />
            全部商品
          </button>
        </div>
      </aside>

      <!-- 右侧内容 -->
      <div class="main-content">
        <!-- 当前筛选摘要 -->
        <div v-if="filterLabel" class="filter-bar">
          <span class="filter-info">
            <SvgIcon name="filter" :size="14" />
            {{ filterLabel }}
          </span>
          <button type="button" class="filter-reset" @click="resetFilter">清除筛选</button>
        </div>

        <!-- 商品网格 -->
        <div class="grid-area">
          <n-spin :show="loading">
            <div v-if="productList.length" class="product-grid">
              <ProductCard
                v-for="p in productList"
                :key="p.id"
                :product="p"
                @add-cart="addToCart"
                @detail-click="traceClick"
              />
            </div>
          </n-spin>

          <EmptyState
            v-if="!loading && productList.length === 0"
            :text="isRagResult ? '没有找到相关商品' : '暂无商品'"
            description="换个关键词或分类试试看"
          >
            <button type="button" class="empty-btn" @click="resetFilter">刷新看看</button>
          </EmptyState>
        </div>

        <!-- 分页（语义搜索结果不分页） -->
        <div v-if="!isRagResult && total > 0" class="pagination-wrapper">
          <n-pagination
            v-model:page="page"
            :page-size="pageSize"
            :item-count="total"
            :page-slot="5"
            @update:page="loadProducts"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NPagination, NSpin, useMessage } from 'naive-ui'
import gsap from 'gsap'
import { productApi } from '@/api/product'
import { categoryApi } from '@/api/category'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import ProductCard from '@/components/ProductCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import type { CategoryVO, ProductPageQuery, ProductVO } from '@/api/types'

const cartStore = useCartStore()
const userStore = useUserStore()
const message = useMessage()
const route = useRoute()

const productList = ref<ProductVO[]>([])
const categories = ref<CategoryVO[]>([])
const loading = ref(false)
const keyword = ref('')
const searchMode = ref<'keyword' | 'semantic'>('keyword')
const currentCategory = ref<string | null>(null)
const expandedCategoryId = ref<string | null>(null)
const page = ref(1)
const pageSize = 12
const total = ref(0)
/** 全量商品总数（Hero 统计条，不受分类/搜索筛选影响） */
const totalProducts = ref(0)
/** 语义搜索命中结果：不参与分页 */
const isRagResult = ref(false)

const pageRef = ref<HTMLElement | null>(null)
let ctx: gsap.Context | null = null

/* ====== 分类 ====== */
const topCategories = computed(() =>
  categories.value.filter(c => c.parentId === '0' || c.parentId === null)
)

const subCategories = computed(() => {
  if (!expandedCategoryId.value) return []
  return categories.value.filter(c => c.parentId === expandedCategoryId.value)
})

const hotTags = computed(() => topCategories.value.slice(0, 6))

/* ====== 搜索历史 + 热搜榜 ====== */
const HISTORY_KEY = 'shop_search_history'
const HISTORY_MAX = 10
/** 热搜榜（静态占位数据，后续可接后端热搜接口） */
const HOT_WORDS = ['手机', '耳机', '手表', '运动鞋', '精华', '笔记本', '拉面', '沙发']
const searchHistory = ref<string[]>([])
const searchPanelOpen = ref(false)
const searchWrapRef = ref<HTMLElement | null>(null)

function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    const arr = raw ? JSON.parse(raw) : []
    searchHistory.value = Array.isArray(arr)
      ? arr.filter((x): x is string => typeof x === 'string').slice(0, HISTORY_MAX)
      : []
  } catch {
    searchHistory.value = []
  }
}

function persistHistory() {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(searchHistory.value))
  } catch {
    /* localStorage 不可用时静默忽略 */
  }
}

function recordHistory(word: string) {
  const q = word.trim()
  if (!q) return
  searchHistory.value = [q, ...searchHistory.value.filter(x => x !== q)].slice(0, HISTORY_MAX)
  persistHistory()
}

function clearHistory() {
  searchHistory.value = []
  persistHistory()
}

function openSearchPanel() {
  searchPanelOpen.value = true
}

function searchWord(word: string) {
  keyword.value = word
  searchPanelOpen.value = false
  handleSearch()
}

const searchPlaceholder = computed(() =>
  searchMode.value === 'semantic' ? '搜索商品描述，例如：便宜的手机…' : '搜索商品名称…'
)

/* ====== 商品加载 ====== */
async function loadProducts() {
  loading.value = true
  isRagResult.value = false
  try {
    const params: ProductPageQuery = {
      page: page.value || 1,
      pageSize,
      keyword: keyword.value.trim() || undefined
    }
    if (currentCategory.value) {
      params.categoryId = currentCategory.value
    } else if (expandedCategoryId.value) {
      params.firstCategoryId = expandedCategoryId.value
    }
    const res = await productApi.page(params)
    productList.value = res.data?.list || []
    // ⚠️ 后端 Long→String，total 需 Number() 转换
    total.value = Number(res.data?.total) || 0
  } catch (e) {
    console.error('加载商品列表失败:', e)
    productList.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 全量商品总数（Hero 统计条用，pageSize=1 轻量拉一次 total） */
async function loadTotalProducts() {
  try {
    const res = await productApi.page({ page: 1, pageSize: 1 })
    totalProducts.value = Number(res.data?.total) || 0
  } catch {
    totalProducts.value = 0
  }
}

async function loadCategories() {
  try {
    const res = await categoryApi.list()
    categories.value = res.data || []
  } catch (e) {
    console.error('加载分类失败:', e)
    categories.value = []
  }
}

async function handleSearch() {
  searchPanelOpen.value = false
  const q = keyword.value.trim()
  if (!q) {
    // 空关键词：恢复全量列表
    keyword.value = ''
    page.value = 1
    loadProducts()
    return
  }
  recordHistory(q)
  if (searchMode.value === 'semantic') {
    await semanticSearch(q)
  } else {
    page.value = 1
    loadProducts()
  }
}

async function semanticSearch(q: string) {
  loading.value = true
  isRagResult.value = true
  try {
    const res = await productApi.ragSearch({ q, topK: 20 })
    const hits = res.data?.hits || []
    productList.value = hits.map(h => ({
      id: h.productId,
      name: h.name || '',
      description: '',
      categoryId: '',
      categoryName: '',
      brand: '',
      mainImage: h.image || '',
      images: [],
      detail: '',
      minPrice: h.minPrice,
      maxPrice: h.maxPrice,
      totalStock: 0,
      soldCount: 0,
      status: 1,
      skuList: []
    }))
    total.value = hits.length
  } catch (e) {
    console.error('语义搜索失败:', e)
    productList.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/* ====== 分类筛选 ====== */
function selectAllCategories() {
  expandedCategoryId.value = null
  currentCategory.value = null
  page.value = 1
  loadProducts()
}

function toggleCategory(cat: CategoryVO) {
  if (expandedCategoryId.value === cat.id) {
    expandedCategoryId.value = null
    currentCategory.value = null
  } else {
    expandedCategoryId.value = cat.id
    currentCategory.value = null
  }
  page.value = 1
  loadProducts()
}

function selectSubCategory(sub: CategoryVO | null) {
  currentCategory.value = sub ? sub.id : null
  page.value = 1
  loadProducts()
}

function selectHotTag(tag: CategoryVO) {
  expandedCategoryId.value = tag.id
  currentCategory.value = null
  page.value = 1
  loadProducts()
}

/* ====== 筛选摘要 ====== */
const filterLabel = computed(() => {
  if (keyword.value.trim()) return `关键词：${keyword.value.trim()}`
  if (currentCategory.value) {
    const sub = categories.value.find(c => c.id === currentCategory.value)
    return `分类：${sub?.name || '全部'}`
  }
  if (expandedCategoryId.value) {
    const top = topCategories.value.find(c => c.id === expandedCategoryId.value)
    return `分类：${top?.name || '全部'}`
  }
  return ''
})

function resetFilter() {
  keyword.value = ''
  currentCategory.value = null
  expandedCategoryId.value = null
  page.value = 1
  searchMode.value = 'keyword'
  isRagResult.value = false
  loadProducts()
}

/* ====== 加购 ====== */
async function addToCart(product: ProductVO) {
  if (!userStore.isLoggedIn()) {
    message.warning('请先登录')
    return
  }
  const sku = product.skuList?.[0]
  if (!sku) {
    message.warning('该商品暂无规格，请到详情页选择')
    return
  }
  try {
    await cartStore.addItem({ skuId: sku.id, quantity: 1 })
    message.success('已加入购物车')
  } catch {
    // 错误提示由 axios 拦截器统一处理
  }
}

/** Phase 4：商品点击行为反馈埋点（异步上报，失败不影响跳转） */
function traceClick(product: ProductVO) {
  const token = localStorage.getItem('token')
  const baseURL = import.meta.env.VITE_API_BASE || ''
  fetch(baseURL + '/api/rag/trace', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { token: token } : {})
    },
    body: JSON.stringify({ query: keyword.value, productId: product.id, action: 'SEARCH_CLICK' })
  }).catch(() => {})
}

/* ====== GSAP Hero 动画 ====== */
function initHeroAnimation() {
  if (!pageRef.value) return
  ctx = gsap.context(() => {
    const tl = gsap.timeline({ defaults: { ease: 'power3.out' } })
    tl.from('.hero-eyebrow', { y: 20, opacity: 0, duration: 0.6 })
      .from('.hero-title', { y: 28, opacity: 0, duration: 0.7 }, '-=0.35')
      .from('.hero-desc', { y: 18, opacity: 0, duration: 0.6 }, '-=0.3')
      .from('.hero-search-wrap', { y: 16, opacity: 0, duration: 0.5 }, '-=0.25')
      .from('.hero-stats', { y: 14, opacity: 0, duration: 0.5 }, '-=0.2')
    // 右侧装饰卡片 / 条形 / 徽章
    gsap.from('.hero-card', { y: 24, opacity: 0, duration: 0.7, delay: 0.15 })
    gsap.from('.bar', {
      scaleY: 0,
      transformOrigin: 'bottom',
      duration: 0.6,
      stagger: 0.08,
      ease: 'power2.out',
      delay: 0.4
    })
    gsap.from('.hero-chip', {
      y: 14,
      opacity: 0,
      duration: 0.5,
      stagger: 0.12,
      ease: 'power2.out',
      delay: 0.6
    })
    // 背景光斑浮动
    gsap.to('.blob-1', { y: -14, duration: 4, repeat: -1, yoyo: true, ease: 'sine.inOut' })
    gsap.to('.blob-2', { y: 10, duration: 3.2, repeat: -1, yoyo: true, ease: 'sine.inOut', delay: 0.6 })
  }, pageRef.value)
}

/* ====== 点击外部关闭搜索面板 ====== */
function onDocClick(e: MouseEvent) {
  if (searchWrapRef.value && !searchWrapRef.value.contains(e.target as Node)) {
    searchPanelOpen.value = false
  }
}

/* ====== 生命周期 ====== */
onMounted(async () => {
  loadHistory()
  document.addEventListener('click', onDocClick)
  // 支持从 AppHeader 搜索跳转携带 ?keyword=：写入搜索历史，与本地搜索保持一致
  const q = typeof route.query.keyword === 'string' ? route.query.keyword.trim() : ''
  if (q) {
    keyword.value = q
    recordHistory(q)
  }
  await Promise.all([loadCategories(), loadProducts(), loadTotalProducts()])
  nextTick(() => initHeroAnimation())
})

// 同路由下 query 变化（AppHeader 全局搜索跳转）时：
// 1. 清空首页分类筛选，确保搜索结果跨全部分类
// 2. 写入搜索历史（最近 10 条去重，与本地搜索一致）
// 3. 切回关键词搜索模式并刷新商品
watch(() => route.query.keyword, (q) => {
  currentCategory.value = null
  expandedCategoryId.value = null
  const kw = typeof q === 'string' ? q.trim() : ''
  keyword.value = kw
  if (kw) recordHistory(kw)
  searchMode.value = 'keyword'
  page.value = 1
  loadProducts()
})

onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  ctx?.revert()
})
</script>

<style scoped>
/* ====== Hero 区域 ====== */
.hero {
  position: relative;
  border-radius: 20px;
  background: linear-gradient(135deg, #f6f7fa 0%, #eef1f7 55%, #e7edfa 100%);
  border: 1px solid var(--ds-line, #e7e9ee);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.85),
    var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
  isolation: isolate;
  padding: 44px 48px;
  margin-bottom: 28px;
}
/*
 * 下拉面板打开时提升层级，保证面板压住页面其他组件。
 * 面板自身 z-index:60 不够用，它被困在三层层叠上下文里：
 *   .hero(isolation:isolate) → .hero-inner(z-index:1) → .hero-search-wrap(transform)
 * 1) .hero 的 z-index 是 auto，DOM 中更靠后的粘性分类栏(aside.category-panel) 与商品网格
 *    (n-spin-container, position:relative) 会整体盖住 hero 这棵树 → 提升 .hero。
 * 2) hero 内部的数据统计条 .hero-stats 被 GSAP 动画留下 transform，同样脱离普通流层叠顺序，
 *    且在 DOM 中晚于搜索框 → 会盖住面板 → 同步提升 .hero-search-wrap。
 * 仅面板打开时生效，避免滚动时 hero 反过来压住粘性侧栏。
 */
.hero--panel-open {
  z-index: 20;
}
.hero--panel-open .hero-search-wrap {
  z-index: 2;
}
.hero-inner {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 40px;
  align-items: center;
}
.hero-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--ds-primary, #2d7ff9);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  border: 1px solid var(--ds-primary-border, rgba(45, 127, 249, 0.25));
  padding: 5px 12px;
  border-radius: var(--ds-r-pill, 999px);
  margin-bottom: 16px;
}
.hero-title {
  font-size: 36px;
  line-height: 1.25;
  font-weight: 800;
  letter-spacing: -1px;
  color: var(--ds-text, #1e2632);
  margin: 0 0 12px;
}
.hero-highlight {
  color: var(--ds-primary, #2d7ff9);
}
.hero-desc {
  font-size: 15px;
  color: var(--ds-text-2, #5a6474);
  margin: 0 0 22px;
}

/* 搜索模式切换 */
.hero-search-wrap { max-width: 540px; position: relative; }
.search-mode { display: flex; gap: 4px; margin-bottom: 10px; }
.mode-item {
  border: none;
  background: transparent;
  font-size: 13px;
  font-weight: 500;
  color: var(--ds-text-3, #98a1af);
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 8px;
  transition: all 0.2s;
}
.mode-item:hover { color: var(--ds-primary, #2d7ff9); }
.mode-item.active {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 700;
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}

/* 搜索条 */
.hero-search { display: flex; gap: 10px; }
.search-input-box {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 48px;
  padding: 0 14px;
  background: var(--ds-card, #fff);
  border: 1.5px solid var(--ds-line-2, #d8dce4);
  border-radius: 12px;
  transition: all 0.2s;
}
.search-input-box:focus-within,
.search-input-box.focused {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: 0 0 0 4px var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.search-icon { color: var(--ds-text-3, #98a1af); flex-shrink: 0; }
.search-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15px;
  color: var(--ds-text, #1e2632);
}
.search-input::placeholder { color: var(--ds-text-3, #98a1af); }
.search-clear {
  border: none;
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}
.search-clear:hover { color: var(--ds-text, #1e2632); }
.search-btn {
  height: 48px;
  padding: 0 22px;
  border: none;
  border-radius: 12px;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.search-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(45, 127, 249, 0.32);
}
.search-hint {
  margin: 8px 2px 0;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

/* 搜索下拉面板 */
.search-panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  z-index: 60;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: 14px;
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
  padding: 14px;
  /* 长内容（历史+热搜）高度受限时面板内部滚动，避免超出可视区 */
  max-height: min(440px, calc(100vh - 150px));
  overflow-y: auto;
}
.sp-section + .sp-section {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--ds-line, #e7e9ee);
}
.sp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sp-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
}
.sp-clear {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  color: var(--ds-text-3, #98a1af);
  font-size: 12px;
  cursor: pointer;
}
.sp-clear:hover { color: var(--ds-primary, #2d7ff9); }
.sp-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.sp-tag {
  padding: 5px 12px;
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-2, #5a6474);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}
.sp-tag:hover { background: var(--ds-primary, #2d7ff9); color: #fff; }
.sp-hot { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px; }
.sp-hot-item {
  display: flex;
  align-items: center;
  gap: 10px;
  border: none;
  background: transparent;
  padding: 7px 8px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--ds-text, #1e2632);
  cursor: pointer;
  transition: all 0.2s;
  text-align: left;
}
.sp-hot-item:hover { background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08)); }
.hot-rank {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  border-radius: 6px;
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.hot-rank.top {
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  color: #fff;
}
.hot-name { flex: 1; }
.panel-enter-active,
.panel-leave-active { transition: all 0.18s ease; }
.panel-enter-from,
.panel-leave-to { opacity: 0; transform: translateY(-6px); }

/* 数据统计条 */
.hero-stats {
  display: flex;
  align-items: center;
  gap: 22px;
  margin-top: 26px;
  padding-top: 20px;
  border-top: 1px dashed var(--ds-line-2, #d8dce4);
}
.stat-item { display: flex; flex-direction: column; gap: 2px; }
.stat-num {
  font-size: 22px;
  font-weight: 800;
  color: var(--ds-primary, #2d7ff9);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.5px;
}
.stat-label { font-size: 12px; color: var(--ds-text-3, #98a1af); }
.stat-divider { width: 1px; height: 28px; background: var(--ds-line-2, #d8dce4); }

/* 右侧装饰 */
.hero-visual {
  position: relative;
  height: 220px;
  overflow: hidden;
  border-radius: 20px;
}
.hero-glow {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at 60% 40%, rgba(45, 127, 249, 0.14), transparent 65%);
  border-radius: 24px;
}
.hero-blob {
  position: absolute;
  border-radius: 50%;
  background: rgba(45, 127, 249, 0.08);
  pointer-events: none;
}
.blob-1 { width: 260px; height: 260px; right: -60px; top: -80px; }
.blob-2 {
  width: 140px;
  height: 140px;
  right: 190px;
  bottom: -40px;
  background: rgba(45, 127, 249, 0.05);
}
.hero-card {
  position: absolute;
  left: 44px;
  top: 28px;
  width: 240px;
  height: 160px;
  background: #fff;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: 18px;
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transform: rotate(-3deg);
}
.float-card { animation: floatCard 5s ease-in-out infinite; }
@keyframes floatCard {
  0%, 100% { transform: rotate(-3deg) translateY(0); }
  50% { transform: rotate(-3deg) translateY(-8px); }
}
.hero-card-head { display: flex; gap: 6px; }
.hero-card-dot { width: 8px; height: 8px; border-radius: 50%; }
.hero-card-dot.dot-a { background: #e0404e; }
.hero-card-dot.dot-b { background: #e08600; }
.hero-card-dot.dot-c { background: #12a77d; }
.hero-card-bars { display: flex; align-items: flex-end; gap: 10px; flex: 1; }
.bar {
  flex: 1;
  border-radius: 6px 6px 2px 2px;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  opacity: 0.92;
}
.bar-1 { height: 40%; }
.bar-2 { height: 70%; }
.bar-3 { height: 55%; }
.bar-4 { height: 90%; }
.bar-2,
.bar-4 { background: linear-gradient(135deg, #a5c9ff, #2d7ff9); }
.hero-chip {
  position: absolute;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #fff;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-pill, 999px);
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text-2, #5a6474);
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
}
.hero-chip svg { color: var(--ds-primary, #2d7ff9); }
.chip-1 { right: 4px; top: 16px; }
.chip-2 { right: 64px; bottom: 6px; }

/* ====== 布局 ====== */
.shop-layout {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}
.main-content { flex: 1; min-width: 0; }

/* ====== 分类面板 ====== */
.category-panel {
  width: 220px;
  flex-shrink: 0;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  overflow: hidden;
  position: sticky;
  top: 84px;
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
}
.category-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 18px 12px;
  font-size: 15px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  border-bottom: 1px solid var(--ds-line, #e7e9ee);
}
.category-header svg { color: var(--ds-primary, #2d7ff9); }
.category-list { padding: 6px 0; }
.category-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 18px;
  cursor: pointer;
  border: none;
  background: transparent;
  font-size: 14px;
  color: var(--ds-text, #1e2632);
  transition: all 0.2s;
  text-align: left;
}
.category-item:hover {
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  color: var(--ds-primary, #2d7ff9);
}
.category-item.expanded {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}
.category-item.active {
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}
.category-item .category-name { flex: 1; }
.category-arrow {
  color: var(--ds-text-3, #98a1af);
  transition: transform 0.2s;
  flex-shrink: 0;
}
.category-arrow.rotated {
  transform: rotate(90deg);
  color: var(--ds-primary, #2d7ff9);
}
.subcategory-panel {
  background: var(--ds-bg-2, #eef0f4);
  border-left: 2px solid var(--ds-primary, #2d7ff9);
  margin: 0 8px 6px;
  border-radius: 8px;
  overflow: hidden;
}
.sub-slide-enter-active,
.sub-slide-leave-active { transition: all 0.22s ease; }
.sub-slide-enter-from,
.sub-slide-leave-to { max-height: 0; opacity: 0; }
.sub-slide-enter-to,
.sub-slide-leave-from { max-height: 400px; opacity: 1; }
.subcategory-item {
  width: 100%;
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 8px 18px 8px 26px;
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  text-align: left;
  transition: all 0.2s;
}
.subcategory-item:hover {
  background: rgba(45, 127, 249, 0.06);
  color: var(--ds-primary, #2d7ff9);
}
.subcategory-item.selected {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}
.subcategory-item.active {
  color: var(--ds-text-3, #98a1af);
  font-style: italic;
  font-size: 12px;
}

/* 热门分类 */
.hot-tags {
  border-top: 1px solid var(--ds-line, #e7e9ee);
  padding: 12px 18px;
}
.hot-tags-label {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-bottom: 8px;
}
.hot-tags-list { display: flex; flex-wrap: wrap; gap: 6px; }
.hot-tag {
  padding: 3px 10px;
  border-radius: var(--ds-r-pill, 999px);
  font-size: 11px;
  cursor: pointer;
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-2, #5a6474);
  transition: all 0.2s;
}
.hot-tag:hover { background: var(--ds-primary, #2d7ff9); color: #fff; }
.hot-tag.active { background: var(--ds-text, #1e2632); color: #fff; }
.category-footer {
  border-top: 1px solid var(--ds-line, #e7e9ee);
  padding: 10px 18px;
}
.category-all {
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  transition: all 0.2s;
  padding: 0;
}
.category-all:hover { color: var(--ds-primary, #2d7ff9); }
.category-all.active {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 600;
}

/* ====== 主内容区 ====== */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding: 0 2px;
}
.filter-info {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
}
.filter-info svg { color: var(--ds-primary, #2d7ff9); }
.filter-reset {
  border: 1px solid var(--ds-line, #e7e9ee);
  background: var(--ds-card, #fff);
  color: var(--ds-text-2, #5a6474);
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: var(--ds-r-pill, 999px);
  cursor: pointer;
  transition: all 0.2s;
}
.filter-reset:hover {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
}

/* 商品网格 */
.grid-area { min-height: 240px; }
.product-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
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

/* ====== 分页 ====== */
.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

/* ====== 响应式 ====== */
@media (max-width: 1100px) {
  .hero-inner { grid-template-columns: 1fr; }
  .hero-visual { display: none; }
  .product-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 900px) {
  .shop-layout { flex-direction: column; }
  .category-panel { width: 100%; position: static; }
  .product-grid { grid-template-columns: repeat(2, 1fr); }
  .hero { padding: 32px 24px; }
  .hero-title { font-size: 28px; }
}
@media (max-width: 500px) {
  .product-grid { grid-template-columns: 1fr; }
  .hero-search { flex-direction: column; }
  .hero-stats { gap: 14px; }
}
</style>
