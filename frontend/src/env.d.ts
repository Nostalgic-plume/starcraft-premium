/// <reference types="vite/client" />

// Vue 单文件组件（SFC）模块声明（标准 Vue 3 + TS 配置，长期保留）
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// ===== 临时 JS 模块桥接 =====
// 【Task 5 完成后删除】router / stores / api 尚在 JS 迁移过渡期，
// allowJs:false 下 TS 无法直接导入 .js，此处以 ambient 声明桥接类型为 any；
// 待 M2 阶段 B Task 5（router/stores/api 全部 TS 化）完成后删除本段。
declare module '@/router'
declare module '@/stores/*'
declare module '@/api/*'
