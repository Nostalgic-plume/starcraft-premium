// 兼容入口（TS 迁移后保留）：三端业务代码仍以显式 `@shared/js/createRouter.js` 扩展名 import，
// Vite 不会把显式 .js 扩展名的导入回退解析到 .ts，故保留该转发层。实现见 createRouter.ts。
export * from './createRouter.ts'