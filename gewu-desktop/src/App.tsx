import React, { useCallback, useEffect, useRef, useState } from 'react';
import { EngineConnection, waitEngineReady } from './engine';
import {
  generateClientId,
  type ChatStreamCallbacks,
  type PlanStepInfo,
} from '@gewu/agent-ui/protocol';
import { streamChat, createSessionFileChangesApi } from '@gewu/agent-ui/api-client';
import { AIProcessTimeline } from '@gewu/agent-ui/chat-ui/timeline';
import { createProcessStreamHandler, hasProcessActivity,
  ProcessTracker, type ProcessItem, type ProcessSnapshot } from '@gewu/agent-ui/process';
import { MarkdownRenderer } from '@gewu/agent-ui/ui';
import { SessionSidebar } from '@gewu/agent-ui/chat-ui/sidebar';
import { PlanCard } from '@gewu/agent-ui/chat-ui/plan';
import { FileEditorPanel, FILE_PANEL_MIN_WIDTH } from '@gewu/agent-ui/chat-ui/filepanel';
import type { ApprovalRequestPayload } from '@gewu/agent-ui/protocol';
import type { FileChangeDTO } from '@gewu/agent-ui/api-client';
import ApprovalDialog from './components/ApprovalDialog';
import SettingsPanel from './components/SettingsPanel';
import TerminalPanel from './components/TerminalPanel';
import OrchestrationWorkbench from './components/OrchestrationWorkbench';
import DevWorkspaceView from './components/DevWorkspaceView';
import TitleBar from './components/TitleBar';
import SplashScreen from './components/SplashScreen';
import Onboarding from './components/Onboarding';
import './styles.css';

// ============ 辅助：尾部正文拆分 ============
// 语义对齐共享包 ProcessTracker.splitFinalContent（用户实测根修）：
// 最后一个过程条目（思考/工具/搜索/子代理）之后的正文段 = 最终气泡文本（任务最终汇总）；
// 其前的正文段保留在时间线内原位置。此前本地实现误把 finalText 算成「full 去掉尾部」——
// 导致中间正文在时间线与气泡双份、最终总结被丢弃不显示。
function splitFinal(items: ProcessItem[]): { items: ProcessItem[]; finalText: string } {
  let lastProcessIdx = -1;
  items.forEach((it, i) => { if (it.kind !== 'content') lastProcessIdx = i; });
  const tl: ProcessItem[] = [];
  let finalText = '';
  items.forEach((it, i) => {
    if (i <= lastProcessIdx) tl.push(it);
    else if (it.kind === 'content') finalText += (it as { text: string }).text;
  });
  return { items: tl, finalText };
}

// ============ 桌面工作台（D1-24）：共享组件族接入 ============
// 时间线（AIProcessTimeline）/ 会话侧栏（SessionSidebar）/ 任务卡片（PlanCard）/
// 文件变更面板（FileEditorPanel，写文件后自动出现 +N/-N 与 diff 审查）——全部来自共享包。

type SessionItem = import('@gewu/agent-ui/protocol').SessionDTO;
interface MessageItem {
  role: 'user' | 'assistant'; content: string;
  process?: ProcessItem[]; processMs?: number; startAt?: number;
  stats?: { rounds?: number; elapsedMs?: number; tokenEstimated?: number };
  /** 流中排队的新指令(uid 关联队列,本轮完成后自动发送) */
  queued?: number;
}
interface GitInfo { branch: string | null }
interface ModelItem { provider: string; modelName: string; displayName: string; supported: boolean }
/** 本地项目：协议 ProjectDTO（引擎补默认值）+ 桌面扩展 directory（项目绑定目录=项目下会话工作区） */
type ProjectItem = import('@gewu/agent-ui/protocol').ProjectDTO & { directory?: string };
/** 本地智能体(会话智能体选择器数据源;引擎 /v1/agents) */
interface AgentItem { agentId: string; name: string; modelProvider?: string; modelName?: string }

