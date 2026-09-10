import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * RagPilot Admin 前端构建配置。
 * 开发期默认端口 5173；后续 ADM-0.3 再配 proxy 到 bootstrap:8081。
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
    // 5173 被占用时自动递增；后端 CORS 已放行 127.0.0.1:* / localhost:*
    strictPort: false,
    host: '127.0.0.1',
    proxy: {
      // ADM-0.3：开发期把 API 转到 bootstrap（会转发浏览器 Origin，勿写死 CORS 端口）
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
  // 生产构建后可由 bootstrap 挂到 /admin/
  base: '/admin/',
})
