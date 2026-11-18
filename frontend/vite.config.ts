import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发态：Vite 起 5173，`/api` 代理到本机 Spring Boot（127.0.0.1:8080）。
 * 生产态：产物由 Maven 拷入单体 jar 的 static/，同源访问，无 CORS（02 T-2）。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 单体 jar 内静态资源无需拆到极致，避免 Element Plus 触发无谓告警
    chunkSizeWarningLimit: 1600,
  },
})
