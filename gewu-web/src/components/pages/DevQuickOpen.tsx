'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Search, FileText, Loader2, CornerDownLeft, Zap, MapPin, Bookmark, Box, Code2 } from 'lucide-react';
import {
  gitListFiles, gitSearch,
  type GitProject, type GrepHit,
} from '@/lib/dev-workspace';

/**
 * 开发工作空间快速检索面板（IDEA 式统一弹层）：
 * - file 模式：文件名模糊检索（Ctrl+Shift+N / Ctrl+P），空查询时展示最近打开
 * - search 模式：项目全局文本搜索（Ctrl+Shift+F），命中行点击跳转
 * - everywhere 模式：Search Everywhere（Double Shift）——文件 + 动作 + 最近 + 书签聚合
 * - actions 模式：Find Action 命令面板（Ctrl+Shift+A）
 * - locations 模式：Recent Locations（Ctrl+Shift+E）
 * - usages 模式：Find Usages 结果（Alt+F7）
 * - symbols 模式：Go to Symbol（Ctrl+Shift+Alt+N）
 * - structure 模式：当前文件结构（Ctrl+F12）
 * - bookmarks 模式：书签列表（Shift+F11）
 * 键盘：↑↓ 选择、Enter 打开、ESC 关闭。文件回调一律传仓库内相对路径。
 */
export type QuickMode = 'file' | 'search' | 'everywhere' | 'actions' | 'locations' | 'usages' | 'symbols' | 'structure' | 'bookmarks';

/** Find Action 动作项 */
export interface QuickAction {
  id: string;
  label: string;
  /** 快捷键提示 */
  hint?: string;
  run: () => void;
}

/** 统一结果行（聚合各来源后渲染） */
interface ResultRow {
  key: string;
  icon: 'file' | 'action' | 'location' | 'bookmark' | 'symbol' | 'usage';
  primary: string;
  secondary?: string;
  line?: number;
  /** 打开动作（动作类直接 run；文件类 onOpenFile） */
  open: () => void;
}

interface Props {
  project: GitProject;
  mode: QuickMode;
  /** 初始查询（跳转定义/查找用法注入光标词） */
  initialQuery?: string;
  /** 最近打开的文件（工作空间相对路径，file/everywhere 空查询时展示） */
  recentPaths: string[];
  /** 动作注册表（actions/everywhere 数据源） */
  actions?: QuickAction[];
  /** 最近光标位置（locations 数据源） */
  locations?: Array<{ path: string; line: number; col: number; text: string }>;
  /** 书签列表（bookmarks 数据源；仓库相对路径:行） */
  bookmarks?: Array<{ rel: string; line: number }>;
  /** 当前文件结构大纲（structure 数据源） */
  structure?: Array<{ name: string; line: number; kind: string; depth: number }>;
  /** 当前文件（structure 标题） */
  structurePath?: string;
  /** 自定义符号检索（usages/symbols/everywhere；返回仓库相对路径结果） */
  queryRunner?: (query: string) => Promise<Array<{ path: string; line?: number; text?: string; kind?: string }>>;
  onClose: () => void;
  /** 打开文件（仓库内相对路径；search 命中带行号） */
  onOpenFile: (repoRelPath: string, line?: number) => void;
}

const ICONS = { file: FileText, action: Zap, location: MapPin, bookmark: Bookmark, symbol: Box, usage: Code2 } as const;

