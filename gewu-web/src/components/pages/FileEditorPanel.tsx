'use client';
import { useCallback, useEffect, useMemo, useState } from 'react';
import dynamic from 'next/dynamic';
import {
  ChevronDown, ChevronRight, FileDiff, FilePlus, FileText, Loader2,
  PanelRightClose, Save, X,
} from 'lucide-react';
import type { FileChangeDTO } from '@/lib/sessionFileChanges';
import {
  getFileContent, getFileDiff, saveFileContent,
} from '@/lib/sessionFileChanges';

// Monaco 懒加载（自托管 public/monaco，免 CDN；面板打开时才加载）
const MonacoDiffEditor = dynamic(() => import('./MonacoDiff'), { ssr: false });
const MonacoFileEditor = dynamic(() => import('./MonacoEditor'), { ssr: false });

/** 打开的标签页：审查（diff）或编辑（全文） */
interface OpenTab {
  path: string;
  mode: 'diff' | 'edit';
}

/**
 * 右侧文件编辑面板（S9 F3，zcode 风格）。
 * 默认折叠；会话结束显示变更文件列表（路径 + +N/-N + 审查/打开按钮）：
 * 审查=before 快照 vs 当前内容的 Monaco DiffEditor；打开=可编辑 Monaco
 * （Ctrl+S 保存写回工作空间，脏标记提示）；多文件 tab 栏切换。
 */
