'use client';
import { useState, useEffect, useCallback, useRef, useMemo, type MouseEvent as ReactMouseEvent } from 'react';
import dynamic from 'next/dynamic';
import { useDispatch } from 'react-redux';
import { setPage } from '@/store';
import { Loader2, Play, Square, GitBranch, Terminal, Save, RefreshCw, Pencil, Undo2, ChevronRight, ChevronDown, ArrowLeft, Folder, FolderOpen, FileText, Bug, Key, X, Bot, BookOpen } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import MarkdownReader from '@/components/ui/MarkdownReader';
import DevEditorTabs from './DevEditorTabs';
import DevGitMenu from './DevGitMenu';
import DevQuickOpen, { type QuickMode, type QuickAction } from './DevQuickOpen';
import DevDebugPanel from './DevDebugPanel';
import DevAgentDrawer from './DevAgentDrawer';
import DevRunConfigDialog, { DEFAULT_BUILD_CMD, type DevRunConf } from './DevRunConfigDialog';
import Modal from '@/components/ui/Modal';
import DevCompareModal, { type DevCompareResult, type CompareItem } from './DevCompareModal';
import DevFileHistoryModal from './DevFileHistoryModal';
import DevEditorSettingsDialog from './DevEditorSettingsDialog';
import type { editor as MonacoEditorApi } from 'monaco-editor';
import type { Monaco } from '@monaco-editor/react';
import { isMarkdownPath, isProbablyBinary, monacoLangOf } from '@/lib/fileLanguage';
import { loadEditorSettings, saveEditorSettings, type EditorSettings } from '@/lib/editorSettings';
import { registerIdeaKeybindings, applySurround, SURROUND_TEMPLATES, type IdeaKeybindingHooks } from '@gewu/agent-ui/ui/monaco';
import { parseOutline, declPatternOf } from '@/lib/structureOutline';
import DevProjectReplaceDialog from './DevProjectReplaceDialog';

/** 运行配置读写（localStorage 按项目隔离；兼容旧版裸命令键） */
const loadRunConf = (pid: string): DevRunConf | null => {
  if (typeof window === 'undefined' || !pid) return null;
  try {
    const raw = window.localStorage.getItem(`dw-runconf-${pid}`);
    if (raw) return JSON.parse(raw) as DevRunConf;
  } catch { /* 损坏配置忽略 */ }
  const legacy = window.localStorage.getItem(`dw-run-cmd-${pid}`);
  return legacy ? { type: 'custom', cmd: legacy } : null;
};
const saveRunConf = (pid: string, conf: DevRunConf) => {
  if (typeof window === 'undefined' || !pid) return;
  window.localStorage.setItem(`dw-runconf-${pid}`, JSON.stringify(conf));
  window.localStorage.removeItem(`dw-run-cmd-${pid}`);
};

/** 运行命令组合（写 .cmd 文件执行；java 类型=编译→类路径→java 多行脚本） */
const composeRunCmd = (conf: DevRunConf, pid: string): string => {
  if (conf.type === 'maven') return `mvn ${(conf.goals || 'spring-boot:run').trim()}`;
  if (conf.type === 'custom') return (conf.cmd || '').trim();
  const jvm = (conf.jvmOpts || '').trim();
  const args = (conf.programArgs || '').trim();
  const javaLine = `java ${jvm} -cp "target/classes:$(cat /workspace/.gewu/cp-${pid}.txt)" ${(conf.mainClass || '').trim()} ${args}`.replace(/\s+/g, ' ');
  return ['mvn compile -DskipTests', `mvn -q dependency:build-classpath -Dmdep.outputFile=/workspace/.gewu/cp-${pid}.txt -Dmdep.includeScope=runtime`, javaLine].join('\n');
};

