'use client';
import { useCallback, useEffect, useMemo, useState } from 'react';
import dynamic from 'next/dynamic';
import {
  ChevronDown, ChevronRight, FileDiff, FilePlus, FileText, Loader2,
  PanelRightClose, Pencil, Save, Undo2, X,
} from 'lucide-react';
import type { FileChangeDTO } from '@/lib/sessionFileChanges';
import {
  getFileContent, getFileDiff, saveFileContent,
} from '@/lib/sessionFileChanges';
import { isMarkdownPath, monacoLangOf } from '@/lib/fileLanguage';
import InlineDiffView from './InlineDiffView';
import MarkdownReader from '@/components/ui/MarkdownReader';

/** 面板默认宽度（px）：ChatPage 拖拽调宽时的最窄边界（向右拖不越过默认位置） */
export const FILE_PANEL_MIN_WIDTH = 520;

// Monaco 懒加载（自托管 public/monaco，免 CDN；面板打开时才加载）
const MonacoDiffEditor = dynamic(() => import('./MonacoDiff'), { ssr: false });
const MonacoFileEditor = dynamic(() => import('./MonacoEditor'), { ssr: false });

/** 标签页类型：审查（diff）或文件（预览/编辑） */
type TabKind = 'diff' | 'file';

interface OpenTab {
  path: string;
  kind: TabKind;
}

/** 审查 diff 数据（before 快照 + 当前内容 + 变更类型） */
interface DiffData {
  before: string | null;
  after: string | null;
  changeType: string;
}

const tabKey = (kind: TabKind, path: string) => `${kind}:${path}`;

/** 变更类型徽标 */
function changeBadge(changeType?: string) {
  if (changeType === 'CREATE') return <span className="text-[10px] text-green-400/90 flex-shrink-0">新建</span>;
  if (changeType === 'DELETE') return <span className="text-[10px] text-red-400/90 flex-shrink-0">已删除</span>;
  return <span className="text-[10px] text-amber-400/90 flex-shrink-0">修改</span>;
}

