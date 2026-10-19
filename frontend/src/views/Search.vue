<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NSpin } from 'naive-ui'
import { productApi } from '@/api/product'
import ProductCard from '@/components/ProductCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { trackView } from '@/utils/behaviorTracker'
import SvgIcon from '@/components/SvgIcon.vue'
import type { ProductVO, RagHitVO } from '@/api/types'

const route = useRoute()
const router = useRouter()

/* ====== 常量 ====== */
const HISTORY_KEY = 'shop_search_history'
const HISTORY_MAX = 10
/** 召回条数档位：点一次「加载更多」递增 20，上限 60（后端 rag-search 不支持真分页） */
const SIZE_STEP = 20
const SIZE_MAX = 60
/** 热搜榜（静态占位数据，后续可接后端热搜接口） */
const HOT_WORDS = ['手机', '耳机', '手表', '运动鞋', '精华', '笔记本', '拉面', '沙发']

type SortKey = 'relevance' | 'price_asc' | 'price_desc'

/**
 * 排序档位
 * ⚠️ 全部在「已召回结果集内」本地排序，不触发重新请求
 * —— rag-search 只返回 topK 条命中，没有销量/时间字段，做不了跨全量的排序
 */
const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: 'relevance', label: '相关性' },
  { key: 'price_asc', label: '价格低→高' },
  { key: 'price_desc', label: '价格高→低' }
]

/* ====== 状态 ====== */
/** 输入框内容（未提交前与 URL 无关） */
const keyword = ref('')
/** RAG 命中原始列表（保持后端相关性顺序，排序在 computed 里做） */
const hits = ref<RagHitVO[]>([])
const loading = ref(false)
const topK = ref(SIZE_STEP)
const searchHistory = ref<string[]>([])
const panelOpen = ref(false)
const searchWrapRef = ref<HTMLElement | null>(null)

/* ====== URL 派生状态（URL 是查询条件的唯一事实来源） ====== */
/** 当前生效的查询词 */
const activeQuery = computed(() =>
  typeof route.query.q === 'string' ? route.query.q.trim() : ''
)

/** 当前排序档位，非法值回落「相关性」 */
const sort = computed<SortKey>(() => {
  const raw = route.query.sort
  const hit = SORT_OPTIONS.find(o => o.key === raw)
  return hit ? hit.key : 'relevance'
})

/** 排序后的命中列表 */
const sortedHits = computed(() => {
  const list = [...hits.value]
  if (sort.value === 'price_asc') {
    return list.sort((a, b) => Number(a.minPrice || 0) - Number(b.minPrice || 0))
  }
  if (sort.value === 'price_desc') {
    return list.sort((a, b) => Number(b.minPrice || 0) - Number(a.minPrice || 0))
  }
  return list
})

/**
 * RAG 命中项 → ProductVO（供 ProductCard 渲染）
 * ⚠️ rag-search 返回体是轻量结构，无 SKU / 品牌 / 销量 / 分类名，缺失字段一律填占位值：
 * - skuList 必须留空数组：ProductCard 的 isSoldOut 要求 skuList 非空，否则会把整页误标「已售罄」
 * - 命中项因此不显示加购（由 hide-add-cart 关掉），这是刻意的，不是遗漏
 * - status 填 1（在售）：商品在 RagHitEnricher 回查阶段已被过滤为仅上架，此处是对齐事实；
 *   代价是每张卡都会挂「在售」角标，属已知的视觉噪音，可接受
 */
function toProductVO(hit: RagHitVO): ProductVO {
  return {
    id: hit.productId,
    name: hit.name || '',
    description: '',
    categoryId: '',
    categoryName: '',
    brandId: '',
    brandName: '',
    mainImage: hit.image || '',
    images: [],
    detail: '',
    minPrice: hit.minPrice,
    maxPrice: hit.maxPrice,
    totalStock: 0,
    soldCount: 0,
    status: 1,
    skuList: []
  }
}

const productList = computed<ProductVO[]>(() => sortedHits.value.map(toProductVO))

/* ====== 搜索历史 ====== */
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

