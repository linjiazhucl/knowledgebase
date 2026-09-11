import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    strictPort: true,
    host: '0.0.0.0',
    proxy: {
      '/api': 'http://localhost:18080'
    }
  },
  preview: {
    port: 5174,
    strictPort: true,
    host: '0.0.0.0'
  }
})
