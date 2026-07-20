<template>
  <div class="page-container fade-in">
    <!-- ====== Hero 横幅 ====== -->
    <section class="hero">
      <div class="hero-content">
        <h1 class="hero-title">发现好物</h1>
        <p class="hero-desc">精选商品，品质生活从这里开始</p>
        <div class="hero-search">
          <div class="search-mode-toggle">
            <span
              class="mode-option"
              :class="{ active: searchMode === 'keyword' }"
              @click="searchMode = 'keyword'"
            >关键词</span>
            <span class="mode-divider">|</span>
            <span
              class="mode-option"
              :class="{ active: searchMode === 'semantic' }"
              @click="searchMode = 'semantic'"
            >智能</span>
          </div>
          <el-input
            v-model="keyword"
            :placeholder="searchMode === 'semantic' ? '搜索商品描述，例如：便宜的手机...' : '搜索商品名称...'"
            clearable
            class="hero-search-input"
            :prefix-icon="Search"
            @keyup.enter="handleSearch"
          />
          <el-button type="primary" class="hero-search-btn" @click="handleSearch">
            {{ searchMode === 'semantic' ? '智能搜索' : '搜索' }}
          </el-button>
        </div>
        <div v-if="searchMode === 'semantic' && keyword" class="search-mode-hint">
          🔍 智能搜索模式 — 基于语义匹配商品描述，非关键词精确匹配
        </div>
      </div>
      <div class="hero-decoration">
        <span class="hero-dot dot-1"></span>
        <span class="hero-dot dot-2"></span>
        <span class="hero-dot dot-3"></span>
      </div>
    </section>

    <!-- ====== 分类 + 商品区域 ====== -->
    <div class="shop-layout">
      <!-- 左侧分类 -->
      <aside class="category-panel">
        <div class="category-header">
          <svg class="category-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="7" height="7" rx="1"/>
            <rect x="14" y="3" width="7" height="7" rx="1"/>
            <rect x="3" y="14" width="7" height="7" rx="1"/>
            <rect x="14" y="14" width="7" height="7" rx="1"/>
          </svg>
          <span>全部分类</span>
        </div>

        <div class="category-list">
          <div
            v-for="cat in topCategories"
            :key="cat.id"
            class="category-item-wrap"
          >
            <div
              class="category-item"
              :class="{
                active: expandedCategoryId === cat.id && !currentCategory,
                expanded: expandedCategoryId === cat.id
              }"
              @click="toggleCategory(cat)"
            >
              <span class="category-name">{{ cat.name }}</span>
              <svg
                class="category-arrow"
                :class="{ rotated: expandedCategoryId === cat.id }"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <polyline points="9 18 15 12 9 6"/>
              </svg>
            </div>
            <!-- 子分类展开在当前一级分类下 -->
            <transition name="sub-slide">
              <div v-if="expandedCategoryId === cat.id" class="subcategory-panel">
                <div
                  class="subcategory-item active"
                  @click="selectSubCategory(null)"
                >
                  全部{{ cat.name }}
                </div>
                <div
                  v-for="sub in subCategories"
                  :key="sub.id"
                  class="subcategory-item"
                  :class="{ selected: currentCategory === sub.id }"
                  @click="selectSubCategory(sub)"
                >
                  {{ sub.name }}
                </div>
              </div>
            </transition>
          </div>
        </div>

        <!-- 热门标签 -->
        <div v-if="hotTags.length" class="hot-tags">
          <div class="hot-tags-label">🔥 热门</div>
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
          <div
            class="category-all"
            :class="{ active: expandedCategoryId === null && currentCategory === null }"
            @click="selectAllCategories"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
              <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>
            </svg>
            全部商品
          </div>
        </div>
      </aside>

      <!-- 右侧内容 -->
      <div class="main-content">
        <!-- 商品网格 -->
        <div v-loading="loading" class="product-grid" element-loading-text="加载商品中…">
          <div
            v-for="p in productList"
            :key="p.id"
            class="product-card"
            @click="$router.push('/product/' + p.id)"
          >
            <div class="product-image-wrap">
              <el-image
                :src="p.mainImage || '/placeholder.png'"
                class="product-image"
                fit="cover"
                loading="lazy"
              >
                <template #placeholder>
                  <div class="image-placeholder skeleton-pulse" />
                </template>
              </el-image>
              <div class="product-badge" v-if="p.status === 1">
                <span>在售</span>
              </div>
            </div>

            <div class="product-body">
              <h3 class="product-name">{{ p.name }}</h3>
              <div class="product-meta">
                <span class="product-brand">{{ p.brand || '通用' }}</span>
                <span class="product-sold">已售 {{ p.soldCount || 0 }}</span>
              </div>
              <div class="product-pricing">
                <span class="price-symbol">¥</span>
                <span class="price-value">{{ formatPrice(p.minPrice) }}</span>
                <span class="price-original" v-if="p.maxPrice && +p.maxPrice > +p.minPrice">
                  ¥{{ formatPrice(p.maxPrice) }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- 空状态 -->
        <el-empty v-if="!loading && productList.length === 0" description="暂无商品">
          <el-button type="primary" class="ds-btn" @click="loadProducts">刷新看看</el-button>
        </el-empty>

        <!-- 分页 -->
        <div class="pagination-wrapper">
          <el-pagination
            v-model:current-page="page"
            :page-size="12"
            :total="Number(total)"
            layout="prev, pager, next"
            @current-change="loadProducts"
            background
            :hide-on-single-page="false"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { productApi } from '@/api/product'
import { categoryApi } from '@/api/category'

const productList = ref([])
const categories = ref([])
const loading = ref(false)
const keyword = ref('')
const searchMode = ref('keyword') // 'keyword' | 'semantic'
const currentCategory = ref(null)
const expandedCategoryId = ref(null)
const page = ref(1)
const pageSize = ref(12)
const total = ref(0)

const topCategories = computed(() =>
  categories.value.filter(c => c.parentId == 0 || c.parentId === null)
)

const subCategories = computed(() => {
  if (!expandedCategoryId.value) return []
  return categories.value.filter(c => c.parentId === expandedCategoryId.value)
})

const hotTags = computed(() => topCategories.value.slice(0, 6))

function formatPrice(val) {
  const n = Number(val)
  return Number.isInteger(n) ? n.toString() : n.toFixed(2)
}

async function loadProducts() {
  loading.value = true
  try {
    const params = {
      page: page.value || 1,
      pageSize: pageSize.value || 12,
      keyword: keyword.value || undefined
    }
    if (currentCategory.value) {
      params.categoryId = currentCategory.value
    } else if (expandedCategoryId.value) {
      params.firstCategoryId = expandedCategoryId.value
    }
    const res = await productApi.page(params)
    productList.value = res.data?.list || []
    total.value = Number(res.data?.total) || 0
  } catch (e) {
    console.error('加载商品列表失败:', e)
    productList.value = []
    total.value = 0
  } finally {
    loading.value = false
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

function selectAllCategories() {
  expandedCategoryId.value = null
  currentCategory.value = null
  page.value = 1
  loadProducts()
}

function toggleCategory(cat) {
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

function selectSubCategory(sub) {
  currentCategory.value = sub ? sub.id : null
  page.value = 1
  loadProducts()
}

function selectHotTag(tag) {
  expandedCategoryId.value = tag.id
  currentCategory.value = null
  page.value = 1
  loadProducts()
}

async function handleSearch() {
  if (searchMode.value === 'semantic' && keyword.value.trim()) {
    // 语义搜索模式 — 调用 RAG 向量检索
    loading.value = true
    try {
      const res = await productApi.ragSearch({ q: keyword.value.trim(), topK: 20 })
      const hits = res.data?.hits || []
      productList.value = hits.map(h => ({
        id: h.productId,
        name: h.name || '',
        mainImage: h.image || '/placeholder.png',
        minPrice: h.minPrice,
        maxPrice: h.maxPrice,
        brand: '',
        soldCount: 0,
        status: 1
      }))
      total.value = Number(res.data?.total) || hits.length
    } catch (e) {
      console.error('语义搜索失败:', e)
      productList.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
    return
  }
  // 关键词模式 — 传统分页搜索
  page.value = 1
  loadProducts()
}

onMounted(() => {
  loadCategories()
  loadProducts()
})
</script>

<style scoped>
/* ====== Hero 区域 ====== */
.hero {
  position: relative;
  background: linear-gradient(135deg, #1a1a2e 0%, #2d2d50 50%, #1a1a2e 100%);
  border-radius: var(--ds-radius-xl, 20px);
  padding: 48px 44px;
  margin-bottom: 28px;
  overflow: hidden;
  isolation: isolate;
}
.hero-content {
  position: relative;
  z-index: 1;
}
.hero-title {
  font-size: 34px;
  font-weight: 800;
  color: #fff;
  margin-bottom: 8px;
  letter-spacing: -0.5px;
}
.hero-desc {
  font-size: 16px;
  color: rgba(255, 255, 255, 0.65);
  margin-bottom: 24px;
}
.search-mode-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.mode-option {
  font-size: 13px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
  padding: 2px 0;
}
.mode-option:hover { color: rgba(255, 255, 255, 0.8); }
.mode-option.active {
  color: #fff;
  font-weight: 700;
  position: relative;
}
.mode-option.active::after {
  content: '';
  position: absolute;
  bottom: -2px;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--ds-coral, #e94560);
  border-radius: 1px;
}
.mode-divider {
  color: rgba(255, 255, 255, 0.25);
  font-size: 13px;
}
.search-mode-hint {
  margin-top: 10px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
  letter-spacing: 0.3px;
}
.hero-search {
  display: flex;
  gap: 10px;
  max-width: 480px;
}
.hero-search-input {
  flex: 1;
}
.hero-search-input :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15) !important;
  box-shadow: none !important;
  height: 48px;
  border-radius: 10px;
}
.hero-search-input :deep(.el-input__inner) {
  color: #fff;
  font-size: 15px;
}
.hero-search-input :deep(.el-input__inner::placeholder) {
  color: rgba(255, 255, 255, 0.4);
}
.hero-search-input :deep(.el-input__prefix-inner) {
  color: rgba(255, 255, 255, 0.5);
}
.hero-search-btn {
  height: 48px;
  padding: 0 28px;
  border-radius: 10px;
  font-weight: 600;
  font-size: 15px;
}

/* 装饰圆点 */
.hero-decoration { position: absolute; inset: 0; pointer-events: none; z-index: 0; }
.hero-dot {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.03);
}
.dot-1 { width: 300px; height: 300px; top: -80px; right: -60px; }
.dot-2 { width: 180px; height: 180px; bottom: -40px; right: 120px; background: rgba(233, 69, 96, 0.06); }
.dot-3 { width: 80px; height: 80px; top: 50%; right: 40%; background: rgba(233, 69, 96, 0.08); }

/* ====== 布局 ====== */
.shop-layout {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

/* ====== 分类面板 ====== */
.category-panel {
  width: 220px;
  flex-shrink: 0;
  background: var(--ds-surface, #fff);
  border: 1px solid var(--ds-border-light, #f0eee9);
  border-radius: var(--ds-radius-lg, 16px);
  overflow: hidden;
  position: sticky;
  top: 20px;
  transition: var(--ds-transition, all 0.3s);
}

.category-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 18px 12px;
  font-size: 15px;
  font-weight: 700;
  color: var(--ds-navy, #1a1a2e);
  border-bottom: 1px solid var(--ds-border-light, #f0eee9);
}
.category-icon { width: 18px; height: 18px; }

.category-list { padding: 4px 0; }

.category-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 18px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 14px;
  color: var(--ds-text, #1a1a2e);
  user-select: none;
  position: relative;
}
.category-item:hover {
  background: var(--ds-surface-hover, #faf9f7);
  color: var(--ds-coral, #e94560);
}
.category-item.active {
  background: rgba(233, 69, 96, 0.06);
  color: var(--ds-coral, #e94560);
  font-weight: 600;
}
.category-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  background: var(--ds-coral, #e94560);
  border-radius: 0 3px 3px 0;
}
.category-item .category-name { flex: 1; }
.category-item-wrap { position: relative; }

.category-arrow {
  width: 14px;
  height: 14px;
  color: var(--ds-text-muted, #b2bec3);
  transition: transform 0.25s;
  flex-shrink: 0;
}
.category-arrow.rotated { transform: rotate(90deg); color: var(--ds-coral, #e94560); }

/* 子分类面板 - 在当前一级分类下方展开 */
.subcategory-panel {
  background: var(--ds-surface-hover, #faf9f7);
  border-left: 2px solid var(--ds-coral, #e94560);
  margin: 0 8px 4px;
  border-radius: 8px;
  overflow: hidden;
}
.sub-slide-enter-active,
.sub-slide-leave-active {
  transition: all 0.25s ease;
}
.sub-slide-enter-from,
.sub-slide-leave-to {
  max-height: 0;
  opacity: 0;
}
.sub-slide-enter-to,
.sub-slide-leave-from {
  max-height: 400px;
  opacity: 1;
}

.subcategory-item {
  padding: 8px 18px 8px 28px;
  font-size: 13px;
  color: var(--ds-text-secondary, #636e72);
  cursor: pointer;
  transition: all 0.2s;
}
.subcategory-item:hover { background: rgba(233, 69, 96, 0.04); color: var(--ds-coral, #e94560); }
.subcategory-item.selected { color: var(--ds-coral, #e94560); font-weight: 600; }
.subcategory-item.active {
  color: var(--ds-text-secondary, #636e72);
  font-style: italic;
  font-size: 12px;
}

/* 热门标签 */
.hot-tags {
  border-top: 1px solid var(--ds-border-light, #f0eee9);
  padding: 12px 18px;
}
.hot-tags-label { font-size: 12px; color: var(--ds-text-muted, #b2bec3); margin-bottom: 8px; }
.hot-tags-list { display: flex; flex-wrap: wrap; gap: 6px; }
.hot-tag {
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 11px;
  cursor: pointer;
  background: #f5f3ef;
  color: var(--ds-text-secondary, #636e72);
  transition: all 0.2s;
}
.hot-tag:hover { background: var(--ds-coral, #e94560); color: #fff; }
.hot-tag.active { background: var(--ds-navy, #1a1a2e); color: #fff; }

.category-footer { border-top: 1px solid var(--ds-border-light, #f0eee9); padding: 10px 18px; }
.category-all {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ds-text-secondary, #636e72);
  cursor: pointer;
  transition: all 0.2s;
}
.category-all:hover { color: var(--ds-coral, #e94560); }
.category-all.active { color: var(--ds-coral, #e94560); font-weight: 600; }

/* ====== 主内容区 ====== */
.main-content { flex: 1; min-width: 0; }

/* ====== 商品网格 ====== */
.product-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  min-height: 200px;
}

/* 商品卡片 */
.product-card {
  background: var(--ds-surface, #fff);
  border: 1px solid var(--ds-border-light, #f0eee9);
  border-radius: var(--ds-radius-lg, 16px);
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.3s ease;
  display: flex;
  flex-direction: column;
}
.product-card:hover {
  transform: translateY(-6px);
  box-shadow: var(--ds-shadow-lg, 0 12px 40px rgba(26,26,46,0.12));
}

/* 商品图片 */
.product-image-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  overflow: hidden;
  background: #f8f7f4;
}
.product-image {
  width: 100%;
  height: 100%;
  transition: transform 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.product-card:hover .product-image { transform: scale(1.08); }

.image-placeholder {
  width: 100%;
  height: 100%;
  background: #f0eee9;
}

.product-badge {
  position: absolute;
  top: 10px;
  left: 10px;
  background: var(--ds-coral, #e94560);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 4px;
  letter-spacing: 0.3px;
}

/* 商品信息 */
.product-body {
  padding: 14px 16px 18px;
  flex: 1;
  display: flex;
  flex-direction: column;
}
.product-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text, #1a1a2e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 4px;
}
.product-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ds-text-muted, #b2bec3);
  margin-bottom: 10px;
}
.product-pricing {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin-top: auto;
}
.price-symbol {
  font-size: 13px;
  font-weight: 700;
  color: var(--ds-coral, #e94560);
}
.price-value {
  font-size: 22px;
  font-weight: 800;
  color: var(--ds-coral, #e94560);
  line-height: 1;
}
.price-original {
  font-size: 13px;
  color: var(--ds-text-muted, #b2bec3);
  text-decoration: line-through;
  margin-left: 6px;
}

/* ====== 分页 ====== */
.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 36px;
  padding-bottom: 12px;
}

/* ====== 响应式 ====== */
@media (max-width: 1100px) {
  .product-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 900px) {
  .shop-layout { flex-direction: column; }
  .category-panel { width: 100%; position: static; }
  .product-grid { grid-template-columns: repeat(2, 1fr); }
  .hero { padding: 36px 28px; }
  .hero-title { font-size: 26px; }
}
@media (max-width: 500px) {
  .product-grid { grid-template-columns: 1fr; }
  .hero { padding: 28px 20px; }
  .hero-search { flex-direction: column; }
}
</style>
