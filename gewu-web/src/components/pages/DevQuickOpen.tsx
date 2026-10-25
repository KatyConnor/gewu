'use client';
import { useEffect, useRef, useState } from 'react';
import { Search, FileText, Loader2, CornerDownLeft } from 'lucide-react';
import {
  gitListFiles, gitSearch,
  type GitProject, type GrepHit,
} from '@/lib/dev-workspace';

/**
 * 开发工作空间快速检索面板（IDEA 式）：
 * - file 模式：文件名模糊检索（Ctrl+Shift+N / Ctrl+P），空查询时展示最近打开
 * - search 模式：项目全局文本搜索（Ctrl+Shift+F），命中行点击跳转
 * 键盘：↑↓ 选择、Enter 打开、ESC 关闭。回调一律传仓库内相对路径。
 */
interface Props {
  project: GitProject;
  mode: 'file' | 'search';
  /** 最近打开的文件（工作空间相对路径，file 模式空查询时展示） */
  recentPaths: string[];
  onClose: () => void;
  /** 打开文件（仓库内相对路径；search 命中带行号） */
  onOpenFile: (repoRelPath: string, line?: number) => void;
}

export default function DevQuickOpen({ project, mode, recentPaths, onClose, onOpenFile }: Props) {
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<string[]>([]);
  const [hits, setHits] = useState<GrepHit[]>([]);
  const [loading, setLoading] = useState(false);
  const [active, setActive] = useState(0);
  const [err, setErr] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const seq = useRef(0);

  useEffect(() => { inputRef.current?.focus(); }, []);

  // 检索执行（file：防抖模糊检索；search：Enter 或防抖触发）
  useEffect(() => {
    if (mode === 'file') {
      if (!query.trim()) {
        setItems([]);
        return;
      }
      const mySeq = ++seq.current;
      setLoading(true);
      const timer = setTimeout(async () => {
        try {
          const list = await gitListFiles(project.projectId, query.trim(), 50);
          if (seq.current === mySeq) { setItems(list); setActive(0); setErr(''); }
        } catch (e) {
          if (seq.current === mySeq) setErr(e instanceof Error ? e.message : String(e));
        } finally {
          if (seq.current === mySeq) setLoading(false);
        }
      }, 200);
      return () => clearTimeout(timer);
    }
    // search 模式：空查询清空，否则防抖 400ms
    if (!query.trim()) { setHits([]); setErr(''); return; }
    const mySeq = ++seq.current;
    setLoading(true);
    const timer = setTimeout(async () => {
      try {
        const list = await gitSearch(project.projectId, query.trim(), 100);
        if (seq.current === mySeq) { setHits(list); setActive(0); setErr(''); }
      } catch (e) {
        if (seq.current === mySeq) setErr(e instanceof Error ? e.message : String(e));
      } finally {
        if (seq.current === mySeq) setLoading(false);
      }
    }, 400);
    return () => clearTimeout(timer);
  }, [query, mode, project.projectId]);

  const recentRepoRel = recentPaths
    .filter(p => p.startsWith(project.localPath + '/'))
    .map(p => p.slice(project.localPath.length + 1));

  const total = mode === 'file' ? (query.trim() ? items.length : recentRepoRel.length) : hits.length;

  const openActive = () => {
    if (mode === 'file') {
      const list = query.trim() ? items : recentRepoRel;
      if (list[active]) onOpenFile(list[active]);
    } else if (hits[active]) {
      onOpenFile(hits[active].path, hits[active].line);
    }
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') { e.preventDefault(); onClose(); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive(a => Math.min(a + 1, Math.max(total - 1, 0))); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); openActive(); }
  };

  // 滚动跟随选中项
  useEffect(() => {
    listRef.current?.querySelector(`[data-idx="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  const repoPrefix = `${project.projectName}/`;

  return (
    <div className="fixed inset-0 z-40 flex justify-center" onMouseDown={onClose}>
      <div className="mt-16 w-[560px] max-w-[90vw] glass-dark rounded-xl border border-tech-500/20 shadow-2xl overflow-hidden"
        onMouseDown={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-3.5 py-2.5 border-b border-tech-500/10">
          {mode === 'file' ? <Search className="w-4 h-4 text-tech-400 flex-shrink-0" /> : <Search className="w-4 h-4 text-gold-400 flex-shrink-0" />}
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={mode === 'file' ? '按文件名模糊检索（输入过滤，↑↓ 选择，Enter 打开）' : '在项目中搜索文本（Enter 打开首个命中）'}
            className="flex-1 bg-transparent text-sm text-ink-100 outline-none placeholder:text-ink-600"
          />
          {loading && <Loader2 className="w-3.5 h-3.5 text-tech-400 animate-spin flex-shrink-0" />}
          <kbd className="text-[9px] text-ink-600 border border-ink-700/60 rounded px-1 py-0.5 flex-shrink-0">ESC</kbd>
        </div>

        <div ref={listRef} className="max-h-80 overflow-y-auto p-1.5">
          {err && <div className="px-3 py-4 text-xs text-cinnabar-300">{err}</div>}

          {mode === 'file' ? (
            <>
              {!query.trim() && recentRepoRel.length > 0 && (
                <div className="px-2.5 py-1 text-[10px] text-ink-600">最近打开</div>
              )}
              {!query.trim() && recentRepoRel.length === 0 && (
                <div className="px-3 py-6 text-center text-xs text-ink-500">输入文件名开始检索（支持模糊匹配）</div>
              )}
              {(query.trim() ? items : recentRepoRel).map((p, i) => (
                <button key={p} data-idx={i}
                  onClick={() => onOpenFile(p)}
                  onMouseEnter={() => setActive(i)}
                  className={`w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-left ${i === active ? 'bg-tech-500/15' : 'hover:bg-tech-500/5'}`}>
                  <FileText className="w-3.5 h-3.5 text-ink-500 flex-shrink-0" />
                  <span className="text-xs text-ink-100 truncate flex-1" title={p}>
                    <span className="text-ink-500">{repoPrefix}</span>{p}
                  </span>
                  {i === active && <CornerDownLeft className="w-3 h-3 text-tech-400 flex-shrink-0" />}
                </button>
              ))}
              {query.trim() && !loading && items.length === 0 && !err && (
                <div className="px-3 py-6 text-center text-xs text-ink-500">无匹配文件</div>
              )}
            </>
          ) : (
            <>
              {hits.map((h, i) => (
                <button key={`${h.path}:${h.line}`} data-idx={i}
                  onClick={() => onOpenFile(h.path, h.line)}
                  onMouseEnter={() => setActive(i)}
                  className={`w-full flex flex-col gap-0.5 px-2.5 py-1.5 rounded-lg text-left ${i === active ? 'bg-tech-500/15' : 'hover:bg-tech-500/5'}`}>
                  <div className="flex items-center gap-2 w-full">
                    <FileText className="w-3.5 h-3.5 text-ink-500 flex-shrink-0" />
                    <span className="text-xs text-ink-300 truncate flex-1" title={h.path}>
                      <span className="text-ink-500">{repoPrefix}</span>{h.path}
                    </span>
                    <span className="text-[10px] text-tech-400 font-mono flex-shrink-0">:{h.line}</span>
                  </div>
                  <p className="text-[11px] font-mono text-ink-400 truncate pl-6" title={h.text}>{h.text.trim()}</p>
                </button>
              ))}
              {!query.trim() && <div className="px-3 py-6 text-center text-xs text-ink-500">输入要搜索的文本</div>}
              {query.trim() && !loading && hits.length === 0 && !err && (
                <div className="px-3 py-6 text-center text-xs text-ink-500">无命中结果</div>
              )}
            </>
          )}
        </div>

        <div className="px-3.5 py-1.5 border-t border-tech-500/10 flex items-center justify-between text-[10px] text-ink-600">
          <span>{mode === 'file' ? `Ctrl+Shift+N 文件检索 · Ctrl+E 最近文件` : 'Ctrl+Shift+F 全局搜索'}</span>
          <span>{total > 0 ? `${total} 条结果` : ''}</span>
        </div>
      </div>
    </div>
  );
}