export default function App() {
  const connRef = useRef<EngineConnection>(new EngineConnection());
  const [engineState, setEngineState] = useState<string>('booting');
  const [engineError, setEngineError] = useState<string | null>(null);
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [archived, setArchived] = useState<SessionItem[]>([]);
  const [showArchived, setShowArchived] = useState(false);
  const [activeSession, setActiveSession] = useState<string | null>(null);
  const [messages, setMessages] = useState<MessageItem[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [models, setModels] = useState<ModelItem[]>([]);
  const [projects, setProjects] = useState<ProjectItem[]>([]);
  const [agents, setAgents] = useState<AgentItem[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<string>('');
  const [wsErr, setWsErr] = useState<string | null>(null);
  // S9 工作台状态
  const [plan, setPlan] = useState<{ title: string; steps: PlanStepInfo[]; planPath?: string } | null>(null);
  const [fileChanges, setFileChanges] = useState<FileChangeDTO[]>([]);
  const [filePanelOpen, setFilePanelOpen] = useState(false);
  const [terminalOpen, setTerminalOpen] = useState(false);
  const [gitBranch, setGitBranch] = useState<string | null>(null);
  const [workspace, setWorkspace] = useState<{ basename: string | null; dir?: string | null }>({ basename: null });
  const [pendingApproval, setPendingApproval] = useState<ApprovalRequestPayload | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsSection, setSettingsSection] = useState<'mode' | 'models' | 'agents' | 'mcp' | 'skills'>('models');
  // 视图状态：home（主页空态）/ workbench（工作台）/ orchestration（编排工作台）
  const [view, setView] = useState<'home' | 'workbench' | 'orchestration' | 'dev'>('home');
  const bottomRef = useRef<HTMLDivElement>(null);
  // 时间线展开态（按消息索引）：流中最后一条默认展开（实时可见），完成后折叠可点击
  const [expandedMsgs, setExpandedMsgs] = useState<Record<number, boolean>>({});
  // 子代理子流（S9 二期，对齐 web）：spawn_subagents 派生的子任务按 subagentId 分桶，
  // 主时间线子代理行可点击，右侧面板展示该子代理的流式时间线与聚合结果
  const subTrackersRef = useRef(new Map<string, ProcessTracker>());
  const subNamesRef = useRef(new Map<string, string>());
  const [subProcesses, setSubProcesses] = useState<Record<string, { name: string; snapshot: ProcessSnapshot }>>({});
  const [activeSubagent, setActiveSubagent] = useState<{ id: string; name: string; result?: string } | null>(null);
  const [subPanelOpen, setSubPanelOpen] = useState(false);
  // 使用模式(首次引导/本地与平台切换):loading=读配置;onboarding=未配置引导;app=正常应用
  const [launchPhase, setLaunchPhase] = useState<'loading' | 'onboarding' | 'app'>('loading');
  const [onboardingBusy, setOnboardingBusy] = useState(false);
  const [onboardingError, setOnboardingError] = useState<string | null>(null);
  // 授权挂起(用户实测:离开超时回来点授权,前端假死):倒计时到点/流中断线 → 挂起态,
  // 授权后基于会话进度自动续跑(用户无感);引擎超时(35min)大于前端挂起点(30min)留出无缝恢复窗口
  const [approvalSuspended, setApprovalSuspended] = useState(false);
  // 流中指令排队(用户实测:执行中无法发新指令):streaming 期间发送的指令入队,
  // 当前轮完成后基于已落库的完整结果自动整合发送
  const queueRef = useRef<Array<{ uid: number; text: string }>>([]);
  const qseqRef = useRef(0);
  const streamingRef = useRef(false);
  const flushQueueRef = useRef<() => Promise<void>>(async () => {});

  const ready = engineState === 'ready';

  // ============ 数据刷新 ============
  const refreshSessions = useCallback(async (withArchived = true) => {
    try {
      setSessions((await connRef.current.data('/v1/ai/sessions')) as SessionItem[]);
      if (withArchived) {
        setArchived((await connRef.current.data('/v1/ai/sessions?status=2')) as SessionItem[]);
      }
    } catch { /* 状态条已反映 */ }
  }, []);

  const refreshModels = useCallback(async () => {
    try {
      setModels((await connRef.current.data('/v1/ai/models')) as ModelItem[]);
    } catch { /* 忽略 */ }
  }, []);

  const refreshProjects = useCallback(async () => {
    try { setProjects((await connRef.current.data('/v1/projects')) as ProjectItem[]); } catch { /* 忽略 */ }
  }, []);

  const refreshAgents = useCallback(async () => {
    try { setAgents((await connRef.current.data('/v1/agents')) as AgentItem[]); } catch { /* 忽略 */ }
  }, []);

  const refreshFileChanges = useCallback(async (sessionId: string) => {
    try {
      setFileChanges((await connRef.current.data(`/v1/sessions/${sessionId}/file-changes`)) as FileChangeDTO[]);
    } catch { /* 忽略 */ }
  }, []);

  // 引擎连接:仅在进入应用阶段(引导完成/已配置 local)后建立——
  // 引导选择「本地」时引擎才由主进程启动,此刻开始轮询接管
  useEffect(() => {
    if (launchPhase !== 'app') return;
    waitEngineReady()
      .then((info) => { connRef.current.update(info); setEngineState('ready'); })
      .catch((e) => setEngineError(String(e)));
    const off = window.gewu.onEngineStatus((s) => {
      if (s.info) connRef.current.update(s.info);
      setEngineState(s.state);
      if (s.state === 'ready') { refreshSessions(); refreshModels(); }
    });
    return off;
  }, [launchPhase, refreshSessions, refreshModels]);

  // 启动时读取使用模式配置:未配置 → 引导页
  useEffect(() => {
    window.gewu.getLaunchConfig()
      .then((cfg) => setLaunchPhase(cfg.mode === 'unconfigured' ? 'onboarding' : 'app'))
      .catch(() => setLaunchPhase('app')); // IPC 不可用(浏览器调试)直接进应用
  }, []);

  useEffect(() => {
    if (ready) { refreshSessions(); refreshModels(); refreshProjects(); refreshAgents(); }
  }, [ready, refreshSessions, refreshModels, refreshProjects, refreshAgents]);

  // 进入对话视图时刷新项目清单（项目可能在别处/对话框外创建——侧栏分组保持新鲜）
  useEffect(() => {
    if (ready && view === 'workbench') { refreshProjects(); }
  }, [ready, view, refreshProjects]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  // 视觉回归测试钩子（GEWU_VISUAL_TEST 模式驱动；恒更新闭包）
  useEffect(() => {
    (window as unknown as Record<string, unknown>).__gewu_test = {
      newSession,
      send: (t: string) => send(t),
      openSession: (id: string) => openSession(id),
      seedAndOpen: async () => {
        // 由主进程完成种子写入；此处仅刷新并打开首个会话
        await refreshSessions(false);
        await refreshFileChanges(activeSession ?? sessions[0]?.sessionId ?? '');
      },
      view,
      sessionCount: sessions.length,
      projectsCount: projects.length,
    };
  });

  // ============ 会话操作 ============
  const openSession = useCallback(async (sessionId: string) => {
    setView('workbench');
    setActiveSession(sessionId); setMessages([]); setPlan(null);
    subTrackersRef.current.clear(); subNamesRef.current.clear();
    setSubProcesses({}); setActiveSubagent(null); setSubPanelOpen(false);
    const list = (await connRef.current.data(`/v1/ai/sessions/${sessionId}/messages`)) as Array<{
      role: string; content: string; metadata?: string | null;
    }>;
    setMessages(list.map((m) => {
      let process: ProcessItem[] | undefined;
      let stats: MessageItem['stats'] | undefined;
      if (m.metadata) {
        try {
          const meta = JSON.parse(m.metadata) as {
            process?: ProcessItem[]; processMs?: number; stats?: MessageItem['stats'];
          };
          if (Array.isArray(meta.process) && meta.process.length > 0) process = meta.process;
          if (meta.stats) stats = meta.stats;
        } catch { /* metadata 非 JSON：忽略 */ }
      }
      return { role: m.role as 'user' | 'assistant', content: m.content, process, stats };
    }));
    // 会话清单先刷新再查标题：外部/刚种子创建的会话不在打开前的列表快照里，
    // 否则标题与侧栏都会回落为「会话」占位
    let sessList = sessions;
    try {
      sessList = (await connRef.current.data('/v1/ai/sessions')) as SessionItem[];
      setSessions(sessList);
      setArchived((await connRef.current.data('/v1/ai/sessions?status=2')) as SessionItem[]);
    } catch { /* 用快照兜底 */ }
    try {
      const gi = (await connRef.current.data(`/v1/sessions/${sessionId}/git-info`)) as GitInfo;
      setGitBranch(gi.branch ?? null);
    } catch { setGitBranch(null); }
    const sess = sessList.find((s) => s.sessionId === sessionId);
    const dir = sess?.directory ?? null;
    setWorkspace({ basename: dir ? dir.split(/[\\/]/).filter(Boolean).pop() || 'workspace' : 'workspace', dir });
    refreshFileChanges(sessionId);
    refreshProjects(); // 项目清单保新(项目可在其他时机/窗口创建——侧栏分组与标题保持新鲜)
  }, [refreshFileChanges, refreshProjects]);

  const newSession = useCallback(() => {
    setView('workbench');
    setActiveSession(null); setMessages([]); setPlan(null); setFileChanges([]); setFilePanelOpen(false);
  }, []);

  // ============ 新建会话对话框（工作区选择器，Phase 1 遗留补齐；D1-25 项目上下文扩展） ============
  const [wsOpen, setWsOpen] = useState(false);
  const [wsForm, setWsForm] = useState<{ title: string; directory: string | null; projectId: string | null }>(
    { title: '', directory: null, projectId: null });
  // 项目上下文新建（侧栏项目行「＋」）：目录锁定为项目绑定目录；null=默认空间（可选目录）
  const openWsDialog = useCallback((projectId: string | null = null) => {
    const proj = projectId ? projects.find((p) => p.projectId === projectId) : null;
    setWsForm({ title: '', directory: proj?.directory ?? null, projectId });
    setWsErr(null); setWsOpen(true);
  }, [projects]);
  const createWsSession = useCallback(async () => {
    try {
      const body: Record<string, unknown> = { title: wsForm.title.trim() || '新会话' };
      if (wsForm.projectId) body.projectId = wsForm.projectId;
      if (wsForm.directory) body.directory = wsForm.directory;
      const env = await connRef.current.fetch('/v1/ai/sessions', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      if (!env.ok) throw new Error(`HTTP ${env.status}`);
      const data = (await env.json()) as { data?: { sessionId?: string } };
      const sid = data.data?.sessionId;
      setWsOpen(false);
      refreshSessions();
      if (sid) openSession(sid); else newSession();
    } catch (e) {
      setWsErr(`创建失败: ${e instanceof Error ? e.message : String(e)}`);
    }
  }, [wsForm, refreshSessions, openSession, newSession]);

  const archiveSession = useCallback(async (id: string) => {
    await connRef.current.fetch(`/v1/ai/sessions/${id}/archive`, { method: 'PUT' });
    refreshSessions();
  }, [refreshSessions]);

  const unarchiveSession = useCallback(async (id: string) => {
    await connRef.current.fetch(`/v1/ai/sessions/${id}/unarchive`, { method: 'PUT' });
    refreshSessions();
  }, [refreshSessions]);

  const pinSession = useCallback(async (id: string, pinned: boolean) => {
    await connRef.current.fetch(`/v1/ai/sessions/${id}/pin?pinned=${pinned}`, { method: 'PUT' });
    refreshSessions();
  }, [refreshSessions]);

  // ============ 新建项目对话框（D1-25：项目=绑定本地目录，会话经项目关联工作区） ============
  const [projOpen, setProjOpen] = useState(false);
  const [projForm, setProjForm] = useState<{ name: string; directory: string | null }>({ name: '', directory: null });
  const [projErr, setProjErr] = useState<string | null>(null);
  const openProjDialog = useCallback(() => {
    setProjForm({ name: '', directory: null }); setProjErr(null); setProjOpen(true);
  }, []);
  const createProject = useCallback(async () => {
    try {
      if (!projForm.directory) throw new Error('项目必须绑定本地目录');
      const env = await connRef.current.fetch('/v1/projects', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: projForm.name.trim() || undefined, directory: projForm.directory }),
      });
      if (!env.ok) {
        const envJson = (await env.json().catch(() => ({}))) as { message?: string };
        throw new Error(envJson.message || `创建失败（HTTP ${env.status}）——该目录可能已被其他项目绑定`);
      }
      setProjOpen(false);
      refreshProjects();
    } catch (e) {
      setProjErr(e instanceof Error ? e.message : String(e));
    }
  }, [projForm, refreshProjects]);

  const activeTitle = activeSession
    ? (sessions.find((s) => s.sessionId === activeSession)?.title ?? '会话')
    : '新对话';

  // ============ 会话尾部文件变更条（用户指定：会话结束在最后显示修改的文件） ============
  const [turnChangesOpen, setTurnChangesOpen] = useState(false);
  const [undoArmed, setUndoArmed] = useState(false);
  const undoAllChanges = useCallback(async () => {
    if (!activeSession) return;
    if (!undoArmed) {
      // 二段式确认：首次点击进入待确认态，3 秒未跟点则复位
      setUndoArmed(true);
      setTimeout(() => setUndoArmed(false), 3000);
      return;
    }
    setUndoArmed(false);
    try {
      const api = createSessionFileChangesApi(connRef.current.transportFor()!);
      await api.undoFileChanges(activeSession); // 引擎语义：before 快照回写，空 paths=全部
      refreshFileChanges(activeSession);
    } catch { /* 逐路径结果由引擎兜底；失败项保留在列表中 */ }
  }, [activeSession, undoArmed, refreshFileChanges]);

  // ============ 发送（过程时间线 + 任务计划 + 变更追踪） ============
  const setStreamingState = (v: boolean) => { streamingRef.current = v; setStreaming(v); };

  // 核心执行（text 已定）：skipUserBubble=气泡已在（排队指令/无感续跑），不再重复追加
  const sendCore = useCallback(async (text: string, opts?: { skipUserBubble?: boolean }) => {
    if (!text.trim() || streamingRef.current) return;
    setView('workbench'); // 从主页直接发送（测试钩子/快捷发送）也进入工作台
    if (!opts?.skipUserBubble) {
      setMessages((prev) => [...prev, { role: 'user', content: text }, { role: 'assistant', content: '' }]);
    } else {
      setMessages((prev) => [...prev, { role: 'assistant', content: '' }]);
    }
    setStreamingState(true);
    const clientId = generateClientId();
    const startedAt = Date.now();

    // 过程时间线（共享包 ProcessTracker）
    const { tracker, handlers } = createProcessStreamHandler((snap) => {
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = { ...last, process: snap.items, processMs: snap.elapsedMs, startAt: snap.startedAt };
        }
        return next;
      });
    });
    // 子代理事件不经过 handlers 的 notify，手动推送主时间线快照（子代理行实时出现/状态更新）
    const pushProcessSnapshot = () => {
      const snap = tracker.snapshot();
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = { ...last, process: snap.items, processMs: snap.elapsedMs, startAt: snap.startedAt };
        }
        return next;
      });
    };

    let contentStartedFlag = false;
    let doneStats: MessageItem['stats'] | undefined;
    const callbacks: ChatStreamCallbacks = {
      ...handlers,
      // 内容片段喂给过程时间线（对齐 web：图文交错；首块前触发 contentStarted）
      onContent: (t: string) => {
        if (!contentStartedFlag) {
          handlers.onContentStarted();
          contentStartedFlag = true;
        }
        tracker.addContent(t);
        setMessages((prev) => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (last?.role === 'assistant') {
            next[next.length - 1] = { ...last, content: last.content + t };
          }
          return next;
        });
      },
      // 截断重试：清空正文段与气泡正文（过程行保留，S9-M0 语义）
      onContentReset: () => {
        try { tracker.resetContent(); } catch { /* 忽略 */ }
        setMessages((prev) => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (last?.role === 'assistant') next[next.length - 1] = { ...last, content: '' };
          return next;
        });
      },
      onExecutionStats: (stats) => { doneStats = stats; },
      // 子代理生命周期（对齐 web）：主时间线生命周期行 + 子流桶创建/完结
      onSubagentStatus: (st) => {
        if (st.status === 'running') {
          tracker.addSubagent(st.subagentId, st.name);
          if (!subTrackersRef.current.has(st.subagentId)) {
            subTrackersRef.current.set(st.subagentId, new ProcessTracker());
            subNamesRef.current.set(st.subagentId, st.name);
          }
        } else {
          tracker.updateSubagent(st.subagentId, { status: st.status, result: st.result });
          const t = subTrackersRef.current.get(st.subagentId);
          if (t) t.finish();
          setActiveSubagent((prev) => prev && prev.id === st.subagentId ? { ...prev, result: st.result } : prev);
        }
        pushProcessSnapshot();
      },
      // 子代理分支事件：路由到对应子流桶（右侧面板同款时间线流式渲染）
      onSubagentEvent: (subagentId, ev) => {
        let t = subTrackersRef.current.get(subagentId);
        if (!t) {
          t = new ProcessTracker();
          subTrackersRef.current.set(subagentId, t);
          subNamesRef.current.set(subagentId, subagentId);
        }
        if (ev.type === 'content' && ev.content) t.addContent(ev.content);
        else if (ev.type === 'thinking' && ev.reasoning) t.addThinking(ev.reasoning);
        else if (ev.type === 'tool_call' && ev.toolCall) t.addTool(ev.toolCall);
        else if (ev.type === 'tool_executing' && ev.toolCall) t.toolExecuting(ev.toolCall.id);
        else if (ev.type === 'tool_result' && ev.toolResult) t.toolResult(ev.toolResult.toolCallId, ev.toolResult.output);
        else if (ev.type === 'status' && ev.content) t.setStatus(ev.content);
        setSubProcesses((prev) => ({
          ...prev,
          [subagentId]: { name: subNamesRef.current.get(subagentId) || subagentId, snapshot: t!.snapshot() },
        }));
      },
      onPlan: (title: string, steps: PlanStepInfo[], planPath?: string) => setPlan({ title, steps, planPath }),
      onApprovalRequired: (req: ApprovalRequestPayload) => { setPendingApproval(req); setApprovalSuspended(false); },
      onError: (msg: string) => setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = { ...last, content: last.content + `\n\n[错误] ${msg}` };
        }
        return next;
      }),
    };
    try {
      // 连接自愈（401/网络错误刷新令牌重试）由 EngineConnection.streamChat 内部处理
      await connRef.current.streamChat(
        '/v1/ai/chat/stream',
        {
          message: text, sessionId: activeSession ?? undefined, clientId,
          agentId: selectedAgentId || undefined,
        },
        callbacks
      );
      // 收尾：时间线定格 + 过程快照回写（重进会话还原时间线——用户实测问题1）
      const items = tracker.snapshot().items;
      const totalMs = Date.now() - startedAt;
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = { ...last, process: items, processMs: totalMs, startAt: startedAt };
        }
        return next;
      });
      try {
        const sidForPersist = activeSession ?? ((await connRef.current.data('/v1/ai/sessions')) as SessionItem[])[0]?.sessionId;
        if (sidForPersist) {
          await connRef.current.fetch(`/v1/ai/sessions/${sidForPersist}/messages/last/process`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ process: items, processMs: totalMs, stats: doneStats }),
          });
        }
      } catch { /* 回写失败不影响本轮展示 */ }
      refreshSessions();
      const target = activeSession ?? sessions[0]?.sessionId ?? null;
      if (target) refreshFileChanges(target);
    } catch (e) {
      callbacks.onError?.(String(e));
    } finally {
      setStreamingState(false);
      // 本轮结果已落库:自动发送排队中的新指令(基于完整历史整合执行,对用户无感)
      void flushQueueRef.current();
    }
  }, [activeSession, sessions, refreshSessions, refreshFileChanges, selectedAgentId]);

  // 降级排队:steer 不可用(无活动流/队列满/网络失败)时,消息标记「排队中」由流结束冲刷发送
  const enqueueLocal = useCallback((text: string) => {
    const uid = ++qseqRef.current;
    queueRef.current.push({ uid, text });
    setMessages((prev) => [...prev, { role: 'user', content: text, queued: uid }]);
  }, []);

  // 执行中转向(对齐 web):消息落库+入队引擎转向队列,ReAct 轮次边界与既有结果整合继续(不中断不重开)
  const steerRunningTurn = useCallback(async (text: string) => {
    if (!activeSession) { enqueueLocal(text); return; }
    try {
      const env = await connRef.current.fetch(`/v1/ai/sessions/${activeSession}/steer`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: text, clientId: generateClientId() }),
      });
      const data = (await env.json().catch(() => ({}))) as { data?: { accepted?: boolean; reason?: string } };
      if (env.ok && (data.data?.accepted ?? false)) {
        // 引擎侧已落库:前端补本地气泡;引擎将在下一轮次边界注入并发 STATUS 提示
        setMessages((prev) => [...prev, { role: 'user', content: text }]);
        return;
      }
      enqueueLocal(text);
    } catch {
      enqueueLocal(text);
    }
  }, [activeSession, enqueueLocal]);

  // 用户交互入口:流式期间发送 → 执行中转向(注入当前任务);降级路径=本地排队
  const send = useCallback((preset?: string, opts?: { skipUserBubble?: boolean }) => {
    const text = (preset ?? input).trim();
    if (!text) return;
    if (streamingRef.current) {
      setInput('');
      void steerRunningTurn(text);
      return;
    }
    setInput('');
    void sendCore(text, opts);
  }, [input, sendCore, steerRunningTurn]);

  // 队列冲刷:逐条发送(串行;每条发送前解除气泡的排队标记)
  const flushQueue = useCallback(async () => {
    while (queueRef.current.length > 0) {
      const item = queueRef.current.shift()!;
      setMessages((prev) => prev.map((m) => (m.queued === item.uid ? { ...m, queued: undefined } : m)));
      await sendCore(item.text, { skipUserBubble: true });
    }
  }, [sendCore]);
  flushQueueRef.current = flushQueue;

  // ============ 供应商管理（已整合进统一设置面板 SettingsPanel） ============

  const decideApproval = useCallback(async (approvalId: string, approve: boolean, alwaysAllow: boolean) => {
    setPendingApproval(null);
    setApprovalSuspended(false);
    let decided = false;
    try {
      const env = await connRef.current.fetch(`/v1/approvals/${approvalId}/decide`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approve, alwaysAllow }),
      });
      decided = env.ok;
    } catch { decided = false; }
    // 无感恢复:挂起态下流已终止(引擎授权等待超时收尾/记录已清除)→ 基于会话进度自动续跑;
    // 若决策命中仍在等待的引擎挂起(前端 30 分钟挂起点 < 引擎 35 分钟超时),流无缝继续,无需重放
    if (approve && (!decided || !streamingRef.current)) {
      void sendCore('任务此前因等待授权而暂停，用户已确认授权该操作，请基于已完成的进度继续执行未完成的部分。', { skipUserBubble: true });
    }
  }, [sendCore]);

  // ============ 首次启动引导(未配置使用模式) ============
  // ============ 首次启动引导:选择使用模式 ============
  const finishOnboarding = async (mode: 'local' | 'cloud', cloudUrl?: string) => {
    setOnboardingBusy(true); setOnboardingError(null);
    try {
      const res = await window.gewu.saveLaunchConfig(mode === 'local' ? { mode } : { mode, cloudUrl });
      if (res.mode === 'local') {
        setLaunchPhase('app'); // 本地引擎此刻启动,splash → waitEngineReady 接管
      }
      // cloud:主进程已重建窗口直接加载平台界面,渲染层随窗口销毁
    } catch (e) {
      setOnboardingError(e instanceof Error ? e.message : String(e));
      setOnboardingBusy(false);
    }
  };

  if (launchPhase === 'loading') return null;
  if (launchPhase === 'onboarding') {
    return (
      <Onboarding
        busy={onboardingBusy}
        error={onboardingError}
        onLocal={() => finishOnboarding('local')}
        onCloud={(url) => finishOnboarding('cloud', url)}
      />
    );
  }

  // ============ 引擎未就绪态 ============
  if (!ready) {
    return (
      <div className="app-root">
        <TitleBar />
        <SplashScreen
          state={engineState}
          error={engineError}
          onRestart={() => window.gewu.restartEngine()}
        />
      </div>
    );
  }

  const lastMsg = messages[messages.length - 1];
  const streamingLast = streaming && lastMsg?.role === 'assistant';
  const workspaceName = workspace.basename || null;
  const totalAdd = fileChanges.reduce((s, c) => s + (c.additions || 0), 0);
  const totalDel = fileChanges.reduce((s, c) => s + (c.deletions || 0), 0);

  // 标题栏右侧动作图标（用户指定：编排/设置/终端移至最顶端紧靠窗控，hover 中文提示）
  const titlebarActions = (
    <>
      <button className="tbtn action" title="编排工作台" aria-label="编排工作台"
        onClick={() => setView('orchestration')}>
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor">
          <rect x="0.5" y="4.5" width="4" height="3" />
          <rect x="7.5" y="0.5" width="4" height="3" />
          <rect x="7.5" y="8.5" width="4" height="3" />
          <path d="M4.5 6 H6 V2 H7.5 M6 6 V10 H7.5" />
        </svg>
      </button>
      <button className="tbtn action" title="设置" aria-label="设置"
        onClick={() => setSettingsOpen(true)}>
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor">
          <circle cx="6" cy="6" r="1.8" />
          <path d="M6 0.8 V2.4 M6 9.6 V11.2 M0.8 6 H2.4 M9.6 6 H11.2 M2.3 2.3 L3.4 3.4 M8.6 8.6 L9.7 9.7 M9.7 2.3 L8.6 3.4 M3.4 8.6 L2.3 9.7" />
        </svg>
      </button>
      <button className="tbtn action" title={terminalOpen ? '收起终端' : '终端'} aria-label="终端"
        onClick={() => setTerminalOpen((v) => !v)}>
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor">
          <rect x="0.5" y="1.5" width="11" height="9" />
          <path d="M2.5 4.5 L4.5 6 L2.5 7.5 M5.5 8 H9" />
        </svg>
      </button>
    </>
  );
  // 统一外壳：自绘标题栏 + 视图内容 + 全局浮层（新建项目对话框——三视图侧栏入口共用）
  const withChrome = (node: React.ReactNode) => (
    <div className="app-root">
      <TitleBar actions={titlebarActions} />
      {node}
      {projOpen && (
        <div className="overlay" onClick={() => setProjOpen(false)}>
          <div className="dialog ws-dialog" onClick={(e) => e.stopPropagation()}>
            <div className="dialog-head">
              <span className="dialog-title">新建项目</span>
              <span className="dialog-badge">绑定目录</span>
            </div>
            <p className="dialog-sub">项目绑定一个本地目录——该项目下的会话以此目录为工作区进行文件、终端与 Git 操作。</p>
            <input className="ws-input" placeholder="项目名称（可选，默认取目录名）" value={projForm.name}
              onChange={(e) => setProjForm((f) => ({ ...f, name: e.target.value }))} />
            <div className="ws-dir-row">
              {projForm.directory
                ? <span className="dialog-path">{projForm.directory}</span>
                : <span className="hint">请选择项目目录（必选）</span>}
            </div>
            {projErr && <p className="error">{projErr}</p>}
            <div className="dialog-actions ws-actions">
              <button className="btn" onClick={async () => {
                const dir = await window.gewu.chooseDirectory();
                if (dir) setProjForm((f) => ({ ...f, directory: dir }));
              }}>选择目录</button>
              {projForm.directory && (
                <button className="btn small" onClick={() => setProjForm((f) => ({ ...f, directory: null }))}>清除</button>
              )}
              <span style={{ flex: 1 }} />
              <button className="btn" onClick={() => setProjOpen(false)}>取消</button>
              <button className="btn primary" disabled={!projForm.directory} onClick={createProject}>创建</button>
            </div>
          </div>
        </div>
      )}
      {/* 新建会话对话框（全局——三视图侧栏/主页入口共用；项目上下文时目录锁定为项目绑定目录） */}
      {wsOpen && (
        <div className="overlay" onClick={() => setWsOpen(false)}>
          <div className="dialog ws-dialog" onClick={(e) => e.stopPropagation()}>
            <div className="dialog-head">
              <span className="dialog-title">新建会话</span>
              <span className="dialog-badge">工作区</span>
            </div>
            <p className="dialog-sub">{wsForm.projectId
              ? <>项目 <b>{projects.find((p) => p.projectId === wsForm.projectId)?.projectName ?? '已选项目'}</b>——会话工作区为项目绑定目录。</>
              : '绑定本地目录后，文件工具、终端与 Git 信息将以该目录为工作区。'}</p>
            <input className="ws-input" placeholder="会话标题（可选，默认「新会话」）" value={wsForm.title}
              onChange={(e) => setWsForm((f) => ({ ...f, title: e.target.value }))} />
            <div className="ws-dir-row">
              {wsForm.directory
                ? <span className="dialog-path">{wsForm.directory}</span>
                : <span className="hint">未选择目录：使用引擎默认工作区</span>}
            </div>
            {wsErr && <p className="error">{wsErr}</p>}
            <div className="dialog-actions ws-actions">
              {!wsForm.projectId && (
                <button className="btn" onClick={async () => {
                  const dir = await window.gewu.chooseDirectory();
                  if (dir) setWsForm((f) => ({ ...f, directory: dir }));
                }}>选择目录</button>
              )}
              {!wsForm.projectId && wsForm.directory && (
                <button className="btn small" onClick={() => setWsForm((f) => ({ ...f, directory: null }))}>清除</button>
              )}
              <span style={{ flex: 1 }} />
              <button className="btn" onClick={() => setWsOpen(false)}>取消</button>
              <button className="btn primary" onClick={createWsSession}>创建</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
  // 会话名称顶部信息带展示截断：超过 18 字符省略（用户指定）
  const headTitle = activeTitle.length > 18 ? `${activeTitle.slice(0, 18)}…` : activeTitle;

  // 侧栏常驻导航（用户实测：入口不应只藏在主页卡片——三个视图均在侧栏顶部可见）
  const sideNav = (
    <div className="side-nav">
      <button className={`btn small ${view === 'home' ? 'chip' : ''}`} onClick={() => setView('home')}>⌂ 主页</button>
      <button className={`btn small ${view === 'workbench' ? 'chip' : ''}`} onClick={() => setView('workbench')}>◉ 对话</button>
      <button className={`btn small ${view === 'orchestration' ? 'chip' : ''}`} onClick={() => setView('orchestration')}>▦ 编排</button>
      <button className={`btn small ${view === 'dev' ? 'chip' : ''}`} onClick={() => setView('dev')}>⌗ 开发</button>
    </div>
  );
  // 项目入口（D1-25）：侧栏导航下「＋ 新建项目」——项目绑定本地目录，会话按项目分组
  const projEntry = (
    <button className="btn small proj-add" title="新建项目（绑定本地目录）" onClick={openProjDialog}>
      ＋ 新建项目
    </button>
  );
  // 侧栏统一 footer（用户行 + 齿轮设置入口——对齐 web 用户面板右侧齿轮）：
  // 三视图同序同构（用户实测：主页与编排顺序不一致）
  const sidebarFooter = (
    <div className="home-user-row">
      <span className="home-user-avatar">本</span>
      <span>本地用户</span>
      <span style={{ flex: 1 }} />
      <button className="user-gear" title="设置" onClick={() => setSettingsOpen(true)}>⚙</button>
    </div>
  );

  // 主页空态（对标 web ChatHomeView）：主页侧栏为独立形态（无会话列表——会话属「对话」视图）
  if (view === 'home') {
    return withChrome(
      <div className="layout">
        {pendingApproval && (
          <ApprovalDialog req={pendingApproval}
            suspended={approvalSuspended || !streaming}
            onExpired={() => setApprovalSuspended(true)}
            onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
        )}
        <aside className="sidebar">
          {sideNav}
          {projEntry}
          <div className="home-side-gap" />
          {sidebarFooter}
          {settingsOpen && <SettingsPanel conn={connRef.current} initialTab={settingsSection} onClose={() => setSettingsOpen(false)} onChanged={() => { refreshModels(); refreshAgents(); }} />}
        </aside>
        <main className="home">
          <div className="home-logo">格物</div>
          <h1 className="home-title">格物<span className="dot">·</span>致虚</h1>
          <p className="home-sub">本地智能体工作台</p>
          <p className="home-slogan">致知在格物，物格而后知至。<br />选择左侧会话开始对话，或创建新的会话。</p>
          <div className="home-cards">
            <button className="home-card" onClick={() => openWsDialog()}>
              <span className="card-icon plus">＋</span>
              <span>新建会话</span>
            </button>
            <button className="home-card" onClick={() => setView('dev')}>
              <span className="card-icon flow">⌗</span>
              <span>开发空间</span>
            </button>
            <button className="home-card" onClick={() => setView('orchestration')}>
              <span className="card-icon flow">▦</span>
              <span>编排工作台</span>
            </button>
            <button className="home-card" onClick={() => { setSettingsSection('agents'); setSettingsOpen(true); }}>
              <span className="card-icon plus">🤖</span>
              <span>智能体与技能</span>
            </button>
            <button className="home-card" onClick={() => { setSettingsSection('models'); setSettingsOpen(true); }}>
              <span className="card-icon doc">⚙</span>
              <span>设置</span>
            </button>
          </div>
        </main>
      </div>
    );
  }

  // 编排工作台（Phase 3 · D2-6）：本地多 Agent 编排（四模式 + 画布 + 生命周期）
  if (view === 'orchestration') {
    return withChrome(
      <div className="layout">
        {settingsOpen && <SettingsPanel conn={connRef.current} initialTab={settingsSection} onClose={() => setSettingsOpen(false)} onChanged={() => { refreshModels(); refreshAgents(); }} />}
        {pendingApproval && (
          <ApprovalDialog req={pendingApproval}
            suspended={approvalSuspended || !streaming}
            onExpired={() => setApprovalSuspended(true)}
            onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
        )}
        <aside className="sidebar">
          {sideNav}
          {projEntry}
          <div className="home-side-gap" />
          {sidebarFooter}
        </aside>
        <main className="orch-main">
          <OrchestrationWorkbench conn={connRef.current} onBackHome={() => setView('home')} />
        </main>
      </div>
    );
  }

  // 开发空间(D1-25 扩展,对齐 web DevWorkspacePage):Git 克隆/文件树/预览/项目终端
  if (view === 'dev') {
    return withChrome(
      <div className="layout">
        {settingsOpen && <SettingsPanel conn={connRef.current} initialTab={settingsSection} onClose={() => setSettingsOpen(false)} onChanged={() => { refreshModels(); refreshAgents(); }} />}
        {pendingApproval && (
          <ApprovalDialog req={pendingApproval}
            suspended={approvalSuspended || !streaming}
            onExpired={() => setApprovalSuspended(true)}
            onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
        )}
        <aside className="sidebar">
          {sideNav}
          {projEntry}
          <div className="home-side-gap" />
          {sidebarFooter}
        </aside>
        <main className="orch-main">
          <DevWorkspaceView conn={connRef.current} projects={projects} refreshProjects={refreshProjects} />
        </main>
      </div>
    );
  }

  return withChrome(
    <div className="layout">
      {settingsOpen && <SettingsPanel conn={connRef.current} initialTab={settingsSection} onClose={() => setSettingsOpen(false)} onChanged={() => { refreshModels(); refreshAgents(); }} />}
      {pendingApproval && (
        <ApprovalDialog req={pendingApproval}
          suspended={approvalSuspended || !streaming}
          onExpired={() => setApprovalSuspended(true)}
          onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
      )}
        <aside className="sidebar">
        {sideNav}
        {projEntry}
        <div className="sidebar-body">
          <SessionSidebar
            sessions={sessions}
            archivedSessions={archived}
            projects={projects}
            activeSessionId={activeSession}
            currentProjectId={activeSession ? (sessions.find((s) => s.sessionId === activeSession)?.projectId ?? null) : null}
            showArchived={showArchived}
            onToggleArchived={() => setShowArchived((v) => !v)}
            onSelectSession={(s) => openSession(s.sessionId)}
            onCreateSession={(pid) => openWsDialog(pid)}
            onArchive={(id) => archiveSession(id)}
            onUnarchive={(id) => unarchiveSession(id)}
            onPin={(id, p) => pinSession(id, p)}
            loading={false}
            user={{ name: '本地用户' }}
            onBackHome={() => setView('home')}
            streamingSessionId={streaming ? activeSession : null}
          />
        </div>
        {sidebarFooter}
      </aside>
      <div className="main-area">
      <main className="chat">
        <div className="chat-head">
          <div className="flex items-center gap-2 min-w-0">
            <span className="chat-title">{headTitle}</span>
            <select className="agent-select" value={selectedAgentId}
              onChange={(e) => setSelectedAgentId(e.target.value)}
              title="选择本轮对话使用的智能体(在设置-子智能体中管理)">
              <option value="">默认助手</option>
              {agents.map((a) => (
                <option key={a.agentId} value={a.agentId}>
                  {a.name}{a.modelName ? ` · ${a.modelName}` : ''}
                </option>
              ))}
            </select>
            {workspaceName && <span className="chip-static">📁 {workspaceName}</span>}
            {gitBranch && <span className="chip-static git">⎇ {gitBranch}</span>}
            {fileChanges.length > 0 && (
              <button className="btn small chip" onClick={() => { setFilePanelOpen((v) => !v); setSubPanelOpen(false); }}>
                {fileChanges.length} 个文件已更改
                {totalAdd > 0 && <span className="stat-add"> +{totalAdd}</span>}
                {totalDel > 0 && <span className="stat-del"> -{totalDel}</span>}
              </button>
            )}
          </div>
          {/* 视图动作（编排/设置/终端）已上移至自绘标题栏图标（用户指定）——信息带保留变更审查入口 */}
          <span style={{ flex: 1 }} />
        </div>
        {plan && plan.steps.length > 0 && (
          <div className="plan-wrap">
            <PlanCard title={plan.title} steps={plan.steps} streaming={streaming} planPath={plan.planPath} />
          </div>
        )}
        <div className="messages">
          {messages.length === 0 && <p className="hint center-text">选择会话或直接开始对话</p>}
          {messages.map((m, i) => {
            const isLast = i === messages.length - 1;
            const allItems = m.process ?? [];
            const isStreamingMsg = streamingLast && isLast;
            const expanded = expandedMsgs[i] ?? isStreamingMsg;
            // 共享包 splitFinalContent：尾部正文（最后过程条目之后）独立成气泡，
            // 过程叙述（思考/工具/中途正文段）留在时间线内——消除双份渲染（web 同语义）
            const trackerLike = { items: allItems };
            const split = m.role === 'assistant' && allItems.length > 0
              ? splitFinal(allItems)
              : { items: allItems, finalText: m.content };
            void trackerLike;
            return (
              <div key={i} className={`msg ${m.role}`}>
                {m.role === 'assistant' && hasProcessActivity(split.items) && (
                  <AIProcessTimeline
                    items={split.items}
                    streaming={isStreamingMsg}
                    status={isStreamingMsg ? '生成中…' : undefined}
                    totalMs={m.processMs}
                    startAt={m.startAt}
                    expanded={expanded}
                    onToggle={() => setExpandedMsgs((prev) => ({ ...prev, [i]: !expanded }))}
                    onItemClick={(item) => {
                      if (item.kind === 'subagent') {
                        setFilePanelOpen(false);
                        setActiveSubagent({ id: item.id, name: item.name, result: item.result });
                        setSubPanelOpen(true);
                      }
                    }}
                  />
                )}
                {m.role === 'user' || split.finalText ? (
                  <div className="bubble">
                    {split.finalText}
                    {m.queued ? <span className="queued-tag">⏳ 排队中 · 本轮完成后自动执行</span> : null}
                  </div>
                ) : isStreamingMsg ? (
                  <div className="bubble dim typing" aria-label="生成中"><i /><i /><i /></div>
                ) : null}
              </div>
            );
          })}
          <div ref={bottomRef} />
        </div>
        {/* 会话尾部变更条（用户指定）：非流式且有修改时在消息最后展示；可展开逐文件审查 */}
        {!streaming && activeSession && fileChanges.length > 0 && (
          <div className="turn-changes">
            <button className="turn-changes-head" onClick={() => setTurnChangesOpen((v) => !v)}>
              <span className={`adv-chev ${turnChangesOpen ? 'open' : ''}`}>⌄</span>
              <span>{fileChanges.length} 个文件已更改</span>
              {totalAdd > 0 && <span className="stat-add"> +{totalAdd}</span>}
              {totalDel > 0 && <span className="stat-del"> -{totalDel}</span>}
              <span style={{ flex: 1 }} />
              <span role="button" tabIndex={0} className={`undo-btn ${undoArmed ? 'armed' : ''}`}
                onClick={(e) => { e.stopPropagation(); undoAllChanges(); }}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.stopPropagation(); undoAllChanges(); } }}>
                {undoArmed ? '确认撤销全部？' : '↺ 撤销'}
              </span>
            </button>
            {turnChangesOpen && (
              <div className="turn-changes-body">
                {fileChanges.map((c) => (
                  <div key={c.path} className="turn-change-row" onClick={() => setFilePanelOpen(true)}>
                    <span className="tc-type">{c.changeType === 'CREATE' ? '新建' : c.changeType === 'DELETE' ? '删除' : '修改'}</span>
                    <span className="tc-path">{c.path}</span>
                    {c.additions > 0 && <span className="stat-add"> +{c.additions}</span>}
                    {c.deletions > 0 && <span className="stat-del"> -{c.deletions}</span>}
                    <span style={{ flex: 1 }} />
                    <button className="btn small" onClick={(e) => { e.stopPropagation(); setFilePanelOpen(true); }}>审查</button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
        {/* 终端抽屉常驻挂载：收起=CSS 隐藏（PTY/缓冲存活，重开即恢复原会话内容） */}
        <TerminalPanel cwd={workspace.dir || undefined} visible={terminalOpen} onAllClosed={() => setTerminalOpen(false)} />
      <div className="composer">
        <textarea
          value={input}
          placeholder={streaming
            ? '任务执行中——可输入新指令，Enter 排队（本轮完成后基于已有结果自动执行）'
            : '输入消息，Enter 发送（Shift+Enter 换行）'}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
        />
        <button className="btn primary" onClick={() => send()} disabled={!input.trim()}
          title={streaming ? '本轮完成后自动执行' : '发送'}>
          {streaming ? '排队' : '发送'}
        </button>
      </div>
      </main>
      {filePanelOpen && activeSession && (
        <FileEditorPanel
          sessionId={activeSession}
          changes={fileChanges}
          onClose={() => setFilePanelOpen(false)}
          onChangesRefresh={() => refreshFileChanges(activeSession)}
          width={FILE_PANEL_MIN_WIDTH + 120}
          api={createSessionFileChangesApi(connRef.current.transportFor()!)}
        />
      )}
      {subPanelOpen && activeSubagent && (
        <div className="subagent-panel">
          <div className="subagent-panel-head">
            <span className="subagent-panel-title">子智能体 · {activeSubagent.name}</span>
            <button className="btn small" onClick={() => setSubPanelOpen(false)}>关闭</button>
          </div>
          <div className="subagent-panel-body">
            {(() => {
              const live = subProcesses[activeSubagent.id];
              return (
                <>
                  {live && hasProcessActivity(live.snapshot.items) && (
                    <AIProcessTimeline
                      items={live.snapshot.items}
                      status={live.snapshot.status}
                      streaming={streaming}
                      startAt={live.snapshot.startedAt}
                      expanded
                      onToggle={() => {}}
                    />
                  )}
                  {!live && !activeSubagent.result && (
                    <p className="hint">该子智能体的流式过程仅在本次执行期间实时展示；完成后在下方查看聚合结果。</p>
                  )}
                  {activeSubagent.result && (
                    <div className={live && hasProcessActivity(live.snapshot.items) ? 'subagent-result bordered' : 'subagent-result'}>
                      <p className="subagent-result-head">聚合结果</p>
                      <MarkdownRenderer content={activeSubagent.result} />
                    </div>
                  )}
                </>
              );
            })()}
          </div>
        </div>
      )}
      </div>
    </div>
  );
}
