<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 页头 -->
    <div class="page-head">
      <h2 class="pg-title">我的收藏</h2>
      <span class="count">{{ favoriteList.length }} 件商品</span>
    </div>

    <!-- 收藏网格 -->
    <div v-if="favoriteList.length" class="fav-grid">
      <div v-for="p in favoriteList" :key="p.id" class="fav-item">
        <ProductCard :product="p" @add-cart="onAddCart" />
        <button
          type="button"
          class="unfav-btn"
          :aria-label="'取消收藏：' + p.name"
          @click.stop.prevent="onUnfavorite(p)"
        >
          <SvgIcon name="heart-filled" :size="20" />
        </button>
      </div>
    </div>

    <EmptyState
      v-else
      text="暂无收藏商品"
      description="在商品详情页点击红心即可收藏喜欢的商品"
      icon="heart"
    >
      <button type="button" class="go-shop" @click="router.push('/home')">去逛逛</button>
    </EmptyState>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import ProductCard from '@/components/ProductCard.vue'
import BackBar from '@/components/BackBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { favoriteApi } from '@/api/favorite'
import type { ProductVO } from '@/api/types'

const router = useRouter()
const message = useMessage()

/** 收藏列表：来自 GET /api/favorite/list（后端已跳过下架 / 删除商品） */
const favoriteList = ref<ProductVO[]>([])

/** 加载收藏列表 */
async function loadFavorites() {
  try {
    const res = await favoriteApi.list()
    favoriteList.value = res.data || []
  } catch {
    favoriteList.value = []
  }
}

/** 取消收藏 */
async function onUnfavorite(p: ProductVO) {
  await favoriteApi.remove(p.id)
  favoriteList.value = favoriteList.value.filter(item => item.id !== p.id)
  message.success('已取消收藏')
}

/** 加购：收藏页商品只带首个 SKU 时直接提示去详情页选择规格 */
function onAddCart(p: ProductVO) {
  const sku = p.skuList?.[0]
  if (!sku) {
    message.warning('该商品暂无规格，请到详情页选择')
    return
  }
  message.info('请到商品详情页选择规格后加入购物车')
}

onMounted(loadFavorites)
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.pg-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0;
}
.count {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}

/* 收藏网格 */
.fav-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}
.fav-item {
  position: relative;
}
/* 取消收藏红心 */
.unfav-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 5;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.92);
  color: var(--ds-red, #e0404e);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.12));
  transition: all 0.2s ease;
}
.unfav-btn:hover {
  transform: scale(1.1);
  background: #fff;
  box-shadow: 0 4px 16px rgba(224, 64, 78, 0.25);
}

/* 空态按钮 */
.go-shop {
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 9px 24px;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.go-shop:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

@media (max-width: 1100px) {
  .fav-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 900px) {
  .fav-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 500px) {
  .fav-grid { grid-template-columns: 1fr; }
}
</style>
