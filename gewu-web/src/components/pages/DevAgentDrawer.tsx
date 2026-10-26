'use client';
// 开发工作空间 Agent 抽屉：绑定当前项目的 AI 会话。
// 会话落 session.projectId（后端自动绑工作空间，Agent 文件工具根目录 =
// /workspace/projects/{id}/repo），因此可直接对话与 AI 编码执行；
// 会话在「会话」页按项目分组同步展示；支持一键跳转会话页继续。
import { useCallback, useEffect, useRef, useState } from 'react';
import { useDispatch } from 'react-redux';
import { Bot, ExternalLink, Loader2, Plus, Send, X } from 'lucide-react';
import Modal from '@/components/ui/Modal';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import { setPage, setActiveSession } from '@/store';
import { createSession, listMessages, type SessionDTO } from '@/lib/session';
import { chatStream, generateClientId, type ChatRequest } from '@/lib/chat';

interface ChatItem {
  role: 'user' | 'assistant' | 'status';
  text: string;
  streaming?: boolean;
}

export default function DevAgentDrawer({ project, open, onClose }: {
  project: { projectId: string; projectName: string } | null;
  open: boolean;
  onClose: () => void;
}) {
  const dispatch = useDispatch();
  const [session, setSession] = useState<SessionDTO | null>(null);
  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [loading, setLoading] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);

  const storageKey = project ? `dw-agent-session-${project.projectId}` : '';

  const scrollBottom = useCallback(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, []);

  // 打开时恢复绑定会话（localStorage 持久会话 ID；不存在则空态等首条消息）
  useEffect(() => {
    if (!open || !project || !storageKey) return;
    const sid = typeof window !== 'undefined' ? localStorage.getItem(storageKey) : null;
    if (!sid) { setSession(null); setItems([]); return; }
    setLoading(true);
    (async () => {
      try {
        const page = await listMessages(sid, 1, 100, 'asc');
        const his: ChatItem[] = (page.records || [])
          .filter(m => (m.messageType === 'user' || m.messageType === 'assistant') && m.content)
          .map(m => ({ role: m.messageType === 'user' ? 'user' : 'assistant', text: m.content }));
        setSession({ sessionId: sid } as SessionDTO);
        setItems(his);
      } catch {
        // 会话已失效（删除/无权限）：清绑定重新开始
        localStorage.removeItem(storageKey);
        setSession(null); setItems([]);
      } finally {
        setLoading(false);
      }
    })();
  }, [open, project?.projectId, storageKey]);

  useEffect(() => { scrollBottom(); }, [items, scrollBottom]);

  const newSession = useCallback(() => {
    if (storageKey) localStorage.removeItem(storageKey);
    setSession(null); setItems([]); setInput('');
  }, [storageKey]);

  const gotoSessionsPage = useCallback(() => {
    if (session) dispatch(setActiveSession(session.sessionId));
    dispatch(setPage('chat'));
    onClose();
  }, [dispatch, session, onClose]);

  const send = async () => {
    if (!project || !storageKey || streaming) return;
    const text = input.trim();
    if (!text) return;
    setInput('');
    let sid = session?.sessionId;
    try {
      if (!sid) {
        const s = await createSession({
          title: `${project.projectName} · Agent`.slice(0, 40),
          type: 1,
          projectId: project.projectId,
        });
        sid = s.sessionId;
        setSession(s);
        localStorage.setItem(storageKey, sid);
      }
    } catch (e) {
      setItems(prev => [...prev, { role: 'status', text: '⚠ 会话创建失败: ' + (e instanceof Error ? e.message : String(e)) }]);
      return;
    }
    setItems(prev => [...prev, { role: 'user', text }, { role: 'assistant', text: '', streaming: true }]);
    setStreaming(true);
    const req: ChatRequest = {
      sessionId: sid,
      projectId: project.projectId,
      message: text,
      clientId: generateClientId(),
    } as ChatRequest;
    const patchLast = (patch: Partial<ChatItem>) => setItems(prev => prev.map((it, i) => (i === prev.length - 1 ? { ...it, ...patch } : it)));
    try {
      await chatStream(req, {
        onContent: (t) => setItems(prev => prev.map((it, i) => (i === prev.length - 1 ? { ...it, text: it.text + t } : it))),
        onToolCall: (tc) => setItems(prev => [...prev, { role: 'status', text: `⚙ ${tc?.name || '工具调用'}` }]),
        onToolResult: () => { /* 工具结果详情留在会话页查看 */ },
        onError: (msg) => { patchLast({ streaming: false }); setItems(prev => [...prev, { role: 'status', text: '⚠ ' + msg }]); },
        onComplete: () => { /* finally 统一收口 */ },
      });
    } catch (e) {
      setItems(prev => [...prev, { role: 'status', text: '⚠ 连接中断: ' + (e instanceof Error ? e.message : String(e)) }]);
    } finally {
      setStreaming(false);
      setItems(prev => prev.map(it => ({ ...it, streaming: false })));
      scrollBottom();
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      variant="right"
      zIndex="z-[70]"
      ariaLabel="项目 Agent 对话"
      panelClassName="glass-dark w-full max-w-[480px] h-full p-0 border-l border-tech-500/15 shadow-2xl flex flex-col"
    >
      <div className="flex items-center justify-between px-4 py-3 border-b border-tech-500/10 flex-shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          <Bot className="w-4 h-4 text-tech-400 flex-shrink-0" />
          <div className="min-w-0">
            <div className="text-sm font-semibold text-ink-50 truncate">Agent · {project?.projectName}</div>
            <div className="text-[10px] text-ink-500 truncate">
              {session ? `会话 ${session.sessionId.slice(0, 8)}…` : '发送首条消息时创建项目绑定会话'}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1 flex-shrink-0">
          <button onClick={newSession} className="p-1.5 text-ink-500 hover:text-tech-400 rounded-md hover:bg-tech-500/10 transition-all" title="新建 Agent 会话">
            <Plus className="w-4 h-4" />
          </button>
          <button onClick={gotoSessionsPage} className="p-1.5 text-ink-500 hover:text-tech-400 rounded-md hover:bg-tech-500/10 transition-all" title="前往会话页（此项目会话已在项目分组下同步展示）">
            <ExternalLink className="w-4 h-4" />
          </button>
          <button onClick={onClose} className="p-1.5 text-ink-500 hover:text-ink-200 rounded-md hover:bg-ink-800/50 transition-all" title="关闭">
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      <div ref={listRef} className="flex-1 min-h-0 overflow-y-auto scrollbar-thin px-4 py-3 space-y-3">
        {loading ? (
          <div className="flex items-center justify-center py-10"><Loader2 className="w-4 h-4 text-tech-400 animate-spin" /></div>
        ) : items.length === 0 ? (
          <div className="text-center py-10 text-ink-500 text-xs leading-6">
            Agent 已绑定当前项目工作空间<br />
            可以直接对话，也可以让 AI 阅读与修改项目文件<br />
            <span className="text-ink-600">会话将同步展示在「会话」页的项目分组下</span>
          </div>
        ) : items.map((it, i) => it.role === 'status' ? (
          <div key={i} className="text-[10px] text-ink-500 font-mono truncate">{it.text}</div>
        ) : it.role === 'user' ? (
          <div key={i} className="flex justify-end">
            <div className="max-w-[85%] px-3 py-2 rounded-xl rounded-br-sm bg-tech-500/15 text-ink-100 text-xs whitespace-pre-wrap break-all">{it.text}</div>
          </div>
        ) : (
          <div key={i} className="text-xs text-ink-100 leading-relaxed">
            {it.text ? <MarkdownRenderer content={it.text} /> : it.streaming ? <Loader2 className="w-3.5 h-3.5 text-tech-400 animate-spin" /> : null}
            {it.streaming && it.text && <span className="inline-block w-1.5 h-3.5 bg-tech-400/70 animate-pulse align-middle ml-0.5" />}
          </div>
        ))}
      </div>

      <div className="flex-shrink-0 border-t border-tech-500/10 p-3">
        <div className="flex items-end gap-2">
          <textarea
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
            rows={2}
            placeholder={streaming ? 'Agent 执行中…' : '对话或下达 AI 编码指令（Enter 发送）'}
            disabled={streaming || loading}
            className="flex-1 resize-none bg-ink-900/60 border border-tech-500/10 rounded-lg px-3 py-2 text-xs text-ink-100 placeholder:text-ink-600 outline-none focus:border-tech-500/40 disabled:opacity-60"
          />
          <button
            onClick={send}
            disabled={streaming || loading || !input.trim()}
            className="flex-shrink-0 px-3 py-2 btn-primary text-white rounded-lg disabled:opacity-40"
            title="发送">
            {streaming ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
          </button>
        </div>
      </div>
    </Modal>
  );
}
