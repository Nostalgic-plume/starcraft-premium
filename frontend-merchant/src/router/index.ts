import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { createAppRouter } from '@shared/js/createRouter'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: () => import('@/views/Login.vue'), meta: { title: '商家登录' } },
  {
    path: '/dashboard',
    component: () => import('@/views/Layout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', component: () => import('@/views/Dashboard.vue'), meta: { title: '控制台' } },
      { path: 'products', component: () => import('@/views/Products.vue'), meta: { title: '商品管理' } },
      { path: 'orders', component: () => import('@/views/Orders.vue'), meta: { title: '订单管理' } },
      { path: 'coupons', component: () => import('@/views/Coupons.vue'), meta: { title: '本店优惠券' } },
      { path: 'reviews', component: () => import('@/views/Reviews.vue'), meta: { title: '评价管理' } },
      { path: 'after-sales', component: () => import('@/views/AfterSales.vue'), meta: { title: '售后处理' } },
      { path: 'stock-alert', component: () => import('@/views/StockAlert.vue'), meta: { title: '库存预警' } }
    ]
  }
]

export default createAppRouter({
  createRouter,
  createWebHistory,
  base: '/merchant/',
  defaultTitle: '商家中心',
  routes,
  tokenKey: 'merchant-token'
})