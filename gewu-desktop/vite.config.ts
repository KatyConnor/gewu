import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 渲染层构建（base=./ 支持 file:// 加载；outDir=dist 由 Electron 主进程生产态加载）
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    strictPort: true,
  },
});