export default function FileEditorPanel({ sessionId, changes, onClose, onChangesRefresh }: {
  sessionId: string;
  changes: FileChangeDTO[];
  onClose: () => void;
  onChangesRefresh: () => void;
}) {
  const [tabs, setTabs] = useState<OpenTab[]>([]);
  const [activeTab, setActiveTab] = useState<OpenTab | null>(null);
  const [diffData, setDiffData] = useState<{ before: string; after: string } | null>(null);
  const [editorText, setEditorText] = useState('');
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);
  const [listCollapsed, setListCollapsed] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const openTab = useCallback(async (path: string, mode: 'diff' | 'edit') => {
    const existing = tabs.find(t => t.path === path && t.mode === mode);
    if (existing) {
      setActiveTab(existing);
      return;
    }
    const tab: OpenTab = { path, mode };
    setTabs(prev => [...prev, tab]);
    setActiveTab(tab);
    setLoading(true);
    try {
      if (mode === 'diff') {
        const d = await getFileDiff(sessionId, path);
        setDiffData({ before: d.before ?? '', after: d.after ?? '' });
        setEditorText('');
      } else {
        const content = await getFileContent(sessionId, path);
        setEditorText(content);
        setDirty(false);
        setDiffData(null);
      }
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [sessionId, tabs]);

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
    if (!activeTab || activeTab.mode !== 'edit' || !dirty || saving) return;
    setSaving(true);
    setSaveError(null);
    try {
      await saveFileContent(sessionId, activeTab.path, editorText);
      setDirty(false);
      onChangesRefresh();
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  }, [activeTab, dirty, editorText, saving, sessionId, onChangesRefresh]);

  // Ctrl+S 保存
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

  const totalAdd = useMemo(() => changes.reduce((s, c) => s + (c.additions || 0), 0), [changes]);
  const totalDel = useMemo(() => changes.reduce((s, c) => s + (c.deletions || 0), 0), [changes]);

  const fileName = (p: string) => p.slice(p.lastIndexOf('/') + 1);
  const languageOf = (p: string): string => {
    const ext = p.slice(p.lastIndexOf('.') + 1).toLowerCase();
    const map: Record<string, string> = {
      ts: 'typescript', tsx: 'typescript', js: 'javascript', jsx: 'javascript',
      java: 'java', py: 'python', go: 'go', rs: 'rust', c: 'c', h: 'c',
      cpp: 'cpp', cs: 'csharp', sh: 'shell', bash: 'shell', yml: 'yaml', yaml: 'yaml',
      json: 'json', xml: 'xml', md: 'markdown', sql: 'sql', html: 'html', css: 'css',
      toml: 'ini', ini: 'ini', properties: 'ini', gradle: 'groovy',
    };
    return map[ext] || 'plaintext';
  };

  return (
    <div className="w-[520px] flex-shrink-0 border-l flex flex-col h-full file-panel-bg" style={{ borderColor: 'rgba(0,184,148,0.1)' }}>
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
            {changes.map(c => (
              <div key={c.path} className="group/f flex items-center gap-2 px-3 py-1.5 hover:bg-tech-500/5">
                {c.changeType === 'CREATE'
                  ? <FilePlus className="w-3.5 h-3.5 text-green-400/70 flex-shrink-0" />
                  : <FileText className="w-3.5 h-3.5 text-ink-500 flex-shrink-0" />}
                <span className="text-[11px] font-mono text-ink-300 truncate flex-1" title={c.path}>
                  {fileName(c.path)} <span className="text-ink-600">{c.path.slice(0, c.path.lastIndexOf('/') + 1)}</span>
                </span>
                <span className="text-[10px] text-green-400/80 flex-shrink-0">+{c.additions || 0}</span>
                <span className="text-[10px] text-red-400/70 flex-shrink-0">-{c.deletions || 0}</span>
                <button onClick={() => openTab(c.path, 'diff')}
                  className="px-1.5 py-0.5 text-[10px] rounded text-tech-300 hover:text-tech-200 hover:bg-tech-500/10 border border-tech-500/20 flex-shrink-0"
                  title="查看变更差异">审查</button>
                <button onClick={() => openTab(c.path, 'edit')}
                  className="px-1.5 py-0.5 text-[10px] rounded text-ink-300 hover:text-ink-100 hover:bg-ink-700/40 border border-ink-700/60 flex-shrink-0"
                  title="查看完整文件内容">打开</button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 打开文件 tab 栏 */}
      {tabs.length > 0 && (
        <div className="flex items-center border-b overflow-x-auto scrollbar-thin flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          {tabs.map(tab => (
            <div key={`${tab.mode}:${tab.path}`}
              onClick={() => setActiveTab(tab)}
              className={`group/tab flex items-center gap-1 px-3 py-1.5 cursor-pointer border-r text-[11px] font-mono whitespace-nowrap ${
                activeTab === tab ? 'text-ink-100 bg-tech-500/10' : 'text-ink-500 hover:text-ink-300'
              }`} style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
              {tab.mode === 'diff' ? <FileDiff className="w-3 h-3" /> : <FileText className="w-3 h-3" />}
              <span>{fileName(tab.path)}</span>
              <button onClick={(e) => { e.stopPropagation(); closeTab(tab); }}
                className="p-0.5 text-ink-600 hover:text-red-400 rounded" aria-label="关闭标签"><X className="w-2.5 h-2.5" /></button>
            </div>
          ))}
        </div>
      )}

      {/* 编辑器区域 */}
      <div className="flex-1 min-h-0 flex flex-col relative">
        {loading ? (
          <div className="flex-1 flex items-center justify-center">
            <Loader2 className="w-4 h-4 text-tech-400 animate-spin" />
          </div>
        ) : activeTab == null ? (
          <div className="flex-1 flex items-center justify-center">
            <p className="text-xs text-ink-600">从变更列表中选择「审查」或「打开」</p>
          </div>
        ) : activeTab.mode === 'diff' ? (
          <div className="flex-1 min-h-0">
            <MonacoDiffEditor original={diffData?.before ?? ''} modified={diffData?.after ?? ''}
              language={languageOf(activeTab.path)} path={activeTab.path} />
          </div>
        ) : (
          <div className="flex-1 min-h-0 flex flex-col">
            <div className="flex items-center gap-2 px-3 py-1.5 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
              <span className="text-[11px] font-mono text-ink-500 truncate flex-1">{activeTab.path}</span>
              {dirty && <span className="text-[10px] text-amber-400 flex-shrink-0">● 未保存</span>}
              <button onClick={save} disabled={!dirty || saving}
                className={`flex items-center gap-1 px-2 py-0.5 text-[10px] rounded border transition-all flex-shrink-0 ${
                  dirty ? 'text-tech-300 hover:bg-tech-500/10 border-tech-500/30' : 'text-ink-600 border-ink-700/40'
                } disabled:opacity-40`} title="Ctrl+S 保存（写回工作空间）">
                {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}
                保存
              </button>
            </div>
            {saveError && (
              <div className="px-3 py-1 text-[10px] text-red-400/80 border-b" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>{saveError}</div>
            )}
            <div className="flex-1 min-h-0">
              <MonacoFileEditor value={editorText} language={languageOf(activeTab.path)}
                path={activeTab.path} onChange={(v) => { setEditorText(v ?? ''); setDirty(true); }} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
