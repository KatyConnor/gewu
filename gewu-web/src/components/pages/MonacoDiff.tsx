'use client';
// Monaco Diff 编辑器（S9 F3 审查视图）：before 快照 vs 当前内容。
// 仅客户端渲染（FileEditorPanel 经 next/dynamic ssr:false 引入）。
import { DiffEditor, loader } from '@monaco-editor/react';

let configured = false;
function ensureLoader() {
  if (configured) return;
  configured = true;
  loader.config({ paths: { vs: '/monaco/vs' } });
}

export default function MonacoDiff({ original, modified, language }: {
  original: string;
  modified: string;
  language: string;
  path: string;
}) {
  ensureLoader();
  return (
    <DiffEditor
      height="100%"
      language={language}
      original={original}
      modified={modified}
      theme="vs-dark"
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
