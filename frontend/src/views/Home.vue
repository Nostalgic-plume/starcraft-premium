<template>
  <div class="page-container fade-in" ref="pageRef">
    <!-- ====== Hero 区域（中性渐变底 + 蓝色强调 + 统计条） ====== -->
    <section class="hero">
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

          <!-- 搜索框：固定走智能搜索，统一跳转检索页（首页不再就地渲染搜索结果） -->
          <div class="hero-search-wrap">
            <div class="hero-search">
              <div class="search-input-box">
                <SvgIcon name="search" :size="18" class="search-icon" />
                <input
                  v-model="keyword"
                  placeholder="搜索商品描述，例如：便宜的手机…"
                  class="search-input"
                  @keyup.enter="goSearch"
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
              <button type="button" class="search-btn" @click="goSearch">
                智能搜索
                <SvgIcon name="search" :size="16" />
              </button>
            </div>

            <p class="search-hint">
              智能搜索基于商品描述语义匹配，而非关键词精确匹配
            </p>

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

    <!-- ====== 推荐榜单（个性化，来自推荐子系统；不可达时后端返回原生热销榜） ====== -->
    <RecRankList @add-cart="addToCart" />

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
          <!-- 只在首屏转圈：触底续接时若整块转圈，已展示的内容会闪一下 -->
          <n-spin :show="loading && productList.length === 0">
            <div v-if="productList.length" class="product-grid">
              <ProductCard
                v-for="p in productList"
                :key="p.id"
                :product="p"
                @add-cart="addToCart"
              />
            </div>
          </n-spin>

          <EmptyState
            v-if="!loading && productList.length === 0 && !loadFailed"
            text="暂无商品"
            description="换个分类试试看"
          >
            <button type="button" class="empty-btn" @click="resetFilter">刷新看看</button>
          </EmptyState>
        </div>

        <!-- 触底哨兵：进入视口自动加载下一页（替代原分页组件） -->
        <div ref="sentinelRef" class="feed-sentinel">
          <span v-if="loading && productList.length > 0" class="feed-sentinel-text">正在加载…</span>
          <button
            v-else-if="loadFailed"
            type="button"
            class="empty-btn"
            @click="loadProducts(false)"
          >
            加载失败，点击重试
          </button>
          <span v-else-if="!hasMore && productList.length > 0" class="feed-sentinel-text">
            已经到底了
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { NSpin, useMessage } from 'naive-ui'
import gsap from 'gsap'
import { productApi } from '@/api/product'
import { categoryApi } from '@/api/category'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import ProductCard from '@/components/ProductCard.vue'
import RecRankList from '@/components/RecRankList.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { trackAddCart } from '@/utils/behaviorTracker'
import type { CategoryVO, ProductPageQuery, ProductVO } from '@/api/types'

const cartStore = useCartStore()
const userStore = useUserStore()
const message = useMessage()
const router = useRouter()

const productList = ref<ProductVO[]>([])
const categories = ref<CategoryVO[]>([])
const loading = ref(false)
/** Hero 搜索框输入（仅用于跳转检索页，不参与本页商品筛选） */
const keyword = ref('')
const currentCategory = ref<string | null>(null)
const expandedCategoryId = ref<string | null>(null)
const page = ref(1)
/** 每页条数：触底续接场景下取 20，减少请求次数（原分页每页 12） */
const pageSize = 20
const total = ref(0)
/** 全量商品总数（Hero 统计条，不受分类筛选影响） */
const totalProducts = ref(0)
/**
 * 触底续接是否还有下一页
 *
 * <p>用「已请求页数 × 每页条数 < 总数」而非「已加载条数 < 总数」：后者在服务端返回空页
 * （或整页都被去重掉）时会永远为 true，配合下面的重新观察会变成死循环刷接口。
 * 前者只依赖页码推进，哪怕某页一条没加，页码照样前进，必然收敛。
 */
const hasMore = computed(() => page.value * pageSize < total.value)
/** 续接失败：保留已展示内容，尾部给重试入口 */
const loadFailed = ref(false)
/** 触底哨兵：进入视口即加载下一页 */
const sentinelRef = ref<HTMLElement | null>(null)
let feedObserver: IntersectionObserver | null = null

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

/* ====== 搜索跳转 ====== */
/**
 * Hero 搜索统一跳检索页 /search（固定走智能搜索）
 * 搜索结果渲染、搜索历史 / 热搜榜均已迁至 Search.vue，首页不再就地搜索
 */
function goSearch() {
  const q = keyword.value.trim()
  router.push(q ? { path: '/search', query: { q } } : '/search')
}

/* ====== 商品加载 ====== */
/**
 * 加载商品
 *
 * @param reset true（默认）= 重置到第一页并覆盖列表（首屏、切分类、清除筛选都走这条）；
 *              false = 触底续接，取下一页并**追加去重**
 *
 * <p>默认值取 true 是刻意的：现有 6 处调用点（初始化、切分类、重置筛选等）都必须重置，
 * 只有哨兵需要追加。这样加参数不必逐个改调用点，也不会漏改出「切了分类还追加旧数据」的 bug。
 */
