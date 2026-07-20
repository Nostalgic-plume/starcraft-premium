import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(__dirname, 'src'), '@shared': resolve(__dirname, '../shared') } },
  base: '/admin/',
  server: {
    port: 3001,
    proxy: {
      '/api/dashboard': { target: 'http://localhost:8081', changeOrigin: true },
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
