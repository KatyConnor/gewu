import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

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
  resolve: {
    alias: [
      // monaco 0.56 exports 对深路径解析缺陷（./esm/vs/* 前缀重复）：
      // regex 别名容忍 ?worker 查询，直指实际文件（用户实测构建失败）
      {
        find: /monaco-editor\/esm\/vs\/editor\/editor\.worker\.js(\?worker)?$/,
        replacement: path.resolve(__dirname, 'node_modules/monaco-editor/esm/vs/editor/editor.worker.js') + '?worker',
      },
    ],
  },

});
