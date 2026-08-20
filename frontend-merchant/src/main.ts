// M4 商家中心批次1：基础设施 TS 化 + Naive UI 提供器 + 设计令牌接入
// M4 final fix：移除 Element Plus —— 不再注册 element-plus / 全量图标 / style.css 旧令牌覆盖，
// 中性令牌 tokens.css + 结构 global.css 全面生效。
import '@shared/css/tokens.css'
import './assets/global.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia()).use(router)
app.mount('#app')