/** 分段切换按钮（内联/分栏、阅读/源码共用样式） */
function Segmented<T extends string>({ value, options, onChange }: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="flex items-center rounded border border-tech-500/20 overflow-hidden flex-shrink-0">
      {options.map(o => (
        <button key={o.value} onClick={() => onChange(o.value)}
          className={`px-2 py-0.5 text-[10px] transition-all ${
            value === o.value ? 'bg-tech-500/15 text-tech-300' : 'text-ink-500 hover:text-ink-300'
          }`}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

/**
 * 右侧文件编辑面板（S9 F3，zcode 风格）。
 * 默认折叠；会话结束显示变更文件列表（目录分组：路径作组头，文件行 +N/-N + 审查/打开）：
 * 审查=内联 diff（修改区+上下文行+折叠，可切 Monaco 分栏）；打开=只读预览
 * （代码 Monaco 高亮 / Markdown 阅读模式），「编辑」进入可编辑 Monaco
 * （Ctrl+S 保存写回工作空间，脏标记提示）；多文件 tab 栏切换，数据按路径缓存。
 */
export default function FileEditorPanel({ sessionId, changes, onClose, onChangesRefresh, width = FILE_PANEL_MIN_WIDTH, openRequest, onOpenRequestConsumed }: {
  sessionId: string;
  changes: FileChangeDTO[];
  onClose: () => void;
  onChangesRefresh: () => void;
  /** 面板宽度（px）：由 ChatPage 拖拽分隔条控制并持久化 */
  width?: number;
  /** 外部打开请求（回合汇总条「审查/打开」定位 tab）：消费一次后由父级清空 */
  openRequest?: { path: string; mode: 'diff' | 'edit'; seq: number } | null;
  onOpenRequestConsumed?: () => void;
}) {
  const [tabs, setTabs] = useState<OpenTab[]>([]);
  const [activeTab, setActiveTab] = useState<OpenTab | null>(null);
  // 数据/视图状态均按 path 记忆，tab 间切换互不串位
  const [diffs, setDiffs] = useState<Record<string, DiffData>>({});
  const [editorTexts, setEditorTexts] = useState<Record<string, string>>({});
  const [savedTexts, setSavedTexts] = useState<Record<string, string>>({});
  const [dirtyPaths, setDirtyPaths] = useState<Set<string>>(new Set());
  const [diffModes, setDiffModes] = useState<Record<string, 'inline' | 'split'>>({});
  const [fileModes, setFileModes] = useState<Record<string, 'preview' | 'edit'>>({});
  const [mdModes, setMdModes] = useState<Record<string, 'read' | 'source'>>({});
  const [loadings, setLoadings] = useState<Set<string>>(new Set());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [listCollapsed, setListCollapsed] = useState(false);

  const changeTypeOf = useCallback((path: string) =>
    changes.find(c => c.path === path)?.changeType, [changes]);

  /** 拉取并缓存 diff 数据（含读失败降级后的 before-only 快照） */
  const loadDiffTab = useCallback(async (path: string) => {
    const key = tabKey('diff', path);
    setLoadings(prev => new Set(prev).add(key));
    setErrors(prev => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
    try {
      const d = await getFileDiff(sessionId, path);
      setDiffs(prev => ({ ...prev, [path]: { before: d.before, after: d.after, changeType: d.changeType } }));
    } catch (e) {
      setErrors(prev => ({ ...prev, [key]: e instanceof Error ? e.message : '加载失败' }));
    } finally {
      setLoadings(prev => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    }
  }, [sessionId]);

  const openTab = useCallback(async (path: string, kind: TabKind) => {
    const existing = tabs.find(t => t.kind === kind && t.path === path);
    if (existing) {
      setActiveTab(existing);
      return;
    }
    const key = tabKey(kind, path);
    setTabs(prev => [...prev, { path, kind }]);
    setActiveTab({ path, kind });
    setLoadings(prev => new Set(prev).add(key));
    setErrors(prev => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
    try {
      if (kind === 'diff') {
        await loadDiffTab(path);
      } else {
        const content = await getFileContent(sessionId, path);
        setEditorTexts(prev => ({ ...prev, [path]: content }));
        setSavedTexts(prev => ({ ...prev, [path]: content }));
        setDirtyPaths(prev => {
          const next = new Set(prev);
          next.delete(path);
          return next;
        });
      }
    } catch (e) {
      if (kind === 'file' && changeTypeOf(path) === 'DELETE') {
        // 已删除文件无法打开：回退审查视图（展示删除前快照）
        setTabs(prev => prev.filter(t => !(t.kind === 'file' && t.path === path)));
        setActiveTab({ path, kind: 'diff' });
        await loadDiffTab(path);
      } else {
        setErrors(prev => ({ ...prev, [key]: e instanceof Error ? e.message : '加载失败' }));
      }
    } finally {
      setLoadings(prev => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    }
  }, [sessionId, tabs, changes, changeTypeOf, loadDiffTab]);

  const closeTab = (tab: OpenTab) => {
    setTabs(prev => {
      const next = prev.filter(t => t !== tab);
      if (activeTab === tab) {
        setActiveTab(next.length > 0 ? next[next.length - 1] : null);
      }
      return next;
    });
  };

  const save = useCallback(async () => {
    if (!activeTab || activeTab.kind !== 'file' || saving) return;
    const path = activeTab.path;
    if (!dirtyPaths.has(path) || fileModes[path] !== 'edit') return;
    setSaving(true);
    setErrors(prev => {
      const next = { ...prev };
      delete next[tabKey('file', path)];
      return next;
    });
    try {
      await saveFileContent(sessionId, path, editorTexts[path] ?? '');
      setDirtyPaths(prev => {
        const next = new Set(prev);
        next.delete(path);
        return next;
      });
      setSavedTexts(prev => ({ ...prev, [path]: editorTexts[path] ?? '' }));
      onChangesRefresh();
    } catch (e) {
      setErrors(prev => ({ ...prev, [tabKey('file', path)]: e instanceof Error ? e.message : '保存失败' }));
    } finally {
      setSaving(false);
    }
  }, [activeTab, dirtyPaths, editorTexts, fileModes, saving, sessionId, onChangesRefresh]);

  // Ctrl+S 保存（仅文件 tab 编辑态生效）
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        save();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [save]);

  const enterEdit = (path: string) => setFileModes(prev => ({ ...prev, [path]: 'edit' }));

  /** 退出编辑：有未保存修改先确认，放弃则还原到最近保存内容 */
  const exitEdit = (path: string) => {
    if (dirtyPaths.has(path) && !window.confirm('有未保存的修改，确定放弃并退出编辑？')) return;
    setEditorTexts(prev => ({ ...prev, [path]: savedTexts[path] ?? '' }));
    setDirtyPaths(prev => {
      const next = new Set(prev);
      next.delete(path);
      return next;
    });
    setFileModes(prev => ({ ...prev, [path]: 'preview' }));
  };

  // 外部打开请求（回合汇总条「审查/打开」）：消费一次即清空防重复触发
  useEffect(() => {
    if (!openRequest) return;
    openTab(openRequest.path, openRequest.mode === 'diff' ? 'diff' : 'file');
    onOpenRequestConsumed?.();
    // openTab 随 tabs 状态重建，依赖它会在 tab 增删时重跑；openRequest 已清空故仅守卫返回
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openRequest]);

  const totalAdd = useMemo(() => changes.reduce((s, c) => s + (c.additions || 0), 0), [changes]);
  const totalDel = useMemo(() => changes.reduce((s, c) => s + (c.deletions || 0), 0), [changes]);

  // 变更文件按目录前缀分组（保持接口返回顺序）：目录路径作组头，文件行只显示文件名
  const groupedChanges = useMemo(() => {
    const groups: { dir: string; items: FileChangeDTO[] }[] = [];
    const indexOf = new Map<string, number>();
    for (const c of changes) {
      const dir = c.path.slice(0, c.path.lastIndexOf('/') + 1) || '/';
      const idx = indexOf.get(dir);
      if (idx === undefined) {
        indexOf.set(dir, groups.length);
        groups.push({ dir, items: [c] });
      } else {
        groups[idx].items.push(c);
      }
    }
    return groups;
  }, [changes]);

  const fileName = (p: string) => p.slice(p.lastIndexOf('/') + 1);

  // 当前激活 tab 的派生状态
  const active = activeTab;
  const activeKey = active ? tabKey(active.kind, active.path) : '';
  const activeDiff = active ? diffs[active.path] : undefined;
  const activeText = active ? editorTexts[active.path] ?? '' : '';
  const activeDirty = active ? dirtyPaths.has(active.path) : false;
  const activeFileMode: 'preview' | 'edit' = active ? fileModes[active.path] ?? 'preview' : 'preview';
  const activeDiffMode: 'inline' | 'split' = active ? diffModes[active.path] ?? 'inline' : 'inline';
  const activeMdMode: 'read' | 'source' = active ? mdModes[active.path] ?? 'read' : 'read';
  const activeIsMd = active ? isMarkdownPath(active.path) : false;
  const activeError = errors[activeKey];

  return (
    <div className="flex-shrink-0 border-l flex flex-col h-full" style={{ width, borderColor: 'rgba(0,184,148,0.1)' }}>
      {/* 头部：N 个文件已更改 +a -b + 关闭 */}
      <div className="flex items-center gap-2 px-3 py-2 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
        <FileDiff className="w-3.5 h-3.5 text-tech-400 flex-shrink-0" />
        <span className="text-xs font-medium text-ink-200 flex-1">
          {changes.length} 个文件已更改
          <span className="ml-2 text-green-400/80">+{totalAdd}</span>
          <span className="ml-1 text-red-400/70">-{totalDel}</span>
        </span>
        <button onClick={onClose} className="p-1.5 text-ink-500 hover:text-tech-400 rounded-md transition-all"
          aria-label="关闭文件面板" title="收起文件面板"><PanelRightClose className="w-4 h-4" /></button>
      </div>

      {/* 变更文件列表（可折叠） */}
      <div className="border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
        <button onClick={() => setListCollapsed(prev => !prev)}
          className="w-full flex items-center gap-1.5 px-3 py-1.5 text-[11px] text-ink-400 hover:text-ink-200">
          {listCollapsed ? <ChevronRight className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
          变更文件
        </button>
        {!listCollapsed && (
          <div className="max-h-44 overflow-y-auto scrollbar-thin pb-1">
            {groupedChanges.map((g, gi) => (
              <div key={g.dir}>
                {/* 目录组头：完整路径（原行内路径前缀上移至此） */}
                <p className={`px-3 py-0.5 text-[10px] font-mono text-ink-600 truncate ${gi > 0 ? 'border-t' : ''}`}
                  style={{ borderColor: 'rgba(0,184,148,0.08)' }} title={g.dir}>{g.dir}</p>
                {g.items.map(c => (
                  <div key={c.path} className="group/f flex items-center gap-2 px-3 py-1.5 hover:bg-tech-500/5">
                    {c.changeType === 'CREATE'
                      ? <FilePlus className="w-3.5 h-3.5 text-green-400/70 flex-shrink-0" />
                      : <FileText className="w-3.5 h-3.5 text-ink-500 flex-shrink-0" />}
                    <span className="text-[11px] font-mono text-ink-300 truncate flex-1" title={c.path}>{fileName(c.path)}</span>
                    <span className="text-[10px] text-green-400/80 flex-shrink-0">+{c.additions || 0}</span>
                    <span className="text-[10px] text-red-400/70 flex-shrink-0">-{c.deletions || 0}</span>
                    <button onClick={() => openTab(c.path, 'diff')}
                      className="px-1.5 py-0.5 text-[10px] rounded text-tech-300 hover:text-tech-200 hover:bg-tech-500/10 border border-tech-500/20 flex-shrink-0"
                      title="查看变更差异">审查</button>
                    <button onClick={() => openTab(c.path, 'file')}
                      className="px-1.5 py-0.5 text-[10px] rounded text-ink-300 hover:text-ink-100 hover:bg-ink-700/40 border border-ink-700/60 flex-shrink-0"
                      title="查看完整文件内容">打开</button>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 打开文件 tab 栏 */}
      {tabs.length > 0 && (
        <div className="flex items-center border-b overflow-x-auto scrollbar-thin flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          {tabs.map(tab => (
            <div key={tabKey(tab.kind, tab.path)}
              onClick={() => setActiveTab(tab)}
              className={`group/tab flex items-center gap-1 px-3 py-1.5 cursor-pointer border-r text-[11px] font-mono whitespace-nowrap ${
                activeTab === tab ? 'text-ink-100 bg-tech-500/10' : 'text-ink-500 hover:text-ink-300'
              }`} style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
              {tab.kind === 'diff' ? <FileDiff className="w-3 h-3" /> : <FileText className="w-3 h-3" />}
              <span>{fileName(tab.path)}</span>
              <button onClick={(e) => { e.stopPropagation(); closeTab(tab); }}
                className="p-0.5 text-ink-600 hover:text-red-400 rounded" aria-label="关闭标签"><X className="w-2.5 h-2.5" /></button>
            </div>
          ))}
        </div>
      )}

      {/* 编辑器区域 */}
      <div className="flex-1 min-h-0 flex flex-col relative">
        {active == null ? (
          <div className="flex-1 flex items-center justify-center">
            <p className="text-xs text-ink-600">从变更列表中选择「审查」或「打开」</p>
          </div>
        ) : loadings.has(activeKey) ? (
          <div className="flex-1 flex items-center justify-center">
            <Loader2 className="w-4 h-4 text-tech-400 animate-spin" />
          </div>
        ) : active.kind === 'diff' ? (
          /* ===== 审查 tab：内联 diff（默认）+ Monaco 分栏切换 ===== */
          <>
            <div className="flex items-center gap-2 px-3 py-1.5 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
              <span className="text-[11px] font-mono text-ink-500 truncate flex-1" title={active.path}>{active.path}</span>
              {changeBadge(activeDiff?.changeType ?? changeTypeOf(active.path))}
              <Segmented value={activeDiffMode}
                options={[{ value: 'inline' as const, label: '内联' }, { value: 'split' as const, label: '分栏' }]}
                onChange={v => setDiffModes(prev => ({ ...prev, [active.path]: v }))} />
            </div>
            {activeError && (
              <div className="px-3 py-1 text-[10px] text-red-400/80 border-b" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>{activeError}</div>
            )}
            {activeDiffMode === 'inline' ? (
              <InlineDiffView before={activeDiff?.before ?? null} after={activeDiff?.after ?? null}
                changeType={activeDiff?.changeType ?? 'MODIFY'} path={active.path} />
            ) : (
              <div className="flex-1 min-h-0">
                <MonacoDiffEditor original={activeDiff?.before ?? ''} modified={activeDiff?.after ?? ''}
                  language={monacoLangOf(active.path)} path={active.path} />
              </div>
            )}
          </>
        ) : (
          /* ===== 文件 tab：预览（代码高亮 / Markdown 阅读）+ 编辑两态 ===== */
          <>
            <div className="flex items-center gap-2 px-3 py-1.5 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
              <span className="text-[11px] font-mono text-ink-500 truncate flex-1" title={active.path}>{active.path}</span>
              {activeFileMode === 'preview' ? (
                <>
                  {changeBadge(changeTypeOf(active.path))}
                  {activeIsMd && (
                    <Segmented value={activeMdMode}
                      options={[{ value: 'read' as const, label: '阅读' }, { value: 'source' as const, label: '源码' }]}
                      onChange={v => setMdModes(prev => ({ ...prev, [active.path]: v }))} />
                  )}
                  <button onClick={() => enterEdit(active.path)}
                    className="flex items-center gap-1 px-2 py-0.5 text-[10px] rounded text-ink-300 hover:text-ink-100 hover:bg-ink-700/40 border border-ink-700/60 transition-all flex-shrink-0"
                    title="编辑此文件（Ctrl+S 保存写回工作空间）">
                    <Pencil className="w-3 h-3" />编辑
                  </button>
                </>
              ) : (
                <>
                  {activeDirty && <span className="text-[10px] text-amber-400 flex-shrink-0">● 未保存</span>}
                  <button onClick={save} disabled={!activeDirty || saving}
                    className={`flex items-center gap-1 px-2 py-0.5 text-[10px] rounded border transition-all flex-shrink-0 ${
                      activeDirty ? 'text-tech-300 hover:bg-tech-500/10 border-tech-500/30' : 'text-ink-600 border-ink-700/40'
                    } disabled:opacity-40`} title="Ctrl+S 保存（写回工作空间）">
                    {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}
                    保存
                  </button>
                  <button onClick={() => exitEdit(active.path)}
                    className="flex items-center gap-1 px-2 py-0.5 text-[10px] rounded text-ink-400 hover:text-ink-100 hover:bg-ink-700/40 border border-ink-700/60 transition-all flex-shrink-0"
                    title="退出编辑（未保存修改将还原）">
                    <Undo2 className="w-3 h-3" />退出
                  </button>
                </>
              )}
            </div>
            {activeError && (
              <div className="px-3 py-1 text-[10px] text-red-400/80 border-b" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>{activeError}</div>
            )}
            {activeFileMode === 'preview' ? (
              activeIsMd && activeMdMode === 'read' ? (
                <div className="flex-1 min-h-0 overflow-auto scrollbar-thin">
                  <MarkdownReader content={activeText} />
                </div>
              ) : (
                <div className="flex-1 min-h-0">
                  <MonacoFileEditor value={activeText} language={monacoLangOf(active.path)}
                    path={active.path} readOnly wordWrap="off" />
                </div>
              )
            ) : (
              <div className="flex-1 min-h-0">
                <MonacoFileEditor value={activeText} language={monacoLangOf(active.path)}
                  path={active.path} wordWrap="on"
                  onChange={(v) => {
                    const text = v ?? '';
                    setEditorTexts(prev => ({ ...prev, [active.path]: text }));
                    setDirtyPaths(prev => new Set(prev).add(active.path));
                  }} />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
