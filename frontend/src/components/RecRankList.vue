<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NSpin } from 'naive-ui'
import { fetchHomeRank } from '@/api/rec'
import type { RankItem } from '@/api/rec'
import ProductCard from '@/components/ProductCard.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import type { ProductVO } from '@/api/types'

/**
 * 首页推荐榜单区块
 *
 * <p>数据来自推荐子系统（M-08 榜单查询），经商城后端代理转发；推荐服务不可达时后端自动返回
 * 商城原生热销榜，两种情况的响应结构完全一致，因此本组件无需区分正常与降级。
 *
 * <p><b>快照语义</b>：首屏与「换一批」不传 snapshotId，由服务端按当前实时特征重算——
 * 这样「行为产生后 5 秒内影响推荐」才成立；仅「加载更多」回传首屏的 snapshotId，
 * 保证同一批次翻页时顺序稳定、不出现重复或漏项。
 *
 * <p><b>关于加购</b>：榜单只返回商品的展示字段（标识/名称/主图/价格），不含规格与库存，
 * 因此点击加购会提示「请到详情页选择」——这是有意为之，库存校验必须在详情页拿到真实规格后进行。
 */

const emit = defineEmits<{
  /** 加购（携带商品对象，由父组件处理登录与库存校验） */
  (e: 'add-cart', product: ProductVO): void
}>()

/** 每页条数：首页区块两行四列 */
const PAGE_SIZE = 8

const items = ref<RankItem[]>([])
/** 批次快照标识：仅「加载更多」回传；游客场景为 null */
const snapshotId = ref<string | null>(null)
const pageNum = ref(1)
const hasMore = ref(false)
const loading = ref(false)
/** 首屏加载失败：隐藏整个区块，避免页面上出现一个空标题 */
const failed = ref(false)

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

/**
 * 加载榜单
 *
 * @param reset true = 重新生成榜单（首屏 / 换一批）；false = 按快照翻页
 */
async function load(reset: boolean) {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const res = await fetchHomeRank(
      reset
        ? { pageNum: 1, pageSize: PAGE_SIZE }
        : { snapshotId: snapshotId.value ?? undefined, pageNum: pageNum.value + 1, pageSize: PAGE_SIZE },
    )
    const data = res.data
    const incoming = data?.items || []
    items.value = reset ? incoming : [...items.value, ...incoming]
    snapshotId.value = data?.snapshotId ?? null
    pageNum.value = data?.pageNum ?? 1
    hasMore.value = !!data?.hasMore
    failed.value = false
  } catch (e) {
    console.error('加载推荐榜单失败:', e)
    // 首屏失败整块隐藏；翻页失败保留已展示内容，用户可继续浏览
    if (reset) {
      failed.value = true
      items.value = []
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => load(true))
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

    <n-spin :show="loading">
      <div class="rec-grid">
        <ProductCard
          v-for="item in items"
          :key="String(item.itemId)"
          :product="toProduct(item)"
          @add-cart="(p: ProductVO) => emit('add-cart', p)"
        />
      </div>

      <div v-if="hasMore" class="rec-more">
        <button type="button" class="rec-more-btn" :disabled="loading" @click="load(false)">
          加载更多
        </button>
      </div>
    </n-spin>
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

/* 商品网格 */
.rec-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

/* 加载更多 */
.rec-more {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
.rec-more-btn {
  padding: 9px 32px;
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-card, #ffffff);
  color: var(--ds-text-2, #5b6472);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.rec-more-btn:hover:not(:disabled) {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.rec-more-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
