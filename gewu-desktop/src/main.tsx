import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './theme.css';
import './styles.css';

// Monaco npm 模式声明（共享组件双载体：桌面走 npm 包，file:// 无自托管资源）
(globalThis as { __GEWU_MONACO_MODE__?: 'npm' | 'paths' }).__GEWU_MONACO_MODE__ = 'npm';
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
(globalThis as { MonacoEnvironment?: { getWorker: () => Worker } }).MonacoEnvironment = {
  getWorker: () => new editorWorker(),
};

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
