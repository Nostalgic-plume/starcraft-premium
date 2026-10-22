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
      { path: '', name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '数据看板' } },
      { path: 'products', name: 'Products', component: () => import('@/views/Products.vue'), meta: { title: '商品管理' } },
      { path: 'orders', name: 'Orders', component: () => import('@/views/Orders.vue'), meta: { title: '订单管理' } },
      { path: 'coupons', name: 'Coupons', component: () => import('@/views/Coupons.vue'), meta: { title: '优惠券管理' } },
      { path: 'reviews', name: 'Reviews', component: () => import('@/views/Reviews.vue'), meta: { title: '评价管理' } },
      { path: 'after-sales', name: 'AfterSales', component: () => import('@/views/AfterSales.vue'), meta: { title: '售后管理' } },
      { path: 'users', name: 'Users', component: () => import('@/views/Users.vue'), meta: { title: '用户管理' } },
      { path: 'categories', name: 'Categories', component: () => import('@/views/Categories.vue'), meta: { title: '分类管理' } },
      { path: 'brands', name: 'Brands', component: () => import('@/views/Brands.vue'), meta: { title: '品牌管理' } },
      { path: 'roles', name: 'Roles', component: () => import('@/views/Roles.vue'), meta: { title: '角色权限' } },
      { path: 'rag-index', name: 'RagIndex', component: () => import('@/views/RagIndex.vue'), meta: { title: '索引管理' } },
      // ====== 推荐子系统（接口由推荐服务进程 8082 提供，vite 以 '/api/rec' 代理直达）======
      { path: 'rec-config', name: 'RecConfig', component: () => import('@/views/RecConfig.vue'), meta: { title: '推荐策略配置' } },
      { path: 'rec-slots', name: 'RecSlots', component: () => import('@/views/RecSlots.vue'), meta: { title: '固定坑位' } },
      { path: 'rec-rules', name: 'RecRules', component: () => import('@/views/RecRules.vue'), meta: { title: '过滤规则' } },
      { path: 'rec-profiles', name: 'RecProfiles', component: () => import('@/views/RecProfiles.vue'), meta: { title: '画像版本' } },
      { path: 'rec-metrics', name: 'RecMetrics', component: () => import('@/views/RecMetrics.vue'), meta: { title: '运行看板' } },
      { path: 'rec-item-index', name: 'RecItemIndex', component: () => import('@/views/RecItemIndex.vue'), meta: { title: '商品索引' } },
      { path: 'settings', name: 'Settings', component: () => import('@/views/Settings.vue'), meta: { title: '系统设置' } },
      // 旧「数据看板」入口兼容：并入控制台 Dashboard，避免旧链接 404
      { path: 'report', redirect: '/dashboard' }
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