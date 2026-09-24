'use client';
import { useMemo } from 'react';
import { lineDiff } from '@/lib/lineDiff';

/**
 * 过程时间线·编辑条目的差异块（zcode 风格）：行号槽 + 红/绿差异行。
 * 删行红底显旧行号、增行绿底显新行号、未变行灰显；startLine 来自引擎
 * edit_file 结果文案（真实文件行号），缺省时按片段相对编号。
 */
export default function ProcessEditDiff({ oldText, newText, startLine }: {
  oldText: string;
  newText: string;
  /** 差异块在文件中的起始行号；缺省按片段相对编号 */
  startLine?: number;
}) {
  const diff = useMemo(() => lineDiff(oldText, newText, startLine ?? 1), [oldText, newText, startLine]);
  return (
    <div className="mb-1.5 rounded-md border border-ink-700/60 bg-ink-900/40 overflow-hidden">
      {/* 长行横向滚动：min-w-max 内层保证行背景随最宽行连续铺满 */}
      <div className="max-h-80 overflow-auto scrollbar-thin">
        <div className="min-w-max py-1">
          {diff.rows.map((r, i) => (
            <div key={i} className={`flex ${r.type === 'del' ? 'bg-red-500/10' : r.type === 'add' ? 'bg-green-500/10' : ''}`}>
              <span className="w-9 flex-shrink-0 pr-2 text-right text-[10px] font-mono leading-4 text-ink-600 select-none">
                {r.type === 'del' ? r.oldNo : r.newNo}
              </span>
              <span className={`pr-3 text-[11px] font-mono leading-4 whitespace-pre ${
                r.type === 'del' ? 'text-red-300/90' : r.type === 'add' ? 'text-green-300/90' : 'text-ink-500'
              }`}>{r.text || ' '}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
