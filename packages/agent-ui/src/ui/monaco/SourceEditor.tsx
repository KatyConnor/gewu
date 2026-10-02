import { useMemo, useState } from 'react';
import { highlightLine, prismLangOf } from '../prismHighlight';

/**
 * 轻量源码查看/编辑组件（Phase 2 降级路线）：
 * 与 MonacoFileEditor 同签名——只读模式 prism 高亮（prismHighlight），
 * 编辑模式等宽 textarea。零 worker/零 AMD loader，file:// 下无阻塞。
 * Monaco 富编辑器（补全/诊断）为深化项，见 docs/design/42 §7.4 备注。
 */
export default function SourceEditor({ value, language, path, onChange, readOnly = false, wordWrap = 'on' }: {
  value: string;
  language: string;
  path: string;
  onChange?: (value: string | undefined) => void;
  readOnly?: boolean;
  wordWrap?: 'on' | 'off';
}) {
  const [editText, setEditText] = useState<string | null>(null);
  const shown = editText !== null ? editText : value;
  const lines = useMemo(() => shown.split('\n'), [shown]);
  const prismLang = prismLangOf(path) ?? 'plaintext';
  const wrap = wordWrap !== 'off';

  const lineEls = lines.map((line, i) => {
    const safe = highlightLine(line, prismLang) ?? '';
    return (
      <div key={i} className="flex">
        <span className="select-none text-right pr-3 text-ink-600 flex-shrink-0" style={{ width: 44 }}>
          {i + 1}
        </span>
        <span
          className="flex-1"
          style={{ whiteSpace: wrap ? 'pre-wrap' : 'pre' }}
          dangerouslySetInnerHTML={{ __html: safe || '&nbsp;' }}
        />
      </div>
    );
  });

  return (
    <div className="h-full w-full flex flex-col bg-[#0a0f0d] overflow-hidden">
      <div className="px-3 py-1 text-[11px] text-ink-500 border-b flex-shrink-0"
        style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
        {path} · {language}
      </div>
      {readOnly ? (
        <div className="flex-1 overflow-auto font-mono text-[12px] leading-5 px-2 py-2">
          {lineEls}
        </div>
      ) : (
        <textarea
          value={shown}
          onChange={(e) => {
            setEditText(e.target.value);
            onChange?.(e.target.value);
          }}
          spellCheck={false}
          className="flex-1 w-full resize-none bg-[#0a0f0d] text-[#c9d1d9] font-mono text-[12px] leading-5 p-3 outline-none"
          style={{ whiteSpace: 'pre', overflowWrap: 'normal' }}
        />
      )}
    </div>
  );
}
