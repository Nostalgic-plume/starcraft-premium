import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { createAppRouter } from '@shared/js/createRouter'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/login' },
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue'), meta: { title: '管理员登录' } },
  {
    path: '/dashboard',
    component: () => import('@/views/Layout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '控制台' } },
      { path: 'products', name: 'Products', component: () => import('@/views/Products.vue'), meta: { title: '商品管理' } },
      { path: 'orders', name: 'Orders', component: () => import('@/views/Orders.vue'), meta: { title: '订单管理' } },
      { path: 'users', name: 'Users', component: () => import('@/views/Users.vue'), meta: { title: '用户管理' } },
      { path: 'report', name: 'DataDashboard', component: () => import('@/views/DataDashboard.vue'), meta: { title: '数据看板' } },
    ]
  }
]

export default createAppRouter({
  createRouter,
  createWebHistory,
  base: '/admin/',
  defaultTitle: 'Shop 管理后台',
  routes,
  tokenKey: 'admin-token'
})