/* ====== 检索 ====== */
// FIXME: 后端向量路失败时会静默降级到 MySQL FULLTEXT 关键词路，而 rag-search 返回体
//        没有降级标志位（RagSearchController 只塞了 hits/total/query 三个 key），
//        前端无从判断本次走的是哪条路，也就没法提示用户"已切换为关键词匹配"。
//        修法：后端在返回体补一个 degraded / route 字段，前端据此显示降级提示。
async function loadResults() {
  const q = activeQuery.value
  if (!q) {
    hits.value = []
    return
  }
  loading.value = true
  try {
    const res = await productApi.ragSearch({ q, topK: topK.value })
    hits.value = res.data?.hits || []
    reportSearchImpressions(q)
  } catch (e) {
    // 失败必须清空，否则旧结果会被当成新词的结果展示
    console.error('智能搜索失败:', e)
    hits.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 搜索意图埋点：搜索结果曝光前 N 个商品，写入推荐子系统的行为流
 *
 * <p>搜索页原先只写 rag_trace（RAG 反馈），不进入推荐画像；用户搜索后
 * 「猜你喜欢」不会变化。这里对首次加载的结果上报 view，让搜索意图实时
 * 进入用户类目偏好。加载更多、同一关键词重复请求不重复上报，避免过度加权。</p>
 */
const SEARCH_IMPRESSION_LIMIT = 3
let impressedQuery = ''
function reportSearchImpressions(query: string) {
  if (topK.value !== SIZE_STEP || query === impressedQuery) {
    return
  }
  impressedQuery = query
  hits.value.slice(0, SEARCH_IMPRESSION_LIMIT).forEach((hit) => {
    // duration=0：曝光不是真实停留，不参与停留时长判定
    trackView(hit.productId, 0)
  })
}

/** 提交搜索：只改 URL，加载由 watch(activeQuery) 统一触发，避免同一状态两个写入方 */
function submitSearch(word?: string) {
  const q = (word ?? keyword.value).trim()
  panelOpen.value = false
  if (!q) return
  recordHistory(q)
  if (q === activeQuery.value) {
    // 重复搜同一个词：URL 不变不会触发 watch，这里主动刷新
    topK.value = SIZE_STEP
    loadResults()
    return
  }
  router.push({ path: '/search', query: { q, sort: sort.value } })
}

/** 切换排序：仅本地重排，不重新请求；replace 避免污染浏览器后退栈 */
function changeSort(key: SortKey) {
  if (key === sort.value) return
  router.replace({ path: '/search', query: { q: activeQuery.value, sort: key } })
}

/** 加载更多：递增 topK 重新请求（后端不支持真分页） */
function loadMore() {
  if (topK.value >= SIZE_MAX) return
  topK.value = Math.min(topK.value + SIZE_STEP, SIZE_MAX)
  loadResults()
}

/** 点历史/热搜词：直接以该词发起检索 */
function searchWord(word: string) {
  keyword.value = word
  submitSearch(word)
}

/* ====== 埋点（Phase 4：检索结果点击反馈，异步旁路，失败不影响跳转） ====== */
function traceClick(product: ProductVO) {
  const token = localStorage.getItem('token')
  const baseURL = import.meta.env.VITE_API_BASE || ''
  fetch(baseURL + '/api/rag/trace', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { token: token } : {})
    },
    body: JSON.stringify({ query: activeQuery.value, productId: product.id, action: 'SEARCH_CLICK' })
  }).catch(() => {})
}

/* ====== 点击面板外部关闭 ====== */
function onDocClick(e: MouseEvent) {
  if (searchWrapRef.value && !searchWrapRef.value.contains(e.target as Node)) {
    panelOpen.value = false
  }
}

/* ====== 生命周期 ====== */
function initPage() {
  loadHistory()
  document.addEventListener('click', onDocClick)
  const q = activeQuery.value
  keyword.value = q
  if (q) {
    recordHistory(q)
    loadResults()
  }
}

onMounted(initPage)

