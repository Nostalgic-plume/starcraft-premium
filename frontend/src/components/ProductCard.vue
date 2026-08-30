<script setup lang="ts">
import type { ProductVO } from '@/api/types'
import SvgIcon from './SvgIcon.vue'

const props = defineProps<{
  /** 商品数据（ProductVO 至少需 id/name/minPrice） */
  product: ProductVO
}>()

const emit = defineEmits<{
  /** 点击加购按钮（携带完整商品对象，父组件处理库存/登录校验） */
  (e: 'add-cart', product: ProductVO): void
  /** Phase 4：点击商品卡片跳详情（父组件做行为反馈埋点） */
  (e: 'detail-click', product: ProductVO): void
}>()

/** 价格展示：整数省略小数，否则保留两位 */
function formatPrice(val: number | undefined | null): string {
  const n = Number(val || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

function onAddCart() {
  emit('add-cart', props.product)
}

/** Phase 4：点击卡片跳详情（router-link 处理跳转，此处仅上报给父组件埋点） */
function onDetailClick() {
  emit('detail-click', props.product)
}
</script>

<template>
  <router-link :to="'/product/' + product.id" class="product-card" @click="onDetailClick">
    <!-- 图片区：有主图显示主图，否则 SVG 占位 -->
    <div class="card-media">
      <img
        v-if="product.mainImage"
        :src="product.mainImage"
        class="media-img"
        alt=""
        loading="lazy"
      />
      <div v-else class="media-placeholder">
        <SvgIcon name="box" :size="46" class="placeholder-icon" />
      </div>
      <span v-if="product.status === 1" class="card-badge">在售</span>
    </div>

    <!-- 信息区 -->
    <div class="card-body">
      <h3 class="card-name" :title="product.name">{{ product.name }}</h3>
      <div class="card-meta">
        <span class="card-brand">{{ product.brand || '通用' }}</span>
        <span class="card-sold">已售 {{ product.soldCount || 0 }}</span>
      </div>
      <div class="card-footer">
        <div class="card-price">
          <span class="price-symbol">¥</span>
          <span class="price-value">{{ formatPrice(product.minPrice) }}</span>
        </div>
        <button
          class="add-btn"
          type="button"
          :aria-label="'加入购物车：' + product.name"
          @click.prevent="onAddCart"
        >
          <SvgIcon name="plus" :size="16" />
        </button>
      </div>
    </div>
  </router-link>
</template>

<style scoped>
.product-card {
  display: flex;
  flex-direction: column;
  background: var(--ds-card, #ffffff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  overflow: hidden;
  text-decoration: none;
  transition: all 0.25s ease;
}
.product-card:hover {
  transform: translateY(-4px);
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}

/* 图片区 */
.card-media {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: linear-gradient(135deg, var(--ds-bg-2, #eef0f4), var(--ds-bg, #f6f7fa));
}
.media-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.product-card:hover .media-img {
  transform: scale(1.06);
}
.media-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--ds-bg-2, #eef0f4), var(--ds-bg, #f6f7fa));
}
.placeholder-icon {
  color: var(--ds-primary, #2d7ff9);
  opacity: 0.55;
}
.card-badge {
  position: absolute;
  top: 10px;
  left: 10px;
  padding: 2px 10px;
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

/* 信息区 */
.card-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  padding: 12px 14px 14px;
}
.card-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text, #1e2632);
  line-height: 1.45;
  margin: 0 0 6px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin-bottom: 10px;
}
.card-brand {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 55%;
}

/* 底部：价格 + 加购 */
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
}
.card-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  color: var(--ds-price, #2d7ff9);
}
.price-symbol {
  font-size: 12px;
  font-weight: 700;
}
.price-value {
  font-size: 20px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.5px;
}
.add-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
}
.add-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: scale(1.08);
}
</style>
