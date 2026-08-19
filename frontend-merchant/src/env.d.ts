/// <reference types="vite/client" />

// Vue 单文件组件（SFC）模块声明（标准 Vue 3 + TS 配置，长期保留）
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}