// URL 查询词变化（本页搜索 / 浏览器后退 / 直接访问带参链接）统一在此加载
watch(activeQuery, (q) => {
  topK.value = SIZE_STEP
  keyword.value = q
  if (q) {
    recordHistory(q)
    loadResults()
  } else {
    hits.value = []
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div class="page-container fade-in">
    <!-- ====== 搜索区 ====== -->
    <section class="search-hero">
      <div class="search-wrap" ref="searchWrapRef">
        <div class="search-row">
          <div class="search-input-box" :class="{ focused: panelOpen }">
            <SvgIcon name="search" :size="18" class="search-icon" />
            <input
              v-model="keyword"
              class="search-input"
              placeholder="搜索商品描述，例如：便宜的手机…"
              @focus="panelOpen = true"
              @keyup.enter="submitSearch()"
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
          <button type="button" class="search-btn" @click="submitSearch()">
            智能搜索
            <SvgIcon name="search" :size="16" />
          </button>
        </div>
        <p class="search-hint">基于商品描述语义匹配，不是关键词精确匹配</p>

        <!-- 下拉面板：搜索历史 / 热搜榜 -->
        <transition name="panel">
          <div v-if="panelOpen" class="search-panel">
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
                <span v-for="h in searchHistory" :key="h" class="sp-tag" @click="searchWord(h)">
                  {{ h }}
                </span>
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
    </section>

    <!-- ====== 未发起搜索：引导态（直接访问 /search 时不留白屏） ====== -->
    <section v-if="!activeQuery" class="guide">
      <div v-if="searchHistory.length" class="guide-block">
        <h2 class="guide-title">最近的搜索</h2>
        <div class="guide-tags">
          <button
            v-for="h in searchHistory"
            :key="h"
            type="button"
            class="guide-tag"
            @click="searchWord(h)"
          >{{ h }}</button>
        </div>
      </div>

      <div class="guide-block">
        <h2 class="guide-title">大家在搜</h2>
        <div class="guide-tags">
          <button
            v-for="w in HOT_WORDS"
            :key="w"
            type="button"
            class="guide-tag guide-tag--hot"
            @click="searchWord(w)"
          >{{ w }}</button>
        </div>
      </div>

      <p class="guide-tip">
        <SvgIcon name="star" :size="14" />
        试试描述你想要的东西，比如「适合跑步的轻便鞋子」「便宜的蓝牙耳机」
      </p>
    </section>

    <!-- ====== 检索结果 ====== -->
    <section v-else class="result-area">
      <div class="result-bar">
        <span class="result-summary">
          「{{ activeQuery }}」找到 <strong>{{ hits.length }}</strong> 件商品
        </span>
        <div class="sort-group">
          <button
            v-for="opt in SORT_OPTIONS"
            :key="opt.key"
            type="button"
            class="sort-item"
            :class="{ active: sort === opt.key }"
            @click="changeSort(opt.key)"
          >{{ opt.label }}</button>
        </div>
      </div>

      <div class="grid-area">
        <n-spin :show="loading">
          <div v-if="productList.length" class="product-grid">
            <ProductCard
              v-for="p in productList"
              :key="p.id"
              :product="p"
              :hide-add-cart="true"
              @detail-click="traceClick"
            />
          </div>
        </n-spin>

        <EmptyState
          v-if="!loading && productList.length === 0"
          text="没有找到相关商品"
          description="换个说法试试，比如把「便宜的手机」改成「性价比手机」"
        />

        <div v-if="!loading && productList.length" class="load-more">
          <button v-if="topK < SIZE_MAX" type="button" class="more-btn" @click="loadMore">
            加载更多（{{ topK + SIZE_STEP }} 条）
          </button>
          <span v-else class="more-done">— 已显示全部 —</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ====== 搜索区 ====== */
.search-hero {
  padding: 28px 0 8px;
}
.search-wrap {
  position: relative;
  max-width: 720px;
  margin: 0 auto;
}
.search-row {
  display: flex;
  gap: 10px;
}
.search-input-box {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 48px;
  padding: 0 16px;
  background: var(--ds-card, #fff);
  border: 1.5px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  transition: all 0.2s ease;
}
.search-input-box.focused {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: 0 0 0 4px rgba(45, 127, 249, 0.1);
}
.search-icon {
  color: var(--ds-primary, #2d7ff9);
  flex-shrink: 0;
}
.search-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15px;
  color: var(--ds-text, #1e2632);
}
.search-input::placeholder {
  color: var(--ds-text-3, #98a1af);
}
.search-clear {
  border: none;
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s;
}
.search-clear:hover {
  background: var(--ds-line, #e7e9ee);
  color: var(--ds-text-2, #5a6474);
}
.search-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 48px;
  padding: 0 26px;
  border: none;
  border-radius: var(--ds-r-lg, 16px);
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
  box-shadow: var(--ds-shadow-brand, 0 6px 18px rgba(45, 127, 249, 0.25));
}
.search-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}
.search-hint {
  margin: 8px 4px 0;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

/* 下拉面板 */
.search-panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  z-index: 20;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  box-shadow: var(--ds-shadow-lg, 0 16px 40px rgba(31, 38, 48, 0.12));
  padding: 16px;
}
.sp-section + .sp-section {
  margin-top: 16px;
  padding-top: 16px;
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
  font-weight: 600;
  color: var(--ds-text-2, #5a6474);
}
.sp-title svg {
  color: var(--ds-primary, #2d7ff9);
}
.sp-clear {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: none;
  background: transparent;
  color: var(--ds-text-3, #98a1af);
  font-size: 12px;
  cursor: pointer;
  transition: color 0.2s;
}
.sp-clear:hover {
  color: var(--ds-coral, #e94560);
}
.sp-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.sp-tag {
  padding: 5px 12px;
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-2, #5a6474);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.sp-tag:hover {
  background: rgba(45, 127, 249, 0.1);
  color: var(--ds-primary, #2d7ff9);
}
.sp-hot {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}
.sp-hot-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border: none;
  background: transparent;
  border-radius: 10px;
  cursor: pointer;
  text-align: left;
  transition: background 0.2s;
}
.sp-hot-item:hover {
  background: var(--ds-bg-2, #eef0f4);
}
.hot-rank {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
  font-size: 11px;
  font-weight: 700;
}
.hot-rank.top {
  background: var(--ds-coral, #e94560);
  color: #fff;
}
.hot-name {
  font-size: 13px;
  color: var(--ds-text, #1e2632);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.panel-enter-active,
.panel-leave-active {
  transition: all 0.2s ease;
}
.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ====== 引导态 ====== */
.guide {
  max-width: 720px;
  margin: 0 auto;
  padding: 16px 0 40px;
}
.guide-block + .guide-block {
  margin-top: 28px;
}
.guide-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text-2, #5a6474);
  margin: 0 0 12px;
}
.guide-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.guide-tag {
  padding: 8px 16px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-card, #fff);
  color: var(--ds-text, #1e2632);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}
.guide-tag:hover {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
  transform: translateY(-1px);
}
.guide-tag--hot {
  background: rgba(233, 69, 96, 0.05);
  border-color: rgba(233, 69, 96, 0.2);
  color: var(--ds-coral, #e94560);
}
.guide-tag--hot:hover {
  border-color: var(--ds-coral, #e94560);
  color: var(--ds-coral, #e94560);
}
.guide-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 32px 0 0;
  padding: 14px 18px;
  border-radius: var(--ds-r-lg, 16px);
  background: rgba(45, 127, 249, 0.06);
  color: var(--ds-text-2, #5a6474);
  font-size: 13px;
  line-height: 1.6;
}
.guide-tip svg {
  color: var(--ds-primary, #2d7ff9);
  flex-shrink: 0;
}

/* ====== 结果区 ====== */
.result-area {
  padding-top: 12px;
}
.result-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  padding: 0 2px;
}
.result-summary {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
}
.result-summary strong {
  color: var(--ds-primary, #2d7ff9);
  font-size: 15px;
  margin: 0 2px;
}
.sort-group {
  display: inline-flex;
  gap: 6px;
  padding: 3px;
  background: var(--ds-bg-2, #eef0f4);
  border-radius: var(--ds-r-pill, 999px);
}
.sort-item {
  border: none;
  background: transparent;
  color: var(--ds-text-2, #5a6474);
  font-size: 12px;
  font-weight: 600;
  padding: 6px 14px;
  border-radius: var(--ds-r-pill, 999px);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}
.sort-item:hover {
  color: var(--ds-primary, #2d7ff9);
}
.sort-item.active {
  background: var(--ds-card, #fff);
  color: var(--ds-primary, #2d7ff9);
  box-shadow: 0 1px 4px rgba(31, 38, 48, 0.08);
}

/* 商品网格：与首页保持一致（4 列 / 间距 20px） */
.grid-area {
  min-height: 240px;
}
.product-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}

/* 加载更多 */
.load-more {
  display: flex;
  justify-content: center;
  padding: 28px 0 8px;
}
.more-btn {
  border: 1px solid var(--ds-line, #e7e9ee);
  background: var(--ds-card, #fff);
  color: var(--ds-text-2, #5a6474);
  font-size: 13px;
  font-weight: 600;
  padding: 10px 28px;
  border-radius: var(--ds-r-pill, 999px);
  cursor: pointer;
  transition: all 0.2s;
}
.more-btn:hover {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
}
.more-done {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

/* ====== 响应式（断点与首页对齐） ====== */
@media (max-width: 1200px) {
  .product-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 900px) {
  .product-grid { grid-template-columns: repeat(2, 1fr); }
  .result-bar { flex-direction: column; align-items: flex-start; }
}
@media (max-width: 500px) {
  .product-grid { grid-template-columns: 1fr; }
  .search-row { flex-direction: column; }
  .search-btn { justify-content: center; }
  .sp-hot { grid-template-columns: 1fr; }
}
</style>
