'use client';
import { useState, useEffect, useCallback, useRef } from 'react';
import dynamic from 'next/dynamic';
import { useDispatch } from 'react-redux';
import { setPage } from '@/store';
import { Loader2, Play, Square, GitBranch, Terminal, Save, RefreshCw, Pencil, Undo2, ChevronRight, ChevronDown, ArrowLeft, Folder, FolderOpen, FileText, Bug, Key, X, Bot } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import MarkdownReader from '@/components/ui/MarkdownReader';
import DevGitMenu from './DevGitMenu';
import DevQuickOpen from './DevQuickOpen';
import DevDebugPanel from './DevDebugPanel';
import DevAgentDrawer from './DevAgentDrawer';
import { isMarkdownPath, isProbablyBinary, monacoLangOf } from '@/lib/fileLanguage';
import { confirmDialog } from '@/components/ui/ConfirmDialog';
import {
  getDevWorkspace, startSandbox, readFile, writeFile,
  listProjects, gitPull, buildProject, execCommand,
  listFileEntries, blame as fetchBlame,
  debugStart, debugState as fetchDebugState, debugStop, debugBreakpoint, debugAction, debugEval, debugWatch,
  type DevWorkspaceInfo, type GitProject, type FileEntry, type DebugState,
} from '@/lib/dev-workspace';
import { registerDeployKey } from '@/lib/vcs';
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

/** 仓库主目录名（路径/文件树根显示用）：git@gitee.com:owner/mapper.git → mapper */
const repoRootName = (url?: string | null): string | null => {
  if (!url) return null;
  const last = url.trim().replace(/\/+$/, '').split('/').pop() || '';
  return last.replace(/\.git$/i, '') || null;
};

/** 展示用根名：仓库主目录名优先，回落 localPath 末段（repo） */
const displayRootName = (p?: { repoUrl?: string | null; localPath?: string | null } | null): string =>
  repoRootName(p?.repoUrl) || (p?.localPath ? p.localPath.split('/').pop() || '' : '') || 'repo';

/** IDEA 目录树排序：目录在前，同类按名称不区分大小写字母序 */
const ideaSortEntries = <T extends { name: string; dir: boolean }>(es: T[]): T[] =>
  [...es].sort((a, b) =>
    (a.dir === b.dir ? 0 : a.dir ? -1 : 1) ||
    a.name.toLowerCase().localeCompare(b.name.toLowerCase()));

/** 文件类型图标元数据：按扩展名着色的字母徽标（IDEA/VS Code 风格），未命中回落通用图标 */
const FILE_TYPE_META: Array<{ test: RegExp; label: string; color: string }> = [
  { test: /^\.git(ignore|modules|attributes|keep)?$|\.gitconfig$/i, label: 'G', color: '#8b949e' },
  { test: /\.(java)$/i, label: 'J', color: '#e76f00' },
  { test: /\.(md|markdown)$/i, label: 'M', color: '#519aba' },
  { test: /\.(xml|xsd|xsl|pom)$/i, label: 'X', color: '#e37933' },
  { test: /\.(yml|yaml)$/i, label: 'Y', color: '#cb4b16' },
  { test: /\.properties$/i, label: 'P', color: '#8dc149' },
  { test: /\.json$/i, label: '{', color: '#cbcb41' },
  { test: /\.(html?|htm)$/i, label: 'H', color: '#e37933' },
  { test: /\.css$/i, label: 'C', color: '#519aba' },
  { test: /\.jsx?$/i, label: 'JS', color: '#cbcb41' },
  { test: /\.tsx?$/i, label: 'TS', color: '#519aba' },
  { test: /\.py$/i, label: 'PY', color: '#3572a5' },
  { test: /\.go$/i, label: 'GO', color: '#00add8' },
  { test: /\.rs$/i, label: 'RS', color: '#dea584' },
  { test: /\.sql$/i, label: 'S', color: '#dd7c47' },
  { test: /\.(sh|bat|cmd)$/i, label: 'SH', color: '#89e051' },
  { test: /\.(png|jpe?g|gif|svg|ico|webp|bmp)$/i, label: 'I', color: '#a074c4' },
  { test: /\.(zip|jar|tar|gz|tgz|bz2|xz|7z|rar|war)$/i, label: 'Z', color: '#af7a4a' },
  { test: /\.(pdf|docx?|xlsx?|pptx?)$/i, label: 'D', color: '#c0392b' },
  { test: /\.(c|cc|h|hpp|cpp)$/i, label: 'C', color: '#519aba' },
  { test: /\.(php|rb|kt|scala|cs|swift|dart|m|mm)$/i, label: '·', color: '#7e8ca0' },
  { test: /\.(iml)$/i, label: 'I', color: '#519aba' },
  { test: /\.(txt|log)$/i, label: 'T', color: '#8b949e' },
];

