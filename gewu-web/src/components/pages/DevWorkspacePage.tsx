'use client';
import { useState, useEffect, useCallback, useRef } from 'react';
import dynamic from 'next/dynamic';
import { useDispatch } from 'react-redux';
import { setPage } from '@/store';
import { Loader2, Play, Square, GitBranch, Terminal, Save, Upload, Plus, RefreshCw, Key, Pencil, Undo2, ChevronRight, ChevronDown, ArrowLeft, Folder, FolderOpen, FileText, Trash2 } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import MarkdownReader from '@/components/ui/MarkdownReader';
import { isMarkdownPath, isProbablyBinary, monacoLangOf } from '@/lib/fileLanguage';
import {
  getDevWorkspace, startSandbox, stopSandbox, readFile, writeFile,
  cloneRepo, listProjects, gitPull, gitPush, buildProject, runProject,
  listGitCredentials, addGitCredential, deleteGitCredential,
  listFileEntries, deleteProject,
  type DevWorkspaceInfo, type GitProject, type GitCredential, type FileEntry,
} from '@/lib/dev-workspace';
// 终端面板客户端专用（ssr:false）：@xterm/addon-fit UMD 顶层引用 self，SSR 预渲染会崩
const TerminalPanel = dynamic(() => import('@/components/ui/TerminalPanel'), { ssr: false });

// Monaco 懒加载（自托管 public/monaco，免 CDN）
const MonacoFileEditor = dynamic(() => import('./MonacoEditor'), { ssr: false });

