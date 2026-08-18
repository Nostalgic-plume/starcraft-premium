import '@shared/css/tokens.css'
import './assets/global.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

// M3 批次4：整体移除 Element Plus（全局注册 + icons + 样式均删除）。
// 仅保留设计令牌 tokens.css + 后台结构样式 global.css（reset/body/page-container/fade-in）。
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
