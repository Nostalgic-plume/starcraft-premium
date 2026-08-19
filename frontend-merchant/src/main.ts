// M4 商家中心批次1：基础设施 TS 化 + Naive UI 提供器 + 设计令牌接入（叠加式）
// CSS 引入顺序：tokens.css → global.css → element-plus/dist/index.css → style.css
// （style.css 含 style-base.css 的品牌墨蓝/珊瑚覆盖，最后加载保证旧页面品牌色生效，
//   与 M3 admin 批次1 Fix Round 1 的级联结论一致）
import '@shared/css/tokens.css'
import './assets/global.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as Icons from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './assets/style.css'

const app = createApp(App)
for (const [k, v] of Object.entries(Icons)) app.component(k, v)
app.use(createPinia()).use(router).use(ElementPlus)
app.mount('#app')