export default function DevQuickOpen({
  project, mode, initialQuery = '', recentPaths, actions = [], locations = [], bookmarks = [], structure = [],
  structurePath, queryRunner, onClose, onOpenFile,
}: Props) {
  const [query, setQuery] = useState(initialQuery);
  const [items, setItems] = useState<string[]>([]);
  const [hits, setHits] = useState<GrepHit[]>([]);
  const [remoteRows, setRemoteRows] = useState<Array<{ path: string; line?: number; text?: string; kind?: string }>>([]);
  const [loading, setLoading] = useState(false);
  const [active, setActive] = useState(0);
  const [err, setErr] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const seq = useRef(0);

  useEffect(() => { inputRef.current?.focus(); }, []);

  const openRel = (repoRel: string, line?: number) => onOpenFile(repoRel, line);

  const recentRepoRel = useMemo(
    () => recentPaths.filter(p => p.startsWith(project.localPath + '/')).map(p => p.slice(project.localPath.length + 1)),
    [recentPaths, project.localPath]);

  const q = query.trim().toLowerCase();

  // ===== 静态行（无网络）：actions/locations/bookmarks/structure/everywhere 本地部分 =====
  const staticRows = useMemo<ResultRow[]>(() => {
    const rows: ResultRow[] = [];
    if (mode === 'actions') {
      actions.filter(a => !q || a.label.toLowerCase().includes(q) || a.id.includes(q)).forEach(a =>
        rows.push({ key: `act-${a.id}`, icon: 'action', primary: a.label, secondary: a.hint, open: a.run }));
    }
    if (mode === 'locations') {
      locations.filter(l => !q || l.path.toLowerCase().includes(q) || l.text.toLowerCase().includes(q)).forEach((l, i) =>
        rows.push({
          key: `loc-${i}`, icon: 'location', primary: l.text || '（空行）', secondary: l.path.slice(project.localPath.length + 1), line: l.line,
          open: () => openRel(l.path.slice(project.localPath.length + 1), l.line),
        }));
    }
    if (mode === 'bookmarks') {
      bookmarks.filter(b => !q || b.rel.toLowerCase().includes(q)).forEach((b, i) =>
        rows.push({
          key: `bm-${i}`, icon: 'bookmark', primary: `${b.rel}:${b.line}`, secondary: '书签',
          open: () => openRel(b.rel, b.line),
        }));
    }
    if (mode === 'structure') {
      structure.filter(s => !q || s.name.toLowerCase().includes(q)).forEach((s, i) =>
        rows.push({
          key: `st-${i}`, icon: 'symbol', primary: `${'　'.repeat(Math.min(s.depth, 6))}${s.name}`, secondary: `${s.kind} · 行 ${s.line}`,
          line: s.line, open: () => openRel((structurePath || '').slice(project.localPath.length + 1), s.line),
        }));
    }
    if (mode === 'everywhere') {
      // 动作 + 最近文件 + 书签（文件检索走远端分支）
      actions.filter(a => !q || a.label.toLowerCase().includes(q)).slice(0, q ? 12 : 6).forEach(a =>
        rows.push({ key: `ew-act-${a.id}`, icon: 'action', primary: a.label, secondary: a.hint, open: a.run }));
      if (!q) {
        recentRepoRel.slice(0, 8).forEach(p =>
          rows.push({ key: `ew-rec-${p}`, icon: 'file', primary: p, secondary: '最近打开', open: () => openRel(p) }));
        bookmarks.slice(0, 5).forEach((b, i) =>
          rows.push({ key: `ew-bm-${i}`, icon: 'bookmark', primary: `${b.rel}:${b.line}`, secondary: '书签', open: () => openRel(b.rel, b.line) }));
      }
    }
    return rows;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, actions, locations, bookmarks, structure, q, recentRepoRel, project.localPath]);

  // ===== 检索执行 =====
  useEffect(() => {
    setErr('');
    if (mode === 'file' || (mode === 'everywhere' && !!q)) {
      if (mode === 'file' && !q) { setItems([]); return; }
      const mySeq = ++seq.current;
      setLoading(true);
      const timer = setTimeout(async () => {
        try {
          const list = await gitListFiles(project.projectId, query.trim(), 50);
          if (seq.current === mySeq) { setItems(list); setActive(0); }
        } catch (e) {
          if (seq.current === mySeq) setErr(e instanceof Error ? e.message : String(e));
        } finally {
          if (seq.current === mySeq) setLoading(false);
        }
      }, 200);
      return () => clearTimeout(timer);
    }
    if (mode === 'search') {
      if (!q) { setHits([]); return; }
      const mySeq = ++seq.current;
      setLoading(true);
      const timer = setTimeout(async () => {
        try {
          const list = await gitSearch(project.projectId, query.trim(), 100);
          if (seq.current === mySeq) { setHits(list); setActive(0); }
        } catch (e) {
          if (seq.current === mySeq) setErr(e instanceof Error ? e.message : String(e));
        } finally {
          if (seq.current === mySeq) setLoading(false);
        }
      }, 400);
      return () => clearTimeout(timer);
    }
    if ((mode === 'usages' || mode === 'symbols' || (mode === 'everywhere' && !!q)) && queryRunner) {
      if (!q && mode !== 'usages') { setRemoteRows([]); return; }
      const mySeq = ++seq.current;
      setLoading(true);
      const timer = setTimeout(async () => {
        try {
          const list = await queryRunner(query.trim());
          if (seq.current === mySeq) { setRemoteRows(list.slice(0, 100)); setActive(0); }
        } catch (e) {
          if (seq.current === mySeq) setErr(e instanceof Error ? e.message : String(e));
        } finally {
          if (seq.current === mySeq) setLoading(false);
        }
      }, 350);
      return () => clearTimeout(timer);
    }
    setItems([]); setHits([]); setRemoteRows([]);
  }, [query, q, mode, project.projectId, queryRunner]);

  // ===== 汇总结果行 =====
  const rows = useMemo<ResultRow[]>(() => {
    const list = [...staticRows];
    if (mode === 'file' || (mode === 'everywhere' && !!q)) {
      (q ? items : recentRepoRel).forEach(p => {
        if (!list.some(r => r.key === `ew-rec-${p}`)) list.push({ key: `f-${p}`, icon: 'file', primary: p, open: () => openRel(p) });
      });
    }
    if (mode === 'search') {
      hits.forEach(h => list.push({
        key: `h-${h.path}:${h.line}`, icon: 'usage', primary: h.text.trim(), secondary: h.path, line: h.line,
        open: () => openRel(h.path, h.line),
      }));
    }
    if (mode === 'usages' || mode === 'symbols') {
      remoteRows.forEach((r, i) => list.push({
        key: `q-${i}`, icon: 'symbol', primary: r.text?.trim() || r.path, secondary: r.kind ? `${r.kind} · ${r.path}` : r.path, line: r.line,
        open: () => openRel(r.path, r.line),
      }));
    }
    if (mode === 'everywhere' && !!q) {
      remoteRows.slice(0, 20).forEach((r, i) => list.push({
        key: `ew-sym-${i}`, icon: 'symbol', primary: r.text?.trim() || r.path, secondary: r.kind ? `${r.kind} · ${r.path}` : r.path, line: r.line,
        open: () => openRel(r.path, r.line),
      }));
    }
    return list;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [staticRows, mode, items, hits, remoteRows, recentRepoRel]);

  const total = rows.length;

  const openActive = () => {
    const r = rows[active];
    if (r) r.open();
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

  useEffect(() => { setActive(0); }, [q, mode]);

  const repoPrefix = `${project.projectName}/`;
  const placeholderOf: Record<QuickMode, string> = {
    file: '按文件名模糊检索（输入过滤，↑↓ 选择，Enter 打开）',
    search: '在项目中搜索文本（Enter 打开首个命中）',
    everywhere: '搜索一切：文件 / 动作 / 最近 / 书签 / 符号（Double Shift）',
    actions: '输入动作名称执行命令（Ctrl+Shift+A）',
    locations: '最近光标位置（Ctrl+Shift+E）',
    usages: '查找用法结果（Alt+F7），点击跳转',
    symbols: '按符号名全项目跳转（Ctrl+Shift+Alt+N）',
    structure: '当前文件结构（Ctrl+F12），输入过滤',
    bookmarks: '书签列表（Shift+F11），Enter 跳转',
  };

  return (
    <div className="fixed inset-0 z-40 flex justify-center" onMouseDown={onClose}>
      <div className="mt-16 w-[560px] max-w-[90vw] glass-dark rounded-xl border border-tech-500/20 shadow-2xl overflow-hidden"
        onMouseDown={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-3.5 py-2.5 border-b border-tech-500/10">
          <Search className={`w-4 h-4 flex-shrink-0 ${mode === 'search' || mode === 'usages' ? 'text-gold-400' : 'text-tech-400'}`} />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={placeholderOf[mode]}
            className="flex-1 bg-transparent text-sm text-ink-100 outline-none placeholder:text-ink-600"
          />
          {loading && <Loader2 className="w-3.5 h-3.5 text-tech-400 animate-spin flex-shrink-0" />}
          <kbd className="text-[9px] text-ink-600 border border-ink-700/60 rounded px-1 py-0.5 flex-shrink-0">ESC</kbd>
        </div>

        <div ref={listRef} className="max-h-80 overflow-y-auto p-1.5">
          {err && <div className="px-3 py-4 text-xs text-cinnabar-300">{err}</div>}

          {rows.length === 0 && !loading && !err && (
            <div className="px-3 py-6 text-center text-xs text-ink-500">
              {mode === 'file' ? '输入文件名开始检索（支持模糊匹配）'
                : mode === 'search' ? '输入要搜索的文本'
                : mode === 'locations' ? '暂无光标位置历史'
                : mode === 'bookmarks' ? '暂无书签（编辑器内 F11 添加）'
                : mode === 'structure' ? '未识别到符号（仅代码/MD/YAML/XML 支持）'
                : mode === 'usages' || mode === 'symbols' ? '输入符号名检索'
                : '无匹配项'}
            </div>
          )}

          {rows.map((r, i) => {
            const Icon = ICONS[r.icon];
            const isPath = r.icon === 'file' || r.icon === 'usage';
            return (
              <button key={r.key} data-idx={i}
                onClick={r.open}
                onMouseEnter={() => setActive(i)}
                className={`w-full flex flex-col gap-0.5 px-2.5 py-1.5 rounded-lg text-left ${i === active ? 'bg-tech-500/15' : 'hover:bg-tech-500/5'}`}>
                <div className="flex items-center gap-2 w-full">
                  <Icon className={`w-3.5 h-3.5 flex-shrink-0 ${r.icon === 'action' ? 'text-gold-400' : r.icon === 'bookmark' ? 'text-sky-400' : 'text-ink-500'}`} />
                  <span className={`text-xs truncate flex-1 ${r.icon === 'location' ? 'text-ink-400 font-mono' : 'text-ink-100'}`}
                    title={isPath ? `${repoPrefix}${r.primary}` : r.primary}>
                    {isPath && <span className="text-ink-500">{repoPrefix}</span>}
                    {r.primary}
                  </span>
                  {r.secondary && <span className="text-[10px] text-ink-600 truncate max-w-[180px] flex-shrink-0">{r.secondary}</span>}
                  {r.line !== undefined && r.icon !== 'symbol' && <span className="text-[10px] text-tech-400 font-mono flex-shrink-0">:{r.line}</span>}
                  {i === active && <CornerDownLeft className="w-3 h-3 text-tech-400 flex-shrink-0" />}
                </div>
              </button>
            );
          })}
        </div>

        <div className="px-3.5 py-1.5 border-t border-tech-500/10 flex items-center justify-between text-[10px] text-ink-600">
          <span>{placeholderOf[mode].split('（')[0]}</span>
          <span>{total > 0 ? `${total} 条结果` : ''}</span>
        </div>
      </div>
    </div>
  );
}