/** 调试命令组合（后端按前缀注入 JDWP，必须单行：java 直插 / mvn 走 jvmArguments；java 类型类路径由启动前准备步骤生成） */
const composeDebugCmd = (conf: DevRunConf, pid: string): string => {
  if (conf.type === 'maven') return `mvn ${(conf.goals || 'spring-boot:run').trim()}`;
  if (conf.type === 'custom') return (conf.cmd || '').trim();
  const jvm = (conf.jvmOpts || '').trim();
  const args = (conf.programArgs || '').trim();
  return `java ${jvm} -cp "target/classes:$(cat /workspace/.gewu/cp-${pid}.txt)" ${(conf.mainClass || '').trim()} ${args}`.replace(/\s+/g, ' ');
};
import { confirmDialog } from '@/components/ui/ConfirmDialog';
import {
  getDevWorkspace, startSandbox, readFile, writeFile,
  listProjects, gitPull, buildProject, execCommand, deleteFile,
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

/** 编辑器 Tab 数据模型（多 Tab 编辑 · IDEA 对齐；Phase 1 结构改造） */
interface EditorTab {
  path: string;           // 工作空间绝对路径（= Tab 唯一键）
  title: string;          // 文件名（Tab 显示）
  content: string;        // 编辑器当前内容
  savedContent: string;   // 最近保存内容（dirty 判定基准）
  dirty: boolean;
  pinned: boolean;
  preview: boolean;       // 预览 Tab（斜体；编辑/再点转正，IDEA 语义）
  kind: 'code' | 'md' | 'binary';
  reading: boolean;       // md 阅读视图开关（code/binary 恒 false）
  loading: boolean;
  gotoLine?: number;      // 打开定位行（全局搜索/调试停点跳转）
  eol: '\n' | '\r\n';     // 换行符（保存时保留，IDEA 状态栏可切）
}
const tabTitleOf = (path: string): string => path.split('/').pop() || path;
/** Tab 元数据投影（TabBar 渲染用） */
const tabMetaOf = (t: EditorTab) => ({ path: t.path, title: t.title, dirty: t.dirty, pinned: t.pinned, preview: t.preview });

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
  // ===== 多 Tab 编辑状态（IDEA 对齐：主组 + 可选副组分屏，2-Group 模型）=====
  const [mainTabs, setMainTabs] = useState<EditorTab[]>([]);
  const [mainActive, setMainActive] = useState('');
  const [secondTabs, setSecondTabs] = useState<EditorTab[]>([]);
  const [secondActive, setSecondActive] = useState('');
  const [splitDir, setSplitDir] = useState<'right' | 'down' | null>(null);
  const [focusedGroup, setFocusedGroup] = useState<'main' | 'second'>('main');
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
  /** 运行/调试配置对话框 */
  const [runConfOpen, setRunConfOpen] = useState(false);
  /** 未提交变更表（git status -z）：仓库相对路径 → M 修改 / A 新增 / D 删除（文件树变色数据源） */
  const [gitStatusMap, setGitStatusMap] = useState<Record<string, 'M' | 'A' | 'D'>>({});
  /** 编辑器实例（自定义右键菜单动作；主组/副组分屏各一） */
  const editorRef = useRef<MonacoEditorApi.IStandaloneCodeEditor | null>(null);
  const secondEditorRef = useRef<MonacoEditorApi.IStandaloneCodeEditor | null>(null);
  /** 聚焦组对应的编辑器实例（右键菜单动作作用于聚焦组） */
  const focusedEditor = () => (focusedGroup === 'second' ? secondEditorRef.current : editorRef.current);
  /** 编辑器右键菜单位置 */
  const [ctxMenu, setCtxMenu] = useState<{ x: number; y: number } | null>(null);
  /** 文件历史版本弹窗 */
  const [historyOpen, setHistoryOpen] = useState(false);
  /** 文件树右键菜单（目标节点 + 新建子菜单展开态） */
  const [treeMenu, setTreeMenu] = useState<{ x: number; y: number; path: string; name: string; isDir: boolean; sub?: boolean } | null>(null);
  /** 新建文件/目录：父目录 + 类型 + 名称输入 */
  const [newFileDialog, setNewFileDialog] = useState<{ parentPath: string; type: { label: string; kind: string; ext: string } } | null>(null);
  const [newFileName, setNewFileName] = useState('');
  /** 重命名：目标 + 新名称输入 */
  const [renameDialog, setRenameDialog] = useState<{ path: string; isDir: boolean; oldName: string } | null>(null);
  const [renameName, setRenameName] = useState('');
  /** 比较对象：右侧目标选择框（右键第一项 → 弹框选第二项） */
  const [comparePicker, setComparePicker] = useState<{ a: { path: string; isDir: boolean }; kind: 'dir' | 'file'; options: string[] } | null>(null);
  const [compareFilter, setCompareFilter] = useState('');
  const [compareResult, setCompareResult] = useState<DevCompareResult | null>(null);
  /** 树内部剪贴板（IDEA 式：剪切/复制路径状态） */
  const treeClipRef = useRef<{ path: string; isDir: boolean; mode: 'copy' | 'cut' } | null>(null);
  const [pulling, setPulling] = useState(false);
  const [termOpen, setTermOpen] = useState(false);
  const [termFullscreen, setTermFullscreen] = useState(false);
  // ===== IDEA 式编辑区增强 =====
  /** 快速检索面板（IDEA 统一弹层：file/search/everywhere/actions/locations/usages/symbols/structure/bookmarks） */
  const [quick, setQuick] = useState<QuickMode | null>(null);
  /** 弹层初始查询（跳转定义/查找用法注入光标词） */
  const [pendingQuery, setPendingQuery] = useState('');
  /** 最近打开的文件（工作空间相对路径，最多 20 条） */
  const [recentFiles, setRecentFiles] = useState<string[]>([]);
  /** 全项目替换（IDEA Ctrl+Shift+R） */
  const [replaceOpen, setReplaceOpen] = useState(false);
  /** 底部工具窗页签（控制台/问题/TODO，IDEA Run·Problems·TODO 工具窗） */
  const [bottomTab, setBottomTab] = useState<'console' | 'problems' | 'todo'>('console');
  /** TODO 清单（grep TODO|FIXME|XXX|HACK） */
  const [todos, setTodos] = useState<Array<{ path: string; line: number; text: string; todoKind: string }>>([]);
  /** 问题面板（Monaco markers 聚合：文件路径 → 问题列表） */
  const [problemsMap, setProblemsMap] = useState<Record<string, Array<{ line: number; sev: number; msg: string }>>>({});
  /** 使用处高亮词（Ctrl+Shift+F7；空数组=清除） */
  const [useHighlightWords, setUseHighlightWords] = useState<string[]>([]);
  /** 符号重命名（IDEA Shift+F6 词法近似：光标词全项目词边界替换） */
  const [renamePrompt, setRenamePrompt] = useState<null | { word: string }>(null);
  const [renameInput, setRenameInput] = useState('');
  /** Double Shift 检测（上一次 Shift 按下时刻） */
  const lastShiftAt = useRef(0);
  // ===== Phase 4：Gutter 变更标记 / 最大化编辑器 =====
  /** 未提交变更行标记（IDEA gutter：增=绿/改=蓝/删=红三角；git diff -U0 解析） */
  const [changeMarksMap, setChangeMarksMap] = useState<Record<string, { added: number[]; modified: number[]; removed: number[] }>>({});
  /** 最大化编辑器（IDEA Ctrl+Shift+F12：隐藏左栏/底部工具窗） */
  const [maximized, setMaximized] = useState(false);
  // ===== 跨文件导航栈（IDEA Ctrl+Alt+←/→ 后退/前进）=====
  const navStackRef = useRef<Array<{ path: string; line?: number }>>([]);
  const navIndexRef = useRef(-1);
  const navLockRef = useRef(false);
  const [, setNavVer] = useState(0); // 栈变化触发重渲染（按钮可用性）
  /** 导航入栈：同栈顶去重；后退/前进触发的打开经 navLockRef 静默 */
  const pushNav = useCallback((path: string, line?: number) => {
    if (navLockRef.current || !path) return;
    const stack = navStackRef.current;
    const top = stack[navIndexRef.current];
    if (top && top.path === path && (top.line ?? 0) === (line ?? 0)) return;
    stack.splice(navIndexRef.current + 1);
    stack.push({ path, line });
    if (stack.length > 100) stack.shift();
    navIndexRef.current = stack.length - 1;
    setNavVer(v => v + 1);
  }, []);

  // ===== 激活 Tab 派生（跟随聚焦组；兼容原单文件语义的只读投影）=====
  const focusedTabs = focusedGroup === 'second' ? secondTabs : mainTabs;
  const focusedActive = focusedGroup === 'second' ? secondActive : mainActive;
  const activeTab = focusedTabs.find(t => t.path === focusedActive) || null;
  const mainActiveTab = mainTabs.find(t => t.path === mainActive) || null;
  const secondActiveTab = secondTabs.find(t => t.path === secondActive) || null;
  const currentPath = activeTab?.path || '';
  const fileContent = activeTab?.content || '';
  const savedContent = activeTab?.savedContent || '';
  const dirty = !!activeTab?.dirty;
  const gotoLine = activeTab?.gotoLine;
  /** 显示模式：md 阅读视图=preview（MarkdownReader），其余=edit（IDEA 语义打开即可编辑） */
  const viewMode: 'edit' | 'preview' = activeTab ? (activeTab.kind === 'md' && activeTab.reading ? 'preview' : 'edit') : 'preview';

  // ===== Tab 操作工具（跨两组统一操作）=====
  /** 更新指定 Tab（按 path 定位所在组；patch 可传对象或基于原 Tab 的计算函数） */
  const patchTab = useCallback((path: string, patch: Partial<EditorTab> | ((t: EditorTab) => Partial<EditorTab>)) => {
    const apply = (arr: EditorTab[]): EditorTab[] => {
      const idx = arr.findIndex(t => t.path === path);
      if (idx < 0) return arr;
      const p = typeof patch === 'function' ? patch(arr[idx]) : patch;
      const next = [...arr];
      next[idx] = { ...next[idx], ...p };
      return next;
    };
    setMainTabs(prev => (prev.some(t => t.path === path) ? apply(prev) : prev));
    setSecondTabs(prev => (prev.some(t => t.path === path) ? apply(prev) : prev));
  }, []);

  /** 激活指定组的 Tab（切换=导航入栈，后退/前进时由 navLockRef 静默） */
  const activateIn = useCallback((group: 'main' | 'second', path: string) => {
    setFocusedGroup(group);
    if (group === 'second') setSecondActive(path); else setMainActive(path);
    pushNav(path);
  }, [pushNav]);

  /** 从组中移除 Tab 并修正激活项（右邻优先，否则左邻） */
  const removeTabIn = useCallback((group: 'main' | 'second', key: string) => {
    const fix = (arr: EditorTab[], active: string): [EditorTab[], string] => {
      const idx = arr.findIndex(t => t.path === key);
      if (idx < 0) return [arr, active];
      const next = arr.filter(t => t.path !== key);
      return [next, active === key ? (next[Math.min(idx, next.length - 1)]?.path || '') : active];
    };
    if (group === 'second') {
      setSecondTabs(prev => { const [n, a] = fix(prev, secondActive); setSecondActive(a); return n; });
    } else {
      setMainTabs(prev => { const [n, a] = fix(prev, mainActive); setMainActive(a); return n; });
    }
  }, [secondActive, mainActive]);

  /** 批量重命名 Tab 路径前缀（文件/目录重命名与移动时同步 Tab） */
  const renameTabsPath = useCallback((oldPrefix: string, newPrefix: string) => {
    const fix = (t: EditorTab): EditorTab => {
      const np = t.path === oldPrefix ? newPrefix
        : t.path.startsWith(oldPrefix + '/') ? newPrefix + t.path.slice(oldPrefix.length) : t.path;
      return np === t.path ? t : { ...t, path: np, title: tabTitleOf(np) };
    };
    const fixActive = (a: string) =>
      a === oldPrefix ? newPrefix : a.startsWith(oldPrefix + '/') ? newPrefix + a.slice(oldPrefix.length) : a;
    setMainTabs(prev => prev.map(fix));
    setMainActive(fixActive);
    setSecondTabs(prev => prev.map(fix));
    setSecondActive(fixActive);
  }, []);

  /** 关闭受影响路径（目录删除场景：前缀内全部 Tab）下线 */
  const closeTabsUnder = useCallback((prefix: string) => {
    const hit = (t: EditorTab) => t.path === prefix || t.path.startsWith(prefix + '/');
    const nextMain = mainTabs.filter(t => !hit(t));
    const nextSecond = secondTabs.filter(t => !hit(t));
    setMainTabs(nextMain);
    setMainActive(a => (nextMain.some(t => t.path === a) ? a : nextMain[nextMain.length - 1]?.path || ''));
    setSecondTabs(nextSecond);
    setSecondActive(a => (nextSecond.some(t => t.path === a) ? a : nextSecond[nextSecond.length - 1]?.path || ''));
  }, [mainTabs, secondTabs]);

  /** openInEditor 经 ref 互引（goNav 声明先于其定义，运行时回调已就绪） */
  const openInEditorRef = useRef<((path: string, line?: number) => Promise<void>) | null>(null);
  /** 后退/前进（Ctrl+Alt+←/→）：静默打开栈内目标 */
  const goNav = useCallback((dir: -1 | 1) => {
    const stack = navStackRef.current;
    const idx = navIndexRef.current + dir;
    if (idx < 0 || idx >= stack.length) return;
    navIndexRef.current = idx;
    setNavVer(v => v + 1);
    navLockRef.current = true;
    Promise.resolve(openInEditorRef.current?.(stack[idx].path, stack[idx].line))
      .finally(() => { navLockRef.current = false; });
  }, []);
  /** 光标位置（状态栏 行,列 展示，跟随聚焦组） */
  const [cursor, setCursor] = useState({ line: 0, col: 0 });
  /** 最近光标位置（IDEA Recent Locations；跨文件光标历史，Recent Locations 弹层数据源） */
  const [recentLocations, setRecentLocations] = useState<Array<{ path: string; line: number; col: number; text: string }>>([]);
  const lastLocRef = useRef<{ path: string; line: number }>({ path: '', line: 0 });
  /** 编辑器主题（持久化到 localStorage，默认格物暗色） */
  const [editorTheme, setEditorTheme] = useState<string>(() =>
    (typeof window !== 'undefined' && window.localStorage.getItem('dw-editor-theme')) || 'gewu-dark');
  // ===== Phase 2 编辑功能（IDEA 对齐）：设置 / 自动保存 / 多剪贴板 / 包裹 / 书签 / 跳行 =====
  /** 编辑器偏好（全局，Ctrl+Alt+S 设置面板） */
  const [editorSettings, setEditorSettingsState] = useState<EditorSettings>(() => loadEditorSettings());
  const setEditorSettings = (s: EditorSettings) => { setEditorSettingsState(s); saveEditorSettings(s); };
  const [settingsOpen, setSettingsOpen] = useState(false);
  /** 多剪贴板历史（IDEA Ctrl+Shift+V，≤100 条） */
  const [clipHistory, setClipHistory] = useState<string[]>([]);
  const [clipPickerOpen, setClipPickerOpen] = useState(false);
  /** Surround With 弹层（Ctrl+Alt+T） */
  const [surroundOpen, setSurroundOpen] = useState(false);
  /** 行书签（F11；repoRel:line，按项目持久化） */
  const [bookmarks, setBookmarks] = useState<Set<string>>(new Set());
  /** Ctrl+G 跳行浮层 */
  const [gotoPrompt, setGotoPrompt] = useState<null | { group: 'main' | 'second' }>(null);
  const [gotoInput, setGotoInput] = useState('');
  /** 运行时 monaco 命名空间（markers/EditorOption 等 API） */
  const monacoNsRef = useRef<Monaco | null>(null);
  /** IDEA 键位钩子：稳定壳（onReady 注册一次）经 ref 转发到每次渲染的最新实现 */
  const hooksRef = useRef<Partial<IdeaKeybindingHooks>>({});
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
    // 切换项目 = 关闭全部 Tab（跨项目 Tab 不保留），并退出分屏
    setMainTabs([]); setMainActive('');
    setSecondTabs([]); setSecondActive('');
    setSplitDir(null); setFocusedGroup('main');
    setCursor({ line: 0, col: 0 });
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

  // ==================== 文件树右键：新建/剪切/复制/粘贴/重命名/删除/比较 ====================
  const relOf = (v: string) => v.slice((selectedProject?.localPath || '').length + 1);

  const NEW_FILE_TYPES = [
    { label: 'Java 类', kind: 'java', ext: '.java' },
    { label: 'Markdown 文件', kind: 'md', ext: '.md' },
    { label: 'XML 文件', kind: 'xml', ext: '.xml' },
    { label: 'YAML 文件', kind: 'yaml', ext: '.yml' },
    { label: 'Properties 配置', kind: 'props', ext: '.properties' },
    { label: 'JSON 文件', kind: 'json', ext: '.json' },
    { label: 'HTML 文件', kind: 'html', ext: '.html' },
    { label: 'CSS 样式表', kind: 'css', ext: '.css' },
    { label: 'JavaScript 文件', kind: 'js', ext: '.js' },
    { label: 'TypeScript 文件', kind: 'ts', ext: '.ts' },
    { label: 'SQL 脚本', kind: 'sql', ext: '.sql' },
    { label: 'Shell 脚本', kind: 'sh', ext: '.sh' },
    { label: '文本文件', kind: 'txt', ext: '.txt' },
    { label: '目录', kind: 'dir', ext: '' },
  ];
  const escapeReg = (v: string) => v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  /** 新建文件模板（Java 类自动补 package 声明） */
  const templateFor = (kind: string, name: string, parentRel: string): string => {
    switch (kind) {
      case 'java': {
        const segs = ['src/main/java/', 'src/test/java/'];
        const seg = segs.find(x => parentRel.includes(x));
        const pkg = seg ? parentRel.slice(parentRel.indexOf(seg) + seg.length).replace(/\//g, '.') : '';
        return (pkg ? `package ${pkg};\n\n` : '') + `/**\n * ${name}\n */\npublic class ${name} {\n}\n`;
      }
      case 'md': return `# ${name}\n`;
      case 'xml': return '<?xml version="1.0" encoding="UTF-8"?>\n';
      case 'html': return `<!DOCTYPE html>\n<html lang="zh-CN">\n<head>\n  <meta charset="UTF-8">\n  <title>${name}</title>\n</head>\n<body>\n</body>\n</html>\n`;
      case 'sh': return '#!/bin/sh\n';
      case 'json': return '{\n}\n';
      default: return '';
    }
  };

  const openTreeMenu = (e: ReactMouseEvent, nodePath: string, isDir: boolean) => {
    e.preventDefault();
    e.stopPropagation();
    const name = nodePath.split('/').pop() || nodePath;
    setTreeMenu({ x: e.clientX, y: e.clientY, path: nodePath, name, isDir, sub: false });
  };

  const treeCopyPath = () => {
    if (!treeMenu || !selectedProject) return;
    const display = displayPathOf(treeMenu.path, selectedProject.localPath, selectedProject.repoUrl);
    navigator.clipboard.writeText(display).then(
      () => toast('路径已复制：' + display, 'success'),
      () => toast('复制失败（浏览器剪贴板权限）', 'error'),
    );
    setTreeMenu(null);
  };

  const treePaste = async () => {
    const clip = treeClipRef.current;
    if (!clip || !treeMenu || !selectedProject) return;
    setTreeMenu(null);
    const destDir = treeMenu.isDir ? treeMenu.path : treeMenu.path.split('/').slice(0, -1).join('/');
    const base = clip.path.split('/').pop() || '';
    const dst = `${destDir}/${base}`;
    if (dst === clip.path || dst.startsWith(clip.path + '/')) { toast('目标与源相同或为其子目录', 'error'); return; }
    try {
      const ex = await execCommand(`test -e "/workspace/${dst}" && echo E || echo N`, 8);
      if ((ex.stdout || '').includes('E')) { toast('目标已存在同名文件/目录', 'error'); return; }
      await execCommand(`${clip.mode === 'cut' ? 'mv' : 'cp -r'} "/workspace/${clip.path}" "/workspace/${dst}"`, 30);
      if (clip.mode === 'cut') {
        // 移动后修正引用路径（IDEA Move 语义的字符串级近似）
        await updateReferencesOnRename(clip.path, dst, clip.isDir, base, base);
        treeClipRef.current = null;
        renameTabsPath(clip.path, dst);
      }
      refreshTree(); refreshGitStatus();
      toast(clip.mode === 'cut' ? '已移动' : '已复制', 'success');
    } catch (e) {
      toast('粘贴失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const treeDelete = async () => {
    const t = treeMenu;
    if (!t || !selectedProject) return;
    setTreeMenu(null);
    const rel = relOf(t.path);
    if (!(await confirmDialog({ message: `删除「${t.name}」？${t.isDir ? '目录及其全部内容' : '文件'}将被移除（删除会体现在 Git 变更中）。`, danger: true, confirmText: '删除' }))) return;
    try {
      await deleteFile(rel);
      closeTabsUnder(t.path);
      refreshTree(); refreshGitStatus();
      toast('已删除', 'success');
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 重命名引用更新（字符串级近似 IDEA Rename）：路径引用；Java 源码目录额外替换包名；Java 文件额外替换类名词 */
  const updateReferencesOnRename = async (oldRel: string, newRel: string, isDir: boolean, oldName: string, newName: string): Promise<number> => {
    if (!selectedProject) return 0;
    const lp = selectedProject.localPath || '';
    const jobs: Array<{ needle: string; replacement: string; word?: boolean }> = [{ needle: oldRel, replacement: newRel }];
    if (isDir) {
      for (const seg of ['src/main/java/', 'src/test/java/']) {
        if (oldRel.includes(seg)) {
          const oldPkg = oldRel.slice(oldRel.indexOf(seg) + seg.length).replace(/\//g, '.');
          const newPkg = newRel.slice(newRel.indexOf(seg) + seg.length).replace(/\//g, '.');
          if (oldPkg && newPkg) jobs.push({ needle: oldPkg, replacement: newPkg, word: true });
        }
      }
    } else if (oldName.endsWith('.java')) {
      jobs.push({ needle: oldName.replace(/\.java$/, ''), replacement: newName.replace(/\.java$/, ''), word: true });
    }
    let touched = 0;
    for (const j of jobs) {
      if (!j.needle || j.needle === j.replacement) continue;
      const g = await execCommand(`grep -rIlF "${j.needle}" --exclude-dir=.git /workspace/${lp}`, 30);
      const files = (g.stdout || '').split('\n').map(v => v.trim()).filter(Boolean).slice(0, 50);
      for (const f of files) {
        try {
          const rel = f.replace(/^\/workspace\//, '');
          const content = await readFile(rel);
          const next = j.word
            ? content.replace(new RegExp(`\\b${escapeReg(j.needle)}\\b`, 'g'), j.replacement)
            : content.split(j.needle).join(j.replacement);
          if (next !== content) { await writeFile(rel, next); touched++; }
        } catch { /* 单文件失败不阻断 */ }
      }
    }
    return touched;
  };

  const treeRename = () => {
    const t = treeMenu;
    if (!t) return;
    setRenameDialog({ path: t.path, isDir: t.isDir, oldName: t.name });
    setRenameName(t.name);
    setTreeMenu(null);
  };

  const doRename = async () => {
    if (!renameDialog || !selectedProject) return;
    const oldName = renameDialog.oldName;
    const newName = renameName.trim();
    if (!newName || newName === oldName) { setRenameDialog(null); return; }
    if (/[/\\"'\s]/.test(newName)) { toast('名称不能包含空格与 / \\ " \' 字符', 'error'); return; }
    const parent = renameDialog.path.split('/').slice(0, -1).join('/');
    const finalName = !renameDialog.isDir && !newName.includes('.') && oldName.includes('.')
      ? newName + oldName.slice(oldName.lastIndexOf('.')) : newName;
    const oldRel = relOf(renameDialog.path);
    const newRel = `${parent}/${finalName}`;
    setRenameDialog(null);
    try {
      const ex = await execCommand(`test -e "/workspace/${newRel}" && echo E || echo N`, 8);
      if ((ex.stdout || '').includes('E')) { toast('目标已存在同名文件/目录', 'error'); return; }
      // 先修正引用（grep 旧值），再移动
      const touched = await updateReferencesOnRename(oldRel, newRel, renameDialog.isDir, oldName, finalName);
      await execCommand(`mv "/workspace/${renameDialog.path}" "/workspace/${newRel}"`, 20);
      renameTabsPath(renameDialog.path, newRel);
      refreshTree(); refreshGitStatus();
      toast(touched > 0 ? `已重命名，并更新 ${touched} 个引用文件` : '已重命名', 'success');
    } catch (e) {
      toast('重命名失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 比较对象：弹出选择框列出仓库内同类对象，选定后执行比较 */
  const compareFromMenu = async () => {
    const t = treeMenu;
    if (!t || !selectedProject) return;
    setTreeMenu(null);
    const kind: 'dir' | 'file' = t.isDir ? 'dir' : 'file';
    try {
      const findExpr = kind === 'dir' ? '-type d' : '-type f';
      const r = await execCommand(`find "/workspace/${selectedProject.localPath}" ${findExpr} -not -path "*/.git/*" -not -path "*/target/*" -not -path "*/node_modules/*"`, 30);
      const options = (r.stdout || '').split('\n')
        .map(v => v.trim().replace(/^\/workspace\//, ''))
        .filter(v => v && v !== t.path)
        .sort();
      setCompareFilter('');
      setComparePicker({ a: { path: t.path, isDir: t.isDir }, kind, options });
    } catch (e) {
      toast('加载对象清单失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const openCompare = async (a: { path: string; isDir: boolean }, b: { path: string; isDir: boolean }) => {
    if (!selectedProject) return;
    const la = displayPathOf(a.path, selectedProject.localPath, selectedProject.repoUrl);
    const lb = displayPathOf(b.path, selectedProject.localPath, selectedProject.repoUrl);
    if (!a.isDir) {
      try {
        const [ca, cb] = await Promise.all([readFile(a.path), readFile(b.path)]);
        setCompareResult({ kind: 'file', original: ca, modified: cb, leftTitle: la, rightTitle: lb });
      } catch (e) {
        toast('读取比较内容失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      }
      return;
    }
    // 目录比较：diff -rq 解析差异/独有清单 + find 集合求无差异清单
    const exclNames = ['.git', 'target', 'node_modules', '.idea'];
    const findFilters = ['-not -path "*/.git/*"', ...exclNames.map(n => `-not -path "*/${n}/*"`)].join(' ');
    const diffExcludes = exclNames.map(n => `--exclude=${n}`).join(' ');
    const toRelA = (v: string) => v.startsWith('/workspace/' + a.path + '/') ? v.slice(('/workspace/' + a.path).length + 1) : v;
    try {
      const [rd, fa] = await Promise.all([
        execCommand(`diff -rq ${diffExcludes} "/workspace/${a.path}" "/workspace/${b.path}"`, 60),
        execCommand(`find "/workspace/${a.path}" -type f ${findFilters}`, 60).catch(() => null),
      ]);
      const items: CompareItem[] = [];
      const differRels = new Set<string>();
      (rd.stdout || '').split('\n').filter(Boolean).forEach(line => {
        if (line.startsWith('Files ')) {
          const left = line.slice('Files '.length).split(' and ')[0];
          items.push({ rel: toRelA(left), kind: 'diff' });
          differRels.add(toRelA(left));
        } else if (line.startsWith('Only in ')) {
          const m = /^Only in (.+?): (.+)$/.exec(line);
          if (m) {
            const dirAbs = m[1], name = m[2];
            const relBase = dirAbs.startsWith('/workspace/' + a.path + '/') ? dirAbs.slice(('/workspace/' + a.path).length + 1)
              : dirAbs.startsWith('/workspace/' + b.path + '/') ? dirAbs.slice(('/workspace/' + b.path).length + 1) : '';
            items.push({ rel: relBase ? `${relBase}/${name}` : name, kind: dirAbs.startsWith('/workspace/' + a.path) ? 'onlyA' : 'onlyB' });
          }
        }
      });
      // 无差异 = 左侧清单 − 差异 − 仅左（右侧独有不入此组）
      const onlyASet = new Set(items.filter(i => i.kind === 'onlyA').map(i => i.rel));
      const faRels = (fa?.stdout || '').split('\n').map(v => v.trim())
        .filter(v => v.startsWith('/workspace/' + a.path + '/') && !differRels.has(toRelA(v)) && !onlyASet.has(toRelA(v)))
        .map(v => toRelA(v));
      faRels.sort().forEach(rel => items.push({ rel, kind: 'same' }));
      setCompareResult({ kind: 'dir', original: '', modified: '', dirA: a.path, dirB: b.path, items, leftTitle: la, rightTitle: lb });
    } catch (e) {
      toast('目录比较失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 新建文件/目录（模板内容；Java 类自动补 package 声明） */
  const createNew = async () => {
    if (!newFileDialog || !selectedProject) return;
    const { parentPath, type } = newFileDialog;
    const name = newFileName.trim();
    if (!name) { toast('请输入名称', 'error'); return; }
    if (/[/"'\\]/.test(name)) { toast('名称不能包含 / 与引号字符', 'error'); return; }
    const isDir = type.kind === 'dir';
    const fileName = isDir ? name : (name.includes('.') ? name : name + type.ext);
    const abs = `${parentPath}/${fileName}`;
    const rel = relOf(abs);
    try {
      const ex = await execCommand(`test -e "/workspace/${abs}" && echo E || echo N`, 8);
      if ((ex.stdout || '').includes('E')) { toast('同名文件/目录已存在', 'error'); return; }
      await execCommand(`mkdir -p "/workspace/${parentPath}"`, 8);
      if (isDir) await execCommand(`mkdir -p "/workspace/${abs}"`, 8);
      else await writeFile(abs, templateFor(type.kind, fileName.endsWith(type.ext) ? fileName.slice(0, -type.ext.length) : fileName, rel));
      setNewFileDialog(null); setNewFileName('');
      if (!expandedDirs.has(parentPath)) toggleDir(parentPath);
      refreshGitStatus();
      toast(`已创建 ${rel}`, 'success');
      if (!isDir) openInEditor(abs);
    } catch (e) {
      toast('创建失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 递归渲染树节点（相对路径 = 卷内文件路径；单链目录按 IDEA 式折叠为单节点） */
  const renderTreeNode = (entry: FileEntry, parentPath: string, depth: number) => {
    const absPath = parentPath ? `${parentPath}/${entry.name}` : entry.name;
    const isDir = entry.dir;
    // 折叠链：仅含单一子目录的目录向下合并，标签显示完整链（如 com/hx/nine/eleven/socket）
    const { label, nodePath } = isDir ? compactOf(absPath, entry.name) : { label: entry.name, nodePath: absPath };
    const isExpanded = expandedDirs.has(nodePath);
    // 未提交变更着色（IDEA 语义：修改=蓝，新增=绿，删除=红删除线；目录不改色）
    const rel = nodePath.slice((selectedProject?.localPath || '').length + 1);
    const changeCls = !isDir
      ? (gitStatusMap[rel] === 'M' ? 'text-sky-400' : gitStatusMap[rel] === 'A' ? 'text-green-400' : gitStatusMap[rel] === 'D' ? 'text-cinnabar-400 line-through' : '')
      : '';
    return (
      <div key={nodePath}>
        <button
          onClick={() => (isDir ? toggleDir(nodePath) : openInEditor(absPath, undefined, { preview: true, group: focusedGroup }))}
          onContextMenu={(e) => openTreeMenu(e, nodePath, isDir)}
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
          <span className={`truncate ${changeCls}`}>{label}</span>
        </button>
        {isDir && isExpanded && ideaSortEntries(tree[nodePath] || []).map(child => renderTreeNode(child, nodePath, depth + 1))}
        {isDir && isExpanded && treeLoading && !tree[nodePath] && (
          <div className="px-2 py-1 text-[10px] text-ink-600" style={{ paddingLeft: 20 + depth * 14 }}>加载中…</div>
        )}
      </div>
    );
  };

  // ==================== 多 Tab 编辑器（IDEA 对齐） ====================

  /**
   * 打开文件到编辑 Tab（IDEA 语义）：
   * - 已打开 → 激活；预览 Tab 再次打开转正；
   * - 新打开（默认预览 Tab）→ 复用唯一预览 Tab 位（dirty 旧预览转正保留）；
   * - code 打开即可编辑，md 默认阅读视图，二进制只读提示；
   * - line 携带时定位（全项目搜索命中/调试停点）。
   */
  const openInEditor = async (path: string, line?: number, opts?: { preview?: boolean; group?: 'main' | 'second'; }) => {
    if (!path) return;
    const group = opts?.group || 'main';
    const wantPreview = !!opts?.preview && group === 'main';
    const existsIn = group === 'second' ? secondTabs : mainTabs;
    // 跨组去重：文件已在另一组打开 → 激活那一组（同文件不双开，IDEA 导航跳转到所在组）
    const otherHit = (group === 'second' ? mainTabs : secondTabs).find(t => t.path === path);
    if (otherHit) {
      const other = group === 'second' ? 'main' : 'second';
      if (otherHit.preview) patchTab(path, { preview: false });
      if (line && line !== otherHit.gotoLine) patchTab(path, { gotoLine: line });
      activateIn(other, path);
      return;
    }
    const existed = existsIn.find(t => t.path === path);
    if (existed) {
      // 已打开：激活 + 转正（IDEA 语义：预览 Tab 被再次打开/定位即固定）+ 跳行
      if (existed.preview) patchTab(path, { preview: false });
      if (line && line !== existed.gotoLine) patchTab(path, { gotoLine: line });
      activateIn(group, path);
      return;
    }
    // 预览 Tab 复用：新预览替换旧预览位（dirty 旧预览先转正保留）
    let replacedPreviewKey = '';
    if (wantPreview) {
      const oldPreview = mainTabs.find(t => t.preview && !t.pinned && t.path !== path);
      if (oldPreview) {
        if (oldPreview.dirty) patchTab(oldPreview.path, { preview: false });
        else replacedPreviewKey = oldPreview.path;
      }
    }
    const kind: EditorTab['kind'] = isMarkdownPath(path) ? 'md' : 'code';
    const tab: EditorTab = {
      path, title: tabTitleOf(path), content: '', savedContent: '',
      dirty: false, pinned: false, preview: wantPreview, kind,
      reading: kind === 'md', loading: true, gotoLine: line, eol: '\n',
    };
    if (group === 'second') {
      setSecondTabs(prev => [...prev.filter(t => t.path !== path), tab]);
      activateIn('second', path);
    } else {
      setMainTabs(prev => [...prev.filter(t => t.path !== replacedPreviewKey && t.path !== path), tab]);
      activateIn('main', path);
    }
    try {
      const content = await readFile(path);
      const realKind: EditorTab['kind'] = kind === 'code' && isProbablyBinary(content) ? 'binary' : kind;
      patchTab(path, { content, savedContent: content, loading: false, kind: realKind });
      setRecentFiles(prev => [path, ...prev.filter(p => p !== path)].slice(0, 20));
    } catch (e) {
      toast('读取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      removeTabIn(group, path);
    }
  };
  // goNav 前向引用接线（后退/前进经 ref 调用 openInEditor）
  openInEditorRef.current = openInEditor;

  /** 快速检索/搜索命中打开（入参为仓库内相对路径，拼接工作空间前缀） */
  const openRepoFile = (repoRel: string, line?: number) => {
    if (!selectedProject?.localPath) return;
    setQuick(null);
    openInEditor(`${selectedProject.localPath}/${repoRel}`, line);
  };

  /** 保存聚焦组激活 Tab（Ctrl+S / 保存按钮；md 阅读态/二进制/加载中忽略） */
  const saveActiveTab = async () => {
    const t = activeTab;
    if (!t || t.kind === 'binary' || t.loading || (t.kind === 'md' && t.reading)) return;
    setSaving(true);
    try {
      await writeFile(t.path, t.content);
      patchTab(t.path, { savedContent: t.content, dirty: false, preview: false });
      toast('已保存', 'success');
      refreshGitStatus();
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  /** 还原激活 Tab 到最近保存内容（原「退出编辑」的 IDEA 化：编辑器内回滚未保存修改） */
  const rollbackActiveTab = async () => {
    const t = activeTab;
    if (!t || !t.dirty) return;
    if (!(await confirmDialog({ message: `把「${t.title}」还原到最近保存的内容？未保存修改将丢失。`, danger: true, confirmText: '还原' }))) return;
    patchTab(t.path, { content: t.savedContent, dirty: false });
  };

  /** 关闭 Tab（dirty 先确认）；激活项转移由 removeTabIn 修正 */
  const closeTab = async (group: 'main' | 'second', key: string) => {
    const arr = group === 'second' ? secondTabs : mainTabs;
    const t = arr.find(x => x.path === key);
    if (!t) return;
    if (t.dirty && !(await confirmDialog({ message: `「${t.title}」有未保存的修改，确定关闭并丢弃？`, danger: true, confirmText: '关闭并丢弃' }))) return;
    removeTabIn(group, key);
  };

  /** 关闭其他（保留固定与目标 Tab；未保存修改一次性确认） */
  const closeOtherTabs = async (group: 'main' | 'second', key: string) => {
    const arr = group === 'second' ? secondTabs : mainTabs;
    const removed = arr.filter(t => !t.pinned && t.path !== key);
    if (removed.length === 0) return;
    if (removed.some(t => t.dirty) && !(await confirmDialog({ message: `${removed.filter(t => t.dirty).length} 个未保存的标签将被丢弃，继续关闭其他？`, danger: true, confirmText: '继续' }))) return;
    if (group === 'second') {
      setSecondTabs(arr.filter(t => t.pinned || t.path === key));
      setSecondActive(a => (a === key || !arr.find(t => t.path === a && (t.pinned || t.path === key))) ? key : a);
    } else {
      setMainTabs(arr.filter(t => t.pinned || t.path === key));
      setMainActive(key);
    }
  };

  /** 关闭全部（固定 Tab 一并关闭；未保存一次性确认） */
  const closeAllTabs = async (group: 'main' | 'second') => {
    const arr = group === 'second' ? secondTabs : mainTabs;
    if (arr.length === 0) return;
    if (arr.some(t => t.dirty) && !(await confirmDialog({ message: `${arr.filter(t => t.dirty).length} 个未保存的标签将被丢弃，继续关闭全部？`, danger: true, confirmText: '继续' }))) return;
    if (group === 'second') { setSecondTabs([]); setSecondActive(''); }
    else { setMainTabs([]); setMainActive(''); }
  };

  /** 固定/取消固定（双击 Tab 同效） */
  const togglePinTab = (path: string) => {
    patchTab(path, t => ({ pinned: !t.pinned }));
  };

  /** 移到右侧/下方分屏（IDEA Split：Tab 移入副组） */
  const moveToSecond = (path: string, dir: 'right' | 'down') => {
    const t = mainTabs.find(x => x.path === path);
    if (!t) return;
    removeTabIn('main', path);
    setSplitDir(dir);
    setSecondTabs(prev => [...prev.filter(x => x.path !== path), t]);
    setSecondActive(path);
    setFocusedGroup('second');
  };

  /** 副组 Tab 移回主编辑区（空则解除分屏） */
  const moveBackToMain = (path: string) => {
    const t = secondTabs.find(x => x.path === path);
    if (!t) return;
    const remaining = secondTabs.filter(x => x.path !== path);
    setSecondTabs(remaining);
    setSecondActive(remaining[remaining.length - 1]?.path || '');
    if (remaining.length === 0) setSplitDir(null);
    setFocusedGroup('main');
    setMainTabs(prev => (prev.some(x => x.path === path) ? prev : [...prev, t]));
    setMainActive(path);
  };

  /** 解除分屏：副组 Tab 全部移回主组（同 path 冲突保留主组版本） */
  const unsplit = () => {
    if (!splitDir) return;
    const move = secondTabs.filter(t => !mainTabs.some(m => m.path === t.path));
    setMainTabs(prev => [...prev, ...move]);
    const movedActive = move.some(t => t.path === secondActive) ? secondActive : mainActive;
    setMainActive(movedActive);
    setSecondTabs([]); setSecondActive('');
    setSplitDir(null); setFocusedGroup('main');
  };

  /** 相邻 Tab 切换（Alt+←/→）；Ctrl+Tab 循环切换同向实现 */
  const switchTab = (dir: 1 | -1) => {
    const arr = focusedTabs;
    if (arr.length < 2 || !focusedActive) return;
    const idx = arr.findIndex(t => t.path === focusedActive);
    const next = arr[(idx + dir + arr.length) % arr.length];
    activateIn(focusedGroup, next.path);
  };

  /** 记录最近光标位置（IDEA Recent Locations：跨文件光标历史，同文件同行去重，保留 30 条） */
  const recordLocation = useCallback((path: string, line: number, col: number) => {
    if (!line) return;
    const last = lastLocRef.current;
    if (last.path === path && last.line === line) return;
    lastLocRef.current = { path, line };
    const t = mainTabs.find(x => x.path === path) || secondTabs.find(x => x.path === path);
    const text = t ? (t.content.split('\n')[line - 1] || '').trim().slice(0, 80) : '';
    setRecentLocations(prev => [{ path, line, col, text }, ...prev.filter(l => !(l.path === path && l.line === line))].slice(0, 30));
  }, [mainTabs, secondTabs]);

  // ==================== Phase 2 · IDEA 编辑功能 ====================

  /** 指定文件的书签行集合 */
  const bookmarksOf = (p: string): number[] => {
    const rel = repoRelOf(p);
    return rel ? Array.from(bookmarks).filter(k => k.startsWith(rel + ':')).map(k => Number(k.slice(rel.length + 1))) : [];
  };
  /** F11 行书签切换（当前光标行，IDEA 语义） */
  const toggleBookmarkAt = () => {
    const ed = focusedEditor();
    const pos = ed?.getPosition();
    if (!ed || !pos || !currentPath || !repoRelOf(currentPath)) return;
    const key = `${repoRelOf(currentPath)}:${pos.lineNumber}`;
    setBookmarks(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };
  // 书签按项目持久化
  useEffect(() => {
    if (!selectedProject?.projectId || typeof window === 'undefined') return;
    try {
      const raw = window.localStorage.getItem(`dw-bookmarks-${selectedProject.projectId}`);
      setBookmarks(new Set<string>(raw ? (JSON.parse(raw) as string[]) : []));
    } catch { setBookmarks(new Set()); }
  }, [selectedProject?.projectId]);
  useEffect(() => {
    if (!selectedProject?.projectId || typeof window === 'undefined') return;
    try { window.localStorage.setItem(`dw-bookmarks-${selectedProject.projectId}`, JSON.stringify(Array.from(bookmarks))); } catch { /* 忽略 */ }
  }, [bookmarks, selectedProject?.projectId]);

  /** F2/Shift+F2 错误导航：Monaco markers 循环跳转（TS/JSON/CSS/HTML worker 内置诊断） */
  const jumpMarker = (dir: 1 | -1) => {
    const ed = focusedEditor();
    const mono = monacoNsRef.current;
    if (!ed || !mono) return;
    const model = ed.getModel();
    if (!model) return;
    const markers: Array<{ severity: number; startLineNumber: number; startColumn: number; message: string }> =
      (mono.editor.getModelMarkers({ resource: model.uri }) as Array<{ severity: number; startLineNumber: number; startColumn: number; message: string }>)
        .filter(m => m.severity >= 4) // >= Warning
        .sort((a, b) => a.startLineNumber - b.startLineNumber);
    if (!markers.length) { toast('当前文件没有检测到问题（仅 TS/JSON/CSS/HTML 有内置诊断）', 'info'); return; }
    const cur = ed.getPosition()?.lineNumber ?? 0;
    const target = dir === 1
      ? markers.find(m => m.startLineNumber > cur) || markers[0]
      : [...markers].reverse().find(m => m.startLineNumber < cur) || markers[markers.length - 1];
    ed.revealLineInCenter(target.startLineNumber);
    ed.setPosition({ lineNumber: target.startLineNumber, column: target.startColumn });
    ed.focus();
    const sev = target.severity >= 8 ? 'error' : 'info'; // 8=Error
    toast(`行 ${target.startLineNumber}：${target.message.slice(0, 140)}`, sev);
  };

  /** Ctrl+Alt+L 格式化：Monaco 内置 formatter 优先（TS/JSON/CSS/HTML），无 provider 时由设置面板的保存格式化兜底提示 */
  const formatActiveEditor = () => {
    const ed = focusedEditor();
    if (!ed) return;
    const action = ed.getAction('editor.action.formatDocument');
    if (!action) { toast('当前语言暂无内置格式化器', 'info'); return; }
    void action.run().then(() => toast('已格式化', 'success')).catch(() => toast('格式化失败', 'error'));
  };

  /** Ctrl+Alt+T 应用包裹（选区通用花括号结构） */
  const doSurround = (tplKey: string) => {
    const ed = focusedEditor();
    const tpl = SURROUND_TEMPLATES.find(t => t.key === tplKey);
    if (!ed || !tpl) { setSurroundOpen(false); return; }
    if (!ed.getSelection()?.isEmpty()) applySurround(ed, tpl);
    else toast('请先选中要包裹的代码', 'info');
    setSurroundOpen(false);
  };

  /** Ctrl+Shift+V 历史粘贴到光标/选区 */
  const pasteFromHistory = (text: string) => {
    const ed = focusedEditor();
    if (!ed) return;
    const sel = ed.getSelection();
    if (sel) ed.executeEdits('paste-history', [{ range: sel, text, forceMoveMarkers: true }]);
    ed.focus();
    setClipPickerOpen(false);
  };
  // 剪贴板历史采集：copy/cut 事件统一记录（Monaco 剪贴板动作也会派发 document 事件）
  useEffect(() => {
    const onCopyCut = (e: ClipboardEvent) => {
      const text = e.clipboardData?.getData('text/plain');
      if (text) setClipHistory(prev => [text, ...prev.filter(x => x !== text)].slice(0, 100));
    };
    document.addEventListener('copy', onCopyCut);
    document.addEventListener('cut', onCopyCut);
    return () => { document.removeEventListener('copy', onCopyCut); document.removeEventListener('cut', onCopyCut); };
  }, []);

  /** Ctrl+G 跳行（行或 行:列），作用于发起时的组 */
  const doGotoLine = () => {
    const ed = gotoPrompt?.group === 'second' ? secondEditorRef.current : editorRef.current;
    const m = /^(\d+)(?::(\d+))?$/.exec(gotoInput.trim());
    if (ed && m) {
      const line = Math.max(1, Number(m[1]));
      const col = Math.max(1, Number(m[2] || 1));
      ed.revealLineInCenter(line);
      ed.setPosition({ lineNumber: line, column: col });
      ed.focus();
    }
    setGotoPrompt(null);
  };

  /** 弹层关闭后焦点回归编辑器（IDEA 行为：Escape 关闭弹层后继续键位操作） */
  const restoreEditorFocus = () => {
    setTimeout(() => focusedEditor()?.focus(), 60);
  };

  /** 自动保存（IDEA 默认语义：修改后 0.8s 写盘；保存时格式化可开） */
  const autoSaveTimersRef = useRef<Record<string, ReturnType<typeof setTimeout>>>({});
  useEffect(() => {
    if (!editorSettings.autoSave) return;
    const all = [...mainTabs, ...secondTabs];
    all.forEach(t => {
      if (!t.dirty || t.loading || t.kind === 'binary' || (t.kind === 'md' && t.reading)) return;
      if (autoSaveTimersRef.current[t.path]) return;
      autoSaveTimersRef.current[t.path] = setTimeout(async () => {
        delete autoSaveTimersRef.current[t.path];
        try {
          let content = t.content;
          if (editorSettings.formatOnSave) {
            // 保存时格式化：Monaco 内置 formatter 就绪才生效（无 provider 语言跳过）
            const ed = editorRef.current;
            const model = ed?.getModel();
            const fmt = editorRef.current?.getAction('editor.action.formatDocument');
            if (ed && model && fmt && model.getValue() === content) {
              try { await fmt.run(); } catch { /* 格式化失败按原文保存 */ }
              content = model.getValue();
            }
          }
          await writeFile(t.path, content);
          patchTab(t.path, tt => (tt.content === content ? { savedContent: content, dirty: false } : {}));
        } catch { /* 自动保存失败静默，手动 Ctrl+S 可重试 */ }
      }, 800);
    });
    return () => {
      // 清理已不在 dirty 列表的定时器（保存完成/关闭 Tab）
      const dirtyPaths = new Set(all.filter(t => t.dirty).map(t => t.path));
      Object.keys(autoSaveTimersRef.current).forEach(p => {
        if (!dirtyPaths.has(p)) { clearTimeout(autoSaveTimersRef.current[p]); delete autoSaveTimersRef.current[p]; }
      });
    };
  }, [mainTabs, secondTabs, editorSettings.autoSave, editorSettings.formatOnSave, patchTab]);

  /** IDEA 键位钩子实现（每次渲染更新到 hooksRef；onReady 注册的稳定壳转发到这里） */
  hooksRef.current = {
    onGotoLine: () => { setGotoInput(''); setGotoPrompt({ group: focusedGroup }); },
    onToggleBookmark: toggleBookmarkAt,
    onSurroundWith: () => setSurroundOpen(true),
    onFormat: formatActiveEditor,
    onPasteHistory: () => setClipPickerOpen(true),
    onJumpToNextError: () => jumpMarker(1),
    onJumpToPrevError: () => jumpMarker(-1),
  };
  /** onReady 稳定壳（闭包只引 hooksRef，函数身份恒定） */
  const editorHooks: IdeaKeybindingHooks = useMemo(() => ({
    onGotoLine: () => hooksRef.current.onGotoLine?.(),
    onToggleBookmark: () => hooksRef.current.onToggleBookmark?.(),
    onShowBookmarks: () => hooksRef.current.onShowBookmarks?.(),
    onSurroundWith: () => hooksRef.current.onSurroundWith?.(),
    onFormat: () => hooksRef.current.onFormat?.(),
    onFindUsages: () => hooksRef.current.onFindUsages?.(),
    onGotoDefinition: () => hooksRef.current.onGotoDefinition?.(),
    onFindAction: () => hooksRef.current.onFindAction?.(),
    onPasteHistory: () => hooksRef.current.onPasteHistory?.(),
    onRename: () => hooksRef.current.onRename?.(),
    onJumpToNextError: () => hooksRef.current.onJumpToNextError?.(),
    onJumpToPrevError: () => hooksRef.current.onJumpToPrevError?.(),
    onGotoStructure: () => hooksRef.current.onGotoStructure?.(),
  }), []);

  // ==================== Phase 3 · 导航与搜索（IDEA 对齐） ====================

  /** 光标处单词（跳转定义/查找用法/重命名共用） */
  const wordAtCursor = (): string | null => {
    const ed = focusedEditor();
    if (!ed || !ed.getPosition()) return null;
    return ed.getModel()?.getWordAtPosition(ed.getPosition()!)?.word || null;
  };

  /** 项目内声明检索（Go to Symbol / 跳转定义数据源；grep -E 声明模式，词白名单防注入） */
  const runSymbolsQuery = useCallback(async (query: string): Promise<Array<{ path: string; line?: number; text?: string; kind?: string }>> => {
    const lp = selectedProject?.localPath;
    const w = query.trim().replace(/[^\w$]/g, '');
    if (!lp || !w) return [];
    const r = await execCommand(
      `grep -rnIE "((def|function|func|fn)[[:space:]]+${w}\\b|(class|interface|enum|struct|trait|record)[[:space:]]+${w}\\b|${w}[[:space:]]*\\\\()" --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target /workspace/${lp} 2>/dev/null | head -60`, 30);
    return (r.stdout || '').split('\n').filter(Boolean).map(line => {
      const m = /^\/workspace\/(.+?):(\d+):(.*)$/.exec(line);
      if (!m) return null;
      const isCall = m[3].includes(`${w}(`) && !/(def|function|func|fn)\s/.test(m[3]) && !/(class|interface|enum|struct|trait)\s/.test(m[3]);
      return { path: m[1], line: Number(m[2]), text: m[3], kind: isCall ? '引用' : '声明' };
    }).filter(Boolean) as Array<{ path: string; line?: number; text?: string; kind?: string }>;
  }, [selectedProject]);

  /** 项目内词边界用法检索（Find Usages 数据源） */
  const runUsagesQuery = useCallback(async (query: string): Promise<Array<{ path: string; line?: number; text?: string; kind?: string }>> => {
    const lp = selectedProject?.localPath;
    const w = query.trim().replace(/[^\w$]/g, '');
    if (!lp || !w) return [];
    const r = await execCommand(
      `grep -rnIw "${w}" --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target /workspace/${lp} 2>/dev/null | head -100`, 30);
    return (r.stdout || '').split('\n').filter(Boolean).map(line => {
      const m = /^\/workspace\/(.+?):(\d+):(.*)$/.exec(line);
      return m ? { path: m[1], line: Number(m[2]), text: m[3], kind: '用法' } : null;
    }).filter(Boolean) as Array<{ path: string; line?: number; text?: string; kind?: string }>;
  }, [selectedProject]);

  /** Ctrl+B 跳转定义（词法近似）：声明 grep → 单命中直跳 / 多命中弹列表 */
  const gotoDefinition = async () => {
    const word = wordAtCursor();
    if (!word) { toast('光标处无符号', 'info'); return; }
    setPendingQuery(word);
    setQuick('symbols');
  };

  /** Alt+F7 查找用法：词边界 grep → 结果列表 + 当前文件高亮 */
  const findUsages = async () => {
    const word = wordAtCursor();
    if (!word) { toast('光标处无符号', 'info'); return; }
    setUseHighlightWords([word]);
    setPendingQuery(word);
    setQuick('usages');
  };

  /** Ctrl+Shift+F7 高亮光标词在当前文件的所有使用处（再按清除） */
  const toggleUsageHighlight = () => {
    const word = wordAtCursor();
    if (!word) return;
    setUseHighlightWords(prev => (prev.length === 1 && prev[0] === word ? [] : [word]));
  };

  /** Ctrl+F12 结构视图（当前文件正则大纲） */
  const openStructure = () => setQuick('structure');

  /** Shift+F11 书签列表 */
  const openBookmarks = () => setQuick('bookmarks');

  /** Shift+F6 重命名符号（词法近似：确认后全项目词边界替换，同步打开中的 Tab） */
  const startRenameSymbol = () => {
    const word = wordAtCursor();
    if (!word) { toast('光标处无符号', 'info'); return; }
    setRenameInput(word);
    setRenamePrompt({ word });
  };
  const doRenameSymbol = async () => {
    const rp = renamePrompt;
    if (!rp || !selectedProject) return;
    const next = renameInput.trim();
    setRenamePrompt(null);
    if (!next || next === rp.word) return;
    if (!/^[A-Za-z_$][\w$]*$/.test(next)) { toast('符号名仅支持字母/数字/下划线/$', 'error'); return; }
    const lp = selectedProject.localPath;
    try {
      const g = await execCommand(`grep -rIlw "${rp.word}" --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target /workspace/${lp} 2>/dev/null`, 30);
      const files = (g.stdout || '').split('\n').map(v => v.trim()).filter(Boolean).slice(0, 60);
      const re = new RegExp(`\\b${rp.word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'g');
      let touched = 0;
      for (const f of files) {
        try {
          const rel = f.replace(/^\/workspace\//, '');
          const abs = `${lp}/${rel}`;
          const content = await readFile(abs);
          const nextContent = content.replace(re, next);
          if (nextContent !== content) {
            await writeFile(abs, nextContent);
            patchTab(abs, { content: nextContent, savedContent: nextContent, dirty: false });
            touched++;
          }
        } catch { /* 单文件失败不阻断 */ }
      }
      toast(touched > 0 ? `已重命名「${rp.word}」→「${next}」（${touched} 个文件）` : `未找到「${rp.word}」的使用处`, touched > 0 ? 'success' : 'info');
      if (touched > 0) { refreshGitStatus(); refreshTree(); }
    } catch (e) {
      toast('重命名失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 当前文件结构大纲（结构视图数据源；激活 Tab 内容变化时重算） */
  const currentStructure = useMemo(
    () => (activeTab && !activeTab.loading ? parseOutline(activeTab.content, activeTab.path) : []),
    [activeTab?.path, activeTab?.content, activeTab?.loading, activeTab]);

  /** TODO 扫描（grep TODO|FIXME|XXX|HACK，IDEA TODO 工具窗） */
  const refreshTodos = useCallback(async () => {
    const lp = selectedProject?.localPath;
    if (!lp) return;
    try {
      const r = await execCommand(
        `grep -rnIE "(TODO|FIXME|XXX|HACK)" --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target /workspace/${lp} 2>/dev/null | head -100`, 30);
      const list = (r.stdout || '').split('\n').filter(Boolean).map(line => {
        const m = /^\/workspace\/(.+?):(\d+):(.*)$/.exec(line);
        if (!m) return null;
        const kind = m[3].includes('FIXME') ? 'FIXME' : m[3].includes('XXX') ? 'XXX' : m[3].includes('HACK') ? 'HACK' : 'TODO';
        return { path: m[1], line: Number(m[2]), text: m[3].trim(), todoKind: kind };
      }).filter(Boolean) as Array<{ path: string; line: number; text: string; todoKind: string }>;
      setTodos(list);
    } catch { /* 沙箱未就绪忽略 */ }
  }, [selectedProject]);

  /** Find Action 动作注册表（Ctrl+Shift+A / Search Everywhere 数据源） */
  const quickActions = useMemo<QuickAction[]>(() => [
    { id: 'search-files', label: '按文件名检索…', hint: 'Ctrl+Shift+N', run: () => setQuick('file') },
    { id: 'search-text', label: '在项目中搜索文本…', hint: 'Ctrl+Shift+F', run: () => setQuick('search') },
    { id: 'search-everywhere', label: '全局搜索一切…', hint: 'Double Shift', run: () => setQuick('everywhere') },
    { id: 'replace-in-files', label: '在项目中替换…', hint: 'Ctrl+Shift+R', run: () => setReplaceOpen(true) },
    { id: 'structure', label: '文件结构…', hint: 'Ctrl+F12', run: openStructure },
    { id: 'locations', label: '最近位置…', hint: 'Ctrl+Shift+E', run: () => setQuick('locations') },
    { id: 'bookmarks', label: '书签列表…', hint: 'Shift+F11', run: openBookmarks },
    { id: 'format', label: '格式化代码', hint: 'Ctrl+Alt+L', run: formatActiveEditor },
    { id: 'surround', label: '包裹选中代码…', hint: 'Ctrl+Alt+T', run: () => setSurroundOpen(true) },
    { id: 'settings', label: '编辑器设置…', hint: 'Ctrl+Alt+S', run: () => setSettingsOpen(true) },
    { id: 'terminal', label: '显示/隐藏终端', run: () => setTermOpen(v => !v) },
    { id: 'console', label: '显示/隐藏控制台', run: () => { setConsoleOpen(v => !v); setBottomTab('console'); } },
    { id: 'problems', label: '问题面板', hint: 'Alt+6', run: () => { setConsoleOpen(true); setBottomTab('problems'); } },
    { id: 'todo', label: 'TODO 面板', run: () => { setConsoleOpen(true); setBottomTab('todo'); void refreshTodos(); } },
    { id: 'annotate', label: 'Annotate 行级归属', run: () => void toggleBlame() },
    { id: 'build', label: '构建项目', run: () => selectedProject && void handleBuild(loadRunConf(selectedProject.projectId)?.buildCmd || DEFAULT_BUILD_CMD) },
    { id: 'run', label: '运行（按配置）', run: () => void startRun() },
    { id: 'debug', label: '调试（按配置）', run: () => void startDebug() },
    { id: 'run-config', label: '运行/调试配置…', run: () => setRunConfOpen(true) },
    { id: 'split-right', label: '向右分屏（当前文件）', run: () => mainActive && moveToSecond(mainActive, 'right') },
    { id: 'split-down', label: '向下分屏（当前文件）', run: () => mainActive && moveToSecond(mainActive, 'down') },
    { id: 'unsplit', label: '取消分屏', run: unsplit },
    { id: 'theme-gewu', label: '主题：格物暗色', run: () => setEditorTheme('gewu-dark') },
    { id: 'theme-vs', label: '主题：VS Dark+', run: () => setEditorTheme('vs-dark') },
    { id: 'theme-hc', label: '主题：高对比暗色', run: () => setEditorTheme('hc-black') },
    { id: 'theme-light', label: '主题：浅色', run: () => setEditorTheme('vs') },
    { id: 'refresh-todos', label: '刷新 TODO 扫描', run: () => void refreshTodos() },
  // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [selectedProject, mainActive, refreshTodos]);

  // 键位钩子补全（Phase 3 项；hooksRef 每渲染更新）
  hooksRef.current = {
    ...hooksRef.current,
    onFindUsages: () => void findUsages(),
    onGotoDefinition: () => void gotoDefinition(),
    onFindAction: () => setQuick('actions'),
    onGotoStructure: openStructure,
    onShowBookmarks: openBookmarks,
    onRename: startRenameSymbol,
  };

  // ==================== Phase 4 · Git 集成与辅助 ====================

  /** 刷新指定文件的 gutter 变更标记（git diff -U0 解析：增=纯+行 / 改=+行且 hunk 含-行 / 删=无对应+的块起点） */
  const refreshChangeMarks = useCallback(async (tab: EditorTab | null) => {
    const lp = selectedProject?.localPath;
    const rel = tab ? repoRelOf(tab.path) : '';
    if (!tab || !lp || !rel || tab.kind === 'binary' || tab.loading) return;
    try {
      const r = await execCommand(`git -C /workspace/${lp} diff -U0 -- "${rel}" 2>/dev/null | head -3000`, 15);
      const added: number[] = [], modified: number[] = [], removed: number[] = [];
      let newLine = 0;
      let hunkDeleted = 0;
      let hunkAdded = 0;
      let addedLinesInHunk: number[] = [];
      (r.stdout || '').split('\n').forEach(l => {
        const hunk = /^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(l);
        if (hunk) {
          // 结算上一 hunk：删行标记在 hunk 新起点（删除块落在文件顶部时取 1）
          if (hunkDeleted > 0 && hunkAdded === 0) removed.push(Math.max(1, newLine - 1));
          else if (hunkDeleted > 0) addedLinesInHunk.forEach(n => modified.push(n));
          else addedLinesInHunk.forEach(n => added.push(n));
          addedLinesInHunk = [];
          hunkDeleted = 0;
          hunkAdded = 0;
          newLine = Number(hunk[2]);
          return;
        }
        if (!newLine) return;
        if (l.startsWith('+')) { addedLinesInHunk.push(newLine); hunkAdded++; newLine++; }
        else if (l.startsWith('-')) hunkDeleted++;
        else if (l.startsWith(' ') || l === '' ) newLine++;
      });
      if (hunkDeleted > 0 && hunkAdded === 0) removed.push(Math.max(1, newLine - 1));
      else if (hunkDeleted > 0) addedLinesInHunk.forEach(n => modified.push(n));
      else addedLinesInHunk.forEach(n => added.push(n));
      setChangeMarksMap(prev => ({ ...prev, [tab.path]: { added, modified, removed } }));
    } catch { /* 沙箱未就绪忽略 */ }
  }, [selectedProject]);

  /** 激活 Tab 内容变化（打开/保存/自动保存）后刷新变更标记 */
  useEffect(() => {
    const t = activeTab;
    if (!t) return;
    void refreshChangeMarks(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab?.path, activeTab?.savedContent]);

  /** 回滚当前文件到 HEAD（IDEA Rollback：git checkout -- 文件，丢弃全部未提交修改并刷新 Tab） */
  const rollbackFileToHead = async () => {
    const t = activeTab;
    const lp = selectedProject?.localPath;
    const rel = t ? repoRelOf(t.path) : '';
    if (!t || !lp || !rel) return;
    if (!(await confirmDialog({ message: `把「${t.title}」回滚到 HEAD 版本？未提交的修改（含未保存）将全部丢弃。`, danger: true, confirmText: '回滚' }))) return;
    try {
      const r = await execCommand(`git -C /workspace/${lp} checkout -- "${rel}"`, 15);
      if ((r.stderr || '').trim()) throw new Error(r.stderr);
      const content = await readFile(t.path);
      patchTab(t.path, { content, savedContent: content, dirty: false });
      toast('已回滚到 HEAD 版本', 'success');
      refreshGitStatus();
      void refreshChangeMarks(t);
    } catch (e) {
      toast('回滚失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** EOL 切换（IDEA 状态栏）：model.getEOL() 返回 '\n'/'\r\n' 字符串（非枚举），按字符串比较翻转 */
  const toggleEol = () => {
    const ed = focusedEditor();
    const mono = monacoNsRef.current;
    const t = activeTab;
    if (!ed || !mono || !t) return;
    const model = ed.getModel();
    if (!model) return;
    const toCRLF = model.getEOL() === '\n';
    model.setEOL(toCRLF ? mono.editor.EndOfLineSequence.CRLF : mono.editor.EndOfLineSequence.LF);
    const value = model.getValue();
    patchTab(t.path, { content: value, dirty: value !== t.savedContent });
    toast(`换行符已切换为 ${toCRLF ? 'CRLF' : 'LF'}`, 'success');
  };
  const currentEolLabel = activeTab?.content.includes('\r\n') ? 'CRLF' : 'LF';

  // 编辑区快捷键（IDEA 对齐）：Ctrl+S 保存；Ctrl+F4 关闭 Tab；Ctrl+Tab/Alt+←→ 切换 Tab；
  // Ctrl+Alt+←/→ 导航后退/前进；Ctrl+Shift+N/Ctrl+P 文件检索、Ctrl+E 最近文件、Ctrl+Shift+F 全局搜索
  // （检索面板打开时让位给面板自身的键处理）
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
        saveActiveTab();
        return;
      }
      // Ctrl+F4 关闭当前 Tab（IDEA）
      if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key === 'F4') {
        e.preventDefault();
        void closeTab(focusedGroup, focusedActive);
        return;
      }
      // Ctrl+Tab / Ctrl+Shift+Tab 循环切换（Tab 序前/后）
      if (e.ctrlKey && !e.altKey && !e.metaKey && e.key === 'Tab') {
        e.preventDefault();
        switchTab(e.shiftKey ? -1 : 1);
        return;
      }
      // Ctrl+Alt+←/→ 导航后退/前进（IDEA）
      if ((e.ctrlKey || e.metaKey) && e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault();
        goNav(e.key === 'ArrowLeft' ? -1 : 1);
        return;
      }
      // Alt+←/→ 相邻 Tab 切换
      if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault();
        switchTab(e.key === 'ArrowLeft' ? -1 : 1);
        return;
      }
      // Ctrl+Alt+S 编辑器设置（IDEA）
      if ((e.ctrlKey || e.metaKey) && e.altKey && e.key.toLowerCase() === 's') {
        e.preventDefault();
        setSettingsOpen(true);
        return;
      }
      // Ctrl+Shift+R 全项目替换（IDEA Replace in Files）
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === 'r') {
        e.preventDefault();
        if (projectReady) setReplaceOpen(true);
        return;
      }
      // Alt+6 问题面板 / Alt+7 TODO（IDEA Problems/TODO 工具窗）
      if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
        if (e.key === '6') { e.preventDefault(); setConsoleOpen(true); setBottomTab('problems'); return; }
        if (e.key === '7') { e.preventDefault(); setConsoleOpen(true); setBottomTab('todo'); void refreshTodos(); return; }
      }
      // Ctrl+Shift+F12 最大化编辑器（隐藏/恢复全部工具窗，IDEA）
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'F12') {
        e.preventDefault();
        setMaximized(v => !v);
        return;
      }
      // Ctrl+Shift+F7 高亮使用处（编辑器外兜底；编辑器内走 hooks）
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'F7') {
        e.preventDefault();
        toggleUsageHighlight();
        return;
      }
      // Double Shift 全局搜索一切（IDEA Search Everywhere）：300ms 内两次 Shift 键
      const now = Date.now();
      if (e.key === 'Shift' && !e.ctrlKey && !e.altKey && !e.metaKey && !e.repeat && !quick) {
        const last = lastShiftAt.current;
        lastShiftAt.current = now;
        if (now - last < 300 && projectReady) {
          e.preventDefault();
          setQuick('everywhere');
          return;
        }
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
  }, [quick, selectedProject, selectedProject?.cloneStatus, debugActive, focusedGroup, focusedActive, mainTabs, secondTabs, refreshTodos]);

  // 编辑器主题持久化
  useEffect(() => {
    if (typeof window !== 'undefined') window.localStorage.setItem('dw-editor-theme', editorTheme);
  }, [editorTheme]);

  /** Git 菜单操作完成后：刷新项目状态 + 按展开状态重载文件树 */
  const afterGitRefresh = async () => {
    await load();
    if (selectedProject?.localPath) refreshTree();
    refreshGitStatus();
  };

  /** 刷新未提交变更表（文件树变色数据源）：git status --porcelain -z（NUL 分隔，原生支持中文/重命名） */
  const refreshGitStatus = useCallback(async () => {
    if (!selectedProject || selectedProject.cloneStatus !== 'ready' || !selectedProject.localPath) { setGitStatusMap({}); return; }
    try {
      const r = await execCommand(`git -C /workspace/${selectedProject.localPath} status --porcelain=v1 -z`, 15);
      const map: Record<string, 'M' | 'A' | 'D'> = {};
      const fields = (r.stdout || '').split('\u0000').filter(f => f.length >= 3);
      for (let i = 0; i < fields.length; i++) {
        const f = fields[i];
        const x = f[0], y = f[1];
        let p = f.slice(3);
        if (x === 'R' || y === 'R' || x === 'C' || y === 'C') i++; // 重命名/拷贝：下一字段为原路径
        if (!p) continue;
        if (x === '?' || y === '?') { map[p] = 'A'; continue; }
        const code = x + y;
        if (code.includes('D')) map[p] = 'D';
        else if (code.includes('A') || code.includes('C')) map[p] = 'A';
        else map[p] = 'M';
      }
      setGitStatusMap(map);
    } catch { /* 沙箱未就绪等忽略 */ }
  }, [selectedProject, selectedProject?.cloneStatus, selectedProject?.localPath]);

  useEffect(() => { refreshGitStatus(); }, [refreshGitStatus]);

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
  /** 启动后台运行（base64 落盘 + setsid 进程组 + pidfile）；配置运行与主类右键运行共用 */
  const launchRun = async (cmd: string, title: string) => {
    if (!selectedProject) return;
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
      setRunProc({ pid, cmd });
      setConsoleOpen(true);
      toast(`已启动运行（PID ${pid}）：${title}`, 'success');
    } catch (e) {
      toast('运行启动失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setRunBusy(false);
    }
  };

  const startRun = async () => {
    if (!selectedProject || runBusy) return;
    const conf = loadRunConf(selectedProject.projectId);
    if (!conf) {
      setRunConfOpen(true);
      toast('请先填写运行/调试配置（运行类型、启动类等）', 'info');
      return;
    }
    const cmd = composeRunCmd(conf, selectedProject.projectId);
    if (!cmd) {
      setRunConfOpen(true);
      toast('运行配置不完整，请补充命令', 'info');
      return;
    }
    await launchRun(cmd, '运行配置');
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

  /** 当前文件是否含 main 方法的 Java 类（右键「运行/调试主类」数据源；fqcn 由 src/main/java 相对路径推导） */
  const mainRunInfo = useMemo(() => {
    if (!selectedProject || !currentPath || !currentPath.endsWith('.java') || !fileContent) return null;
    if (!/static\s+(?:final\s+)?void\s+main\s*\(/.test(fileContent)) return null;
    const rel = currentPath.slice((selectedProject.localPath || '').length + 1);
    // 多模块工程取最后一段 src/main|test/java（如 code/src/main/java/...）
    const seg = rel.lastIndexOf('src/main/java/') >= 0 ? 'src/main/java/' : 'src/test/java/';
    const idx = rel.lastIndexOf(seg);
    if (idx < 0) return null;
    const fqcn = rel.slice(idx + seg.length).replace(/\.java$/, '').replace(/\//g, '.');
    return fqcn.includes('.') ? { fqcn } : null;
  }, [selectedProject, currentPath, fileContent]);

  /** 编辑器右键：接管 Monaco 内置英文菜单，改显中文菜单 */
  const onEditorContextMenu = useCallback((e: ReactMouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setCtxMenu({ x: e.clientX, y: e.clientY });
  }, []);

  const menuAct = (handlerId: string) => {
    focusedEditor()?.trigger('ctx-menu', handlerId, null);
    setCtxMenu(null);
  };

  /** 右键运行主类：先保存未提交内容（磁盘为准），编译+类路径后后台运行（复用控制台/停止） */
  const runMain = async () => {
    setCtxMenu(null);
    if (!mainRunInfo || !selectedProject) return;
    const at = activeTab;
    if (at && at.dirty && at.kind !== 'binary') {
      try { await writeFile(at.path, at.content); patchTab(at.path, { savedContent: at.content, dirty: false }); refreshGitStatus(); } catch { /* 保存失败按磁盘版本运行 */ }
    }
    const conf = loadRunConf(selectedProject.projectId);
    const jvm = conf?.type === 'java' ? (conf.jvmOpts || '') : '';
    const cmd = ['mvn compile -DskipTests',
      `mvn -q dependency:build-classpath -Dmdep.outputFile=/workspace/.gewu/cp-${selectedProject.projectId}.txt -Dmdep.includeScope=runtime`,
      `java ${jvm} -cp "target/classes:$(cat /workspace/.gewu/cp-${selectedProject.projectId}.txt)" ${mainRunInfo.fqcn}`.replace(/\s+/g, ' ')].join('\n');
    await launchRun(cmd, `主类 ${mainRunInfo.fqcn}`);
  };

  /** 右键调试主类：保存 → 类路径准备 → 单行 java 命令交后端注入 JDWP */
  const debugMain = async () => {
    setCtxMenu(null);
    if (!mainRunInfo || !selectedProject || debugBusy) return;
    setDebugBusy('start');
    try {
      const at = activeTab;
      if (at && at.dirty && at.kind !== 'binary') {
        try { await writeFile(at.path, at.content); patchTab(at.path, { savedContent: at.content, dirty: false }); refreshGitStatus(); } catch { /* 保存失败按磁盘版本调试 */ }
      }
      toast('正在准备类路径（编译 + 依赖清单）…', 'info');
      const prep = await execCommand(`cd /workspace/${selectedProject.localPath} && mvn -q compile dependency:build-classpath -Dmdep.outputFile=/workspace/.gewu/cp-${selectedProject.projectId}.txt -Dmdep.includeScope=runtime`, 180);
      if (prep.exitCode !== 0) {
        throw new Error('类路径准备失败: ' + ((prep.stderr || prep.stdout || '').trim().slice(-200) || '未知原因'));
      }
      setDebugConsole('');
      applyDebugState(await debugStart(selectedProject.projectId, `java -cp "target/classes:$(cat /workspace/.gewu/cp-${selectedProject.projectId}.txt)" ${mainRunInfo.fqcn}`));
      toast('调试会话已启动', 'success');
    } catch (e) {
      toast('调试启动失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setDebugBusy(null);
    }
  };

  // 控制台自动滚底
  useEffect(() => {
    const el = consoleBodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [runLog, consoleOpen]);

  // 问题面板：Monaco markers 全量聚合（TS/JSON/CSS/HTML worker 内置诊断；激活 Tab 变化时重读）
  useEffect(() => {
    const mono = monacoNsRef.current;
    if (!mono || !activeTab) return;
    const update = () => {
      const map: Record<string, Array<{ line: number; sev: number; msg: string }>> = {};
      (mono.editor.getModelMarkers({}) as Array<{ resource: { path: string }; startLineNumber: number; severity: number; message: string }>).forEach(m => {
        const key = m.resource.path.replace(/^\/workspace\//, '');
        (map[key] ||= []).push({ line: m.startLineNumber, sev: m.severity, msg: m.message });
      });
      setProblemsMap(map);
    };
    update();
    const d = mono.editor.onDidChangeMarkers(update);
    return () => d.dispose();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab?.path, activeTab?.loading]);

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
    const conf = loadRunConf(selectedProject.projectId);
    if (!conf) {
      setRunConfOpen(true);
      toast('请先填写运行/调试配置（运行类型、启动类等）', 'info');
      return;
    }
    const cmd = composeDebugCmd(conf, selectedProject.projectId);
    if (!cmd) {
      setRunConfOpen(true);
      toast('运行配置不完整，请补充命令', 'info');
      return;
    }
    setDebugBusy('start');
    try {
      if (conf.type === 'java') {
        // JDWP 注入要求单行 java 命令——类路径清单先同步生成（编译 + 运行时依赖清单）
        toast('正在准备类路径（编译 + 依赖清单）…', 'info');
        const prep = await execCommand(
          `cd /workspace/${selectedProject.localPath} && mvn -q compile dependency:build-classpath -Dmdep.outputFile=/workspace/.gewu/cp-${selectedProject.projectId}.txt -Dmdep.includeScope=runtime`, 180);
        if (prep.exitCode !== 0) {
          throw new Error('类路径准备失败: ' + ((prep.stderr || prep.stdout || '').trim().slice(-200) || '未知原因'));
        }
      }
      setDebugConsole('');
      applyDebugState(await debugStart(selectedProject.projectId, cmd));
      toast('调试会话已启动', 'success');
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

  /** 仓库相对路径（多 Tab：按任意文件路径计算，装饰数据源按组各自取） */
  const repoRelOf = (p: string): string =>
    selectedProject?.localPath && p.startsWith(selectedProject.localPath + '/') ? p.slice(selectedProject.localPath.length + 1) : '';
  /** 指定文件的断点行集合（编辑器装饰数据源） */
  const breakpointsOf = (p: string): number[] => {
    const rel = repoRelOf(p);
    return rel ? Array.from(bpLines).filter(k => k.startsWith(rel + ':')).map(k => Number(k.slice(rel.length + 1))) : [];
  };
  /** 条件断点行（琥珀色 ? 点，取自会话状态） */
  const condBreakpointsOf = (p: string): number[] => {
    const rel = repoRelOf(p);
    return rel && debugState ? debugState.breakpoints.filter(b => b.condition && b.path === rel).map(b => b.line) : [];
  };
  /** 调试当前执行行（指定文件命中停点时） */
  const debugCurrentLineOf = (p: string): number | undefined => {
    const stopped = debugState?.status === 'stopped' && !!debugState.currentFile
      && (p.endsWith('/' + debugState.currentFile) || p === debugState.currentFile);
    return stopped ? debugState!.currentLine : undefined;
  };
  // 聚焦组激活文件的调试装饰（运行到光标/状态栏引用）
  const currentRepoRel = repoRelOf(currentPath);
  const currentBreakpoints = breakpointsOf(currentPath);
  const currentCondBreakpoints = condBreakpointsOf(currentPath);
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
                  <button onClick={() => handleBuild(loadRunConf(selectedProject.projectId)?.buildCmd || DEFAULT_BUILD_CMD)} disabled={execLoading} className="px-2 py-1.5 text-xs text-ink-300 hover:text-tech-400 border border-ink-700/60 rounded transition-all disabled:opacity-50" title="构建（命令在运行/调试配置中设置）">构建</button>
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
                    onClick={() => setRunConfOpen(true)}
                    className="px-0.5 py-1.5 text-ink-500 hover:text-tech-400 transition-all" title="运行/调试配置（运行类型、启动类、JVM/程序参数、构建命令）">
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
                  title="调试运行（按运行/调试配置启动；Java 自动注入 JDWP；行号槽点击设断点）">
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

          {/* 编辑操作组（多 Tab：聚焦组激活文件；md 阅读态给「编辑」入口，其余可编辑态给保存/还原） */}
          {activeTab && (
            <div className="flex items-center gap-1.5">
              {activeTab.kind === 'md' && (
                <button
                  onClick={() => patchTab(activeTab.path, t => ({ reading: !t.reading }))}
                  className="flex items-center gap-1 px-2 py-1.5 text-xs text-ink-300 hover:text-ink-100 border border-ink-700/60 rounded transition-all"
                  title={activeTab.reading ? '编辑此 Markdown（Ctrl+S 保存）' : '阅读视图（渲染预览）'}>
                  {activeTab.reading ? <><Pencil className="w-3 h-3" />编辑</> : <><BookOpen className="w-3 h-3" />阅读</>}
                </button>
              )}
              {dirty && <span className="text-[10px] text-amber-400">● 未保存</span>}
              <button onClick={saveActiveTab} disabled={saving || !dirty || viewMode !== 'edit'} className="flex items-center gap-1 px-2 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50" title="保存（Ctrl+S）">
                {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}保存
              </button>
              <button onClick={rollbackActiveTab} disabled={!dirty} className="flex items-center gap-1 px-2 py-1.5 text-xs text-ink-300 hover:text-ink-100 border border-ink-700/60 rounded transition-all disabled:opacity-50" title="还原到最近保存内容（丢弃未保存修改）">
                <Undo2 className="w-3 h-3" />还原
              </button>
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
        {/* 左侧：项目 + 文件树 + 凭证（项目配置同步；最大化编辑器时隐藏，IDEA Ctrl+Shift+F12） */}
        <div className={`w-72 flex-shrink-0 flex flex-col gap-3 min-h-0 ${maximized ? 'hidden' : ''}`}>
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
            <div className="flex-1 min-h-0 overflow-y-auto" onContextMenu={(e) => e.preventDefault()}>
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
                    onContextMenu={(e) => openTreeMenu(e, selectedProject.localPath, true)}
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

        {/* 中间：代码编辑器（IDEA 式多 Tab + 可选分屏：右/下 2-Group；路径与操作按钮已上移顶部导航栏） */}
        <div className="flex-1 min-w-0 glass-dark rounded-xl p-4 pt-2 flex flex-col overflow-hidden">
          <div className={`flex-1 min-h-0 flex gap-2 ${splitDir === 'down' ? 'flex-col' : 'flex-row'}`}>
            {/* 主编辑组 */}
            <div className={`flex flex-col min-w-0 min-h-0 flex-1 ${splitDir ? 'border border-tech-500/10 rounded-lg overflow-hidden' : ''}`}
              onMouseDownCapture={() => setFocusedGroup('main')}>
              <DevEditorTabs
                tabs={mainTabs.map(tabMetaOf)}
                activeKey={mainActive}
                hasSplit={!!splitDir}
                onActivate={(p) => activateIn('main', p)}
                onActivatePreview={(p) => activateIn('main', p)}
                onClose={(p) => void closeTab('main', p)}
                onCloseOthers={(p) => void closeOtherTabs('main', p)}
                onCloseAll={() => void closeAllTabs('main')}
                onTogglePin={togglePinTab}
                onOpenNormal={(p) => patchTab(p, { preview: false })}
                onSplit={moveToSecond}
                onMoveBack={() => undefined}
              />
              <div className="flex-1 min-h-0 flex flex-col" onContextMenuCapture={mainActiveTab ? onEditorContextMenu : undefined}>
                {mainActiveTab?.loading ? (
                  <div className="flex-1 flex items-center justify-center py-20"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>
                ) : mainActiveTab?.kind === 'binary' ? (
                  <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
                    <p className="text-sm">二进制文件不支持预览</p>
                  </div>
                ) : mainActiveTab?.kind === 'md' && mainActiveTab.reading ? (
                  <div className="flex-1 min-h-0">
                    <MarkdownReader content={mainActiveTab.content} showToc />
                  </div>
                ) : mainActiveTab ? (
                  <div className="flex-1 min-h-0">
                    <MonacoFileEditor
                      value={mainActiveTab.content}
                      language={monacoLangOf(mainActiveTab.path)}
                      path={`dw-${mainActiveTab.path}`}
                      wordWrap="on"
                      theme={editorTheme}
                      settings={editorSettings}
                      bookmarks={bookmarksOf(mainActiveTab.path)}
                      highlightWords={useHighlightWords}
                      changeMarks={changeMarksMap[mainActiveTab.path]}
                      onReady={(ed, mono) => { editorRef.current = ed; monacoNsRef.current = mono; registerIdeaKeybindings(ed, mono, editorHooks); (window as unknown as { __dwEditors?: Record<string, unknown> }).__dwEditors = { ...((window as unknown as { __dwEditors?: Record<string, unknown> }).__dwEditors || {}), main: ed }; }}
                      gotoLine={mainActiveTab.gotoLine}
                      onCursor={(line, col) => { if (focusedGroup === 'main') { setCursor({ line, col }); recordLocation(mainActiveTab.path, line, col); } }}
                      breakpoints={breakpointsOf(mainActiveTab.path)}
                      conditionalBreakpoints={condBreakpointsOf(mainActiveTab.path)}
                      onToggleBreakpoint={toggleBreakpoint}
                      currentLine={debugCurrentLineOf(mainActiveTab.path)}
                      inlineValue={inlineValue}
                      lineAfter={blameOn && mainActiveTab.path === currentPath ? blameLines : undefined}
                      valueLookup={debugValueLookup}
                      onChange={(v) => {
                        const val = v ?? '';
                        patchTab(mainActiveTab.path, t => ({ content: val, dirty: val !== t.savedContent, preview: false }));
                      }}
                    />
                  </div>
                ) : (
                  <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
                    <p className="text-sm">{projectReady ? '从左侧文件树选择文件进行查看或编辑' : '选择项目后自动拉取代码，或点击顶部「拉取代码」'}</p>
                    {projectReady && <p className="text-xs mt-2 text-ink-600">Ctrl+Shift+N 文件检索 · Ctrl+Shift+F 全局搜索 · Ctrl+E 最近文件 · Double Shift 全局搜索一切</p>}
                  </div>
                )}
              </div>
            </div>

            {/* 副编辑组（IDEA 分屏 2-Group） */}
            {splitDir && (
              <div className="flex flex-col min-w-0 min-h-0 flex-1 border border-tech-500/10 rounded-lg overflow-hidden"
                onMouseDownCapture={() => setFocusedGroup('second')}>
                <DevEditorTabs
                  tabs={secondTabs.map(tabMetaOf)}
                  activeKey={secondActive}
                  isSecondary
                  hasSplit
                  onActivate={(p) => activateIn('second', p)}
                  onActivatePreview={(p) => activateIn('second', p)}
                  onClose={(p) => void closeTab('second', p)}
                  onCloseOthers={(p) => void closeOtherTabs('second', p)}
                  onCloseAll={() => void closeAllTabs('second')}
                  onTogglePin={togglePinTab}
                  onOpenNormal={(p) => patchTab(p, { preview: false })}
                  onSplit={() => undefined}
                  onMoveBack={moveBackToMain}
                />
                <div className="flex-1 min-h-0 flex flex-col" onContextMenuCapture={secondActiveTab ? onEditorContextMenu : undefined}>
                  {secondActiveTab?.loading ? (
                    <div className="flex-1 flex items-center justify-center py-20"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>
                  ) : secondActiveTab?.kind === 'binary' ? (
                    <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
                      <p className="text-sm">二进制文件不支持预览</p>
                    </div>
                  ) : secondActiveTab?.kind === 'md' && secondActiveTab.reading ? (
                    <div className="flex-1 min-h-0">
                      <MarkdownReader content={secondActiveTab.content} showToc />
                    </div>
                  ) : secondActiveTab ? (
                    <div className="flex-1 min-h-0">
                      <MonacoFileEditor
                        value={secondActiveTab.content}
                        language={monacoLangOf(secondActiveTab.path)}
                        path={`dw-${secondActiveTab.path}`}
                        wordWrap="on"
                        theme={editorTheme}
                        settings={editorSettings}
                        bookmarks={bookmarksOf(secondActiveTab.path)}
                        highlightWords={useHighlightWords}
                        changeMarks={changeMarksMap[secondActiveTab.path]}
                        onReady={(ed, mono) => { secondEditorRef.current = ed; monacoNsRef.current = mono; registerIdeaKeybindings(ed, mono, editorHooks); (window as unknown as { __dwEditors?: Record<string, unknown> }).__dwEditors = { ...((window as unknown as { __dwEditors?: Record<string, unknown> }).__dwEditors || {}), second: ed }; }}
                        gotoLine={secondActiveTab.gotoLine}
                        onCursor={(line, col) => { if (focusedGroup === 'second') { setCursor({ line, col }); recordLocation(secondActiveTab.path, line, col); } }}
                        breakpoints={breakpointsOf(secondActiveTab.path)}
                        conditionalBreakpoints={condBreakpointsOf(secondActiveTab.path)}
                        onToggleBreakpoint={toggleBreakpoint}
                        currentLine={debugCurrentLineOf(secondActiveTab.path)}
                        valueLookup={debugValueLookup}
                        onChange={(v) => {
                          const val = v ?? '';
                          patchTab(secondActiveTab.path, t => ({ content: val, dirty: val !== t.savedContent, preview: false }));
                        }}
                      />
                    </div>
                  ) : (
                    <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
                      <p className="text-xs">副编辑区（Tab 右键「移到右侧/下方分屏」移入）</p>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>

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

          {/* 底部工具窗（IDEA 式：控制台/问题/TODO 页签；最大化编辑器时隐藏） */}
          {consoleOpen && !maximized && (
            <div className="flex-shrink-0 border-t border-tech-500/10 bg-black/40 rounded-b-xl overflow-hidden flex flex-col" style={{ height: 230 }}>
              <div className="flex items-center justify-between px-3 py-1 border-b border-tech-500/10">
                <div className="flex items-center gap-1 text-[11px] min-w-0">
                  {([
                    ['console', `控制台${runProc ? '（运行中）' : ''}`],
                    ['problems', `问题${Object.values(problemsMap).reduce((n, l) => n + l.length, 0) ? `（${Object.values(problemsMap).reduce((n, l) => n + l.length, 0)}）` : ''}`],
                    ['todo', `TODO${todos.length ? `（${todos.length}）` : ''}`],
                  ] as Array<['console' | 'problems' | 'todo', string]>).map(([k, label]) => (
                    <button key={k} onClick={() => { setBottomTab(k); if (k === 'todo') void refreshTodos(); }}
                      className={`px-2 py-1 rounded-t transition-colors ${bottomTab === k ? 'text-tech-400 border-b border-tech-400' : 'text-ink-500 hover:text-ink-300'}`}>
                      {label}
                    </button>
                  ))}
                  {bottomTab === 'console' && runProc && (
                    <span className="flex items-center gap-1.5 text-tech-400 truncate ml-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-green-400 animate-pulse flex-shrink-0" />
                      PID {runProc.pid} · {runProc.cmd}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  {bottomTab === 'console' && <button onClick={() => setRunLog('')} className="text-[10px] text-ink-500 hover:text-ink-300">清屏</button>}
                  <button onClick={() => setConsoleOpen(false)} className="text-ink-500 hover:text-ink-200" title="隐藏工具窗（再次运行时自动打开）">
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
              {bottomTab === 'console' && (
                <div ref={consoleBodyRef} className="flex-1 min-h-0 overflow-y-auto scrollbar-thin px-3 py-2 font-mono text-[11px] leading-5 whitespace-pre-wrap break-all text-ink-200">
                  {runLog || '（暂无输出）'}
                </div>
              )}
              {bottomTab === 'problems' && (
                <div className="flex-1 min-h-0 overflow-y-auto scrollbar-thin px-3 py-2 text-[11px]">
                  {Object.entries(problemsMap).length === 0 && <div className="text-ink-500">未检测到问题（仅 TS/JS/JSON/CSS/HTML 有内置诊断；打开文件后自动刷新）</div>}
                  {Object.entries(problemsMap).map(([f, list]) => (
                    <div key={f} className="mb-2">
                      <div className="text-ink-300 mb-0.5 truncate" title={f}>{f.replace(/^\/workspace\//, '')}</div>
                      {list.map((p, i) => (
                        <button key={i}
                          onClick={() => openRepoFile(f.replace(/^\/workspace\//, ''), p.line)}
                          className="w-full text-left pl-3 py-0.5 font-mono text-ink-400 hover:text-ink-100 hover:bg-tech-500/10 rounded truncate">
                          <span className={p.sev >= 8 ? 'text-cinnabar-400' : 'text-amber-400'}>{p.sev >= 8 ? '错误' : '警告'}</span>
                          {' 行 ' + p.line + '：'}{p.msg.slice(0, 140)}
                        </button>
                      ))}
                    </div>
                  ))}
                </div>
              )}
              {bottomTab === 'todo' && (
                <div className="flex-1 min-h-0 overflow-y-auto scrollbar-thin px-3 py-2 text-[11px]">
                  {todos.length === 0 && <div className="text-ink-500">未扫描到 TODO/FIXME/XXX/HACK 标记</div>}
                  {todos.map((t, i) => (
                    <button key={i}
                      onClick={() => openRepoFile(t.path, t.line)}
                      className="w-full text-left py-0.5 font-mono hover:bg-tech-500/10 rounded truncate flex gap-2">
                      <span className={`flex-shrink-0 font-semibold ${t.todoKind === 'TODO' ? 'text-sky-400' : t.todoKind === 'FIXME' ? 'text-cinnabar-400' : 'text-amber-400'}`}>{t.todoKind}</span>
                      <span className="text-ink-500 flex-shrink-0">{t.path}:{t.line}</span>
                      <span className="text-ink-300 truncate">{t.text.slice(0, 120)}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* 状态栏（IDEA 式）：语言 · 行列 · 编码缩进 · 主题切换（跟随聚焦组激活 Tab） */}
          <div className="flex items-center justify-between px-2 py-1 mt-2 flex-shrink-0 border-t border-tech-500/10 text-[10px] text-ink-500">
            <div className="flex items-center gap-3 min-w-0">
              <span className="truncate">{currentPath ? langLabelOf(currentPath) : '—'}</span>
              {cursor.line > 0 && <span className="flex-shrink-0">行 {cursor.line}，列 {cursor.col}</span>}
              {projectReady && currentPath && (
                <button onClick={toggleBlame} disabled={!activeTab || activeTab.loading}
                  className={`flex-shrink-0 hover:text-tech-400 disabled:opacity-50 ${blameOn ? 'text-tech-400' : ''}`} title="行级归属标注（IDEA Annotate）">
                  Annotate
                </button>
              )}
            </div>
            <div className="flex items-center gap-3 flex-shrink-0">
              <span>UTF-8</span>
              {activeTab && activeTab.kind !== 'binary' && (
                <button onClick={toggleEol} className="hover:text-tech-400 transition-colors" title="切换换行符（LF / CRLF，保存时保留）">
                  {currentEolLabel}
                </button>
              )}
              <button onClick={() => setSettingsOpen(true)}
                className="hover:text-tech-400 transition-colors" title="编辑器设置（Ctrl+Alt+S）：字号/缩进/软换行/空白符/自动保存">
                {editorSettings.tabSize} 空格缩进 · {editorSettings.fontSize}px
              </button>
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

      {/* 快速检索统一弹层（IDEA：文件/搜索/Everywhere/动作/位置/用法/符号/结构/书签） */}
      {quick && projectReady && selectedProject && (
        <DevQuickOpen
          project={selectedProject}
          mode={quick}
          initialQuery={pendingQuery}
          recentPaths={recentFiles}
          actions={quickActions}
          locations={recentLocations}
          bookmarks={Array.from(bookmarks).map(k => {
            const idx = k.lastIndexOf(':');
            return { rel: k.slice(0, idx), line: Number(k.slice(idx + 1)) || 0 };
          })}
          structure={currentStructure}
          structurePath={currentPath}
          queryRunner={quick === 'symbols' || quick === 'usages' || quick === 'everywhere' ? (quick === 'usages' ? runUsagesQuery : runSymbolsQuery) : undefined}
          onClose={() => { setQuick(null); setPendingQuery(''); restoreEditorFocus(); }}
          onOpenFile={openRepoFile}
        />
      )}

      {/* 全项目替换（IDEA Ctrl+Shift+R：预览 + 勾选替换） */}
      {replaceOpen && selectedProject && (
        <DevProjectReplaceDialog
          open={replaceOpen}
          project={selectedProject}
          onClose={() => setReplaceOpen(false)}
          onDone={() => { refreshGitStatus(); refreshTree(); }}
        />
      )}

      {/* 符号重命名（IDEA Shift+F6 词法近似：全项目词边界替换） */}
      {renamePrompt && (
        <Modal open onClose={() => setRenamePrompt(null)} zIndex="z-[88]" ariaLabel="重命名符号"
          panelClassName="glass-dark rounded-xl w-full max-w-sm p-4 border border-tech-500/15 shadow-2xl">
          <div className="text-sm font-semibold text-ink-50 mb-1">重命名符号「{renamePrompt.word}」</div>
          <div className="text-[10px] text-ink-500 mb-2">将全项目词边界替换所有出现处（含已打开的编辑器 Tab；词法级近似 IDEA Rename）</div>
          <input autoFocus value={renameInput} onChange={(e) => setRenameInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void doRenameSymbol(); }}
            className="w-full px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 outline-none focus:border-tech-500/40" />
          <div className="flex justify-end gap-2 mt-4">
            <button onClick={() => setRenamePrompt(null)} className="px-3 py-1.5 text-xs text-ink-300 hover:text-ink-100 transition-colors">取消</button>
            <button onClick={doRenameSymbol} className="px-3 py-1.5 text-xs font-medium text-white bg-tech-500 hover:bg-tech-600 rounded-lg transition-colors">重命名</button>
          </div>
        </Modal>
      )}

      {/* Ctrl+G 跳行浮层（IDEA Go to Line:Column） */}
      {gotoPrompt && (
        <div className="fixed inset-0 z-[87]" onClick={() => { setGotoPrompt(null); restoreEditorFocus(); }}>
          <div className="absolute left-1/2 top-16 -translate-x-1/2 dw-lightbar glass-dark rounded-lg border border-tech-500/15 p-3 w-[300px]"
            onClick={(e) => e.stopPropagation()}>
            <div className="text-[10px] text-ink-500 mb-2">跳转到行（格式：行号 或 行号:列号）</div>
            <input autoFocus value={gotoInput}
              onChange={(e) => setGotoInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') doGotoLine(); if (e.key === 'Escape') setGotoPrompt(null); }}
              placeholder="如 42 或 42:8"
              className="w-full px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 placeholder:text-ink-600 outline-none focus:border-tech-500/40" />
          </div>
        </div>
      )}

      {/* 多剪贴板粘贴（IDEA Ctrl+Shift+V：历史选择器） */}
      {clipPickerOpen && (
        <Modal open onClose={() => { setClipPickerOpen(false); restoreEditorFocus(); }} zIndex="z-[88]" ariaLabel="从历史粘贴"
          panelClassName="glass-dark rounded-xl w-full max-w-md h-[60vh] p-4 border border-tech-500/15 shadow-2xl flex flex-col">
          <div className="text-sm font-semibold text-ink-50 mb-2">从历史粘贴</div>
          <div className="text-[10px] text-ink-500 mb-2">最近 100 条复制/剪切内容（Enter 或点击插入光标处）</div>
          <div className="flex-1 min-h-0 overflow-y-auto scrollbar-thin space-y-0.5">
            {clipHistory.length === 0 && <div className="text-center py-8 text-ink-500 text-xs">暂无历史（复制内容后自动记录）</div>}
            {clipHistory.map((c, i) => (
              <button key={i} onClick={() => pasteFromHistory(c)}
                className="w-full text-left px-2 py-1.5 rounded-md text-[11px] font-mono text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all truncate border border-tech-500/5"
                title={c.slice(0, 500)}>
                <span className="text-ink-600 mr-1.5">{String(i + 1).padStart(2, '0')}</span>{c.replace(/\n/g, '⏎').slice(0, 120)}
              </button>
            ))}
          </div>
        </Modal>
      )}

      {/* Surround With 包裹菜单（IDEA Ctrl+Alt+T：通用花括号结构） */}
      {surroundOpen && (
        <Modal open onClose={() => { setSurroundOpen(false); restoreEditorFocus(); }} zIndex="z-[88]" ariaLabel="包裹"
          panelClassName="glass-dark rounded-xl w-full max-w-xs p-3 border border-tech-500/15 shadow-2xl">
          <div className="text-sm font-semibold text-ink-50 mb-2">包裹选中代码（Surround With）</div>
          <div className="space-y-0.5">
            {SURROUND_TEMPLATES.map(t => (
              <button key={t.key} onClick={() => doSurround(t.key)}
                className="w-full text-left px-2.5 py-1.5 rounded-md text-xs text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all font-mono">
                {t.label}
              </button>
            ))}
          </div>
          <div className="text-[10px] text-ink-600 mt-2">未选中代码时提示先选择（与 IDEA 一致）</div>
        </Modal>
      )}

      {/* 编辑器设置面板（IDEA Ctrl+Alt+S：字号/缩进/换行/空白符/自动保存等） */}
      <DevEditorSettingsDialog
        open={settingsOpen}
        settings={editorSettings}
        onChange={setEditorSettings}
        onClose={() => setSettingsOpen(false)}
      />

      {/* 编辑器右键菜单（中文；替代 Monaco 内置英文菜单；支持快捷键，剪/复/粘走 Monaco 剪贴板动作） */}
      {ctxMenu && (
        <>
          <div className="fixed inset-0 z-[85]" onClick={() => setCtxMenu(null)} onContextMenu={e => { e.preventDefault(); setCtxMenu(null); }} />
          <div className="fixed z-[86] min-w-[210px] glass-dark rounded-lg border border-tech-500/15 shadow-2xl py-1 text-xs"
            style={{ left: Math.min(ctxMenu.x, window.innerWidth - 230), top: Math.min(ctxMenu.y, window.innerHeight - 320) }}>
            <button onClick={() => menuAct('undo')} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>撤销</span><span className="text-[10px] text-ink-600">Ctrl+Z</span>
            </button>
            <button onClick={() => menuAct('redo')} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>重做</span><span className="text-[10px] text-ink-600">Ctrl+Shift+Z</span>
            </button>
            <div className="h-px bg-tech-500/10 my-1" />
            <button onClick={() => menuAct('editor.action.clipboardCutAction')} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>剪切</span><span className="text-[10px] text-ink-600">Ctrl+X</span>
            </button>
            <button onClick={() => menuAct('editor.action.clipboardCopyAction')} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>复制</span><span className="text-[10px] text-ink-600">Ctrl+C</span>
            </button>
            <button onClick={() => menuAct('editor.action.clipboardPasteAction')} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>粘贴</span><span className="text-[10px] text-ink-600">Ctrl+V</span>
            </button>
            <div className="h-px bg-tech-500/10 my-1" />
            <button onClick={() => { setCtxMenu(null); void gotoDefinition(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>跳转定义</span><span className="text-[10px] text-ink-600">Ctrl+B</span>
            </button>
            <button onClick={() => { setCtxMenu(null); void findUsages(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>查找用法</span><span className="text-[10px] text-ink-600">Alt+F7</span>
            </button>
            <button onClick={() => { setCtxMenu(null); openStructure(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>文件结构</span><span className="text-[10px] text-ink-600">Ctrl+F12</span>
            </button>
            <button onClick={() => { setCtxMenu(null); toggleBookmarkAt(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>切换书签</span><span className="text-[10px] text-ink-600">F11</span>
            </button>
            <button onClick={() => { setCtxMenu(null); setSurroundOpen(true); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>包裹选中代码…</span><span className="text-[10px] text-ink-600">Ctrl+Alt+T</span>
            </button>
            <button onClick={() => { setCtxMenu(null); formatActiveEditor(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>格式化代码</span><span className="text-[10px] text-ink-600">Ctrl+Alt+L</span>
            </button>
            <button onClick={() => { setCtxMenu(null); startRenameSymbol(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
              <span>重命名符号…</span><span className="text-[10px] text-ink-600">Shift+F6</span>
            </button>
            <div className="h-px bg-tech-500/10 my-1" />
            <button onClick={() => { setCtxMenu(null); void rollbackActiveTab(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3" title="还原到最近保存内容（丢弃未保存修改）">
              <span>回滚更改</span><span className="text-[10px] text-ink-600">本地</span>
            </button>
            <button onClick={() => { setCtxMenu(null); void rollbackFileToHead(); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3" title="git checkout 当前文件到 HEAD 版本（丢弃全部未提交修改）">
              <span>回滚到 HEAD</span><span className="text-[10px] text-ink-600">Git</span>
            </button>
            <div className="h-px bg-tech-500/10 my-1" />
            <button onClick={() => { setCtxMenu(null); setHistoryOpen(true); }} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all" title="查看本文件的最近提交与左右差异对比">
              历史版本
            </button>
            {mainRunInfo && (
              <>
                <div className="h-px bg-tech-500/10 my-1" />
                <button onClick={runMain} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all truncate" title={`java ${mainRunInfo.fqcn}`}>
                  运行主类
                </button>
                <button onClick={debugMain} className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all truncate" title={`调试 ${mainRunInfo.fqcn}`}>
                  调试主类
                </button>
              </>
            )}
          </div>
        </>
      )}

      {/* 文件历史版本（左列表右对照 diff） */}
      {historyOpen && selectedProject && currentPath && (
        <DevFileHistoryModal
          open={historyOpen}
          project={selectedProject}
          path={currentPath}
          worktreeContent={fileContent}
          theme={editorTheme}
          onClose={() => setHistoryOpen(false)}
        />
      )}

      {/* 文件树右键菜单（IDEA 项目视图风格；新建子菜单支持多文件类型模板） */}
      {treeMenu && (
        <>
          <div className="fixed inset-0 z-[85]" onClick={() => setTreeMenu(null)} onContextMenu={e => { e.preventDefault(); setTreeMenu(null); }} />
          <div className="fixed z-[86] min-w-[190px] glass-dark rounded-lg border border-tech-500/15 shadow-2xl py-1 text-xs"
            style={{ left: Math.min(treeMenu.x, window.innerWidth - 230), top: Math.min(treeMenu.y, window.innerHeight - 360) }}>
            {treeMenu.isDir && (
              <div className="relative">
                <button onClick={() => setTreeMenu({ ...treeMenu, sub: !treeMenu.sub })}
                  className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all flex items-center justify-between gap-3">
                  <span>新建</span><ChevronRight className="w-3 h-3" />
                </button>
                {treeMenu.sub && (
                  <div className="absolute left-full top-0 ml-0.5 min-w-[170px] max-h-80 overflow-y-auto scrollbar-thin glass-dark rounded-lg border border-tech-500/15 shadow-2xl py-1">
                    {NEW_FILE_TYPES.map(t => (
                      <button key={t.kind} onClick={() => { setNewFileDialog({ parentPath: treeMenu.path, type: t }); setNewFileName(''); setTreeMenu(null); }}
                        className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">
                        {t.label}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}
            <button onClick={() => { treeClipRef.current = { path: treeMenu.path, isDir: treeMenu.isDir, mode: 'cut' }; setTreeMenu(null); toast('已剪切', 'info'); }}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">剪切</button>
            <button onClick={() => { treeClipRef.current = { path: treeMenu.path, isDir: treeMenu.isDir, mode: 'copy' }; setTreeMenu(null); toast('已复制', 'info'); }}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">复制</button>
            <button onClick={treeCopyPath}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">复制路径</button>
            <button onClick={treePaste} disabled={!treeClipRef.current}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all disabled:opacity-40 disabled:pointer-events-none">粘贴</button>
            <button onClick={treeRename}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">重命名</button>
            <button onClick={treeDelete}
              className="w-full text-left px-3 py-1.5 text-cinnabar-400 hover:text-cinnabar-300 hover:bg-cinnabar-500/10 transition-all">删除</button>
            <div className="h-px bg-tech-500/10 my-1" />
            <button onClick={compareFromMenu}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all" title="标记后点击另一个同类对象进行对比（文件内容 diff / 目录清单 diff）">
              比较对象
            </button>
          </div>
        </>
      )}

      {/* 新建文件/目录（类型模板 + 名称输入） */}
      {newFileDialog && (
        <Modal open onClose={() => setNewFileDialog(null)} zIndex="z-[88]" ariaLabel="新建"
          panelClassName="glass-dark rounded-xl w-full max-w-sm p-4 border border-tech-500/15 shadow-2xl">
          <div className="text-sm font-semibold text-ink-50 mb-2">新建{newFileDialog.type.kind === 'dir' ? '目录' : newFileDialog.type.label}</div>
          <div className="text-[10px] text-ink-500 mb-2 truncate">位置：{displayPathOf(newFileDialog.parentPath, selectedProject?.localPath, selectedProject?.repoUrl)}</div>
          <input autoFocus value={newFileName} onChange={e => setNewFileName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') createNew(); }}
            placeholder={newFileDialog.type.kind === 'dir' ? '目录名称' : `文件名称（自动追加 ${newFileDialog.type.ext}）`}
            className="w-full px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 placeholder:text-ink-600 outline-none focus:border-tech-500/40" />
          <div className="flex justify-end gap-2 mt-4">
            <button onClick={() => setNewFileDialog(null)} className="px-3 py-1.5 text-xs text-ink-300 hover:text-ink-100 transition-colors">取消</button>
            <button onClick={createNew} className="px-3 py-1.5 text-xs font-medium text-white bg-tech-500 hover:bg-tech-600 rounded-lg transition-colors">创建</button>
          </div>
        </Modal>
      )}

      {/* 重命名（引用更新：路径/包名/类名） */}
      {renameDialog && (
        <Modal open onClose={() => setRenameDialog(null)} zIndex="z-[88]" ariaLabel="重命名"
          panelClassName="glass-dark rounded-xl w-full max-w-sm p-4 border border-tech-500/15 shadow-2xl">
          <div className="text-sm font-semibold text-ink-50 mb-2">重命名「{renameDialog.oldName}」</div>
          <div className="text-[10px] text-ink-500 mb-2">将自动搜索并更新所有引用该{renameDialog.isDir ? '目录（含包名）' : '文件（含类名）'}的位置</div>
          <input autoFocus value={renameName} onChange={e => setRenameName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') doRename(); }}
            className="w-full px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 outline-none focus:border-tech-500/40" />
          <div className="flex justify-end gap-2 mt-4">
            <button onClick={() => setRenameDialog(null)} className="px-3 py-1.5 text-xs text-ink-300 hover:text-ink-100 transition-colors">取消</button>
            <button onClick={doRename} className="px-3 py-1.5 text-xs font-medium text-white bg-tech-500 hover:bg-tech-600 rounded-lg transition-colors">重命名</button>
          </div>
        </Modal>
      )}

      {/* 比较对象选择框 */}
      {comparePicker && (
        <Modal open onClose={() => setComparePicker(null)} zIndex="z-[88]" ariaLabel="选择比较对象"
          panelClassName="glass-dark rounded-xl w-full max-w-md h-[70vh] p-4 border border-tech-500/15 shadow-2xl flex flex-col">
          <div className="text-sm font-semibold text-ink-50 mb-2">选择比较对象</div>
          <div className="text-[10px] text-ink-500 mb-2 truncate">
            与「{displayPathOf(comparePicker.a.path, selectedProject?.localPath, selectedProject?.repoUrl)}」比较（{comparePicker.kind === 'dir' ? '目录' : '文件'}）
          </div>
          <input autoFocus value={compareFilter} onChange={e => setCompareFilter(e.target.value)}
            placeholder="输入路径过滤…" className="mb-2 w-full px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 placeholder:text-ink-600 outline-none focus:border-tech-500/40" />
          <div className="flex-1 min-h-0 overflow-y-auto scrollbar-thin space-y-0.5">
            {comparePicker.options.filter(o => !compareFilter.trim() || o.includes(compareFilter.trim())).map(o => (
              <button key={o} onClick={() => { const a = comparePicker.a; setComparePicker(null); void openCompare(a, { path: o, isDir: comparePicker.kind === 'dir' }); }}
                className="w-full text-left px-2 py-1 rounded-md text-[11px] text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all truncate" title={o}>
                {o}
              </button>
            ))}
            {comparePicker.options.filter(o => !compareFilter.trim() || o.includes(compareFilter.trim())).length === 0 && (
              <div className="text-center py-6 text-ink-500 text-xs">无可比较对象</div>
            )}
          </div>
        </Modal>
      )}

      {/* 比较对象结果（版本历史式布局：分类清单 + 左右对照） */}
      {compareResult && (
        <DevCompareModal open={!!compareResult} result={compareResult} theme={editorTheme} onClose={() => setCompareResult(null)} />
      )}

      {/* 运行/调试配置对话框（IDEA 式：类型/主类/JVM/程序参数/构建命令） */}
      <DevRunConfigDialog
        open={runConfOpen}
        conf={selectedProject ? loadRunConf(selectedProject.projectId) : null}
        onClose={() => setRunConfOpen(false)}
        onSave={(c) => {
          if (selectedProject) saveRunConf(selectedProject.projectId, c);
          setRunConfOpen(false);
          toast('运行/调试配置已保存', 'success');
        }}
      />

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
