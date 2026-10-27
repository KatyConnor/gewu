'use client';
// Monaco Diff 编辑器（S9 F3 审查视图）：before 快照 vs 当前内容。
// 仅客户端渲染（React.lazy 客户端懒加载引入）。
import { DiffEditor, loader } from '@monaco-editor/react';

let configured = false;
async function ensureLoader() {
  if (configured) return;
  configured = true;
  const g = globalThis as { __GEWU_MONACO_VS_PATH__?: string; __GEWU_MONACO_MODE__?: 'npm' | 'paths' };
  if (g.__GEWU_MONACO_MODE__ === 'npm') {
    // webpackIgnore：web 构建不打包 monaco ESM——静态分析会把 worker 资源文件
    // （内含 import 语句）交给 Terser 按普通脚本压缩而构建失败（用户实报）。
    // 该分支仅桌面端（__GEWU_MONACO_MODE__='npm'）运行时触达；web 恒为 paths 模式，
    // 此原生动态 import 在 web 运行时永不执行。桌面侧由 vite 打包，行为不变。
    const monaco = await import(/* webpackIgnore: true */ 'monaco-editor');
    loader.config({ monaco });
    return;
  }
  loader.config({ paths: { vs: g.__GEWU_MONACO_VS_PATH__ || '/monaco/vs' } });
}

export default function MonacoDiff({ original, modified, language, theme, onReady }: {
  original: string;
  modified: string;
  language: string;
  path: string;
  /** diff 编辑器主题（Monaco 主题为全局单例——不传会用 vs-dark 污染宿主编辑器主题） */
  theme?: string;
  /** diff 编辑器就绪回调（宿主扩展：差异导航等） */
  onReady?: (diffEditor: { getModifiedEditor: () => { revealLineInCenter(lineNumber: number): void; setPosition(p: { lineNumber: number; column: number }): void; getPosition(): { lineNumber: number } | null } }) => void;
}) {
  void ensureLoader();
  return (
    <DiffEditor
      height="100%"
      language={language}
      original={original}
      modified={modified}
      theme={theme || 'vs-dark'}
      onMount={(editor) => onReady?.(editor)}
      options={{
        renderSideBySide: true,
        minimap: { enabled: false },
        fontSize: 12,
        lineNumbersMinChars: 3,
        scrollBeyondLastLine: false,
        automaticLayout: true,
        readOnly: true,
        stickyScroll: { enabled: false },
      }}
    />
  );
}
