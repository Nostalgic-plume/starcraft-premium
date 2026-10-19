<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
 * 首页推荐位固定展示 4 个商品，一行排开。
 * 不做无限滚动：只加载第一页，换一批时重新生成。
 */
const PAGE_SIZE = 4

const items = ref<RankItem[]>([])
const loading = ref(false)
/** 加载失败：隐藏整个区块，避免页面上出现一个空标题 */
const failed = ref(false)

/**
 * 加载推荐位（首屏 / 换一批都重新生成）
 *
 * <p>不传 snapshotId：由服务端按当前实时特征重算，保证「行为产生后 5 秒内影响推荐」。</p>
 */
async function load() {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const res = await fetchHomeRank({ pageNum: 1, pageSize: PAGE_SIZE })
    items.value = (res.data?.items || []).slice(0, PAGE_SIZE)
    failed.value = false
  } catch (e) {
    console.error('加载推荐位失败:', e)
    failed.value = true
    items.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

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
      <button type="button" class="rec-refresh" :disabled="loading" @click="load">
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

/* 固定 4 个商品，一行排开 */
.rec-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
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

@media (max-width: 900px) {
  .rec-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
}
</style>
