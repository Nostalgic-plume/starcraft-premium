<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" to="/my-reviews" />

    <!-- 页头 -->
    <div class="page-head">
      <h2 class="pg-title">发表评价</h2>
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

      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" class="review-form">
        <n-form-item label="商品评分" path="rating">
          <div class="rating-input">
            <n-rate v-model:value="form.rating" allow-half :count="5" />
            <span class="rating-hint">{{ form.rating > 0 ? `${form.rating.toFixed(1)} 分` : '点击星星评分' }}</span>
          </div>
        </n-form-item>

        <n-form-item label="评价内容" path="content">
          <n-input
            v-model:value="form.content"
            type="textarea"
            :rows="5"
            placeholder="分享你的使用体验，帮助其他小伙伴做决定～"
            :maxlength="500"
            show-count
          />
        </n-form-item>

        <n-form-item label="晒图（可选）">
          <div class="upload-placeholder">
            <SvgIcon name="image" :size="28" />
            <p class="upload-text">晒图功能开发中，敬请期待</p>
          </div>
        </n-form-item>

        <div class="form-actions">
          <n-button quaternary @click="router.back()">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="handleSubmit">提交评价</n-button>
        </div>
      </n-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton, NForm, NFormItem, NInput, NRate, useMessage,
  type FormInst, type FormRules
} from 'naive-ui'
import BackBar from '@/components/BackBar.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { reviewApi } from '@/api/review'

interface ReviewFormModel {
  rating: number
  content: string
}

const route = useRoute()
const router = useRouter()
const message = useMessage()

/** 评价对象由订单详情页通过 query 带入（orderItemId 是唯一的业务键） */
const orderItemId = String(route.query.orderItemId || '')

const formRef = ref<FormInst | null>(null)
const submitting = ref(false)

const form = reactive<ReviewFormModel>({
  rating: 0,
  content: ''
})

const rules: FormRules = {
  rating: {
    type: 'number',
    required: true,
    trigger: ['blur', 'change'],
    validator: (_rule, value) => {
      if (Number(value) <= 0) {
        return new Error('请为商品打分')
      }
      return true
    }
  },
  content: {
    required: true,
    trigger: ['blur', 'input'],
    validator: (_rule, value) => {
      if (!String(value).trim()) {
        return new Error('请输入评价内容')
      }
      return true
    }
  }
}

/** 目标商品展示信息（订单详情页通过 query 带入，仅用于展示，不参与提交） */
const targetProduct = {
  name: String(route.query.productName || '商品'),
  sku: String(route.query.skuName || ''),
  image: String(route.query.productImage || '')
}

/** 提交评价：只传 orderItemId，商品与归属由服务端校验 */
async function handleSubmit() {
  if (!orderItemId) {
    message.warning('缺少订单项信息，请从订单详情页发起评价')
    return
  }
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    await reviewApi.create({
      orderItemId,
      rating: form.rating,
      content: form.content
    })
    message.success('评价提交成功，感谢你的分享')
    router.push('/my-reviews')
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

.review-form {
  max-width: 640px;
}
.rating-input {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}
.rating-hint {
  font-size: 13px;
  color: var(--ds-text-3, #98a1af);
}

/* 晒图占位 */
.upload-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 120px;
  height: 120px;
  border: 1.5px dashed var(--ds-line-2, #d8dce4);
  border-radius: var(--ds-r, 12px);
  color: var(--ds-text-3, #98a1af);
  cursor: default;
  transition: all 0.2s;
}
.upload-placeholder:hover {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
}
.upload-text {
  font-size: 12px;
  margin: 0;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}
</style>
