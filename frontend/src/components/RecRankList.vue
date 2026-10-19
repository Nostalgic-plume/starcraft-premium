<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { NSpin } from 'naive-ui'
import { fetchHomeRank } from '@/api/rec'
import type { RankItem } from '@/api/rec'
import ProductCard from '@/components/ProductCard.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import type { ProductVO } from '@/api/types'

/**
 * 首页推荐信息流（竖向、无限滚动）
 *
 * <p>数据来自推荐子系统（M-08 榜单查询），经商城后端代理转发；推荐服务不可达时后端自动返回
 * 商城原生热销榜，两种情况的响应结构完全一致，因此本组件无需区分正常与降级。
 *
 * <p><b>排序语义</b>：服务端已把前 N 条（默认 5）换成类目偏好路召回的商品，其后位次为
 * 「类目偏好 + 热度」融合排序。前端不再重排——一旦前端二次排序，服务端配额就白做了。
 *
 * <p><b>快照语义</b>：首屏与「换一批」不传 snapshotId，由服务端按当前实时特征重算——
 * 这样「行为产生后 5 秒内影响推荐」才成立；仅触底续接回传首屏的 snapshotId，
 * 保证同一批次内顺序稳定、不出现重复或漏项。
 * <b>切勿在触底续接时丢掉 snapshotId</b>：那会让服务端重算，表现为滑到第二屏内容顺序突变。
 *
 * <p><b>条数上限</b>：候选池固定为服务端的 recall.pool.size（200），故本流滑到底会自然终止，
 * 不是「无限」——到底后显示终止文案并停止观察，不再发请求。
 *
 * <p><b>关于加购</b>：榜单只返回商品的展示字段（标识/名称/主图/价格），不含规格与库存，
 * 因此点击加购会提示「请到详情页选择」——这是有意为之，库存校验必须在详情页拿到真实规格后进行。
 */

const emit = defineEmits<{
  /** 加购（携带商品对象，由父组件处理登录与库存校验） */
  (e: 'add-cart', product: ProductVO): void
}>()

/**
 * 每页条数
 *
 * <p>由 8 提到 20：竖向信息流一屏能放更多卡片，且候选池上限 200，取 20 只需约 10 次请求
 * 就能铺满整个流；取 8 会变成 25 次请求。
 */
const PAGE_SIZE = 20

const items = ref<RankItem[]>([])
/** 批次快照标识：仅触底续接回传；游客场景为 null */
const snapshotId = ref<string | null>(null)
const pageNum = ref(1)
const hasMore = ref(false)
const loading = ref(false)
/** 首屏加载失败：隐藏整个区块，避免页面上出现一个空标题 */
const failed = ref(false)
/** 续接失败：保留已展示内容，尾部给重试入口 */
const loadFailed = ref(false)

/**
 * 已展示的商品标识
 *
 * <p>用于追加时去重。快照边界处、以及快照过期后服务端重算的场景，都可能让同一商品
 * 出现在两页里；H5 列表里出现重复卡片观感极差，必须按标识去重。
 * 统一转字符串比较——itemId 类型会随降级路径漂移（推荐服务返回数字、商城兜底榜返回字符串）。
 */
const seenIds = new Set<string>()

/** 列表尾部哨兵：进入视口即触发续接 */
const sentinelRef = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/**
 * 加载榜单
 *
 * @param reset true = 重新生成榜单（首屏 / 换一批）；false = 按快照触底续接
 */
async function load(reset: boolean) {
  if (loading.value) {
    return
  }
  loading.value = true
  if (!reset) {
    loadFailed.value = false
  }
  try {
    const res = await fetchHomeRank(
      reset
        ? { pageNum: 1, pageSize: PAGE_SIZE }
        : { snapshotId: snapshotId.value ?? undefined, pageNum: pageNum.value + 1, pageSize: PAGE_SIZE },
    )
    const data = res.data
    const incoming = data?.items || []

    if (reset) {
      seenIds.clear()
      items.value = incoming
      incoming.forEach((it) => seenIds.add(String(it.itemId)))
    } else {
      // 去重后追加：快照边界与重算场景都可能带回已展示过的商品
      const fresh = incoming.filter((it) => !seenIds.has(String(it.itemId)))
      fresh.forEach((it) => seenIds.add(String(it.itemId)))
      items.value = [...items.value, ...fresh]
      if (fresh.length === 0) {
        // 本页没有新内容（已滑到快照尾部，或整页都是已展示过的商品）：
        // 必须在此终止，否则「哨兵仍可见 → 继续请求 → 又返回全重复」会死循环刷接口
        hasMore.value = false
        return
      }
    }

    snapshotId.value = data?.snapshotId ?? null
    pageNum.value = data?.pageNum ?? 1
    hasMore.value = !!data?.hasMore
    failed.value = false
  } catch (e) {
    console.error('加载推荐信息流失败:', e)
    // 首屏失败整块隐藏；续接失败保留已展示内容，用户可继续浏览并重试
    if (reset) {
      failed.value = true
      items.value = []
    } else {
      loadFailed.value = true
    }
  } finally {
    loading.value = false
  }

  // 追加后哨兵可能仍在视口内（列表短、屏幕高），此时不产生新的交叉变化，
  // 观察器不会再回调 —— 重新 observe 强制它按当前状态再评估一次。
  // 上面「本页无新内容即置 hasMore=false」的终止条件保证这样不会死循环。
  if (!reset && !loadFailed.value && hasMore.value) {
    await nextTick()
    const el = sentinelRef.value
    if (el && observer) {
      observer.unobserve(el)
      observer.observe(el)
    }
  }
}

