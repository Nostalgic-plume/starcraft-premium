<template>
  <div class="page-container fade-in">
    <!-- 顶部返回 -->
    <BackBar label="返回" />

    <!-- 页头：标题 + 新增 -->
    <div class="page-head">
      <h2 class="pg-title">地址管理</h2>
      <button type="button" class="add-btn" @click="openAdd">
        <SvgIcon name="plus" :size="16" />
        新增地址
      </button>
    </div>

    <!-- 新增 / 编辑表单 -->
    <transition name="form-slide">
      <div v-if="formVisible" class="form-card">
        <div class="form-head">
          <SvgIcon name="edit" :size="18" />
          <h3 class="form-title">{{ editingId ? '编辑地址' : '新增地址' }}</h3>
        </div>
        <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" class="addr-form">
          <n-form-item label="收货人" path="receiverName">
            <n-input v-model:value="form.receiverName" placeholder="请输入收货人姓名" />
          </n-form-item>
          <n-form-item label="手机号" path="receiverPhone">
            <n-input v-model:value="form.receiverPhone" placeholder="请输入 11 位手机号" />
          </n-form-item>
          <n-form-item label="详细地址" path="detail">
            <n-input
              v-model:value="form.detail"
              type="textarea"
              :rows="3"
              placeholder="省市区 + 街道门牌号"
            />
          </n-form-item>
          <n-form-item label="默认地址">
            <n-checkbox v-model:checked="form.isDefault">设为默认收货地址</n-checkbox>
          </n-form-item>
          <div class="form-actions">
            <n-button quaternary @click="closeForm">取消</n-button>
            <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
          </div>
        </n-form>
      </div>
    </transition>

    <!-- 地址列表 -->
    <div v-if="addressList.length" class="address-list">
      <div v-for="addr in addressList" :key="addr.id" class="address-card">
        <div class="addr-main">
          <div class="addr-top">
            <strong class="addr-name">{{ addr.receiverName }}</strong>
            <span class="addr-phone">{{ addr.receiverPhone }}</span>
            <span v-if="addr.isDefault === 1" class="default-tag">
              <SvgIcon name="check-circle" :size="12" />
              默认
            </span>
          </div>
          <p class="addr-detail">{{ addr.detail }}</p>
        </div>
        <div class="addr-ops">
          <button type="button" class="op-btn" @click="openEdit(addr)">
            <SvgIcon name="edit" :size="15" />
            编辑
          </button>
          <button type="button" class="op-btn danger" @click="handleDelete(addr)">
            <SvgIcon name="trash" :size="15" />
            删除
          </button>
        </div>
      </div>
    </div>

    <EmptyState
      v-else
      text="暂无收货地址"
      description="点击右上角新增你的第一个地址"
      icon="location"
    >
      <button type="button" class="empty-add" @click="openAdd">新增地址</button>
    </EmptyState>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import {
  NButton, NCheckbox, NForm, NFormItem, NInput, useMessage,
  type FormInst, type FormRules
} from 'naive-ui'
import BackBar from '@/components/BackBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { useConfirm } from '@/utils/confirm'

/** 收货地址（占位结构，后端就绪后替换为 types.ts 的 AddressVO） */
interface AddressVO {
  id: string
  receiverName: string
  receiverPhone: string
  detail: string
  /** 0=否 1=是 */
  isDefault: number
}

/** 表单数据 */
interface AddressForm {
  receiverName: string
  receiverPhone: string
  detail: string
  isDefault: boolean
}

const message = useMessage()
const { confirm } = useConfirm()

/** 占位数据：接口未就绪，前端先用静态数据演示（TODO(占位) 接入 GET /api/address/list 后移除） */
const addressList = ref<AddressVO[]>([
  // TODO(占位): 以下为本地占位数据，后端地址模块就绪后替换为接口返回
  { id: '1', receiverName: '张三', receiverPhone: '13800138001', detail: '浙江省杭州市西湖区文一西路 969 号 8 幢 3 单元 501 室', isDefault: 1 },
  { id: '2', receiverName: '李四', receiverPhone: '13900139002', detail: '上海市浦东新区张江高科园区博云路 2 号 12F', isDefault: 0 },
  { id: '3', receiverName: '王五', receiverPhone: '13700137003', detail: '广东省深圳市南山区科技园南区深南大道 9988 号', isDefault: 0 }
])

/** 表单显隐与编辑目标 */
const formVisible = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)

const form = reactive<AddressForm>({
  receiverName: '',
  receiverPhone: '',
  detail: '',
  isDefault: false
})

