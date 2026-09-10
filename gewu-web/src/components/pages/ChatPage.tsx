'use client';
import { useState, useRef, useEffect, useCallback } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Send, Plus, Settings, Share2, Clock, ArrowLeft, RefreshCw, Pin, PinOff, Link2, Link2Off, AlertTriangle } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { setPage, setPendingAgentId, type RootState } from '@/store';
import CustomSelect from '@/components/ui/Select';
import { Message } from '@/types';
import AIProcessTimeline from './AIProcessTimeline';
import ChatHomeView from './ChatHomeView';
import { chatStream, regenerateMessageStream } from '@/lib/chat';
import { createProcessStreamHandler, hasProcessActivity, type ProcessSnapshot, type ProcessItem } from '@/lib/agentProcess';
import { listActiveModels, type ModelConfig } from '@/lib/model-config';
import { listAgents } from '@/lib/agent';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import FileCard from '@/components/ui/FileCard';
import ChatErrorBanner from '@/components/ui/ChatErrorBanner';
import { classifyChatError, type ChatErrorInfo } from '@/lib/chatErrors';
import type { FileInfo } from '@/types';
import {
  listMySessions, createSession, updateSession,
  listMessages, pinSession, shareSession, unshareSession,
  type SessionDTO,
} from '@/lib/session';

const chatTabs = [
  { id: 'project', label: '项目' },
  { id: 'requirement', label: '需求' },
  { id: 'task', label: '任务' },
];

const tabGroupLabels: Record<string, string> = {
  project: '项目会话',
  requirement: '需求会话',
  task: '任务会话',
};

const agentModeOptions = [
  { value: 'assistant', label: '助手模式' },
  { value: 'expert', label: '专家模式' },
  { value: 'creative', label: '创意模式' },
  { value: 'precise', label: '精确模式' },
];

const thinkingStyleOptions = [
  { value: 'chain-of-thought', label: '链式推理' },
  { value: 'tree-of-thought', label: '树状推理' },
  { value: 'react', label: 'ReAct' },
  { value: 'step-by-step', label: '逐步分析' },
  { value: 'socratic', label: '苏格拉底式' },
];