/**
 * 建立触底观察
 *
 * <p>用 IntersectionObserver 而不是监听 scroll 事件：scroll 会在滚动期间逐帧触发，
 * 长列表下反复读布局会拖慢主线程（本组件旧版的跑马灯就吃过这个亏）。
 *
 * <p>rootMargin 提前 240px 预加载，避免用户滑到底才看到「加载中」的空白。
 */
function setupObserver() {
  const sentinel = sentinelRef.value
  if (!sentinel || typeof IntersectionObserver === 'undefined') {
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      const hit = entries.some((e) => e.isIntersecting)
      // 到终点或正在加载时不重复触发，避免同一批被连发多次
      if (hit && hasMore.value && !loading.value && !loadFailed.value) {
        load(false)
      }
    },
    { rootMargin: '240px 0px' },
  )
  observer.observe(sentinel)
}

onMounted(() => {
  load(true)
  setupObserver()
})

onBeforeUnmount(() => {
  // 必须断开：否则路由离开首页后，已卸载组件的加载函数仍会被滚动触发
  observer?.disconnect()
  observer = null
})

/**
 * 榜单商品 → 商品卡数据
 *
 * ⚠️ itemId 类型不稳定（推荐服务返回数字、商城兜底榜返回字符串），统一转字符串。
 */
function toProduct(item: RankItem): ProductVO {
  const price = Number(item.price) || 0
  return {
    id: String(item.itemId),
    name: item.itemName || '',
    description: '',
    categoryId: '',
    categoryName: '',
    brandId: '',
    brandName: '',
    mainImage: item.mainImage || '',
    images: [],
    detail: '',
    minPrice: price,
    maxPrice: price,
    totalStock: 0,
    soldCount: 0,
    status: 1,
    // 空数组而非 undefined：ProductCard 仅在 skuList 非空时判定售罄，
    // 榜单拿不到库存，不能凭 totalStock 占位 0 把整批推荐标成「已售罄」
    skuList: [],
  }
}
</script>

<template>
  <section v-if="!failed && (items.length > 0 || loading)" class="rec-rank">
    <div class="rec-head">
      <div class="rec-title-wrap">
        <h2 class="rec-title">
          <SvgIcon name="star" :size="18" class="rec-title-icon" />
          猜你喜欢
        </h2>
        <p class="rec-sub">基于你的浏览与购买偏好实时生成</p>
      </div>
      <button type="button" class="rec-refresh" :disabled="loading" @click="load(true)">
        换一批
      </button>
    </div>

    <n-spin :show="loading && items.length === 0">
      <div class="rec-grid">
        <ProductCard
          v-for="item in items"
          :key="String(item.itemId)"
          :product="toProduct(item)"
          @add-cart="(p: ProductVO) => emit('add-cart', p)"
        />
      </div>
    </n-spin>

    <!-- 触底哨兵：始终渲染，靠 hasMore 控制行为，避免 v-if 反复增删导致观察器失效 -->
    <div ref="sentinelRef" class="rec-sentinel">
      <span v-if="loading && items.length > 0" class="rec-sentinel-text">正在加载…</span>
      <button
        v-else-if="loadFailed"
        type="button"
        class="rec-retry"
        @click="load(false)"
      >
        加载失败，点击重试
      </button>
      <span v-else-if="!hasMore && items.length > 0" class="rec-sentinel-text">已经到底了</span>
    </div>
  </section>
</template>

<style scoped>
.rec-rank {
  margin: 8px 0 28px;
}

/* 标题区 */
.rec-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.rec-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.2px;
}
.rec-title-icon {
  color: var(--ds-primary, #2d7ff9);
}
.rec-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
.rec-refresh {
  flex-shrink: 0;
  padding: 7px 16px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-card, #ffffff);
  color: var(--ds-text-2, #5b6472);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.rec-refresh:hover:not(:disabled) {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
}
.rec-refresh:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* 单列竖向信息流：只保留一列 */
.rec-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
  /* 纵向留白给卡片 hover 上浮与阴影，避免被容器裁掉 */
  padding: 10px 0 4px;
}

/* 触底哨兵：无内容时不占高度，有文案时给出可读的间距 */
.rec-sentinel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 48px;
  padding: 12px 0 4px;
}
.rec-sentinel-text {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}
.rec-retry {
  padding: 8px 24px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-card, #ffffff);
  color: var(--ds-text-2, #5b6472);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.rec-retry:hover {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
}

@media (max-width: 640px) {
  .rec-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }
}
</style>
