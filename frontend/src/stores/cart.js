import { defineStore } from 'pinia'
import { ref } from 'vue'
import { cartApi } from '@/api/cart'

export const useCartStore = defineStore('cart', () => {
  const items = ref([])
  const loading = ref(false)

  const fetchList = async () => {
    loading.value = true
    try {
      const res = await cartApi.list()
      items.value = res.data || []
    } catch (e) { console.error('加载购物车失败:', e); items.value = [] }
    finally { loading.value = false }
  }

  const addItem = async (data) => {
    await cartApi.add(data)
    await fetchList()
  }

  const removeItem = async (skuId) => {
    await cartApi.remove(skuId)
    await fetchList()
  }

  const updateQuantity = async (skuId, quantity) => {
    await cartApi.updateQuantity(skuId, quantity)
    await fetchList()
  }

  const checkItem = async (skuId, checked) => {
    // 先乐观更新本地状态
    const prev = items.value
    items.value = items.value.map(i =>
      i.skuId === skuId ? { ...i, checked } : i
    )
    try {
      await cartApi.check(skuId, checked)
    } catch (e) {
      // 后端校验失败时回滚本地状态（错误提示由 axios 拦截器统一处理）
      items.value = prev
    }
  }

  const selectedItems = () => items.value.filter(i => i.checked && i.valid)

  const totalAmount = () =>
    selectedItems().reduce((sum, i) => sum + (i.subtotal || 0), 0)

  return { items, loading, fetchList, addItem, removeItem, updateQuantity, checkItem, selectedItems, totalAmount }
})
