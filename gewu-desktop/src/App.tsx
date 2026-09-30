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
import './styles.css';

// ============ 桌面工作台（D1-24）：共享组件族接入 ============
// 时间线（AIProcessTimeline）/ 会话侧栏（SessionSidebar）/ 任务卡片（PlanCard）/
// 文件变更面板（FileEditorPanel，写文件后自动出现 +N/-N 与 diff 审查）——全部来自共享包。

type SessionItem = import('@gewu/agent-ui/protocol').SessionDTO;
interface MessageItem {
  role: 'user' | 'assistant'; content: string;
  process?: ProcessItem[]; processMs?: number; startAt?: number;
}
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
  const [showSettings, setShowSettings] = useState(false);
  const [providerForm, setProviderForm] = useState({ code: '', displayName: '', baseUrl: '', modelName: '', apiKey: '' });
  const [settingsMsg, setSettingsMsg] = useState<string | null>(null);
  // S9 工作台状态
  const [plan, setPlan] = useState<{ title: string; steps: PlanStepInfo[]; planPath?: string } | null>(null);
  const [fileChanges, setFileChanges] = useState<FileChangeDTO[]>([]);
  const [filePanelOpen, setFilePanelOpen] = useState(false);
  const [pendingApproval, setPendingApproval] = useState<ApprovalRequestPayload | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const ready = engineState === 'ready';

  // ============ 数据刷新 ============
  const refreshSessions = useCallback(async (withArchived = true) => {
    try {
      const res = await connRef.current.fetch('/v1/ai/sessions');
      if (res.ok) setSessions((await res.json()) as SessionItem[]);
      if (withArchived) {
        const res2 = await connRef.current.fetch('/v1/ai/sessions?status=2');
        if (res2.ok) setArchived((await res2.json()) as SessionItem[]);
      }
    } catch { /* 状态条已反映 */ }
  }, []);

  const refreshModels = useCallback(async () => {
    try {
      const res = await connRef.current.fetch('/v1/ai/models');
      if (res.ok) setModels((await res.json()) as ModelItem[]);
    } catch { /* 忽略 */ }
  }, []);

  const refreshFileChanges = useCallback(async (sessionId: string) => {
    try {
      const res = await connRef.current.fetch(`/v1/sessions/${sessionId}/file-changes`);
      if (res.ok) setFileChanges((await res.json()) as FileChangeDTO[]);
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

  // ============ 会话操作 ============
  const openSession = useCallback(async (sessionId: string) => {
    setActiveSession(sessionId); setMessages([]); setPlan(null);
    const res = await connRef.current.fetch(`/v1/ai/sessions/${sessionId}/messages`);
    if (res.ok) {
      const list = (await res.json()) as Array<{ role: string; content: string }>;
      setMessages(list.map((m) => ({ role: m.role as 'user' | 'assistant', content: m.content })));
    }
    refreshFileChanges(sessionId);
  }, [refreshFileChanges]);

  const newSession = useCallback(() => {
    setActiveSession(null); setMessages([]); setPlan(null); setFileChanges([]); setFilePanelOpen(false);
  }, []);

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

  // ============ 发送（过程时间线 + 任务计划 + 变更追踪） ============
  const send = useCallback(async () => {
    if (!input.trim() || streaming) return;
    const text = input.trim();
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

    const callbacks: ChatStreamCallbacks = {
      ...handlers,
      onContent: (t: string) => setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = { ...last, content: last.content + t };
        }
        return next;
      }),
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
      // 收尾：时间线定格
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
      refreshSessions();
      const target = activeSession ?? sessions[0]?.sessionId ?? null;
      if (target) refreshFileChanges(target);
    } catch (e) {
      callbacks.onError?.(String(e));
    } finally {
      setStreaming(false);
    }
  }, [input, streaming, activeSession, sessions, refreshSessions, refreshFileChanges]);

  // ============ 供应商管理 ============
  const saveProvider = useCallback(async () => {
    if (!providerForm.code || !providerForm.baseUrl) { setSettingsMsg('code 与 baseUrl 必填'); return; }
    try {
      await window.gewu.saveModelKey({
        providerCode: providerForm.code, apiKey: providerForm.apiKey,
        displayName: providerForm.displayName, baseUrl: providerForm.baseUrl,
        modelsJson: JSON.stringify([{
          name: providerForm.modelName || providerForm.code + '-model',
          displayName: providerForm.displayName || providerForm.code, description: '',
        }]),
      });
      const res = await connRef.current.fetch('/v1/engine/providers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code: providerForm.code, displayName: providerForm.displayName,
          baseUrl: providerForm.baseUrl, apiKey: providerForm.apiKey,
          modelsJson: JSON.stringify([{
            name: providerForm.modelName || providerForm.code + '-model',
            displayName: providerForm.displayName || providerForm.code, description: '',
          }]),
        }),
      });
      if (res.ok) {
        setSettingsMsg(`已保存 ${providerForm.code}（Key 已注入，重启后自动恢复）`);
        setProviderForm({ code: '', displayName: '', baseUrl: '', modelName: '', apiKey: '' });
        refreshModels();
      } else { setSettingsMsg(`保存失败: HTTP ${res.status}`); }
    } catch (e) { setSettingsMsg(`保存失败: ${String(e)}`); }
  }, [providerForm, refreshModels]);

  const deleteProvider = useCallback(async (code: string) => {
    if (!confirm(`删除供应商 ${code}？引用它的 Agent 将自动回退到其余可用供应商。`)) return;
    try {
      const res = await connRef.current.fetch(`/v1/engine/providers/${encodeURIComponent(code)}`, { method: 'DELETE' });
      if (res.ok) { setSettingsMsg(`已删除 ${code}`); refreshModels(); }
      else setSettingsMsg(`删除失败: HTTP ${res.status}`);
    } catch (e) { setSettingsMsg(`删除失败: ${String(e)}`); }
  }, [refreshModels]);

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

  // 主页空态（对标 web ChatHomeView；无选中会话且无流中消息时呈现）
  if (!activeSession && messages.length === 0) {
    return (
      <div className="layout">
        {pendingApproval && (
          <ApprovalDialog req={pendingApproval} onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
        )}
        <aside className="sidebar">
          <SessionSidebar
            sessions={sessions}
            archivedSessions={archived}
            projects={[]}
            activeSessionId={null}
            currentProjectId={null}
            showArchived={showArchived}
            onToggleArchived={() => setShowArchived((v) => !v)}
            onSelectSession={(s) => openSession(s.sessionId)}
            onCreateSession={() => newSession()}
            onArchive={(id) => archiveSession(id)}
            onUnarchive={(id) => unarchiveSession(id)}
            onPin={(id, p2) => pinSession(id, p2)}
            loading={false}
            user={{ name: '本地用户' }}
            onBackHome={() => { /* 桌面端无首页概念 */ }}
            streamingSessionId={streaming ? activeSession : null}
          />
          <div className="home-actions">
            <button className="btn" onClick={() => setShowSettings((v) => !v)}>设置</button>
          </div>
        </aside>
        <main className="home">
          <div className="home-logo">格物</div>
          <h1 className="home-title">格物<span className="dot">·</span>致虚</h1>
          <p className="home-sub">本地智能体工作台</p>
          <p className="home-slogan">致知在格物，物格而后知至。<br />选择左侧会话开始对话，或创建新的会话。</p>
          <div className="home-cards">
            <button className="home-card" onClick={newSession}>
              <span className="card-icon plus">＋</span>
              <span>新建会话</span>
            </button>
            <button className="home-card" onClick={() => setShowSettings(true)}>
              <span className="card-icon doc">⚙</span>
              <span>模型设置</span>
            </button>
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="layout">
      {pendingApproval && (
        <ApprovalDialog req={pendingApproval} onDecide={(a, al) => decideApproval(pendingApproval.approvalId, a, al)} />
      )}
      <aside className="sidebar">
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
          onBackHome={() => { /* 桌面端无首页概念 */ }}
          streamingSessionId={streaming ? activeSession : null}
        />
        {showSettings && (
          <div className="settings">
            <h4>模型供应商</h4>
            {models.length === 0 && <p className="hint">尚未配置，引擎以无模型状态运行</p>}
            {models.map((m) => (
              <div key={m.provider + m.modelName} className="provider-row">
                <span className="hint">{m.provider} / {m.displayName}</span>
                <button className="btn small" onClick={() => deleteProvider(m.provider)}>删除</button>
              </div>
            ))}
            <input placeholder="编码（如 zhipu，同编码覆盖更新）" value={providerForm.code}
              onChange={(e) => setProviderForm({ ...providerForm, code: e.target.value })} />
            <input placeholder="显示名" value={providerForm.displayName}
              onChange={(e) => setProviderForm({ ...providerForm, displayName: e.target.value })} />
            <input placeholder="Base URL（如 https://open.bigmodel.cn/api/paas/v4）" value={providerForm.baseUrl}
              onChange={(e) => setProviderForm({ ...providerForm, baseUrl: e.target.value })} />
            <input placeholder="模型名（modelName，如 glm-4-flash）" value={providerForm.modelName}
              onChange={(e) => setProviderForm({ ...providerForm, modelName: e.target.value })} />
            <input placeholder="API Key" type="password" value={providerForm.apiKey}
              onChange={(e) => setProviderForm({ ...providerForm, apiKey: e.target.value })} />
            <button className="btn primary" onClick={saveProvider}>保存供应商</button>
            {settingsMsg && <p className="hint">{settingsMsg}</p>}
          </div>
        )}
      </aside>
      <main className="chat">
        {plan && plan.steps.length > 0 && (
          <div className="plan-wrap">
            <PlanCard title={plan.title} steps={plan.steps} streaming={streaming} planPath={plan.planPath} />
          </div>
        )}
        <div className="messages">
          {messages.length === 0 && <p className="hint center-text">选择会话或直接开始对话</p>}
          {messages.map((m, i) => {
            const isLast = i === messages.length - 1;
            const timelineItems = m.process ?? [];
            return (
              <div key={i} className={`msg ${m.role}`}>
                {m.role === 'assistant' && hasProcessActivity(timelineItems) && (
                  <AIProcessTimeline
                    items={timelineItems}
                    streaming={streamingLast && isLast}
                    status={streamingLast && isLast ? '生成中…' : undefined}
                    totalMs={m.processMs}
                    startAt={m.startAt}
                    expanded={false}
                    onToggle={() => { /* 兼容入口 */ }}
                  />
                )}
                {m.role === 'user' || m.content ? (
                  <div className="bubble">{m.content}</div>
                ) : streamingLast && isLast ? (
                  <div className="bubble dim">…</div>
                ) : null}
              </div>
            );
          })}
          {fileChanges.length > 0 && (
            <div className="chip-row">
              <button className="btn small" onClick={() => setFilePanelOpen((v) => !v)}>
                {filePanelOpen ? '收起文件面板' : `${fileChanges.length} 个文件已更改`}
              </button>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
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
        <div className="composer">
          <textarea
            value={input}
            placeholder="输入消息，Enter 发送（Shift+Enter 换行）"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
            disabled={streaming}
          />
          <button className="btn primary" onClick={send} disabled={streaming || !input.trim()}>
            {streaming ? '生成中…' : '发送'}
          </button>
        </div>
      </main>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
