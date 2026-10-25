'use client';
import { useState, useEffect, useCallback, useRef } from 'react';
import dynamic from 'next/dynamic';
import { useDispatch } from 'react-redux';
import { setPage } from '@/store';
import { Loader2, Play, Square, GitBranch, Terminal, Save, RefreshCw, Pencil, Undo2, ChevronRight, ChevronDown, ArrowLeft, Folder, FolderOpen, FileText } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import MarkdownReader from '@/components/ui/MarkdownReader';
import DevGitMenu from './DevGitMenu';
import DevQuickOpen from './DevQuickOpen';
import { isMarkdownPath, isProbablyBinary, monacoLangOf } from '@/lib/fileLanguage';
import {
  getDevWorkspace, startSandbox, stopSandbox, readFile, writeFile,
  listProjects, gitPull, buildProject, runProject,
  listFileEntries,
  type DevWorkspaceInfo, type GitProject, type FileEntry,
} from '@/lib/dev-workspace';
// 终端面板客户端专用（ssr:false）：@xterm/addon-fit UMD 顶层引用 self，SSR 预渲染会崩
const TerminalPanel = dynamic(() => import('@/components/ui/TerminalPanel'), { ssr: false });

// Monaco 懒加载（自托管 public/monaco，免 CDN）
const MonacoFileEditor = dynamic(() => import('./MonacoEditor'), { ssr: false });

/** 编辑器状态栏语言标签 */
const LANG_LABELS: Record<string, string> = {
  java: 'Java', typescript: 'TypeScript', javascript: 'JavaScript', jsx: 'JavaScript',
  python: 'Python', go: 'Go', rust: 'Rust', cpp: 'C++', c: 'C', csharp: 'C#',
  html: 'HTML', css: 'CSS', scss: 'SCSS', less: 'Less', json: 'JSON', yaml: 'YAML',
  xml: 'XML', markdown: 'Markdown', sql: 'SQL', shell: 'Shell', ini: 'INI',
  groovy: 'Groovy', kotlin: 'Kotlin', swift: 'Swift', php: 'PHP', ruby: 'Ruby',
  dart: 'Dart', 'objective-c': 'Objective-C', lua: 'Lua', perl: 'Perl', dockerfile: 'Dockerfile',
  plaintext: '纯文本',
};
const langLabelOf = (path: string): string => {
  const lang = monacoLangOf(path);
  return LANG_LABELS[lang] || lang;
};

/** 仓库地址 → host 展示（git@host:path / ssh:// / https:// 均归一） */
const hostOf = (url?: string): string => {
  if (!url) return '';
  try {
    const normalized = url.replace(/^git@([^:]+):/, 'https://$1/').replace(/^ssh:\/\/git@/, 'https://');
    return new URL(normalized).host;
  } catch {
    return url;
  }
};

/** 项目级凭证类型 → 中文描述 */
const credTypeDesc = (p: GitProject): string => {
  if (p.repoCredentialType === 'token') return '访问令牌 (Token)';
  if (p.repoCredentialType === 'ssh_key') return 'SSH 私钥';
  if (p.repoCredentialType === 'password') return '账号密码';
  return '未配置';
};

const cloneStatusDesc = (p: GitProject): string => {
  if (p.cloneStatus === 'ready') return '已拉取';
  if (p.cloneStatus === 'failed') return '拉取失败';
  if (p.cloneStatus === 'cloning') return '拉取中';
  return '未拉取';
};

