'use client';
import { useState } from 'react';
import { ChevronDown, ChevronRight, FileText, RotateCcw } from 'lucide-react';
import type { TurnFileChangeDTO } from '@/lib/sessionFileChanges';

/**
 * 回合文件汇总条（AI 回复尾部，zcode 风格）：
 * 折叠显示「N 个文件已更改 +A -D」与全部撤销入口；展开逐文件显示
 * 增删行数与 撤销/审查/打开 操作。撤销仅最近一次回复可用（回合快照）。
 */
export default function TurnChangesBar({ files, undonePaths, canUndo, onUndoAll, onUndoFile, onReview, onOpen }: {
  files: TurnFileChangeDTO[];
  /** 已撤销的路径（撤销成功后由父级更新，本地覆盖标记） */
  undonePaths: Set<string>;
  /** 是否可撤销（仅最近一条汇总条且非流式期间；历史条仅可展开/审查） */
  canUndo: boolean;
  onUndoAll: () => void;
  onUndoFile: (path: string) => void;
  onReview: (path: string) => void;
  onOpen: (path: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const totalAdd = files.reduce((s, f) => s + f.additions, 0);
  const totalDel = files.reduce((s, f) => s + f.deletions, 0);
  const allUndone = files.every(f => undonePaths.has(f.path));
  const fileName = (p: string) => p.slice(p.lastIndexOf('/') + 1);
  const dirOf = (p: string) => p.slice(0, p.lastIndexOf('/') + 1);

  return (
    <div className="mt-2 rounded-lg border" style={{ borderColor: 'rgba(0,184,148,0.1)', background: 'rgba(21,40,38,0.35)' }}>
      {/* 折叠行：N 个文件已更改 +A -D + 全部撤销 */}
      <div className="flex items-center gap-2 px-3 py-2">
        <button onClick={() => setExpanded(prev => !prev)}
          className="flex items-center gap-2 min-w-0 flex-1 text-left text-xs text-ink-300 hover:text-ink-100 transition-colors"
          aria-expanded={expanded}>
          {expanded ? <ChevronDown className="w-3.5 h-3.5 flex-shrink-0 text-ink-500" /> : <ChevronRight className="w-3.5 h-3.5 flex-shrink-0 text-ink-500" />}
          <span className="flex-shrink-0 font-medium">{files.length} 个文件已更改</span>
          <span className="flex-shrink-0 font-mono text-[11px]">
            <span className="text-green-400/80">+{totalAdd}</span>
            {totalDel > 0 && <span className="ml-1 text-red-400/70">-{totalDel}</span>}
          </span>
          {allUndone && <span className="flex-shrink-0 text-[10px] text-ink-600">· 已全部撤销</span>}
        </button>
        {canUndo && !allUndone && (
          <button
            onClick={() => { if (window.confirm(`撤销本次 AI 对 ${files.length} 个文件的全部修改？该操作不可恢复。`)) onUndoAll(); }}
            className="flex-shrink-0 flex items-center gap-1 px-2 py-1 rounded-md text-[11px] text-ink-400 hover:text-tech-300 hover:bg-tech-500/10 border border-transparent hover:border-tech-500/20 transition-all"
            aria-label="撤销本次回复的全部文件修改"
            title="恢复这些文件到本次回复开始前的状态">
            <RotateCcw className="w-3 h-3" />
            撤销
          </button>
        )}
      </div>
      {/* 展开明细：逐文件 +N -D + 撤销/审查/打开 */}
      {expanded && (
        <div className="border-t px-3 py-1" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          {files.map(f => {
            const undone = undonePaths.has(f.path);
            return (
              <div key={f.path} className={`flex items-center gap-2 py-1.5 ${undone ? 'opacity-50' : ''}`}>
                <FileText className="w-3.5 h-3.5 text-ink-500 flex-shrink-0" />
                <span className="text-xs text-ink-200 flex-shrink-0">{fileName(f.path)}</span>
                <span className="text-[11px] font-mono text-ink-600 truncate flex-1" title={f.path}>{dirOf(f.path)}</span>
                <span className="flex-shrink-0 font-mono text-[10px]">
                  <span className="text-green-400/80">+{f.additions}</span>
                  {f.deletions > 0 && <span className="ml-1 text-red-400/70">-{f.deletions}</span>}
                </span>
                {undone ? (
                  <span className="flex-shrink-0 text-[10px] text-ink-600 px-1.5">已撤销</span>
                ) : (
                  <>
                    {canUndo && (
                      <button onClick={() => onUndoFile(f.path)}
                        className="p-1 rounded text-ink-500 hover:text-amber-400 transition-colors flex-shrink-0"
                        aria-label={`撤销对 ${fileName(f.path)} 的修改`} title="撤销此文件的修改">
                        <RotateCcw className="w-3 h-3" />
                      </button>
                    )}
                    <button onClick={() => onReview(f.path)}
                      className="px-1.5 py-0.5 text-[10px] rounded text-tech-300 hover:text-tech-200 hover:bg-tech-500/10 border border-tech-500/20 flex-shrink-0"
                      title="查看变更差异">审查</button>
                    <button onClick={() => onOpen(f.path)}
                      className="px-1.5 py-0.5 text-[10px] rounded text-ink-300 hover:text-ink-100 hover:bg-ink-700/40 border border-ink-700/60 flex-shrink-0"
                      title="查看完整文件内容">打开</button>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
