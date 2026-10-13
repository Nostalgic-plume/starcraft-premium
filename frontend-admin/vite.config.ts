import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@shared': fileURLToPath(new URL('../shared', import.meta.url))
    }
  },
  base: '/admin/',
  server: {
    port: 3001,
    proxy: {
      '/api/dashboard': { target: 'http://localhost:8081', changeOrigin: true },
      // 推荐子系统的管理接口（策略配置 / 固定坑位 / 过滤规则 / 画像版本 / 运行指标）只存在于
      // 推荐服务进程（`@Profile("rec")`，端口 8082），商城后端并没有这些 Bean —— 因此不能走
      // 下面那条 '/api' 规则，否则一律 404。
      // 两个进程共用同一 Redis 实例与同一套 Sa-Token 约定（见 application-rec.yml），
      // 管理后台的登录态在 8082 同样可被验证，无需额外透传凭证。
      // ⚠️ 必须声明在 '/api' 之前：vite 按声明顺序做前缀匹配，顺序颠倒会被 '/api' 全部截走。
      '/api/rec': { target: 'http://localhost:8082', changeOrigin: true },
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'naive-ui': ['naive-ui'],
          'echarts': ['echarts', 'vue-echarts']
        }
      }
    }
  }
})