'use client';
// Monaco 文件编辑器（S9 F3）：自托管 public/monaco 资源加载（免 CDN）。
// 仅客户端渲染（FileEditorPanel 经 next/dynamic ssr:false 引入）。
import { useEffect, useRef } from 'react';
import Editor, { loader } from '@monaco-editor/react';

let configured = false;
function ensureLoader() {
  if (configured) return;
  configured = true;
  loader.config({ paths: { vs: '/monaco/vs' } });
}

export default function MonacoEditor({ value, language, path, onChange, readOnly = false, wordWrap = 'on' }: {
  value: string;
  language: string;
  path: string;
  onChange?: (value: string | undefined) => void;
  /** 只读预览（图2 代码阅读视图；编辑态为 false） */
  readOnly?: boolean;
  /** 自动换行：预览关（横向滚动贴近 IDE），编辑默认开 */
  wordWrap?: 'on' | 'off';
}) {
  ensureLoader();
  const ref = useRef(false);
  useEffect(() => { ref.current = true; }, []);

  return (
    <Editor
      height="100%"
      language={language}
      path={path}
      value={value}
      theme="vs-dark"
      onChange={onChange}
      options={{
        minimap: { enabled: false },
        fontSize: 12,
        lineNumbersMinChars: 3,
        scrollBeyondLastLine: false,
        automaticLayout: true,
        wordWrap,
        tabSize: 4,
        readOnly,
        domReadOnly: readOnly,
        // 新版 Monaco 默认开启作用域固定条（stickyScroll），透明底与代码行重叠发花，阅读视图关闭
        stickyScroll: { enabled: false },
      }}
    />
  );
}