export default function DevWorkspacePage() {
  const toast = useToast();
  const [wsInfo, setWsInfo] = useState<DevWorkspaceInfo | null>(null);
  const [projects, setProjects] = useState<GitProject[]>([]);
  const [credentials, setCredentials] = useState<GitCredential[]>([]);
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
  const [termOpen, setTermOpen] = useState(false);
  const [termFullscreen, setTermFullscreen] = useState(false);
  // 文件树（IDEA 式工作台）：path → entries 懒加载缓存 + 展开集合
  const [tree, setTree] = useState<Record<string, FileEntry[]>>({});
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set());
  const [treeLoading, setTreeLoading] = useState(false);
  /** 紧凑折叠子层补载去重（紧凑目录折叠，IDEA 式） */
  const pendingLoads = useRef<Set<string>>(new Set());
  const [projectMenuOpen, setProjectMenuOpen] = useState(false);
  const dispatch = useDispatch();
  const [showClone, setShowClone] = useState(false);
  const [showCred, setShowCred] = useState(false);
  const [cloneUrl, setCloneUrl] = useState('');
  const [cloneName, setCloneName] = useState('');
  const [cloneBranch, setCloneBranch] = useState('main');
  const [credName, setCredName] = useState('');
  const [credType, setCredType] = useState('token');
  const [credValue, setCredValue] = useState('');
  const [credHost, setCredHost] = useState('github.com');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ws, projs, creds] = await Promise.all([
        getDevWorkspace(), listProjects(), listGitCredentials(),
      ]);
      setWsInfo(ws);
      setProjects(projs || []);
      setCredentials(creds || []);
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

  // ==================== 文件树（IDEA 式工作台） ====================

  /** 选中项目 → 加载其仓库根目录树 */
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

  const handleDeleteProject = async (p: GitProject) => {
    if (!confirm(`删除项目「${p.projectName}」？卷内仓库目录将一并清理。`)) return;
    try {
      await deleteProject(p.projectId);
      toast('项目已删除', 'success');
      if (selectedProject?.projectId === p.projectId) {
        setSelectedProject(null);
        setTree({});
        setExpandedDirs(new Set());
        setCurrentPath('');
        setFileContent('');
      }
      load();
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
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

  const handleReadFile = async (path: string) => {
    setCurrentPath(path);
    setContentLoading(true);
    setViewMode('preview');
    setDirty(false);
    try {
      const content = await readFile(path);
      setFileContent(content);
      setSavedContent(content);
    } catch (e) {
      toast('读取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setFileContent('');
      setSavedContent('');
    } finally {
      setContentLoading(false);
    }
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

  // Ctrl+S 保存（仅编辑态生效）
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        if (viewMode === 'edit') handleSaveFile();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewMode, currentPath, fileContent, saving]);

  const handleClone = async () => {
    if (!cloneUrl.trim() || !cloneName.trim()) return;
    setExecLoading(true);
    try {
      const project = await cloneRepo(cloneUrl, cloneName, cloneBranch);
      toast(`项目 ${cloneName} 克隆成功（分支 ${project.repoBranch}）`, 'success');
      setShowClone(false);
      setCloneUrl(''); setCloneName(''); setCloneBranch('main');
      load();
      setSelectedProject(project);
    } catch (e) {
      toast('克隆失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setExecLoading(false);
    }
  };

  const handleGitPull = async () => {
    if (!selectedProject) return;
    try {
      await gitPull(selectedProject.projectId);
      toast('Pull 成功', 'success');
      load();
    } catch (e) {
      toast('Pull 失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleGitPush = async () => {
    if (!selectedProject) return;
    const message = prompt('Commit message:');
    if (!message) return;
    try {
      await gitPush(selectedProject.projectId, message);
      toast('Push 完成', 'success');
    } catch (e) {
      toast('Push 失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
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

  const handleAddCred = async () => {
    if (!credName.trim() || !credValue.trim()) return;
    try {
      await addGitCredential(credName, credType, credValue, credHost);
      toast('凭证已添加', 'success');
      setShowCred(false);
      setCredName(''); setCredValue('');
      load();
    } catch (e) {
      toast('添加失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  if (loading) {
    return <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>;
  }

  const isRunning = wsInfo?.sandboxStatus === 'running';

  return (
    // 全屏工作台（隐藏侧边栏，页内返回按钮）：整页固定屏幕内不滚动，各面板独立滚动
    <div className="flex flex-col overflow-hidden p-3" style={{ height: '100vh', background: '#081211' }}>
      <header className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          <button onClick={() => dispatch(setPage('dashboard'))}
            className="p-1.5 text-ink-400 hover:text-tech-400 hover:bg-nav-hover rounded-md transition-all flex-shrink-0"
            title="返回主页">
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="min-w-0">
            <h1 className="text-sm font-semibold text-ink-50 flex items-center gap-1.5 leading-tight">
              <GitBranch className="w-4 h-4 text-tech-400" />开发工作空间
            </h1>
            <p className="text-ink-500 text-[10px] leading-tight">Git 项目管理 · 代码编辑 · 编译运行</p>
          </div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
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
        {/* 左侧：Git 项目 + 文件树 + 凭证 */}
        <div className="w-72 flex-shrink-0 flex flex-col gap-3 min-h-0">
          {/* 注意：左列不可加 overflow-hidden——项目下拉为绝对定位，会被裁剪（用户实报）。
              glass-dark（backdrop-filter）使每个面板都是独立层叠上下文：下拉的 z-20 只在本面板内生效，
              DOM 靠后的文件树/凭证面板按文档序绘制会整层盖住下拉——菜单打开期间须临时提升本面板 z 值。
              全屏遮罩须放在 glass 面板之外：面板内 fixed 以 backdrop-filter 祖先为包含块，遮不到其他面板。 */}
          {projectMenuOpen && <div className="fixed inset-0 z-20" onClick={() => setProjectMenuOpen(false)} />}
          <div className={`glass-dark rounded-xl p-3 flex-shrink-0 relative ${projectMenuOpen ? 'z-30' : ''}`}>
            <div className="flex items-center justify-between mb-2 px-1">
              <span className="text-xs text-ink-400 font-medium">项目</span>
              <button onClick={() => setShowClone(true)} className="p-1 text-ink-400 hover:text-tech-400" title="克隆仓库">
                <Plus className="w-4 h-4" />
              </button>
            </div>
            <button onClick={() => setProjectMenuOpen(v => !v)}
              className="w-full flex items-center justify-between gap-2 px-2.5 py-2 rounded-lg border border-tech-500/20 bg-ink-800/40 text-sm text-ink-100 hover:border-tech-500/30 transition-all">
              <span className="flex items-center gap-1.5 min-w-0">
                <GitBranch className="w-3.5 h-3.5 text-tech-400 flex-shrink-0" />
                <span className="truncate">{selectedProject ? selectedProject.projectName : (projects.length ? '选择项目…' : '暂无项目，点击 + 克隆')}</span>
              </span>
              <ChevronDown className={`w-3.5 h-3.5 text-ink-500 flex-shrink-0 transition-transform ${projectMenuOpen ? 'rotate-180' : ''}`} />
            </button>
            {selectedProject && (
              <div className={`text-[10px] mt-1.5 px-2 ${selectedProject.cloneStatus === 'failed' ? 'text-cinnabar-400' : 'text-ink-600'}`}>
                {selectedProject.repoBranch} · {selectedProject.cloneStatus === 'failed' ? '克隆失败' : selectedProject.cloneStatus}
              </div>
            )}
            {projectMenuOpen && (
              <>
                <div className="absolute left-3 right-3 z-20 mt-1 glass-dark rounded-lg border border-tech-500/15 shadow-xl p-1 max-h-64 overflow-y-auto">
                  {projects.length === 0 ? (
                    <div className="text-center py-3 text-xs text-ink-500">无项目，点击 + 克隆仓库</div>
                  ) : projects.map(p => (
                    <div key={p.projectId}
                      onClick={() => { openProject(p); setProjectMenuOpen(false); }}
                      className={`group px-2 py-2 rounded text-sm cursor-pointer ${selectedProject?.projectId === p.projectId ? 'bg-tech-500/15 text-tech-400' : 'text-ink-300 hover:bg-tech-500/5'}`}>
                      <div className="flex items-center gap-1.5">
                        <GitBranch className="w-3.5 h-3.5 flex-shrink-0" />
                        <span className="truncate flex-1">{p.projectName}</span>
                        <button onClick={(e) => { e.stopPropagation(); handleDeleteProject(p); }}
                          className="opacity-0 group-hover:opacity-100 text-ink-600 hover:text-cinnabar-400" title="删除项目">
                          <Trash2 className="w-3 h-3" />
                        </button>
                      </div>
                      <div className={`text-[10px] ml-5 ${p.cloneStatus === 'failed' ? 'text-cinnabar-400' : 'text-ink-600'}`}>
                        {p.repoBranch} · {p.cloneStatus === 'failed' ? '克隆失败' : p.cloneStatus}
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
              {selectedProject && selectedProject.cloneStatus === 'ready' && (
                <button onClick={refreshTree} className="p-1 text-ink-400 hover:text-tech-400" title="刷新目录">
                  <RefreshCw className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
            <div className="flex-1 min-h-0 overflow-y-auto">
              {!selectedProject ? (
                <div className="text-center py-8 text-ink-500 text-xs">点击上方项目<br />展示文件目录</div>
              ) : selectedProject.cloneStatus === 'failed' ? (
                <div className="p-2 rounded-lg bg-cinnabar-500/10 text-xs text-cinnabar-300">
                  <p className="font-medium mb-1">克隆失败</p>
                  <p className="text-[10px] text-ink-400 break-all whitespace-pre-wrap">{selectedProject.cloneError || '原因未知'}</p>
                  <p className="text-[10px] text-ink-500 mt-2">可先在「Git 凭证」中配置 Token，然后删除该项目重新克隆。</p>
                </div>
              ) : treeLoading && !tree[selectedProject.localPath] ? (
                <div className="text-center py-8"><Loader2 className="w-4 h-4 text-tech-400 animate-spin inline" /></div>
              ) : (tree[selectedProject.localPath] || []).length === 0 ? (
                <div className="text-center py-8 text-ink-500 text-xs">目录为空</div>
              ) : (
                (tree[selectedProject.localPath] || []).map(e => renderTreeNode(e, selectedProject.localPath, 0))
              )}
            </div>
          </div>

          <div className="glass-dark rounded-xl p-3 flex-shrink-0">
            <div className="flex items-center justify-between mb-2 px-1">
              <span className="text-xs text-ink-400 font-medium">Git 凭证</span>
              <button onClick={() => setShowCred(true)} className="p-1 text-ink-400 hover:text-tech-400" title="添加凭证">
                <Key className="w-4 h-4" />
              </button>
            </div>
            <div className="space-y-1 max-h-32 overflow-y-auto">
              {credentials.length === 0 ? (
                <div className="text-center py-4 text-ink-500 text-xs">无凭证</div>
              ) : credentials.map(c => (
                <div key={c.credentialId} className="flex items-center justify-between px-2 py-1.5 text-xs text-ink-300">
                  <div className="truncate">
                    <span className="text-ink-200">{c.credName}</span>
                    <span className="text-ink-600 ml-1">{c.credType}</span>
                  </div>
                  <button onClick={async () => { await deleteGitCredential(c.credentialId); load(); }} className="text-ink-600 hover:text-cinnabar-400">✕</button>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* 中间：代码编辑器（IDEA 式：占满视口剩余高度，内容内部滚动） */}
        <div className="flex-1 min-w-0 glass-dark rounded-xl p-4 flex flex-col overflow-hidden">
          <div className="flex items-center justify-between mb-3 pb-3 border-b border-tech-500/10 flex-shrink-0">
            <div className="flex items-center gap-2 min-w-0">
              <span className="text-xs text-ink-300 font-mono truncate" title={currentPath || undefined}>
                {currentPath || (selectedProject ? `${selectedProject.localPath}/（从左侧文件树选择文件）` : '未选择项目')}
              </span>
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              {selectedProject && selectedProject.cloneStatus === 'ready' && (
                <>
                  <button onClick={handleGitPull} className="flex items-center gap-1 px-2 py-1 text-xs text-ink-300 hover:text-tech-400"><RefreshCw className="w-3 h-3" />Pull</button>
                  <button onClick={handleGitPush} className="flex items-center gap-1 px-2 py-1 text-xs text-ink-300 hover:text-tech-400"><Upload className="w-3 h-3" />Push</button>
                  <button onClick={() => handleBuild('mvn compile')} className="px-2 py-1 text-xs text-ink-300 hover:text-tech-400">构建</button>
                  <button onClick={() => { const cmd = prompt('运行命令:', 'mvn spring-boot:run'); if (cmd) { runProject(selectedProject.projectId, cmd).then(r => { if (r.exitCode === 0) { toast(`运行完成: ${cmd}`, 'success'); } else { const tail = (r.stderr || r.stdout || '').trim().split('\n').slice(-1)[0] || ''; toast(`运行失败 (exit ${r.exitCode})${tail ? '：' + tail.slice(0, 120) : ''}`, 'error'); } }).catch(e => toast('运行失败: ' + (e instanceof Error ? e.message : String(e)), 'error')); } }} className="px-2 py-1 text-xs text-ink-300 hover:text-tech-400">运行</button>
                </>
              )}
              <div className="flex items-center gap-2">
                {viewMode === 'edit' && dirty && <span className="text-[10px] text-amber-400">● 未保存</span>}
                {viewMode === 'edit' ? (
                  <>
                    <button onClick={handleSaveFile} disabled={saving || !dirty} className="flex items-center gap-1 px-2 py-1 text-xs btn-primary text-white rounded disabled:opacity-50">
                      {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}保存
                    </button>
                    <button onClick={exitEdit} className="flex items-center gap-1 px-2 py-1 text-xs text-ink-300 hover:text-ink-100 border border-ink-700/60 rounded transition-all" title="退出编辑（未保存修改将还原）">
                      <Undo2 className="w-3 h-3" />退出
                    </button>
                  </>
                ) : (
                  <button onClick={() => setViewMode('edit')} className="flex items-center gap-1 px-2 py-1 text-xs text-ink-300 hover:text-ink-100 border border-ink-700/60 rounded transition-all" title="编辑此文件（Ctrl+S 保存）">
                    <Pencil className="w-3 h-3" />编辑
                  </button>
                )}
              </div>
            </div>
          </div>
          {contentLoading ? (
              <div className="flex items-center justify-center py-20"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>
            ) : viewMode === 'edit' ? (
              <div className="flex-1 min-h-0">
                <MonacoFileEditor
                  value={fileContent}
                  language={monacoLangOf(currentPath)}
                  path={`dw-${currentPath}`}
                  wordWrap="on"
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
                />
              </div>
            ) : (
              <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
                <p className="text-sm">{selectedProject ? '从左侧文件树选择文件进行查看或编辑' : '选择项目后从文件树打开文件'}</p>
              </div>
            )}
        </div>

      </div>

      {/* 网页终端抽屉（设计文档 44，与"我的文件"同款）：默认隐藏，按钮开关 */}
      {termOpen && (
        <TerminalPanel
          visible={termOpen}
          onClose={() => setTermOpen(false)}
          fullscreen={termFullscreen}
          onToggleFullscreen={() => setTermFullscreen((v) => !v)}
        />
      )}

      {/* 克隆仓库弹窗 */}
      {showClone && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowClone(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-semibold text-ink-50 mb-4">克隆 Git 仓库</h3>
            <div className="space-y-3">
              <div><label className="block text-xs text-ink-400 mb-1">仓库地址 *</label><input value={cloneUrl} onChange={e => setCloneUrl(e.target.value)} placeholder="https://github.com/user/repo.git" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">项目名称 *</label><input value={cloneName} onChange={e => setCloneName(e.target.value)} placeholder="my-project" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">分支</label><input value={cloneBranch} onChange={e => setCloneBranch(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setShowClone(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleClone} disabled={!cloneUrl.trim() || !cloneName.trim() || execLoading} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{execLoading && <Loader2 className="w-4 h-4 animate-spin" />}克隆</button>
            </div>
          </div>
        </div>
      )}

      {/* 添加凭证弹窗 */}
      {showCred && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowCred(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-semibold text-ink-50 mb-4">添加 Git 凭证</h3>
            <div className="space-y-3">
              <div><label className="block text-xs text-ink-400 mb-1">名称 *</label><input value={credName} onChange={e => setCredName(e.target.value)} placeholder="GitHub Token" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">类型</label>
                <select value={credType} onChange={e => setCredType(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none">
                  <option value="token">Personal Access Token</option>
                  <option value="ssh_key">SSH Key</option>
                </select>
              </div>
              <div><label className="block text-xs text-ink-400 mb-1">{credType === 'ssh_key' ? '私钥内容 *' : 'Token *'}</label><textarea value={credValue} onChange={e => setCredValue(e.target.value)} rows={4} placeholder={credType === 'ssh_key' ? '-----BEGIN OPENSSH PRIVATE KEY-----\n...' : 'ghp_xxxxxxxxxxxx'} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 font-mono outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">Git 服务地址</label><input value={credHost} onChange={e => setCredHost(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setShowCred(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleAddCred} disabled={!credName.trim() || !credValue.trim()} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50">添加</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
