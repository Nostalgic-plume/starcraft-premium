import '@shared/css/tokens.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from '@/router'
import './assets/style.css'

const app = createApp(App)

// 注册所有 Element Plus 图标
// 【C9 前保留】B 阶段页面尚未全部重写为 Naive，现有页面仍在用 el-* 组件，
// 故 Element Plus 的注册与样式暂不移除；待 C9 页面重写完成后统一清理。
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: undefined })
app.mount('#app')