export default function DevWorkspacePage() {
  const toast = useToast();
  const [wsInfo, setWsInfo] = useState<DevWorkspaceInfo | null>(null);
  const [projects, setProjects] = useState<GitProject[]>([]);
  const [loading, setLoading] = useState(true);
  const [sandboxLoading, setSandboxLoading] = useState(false);
  const [selectedProject, setSelectedProject] = useState<GitProject | null>(null);
  const [fileContent, setFileContent] = useState('');
  const [savedContent, setSavedContent] = useState('');
  const [viewMode, setViewMode] = useState<'preview' | 'edit'>('preview');
  const [dirty, setDirty] = useState(false);
  const [currentPath, setCurrentPath] = useState('');
  const [contentLoading, setContentLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [execLoading, setExecLoading] = useState(false);
  const [pulling, setPulling] = useState(false);
  const [termOpen, setTermOpen] = useState(false);
  const [termFullscreen, setTermFullscreen] = useState(false);
  // ===== IDEA 式编辑区增强 =====
  /** 快速检索面板（file=文件检索/最近文件，search=全局搜索） */
  const [quick, setQuick] = useState<'file' | 'search' | null>(null);
  /** 最近打开的文件（工作空间相对路径，最多 20 条） */
  const [recentFiles, setRecentFiles] = useState<string[]>([]);
  /** 光标位置（状态栏 行,列 展示） */
  const [cursor, setCursor] = useState({ line: 0, col: 0 });
  /** 打开文件后定位到指定行（全局搜索命中跳转；无值不定位） */
  const [gotoLine, setGotoLine] = useState<number | undefined>(undefined);
  /** 编辑器主题（持久化到 localStorage，默认格物暗色） */
  const [editorTheme, setEditorTheme] = useState<string>(() =>
    (typeof window !== 'undefined' && window.localStorage.getItem('dw-editor-theme')) || 'gewu-dark');
  // 文件树（IDEA 式工作台）：path → entries 懒加载缓存 + 展开集合
  const [tree, setTree] = useState<Record<string, FileEntry[]>>({});
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set());
  const [treeLoading, setTreeLoading] = useState(false);
  /** 紧凑折叠子层补载去重（紧凑目录折叠，IDEA 式） */
  const pendingLoads = useRef<Set<string>>(new Set());
  const [projectMenuOpen, setProjectMenuOpen] = useState(false);
  const dispatch = useDispatch();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ws, projs] = await Promise.all([getDevWorkspace(), listProjects()]);
      setWsInfo(ws);
      setProjects(projs || []);
      // 已选项目随列表刷新同步最新状态（拉取/克隆后 cloneStatus 变化）
      setSelectedProject(prev => (prev ? (projs || []).find(p => p.projectId === prev.projectId) || prev : prev));
    } catch (e) {
      toast('加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const handleStartSandbox = async () => {
    setSandboxLoading(true);
    try {
      await startSandbox();
      toast('开发沙箱已启动', 'success');
      load();
    } catch (e) {
      toast('启动失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSandboxLoading(false);
    }
  };

  const handleStopSandbox = async () => {
    try {
      await stopSandbox();
      toast('沙箱已停止', 'success');
      load();
    } catch (e) {
      toast('停止失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  // ==================== 项目拉取（项目配置驱动） ====================

  /**
   * 拉取项目代码：仓库未就绪时后端按项目配置的 Git 地址与凭证（token/账号密码/SSH 私钥）
   * 自动克隆；已就绪则增量 pull。完成后刷新项目状态并加载文件树。
   */
  const handlePull = async (p: GitProject) => {
    if (pulling) return;
    setPulling(true);
    try {
      await gitPull(p.projectId);
      toast('代码拉取成功', 'success');
      const projs = await listProjects();
      setProjects(projs || []);
      const fresh = (projs || []).find(x => x.projectId === p.projectId) || p;
      setSelectedProject(fresh);
      if (fresh.cloneStatus === 'ready' && fresh.localPath) {
        setTree({});
        setExpandedDirs(new Set());
        loadEntries(fresh.localPath);
      }
    } catch (e) {
      toast('拉取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      // 失败也要刷新状态（后端已把 cloneStatus 落库为 failed + 错误摘要）
      try {
        const projs = await listProjects();
        setProjects(projs || []);
        setSelectedProject(prev => (prev ? (projs || []).find(x => x.projectId === prev.projectId) || prev : prev));
      } catch { /* 刷新失败不覆盖原始报错 */ }
    } finally {
      setPulling(false);
    }
  };

  // ==================== 文件树（IDEA 式工作台） ====================

  /** 选中项目 → 就绪即加载根目录树；未拉取/失败自动按项目配置拉取 */
  const openProject = (p: GitProject) => {
    setSelectedProject(p);
    setTree({});
    setExpandedDirs(new Set());
    setCurrentPath('');
    setFileContent('');
    setSavedContent('');
    setViewMode('preview');
    setDirty(false);
    if (p.cloneStatus === 'ready' && p.localPath) {
      loadEntries(p.localPath);
    } else if (p.cloneStatus !== 'cloning') {
      handlePull(p);
    }
  };

  const loadEntries = async (path: string) => {
    setTreeLoading(true);
    try {
      const entries = await listFileEntries(path);
      setTree(prev => ({ ...prev, [path]: entries }));
    } catch (e) {
      toast('目录加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      pendingLoads.current.delete(path);
      setTreeLoading(false);
    }
  };

  /** 紧凑目录折叠（IDEA 式）：仅含单一子目录（无文件）的目录链合并为单节点展示。
   *  数据不变——tree 仍按真实路径存条目；折叠只影响渲染标签与节点定位。
   *  单链子目录由下方 effect 自动补载，保证链路完整可显示。 */
  const compactOf = (dirPath: string, rootName: string): { label: string; nodePath: string } => {
    let label = rootName;
    let cur = dirPath;
    // 最多向下收敛 32 层，防脏数据死循环
    for (let i = 0; i < 32; i++) {
      const es = tree[cur];
      if (!es || es.length !== 1 || !es[0].dir) break;
      const child = `${cur}/${es[0].name}`;
      const childEs = tree[child];
      if (!childEs) break; // 子层未加载（effect 会自动补载）
      label += '/' + es[0].name;
      cur = child;
    }
    return { label, nodePath: cur };
  };

  // 紧凑折叠补载：单子目录链的子层未加载时自动拉取（去重防抖）
  useEffect(() => {
    if (!selectedProject) return;
    Object.keys(tree).forEach(p => {
      const es = tree[p];
      if (es.length === 1 && es[0].dir) {
        const child = `${p}/${es[0].name}`;
        if (!tree[child] && !pendingLoads.current.has(child) && child.startsWith(selectedProject.localPath)) {
          pendingLoads.current.add(child);
          loadEntries(child);
        }
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tree, selectedProject]);

  /** 目录展开/折叠；展开时懒加载子目录 */
  const toggleDir = (path: string) => {
    const next = new Set(expandedDirs);
    if (next.has(path)) {
      next.delete(path);
    } else {
      next.add(path);
      if (!tree[path]) {
        loadEntries(path);
      }
    }
    setExpandedDirs(next);
  };

  const refreshTree = () => {
    if (!selectedProject?.localPath) return;
    const paths = [selectedProject.localPath, ...Array.from(expandedDirs)];
    setTree({});
    paths.forEach(p => loadEntries(p));
  };

  /** 递归渲染树节点（相对路径 = 卷内文件路径；单链目录按 IDEA 式折叠为单节点） */
  const renderTreeNode = (entry: FileEntry, parentPath: string, depth: number) => {
    const absPath = parentPath ? `${parentPath}/${entry.name}` : entry.name;
    const isDir = entry.dir;
    // 折叠链：仅含单一子目录的目录向下合并，标签显示完整链（如 com/hx/nine/eleven/socket）
    const { label, nodePath } = isDir ? compactOf(absPath, entry.name) : { label: entry.name, nodePath: absPath };
    const isExpanded = expandedDirs.has(nodePath);
    return (
      <div key={nodePath}>
        <button
          onClick={() => (isDir ? toggleDir(nodePath) : handleReadFile(absPath))}
          className={`w-full text-left flex items-center gap-1.5 px-2 py-1.5 rounded text-xs transition-all ${
            currentPath === absPath || (isDir && currentPath.startsWith(nodePath + '/'))
              ? 'bg-tech-500/15 text-tech-400'
              : 'text-ink-300 hover:bg-tech-500/5'
          }`}
          style={{ paddingLeft: 8 + depth * 14 }}
          title={nodePath}
        >
          {isDir ? (
            <>
              {isExpanded ? <ChevronDown className="w-3 h-3 flex-shrink-0 text-ink-500" /> : <ChevronRight className="w-3 h-3 flex-shrink-0 text-ink-500" />}
              {isExpanded ? <FolderOpen className="w-3.5 h-3.5 flex-shrink-0 text-gold-400" /> : <Folder className="w-3.5 h-3.5 flex-shrink-0 text-gold-400" />}
            </>
          ) : (
            <FileText className="w-3.5 h-3.5 flex-shrink-0 text-ink-500 ml-[18px]" />
          )}
          <span className="truncate">{label}</span>
        </button>
        {isDir && isExpanded && (tree[nodePath] || []).map(child => renderTreeNode(child, nodePath, depth + 1))}
        {isDir && isExpanded && treeLoading && !tree[nodePath] && (
          <div className="px-2 py-1 text-[10px] text-ink-600" style={{ paddingLeft: 20 + depth * 14 }}>加载中…</div>
        )}
      </div>
    );
  };

  const handleReadFile = async (path: string, line?: number) => {
    setCurrentPath(path);
    setGotoLine(line);
    setCursor({ line: 0, col: 0 });
    setContentLoading(true);
    setViewMode('preview');
    setDirty(false);
    try {
      const content = await readFile(path);
      setFileContent(content);
      setSavedContent(content);
      setRecentFiles(prev => [path, ...prev.filter(p => p !== path)].slice(0, 20));
    } catch (e) {
      toast('读取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setFileContent('');
      setSavedContent('');
    } finally {
      setContentLoading(false);
    }
  };

  /** 快速检索/搜索命中打开（入参为仓库内相对路径，拼接工作空间前缀） */
  const openRepoFile = (repoRel: string, line?: number) => {
    if (!selectedProject?.localPath) return;
    setQuick(null);
    handleReadFile(`${selectedProject.localPath}/${repoRel}`, line);
  };

  const handleSaveFile = async () => {
    if (!currentPath || viewMode !== 'edit') return;
    setSaving(true);
    try {
      await writeFile(currentPath, fileContent);
      setSavedContent(fileContent);
      setDirty(false);
      toast('已保存', 'success');
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  /** 退出编辑：有未保存修改先确认，放弃则还原到最近保存内容 */
  const exitEdit = () => {
    if (dirty && !confirm('有未保存的修改，确定放弃并退出编辑？')) return;
    setFileContent(savedContent);
    setDirty(false);
    setViewMode('preview');
  };

  // 编辑区快捷键：Ctrl+S 保存（编辑态）；IDEA 式导航——Ctrl+Shift+N/Ctrl+P 文件检索、
  // Ctrl+E 最近文件、Ctrl+Shift+F 全局搜索（检索面板打开时让位给面板自身的键处理）
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') {
        e.preventDefault();
        if (viewMode === 'edit') handleSaveFile();
        return;
      }
      if (!(e.ctrlKey || e.metaKey) || e.altKey || quick) return;
      if (!selectedProject || selectedProject.cloneStatus !== 'ready') return;
      const k = e.key.toLowerCase();
      if ((k === 'n' && e.shiftKey) || (k === 'p' && !e.shiftKey)) {
        e.preventDefault();
        setQuick('file');
      } else if (k === 'f' && e.shiftKey) {
        e.preventDefault();
        setQuick('search');
      } else if (k === 'e' && !e.shiftKey) {
        e.preventDefault();
        setQuick('file');
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewMode, currentPath, fileContent, saving, quick, selectedProject]);

  // 编辑器主题持久化
  useEffect(() => {
    if (typeof window !== 'undefined') window.localStorage.setItem('dw-editor-theme', editorTheme);
  }, [editorTheme]);

  /** Git 菜单操作完成后：刷新项目状态 + 按展开状态重载文件树 */
  const afterGitRefresh = async () => {
    await load();
    if (selectedProject?.localPath) refreshTree();
  };

  const handleBuild = async (cmd: string) => {
    if (!selectedProject) return;
    setExecLoading(true);
    try {
      const result = await buildProject(selectedProject.projectId, cmd);
      if (result.exitCode === 0) {
        toast(`构建完成: ${cmd}`, 'success');
      } else {
        const tail = (result.stderr || result.stdout || '').trim().split('\n').slice(-1)[0] || '';
        toast(`构建失败 (exit ${result.exitCode})${tail ? '：' + tail.slice(0, 120) : ''}`, 'error');
      }
    } catch (e) {
      toast('构建失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setExecLoading(false);
    }
  };

  const handleRun = async () => {
    if (!selectedProject) return;
    const cmd = prompt('运行命令:', 'mvn spring-boot:run');
    if (!cmd) return;
    setExecLoading(true);
    try {
      const r = await runProject(selectedProject.projectId, cmd);
      if (r.exitCode === 0) {
        toast(`运行完成: ${cmd}`, 'success');
      } else {
        const tail = (r.stderr || r.stdout || '').trim().split('\n').slice(-1)[0] || '';
        toast(`运行失败 (exit ${r.exitCode})${tail ? '：' + tail.slice(0, 120) : ''}`, 'error');
      }
    } catch (e) {
      toast('运行失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setExecLoading(false);
    }
  };

  if (loading) {
    return <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>;
  }

  const isRunning = wsInfo?.sandboxStatus === 'running';
  const projectReady = !!selectedProject && selectedProject.cloneStatus === 'ready';

  return (
    // 全屏工作台（隐藏侧边栏，页内返回按钮）：整页固定屏幕内不滚动，各面板独立滚动
    <div className="flex flex-col overflow-hidden p-3" style={{ height: '100vh', background: '#081211' }}>
      {/* 顶部导航栏：标题 + 文件路径 + Git/构建/运行/编辑操作 + 沙箱状态/终端/启停 */}
      <header className="flex items-center gap-2 mb-3 flex-shrink-0">
        <div className="flex items-center gap-2 min-w-0 flex-shrink-0">
          <button onClick={() => dispatch(setPage('dashboard'))}
            className="p-1.5 text-ink-400 hover:text-tech-400 hover:bg-nav-hover rounded-md transition-all flex-shrink-0"
            title="返回主页">
            <ArrowLeft className="w-4 h-4" />
          </button>
          <h1 className="text-sm font-semibold text-ink-50 flex items-center gap-1.5 leading-tight whitespace-nowrap">
            <GitBranch className="w-4 h-4 text-tech-400" />开发工作空间
          </h1>
        </div>

        {/* 文件路径（原编辑区左上角，随操作按钮一并上移） */}
        <div className="flex-1 min-w-0 flex items-center px-3 py-1.5 rounded-lg bg-ink-900/40 border border-tech-500/10">
          <span className="text-xs text-ink-300 font-mono truncate" title={currentPath || (selectedProject ? `${selectedProject.localPath}/` : undefined)}>
            {currentPath || (projectReady ? `${selectedProject.localPath}/（从左侧文件树选择文件）` : selectedProject ? '拉取代码后从文件树选择文件' : '未选择项目')}
          </span>
        </div>

        <div className="flex items-center gap-1.5 flex-shrink-0">
          {selectedProject && (projectReady ? (
            <>
              <DevGitMenu project={selectedProject} onRefresh={afterGitRefresh} />
              <button onClick={() => handleBuild('mvn compile')} disabled={execLoading} className="px-2 py-1.5 text-xs text-ink-300 hover:text-tech-400 border border-ink-700/60 rounded transition-all disabled:opacity-50">构建</button>
              <button onClick={handleRun} disabled={execLoading} className="px-2 py-1.5 text-xs text-ink-300 hover:text-tech-400 border border-ink-700/60 rounded transition-all disabled:opacity-50">运行</button>
            </>
          ) : (
            <button onClick={() => handlePull(selectedProject)} disabled={pulling || selectedProject.cloneStatus === 'cloning'}
              className="flex items-center gap-1 px-3 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50"
              title="按项目配置的 Git 地址与凭证拉取代码">
              {pulling || selectedProject.cloneStatus === 'cloning' ? <Loader2 className="w-3 h-3 animate-spin" /> : <RefreshCw className="w-3 h-3" />}
              {selectedProject.cloneStatus === 'failed' ? '重新拉取' : '拉取代码'}
            </button>
          ))}

          {/* 编辑操作组（编辑中：保存/退出；预览：编辑） */}
          {currentPath && (
            <div className="flex items-center gap-1.5">
              {viewMode === 'edit' && dirty && <span className="text-[10px] text-amber-400">● 未保存</span>}
              {viewMode === 'edit' ? (
                <>
                  <button onClick={handleSaveFile} disabled={saving || !dirty} className="flex items-center gap-1 px-2 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50">
                    {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}保存
                  </button>
                  <button onClick={exitEdit} className="flex items-center gap-1 px-2 py-1.5 text-xs text-ink-300 hover:text-ink-100 border border-ink-700/60 rounded transition-all" title="退出编辑（未保存修改将还原）">
                    <Undo2 className="w-3 h-3" />退出
                  </button>
                </>
              ) : (
                <button onClick={() => setViewMode('edit')} className="flex items-center gap-1 px-2 py-1.5 text-xs text-ink-300 hover:text-ink-100 border border-ink-700/60 rounded transition-all" title="编辑此文件（Ctrl+S 保存）">
                  <Pencil className="w-3 h-3" />编辑
                </button>
              )}
            </div>
          )}

          <div className="w-px h-5 bg-tech-500/15 mx-0.5" />

          <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs ${isRunning ? 'bg-green-500/10 text-green-400' : 'bg-ink-800 text-ink-500'}`}>
            <span className={`w-1.5 h-1.5 rounded-full ${isRunning ? 'bg-green-400 animate-pulse' : 'bg-ink-600'}`} />
            {wsInfo?.sandboxStatusDesc || '未创建'}
          </div>
          <button
            onClick={() => setTermOpen((v) => !v)}
            className={`flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-lg border transition-all ${
              termOpen
                ? 'border-tech-500/30 bg-tech-500/15 text-tech-400'
                : 'border-tech-500/20 text-ink-300 hover:text-tech-400 hover:bg-tech-500/10'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" /> 终端
          </button>
          {isRunning ? (
            <button
              onClick={handleStopSandbox}
              className="flex items-center gap-1.5 px-3 py-1.5 border border-cinnabar-500/20 text-cinnabar-400 text-xs rounded-lg hover:bg-cinnabar-500/10"
            >
              <Square className="w-3.5 h-3.5" />停止
            </button>
          ) : (
            <button onClick={handleStartSandbox} disabled={sandboxLoading} className="flex items-center gap-1.5 px-3 py-1.5 btn-primary text-white text-xs rounded-lg disabled:opacity-50">
              {sandboxLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}启动沙箱
            </button>
          )}
        </div>
      </header>

      <div className="flex gap-4 flex-1 min-h-0">
        {/* 左侧：项目 + 文件树 + 凭证（项目配置同步） */}
        <div className="w-72 flex-shrink-0 flex flex-col gap-3 min-h-0">
          {/* 注意：左列不可加 overflow-hidden——项目下拉为绝对定位，会被裁剪（用户实报）。
              glass-dark（backdrop-filter）使每个面板都是独立层叠上下文：下拉的 z-20 只在本面板内生效，
              DOM 靠后的文件树/凭证面板按文档序绘制会整层盖住下拉——菜单打开期间须临时提升本面板 z 值。
              全屏遮罩须放在 glass 面板之外：面板内 fixed 以 backdrop-filter 祖先为包含块，遮不到其他面板。 */}
          {projectMenuOpen && <div className="fixed inset-0 z-20" onClick={() => setProjectMenuOpen(false)} />}
          <div className={`glass-dark rounded-xl p-3 flex-shrink-0 relative ${projectMenuOpen ? 'z-30' : ''}`}>
            <div className="flex items-center justify-between mb-2 px-1">
              <span className="text-xs text-ink-400 font-medium">项目</span>
              <span className="text-[10px] text-ink-600">来自我的项目</span>
            </div>
            <button onClick={() => setProjectMenuOpen(v => !v)}
              className="w-full flex items-center justify-between gap-2 px-2.5 py-2 rounded-lg border border-tech-500/20 bg-ink-800/40 text-sm text-ink-100 hover:border-tech-500/30 transition-all">
              <span className="flex items-center gap-1.5 min-w-0">
                <GitBranch className="w-3.5 h-3.5 text-tech-400 flex-shrink-0" />
                <span className="truncate">{selectedProject ? selectedProject.projectName : (projects.length ? '选择项目…' : '暂无分配项目')}</span>
              </span>
              <ChevronDown className={`w-3.5 h-3.5 text-ink-500 flex-shrink-0 transition-transform ${projectMenuOpen ? 'rotate-180' : ''}`} />
            </button>
            {selectedProject && (
              <div className={`text-[10px] mt-1.5 px-2 ${selectedProject.cloneStatus === 'failed' ? 'text-cinnabar-400' : 'text-ink-600'}`}>
                {selectedProject.repoBranch} · {cloneStatusDesc(selectedProject)}
              </div>
            )}
            {projectMenuOpen && (
              <>
                <div className="absolute left-3 right-3 z-20 mt-1 glass-dark rounded-lg border border-tech-500/15 shadow-xl p-1 max-h-64 overflow-y-auto">
                  {projects.length === 0 ? (
                    <div className="text-center py-3 text-xs text-ink-500">暂无分配的项目，请联系管理员在项目管理中分发</div>
                  ) : projects.map(p => (
                    <div key={p.projectId}
                      onClick={() => { openProject(p); setProjectMenuOpen(false); }}
                      className={`group px-2 py-2 rounded text-sm cursor-pointer ${selectedProject?.projectId === p.projectId ? 'bg-tech-500/15 text-tech-400' : 'text-ink-300 hover:bg-tech-500/5'}`}>
                      <div className="flex items-center gap-1.5">
                        <GitBranch className="w-3.5 h-3.5 flex-shrink-0" />
                        <span className="truncate flex-1">{p.projectName}</span>
                      </div>
                      <div className={`text-[10px] ml-5 ${p.cloneStatus === 'failed' ? 'text-cinnabar-400' : 'text-ink-600'}`}>
                        {p.repoBranch} · {cloneStatusDesc(p)}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>

          <div className="glass-dark rounded-xl p-3 flex-1 min-h-0 flex flex-col">
            <div className="flex items-center justify-between mb-2 px-1 flex-shrink-0">
              <span className="text-xs text-ink-400 font-medium">文件树{selectedProject ? ` · ${selectedProject.projectName}` : ''}</span>
              {projectReady && (
                <button onClick={refreshTree} className="p-1 text-ink-400 hover:text-tech-400" title="刷新目录">
                  <RefreshCw className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
            <div className="flex-1 min-h-0 overflow-y-auto">
              {!selectedProject ? (
                <div className="text-center py-8 text-ink-500 text-xs">点击上方项目<br />展示文件目录</div>
              ) : pulling || selectedProject.cloneStatus === 'cloning' ? (
                <div className="text-center py-8 text-ink-500 text-xs flex flex-col items-center gap-2">
                  <Loader2 className="w-4 h-4 text-tech-400 animate-spin" />
                  正在按项目配置拉取代码…
                </div>
              ) : selectedProject.cloneStatus === 'failed' ? (
                <div className="p-2 rounded-lg bg-cinnabar-500/10 text-xs text-cinnabar-300">
                  <p className="font-medium mb-1">拉取失败</p>
                  <p className="text-[10px] text-ink-400 break-all whitespace-pre-wrap">{selectedProject.cloneError || '原因未知'}</p>
                  <p className="text-[10px] text-ink-500 mt-2">请检查项目管理中的仓库地址与凭证配置，然后点击顶部「重新拉取」重试。</p>
                </div>
              ) : !projectReady ? (
                <div className="text-center py-8 text-ink-500 text-xs">尚未拉取代码<br />选中项目后自动按项目配置拉取</div>
              ) : treeLoading && !tree[selectedProject.localPath] ? (
                <div className="text-center py-8"><Loader2 className="w-4 h-4 text-tech-400 animate-spin inline" /></div>
              ) : (tree[selectedProject.localPath] || []).length === 0 ? (
                <div className="text-center py-8 text-ink-500 text-xs">目录为空</div>
              ) : (
                (tree[selectedProject.localPath] || []).map(e => renderTreeNode(e, selectedProject.localPath, 0))
              )}
            </div>
          </div>

          {/* Git 凭证：直接同步展示项目中配置的仓库信息（只读，配置入口在项目管理/管理端） */}
          <div className="glass-dark rounded-xl p-3 flex-shrink-0">
            <div className="flex items-center justify-between mb-2 px-1">
              <span className="text-xs text-ink-400 font-medium">Git 凭证</span>
              <span className="text-[10px] text-ink-600">同步自项目配置</span>
            </div>
            {!selectedProject ? (
              <div className="text-center py-4 text-ink-500 text-xs">选择项目后展示</div>
            ) : (
              <div className="space-y-1.5 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-ink-500 flex-shrink-0">仓库地址</span>
                  <span className="text-ink-200 truncate" title={selectedProject.repoUrl || undefined}>
                    {hostOf(selectedProject.repoUrl) || '未配置'}
                  </span>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span className="text-ink-500 flex-shrink-0">分支</span>
                  <span className="text-ink-200">{selectedProject.repoBranch || 'main'}</span>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span className="text-ink-500 flex-shrink-0">认证方式</span>
                  <span className="text-ink-200">{credTypeDesc(selectedProject)}</span>
                </div>
                {selectedProject.repoCredentialType === 'password' && selectedProject.repoUsername && (
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-ink-500 flex-shrink-0">用户名</span>
                    <span className="text-ink-200 truncate" title={selectedProject.repoUsername}>{selectedProject.repoUsername}</span>
                  </div>
                )}
                <div className="flex items-center justify-between gap-2">
                  <span className="text-ink-500 flex-shrink-0">凭证状态</span>
                  <span className={selectedProject.hasRepoCredential ? 'text-green-400' : 'text-cinnabar-400'}>
                    {selectedProject.hasRepoCredential ? '已配置' : '未配置'}
                  </span>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* 中间：代码编辑器（IDEA 式：占满视口剩余高度，内容内部滚动；路径与操作按钮已上移顶部导航栏） */}
        <div className="flex-1 min-w-0 glass-dark rounded-xl p-4 pt-3 flex flex-col overflow-hidden">
          {contentLoading ? (
            <div className="flex items-center justify-center py-20"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>
          ) : viewMode === 'edit' ? (
            <div className="flex-1 min-h-0">
              <MonacoFileEditor
                value={fileContent}
                language={monacoLangOf(currentPath)}
                path={`dw-${currentPath}`}
                wordWrap="on"
                theme={editorTheme}
                gotoLine={gotoLine}
                onCursor={(line, col) => setCursor({ line, col })}
                onChange={(v) => { setFileContent(v ?? ''); setDirty(true); }}
              />
            </div>
          ) : isMarkdownPath(currentPath) && fileContent ? (
            <div className="flex-1 min-h-0 overflow-y-auto">
              <MarkdownReader content={fileContent} />
            </div>
          ) : fileContent && isProbablyBinary(fileContent) ? (
            <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
              <p className="text-sm">二进制文件不支持预览</p>
            </div>
          ) : fileContent ? (
            <div className="flex-1 min-h-0">
              <MonacoFileEditor
                value={fileContent}
                language={monacoLangOf(currentPath)}
                path={`dw-${currentPath}`}
                readOnly
                wordWrap="off"
                theme={editorTheme}
                gotoLine={gotoLine}
                onCursor={(line, col) => setCursor({ line, col })}
              />
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
              <p className="text-sm">{projectReady ? '从左侧文件树选择文件进行查看或编辑' : '选择项目后自动拉取代码，或点击顶部「拉取代码」'}</p>
              {projectReady && <p className="text-xs mt-2 text-ink-600">Ctrl+Shift+N 文件检索 · Ctrl+Shift+F 全局搜索 · Ctrl+E 最近文件</p>}
            </div>
          )}

          {/* 状态栏（IDEA 式）：语言 · 行列 · 编码缩进 · 主题切换 */}
          <div className="flex items-center justify-between px-2 py-1 mt-2 flex-shrink-0 border-t border-tech-500/10 text-[10px] text-ink-500">
            <div className="flex items-center gap-3 min-w-0">
              <span className="truncate">{currentPath ? langLabelOf(currentPath) : '—'}</span>
              {cursor.line > 0 && <span className="flex-shrink-0">行 {cursor.line}，列 {cursor.col}</span>}
            </div>
            <div className="flex items-center gap-3 flex-shrink-0">
              <span>UTF-8</span>
              <span>4 空格缩进</span>
              <select
                value={editorTheme}
                onChange={(e) => setEditorTheme(e.target.value)}
                className="bg-transparent outline-none cursor-pointer text-ink-400 hover:text-tech-400"
                title="代码显示主题">
                <option value="gewu-dark">格物暗色</option>
                <option value="vs-dark">VS Dark+</option>
                <option value="hc-black">高对比暗色</option>
                <option value="vs">浅色</option>
              </select>
            </div>
          </div>
        </div>

      </div>

      {/* 快速检索/全局搜索面板（IDEA 式 Ctrl+Shift+N / Ctrl+Shift+F / Ctrl+E） */}
      {quick && projectReady && selectedProject && (
        <DevQuickOpen
          project={selectedProject}
          mode={quick}
          recentPaths={recentFiles}
          onClose={() => setQuick(null)}
          onOpenFile={openRepoFile}
        />
      )}

      {/* 网页终端抽屉（设计文档 44，与"我的文件"同款）：默认隐藏，按钮开关 */}
      {termOpen && (
        <TerminalPanel
          visible={termOpen}
          onClose={() => setTermOpen(false)}
          fullscreen={termFullscreen}
          onToggleFullscreen={() => setTermFullscreen((v) => !v)}
        />
      )}
    </div>
  );
}
