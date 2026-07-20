/**
 * Vue Router 工厂
 * 统一三端路由创建和导航守卫
 *
 * @param {object} opts
 * @param {string} [opts.base='/'] - 路由基础路径
 * @param {string} [opts.defaultTitle='Shop'] - 默认页面标题
 * @param {import('vue-router').RouteRecordRaw[]} opts.routes - 路由配置
 * @param {string} opts.tokenKey - localStorage token 键名（用于 requiresAuth 守卫）
 * @param {Function} opts.createRouter - vue-router 的 createRouter 函数
 * @param {Function} opts.createWebHistory - vue-router 的 createWebHistory 函数
 * @returns {import('vue-router').Router}
 */
export function createAppRouter({ base = '/', defaultTitle = 'Shop', routes, tokenKey, createRouter, createWebHistory }) {
  const router = createRouter({
    history: createWebHistory(base),
    routes
  })

  router.beforeEach((to, from, next) => {
    document.title = to.meta.title || defaultTitle
    if (to.meta.requiresAuth) {
      const token = localStorage.getItem(tokenKey)
      if (!token) {
        next('/login')
        return
      }
    }
    next()
  })

  return router
}
