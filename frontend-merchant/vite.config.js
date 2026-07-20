import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(__dirname, 'src'), '@shared': resolve(__dirname, '../shared') } },
  base: '/merchant/',
  server: { port: 3002, proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } }
})