/** 表单校验规则 */
const rules: FormRules = {
  receiverName: { required: true, message: '请输入收货人姓名', trigger: ['blur', 'input'] },
  receiverPhone: {
    required: true,
    trigger: ['blur', 'input'],
    validator: (_rule, value) => {
      if (!/^1[3-9]\d{9}$/.test(String(value))) {
        return new Error('请输入正确的 11 位手机号')
      }
      return true
    }
  },
  detail: { required: true, message: '请输入详细地址', trigger: ['blur', 'input'] }
}

/** 打开新增表单 */
function openAdd() {
  editingId.value = null
  Object.assign(form, { receiverName: '', receiverPhone: '', detail: '', isDefault: false })
  formVisible.value = true
}

/** 打开编辑表单 */
function openEdit(addr: AddressVO) {
  editingId.value = addr.id
  Object.assign(form, {
    receiverName: addr.receiverName,
    receiverPhone: addr.receiverPhone,
    detail: addr.detail,
    isDefault: addr.isDefault === 1
  })
  formVisible.value = true
}

function closeForm() {
  formVisible.value = false
  editingId.value = null
}

/** 保存（新增或编辑）。TODO(占位): 后端就绪后改调 addressApi.add / addressApi.update */
async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    // TODO(占位): 本地增删改，后端接口就绪后替换为真实调用并刷新列表
    if (editingId.value) {
      const target = addressList.value.find(a => a.id === editingId.value)
      if (target) {
        target.receiverName = form.receiverName
        target.receiverPhone = form.receiverPhone
        target.detail = form.detail
        target.isDefault = form.isDefault ? 1 : 0
      }
    } else {
      addressList.value.unshift({
        id: String(Date.now()),
        receiverName: form.receiverName,
        receiverPhone: form.receiverPhone,
        detail: form.detail,
        isDefault: form.isDefault ? 1 : 0
      })
    }
    // 设为默认时，其他地址取消默认
    if (form.isDefault) {
      addressList.value.forEach(a => {
        if (a.id !== editingId.value) a.isDefault = 0
      })
    }
    message.success(editingId.value ? '地址已更新' : '地址已添加')
    closeForm()
  } finally {
    saving.value = false
  }
}

/** 删除地址 */
async function handleDelete(addr: AddressVO) {
  const ok = await confirm({ content: `确定删除「${addr.receiverName}」的收货地址吗？` })
  if (!ok) return
  // TODO(占位): 本地删除，后端就绪后改调 addressApi.remove
  addressList.value = addressList.value.filter(a => a.id !== addr.id)
  message.success('地址已删除')
}
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
.add-btn {
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
.add-btn:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

/* 表单卡片 */
.form-card {
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 20px 24px 24px;
  margin-bottom: 16px;
  box-shadow: var(--ds-shadow, 0 2px 10px rgba(31, 38, 48, 0.05));
}
.form-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ds-primary, #2d7ff9);
  margin-bottom: 16px;
}
.form-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
  margin: 0;
}
.addr-form {
  max-width: 560px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}
.form-slide-enter-active,
.form-slide-leave-active { transition: all 0.25s ease; }
.form-slide-enter-from,
.form-slide-leave-to { opacity: 0; transform: translateY(-8px); }

/* 地址列表 */
.address-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.address-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: var(--ds-card, #fff);
  border: 1px solid var(--ds-line, #e7e9ee);
  border-radius: var(--ds-r-lg, 16px);
  padding: 18px 20px;
  transition: all 0.25s ease;
}
.address-card:hover {
  border-color: var(--ds-primary, #2d7ff9);
  box-shadow: var(--ds-shadow-hover, 0 12px 30px rgba(31, 38, 48, 0.1));
}
.addr-main { flex: 1; min-width: 0; }
.addr-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}
.addr-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ds-text, #1e2632);
}
.addr-phone {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  font-variant-numeric: tabular-nums;
}
.default-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 9px;
  border-radius: var(--ds-r-pill, 999px);
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
}
.addr-detail {
  font-size: 13px;
  color: var(--ds-text-2, #5a6474);
  line-height: 1.6;
  margin: 0;
}
.addr-ops {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.op-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 13px;
  border: 1px solid var(--ds-line-2, #d8dce4);
  border-radius: var(--ds-r, 12px);
  background: transparent;
  color: var(--ds-text-2, #5a6474);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}
.op-btn:hover {
  color: var(--ds-primary, #2d7ff9);
  border-color: var(--ds-primary, #2d7ff9);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.op-btn.danger:hover {
  color: var(--ds-red, #e0404e);
  border-color: var(--ds-red, #e0404e);
  background: rgba(224, 64, 78, 0.08);
}

/* 空态按钮 */
.empty-add {
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
.empty-add:hover {
  background: var(--ds-primary-strong, #1e63d6);
  transform: translateY(-1px);
}

@media (max-width: 560px) {
  .address-card { flex-direction: column; align-items: flex-start; }
  .addr-ops { width: 100%; justify-content: flex-end; }
}
</style>
