import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { EngineConnection, waitEngineReady } from './engine';
import {
  generateClientId,
  type ChatStreamCallbacks,
  type PlanStepInfo,
} from '@gewu/agent-ui/protocol';
import { streamChat, createSessionFileChangesApi } from '@gewu/agent-ui/api-client';
import { AIProcessTimeline } from '@gewu/agent-ui/chat-ui/timeline';
import { createProcessStreamHandler, hasProcessActivity,
  type ProcessItem } from '@gewu/agent-ui/process';
import { SessionSidebar } from '@gewu/agent-ui/chat-ui/sidebar';
import { PlanCard } from '@gewu/agent-ui/chat-ui/plan';
import { FileEditorPanel, FILE_PANEL_MIN_WIDTH } from '@gewu/agent-ui/chat-ui/filepanel';
import type { ApprovalRequestPayload } from '@gewu/agent-ui/protocol';
import type { FileChangeDTO } from '@gewu/agent-ui/api-client';
import ApprovalDialog from './components/ApprovalDialog';
import SettingsPanel from './components/SettingsPanel';
import TerminalPanel from './components/TerminalPanel';
import OrchestrationWorkbench from './components/OrchestrationWorkbench';
import './styles.css';

// ============ 辅助：尾部正文拆分（最后过程条目之后的正文 = 独立气泡；web splitFinalContent 同语义） ============
function splitFinal(items: ProcessItem[], full: string): { items: ProcessItem[]; finalText: string } {
  let lastIdx = -1;
  for (let i = items.length - 1; i >= 0; i--) {
    if (items[i].kind !== 'content') { lastIdx = i; break; }
  }
  if (lastIdx === -1) return { items: [], finalText: full };
  const tl = items.slice(0, lastIdx + 1);
  const tail = items.slice(lastIdx + 1)
    .filter((x) => x.kind === 'content')
    .map((x) => (x as { text: string }).text)
    .join('');
  const finalText = tail && full.endsWith(tail) ? full.slice(0, full.length - tail.length) : full;
  return { items: tl, finalText: finalText || full };
}

// ============ 桌面工作台（D1-24）：共享组件族接入 ============
// 时间线（AIProcessTimeline）/ 会话侧栏（SessionSidebar）/ 任务卡片（PlanCard）/
// 文件变更面板（FileEditorPanel，写文件后自动出现 +N/-N 与 diff 审查）——全部来自共享包。

