<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 页头 -->
    <div class="page-head">
      <h2 class="pg-title">我的评价</h2>
      <button type="button" class="review-btn" @click="router.push('/my-reviews/new')">
        <SvgIcon name="edit" :size="16" />
        去评价
      </button>
    </div>

    <!-- 评价列表 -->
    <div v-if="reviewList.length" class="review-list">
      <div v-for="r in reviewList" :key="r.id" class="review-card">
        <div class="review-head">
          <div class="goods-thumb">
            <img v-if="r.productImage" :src="r.productImage" alt="" loading="lazy" />
            <SvgIcon v-else name="box" :size="22" class="thumb-icon" />
          </div>
          <div class="goods-info">
            <p class="goods-name">{{ r.productName }}</p>
            <div class="rating-line">
              <n-rate :value="r.rating" readonly allow-half size="small" />
              <span class="rating-text">{{ r.rating.toFixed(1) }} 分</span>
            </div>
            <p class="review-time">
              <SvgIcon name="clock" :size="12" />
              {{ r.time }}
            </p>
          </div>
        </div>
        <p class="review-content">{{ r.content }}</p>
        <div v-if="r.images.length" class="review-images">
          <div v-for="(img, i) in r.images" :key="i" class="review-img">
            <img :src="img" alt="" loading="lazy" />
          </div>
        </div>
      </div>
    </div>

    <EmptyState
      v-else
      text="暂无评价"
      description="购买并确认收货后即可评价商品"
      icon="edit"
    >
      <button type="button" class="go-review" @click="router.push('/home')">去逛逛</button>
    </EmptyState>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NRate } from 'naive-ui'
import BackBar from '@/components/BackBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'

/** 评价记录（占位结构，后端就绪后替换为 types.ts 的 ReviewVO） */
interface ReviewVO {
  id: string
  productName: string
  productImage: string
  rating: number
  content: string
  time: string
  images: string[]
}

const router = useRouter()

/** 占位数据：接口未就绪，前端先用静态数据演示（TODO(占位) 接入 GET /api/review/my 后移除） */
const reviewList = ref<ReviewVO[]>([
  // TODO(占位): 以下为本地占位数据
  {
    id: 'r1',
    productName: '星创无线蓝牙耳机 Pro',
    productImage: '',
    rating: 5,
    content: '音质很惊喜，低音下潜足，降噪效果在地铁上非常明显，续航也能顶一整天。包装精致，发货很快，整体满意！',
    time: '2026-08-02 14:30',
    images: []
  },
  {
    id: 'r2',
    productName: '轻奢便携保温杯 500ml',
    productImage: '',
    rating: 4,
    content: '保温效果不错，早上倒的热水到下午还很烫，杯盖密封性也好，放包里没漏过水。就是杯身稍微有点重。',
    time: '2026-07-28 09:12',
    images: []
  }
])
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
.review-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 9px 18px;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.review-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

/* 评价列表 */
.review-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.review-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 18px 20px;
  transition: all 0.25s ease;
}
.review-card:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.review-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 12px;
}
.goods-thumb {
  width: 60px;
  height: 60px;
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
  flex-shrink: 0;
  background: var(--ds-bg-2, #eef0f4);
  display: flex;
  align-items: center;
  justify-content: center;
}
.goods-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.thumb-icon { color: var(--ds-primary, #2d7ff9); opacity: 0.5; }
.goods-info { flex: 1; min-width: 0; }
.goods-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 0 0 6px;
}
.rating-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rating-text {
  font-size: 12px;
  font-weight: 600;
  color: var(--ds-orange, #e08600);
}
.review-time {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 6px 0 0;
}
.review-content {
  font-size: 14px;
  color: var(--ds-text-2, #5a6474);
  line-height: 1.7;
  margin: 0;
}
.review-images {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.review-img {
  width: 80px;
  height: 80px;
  border-radius: var(--ds-r, 12px);
  overflow: hidden;
  background: var(--ds-bg-2, #eef0f4);
}
.review-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 空态按钮 */
.go-review {
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
.go-review:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}
</style>