export default function ChatPage() {
  const [activeTab, setActiveTab] = useState('project');
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamText, setStreamText] = useState('');
  const [showChatView, setShowChatView] = useState(false);
  const [currentTitle, setCurrentTitle] = useState('');
  const [streamingFiles, setStreamingFiles] = useState<FileInfo[]>([]);
  // AI 处理过程时间线快照（思考/工具/搜索交错，流式期间实时更新）
  const [process, setProcess] = useState<ProcessSnapshot | null>(null);
  // 对话流式错误（持久提示框，替代一闪而过的 toast）
  const [chatError, setChatError] = useState<ChatErrorInfo | null>(null);
  const [modelOptions, setModelOptions] = useState<{ value: string; label: string }[]>([]);
  const [selectedModel, setSelectedModel] = useState('');
  const [agentMode, setAgentMode] = useState('assistant');
  const [thinkingStyle, setThinkingStyle] = useState('chain-of-thought');
  // 智能体选择
  const [agentId, setAgentId] = useState<string | null>(null);
  const [agentOptions, setAgentOptions] = useState<{ value: string; label: string }[]>([]);
  const pendingAgentId = useSelector((s: RootState) => s.app.pendingAgentId);
  const currentUser = useSelector((s: RootState) => s.app.user);
  // 会话记录
  const [sessions, setSessions] = useState<SessionDTO[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [regeneratingId, setRegeneratingId] = useState<string | null>(null);
  const [sharedSessionId, setSharedSessionId] = useState<string | null>(null);
  const [loadingSessions, setLoadingSessions] = useState(false);
  // 项目筛选
  const [filterProjectId, setFilterProjectId] = useState<string | null>(null);
  const [projectSessions, setProjectSessions] = useState<SessionDTO[]>([]);
  const [filteredSessions, setFilteredSessions] = useState<SessionDTO[]>([]);
  const bottomRef = useRef<HTMLDivElement>(null);
  const dispatch = useDispatch();
  const toast = useToast();

  // 从后端加载已启用的模型列表
  const loadModels = useCallback(async () => {
    try {
      const models: ModelConfig[] = await listActiveModels();
      const options = models.map(m => ({ value: m.modelId, label: m.modelName }));
      setModelOptions(options);
      if (options.length > 0 && !selectedModel) {
        setSelectedModel(options[0].value);
      }
    } catch (err) {
      console.error('模型列表加载失败:', err);
      setModelOptions([]);
    }
  }, [selectedModel]);

  useEffect(() => { loadModels(); }, [loadModels]);

  // 加载我的智能体列表（用于智能体选择器）
  const loadAgentOptions = useCallback(async () => {
    try {
      const data = await listAgents();
      setAgentOptions((data.records || []).map(a => ({ value: a.agentId, label: a.agentName })));
    } catch {
      setAgentOptions([]);
    }
  }, []);
  useEffect(() => { loadAgentOptions(); }, [loadAgentOptions]);

  // 跨页传入的"要对话的智能体"：自动选中并开启新对话
  useEffect(() => {
    if (pendingAgentId) {
      setAgentId(pendingAgentId);
      dispatch(setPendingAgentId(null));
      startNewChat(pendingAgentId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingAgentId]);

  // 加载用户会话记录
  const loadSessions = useCallback(async () => {
    setLoadingSessions(true);
    try {
      const result = await listMySessions(1, 50);
      setSessions(result.records || []);
    } catch (err) {
      console.error('加载会话记录失败:', err);
      setSessions([]);
    } finally {
      setLoadingSessions(false);
    }
  }, []);

  useEffect(() => { loadSessions(); }, [loadSessions]);

  // 根据标签过滤会话
  useEffect(() => {
    let filtered = sessions;

    if (activeTab === 'project') {
      // 项目会话：有 projectId 或标题包含"项目"
      filtered = sessions.filter(s =>
        s.projectId || s.title.includes('项目')
      );
    } else if (activeTab === 'requirement') {
      // 需求会话：标题包含"需求"
      filtered = sessions.filter(s =>
        s.title.includes('需求') || s.title.includes('PRD') || s.title.includes('需求文档')
      );
    } else if (activeTab === 'task') {
      // 任务会话：标题包含"任务"
      filtered = sessions.filter(s =>
        s.title.includes('任务') || s.title.includes('Task')
      );
    }
    setFilteredSessions(filtered);
  }, [activeTab, sessions]);

  // 从 URL hash 解析项目 ID 和会话 ID
  useEffect(() => {
    const hash = window.location.hash;
    const match = hash.match(/#\/chat\?projectId=([^&]*)&sessionId=([^&]*)/);
    if (match) {
      const projectId = match[1];
      const sessionId = match[2];
      setFilterProjectId(projectId);
      setActiveTab('project');
      // 自动加载指定会话
      if (sessionId) {
        const targetSession = sessions.find(s => s.sessionId === sessionId);
        if (targetSession) {
          loadConversation(targetSession);
        }
      }
      // 清除 hash 避免重复触发
      window.location.hash = '';
    }
  }, [sessions]);

  const handleMessageComplete = (text: string, processItems: ProcessItem[], processMs: number, files?: FileInfo[]) => {
    setIsStreaming(false);
    setStreamText('');
    setProcess(null);
    setMessages(prev => [...prev, {
      id: Date.now().toString(),
      role: 'ai' as const,
      content: text,
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
      process: processItems.length > 0 ? processItems : undefined,
      processMs: processItems.length > 0 ? processMs : undefined,
      files: files && files.length > 0 ? files : undefined,
      processExpanded: true,
    }]);
    setStreamingFiles([]);
  };

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages, streamText, process]);

  const loadConversation = async (session: SessionDTO) => {
    setCurrentTitle(session.title);
    setCurrentSessionId(session.sessionId);
    setShowChatView(true);
    try {
      const result = await listMessages(session.sessionId, 1, 100);
      const msgs: Message[] = (result.records || []).map(m => {
        // 解析 content 末尾的 <!--FILES:[...]--> 标记，恢复文件卡片
        const parsed = parseFilesFromContent(m.content || '');
        return {
          id: m.messageId,
          fromBackend: true,
          role: m.messageType === 'assistant' ? 'ai' as const : 'user' as const,
          content: parsed.content,
          files: parsed.files,
          timestamp: m.createdAt ? new Date(m.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '',
        };
      });
      setMessages(msgs);
    } catch {
      setMessages([]);
    }
  };

  /** 重新生成：删除目标 AI 消息及之后的本地消息，SSE 重跑，完成后重载会话回填真实 messageId */
  async function regenerateMessage(messageId: string) {
    if (!currentSessionId || isStreaming || regeneratingId) return;
    const idx = messages.findIndex(m => m.id === messageId);
    if (idx >= 0) {
      setMessages(messages.slice(0, idx));
    }
    setRegeneratingId(messageId);
    setIsStreaming(true);
    setStreamText('');
    setStreamingFiles([]);
    const proc = createProcessStreamHandler(setProcess);
    setProcess(proc.tracker.snapshot());
    try {
      await regenerateMessageStream(currentSessionId, messageId, {
        ...proc.handlers,
        onContent: (text) => {
          proc.tracker.addContent(text);
          setStreamText(prev => prev + text);
        },
        onFile: (file) => { setStreamingFiles(prev => [...prev, file]); },
        onError: (msg) => {
          setChatError(classifyChatError(msg));
        },
        onComplete: () => {
          // 回填真实 messageId：从后端重载会话消息
          loadConversationById(currentSessionId);
        },
      });
      } catch (e) {
      console.error('重新生成失败:', e);
      setChatError(classifyChatError((e as Error).message));
    } finally {
      setRegeneratingId(null);
      setIsStreaming(false);
      setStreamText('');
      setStreamingFiles([]);
    }
  }

  /** 按 sessionId 加载消息（重发完成后回填真实 messageId 用） */
  async function loadConversationById(sessionId: string) {
    const session = sessions.find(s => s.sessionId === sessionId);
    if (session) {
      await loadConversation(session);
    } else {
      try {
        const result = await listMessages(sessionId, 1, 100);
        const msgs: Message[] = (result.records || []).map(m => {
          const parsed = parseFilesFromContent(m.content || '');
          return {
            id: m.messageId,
            fromBackend: true,
            role: m.messageType === 'assistant' ? 'ai' as const : 'user' as const,
            content: parsed.content,
            files: parsed.files,
            timestamp: m.createdAt ? new Date(m.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '',
          };
        });
        setMessages(msgs);
      } catch {
        /* 保留现状 */
      }
    }
  }

  /** 置顶/取消置顶（后端已按 pinned desc 排序，刷新列表即生效） */
  async function togglePin(sessionId: string, pinned: boolean) {
    try {
      await pinSession(sessionId, pinned);
      await loadSessions();
      toast(pinned ? '会话已置顶' : '已取消置顶', 'success');
    } catch (e) {
      console.error('置顶操作失败:', e);
    }
  }

  /** 分享 toggle：开启生成 slug 并复制链接；已分享则取消 */
  async function toggleShare() {
    if (!currentSessionId) return;
    try {
      if (sharedSessionId === currentSessionId) {
        await unshareSession(currentSessionId);
        setSharedSessionId(null);
        toast('已取消分享', 'success');
      } else {
        const dto = await shareSession(currentSessionId);
        setSharedSessionId(currentSessionId);
        if (dto.shareUrl) {
          const link = `${window.location.origin}${dto.shareUrl}`;
          await navigator.clipboard.writeText(link).catch(() => {});
        toast('分享链接已复制', 'success');
        }
      }
    } catch (e) {
      console.error('分享操作失败:', e);
    }
  }

  /** 从 content 末尾解析 <!--FILES:[...]--> 标记，分离正文和文件元信息 */
  function parseFilesFromContent(content: string): { content: string; files?: FileInfo[] } {
    const marker = '\n<!--FILES:';
    const idx = content.lastIndexOf(marker);
    if (idx === -1) return { content };
    const endIdx = content.lastIndexOf('-->');
    if (endIdx === -1 || endIdx <= idx) return { content };
    try {
      const jsonStr = content.substring(idx + marker.length, endIdx);
      const files: FileInfo[] = JSON.parse(jsonStr);
      return { content: content.substring(0, idx), files };
    } catch {
      return { content };
    }
  }

  const sendMessage = async () => {
    if (!input.trim() || isStreaming) return;
    // 无会话时自动创建（缺陷修复：此前 sessionId 为空会导致后端不落库、
    // 引擎不加载历史——界面看似连续对话，实际每轮都是无上下文的独立推理）
    let sessionIdForTurn = currentSessionId;
    if (!sessionIdForTurn) {
      try {
        const session = await createSession({
          title: input.trim().slice(0, 20) || '新对话', type: 1, agent: agentId || undefined,
        });
        sessionIdForTurn = session.sessionId;
        setCurrentSessionId(session.sessionId);
        setCurrentTitle(session.title);
      } catch {
        toast('会话创建失败，请稍后重试', 'error');
        return;
      }
    }
    const userMsg: Message = { id: Date.now().toString(), role: 'user', content: input, timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) };
    setMessages(prev => [...prev, userMsg]);
    const userMessage = input;
    setInput('');
    setIsStreaming(true);
    setStreamText('');
    setStreamingFiles([]);
    setProcess(null);
    setChatError(null); // 新消息开始时清除上一次的错误提示

    // 处理过程时间线：思考/工具/搜索事件按发生顺序实时累积渲染
    const proc = createProcessStreamHandler(setProcess);

    try {
      let accumulatedText = '';
      let accumulatedFiles: FileInfo[] = [];
      let firstContentReceived = false;
      let completed = false;

      await chatStream(
        { message: userMessage, model: selectedModel, agentMode, thinkingStyle, sessionId: sessionIdForTurn, agentId: agentId || undefined },
        {
          ...proc.handlers,
          onContent: (text) => {
            if (!firstContentReceived) {
              firstContentReceived = true;
              // 首段正文输出：闭合当前思考片段（后续 thinking 事件开启新片段）
              proc.handlers.onContentStarted();
            }
            // 正文纳入过程时间线，实现"正文 ↔ 操作行"交错展示
            proc.tracker.addContent(text);
            accumulatedText += text;
            setStreamText(accumulatedText);
          },
          onFile: (file) => {
            accumulatedFiles = [...accumulatedFiles, file];
            setStreamingFiles(accumulatedFiles);
          },
          onError: (error) => {
            if (completed) return;
            completed = true;
            setChatError(classifyChatError(error));
            proc.tracker.finish();
            // 始终显示回复消息：有内容则显示内容，否则显示错误信息（而非静默丢弃）
            handleMessageComplete(
              accumulatedText || `⚠️ ${error}`,
              proc.tracker.snapshot().items,
              proc.tracker.elapsedMs,
              accumulatedFiles
            );
          },
          onComplete: () => {
            if (completed) return;
            completed = true;
            proc.tracker.finish();
            const snapshot = proc.tracker.snapshot();
            // 直接使用局部变量，避免在 state updater 中执行副作用。
            // React StrictMode（Next.js App Router 默认开启）会双重调用 updater 函数，
            // 若在 updater 内调用 handleMessageComplete（含 setMessages 副作用），
            // 会导致回复消息被添加两次，产生重复消息。
            if (accumulatedText) {
              handleMessageComplete(accumulatedText, snapshot.items, snapshot.elapsedMs, accumulatedFiles);
            } else if (snapshot.items.some(i => i.kind === 'thinking' && i.text)) {
              // 模型生成了思考内容但未输出正式回复（token 上限不足导致截断）
              handleMessageComplete(
                '⚠️ AI 生成了思考过程但未能输出正式回复，可能是 token 上限不足导致截断。请尝试增大 max_tokens 或简化问题。',
                snapshot.items,
                snapshot.elapsedMs,
                accumulatedFiles
              );
            } else {
              setIsStreaming(false);
              setStreamText('');
              setProcess(null);
            }
            // 会话标题更新
            if (accumulatedText && sessionIdForTurn && currentTitle === '新对话') {
              const newTitle = accumulatedText.length > 12
                ? accumulatedText.substring(0, 12).replace(/[\n\r]/g, '') + '...'
                : accumulatedText.replace(/[\n\r]/g, '');
              updateSession(sessionIdForTurn, { title: newTitle }).then(() => {
                setCurrentTitle(newTitle);
                loadSessions();
              }).catch(() => {});
            }
          },
        }
      );
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : '发送消息失败';
      setChatError(classifyChatError(message));
      setIsStreaming(false);
      setStreamText('');
      setStreamingFiles([]);
      setProcess(null);
    }
  };

  const startNewChat = async (overrideAgentId?: string) => {
    const aid = overrideAgentId ?? agentId;
    try {
      const session = await createSession({ title: '新对话', type: 1, agent: aid || undefined });
      setCurrentSessionId(session.sessionId);
      setCurrentTitle(session.title);
      setShowChatView(true);
      setMessages([]);
      loadSessions();
    } catch {
      // 创建失败不再静默降级为无会话模式（否则整段对话不落库、无上下文）
      toast('会话创建失败，请检查网络后重试', 'error');
    }
  };
  const backToHome = () => { setShowChatView(false); setMessages([]); dispatch(setPage('dashboard')); };
  const toggleProcess = (msgId: string) => { setMessages(prev => prev.map(m => m.id === msgId ? { ...m, processExpanded: !(m.processExpanded ?? true) } : m)); };

  if (!showChatView) {
    return (
      <div className="flex h-screen w-full main-bg">
        <aside className="w-72 border-r flex flex-col h-full flex-shrink0 sidebar-bg">
          <div className="px-3 pt-3 pb-1"><div className="flex gap-1 rounded-lg p-1 tab-bar-bg">
            {chatTabs.map(tab => (
              <button key={tab.id} onClick={() => setActiveTab(tab.id)} className={`flex-1 py-1.5 px-2 text-xs rounded-md font-medium transition-all ${activeTab === tab.id ? 'text-ink-100 tab-active-bg' : 'text-ink-400 hover:text-ink-200'}`}>{tab.label}</button>
            ))}
          </div></div>
          <div className="px-3 pb-2">
            <button onClick={() => startNewChat()} className="w-full flex items-center justify-center gap-1.5 py-2 rounded-lg border border-tech-500/20 text-xs font-medium text-tech-400 hover:bg-tech-500/10 hover:border-tech-500/30 transition-all"><Plus className="w-3.5 h-3.5" />新增会话</button>
          </div>
          <div className="flex-1 overflow-y-auto scrollbar-thin p-2 space-y-1">
            {loadingSessions ? (
              <div className="flex items-center justify-center py-8"><div className="w-4 h-4 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" /></div>
            ) : filteredSessions.length === 0 ? (
              <div className="px-3 py-8 text-center"><p className="text-xs text-ink-500">暂无会话记录</p></div>
            ) : (
              <>
                <div className="px-3 py-2 text-[10px] font-medium text-ink-500 uppercase">
                  {tabGroupLabels[activeTab] || '会话'}
                </div>
                {filteredSessions.map(session => (
                  <div key={session.sessionId}
                    onClick={() => loadConversation(session)}
                    className={`group/item p-3 rounded-lg cursor-pointer transition-all border ${
                      currentSessionId === session.sessionId ? 'bg-tech-500/10 border-tech-500/20' : 'border-transparent hover:bg-tech-500/5 hover:border-tech-500/10'
                    }`}
                  >
                    <div className="flex items-center gap-1.5">
                      {session.pinned === 1 && <Pin className="w-3 h-3 text-tech-400 flex-shrink-0" aria-label="已置顶" />}
                      <p className="text-sm text-ink-200 truncate flex-1">{session.title}</p>
                      <button
                        onClick={(e) => { e.stopPropagation(); togglePin(session.sessionId, session.pinned !== 1); }}
                        className="p-1 text-ink-500 hover:text-tech-400 rounded transition-all opacity-0 group-hover/item:opacity-100"
                        aria-label={session.pinned === 1 ? '取消置顶' : '置顶'}
                        title={session.pinned === 1 ? '取消置顶' : '置顶'}
                      >{session.pinned === 1 ? <PinOff className="w-3.5 h-3.5" /> : <Pin className="w-3.5 h-3.5" />}</button>
                    </div>
                    <p className="text-xs text-ink-500 mt-1">
                      {session.lastMessageAt ? new Date(session.lastMessageAt).toLocaleDateString('zh-CN') : '刚刚'}
                      {session.messageCount > 0 && ` · ${session.messageCount}条消息`}
                    </p>
                  </div>
                ))}
              </>
            )}
          </div>
          <div className="p-3 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
            <div className="flex items-center gap-3 px-2 py-1.5">
              <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center text-white font-semibold text-xs">{(currentUser?.name || '用').charAt(0)}</div>
              <div className="flex-1 min-w-0"><p className="text-xs text-ink-100 truncate font-medium">{currentUser?.name || '当前用户'}</p></div>
              <button onClick={backToHome} className="p-1.5 text-ink-500 hover:text-tech-400 rounded-md transition-all" title="返回主页"><ArrowLeft className="w-4 h-4" /></button>
            </div>
          </div>
        </aside>
        <ChatHomeView onStartNewChat={startNewChat} onSwitchTab={(tab) => setActiveTab(tab)} />
      </div>
    );
  }

  return (
    <div className="flex h-screen w-full chat-bg">
      {/* 左侧会话列表 - 始终显示 */}
      <aside className="w-72 border-r flex flex-col h-full flex-shrink-0 sidebar-bg">
        <div className="px-3 pt-3 pb-1"><div className="flex gap-1 rounded-lg p-1 tab-bar-bg">
          {chatTabs.map(tab => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)} className={`flex-1 py-1.5 px-2 text-xs rounded-md font-medium transition-all ${activeTab === tab.id ? 'text-ink-100 tab-active-bg' : 'text-ink-400 hover:text-ink-200'}`}>{tab.label}</button>
          ))}
        </div></div>
        <div className="px-3 pb-2">
          <button onClick={() => startNewChat()} className="w-full flex items-center justify-center gap-1.5 py-2 rounded-lg border border-tech-500/20 text-xs font-medium text-tech-400 hover:bg-tech-500/10 hover:border-tech-500/30 transition-all"><Plus className="w-3.5 h-3.5" />新增会话</button>
        </div>
        <div className="flex-1 overflow-y-auto scrollbar-thin p-2 space-y-1">
          {loadingSessions ? (
            <div className="flex items-center justify-center py-8"><div className="w-4 h-4 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" /></div>
          ) : filteredSessions.length === 0 ? (
            <div className="px-3 py-8 text-center"><p className="text-xs text-ink-500">暂无会话记录</p></div>
          ) : (
            <>
              <div className="px-3 py-2 text-[10px] font-medium text-ink-500 uppercase">
                {tabGroupLabels[activeTab] || '会话'}
              </div>
              {filteredSessions.map(session => (
                <div key={session.sessionId} onClick={() => loadConversation(session)}
                  className={`group/item p-3 rounded-lg cursor-pointer transition-all border ${
                    currentSessionId === session.sessionId ? 'bg-tech-500/10 border-tech-500/20' : 'border-transparent hover:bg-tech-500/5 hover:border-tech-500/10'
                  }`}>
                  <div className="flex items-center gap-1.5">
                    {session.pinned === 1 && <Pin className="w-3 h-3 text-tech-400 flex-shrink-0" aria-label="已置顶" />}
                    <p className="text-sm text-ink-200 truncate flex-1">{session.title}</p>
                    <button
                      onClick={(e) => { e.stopPropagation(); togglePin(session.sessionId, session.pinned !== 1); }}
                      className="p-1 text-ink-500 hover:text-tech-400 rounded transition-all opacity-0 group-hover/item:opacity-100"
                      aria-label={session.pinned === 1 ? '取消置顶' : '置顶'}
                      title={session.pinned === 1 ? '取消置顶' : '置顶'}
                    >{session.pinned === 1 ? <PinOff className="w-3.5 h-3.5" /> : <Pin className="w-3.5 h-3.5" />}</button>
                  </div>
                  <p className="text-xs text-ink-500 mt-1">
                    {session.lastMessageAt ? new Date(session.lastMessageAt).toLocaleDateString('zh-CN') : '刚刚'}
                    {session.messageCount > 0 && ` · ${session.messageCount}条消息`}
                  </p>
                </div>
              ))}
            </>
          )}
        </div>
        <div className="p-3 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          <div className="flex items-center gap-3 px-2 py-1.5">
            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center text-white font-semibold text-xs">{(currentUser?.name || '用').charAt(0)}</div>
            <div className="flex-1 min-w-0"><p className="text-xs text-ink-100 truncate font-medium">{currentUser?.name || '当前用户'}</p></div>
            <button onClick={backToHome} className="p-1.5 text-ink-500 hover:text-tech-400 rounded-md transition-all" title="返回主页"><ArrowLeft className="w-4 h-4" /></button>
          </div>
        </div>
      </aside>

      {/* 右侧聊天区域 */}
      <div className="flex-1 flex flex-col h-full">
      <header className="flex items-center justify-between px-6 py-4 border-b backdrop-blur-sm flex-shrink-0" style={{ background: 'rgba(8,18,17,0.5)', borderColor: 'rgba(0,184,148,0.08)' }}>
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400/20 to-tech-600/20 flex items-center justify-center">
            <svg className="w-4 h-4 text-tech-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" /></svg>
          </div>
          <div><h3 className="text-sm font-medium text-ink-100">{currentTitle}</h3><p className="text-xs text-ink-500">文档助手</p></div>
        </div>
        <div className="flex items-center gap-2">
          <button className="p-2 text-ink-400 hover:text-tech-400 rounded-lg transition-all" aria-label="设置"><Settings className="w-4 h-4" /></button>
          <button
            onClick={() => toggleShare()}
            disabled={!currentSessionId || isStreaming}
            className={`p-2 rounded-lg transition-all ${sharedSessionId === currentSessionId ? 'text-tech-400 bg-tech-500/10' : 'text-ink-400 hover:text-tech-400'} disabled:opacity-40`}
            aria-label={sharedSessionId === currentSessionId ? '取消分享' : '分享会话'}
            title={sharedSessionId === currentSessionId ? '取消分享' : '分享会话（复制链接）'}
          ><Share2 className="w-4 h-4" /></button>
          <button className="p-2 text-ink-400 hover:text-tech-400 rounded-lg transition-all" aria-label="历史"><Clock className="w-4 h-4" /></button>
        </div>
      </header>
      <div className="flex-1 overflow-y-auto scrollbar-thin px-6 py-6 space-y-5">
        {messages.map(msg => (
          <div key={msg.id} className={`group flex gap-4 ${msg.role === 'user' ? 'justify-end' : ''} animate-fade-up`}>
            {msg.role === 'ai' && <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-bold text-white">AI</span></div>}
            {msg.role === 'ai' && msg.fromBackend && (
              <button
                onClick={() => regenerateMessage(msg.id)}
                disabled={isStreaming || regeneratingId !== null}
                className="self-start mt-1 p-1.5 text-ink-500 hover:text-tech-400 hover:bg-tech-500/10 rounded-md transition-all opacity-0 group-hover:opacity-100 disabled:opacity-30"
                aria-label="重新生成"
                title="以原始输入重新生成此回复"
              ><RefreshCw className={`w-3.5 h-3.5 ${regeneratingId === msg.id ? 'animate-spin' : ''}`} /></button>
            )}
            <div className={`flex-1 max-w-3xl ${msg.role === 'user' ? 'flex flex-col items-end' : ''}`}>
              {msg.role === 'ai' && msg.process && hasProcessActivity(msg.process) ? (
                <>
                  {/* 有过程事件：正文与操作行交错的时间线（zcode 风格），不再套气泡 */}
                  <AIProcessTimeline items={msg.process} streaming={false} totalMs={msg.processMs} expanded={msg.processExpanded ?? true} onToggle={() => toggleProcess(msg.id)} />
                  {msg.files && msg.files.length > 0 && (
                    <div className="mt-2 space-y-2">
                      {msg.files.map((f, i) => <FileCard key={i} file={f} />)}
                    </div>
                  )}
                </>
              ) : (
                <>
                  <div className={`rounded-xl p-4 ${msg.role === 'ai' ? 'chat-bubble-ai rounded-tl-sm' : 'chat-bubble-user rounded-tr-sm'}`}>
                    <MarkdownRenderer content={msg.content} />
                    {msg.role === 'ai' && msg.files && msg.files.length > 0 && (
                      <div className="mt-3 space-y-2">
                        {msg.files.map((f, i) => <FileCard key={i} file={f} />)}
                      </div>
                    )}
                  </div>
                </>
              )}
              <span className="text-[11px] text-ink-500 mt-1.5 block">{msg.timestamp}</span>
            </div>
            {msg.role === 'user' && <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-cyber-500 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-semibold text-white">张</span></div>}
          </div>
        ))}
        {isStreaming && process && hasProcessActivity(process.items) && (
          <div className="flex gap-4 animate-fade-up">
            <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-bold text-white">AI</span></div>
            <div className="flex-1 max-w-3xl">
              <AIProcessTimeline items={process.items} status={process.status} streaming startAt={process.startedAt} expanded onToggle={() => {}} />
              {streamingFiles.length > 0 && (
                <div className="mt-2 space-y-2">
                  {streamingFiles.map((f, i) => <FileCard key={i} file={f} />)}
                </div>
              )}
            </div>
          </div>
        )}
        {isStreaming && (!process || !hasProcessActivity(process.items)) && (
          <div className="flex gap-4 animate-fade-up">
            <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-bold text-white">AI</span></div>
            <div className="flex-1 max-w-3xl"><div className="chat-bubble-ai rounded-xl rounded-tl-sm p-4">
              <MarkdownRenderer content={streamText} isStreaming />
              {streamingFiles.length > 0 && (
                <div className="mt-3 space-y-2">
                  {streamingFiles.map((f, i) => <FileCard key={i} file={f} />)}
                </div>
              )}
            </div></div>
          </div>
        )}
        {chatError && (
          <div className="flex gap-4 animate-fade-up">
            <div className="w-8 h-8 rounded-md bg-red-500/10 border border-red-500/20 flex items-center justify-center flex-shrink-0 mt-1">
              <AlertTriangle className="w-4 h-4 text-red-400" />
            </div>
            <div className="flex-1 max-w-3xl">
              <ChatErrorBanner info={chatError} onClose={() => setChatError(null)} />
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>
      <div className="px-6 pb-6 pt-2 flex-shrink-0">
        <div className="flex items-center gap-2 mb-3">
          <button className="px-3 py-1.5 text-xs rounded-full text-ink-300 border border-tech-500/10 hover:border-tech-500/25 transition-all" aria-label="上传文件">📄 上传文件</button>
          <button className="px-3 py-1.5 text-xs rounded-full text-ink-300 border border-tech-500/10 hover:border-tech-500/25 transition-all" aria-label="引用上下文">🔗 引用上下文</button>
        </div>
        <div className="relative">
          <textarea value={input} onChange={e => setInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } }} rows={2} placeholder="输入消息... (Enter 发送)" className="w-full px-4 py-3 border rounded-xl text-ink-100 placeholder-ink-500 focus:outline-none input-ink resize-none scrollbar-thin transition-all" style={{ background: 'rgba(21,40,38,0.5)', borderColor: 'rgba(0,184,148,0.1)' }} disabled={isStreaming} />
          <div className="absolute bottom-3 right-3 flex items-center gap-2">
            <button onClick={sendMessage} disabled={isStreaming || !input.trim()} className="p-2 btn-primary text-white rounded-lg disabled:opacity-50" aria-label="发送"><Send className="w-4 h-4" /></button>
          </div>
        </div>
        {/* 模型 / 智能体模式 / 思维方式 下拉选择 */}
        <div className="flex items-center gap-2 mt-3 flex-wrap">
          <div className="flex items-center gap-1.5">
            <span className="text-[11px] text-ink-500 flex-shrink-0">模型</span>
            <CustomSelect value={selectedModel} onChange={setSelectedModel} options={modelOptions} />
          </div>
          <div className="flex items-center gap-1.5">
            <span className="text-[11px] text-ink-500 flex-shrink-0">模式</span>
            <CustomSelect value={agentMode} onChange={setAgentMode} options={agentModeOptions} />
          </div>
          <div className="flex items-center gap-1.5">
            <span className="text-[11px] text-ink-500 flex-shrink-0">智能体</span>
            <CustomSelect value={agentId || ''} onChange={(v) => setAgentId(v || null)} className="w-32" options={[{ value: '', label: '通用助手' }, ...agentOptions]} />
          </div>
          <div className="flex items-center gap-1.5">
            <span className="text-[11px] text-ink-500 flex-shrink-0">思维</span>
            <CustomSelect value={thinkingStyle} onChange={setThinkingStyle} options={thinkingStyleOptions} />
          </div>
        </div>
      </div>
      </div>
    </div>
  );
}
