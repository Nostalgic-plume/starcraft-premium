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
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})