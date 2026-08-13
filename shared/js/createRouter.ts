import type { Router, RouteRecordRaw, RouterHistory, createRouter as createRouterFn, createWebHistory as createHistoryFn } from 'vue-router'

export interface CreateAppRouterOptions {
  base?: string
  defaultTitle?: string
  routes: RouteRecordRaw[]
  tokenKey: string
  createRouter: typeof createRouterFn
  createWebHistory: typeof createHistoryFn
}

export function createAppRouter({ base = '/', defaultTitle = 'Shop', routes, tokenKey, createRouter, createWebHistory }: CreateAppRouterOptions): Router {
  const router = createRouter({ history: createWebHistory(base), routes })
  router.beforeEach((to, _from, next) => {
    document.title = (to.meta.title as string | undefined) || defaultTitle
    if (to.meta.requiresAuth) {
      const token = localStorage.getItem(tokenKey)
      if (!token) { next('/login'); return }
    }
    next()
  })
  return router
}