async function loadProducts(reset = true) {
  // 只有追加路径需要防重入；重置路径保持原行为，不受其它加载影响
  if (!reset && loading.value) {
    return
  }
  loading.value = true
  if (reset) {
    loadFailed.value = false
  }
  try {
    const params: ProductPageQuery = {
      page: reset ? 1 : page.value + 1,
      pageSize
    }
    if (currentCategory.value) {
      params.categoryId = currentCategory.value
    } else if (expandedCategoryId.value) {
      params.firstCategoryId = expandedCategoryId.value
    }
    const res = await productApi.page(params)
    const incoming = res.data?.list || []
    // ⚠️ 后端 Long→String，total 需 Number() 转换
    total.value = Number(res.data?.total) || 0

    if (reset) {
      productList.value = incoming
    } else {
      // 去重后追加：分页边界与并发场景都可能带回已展示的商品
      const seen = new Set(productList.value.map((p) => p.id))
      productList.value = [...productList.value, ...incoming.filter((p) => !seen.has(p.id))]
    }
    // ProductPageQuery.page 是可选字段，取出后落到本地页码（已是 number）
    page.value = params.page ?? 1
  } catch (e) {
    console.error('加载商品列表失败:', e)
    if (reset) {
      productList.value = []
      total.value = 0
    } else {
      // 续接失败只标记，保留已展示内容
      loadFailed.value = true
    }
  } finally {
    loading.value = false
  }

  // 追加后哨兵可能仍在视口内（列表短、屏幕高），此时不产生新的交叉变化，
  // 观察器不会再回调 —— 重新 observe 强制它按当前状态再评估一次。
  // 配合 hasMore 的页码式判定，即便某页无新数据也会推进页码，不会死循环。
  if (!reset && !loadFailed.value && hasMore.value) {
    await nextTick()
    const el = sentinelRef.value
    if (el && feedObserver) {
      feedObserver.unobserve(el)
      feedObserver.observe(el)
    }
  }
}

/**
 * 建立触底观察
 *
 * <p>用 IntersectionObserver 而非监听 scroll：scroll 在滚动期间逐帧触发，长列表下反复
 * 读布局会拖慢主线程。rootMargin 提前 240px 预加载，避免滑到底才看到空白。
 */
function setupFeedObserver() {
  const el = sentinelRef.value
  if (!el || typeof IntersectionObserver === 'undefined') {
    return
  }
  feedObserver = new IntersectionObserver(
    (entries) => {
      const hit = entries.some((e) => e.isIntersecting)
      if (hit && hasMore.value && !loading.value && !loadFailed.value) {
        loadProducts(false)
      }
    },
    { rootMargin: '240px 0px' },
  )
  feedObserver.observe(el)
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
  currentCategory.value = null
  expandedCategoryId.value = null
  page.value = 1
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
    // 加购行为埋点（静默旁路，失败不影响主流程；仅在真正加购成功后上报）
    trackAddCart(product.id)
  } catch {
    // 错误提示由 axios 拦截器统一处理
  }
}

/* ====== GSAP Hero 动画 ====== */
function initHeroAnimation() {
  if (!pageRef.value) return
  ctx = gsap.context(() => {
    // 首屏入场总时长控制在 0.6s 内（原实现逐段错开叠加到 1.8s，点击首页要等约 2s 才成型）
    const tl = gsap.timeline({ defaults: { ease: 'power3.out' } })
    tl.from('.hero-eyebrow', { y: 14, opacity: 0, duration: 0.25 })
      .from('.hero-title', { y: 18, opacity: 0, duration: 0.28 }, '-=0.18')
      .from('.hero-desc', { y: 12, opacity: 0, duration: 0.25 }, '-=0.18')
      .from('.hero-search-wrap', { y: 10, opacity: 0, duration: 0.28 }, '-=0.2')
      .from('.hero-stats', { y: 10, opacity: 0, duration: 0.26 }, '-=0.2')
    // 右侧装饰卡片 / 条形 / 徽章
    gsap.from('.hero-card', { y: 16, opacity: 0, duration: 0.3, delay: 0.1 })
    gsap.from('.bar', {
      scaleY: 0,
      transformOrigin: 'bottom',
      duration: 0.3,
      stagger: 0.04,
      ease: 'power2.out',
      delay: 0.15
    })
    gsap.from('.hero-chip', {
      y: 10,
      opacity: 0,
      duration: 0.24,
      stagger: 0.06,
      ease: 'power2.out',
      delay: 0.2
    })
    // 背景光斑浮动
    gsap.to('.blob-1', { y: -14, duration: 4, repeat: -1, yoyo: true, ease: 'sine.inOut' })
    gsap.to('.blob-2', { y: 10, duration: 3.2, repeat: -1, yoyo: true, ease: 'sine.inOut', delay: 0.6 })
  }, pageRef.value)
}

/* ====== 生命周期 ====== */
function initPage() {
  // 入场动画与数据加载并行启动：原实现等三个接口全部返回后才播动画，
  // 首屏因此串行等待「接口 0.3s + 动画 1.8s」，现改为挂载即开始
  nextTick(() => initHeroAnimation())
  loadCategories()
  loadProducts()
  loadTotalProducts()
}

onMounted(() => {
  initPage()
  setupFeedObserver()
})

onUnmounted(() => {
  ctx?.revert()
  // 必须断开：否则路由离开首页后，已卸载组件的加载函数仍会被滚动触发
  feedObserver?.disconnect()
  feedObserver = null
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

/* 搜索框（点击跳转检索页，结果不在本页渲染） */
.hero-search-wrap { max-width: 540px; position: relative; }

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
.search-input-box:focus-within {
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

/* ====== 信息流触底哨兵 ====== */
.feed-sentinel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 56px;
  margin-top: 8px;
}
.feed-sentinel-text {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
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
