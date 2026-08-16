import { createRouter, createWebHistory } from 'vue-router'
import { createAppRouter } from '@shared/js/createRouter'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/login' },
  {
    path: '/home',
    name: 'Home',
    component: () => import('@/views/Home.vue'),
    meta: { title: '首页' }
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册' }
  },
  {
    path: '/product/:id',
    name: 'ProductDetail',
    component: () => import('@/views/ProductDetail.vue'),
    meta: { title: '商品详情' }
  },
  {
    path: '/cart',
    name: 'Cart',
    component: () => import('@/views/Cart.vue'),
    meta: { title: '购物车', requiresAuth: true }
  },
  {
    path: '/order/create',
    name: 'CreateOrder',
    component: () => import('@/views/CreateOrder.vue'),
    meta: { title: '确认订单', requiresAuth: true }
  },
  {
    path: '/orders',
    name: 'MyOrders',
    component: () => import('@/views/MyOrders.vue'),
    meta: { title: '我的订单', requiresAuth: true }
  },
  {
    path: '/order/:id',
    name: 'OrderDetail',
    component: () => import('@/views/OrderDetail.vue'),
    meta: { title: '订单详情', requiresAuth: true }
  },
  {
    path: '/address',
    name: 'Address',
    component: () => import('@/views/Address.vue'),
    meta: { title: '地址管理', requiresAuth: true }
  },
  {
    path: '/coupons',
    name: 'Coupons',
    component: () => import('@/views/Coupons.vue'),
    meta: { title: '领券中心', requiresAuth: true }
  },
  {
    path: '/my-coupons',
    name: 'MyCoupons',
    component: () => import('@/views/MyCoupons.vue'),
    meta: { title: '我的优惠券', requiresAuth: true }
  },
  {
    path: '/my-reviews',
    name: 'MyReviews',
    component: () => import('@/views/MyReviews.vue'),
    meta: { title: '我的评价', requiresAuth: true }
  },
  {
    path: '/my-reviews/new',
    name: 'ReviewForm',
    component: () => import('@/views/ReviewForm.vue'),
    meta: { title: '发表评价', requiresAuth: true }
  },
  {
    path: '/favorites',
    name: 'Favorites',
    component: () => import('@/views/Favorites.vue'),
    meta: { title: '我的收藏', requiresAuth: true }
  },
  {
    path: '/after-sales',
    name: 'AfterSales',
    component: () => import('@/views/AfterSales.vue'),
    meta: { title: '我的售后', requiresAuth: true }
  },
  {
    path: '/after-sales/new',
    name: 'AfterSalesNew',
    component: () => import('@/views/AfterSalesNew.vue'),
    meta: { title: '申请售后', requiresAuth: true }
  }
]

export default createAppRouter({
  createRouter,
  createWebHistory,
  base: '/',
  defaultTitle: 'Shop 电商系统',
  routes,
  tokenKey: 'token'
})
