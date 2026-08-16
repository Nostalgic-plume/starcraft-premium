<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 页头 -->
    <div class="page-head">
      <div class="head-copy">
        <h2 class="pg-title">领券中心</h2>
        <p class="pg-desc">海量优惠券等你来领，先领券再下单更划算</p>
      </div>
      <router-link to="/my-coupons" class="mine-btn">
        我的优惠券
        <SvgIcon name="chevron-right" :size="15" />
      </router-link>
    </div>

    <!-- 平台券 -->
    <section class="coupon-section">
      <h3 class="section-title">
        <SvgIcon name="tag" :size="17" />
        平台券
      </h3>
      <div class="coupon-grid">
        <div v-for="c in platformCoupons" :key="c.id" class="coupon-card">
          <div class="coupon-left">
            <div class="amount">
              <span class="symbol">¥</span>
              <span class="value">{{ c.amount }}</span>
            </div>
            <div class="condition">{{ c.condition }}</div>
          </div>
          <div class="coupon-right">
            <p class="coupon-name">{{ c.name }}</p>
            <p class="coupon-scope">{{ c.scope }}</p>
            <p class="coupon-expire">
              <SvgIcon name="clock" :size="12" />
              有效期至 {{ c.expireAt }}
            </p>
            <button
              type="button"
              class="receive-btn"
              :class="{ claimed: c.claimed }"
              @click="handleReceive(c)"
            >
              {{ c.claimed ? '已领取' : '立即领取' }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- 各店券 -->
    <section class="coupon-section">
      <h3 class="section-title">
        <SvgIcon name="box" :size="17" />
        各店券
      </h3>
      <div class="coupon-grid">
        <div v-for="c in shopCoupons" :key="c.id" class="coupon-card shop">
          <div class="coupon-left">
            <div class="amount">
              <span class="symbol">¥</span>
              <span class="value">{{ c.amount }}</span>
            </div>
            <div class="condition">{{ c.condition }}</div>
          </div>
          <div class="coupon-right">
            <p class="coupon-name">{{ c.name }}</p>
            <p class="coupon-scope">{{ c.scope }}</p>
            <p class="coupon-expire">
              <SvgIcon name="clock" :size="12" />
              有效期至 {{ c.expireAt }}
            </p>
            <button
              type="button"
              class="receive-btn"
              :class="{ claimed: c.claimed }"
              @click="handleReceive(c)"
            >
              {{ c.claimed ? '已领取' : '立即领取' }}
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import BackBar from '@/components/BackBar.vue'
import SvgIcon from '@/components/SvgIcon.vue'

/** 优惠券（占位结构，后端就绪后替换为 types.ts 的 CouponVO） */
interface CouponVO {
  id: string
  name: string
  /** 面额 */
  amount: number
  /** 使用门槛，如「满 299 可用」 */
  condition: string
  scope: string
  expireAt: string
  /** 本店店名，空为平台券 */
  shopName: string
  claimed: boolean
}

const message = useMessage()

/** 占位数据：接口未就绪，前端先用静态数据演示（TODO(占位) 接入 GET /api/coupon/center 后移除） */
const platformCoupons = ref<CouponVO[]>([
  // TODO(占位): 平台券本地占位
  { id: 'p1', name: '新人专享券', amount: 30, condition: '满 199 可用', scope: '全平台通用', expireAt: '2026-12-31', shopName: '', claimed: false },
  { id: 'p2', name: '夏日大促券', amount: 100, condition: '满 999 可用', scope: '全平台通用', expireAt: '2026-09-30', shopName: '', claimed: true }
])

const shopCoupons = ref<CouponVO[]>([
  // TODO(占位): 各店券本地占位
  { id: 's1', name: '数码专营店券', amount: 50, condition: '满 499 可用', scope: '数码专营店', expireAt: '2026-10-31', shopName: '数码专营店', claimed: false },
  { id: 's2', name: '美妆旗舰店券', amount: 20, condition: '满 99 可用', scope: '美妆旗舰店', expireAt: '2026-11-30', shopName: '美妆旗舰店', claimed: false }
])

/** 领取（本地标记，TODO(占位): 后端就绪后改调 couponApi.receive） */
function handleReceive(c: CouponVO) {
  if (c.claimed) {
    message.info('该券已领取，可在「我的优惠券」查看')
    return
  }
  // TODO(占位): 本地置为已领取，接口就绪后替换
  c.claimed = true
  message.success(`「${c.name}」领取成功`)
}
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
.pg-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0 0 6px;
}
.pg-desc {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}
.mine-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--ds-primary, #2d7ff9);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  flex-shrink: 0;
  transition: opacity 0.2s;
}
.mine-btn:hover { opacity: 0.75; }

.coupon-section { margin-bottom: 28px; }
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 0 0 14px;
}
.section-title svg { color: var(--ds-primary, #2d7ff9); }

.coupon-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.coupon-card {
  display: flex;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  overflow: hidden;
  transition: all 0.25s ease;
}
.coupon-card:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}

/* 左侧面额 */
.coupon-left {
  width: 128px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 20px 12px;
  background: var(--ds-primary-grad, linear-gradient(135deg, #5aa3ff, #2d7ff9));
  color: #fff;
}
.coupon-card.shop .coupon-left {
  background: linear-gradient(135deg, #8fb6f7, #5b8ee9);
}
.amount {
  display: flex;
  align-items: baseline;
  gap: 1px;
  color: #fff;
}
.amount .symbol { font-size: 16px; font-weight: 700; }
.amount .value { font-size: 34px; font-weight: 800; line-height: 1; letter-spacing: -1px; }
.condition {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
}

/* 右侧信息 */
.coupon-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 18px;
}
.coupon-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 0 0 4px;
}
.coupon-scope {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0 0 8px;
}
.coupon-expire {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0 0 14px;
  flex: 1;
}
.receive-btn {
  align-self: flex-end;
  border: none;
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  padding: 8px 22px;
  border-radius: var(--ds-r-pill, 999px);
  cursor: pointer;
  box-shadow: var(--ds-shadow-brand, 0 4px 14px rgba(45, 127, 249, 0.25));
  transition: all 0.2s;
}
.receive-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}
.receive-btn.claimed {
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
  box-shadow: none;
  cursor: default;
}

@media (max-width: 720px) {
  .coupon-grid { grid-template-columns: 1fr; }
}
</style>
