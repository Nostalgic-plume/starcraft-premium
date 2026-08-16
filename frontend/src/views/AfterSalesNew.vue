<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" to="/after-sales" />

    <!-- 页头 -->
    <div class="page-head">
      <h2 class="pg-title">申请售后</h2>
    </div>

    <div class="form-card">
      <!-- 目标商品（占位，TODO(占位): 接入订单/商品后按传入商品渲染） -->
      <div class="goods-row">
        <div class="goods-thumb">
          <img v-if="targetProduct.image" :src="targetProduct.image" alt="" loading="lazy" />
          <SvgIcon v-else name="box" :size="22" class="thumb-icon" />
        </div>
        <div class="goods-info">
          <p class="goods-name">{{ targetProduct.name }}</p>
          <p class="goods-sku">规格：{{ targetProduct.sku }}</p>
        </div>
      </div>

      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" class="as-form">
        <n-form-item label="售后类型" path="type">
          <n-select
            v-model:value="form.type"
            :options="typeOptions"
            placeholder="请选择售后类型"
          />
        </n-form-item>

        <n-form-item label="申请原因" path="reason">
          <n-input
            v-model:value="form.reason"
            type="textarea"
            :rows="4"
            placeholder="请描述遇到的问题，方便商家尽快处理"
            :maxlength="200"
            show-count
          />
        </n-form-item>

        <n-form-item label="退款金额（元）" path="amount">
          <n-input-number
            v-model:value="form.amount"
            :min="0"
            :max="100000"
            :precision="2"
            placeholder="请输入退款金额"
            class="amount-input"
          />
          <span class="amount-hint">最多可退 ¥{{ formatPrice(maxRefund) }}</span>
        </n-form-item>

        <div class="form-actions">
          <n-button quaternary @click="router.back()">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="handleSubmit">提交申请</n-button>
        </div>
      </n-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton, NForm, NFormItem, NInput, NInputNumber, NSelect, useMessage,
  type FormInst, type FormRules
} from 'naive-ui'
import BackBar from '@/components/BackBar.vue'
import SvgIcon from '@/components/SvgIcon.vue'

interface AfterSaleFormModel {
  type: string
  reason: string
  amount: number | null
}

const router = useRouter()
const message = useMessage()

const formRef = ref<FormInst | null>(null)
const submitting = ref(false)

const typeOptions = [
  { label: '退货退款', value: '退货退款' },
  { label: '换货', value: '换货' },
  { label: '仅退款', value: '仅退款' }
]

const form = reactive<AfterSaleFormModel>({
  type: '',
  reason: '',
  amount: null
})

const rules: FormRules = {
  type: { required: true, message: '请选择售后类型', trigger: ['blur', 'change'] },
  reason: {
    required: true,
    trigger: ['blur', 'input'],
    validator: (_rule, value) => {
      if (!String(value).trim()) {
        return new Error('请填写申请原因')
      }
      return true
    }
  },
  amount: {
    type: 'number',
    required: true,
    trigger: ['blur', 'change'],
    validator: (_rule, value) => {
      const n = Number(value)
      if (!(n > 0)) {
        return new Error('请输入退款金额')
      }
      if (n > maxRefund) {
        return new Error(`退款金额不能超过 ¥${formatPrice(maxRefund)}`)
      }
      return true
    }
  }
}

/** 目标商品占位（TODO(占位): 从订单详情 /after-sales/new?orderId= 接入后替换） */
const targetProduct = {
  name: '星创无线蓝牙耳机 Pro',
  sku: '标配版 / 曜石黑',
  image: ''
}

/** 最大可退金额占位（TODO(占位): 接入订单实付金额后替换） */
const maxRefund = 299

function formatPrice(val: number | null | undefined): string {
  const n = Number(val || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/** 提交售后申请（TODO(占位): 后端就绪后改调 afterSaleApi.create） */
async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    // TODO(占位): 本地演示提交，接口就绪后替换为真实调用
    message.success('售后申请已提交，请留意处理进度')
    router.push('/after-sales')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.page-head {
  margin-bottom: 20px;
}
.pg-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ds-text, #1e2632);
  letter-spacing: -0.5px;
  margin: 0;
}

.form-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 20px 24px 24px;
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
}

/* 目标商品 */
.goods-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-bottom: 16px;
  margin-bottom: 16px;
  border-bottom: 1px dashed var(--ds-line, #e7e9ee);
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
.goods-info { min-width: 0; }
.goods-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 0 0 4px;
}
.goods-sku {
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
  margin: 0;
}

.as-form {
  max-width: 640px;
}
.amount-input {
  width: 220px;
}
.amount-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--ds-text-3, #98a1af);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}
</style>
