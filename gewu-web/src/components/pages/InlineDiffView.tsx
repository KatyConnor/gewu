'use client';
import { useMemo, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { lineDiff, type DiffRow } from '@/lib/lineDiff';
import { buildDiffHunks, type DiffBlock } from '@/lib/diffHunks';
import { highlightLine, prismLangOf } from '@/lib/prismHighlight';
import 'prismjs/themes/prism-tomorrow.css';

/** 全量行渲染上限：防御异常大文件拖垮 DOM（超出仅提示，仍渲染） */
const MAX_RENDER_ROWS = 8000;

const TOKEN_TEXT_CLASS =
  'pr-3 text-[11px] font-mono leading-4 whitespace-pre';

/**
 * 审查视图·内联 diff（zcode 风格，对齐 GitHub unified diff）：
 * 修改区前后各 3 行上下文、其余未变行折叠可展开；删行红底显旧行号、
 * 增行绿底显新行号、上下文行无底色；行内 Prism 语法着色。
 */
export default function InlineDiffView({ before, after, changeType, path }: {
  before: string | null;
  after: string | null;
  changeType: string;
  path: string;
}) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const lang = prismLangOf(path);

  const diff = useMemo(() => lineDiff(before ?? '', after ?? ''), [before, after]);
  const blocks = useMemo(() => buildDiffHunks(diff.rows), [diff]);
  const oversized = diff.rows.length > MAX_RENDER_ROWS;

  const expandBlock = (index: number) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.add(index);
      return next;
    });
  };

  const renderRow = (r: DiffRow, key: string) => {
    const rowBg = r.type === 'del' ? 'bg-red-500/10' : r.type === 'add' ? 'bg-green-500/10' : '';
    const textColor = r.type === 'del'
      ? 'text-red-300/90'
      : r.type === 'add' ? 'text-green-300/90' : 'text-ink-500';
    const lineNo = r.type === 'del' ? r.oldNo : r.newNo;
    const html = lang ? highlightLine(r.text, lang) : null;
    return (
      <div key={key} className={`flex ${rowBg}`}>
        <span className="w-12 flex-shrink-0 pr-2 text-right text-[10px] font-mono leading-4 text-ink-600 select-none">
          {lineNo}
        </span>
        {html != null
          ? <span className={TOKEN_TEXT_CLASS} dangerouslySetInnerHTML={{ __html: html }} />
          : <span className={`${TOKEN_TEXT_CLASS} ${textColor}`}>{r.text || ' '}</span>}
      </div>
    );
  };

  const renderBlock = (block: DiffBlock, index: number) => {
    if (block.kind === 'rows') {
      return block.rows.map((r, i) => renderRow(r, `b${index}-${i}`));
    }
    if (expanded.has(index)) {
      return block.rows.map((r, i) => renderRow(r, `b${index}-${i}`));
    }
    return (
      <button key={`c${index}`} onClick={() => expandBlock(index)}
        className="w-full flex items-center justify-center gap-1 py-0.5 text-[10px] font-mono text-ink-600 hover:text-tech-300 hover:bg-tech-500/5 border-y border-tech-500/10 select-none"
        aria-label={`展开 ${block.count} 行未变更内容`}>
        <ChevronDown className="w-3 h-3" />
        ⋯ 未变更 {block.count} 行 · 展开
      </button>
    );
  };

  const badge = changeType === 'CREATE'
    ? <span className="text-[10px] text-green-400/90 flex-shrink-0">新建文件</span>
    : changeType === 'DELETE'
      ? <span className="text-[10px] text-red-400/90 flex-shrink-0">文件已删除</span>
      : <span className="text-[10px] text-amber-400/90 flex-shrink-0">修改</span>;

  // 当前内容不可读（后端降级 after=null 且非删除类型）：仅展示修改前快照
  const unreadable = after == null && changeType !== 'DELETE';
  const empty = diff.rows.length === 0;

  return (
    <div className="flex-1 min-h-0 flex flex-col">
      {/* 头部：路径 + 变更类型 + 增删统计 */}
      <div className="flex items-center gap-2 px-3 py-1.5 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
        <span className="text-[11px] font-mono text-ink-500 truncate flex-1" title={path}>{path}</span>
        {badge}
        <span className="text-[10px] font-mono flex-shrink-0">
          <span className="text-green-400/80">+{diff.additions}</span>
          {diff.deletions > 0 && <span className="ml-1 text-red-400/70">-{diff.deletions}</span>}
        </span>
      </div>

      {unreadable && (
        <div className="px-3 py-1 text-[10px] text-amber-400/90 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          当前内容不可读（文件已删除或沙箱已销毁），以下为修改前快照
        </div>
      )}
      {oversized && (
        <div className="px-3 py-1 text-[10px] text-amber-400/90 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          文件较大（{diff.rows.length} 行），渲染可能略慢，可切换分栏对照查看
        </div>
      )}

      {/* diff 行区 */}
      <div className="flex-1 min-h-0 overflow-auto scrollbar-thin">
        <div className="min-w-max py-1">
          {empty ? (
            <div className="py-12 text-center text-xs text-ink-600">文件内容为空</div>
          ) : (
            blocks.map(renderBlock)
          )}
        </div>
      </div>
    </div>
  );
}