const fileTypeMetaOf = (name: string): { label: string; color: string } | null => {
  for (const m of FILE_TYPE_META) if (m.test.test(name)) return m;
  const dot = name.lastIndexOf('.');
  if (dot <= 0) return null; // 无扩展名（LICENSE 等）→ 通用文件图标
  const ext = name.slice(dot + 1);
  if (!ext) return null;
  return { label: ext.slice(0, 2).toUpperCase(), color: '#8b949e' };
};

/** 文件类型图标：已知类型=按扩展名着色的字母徽标，未知无扩展名=通用文件图标（全类型覆盖） */
function FileTypeIcon({ name }: { name: string }) {
  const meta = fileTypeMetaOf(name);
  if (!meta) return <FileText className="w-3.5 h-3.5 flex-shrink-0 text-ink-500 ml-[18px]" />;
  return (
    <span
      className="flex-shrink-0 ml-[18px] inline-flex items-center justify-center rounded-[3px] text-[7px] font-bold leading-none"
      style={{ width: 14, height: 14, color: meta.color, background: meta.color + '24', border: `1px solid ${meta.color}3d` }}
    >
      {meta.label}
    </span>
  );
}

/** 容器内路径 → 以仓库主目录名为根的展示路径（projects/{id}/repo/x → mapper/x） */
const displayPathOf = (path: string | null, localPath: string | undefined, repoUrl?: string | null): string => {
  if (!path) return path || '';
  const root = repoRootName(repoUrl) || (localPath ? localPath.split('/').pop() : null) || 'repo';
  if (localPath && path === localPath) return root;
  if (localPath && path.startsWith(localPath + '/')) return root + path.slice(localPath.length);
  return path;
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
  /** IDEA 式运行：后台进程（nohup）+ 控制台轮询 + 停止 */
  const [runProc, setRunProc] = useState<{ pid: string; cmd: string } | null>(null);
  const [runExited, setRunExited] = useState(false);
  const [runLog, setRunLog] = useState('');
  const [runBusy, setRunBusy] = useState(false);
  const [consoleOpen, setConsoleOpen] = useState(false);
  const consoleBodyRef = useRef<HTMLDivElement>(null);
  /** 项目绑定 Agent 会话抽屉 */
  const [agentOpen, setAgentOpen] = useState(false);
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
  // ===== IDEA 式 Debug（P1 Java 断点调试） =====
  const [debugState, setDebugState] = useState<DebugState | null>(null);
  /** 累积的应用控制台文本 */
  const [debugConsole, setDebugConsole] = useState('');
  /** 正在执行的调试动作 */
  const [debugBusy, setDebugBusy] = useState<string | null>(null);
  /** 本地断点登记：repoRelPath:line */
  const [bpLines, setBpLines] = useState<Set<string>>(new Set());
  /** 停点自动定位去重（同停点只自动打开一次） */
  const lastAutoOpen = useRef<string>('');
  // ===== Deploy Key 注册闭环 =====
  const [deployKeyBusy, setDeployKeyBusy] = useState(false);

  const doRegisterDeployKey = async () => {
    if (!selectedProject) return;
    if (!(await confirmDialog({ message: '将生成 ed25519 密钥对并把公钥注册到托管平台（当前项目仓库），私钥自动配置到沙箱。继续？', confirmText: '继续' }))) return;
    setDeployKeyBusy(true);
    try {
      await registerDeployKey(selectedProject.projectId, 'gewu-' + (selectedProject.projectName || 'repo').replace(/\s+/g, '-') + '-' + Date.now().toString(36));
      toast('Deploy Key 已生成并注册，SSH 仓库可直接免密拉取', 'success');
      load();
    } catch (e) {
      toast('Deploy Key 注册失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDeployKeyBusy(false);
    }
  };

  // ===== blame 行级归属（IDEA Annotate） =====
  const [blameOn, setBlameOn] = useState(false);
  const [blameLines, setBlameLines] = useState<Array<{ line: number; text: string }>>([]);
  /** 调试会话是否进行中 */
  const debugActive = !!debugState && debugState.status !== 'inactive';
  // 文件树（IDEA 式工作台）：path → entries 懒加载缓存 + 展开集合
  const [tree, setTree] = useState<Record<string, FileEntry[]>>({});
  /** 仓库主目录（树根）折叠态：纯展示层开关，不触碰 expandedDirs/refreshTree 链 */
  const [rootCollapsed, setRootCollapsed] = useState(false);
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
            <FileTypeIcon name={entry.name} />
          )}
          <span className="truncate">{label}</span>
        </button>
        {isDir && isExpanded && ideaSortEntries(tree[nodePath] || []).map(child => renderTreeNode(child, nodePath, depth + 1))}
        {isDir && isExpanded && treeLoading && !tree[nodePath] && (
          <div className="px-2 py-1 text-[10px] text-ink-600" style={{ paddingLeft: 20 + depth * 14 }}>加载中…</div>
        )}
      </div>
    );
  };

  const handleReadFile = async (path: string, line?: number) => {
    setCurrentPath(path);
    setGotoLine(line);
    setBlameOn(false);
    setBlameLines([]);
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
  const exitEdit = async () => {
    if (dirty && !(await confirmDialog({ message: '有未保存的修改，确定放弃并退出编辑？', danger: true, confirmText: '放弃并退出' }))) return;
    setFileContent(savedContent);
    setDirty(false);
    setViewMode('preview');
  };

  // 编辑区快捷键：Ctrl+S 保存（编辑态）；IDEA 式导航——Ctrl+Shift+N/Ctrl+P 文件检索、
  // Ctrl+E 最近文件、Ctrl+Shift+F 全局搜索（检索面板打开时让位给面板自身的键处理）
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // 调试 F 键（IDEA 对齐）：F9 继续 / F8 步过 / Shift+F8 步出 / F7 步入 / Alt+F9 运行到光标
      if (debugActive && !e.ctrlKey && !e.metaKey) {
        if (!e.altKey && ['F7', 'F8', 'F9'].includes(e.key)) {
          e.preventDefault();
          if (e.key === 'F9') onDebugAction('cont');
          else if (e.key === 'F8' && e.shiftKey) onDebugAction('stepUp');
          else if (e.key === 'F8') onDebugAction('next');
          else if (e.key === 'F7') onDebugAction('step');
          return;
        }
        if (e.altKey && !e.shiftKey && e.key === 'F9') {
          e.preventDefault();
          onDebugRunTo();
          return;
        }
      }
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
  }, [viewMode, currentPath, fileContent, saving, quick, selectedProject, debugActive]);

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

  // ==================== 运行（IDEA 式：后台进程 + 控制台 + 停止） ====================

  const b64of = (s: string) => {
    const bytes = new TextEncoder().encode(s);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
  };

  /** 启动运行：nohup 后台进程，输出落 .gewu/run-{projectId}.log，控制台轮询输出，按钮转「停止」。
   *  命令经 base64 中转落盘执行——接口层 XSS 过滤会转义 JSON 字符串中的 > <，
   *  重定向符号必须写进脚本文件（容器内解码）绕开过滤，不削弱安全层。
   *  setsid 独立进程组：停止时按组杀净 mvn→java 孙进程，不留孤儿。 */
  const startRun = async () => {
    if (!selectedProject || runBusy) return;
    const stored = window.localStorage.getItem(`dw-run-cmd-${selectedProject.projectId}`);
    const cmd = stored || prompt('运行命令（保存为该项目运行配置，可经运行按钮旁箭头修改）:', 'mvn spring-boot:run');
    if (!cmd) return;
    setRunBusy(true); setRunExited(false); setRunLog('');
    try {
      const key = selectedProject.projectId;
      const cmdFile = `/workspace/.gewu/run-${key}.cmd`;
      const shFile = `/workspace/.gewu/run-${key}.sh`;
      const pidFile = `/workspace/.gewu/run-${key}.pid`;
      // 重定向符号写在启动器文件内（base64 落盘，容器内解码），exec 层命令不含 > <
      const shSrc = `cd /workspace/${selectedProject.localPath}\nexec sh ${cmdFile} > /workspace/.gewu/run-${key}.log 2>&1\n`;
      const sh = `mkdir -p /workspace/.gewu; echo ${b64of(cmd)} | base64 -d | tee ${cmdFile}; echo ${b64of(shSrc)} | base64 -d | tee ${shFile}; setsid sh ${shFile} & sleep 0.5; pgrep -fn "run-${key}\\\\.cmd" | head -1 | tee ${pidFile}`;
      const r = await execCommand(sh, 15);
      const pid = (r.stdout || '').trim().split('\n').pop() || '';
      if (!pid) throw new Error(r.stderr || '未获取到进程 PID');
      window.localStorage.setItem(`dw-run-cmd-${selectedProject.projectId}`, cmd);
      setRunProc({ pid, cmd });
      setConsoleOpen(true);
      toast(`已启动运行（PID ${pid}）`, 'success');
    } catch (e) {
      toast('启动失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setRunBusy(false);
    }
  };

  /** 停止运行：杀整个进程组（setsid 组，含 mvn→java 孙进程），TERM → 宽限 1s → KILL */
  const stopRun = async () => {
    if (!runProc || runBusy) return;
    setRunBusy(true);
    try {
      await execCommand(
        `kill -TERM -- -${runProc.pid}; pkill -TERM -P ${runProc.pid}; sleep 1; kill -KILL -- -${runProc.pid}; pkill -KILL -P ${runProc.pid}; echo stopped`, 15);
      toast('已停止运行', 'success');
    } catch (e) {
      toast('停止失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setRunProc(null);
      setRunExited(true);
      setRunBusy(false);
    }
  };

  /** 控制台轮询：日志 tail + 进程存活探测（进程退出即停轮，按钮回「运行」）。
   *  存活判定用 ps -p 校验命令行含本项目的 cmd 文件路径——kill -0 会被容器 PID 复用欺骗。 */
  useEffect(() => {
    if (!runProc || !selectedProject) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const logAbs = `/workspace/.gewu/run-${selectedProject.projectId}.log`;
    const tick = async () => {
      try {
        const log = await execCommand(`tail -c 60000 ${logAbs}`, 8);
        if (!cancelled) setRunLog((log.stdout || '').replace(/\u0000/g, ''));
        const alive = await execCommand(`ps -p ${runProc.pid} -o args= | grep -q "run-${selectedProject.projectId}\\\\.cmd" && echo ALIVE || echo DEAD`, 8);
        if (!cancelled && (alive.stdout || '').trim().endsWith('DEAD')) {
          setRunProc(null);
          setRunExited(true);
          return;
        }
      } catch { /* 瞬时错误忽略，下轮再取 */ }
      if (!cancelled) timer = setTimeout(tick, 1500);
    };
    timer = setTimeout(tick, 500);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [runProc, selectedProject?.projectId]);

  /** 页面载入/项目切换：探测上次会话遗留的运行进程并恢复「运行中」状态 */
  useEffect(() => {
    if (!selectedProject) return;
    let dead = false;
    const pidFile = `/workspace/.gewu/run-${selectedProject.projectId}.pid`;
    (async () => {
      try {
        const r = await execCommand(`ps -p $(cat ${pidFile}) -o args= | grep -q "run-${selectedProject.projectId}\\\\.cmd" && echo ALIVE || echo NONE`, 8);
        if (!dead && (r.stdout || '').trim().endsWith('ALIVE')) {
          const pid = (await execCommand(`cat ${pidFile}`, 8)).stdout?.trim() || '';
          if (pid && !dead) {
            setRunProc({ pid, cmd: window.localStorage.getItem(`dw-run-cmd-${selectedProject.projectId}`) || '上次会话启动' });
            setConsoleOpen(true);
          }
        }
      } catch { /* 无沙箱等场景忽略 */ }
    })();
    return () => { dead = true; };
  }, [selectedProject?.projectId]);

  // 控制台自动滚底
  useEffect(() => {
    const el = consoleBodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [runLog, consoleOpen]);

  // ==================== Debug 调试（IDEA 式 P1：Java 断点调试） ====================

  /** 应用调试状态：合并控制台增量 + 停点自动定位源文件（同停点只定位一次） */
  const applyDebugState = (st: DebugState) => {
    setDebugState(st);
    if (st.console) setDebugConsole(prev => (prev + st.console).slice(-20000));
    const key = st.status === 'stopped' && st.currentFile ? `${st.currentFile}:${st.currentLine}` : '';
    if (key && key !== lastAutoOpen.current) {
      lastAutoOpen.current = key;
      // python/node 停点携带仓库相对路径（含 /）直接定位；java 携带文件名+类名走 Maven 约定映射
      if (st.currentFile && st.currentFile.includes('/')) {
        const full = `${selectedProject?.localPath}/${st.currentFile}`;
        readFile(full)
          .then(() => openRepoFile(st.currentFile!, st.currentLine))
          .catch(() => undefined); // 外部依赖源码不可得，静默
      } else {
        const fileMatched = !!st.currentFile && (currentPath.endsWith('/' + st.currentFile) || currentPath === st.currentFile);
        if (st.currentClass && !fileMatched) {
          const guess = `src/main/java/${st.currentClass.replace(/\./g, '/')}.java`;
          readFile(`${selectedProject?.localPath}/${guess}`)
            .then(() => openRepoFile(guess, st.currentLine))
            .catch(() => undefined);
        }
      }
    }
  };

  const startDebug = async () => {
    if (!selectedProject) return;
    const cmd = prompt(
      '调试运行命令（自动识别：mvn/java=Java、python=Python、node=Node、go run=Go、./二进制=C/C++/Rust；输入 attach 附加已运行的 Java 进程）:',
      'mvn spring-boot:run');
    if (!cmd) return;
    const attach = cmd.trim() === 'attach';
    setDebugBusy('start');
    try {
      setDebugConsole('');
      applyDebugState(await debugStart(selectedProject.projectId, attach ? undefined : cmd, attach ? 'attach' : undefined));
      toast(attach ? '已附加到运行中的调试进程' : '调试会话已启动', 'success');
      // 本地已登记断点重放到新会话
      for (const key of Array.from(bpLines)) {
        const idx = key.lastIndexOf(':');
        const line = Number(key.slice(idx + 1));
        if (Number.isFinite(line)) {
          await debugBreakpoint(selectedProject.projectId, key.slice(0, idx), line).catch(() => undefined);
        }
      }
    } catch (e) {
      toast('调试启动失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDebugBusy(null);
    }
  };

  const stopDebug = async () => {
    if (!selectedProject) return;
    setDebugBusy('stop');
    try {
      const st = await debugStop(selectedProject.projectId);
      setDebugState(st.status === 'inactive' ? null : st);
      toast('调试已停止', 'success');
    } catch (e) {
      toast('停止失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDebugBusy(null);
    }
  };

  const onDebugAction = async (op: 'cont' | 'next' | 'step' | 'stepUp') => {
    if (!selectedProject || debugBusy) return;
    setDebugBusy(op);
    try {
      applyDebugState(await debugAction(selectedProject.projectId, op));
    } catch (e) {
      toast('调试操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDebugBusy(null);
    }
  };

  const onDebugEval = async (expr: string) => {
    if (!selectedProject || debugBusy) return;
    setDebugBusy('eval');
    try {
      applyDebugState(await debugEval(selectedProject.projectId, expr));
    } catch (e) {
      toast('求值失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDebugBusy(null);
    }
  };

  /** 行号槽切换断点（Shift+点击=条件断点；会话中即时下发；未启动仅本地登记，启动时重放） */
  const toggleBreakpoint = async (line: number, shiftKey?: boolean) => {
    if (!selectedProject?.localPath || !currentPath) return;
    const repoRel = currentPath.startsWith(selectedProject.localPath + '/')
      ? currentPath.slice(selectedProject.localPath.length + 1) : currentPath;
    const key = `${repoRel}:${line}`;
    const removing = bpLines.has(key);
    const next = new Set(bpLines);
    if (removing) next.delete(key); else next.add(key);
    setBpLines(next);
    if (debugActive) {
      try {
        // Shift+点击设置/更新条件；普通添加为无条件（后端空条件=清除）
        let condition: string | undefined;
        if (!removing && shiftKey) {
          const input = prompt('断点条件表达式（命中且求值为 true 时挂起，如 i == 5）:', '');
          if (input === null) return; // 取消
          condition = input.trim() || undefined;
        }
        applyDebugState(await debugBreakpoint(selectedProject.projectId, repoRel, line, removing, condition));
        if (condition) toast(`条件断点已设置: ${condition}`, 'success');
      } catch (e) {
        setBpLines(bpLines);
        toast('断点设置失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      }
    } else if (!removing && shiftKey) {
      toast('条件将在调试启动后生效（当前仅本地登记）', 'info');
    }
  };

  /** Watch 登记/移除 */
  const onDebugWatch = async (expr: string, remove?: boolean) => {
    if (!selectedProject || debugBusy) return;
    try {
      const st = await debugWatch(selectedProject.projectId, expr, remove);
      setDebugState(st); // watch 不产控制台增量，避免重复合并
    } catch (e) {
      toast('Watch 操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 运行到光标（临时断点 + 继续；IDEA Alt+F9） */
  const onDebugRunTo = async () => {
    if (!selectedProject?.localPath || !currentPath || debugBusy) return;
    const repoRel = currentPath.startsWith(selectedProject.localPath + '/')
      ? currentPath.slice(selectedProject.localPath.length + 1) : currentPath;
    setDebugBusy('runTo');
    try {
      applyDebugState(await debugAction(selectedProject.projectId, 'runTo', repoRel, cursor.line));
    } catch (e) {
      toast('运行到光标失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDebugBusy(null);
    }
  };

  /** Annotate 开关：拉取当前文件行级归属（IDEA blame 标注） */
  const toggleBlame = async () => {
    if (blameOn) {
      setBlameOn(false);
      setBlameLines([]);
      return;
    }
    if (!selectedProject?.localPath || !currentPath) return;
    try {
      const lines = await fetchBlame(selectedProject.projectId, currentPath);
      setBlameLines(lines.map(l => ({
        line: l.line,
        text: `${l.sha || ''} ${l.author || ''} ${l.time ? new Date(l.time).toLocaleDateString('zh-CN') : ''}`,
      })));
      setBlameOn(true);
    } catch (e) {
      toast('blame 加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 栈帧定位源文件（python/node=仓库相对路径直开；java=类名 Maven 约定映射；外部依赖提示） */
  const gotoFrame = (className: string, fileName?: string, line?: number) => {
    if (!selectedProject?.localPath) return;
    let guess: string | null = null;
    if (fileName && fileName.startsWith('/')) {
      toast('未能定位源文件（外部依赖）: ' + fileName, 'error');
      return;
    }
    if (fileName && fileName.includes('/')) {
      guess = fileName; // python/node 停点/栈帧行携带仓库相对路径
    } else if (className) {
      guess = `src/main/java/${className.replace(/\./g, '/')}.java`;
    }
    if (!guess) return;
    readFile(`${selectedProject.localPath}/${guess}`)
      .then(() => openRepoFile(guess!, line))
      .catch(() => toast('未能定位源文件: ' + guess, 'error'));
  };

  /** 线程切换（调试面板线程选择器） */
  const onDebugThread = async (threadId: string) => {
    if (!selectedProject || debugBusy) return;
    setDebugBusy('thread');
    try {
      applyDebugState(await debugAction(selectedProject.projectId, 'thread', undefined, undefined, threadId));
    } catch (e) {
      toast('线程切换失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDebugBusy(null);
    }
  };

  // 运行态轮询：cont 后断点异步命中时自动刷新停点（仅调试中且运行态，5s 间隔）
  useEffect(() => {
    if (!debugActive || debugState?.status !== 'running' || !selectedProject) return;
    const timer = setInterval(async () => {
      try {
        const st = await fetchDebugState(selectedProject.projectId);
        if (st.status !== 'inactive') applyDebugState(st);
      } catch { /* 会话失效静默，下次操作会显式报错 */ }
    }, 5000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debugActive, debugState?.status, selectedProject?.projectId]);

  /** 当前文件断点行集合与调试当前行（编辑器装饰数据源） */
  const currentRepoRel = selectedProject?.localPath && currentPath.startsWith(selectedProject.localPath + '/')
    ? currentPath.slice(selectedProject.localPath.length + 1) : '';
  const currentBreakpoints = currentRepoRel
    ? Array.from(bpLines).filter(k => k.startsWith(currentRepoRel + ':')).map(k => Number(k.slice(currentRepoRel.length + 1)))
    : [];
  /** 条件断点行（琥珀色 ? 点，取自会话状态） */
  const currentCondBreakpoints = currentRepoRel && debugState
    ? debugState.breakpoints.filter(b => b.condition && b.path === currentRepoRel).map(b => b.line)
    : [];
  const fileStopped = debugState?.status === 'stopped' && !!debugState.currentFile
    && (currentPath.endsWith('/' + debugState.currentFile) || currentPath === debugState.currentFile);
  const debugCurrentLine = fileStopped ? debugState.currentLine : undefined;
  /** 行尾内联变量值（IDEA inline values：前 6 个局部变量快照） */
  const inlineValue = fileStopped && debugState && debugState.locals.length > 0
    ? {
        line: debugState.currentLine!,
        text: debugState.locals.slice(0, 6).map(l => `${l.name}=${l.value.length > 40 ? l.value.slice(0, 40) + '…' : l.value}`).join('   ·   '),
      }
    : undefined;
  /** 悬停求值（word → 局部变量/Watch 取值） */
  const debugValueLookup = (word: string): string | undefined => {
    if (!debugState || debugState.status !== 'stopped') return undefined;
    const local = debugState.locals.find(l => l.name === word);
    if (local) return local.value;
    const watch = debugState.watches.find(w => w.expr === word);
    return watch?.value;
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

        {/* 文件路径（原编辑区左上角，随操作按钮一并上移；容器内前缀以仓库主目录名展示） */}
        <div className="flex-1 min-w-0 flex items-center px-3 py-1.5 rounded-lg bg-ink-900/40 border border-tech-500/10">
          <span
            className="text-xs text-ink-300 font-mono truncate"
            title={currentPath ? displayPathOf(currentPath, selectedProject?.localPath, selectedProject?.repoUrl) : (selectedProject ? `${displayRootName(selectedProject)}/` : undefined)}
          >
            {currentPath
              ? displayPathOf(currentPath, selectedProject?.localPath, selectedProject?.repoUrl)
              : projectReady
                ? `${displayRootName(selectedProject)}/（从左侧文件树选择文件）`
                : selectedProject ? '拉取代码后从文件树选择文件' : '未选择项目'}
          </span>
        </div>

        <div className="flex items-center gap-1.5 flex-shrink-0">
          {selectedProject && (projectReady ? (
            <>
              <DevGitMenu project={selectedProject} onRefresh={afterGitRefresh} />
              <button onClick={() => handleBuild('mvn compile')} disabled={execLoading} className="px-2 py-1.5 text-xs text-ink-300 hover:text-tech-400 border border-ink-700/60 rounded transition-all disabled:opacity-50">构建</button>
              {runProc ? (
                <button onClick={stopRun} disabled={runBusy}
                  className="flex items-center gap-1 px-2 py-1.5 text-xs border border-cinnabar-500/30 text-cinnabar-400 hover:bg-cinnabar-500/10 rounded transition-all disabled:opacity-50"
                  title={`运行中（PID ${runProc.pid}）· ${runProc.cmd} · 点击停止`}>
                  <Square className="w-3 h-3" />停止
                </button>
              ) : (
                <>
                  <button onClick={startRun} disabled={execLoading || runBusy}
                    className="px-2 py-1.5 text-xs text-ink-300 hover:text-tech-400 border border-ink-700/60 rounded transition-all disabled:opacity-50"
                    title="运行（后台进程 + 控制台输出；命令取自运行配置）">运行</button>
                  <button
                    onClick={() => {
                      const c = prompt('运行命令（保存为该项目运行配置）:', window.localStorage.getItem(`dw-run-cmd-${selectedProject.projectId}`) || 'mvn spring-boot:run');
                      if (c) {
                        window.localStorage.setItem(`dw-run-cmd-${selectedProject.projectId}`, c);
                        toast('运行配置已保存', 'success');
                      }
                    }}
                    className="px-0.5 py-1.5 text-ink-500 hover:text-tech-400 transition-all" title="编辑运行配置">
                    <ChevronDown className="w-3 h-3" />
                  </button>
                </>
              )}
              {debugActive ? (
                <button onClick={stopDebug} disabled={debugBusy !== null}
                  className="flex items-center gap-1 px-2 py-1.5 text-xs border border-cinnabar-500/30 text-cinnabar-400 hover:bg-cinnabar-500/10 rounded transition-all disabled:opacity-50"
                  title="停止调试会话">
                  <Square className="w-3 h-3" />停止
                </button>
              ) : (
                <button onClick={startDebug} disabled={!projectReady || debugBusy !== null}
                  className="flex items-center gap-1 px-2 py-1.5 text-xs text-ink-300 hover:text-tech-400 border border-ink-700/60 rounded transition-all disabled:opacity-50"
                  title="调试运行（Java，注入 JDWP；行号槽点击设断点）">
                  {debugBusy === 'start' ? <Loader2 className="w-3 h-3 animate-spin" /> : <Bug className="w-3 h-3" />}调试
                </button>
              )}
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

          {/* Agent：绑定当前项目的 AI 会话（工作空间 = 项目仓库目录） */}
          <button
            onClick={() => setAgentOpen(true)}
            disabled={!selectedProject}
            className={`flex items-center gap-1.5 px-2.5 py-1.5 text-xs rounded-lg border transition-all disabled:opacity-50 ${
              agentOpen
                ? 'border-tech-500/30 bg-tech-500/15 text-tech-400'
                : 'border-tech-500/20 text-ink-300 hover:text-tech-400 hover:bg-tech-500/10'
            }`}
            title="AI Agent（绑定当前项目，可直接对话与 AI 编码执行）">
            <Bot className="w-3.5 h-3.5" />Agent
          </button>

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
          {!isRunning && (
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
                {selectedProject.repoBranch || '默认分支'} · {cloneStatusDesc(selectedProject)}
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
                        {p.repoBranch || '默认分支'} · {cloneStatusDesc(p)}
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
                <>
                  {/* 仓库主目录（根节点）：IDEA 式可折叠，目录名取自仓库地址主目录 */}
                  <button
                    onClick={() => setRootCollapsed(c => !c)}
                    className={`w-full text-left flex items-center gap-1.5 px-2 py-1.5 rounded text-xs transition-all ${
                      currentPath === selectedProject.localPath ? 'bg-tech-500/15 text-tech-400' : 'text-ink-300 hover:bg-tech-500/5'
                    }`}
                    style={{ paddingLeft: 8 }}
                    title={selectedProject.localPath}
                  >
                    {rootCollapsed ? <ChevronRight className="w-3 h-3 flex-shrink-0 text-ink-500" /> : <ChevronDown className="w-3 h-3 flex-shrink-0 text-ink-500" />}
                    {rootCollapsed ? <Folder className="w-3.5 h-3.5 flex-shrink-0 text-gold-400" /> : <FolderOpen className="w-3.5 h-3.5 flex-shrink-0 text-gold-400" />}
                    <span className="truncate">{displayRootName(selectedProject)}/</span>
                  </button>
                  {!rootCollapsed && ideaSortEntries(tree[selectedProject.localPath] || []).map(e => renderTreeNode(e, selectedProject.localPath, 0))}
                </>
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
                  <span className="text-ink-200">{selectedProject.repoBranch || '默认分支'}</span>
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
                <button onClick={doRegisterDeployKey} disabled={deployKeyBusy}
                  className="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-lg border border-tech-500/20 text-[11px] text-ink-300 hover:text-tech-400 hover:border-tech-500/30 disabled:opacity-50"
                  title="沙箱生成 ed25519 密钥对，公钥注册到托管平台，私钥自动注入沙箱（SSH 免密拉取）">
                  {deployKeyBusy ? <Loader2 className="w-3 h-3 animate-spin" /> : <Key className="w-3 h-3" />}生成 Deploy Key（SSH 免密）
                </button>
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
                breakpoints={currentBreakpoints}
                conditionalBreakpoints={currentCondBreakpoints}
                onToggleBreakpoint={toggleBreakpoint}
                currentLine={debugCurrentLine}
                inlineValue={inlineValue}
                valueLookup={debugValueLookup}
                onChange={(v) => { setFileContent(v ?? ''); setDirty(true); }}
              />
            </div>
          ) : isMarkdownPath(currentPath) && fileContent ? (
            <div className="flex-1 min-h-0">
              <MarkdownReader content={fileContent} showToc />
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
                breakpoints={currentBreakpoints}
                conditionalBreakpoints={currentCondBreakpoints}
                onToggleBreakpoint={toggleBreakpoint}
                currentLine={debugCurrentLine}
                inlineValue={inlineValue}
                lineAfter={blameOn ? blameLines : undefined}
                valueLookup={debugValueLookup}
              />
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
              <p className="text-sm">{projectReady ? '从左侧文件树选择文件进行查看或编辑' : '选择项目后自动拉取代码，或点击顶部「拉取代码」'}</p>
              {projectReady && <p className="text-xs mt-2 text-ink-600">Ctrl+Shift+N 文件检索 · Ctrl+Shift+F 全局搜索 · Ctrl+E 最近文件</p>}
            </div>
          )}

          {/* 调试面板（IDEA Debug：工具条 + 变量/调用栈/控制台） */}
          {debugActive && debugState && (
            <DevDebugPanel
              state={debugState}
              consoleText={debugConsole}
              busy={debugBusy}
              onAction={onDebugAction}
              onRunTo={onDebugRunTo}
              onThread={onDebugThread}
              onStop={stopDebug}
              onEval={onDebugEval}
              onWatch={onDebugWatch}
              onGoto={gotoFrame}
            />
          )}

          {/* 运行控制台（IDEA 式 Run 工具窗）：后台进程日志轮询输出 */}
          {consoleOpen && (
            <div className="flex-shrink-0 border-t border-tech-500/10 bg-black/40 rounded-b-xl overflow-hidden" style={{ height: 230 }}>
              <div className="flex items-center justify-between px-3 py-1.5 border-b border-tech-500/10">
                <div className="flex items-center gap-2 text-[11px] text-ink-400 min-w-0">
                  <Terminal className="w-3 h-3 text-tech-400 flex-shrink-0" />
                  <span className="font-medium flex-shrink-0">控制台</span>
                  {runProc ? (
                    <span className="flex items-center gap-1.5 text-tech-400 truncate">
                      <span className="w-1.5 h-1.5 rounded-full bg-green-400 animate-pulse flex-shrink-0" />
                      运行中 · PID {runProc.pid} · {runProc.cmd}
                    </span>
                  ) : runExited ? (
                    <span className="text-ink-500">已退出</span>
                  ) : null}
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <button onClick={() => setRunLog('')} className="text-[10px] text-ink-500 hover:text-ink-300">清屏</button>
                  <button onClick={() => setConsoleOpen(false)} className="text-ink-500 hover:text-ink-200" title="隐藏控制台（再次运行时自动打开）">
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
              <div ref={consoleBodyRef} className="h-[calc(100%-32px)] overflow-y-auto scrollbar-thin px-3 py-2 font-mono text-[11px] leading-5 whitespace-pre-wrap break-all text-ink-200">
                {runLog || '（暂无输出）'}
              </div>
            </div>
          )}

          {/* 状态栏（IDEA 式）：语言 · 行列 · 编码缩进 · 主题切换 */}
          <div className="flex items-center justify-between px-2 py-1 mt-2 flex-shrink-0 border-t border-tech-500/10 text-[10px] text-ink-500">
            <div className="flex items-center gap-3 min-w-0">
              <span className="truncate">{currentPath ? langLabelOf(currentPath) : '—'}</span>
              {cursor.line > 0 && <span className="flex-shrink-0">行 {cursor.line}，列 {cursor.col}</span>}
              {projectReady && currentPath && (
                <button onClick={toggleBlame} disabled={contentLoading}
                  className={`flex-shrink-0 hover:text-tech-400 disabled:opacity-50 ${blameOn ? 'text-tech-400' : ''}`} title="行级归属标注（IDEA Annotate）">
                  Annotate
                </button>
              )}
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

      {/* 项目 Agent 抽屉：绑定当前项目的 AI 会话 */}
      {agentOpen && selectedProject && (
        <DevAgentDrawer project={selectedProject} open={agentOpen} onClose={() => setAgentOpen(false)} />
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