type SessionItem = import('@gewu/agent-ui/protocol').SessionDTO;
interface MessageItem {
  role: 'user' | 'assistant'; content: string;
  process?: ProcessItem[]; processMs?: number; startAt?: number;
  stats?: { rounds?: number; elapsedMs?: number; tokenEstimated?: number };
}
interface GitInfo { branch: string | null }
interface ModelItem { provider: string; modelName: string; displayName: string; supported: boolean }

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
  // 视图状态：home（主页空态）/ workbench（工作台）/ orchestration（编排工作台）
  const [view, setView] = useState<'home' | 'workbench' | 'orchestration'>('home');
  const bottomRef = useRef<HTMLDivElement>(null);
  // 时间线展开态（按消息索引）：流中最后一条默认展开（实时可见），完成后折叠可点击
  const [expandedMsgs, setExpandedMsgs] = useState<Record<number, boolean>>({});

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

  const refreshFileChanges = useCallback(async (sessionId: string) => {
    try {
      setFileChanges((await connRef.current.data(`/v1/sessions/${sessionId}/file-changes`)) as FileChangeDTO[]);
    } catch { /* 忽略 */ }
  }, []);

  useEffect(() => {
    waitEngineReady()
      .then((info) => { connRef.current.update(info); setEngineState('ready'); })
      .catch((e) => setEngineError(String(e)));
    const off = window.gewu.onEngineStatus((s) => {
      if (s.info) connRef.current.update(s.info);
      setEngineState(s.state);
      if (s.state === 'ready') { refreshSessions(); refreshModels(); }
    });
    return off;
  }, []);

  useEffect(() => {
    if (ready) { refreshSessions(); refreshModels(); }
  }, [ready, refreshSessions, refreshModels]);

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
    };
  });

  // ============ 会话操作 ============
  const openSession = useCallback(async (sessionId: string) => {
    setView('workbench');
    setActiveSession(sessionId); setMessages([]); setPlan(null);
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
    try {
      const gi = (await connRef.current.data(`/v1/sessions/${sessionId}/git-info`)) as GitInfo;
      setGitBranch(gi.branch ?? null);
    } catch { setGitBranch(null); }
    const sess = sessions.find((s) => s.sessionId === sessionId);
    const dir = sess?.directory ?? null;
    setWorkspace({ basename: dir ? dir.split(/[\\/]/).filter(Boolean).pop() || 'workspace' : 'workspace', dir });
    refreshFileChanges(sessionId);
  }, [refreshFileChanges]);

  const newSession = useCallback(() => {
    setView('workbench');
    setActiveSession(null); setMessages([]); setPlan(null); setFileChanges([]); setFilePanelOpen(false);
  }, []);

  // ============ 新建会话对话框（工作区选择器，Phase 1 遗留补齐） ============
  const [wsOpen, setWsOpen] = useState(false);
  const [wsForm, setWsForm] = useState<{ title: string; directory: string | null }>({ title: '', directory: null });
  const openWsDialog = useCallback(() => { setWsForm({ title: '', directory: null }); setWsErr(null); setWsOpen(true); }, []);
  const createWsSession = useCallback(async () => {
    try {
      const body: Record<string, unknown> = { title: wsForm.title.trim() || '新会话' };
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

  const activeTitle = activeSession
    ? (sessions.find((s) => s.sessionId === activeSession)?.title ?? '会话')
    : '新对话';

  // ============ 发送（过程时间线 + 任务计划 + 变更追踪） ============
  const send = useCallback(async (preset?: string) => {
    const text = (preset ?? input).trim();
    if (!text || streaming) return;
    setView('workbench'); // 从主页直接发送（测试钩子/快捷发送）也进入工作台
    setInput('');
    setMessages((prev) => [...prev, { role: 'user', content: text }, { role: 'assistant', content: '' }]);
    setStreaming(true);
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
      onPlan: (title: string, steps: PlanStepInfo[], planPath?: string) => setPlan({ title, steps, planPath }),
      onApprovalRequired: (req: ApprovalRequestPayload) => setPendingApproval(req),
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
        { message: text, sessionId: activeSession ?? undefined, clientId },
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
      setStreaming(false);
    }
  }, [input, streaming, activeSession, sessions, refreshSessions, refreshFileChanges]);

  // ============ 供应商管理（已整合进统一设置面板 SettingsPanel） ============

  const decideApproval = useCallback(async (approvalId: string, approve: boolean, alwaysAllow: boolean) => {
    setPendingApproval(null);
    try {
      await connRef.current.fetch(`/v1/approvals/${approvalId}/decide`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approve, alwaysAllow }),
      });
    } catch { /* 流恢复由引擎侧超时兜底 */ }
  }, []);

  // ============ 引擎未就绪态 ============
  if (!ready) {
    return (
      <div className="center">
        <div className="card">
          <h2>格物桌面</h2>
          <p>本地引擎状态：<b>{engineState}</b></p>
          {engineError && <p className="error">{engineError}</p>}
          <p className="hint">首次启动需初始化本地运行时（约 3~10 秒）；崩溃会自动重启（≤3 次）</p>
          <button className="btn" onClick={() => window.gewu.restartEngine()}>重启引擎</button>
        </div>
      </div>
    );
  }

  const lastMsg = messages[messages.length - 1];
  const streamingLast = streaming && lastMsg?.role === 'assistant';
  const workspaceName = workspace.basename || null;
  const totalAdd = fileChanges.reduce((s, c) => s + (c.additions || 0), 0);
  const totalDel = fileChanges.reduce((s, c) => s + (c.deletions || 0), 0);

  // 侧栏常驻导航（用户实测：入口不应只藏在主页卡片——三个视图均在侧栏顶部可见）
  const sideNav = (
    <div className="side-nav">
      <button className={`btn small ${view === 'home' ? 'chip' : ''}`} onClick={() => setView('home')}>⌂ 主页</button>
      <button className={`btn small ${view === 'workbench' ? 'chip' : ''}`} onClick={() => setView('workbench')}>◉ 对话</button>
      <button className={`btn small ${view === 'orchestration' ? 'chip' : ''}`} onClick={() => setView('orchestration')}>▦ 编排</button>
    </div>
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
    return (
      <div className="layout">
        {pendingApproval && (
          <ApprovalDialog req={pendingApproval} onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
        )}
        <aside className="sidebar">
          {sideNav}
          <div className="home-side-gap" />
          {sidebarFooter}
          {settingsOpen && <SettingsPanel conn={connRef.current} onClose={() => setSettingsOpen(false)} onChanged={refreshModels} />}
        </aside>
        <main className="home">
          <div className="home-logo">格物</div>
          <h1 className="home-title">格物<span className="dot">·</span>致虚</h1>
          <p className="home-sub">本地智能体工作台</p>
          <p className="home-slogan">致知在格物，物格而后知至。<br />选择左侧会话开始对话，或创建新的会话。</p>
          {wsOpen && (
            <div className="overlay" onClick={() => setWsOpen(false)}>
              <div className="dialog ws-dialog" onClick={(e) => e.stopPropagation()}>
                <div className="dialog-head">
                  <span className="dialog-title">新建会话</span>
                  <span className="dialog-badge">工作区</span>
                </div>
                <p className="dialog-sub">绑定本地目录后，文件工具、终端与 Git 信息将以该目录为工作区。</p>
                <input className="ws-input" placeholder="会话标题（可选，默认「新会话」）" value={wsForm.title}
                  onChange={(e) => setWsForm((f) => ({ ...f, title: e.target.value }))} />
                <div className="ws-dir-row">
                  {wsForm.directory
                    ? <span className="dialog-path">{wsForm.directory}</span>
                    : <span className="hint">未选择目录：使用引擎默认工作区</span>}
                </div>
                {wsErr && <p className="error">{wsErr}</p>}
                <div className="dialog-actions ws-actions">
                  <button className="btn" onClick={async () => {
                    const dir = await window.gewu.chooseDirectory();
                    if (dir) setWsForm((f) => ({ ...f, directory: dir }));
                  }}>选择目录</button>
                  {wsForm.directory && (
                    <button className="btn small" onClick={() => setWsForm((f) => ({ ...f, directory: null }))}>清除</button>
                  )}
                  <span style={{ flex: 1 }} />
                  <button className="btn" onClick={() => setWsOpen(false)}>取消</button>
                  <button className="btn primary" onClick={createWsSession}>创建</button>
                </div>
              </div>
            </div>
          )}
          <div className="home-cards">
            <button className="home-card" onClick={openWsDialog}>
              <span className="card-icon plus">＋</span>
              <span>新建会话</span>
            </button>
            <button className="home-card" onClick={() => setView('orchestration')}>
              <span className="card-icon flow">▦</span>
              <span>编排工作台</span>
            </button>
            <button className="home-card" onClick={() => setSettingsOpen(true)}>
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
    return (
      <div className="layout">
        {settingsOpen && <SettingsPanel conn={connRef.current} onClose={() => setSettingsOpen(false)} onChanged={refreshModels} />}
        {pendingApproval && (
          <ApprovalDialog req={pendingApproval} onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
        )}
        <aside className="sidebar">
          {sideNav}
          <div className="home-side-gap" />
          {sidebarFooter}
        </aside>
        <main className="orch-main">
          <OrchestrationWorkbench conn={connRef.current} onBackHome={() => setView('home')} />
        </main>
      </div>
    );
  }

  return (
    <div className="layout">
      {settingsOpen && <SettingsPanel conn={connRef.current} onClose={() => setSettingsOpen(false)} onChanged={refreshModels} />}
      {pendingApproval && (
        <ApprovalDialog req={pendingApproval} onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
      )}
        <aside className="sidebar">
        {sideNav}
        <div className="sidebar-body">
          <SessionSidebar
            sessions={sessions}
            archivedSessions={archived}
            projects={[]}
            activeSessionId={activeSession}
            currentProjectId={null}
            showArchived={showArchived}
            onToggleArchived={() => setShowArchived((v) => !v)}
            onSelectSession={(s) => openSession(s.sessionId)}
            onCreateSession={() => newSession()}
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
            <span className="chat-title">{activeTitle}</span>
            {workspaceName && <span className="chip-static">📁 {workspaceName}</span>}
            {gitBranch && <span className="chip-static git">⎇ {gitBranch}</span>}
          </div>
          <div className="flex items-center gap-2">
            <button className="btn small" onClick={() => setView('orchestration')}>编排</button>
            <button className="btn small" onClick={() => setSettingsOpen(true)}>设置</button>
            {fileChanges.length > 0 && (
              <button className="btn small chip" onClick={() => setFilePanelOpen((v) => !v)}>
                {fileChanges.length} 个文件已更改
                {totalAdd > 0 && <span className="stat-add"> +{totalAdd}</span>}
                {totalDel > 0 && <span className="stat-del"> -{totalDel}</span>}
              </button>
            )}
            <button className="btn small" onClick={() => setTerminalOpen((v) => !v)}>
              {terminalOpen ? '收起终端' : '终端'}
            </button>
          </div>
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
              ? splitFinal(allItems, m.content)
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
                  />
                )}
                {m.role === 'user' || split.finalText ? (
                  <div className="bubble">{split.finalText}</div>
                ) : isStreamingMsg ? (
                  <div className="bubble dim">…</div>
                ) : null}
              </div>
            );
          })}
          <div ref={bottomRef} />
        </div>
        {terminalOpen && <TerminalPanel cwd={workspace.dir || undefined} onAllClosed={() => setTerminalOpen(false)} />}
      <div className="composer">
        <textarea
          value={input}
          placeholder="输入消息，Enter 发送（Shift+Enter 换行）"
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
          disabled={streaming}
        />
        <button className="btn primary" onClick={() => send()} disabled={streaming || !input.trim()}>
          {streaming ? '生成中…' : '发送'}
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
      </div>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
