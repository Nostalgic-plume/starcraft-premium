<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 页头 -->
    <div class="page-head">
      <h2 class="pg-title">我的优惠券</h2>
      <router-link to="/coupons" class="center-link">
        去领券
        <SvgIcon name="chevron-right" :size="15" />
      </router-link>
    </div>

    <!-- 状态 tabs -->
    <div class="tabs-card">
      <n-tabs v-model:value="activeTab" type="line" class="coupon-tabs">
        <n-tab v-for="t in tabOptions" :key="t.value" :name="t.value">
          {{ t.label }}
        </n-tab>
      </n-tabs>
    </div>

    <!-- 券列表 -->
    <div v-if="currentList.length" class="coupon-list">
      <div v-for="c in currentList" :key="c.id" class="my-coupon" :class="c.status">
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
          <span class="status-pill">{{ c.statusText }}</span>
        </div>
      </div>
    </div>

    <EmptyState
      v-else
      :text="emptyText"
      description="去领券中心逛逛，好券等你来领"
      icon="ticket"
    >
      <button type="button" class="go-center" @click="router.push('/coupons')">去领券中心</button>
    </EmptyState>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NTab, NTabs } from 'naive-ui'
import BackBar from '@/components/BackBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'

/** 我的优惠券（占位结构） */
interface MyCoupon {
  id: string
  name: string
  amount: number
  condition: string
  scope: string
  expireAt: string
  /** 0=可用 1=已用 2=已过期 */
  status: 'usable' | 'used' | 'expired'
  statusText: string
}

const router = useRouter()

/** 状态 tabs */
const tabOptions = [
  { label: '可用', value: 'usable' },
  { label: '已用', value: 'used' },
  { label: '已过期', value: 'expired' }
]

const activeTab = ref<'usable' | 'used' | 'expired'>('usable')

/** 占位数据：接口未就绪，前端先用静态数据演示（TODO(占位) 接入 GET /api/coupon/my 后移除） */
const couponList = ref<MyCoupon[]>([
  // TODO(占位): 以下为本地占位数据
  { id: 'u1', name: '新人专享券', amount: 30, condition: '满 199 可用', scope: '全平台通用', expireAt: '2026-12-31', status: 'usable', statusText: '可使用' },
  { id: 'u2', name: '数码专营店券', amount: 50, condition: '满 499 可用', scope: '数码专营店', expireAt: '2026-10-31', status: 'usable', statusText: '可使用' },
  // 已使用券的有效期需晚于当前日期，避免演示数据自相矛盾（先用后过期）
  { id: 'u3', name: '夏日大促券', amount: 100, condition: '满 999 可用', scope: '全平台通用', expireAt: '2026-09-15', status: 'used', statusText: '已使用' },
  { id: 'u4', name: '美妆旗舰店券', amount: 20, condition: '满 99 可用', scope: '美妆旗舰店', expireAt: '2026-07-31', status: 'expired', statusText: '已过期' }
])

const currentList = computed(() => couponList.value.filter(c => c.status === activeTab.value))

const emptyText = computed(() => {
  if (activeTab.value === 'usable') return '暂无可用优惠券'
  if (activeTab.value === 'used') return '暂无已使用的优惠券'
  return '暂无已过期的优惠券'
})
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
.center-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--ds-primary, #2d7ff9);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.2s;
}
.center-link:hover { opacity: 0.75; }

/* tabs */
.tabs-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 6px 20px 0;
  margin-bottom: 16px;
}
.coupon-tabs {
  --n-tabs-bar-color: var(--ds-primary, #2d7ff9);
}
.coupon-tabs :deep(.n-tab) {
  font-size: 14px;
  color: var(--ds-text-2, #5a6474);
  transition: color 0.2s ease;
}
.coupon-tabs :deep(.n-tab:hover) { color: var(--ds-primary, #2d7ff9); }
.coupon-tabs :deep(.n-tab--active) {
  color: var(--ds-primary, #2d7ff9);
  font-weight: 700;
}

/* 券列表 */
.coupon-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.my-coupon {
  display: flex;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  overflow: hidden;
  transition: all 0.25s ease;
}
.my-coupon:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.my-coupon.used .coupon-left,
.my-coupon.expired .coupon-left {
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
}
.my-coupon.used .coupon-left .condition,
.my-coupon.expired .coupon-left .condition { color: var(--ds-text-3, #98a1af); }
.my-coupon.used .coupon-right,
.my-coupon.expired .coupon-right { opacity: 0.6; }

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
.amount { display: flex; align-items: baseline; gap: 1px; color: inherit; }
.amount .symbol { font-size: 16px; font-weight: 700; }
.amount .value { font-size: 34px; font-weight: 800; line-height: 1; letter-spacing: -1px; }
.condition { font-size: 12px; color: rgba(255, 255, 255, 0.85); }

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
  margin: 0 0 12px;
  flex: 1;
}
.status-pill {
  align-self: flex-end;
  padding: 4px 14px;
  border-radius: var(--ds-r-pill, 999px);
  font-size: 12px;
  font-weight: 600;
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
  color: var(--ds-primary, #2d7ff9);
}
.my-coupon.used .status-pill {
  background: var(--ds-bg-2, #eef0f4);
  color: var(--ds-text-3, #98a1af);
}
.my-coupon.expired .status-pill {
  background: rgba(224, 134, 0, 0.1);
  color: var(--ds-orange, #e08600);
}

/* 空态按钮 */
.go-center {
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
.go-center:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}
</style>
