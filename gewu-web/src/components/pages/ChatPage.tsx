'use client';
import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Send, Settings, Share2, Clock, RefreshCw, Link2, Link2Off, AlertTriangle, FolderOpen, GitBranch, MessageSquare, FileDiff } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { setPage, setPendingAgentId, type RootState } from '@/store';
import CustomSelect from '@/components/ui/Select';
import { Message } from '@/types';
import AIProcessTimeline from './AIProcessTimeline';
import ChatHomeView from './ChatHomeView';
import SessionSidebar from './SessionSidebar';
import PlanCard from './PlanCard';
import FileEditorPanel from './FileEditorPanel';
import { chatStream, regenerateMessageStream, type PlanStepInfo } from '@/lib/chat';
import { listFileChanges, type FileChangeDTO } from '@/lib/sessionFileChanges';
import { createProcessStreamHandler, hasProcessActivity, type ProcessSnapshot, type ProcessItem } from '@/lib/agentProcess';
import { listActiveModels, type ModelConfig } from '@/lib/model-config';
import { listAgents } from '@/lib/agent';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import FileCard from '@/components/ui/FileCard';
import ChatErrorBanner from '@/components/ui/ChatErrorBanner';
import { classifyChatError, truncationNotice, type ChatErrorInfo } from '@/lib/chatErrors';
import type { FileInfo } from '@/types';
import {
  listMySessions, createSession, updateSession,
  listMessages, pinSession, shareSession, unshareSession,
  archiveSession, unarchiveSession,
  type SessionDTO,
} from '@/lib/session';
import { listMyProjects, type ProjectDTO } from '@/lib/project';


/**
 * 单条历史消息（S9 性能修复）：React.memo 隔离——输入按键/流式 chunk 引发的
 * 父组件重渲染不再重跑全部历史消息的 markdown 解析与代码高亮。
 */
const MessageItem = React.memo(function MessageItem({ msg, isStreaming, regeneratingId, onRegenerate, onToggleProcess }: {
  msg: Message;
  isStreaming: boolean;
  regeneratingId: string | null;
  onRegenerate: (messageId: string) => void;
  onToggleProcess: (msgId: string) => void;
}) {
  return (
    <div className={`group flex gap-4 ${msg.role === 'user' ? 'justify-end' : ''} animate-fade-up`}>
      {msg.role === 'ai' && <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-bold text-white">AI</span></div>}
      {msg.role === 'ai' && msg.fromBackend && (
        <button
          onClick={() => onRegenerate(msg.id)}
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
            <AIProcessTimeline items={msg.process} streaming={false} totalMs={msg.processMs} expanded={msg.processExpanded ?? false} onToggle={() => onToggleProcess(msg.id)} />
            {msg.files && msg.files.length > 0 && (
              <div className="mt-2 space-y-2">
                {msg.files.map((f, i) => <FileCard key={i} file={f} />)}
              </div>
            )}
          </>
        ) : (
          <div className={`rounded-xl p-4 ${msg.role === 'ai' ? 'chat-bubble-ai rounded-tl-sm' : 'chat-bubble-user rounded-tr-sm'}`}>
            <MarkdownRenderer content={msg.content} />
            {msg.role === 'ai' && msg.files && msg.files.length > 0 && (
              <div className="mt-3 space-y-2">
                {msg.files.map((f, i) => <FileCard key={i} file={f} />)}
              </div>
            )}
          </div>
        )}
        <span className="text-[11px] text-ink-500 mt-1.5 block">{msg.timestamp}</span>
      </div>
      {msg.role === 'user' && <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-cyber-500 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-semibold text-white">张</span></div>}
    </div>
  );
});

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
  // 任务流程计划（S9 F5：模型经 plan_task 工具提交，右上角卡片渲染）
  const [plan, setPlan] = useState<{ title: string; steps: PlanStepInfo[] } | null>(null);
  // 会话状态隔离（S9 修复）：流式过程属于发起它的会话，切换会话仅隐藏 UI，
  // 流在后台继续生成并照常落库；plan/fileChanges 各自带所属会话标记
  const [streamingSessionId, setStreamingSessionId] = useState<string | null>(null);
  const [planSessionId, setPlanSessionId] = useState<string | null>(null);
  const [fileChangesSessionId, setFileChangesSessionId] = useState<string | null>(null);
  // 回调内读取「当前正在查看的会话」需绕过闭包陈旧值，用 ref 镜像（effect 在 state 声明后绑定）
  const currentSessionIdRef = useRef<string | null>(null);
  // 会话文件变更（S9 F3：流结束后拉取，驱动「更改」chip 与右侧编辑面板）
  const [fileChanges, setFileChanges] = useState<FileChangeDTO[]>([]);
  const [filePanelOpen, setFilePanelOpen] = useState(false);
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
  const [archivedSessions, setArchivedSessions] = useState<SessionDTO[]>([]);
  const [showArchived, setShowArchived] = useState(false);
  const [projects, setProjects] = useState<ProjectDTO[]>([]);
  // 当前项目上下文（会话所属项目/新建会话的归属空间；null=默认空间）
  const [currentProjectId, setCurrentProjectId] = useState<string | null>(null);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [regeneratingId, setRegeneratingId] = useState<string | null>(null);
  const [sharedSessionId, setSharedSessionId] = useState<string | null>(null);
  const [loadingSessions, setLoadingSessions] = useState(false);
  useEffect(() => { currentSessionIdRef.current = currentSessionId; }, [currentSessionId]);
  // 项目筛选
  const [filterProjectId, setFilterProjectId] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
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

  // 加载用户会话记录（活跃 + 已归档两份，归档视图单独展示）
  const loadSessions = useCallback(async () => {
    setLoadingSessions(true);
    try {
      const [active, archived] = await Promise.all([
        listMySessions(1, 50),
        listMySessions(1, 50, { status: 2 }),
      ]);
      setSessions(active.records || []);
      setArchivedSessions(archived.records || []);
    } catch (err) {
      console.error('加载会话记录失败:', err);
      setSessions([]);
      setArchivedSessions([]);
    } finally {
      setLoadingSessions(false);
    }
  }, []);

  // 加载我的项目（侧栏项目分组）
  const loadProjects = useCallback(async () => {
    try {
      const result = await listMyProjects({ page: 1, size: 50 });
      setProjects(result.records || []);
    } catch {
      setProjects([]);
    }
  }, []);

  useEffect(() => { loadSessions(); }, [loadSessions]);
  useEffect(() => { loadProjects(); }, [loadProjects]);

  // 从 URL hash 解析项目 ID 和会话 ID
  useEffect(() => {
    const hash = window.location.hash;
    const match = hash.match(/#\/chat\?projectId=([^&]*)&sessionId=([^&]*)/);
    if (match) {
      const projectId = match[1];
      const sessionId = match[2];
      setFilterProjectId(projectId);
      setCurrentProjectId(projectId);
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

  // 加载会话文件变更（S9 F3）：流结束后或切换会话时拉取
  const loadFileChanges = useCallback(async (sessionId: string) => {
    try {
      const changes = await listFileChanges(sessionId);
      setFileChanges(changes);
      setFileChangesSessionId(sessionId);
    } catch {
      // 会话无工作空间/无变更记录时静默（面板数据为空即可）
      setFileChanges([]);
      setFileChangesSessionId(sessionId);
    }
  }, []);

  const handleMessageComplete = (text: string, processItems: ProcessItem[], processMs: number, files?: FileInfo[], targetSessionId?: string | null) => {
    setIsStreaming(false);
    setStreamText('');
    setProcess(null);
    setStreamingSessionId(null);
    setStreamingFiles([]);
    // 完成落位守卫：仅当用户仍停留在发起流式的会话时才 append 本地消息；
    // 已切走时不污染当前列表（后端照常落库，重新进入该会话时从后端重载）
    if (targetSessionId && targetSessionId !== currentSessionIdRef.current) {
      return;
    }
    setMessages(prev => [...prev, {
      id: Date.now().toString(),
      role: 'ai' as const,
      content: text,
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
      process: processItems.length > 0 ? processItems : undefined,
      processMs: processItems.length > 0 ? processMs : undefined,
      files: files && files.length > 0 ? files : undefined,
      processExpanded: false,
    }]);
  };

  // 自动滚动节流（S9 性能修复）：仅当用户停留在底部附近时跟随，且 rAF 合帧，
  // 避免长会话下每个流式 chunk 都强制整页 layout
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;
    const distance = container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distance > 160) return;
    const raf = requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }));
    return () => cancelAnimationFrame(raf);
  }, [messages, streamText, process]);

  const loadConversation = async (session: SessionDTO) => {
    setCurrentTitle(session.title);
    setCurrentSessionId(session.sessionId);
    setCurrentProjectId(session.projectId ?? null);
    setShowChatView(true);
    setShowArchived(false);
    setPlan(null);
    setFileChanges([]);
    setFilePanelOpen(false);
    loadFileChanges(session.sessionId);
    try {
      const result = await listMessages(session.sessionId, 1, 100);
      const msgs: Message[] = (result.records || []).map(m => {
        // 解析 content 末尾的 <!--FILES:[...]--> 标记，恢复文件卡片
        let parsed = parseFilesFromContent(m.content || '');
        // 解析 content 末尾的 <!--PLAN:{...}--> 标记，恢复任务流程卡片（S9 F5）
        const planParsed = parsePlanFromContent(parsed.content);
        parsed = { ...parsed, content: planParsed.content };
        if (planParsed.plan) {
          // 最后一条带计划的 AI 消息恢复卡片（历史回放）
          setPlan(planParsed.plan);
        }
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
  const regenerateMessage = useCallback(async (messageId: string) => {
    if (!currentSessionId || isStreaming || regeneratingId) return;
    const targetSession = currentSessionId;
    const idx = messages.findIndex(m => m.id === messageId);
    if (idx >= 0) {
      setMessages(messages.slice(0, idx));
    }
    setRegeneratingId(messageId);
    setIsStreaming(true);
    setStreamText('');
    setStreamingFiles([]);
    // 会话隔离：重发生成的流式状态归属当前会话
    setStreamingSessionId(targetSession);
    const proc = createProcessStreamHandler(setProcess);
    setProcess(proc.tracker.snapshot());
    try {
      await regenerateMessageStream(currentSessionId, messageId, {
        ...proc.handlers,
        onContent: (text) => {
          proc.tracker.addContent(text);
          setStreamText(prev => prev + text);
        },
        onContentReset: () => {
          // 截断重试：清空已累积的部分正文（旧内容将被整体重新生成）
          setStreamText('');
          proc.tracker.resetContent();
        },
        onFile: (file) => { setStreamingFiles(prev => [...prev, file]); },
        onPlan: (title, steps) => {
          // 重发场景同样更新任务流程卡片（归属当前会话）
          setPlan({ title, steps });
          setPlanSessionId(targetSession);
        },
        onBudgetWarning: (message) => {
          if (currentSessionIdRef.current === targetSession) {
            toast(message, 'info');
          }
        },
        onBudgetExceeded: (message) => {
          if (currentSessionIdRef.current === targetSession) {
            setChatError({
              raw: message,
              title: '任务预算已用尽，执行已中止（已保留部分进度）',
              category: '预算熔断',
              cause: '本轮执行的时间/上下文预算达到上限。',
              suggestion: '已生成的部分内容已保存。可重发继续未完成的任务。',
              time: Date.now(),
              level: 'warning',
            });
          }
        },
        onError: (msg) => {
          setChatError(classifyChatError(msg));
        },
        onComplete: (finishReason?: string) => {
          // 截断重试耗尽：明示不完整（S9）
          if (finishReason === 'length' && currentSessionIdRef.current === targetSession) {
            setChatError(truncationNotice());
          }
          // F3：重发生成后刷新文件变更列表
          loadFileChanges(targetSession);
          // 回填真实 messageId：仅当用户仍停留在该会话时重载列表
          if (currentSessionIdRef.current === targetSession) {
            loadConversationById(targetSession);
          }
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
      setStreamingSessionId(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [messages, currentSessionId, isStreaming, regeneratingId, currentTitle]);

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

  /** 解析 content 末尾的 <!--PLAN:{json}--> 标记，恢复任务流程卡片（S9 F5） */
  function parsePlanFromContent(content: string): { content: string; plan?: { title: string; steps: PlanStepInfo[] } } {
    const marker = '\n<!--PLAN:';
    const idx = content.lastIndexOf(marker);
    if (idx === -1) return { content };
    const endIdx = content.lastIndexOf('-->');
    if (endIdx === -1 || endIdx <= idx) return { content };
    try {
      const jsonStr = content.substring(idx + marker.length, endIdx);
      const parsed = JSON.parse(jsonStr) as { title?: string; steps?: PlanStepInfo[] };
      if (!Array.isArray(parsed.steps) || parsed.steps.length === 0) return { content };
      return { content: content.substring(0, idx), plan: { title: parsed.title || '', steps: parsed.steps } };
    } catch {
      return { content };
    }
  }

  const sendMessage = async () => {
    if (isStreaming) {
      // 流式进行中（可能在后台会话）：不允许发起第二个流
      toast('有会话正在生成中，请等待完成或回到该会话查看', 'error');
      return;
    }
    if (!input.trim()) return;
    // 无会话时自动创建（缺陷修复：此前 sessionId 为空会导致后端不落库、
    // 引擎不加载历史——界面看似连续对话，实际每轮都是无上下文的独立推理）
    let sessionIdForTurn = currentSessionId;
    if (!sessionIdForTurn) {
      try {
        const session = await createSession({
          title: input.trim().slice(0, 20) || '新对话', type: 1,
          agent: agentId || undefined,
          projectId: currentProjectId ?? undefined,
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
    setPlan(null); // 新消息开始时清除上一次的任务计划
    // 会话隔离：本轮流式过程归属该会话（切换会话仅隐藏 UI，流后台继续）
    setStreamingSessionId(sessionIdForTurn);

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
          onContentReset: () => {
            // 截断重试：清空已累积的部分正文（旧内容将被整体重新生成）
            accumulatedText = '';
            setStreamText('');
            proc.tracker.resetContent();
          },
          onFile: (file) => {
            accumulatedFiles = [...accumulatedFiles, file];
            setStreamingFiles(accumulatedFiles);
          },
          onPlan: (title, steps) => {
            // 任务流程卡片（S9 F5）：plan_created/plan_updated/done 快照实时更新
            setPlan({ title, steps });
            setPlanSessionId(sessionIdForTurn);
          },
          onBudgetWarning: (message) => {
            // 预算告警（S9 方案A）：非阻塞 toast，仅当前查看的会话提示
            if (currentSessionIdRef.current === sessionIdForTurn) {
              toast(message, 'info');
            }
          },
          onBudgetExceeded: (message) => {
            // 预算熔断可见化（S9）：仅当前查看的会话提示；后台完成照常落库
            if (currentSessionIdRef.current === sessionIdForTurn) {
              setChatError({
                raw: message,
                title: '任务预算已用尽，执行已中止（已保留部分进度）',
                category: '预算熔断',
                cause: '本轮执行的时间/上下文预算达到上限（短消息触发轻量级预算配置，而会话任务是长程形态）。',
                suggestion: '已生成的部分内容已保存。点击重发可继续未完成的任务；会话任务的预算下限已提升，复发率将显著降低。',
                time: Date.now(),
                level: 'warning',
              });
            }
          },
          onError: (error) => {
            if (completed) return;
            completed = true;
            if (currentSessionIdRef.current === sessionIdForTurn) {
              setChatError(classifyChatError(error));
            }
            proc.tracker.finish();
            // 始终显示回复消息：有内容则显示内容，否则显示错误信息（而非静默丢弃）
            handleMessageComplete(
              accumulatedText || `⚠️ ${error}`,
              proc.tracker.snapshot().items,
              proc.tracker.elapsedMs,
              accumulatedFiles,
              sessionIdForTurn
            );
          },
          onComplete: (finishReason?: string) => {
            if (completed) return;
            completed = true;
            proc.tracker.finish();
            const snapshot = proc.tracker.snapshot();
            const stillViewing = currentSessionIdRef.current === sessionIdForTurn;
            // finishReason=length：重试预算耗尽后的部分回复，明示不完整（S9）
            if (finishReason === 'length' && stillViewing) {
              setChatError(truncationNotice());
            }
            // F3：流结束后拉取会话文件变更（驱动「更改」chip 与编辑面板）
            if (sessionIdForTurn) {
              loadFileChanges(sessionIdForTurn);
            }
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
                accumulatedFiles,
                sessionIdForTurn
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
                if (currentSessionIdRef.current === sessionIdForTurn) {
                  setCurrentTitle(newTitle);
                }
                loadSessions();
              }).catch(() => {});
            }
          },
        }
      );
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : '发送消息失败';
      if (currentSessionIdRef.current === sessionIdForTurn) {
        setChatError(classifyChatError(message));
      }
      setIsStreaming(false);
      setStreamText('');
      setStreamingFiles([]);
      setProcess(null);
      setStreamingSessionId(null);
    }
  };

  /** 新建会话：projectId 非空=项目会话（文件操作走项目仓库目录），null=默认空间 */
  const startNewChat = async (overrideAgentId?: string, projectId?: string | null) => {
    const aid = overrideAgentId ?? agentId;
    const pid = projectId !== undefined ? projectId : currentProjectId;
    try {
      const session = await createSession({
        title: '新对话', type: 1,
        agent: aid || undefined,
        projectId: pid ?? undefined,
      });
      setCurrentSessionId(session.sessionId);
      setCurrentTitle(session.title);
      setCurrentProjectId(pid ?? null);
      setShowChatView(true);
      setMessages([]);
      setFileChanges([]);
      setFilePanelOpen(false);
      loadSessions();
    } catch {
      // 创建失败不再静默降级为无会话模式（否则整段对话不落库、无上下文）
      toast('会话创建失败，请检查网络后重试', 'error');
    }
  };

  /** 在指定项目/默认空间新建会话（侧栏项目名右侧 + 按钮） */
  const handleCreateSessionIn = (projectId: string | null) => {
    startNewChat(undefined, projectId);
  };

  /** 归档/取消归档（S9 F1）：成功后刷新两份列表 */
  const handleArchiveSession = async (sessionId: string) => {
    try {
      await archiveSession(sessionId);
      toast('会话已归档', 'success');
      await loadSessions();
    } catch (e) {
      console.error('归档失败:', e);
    }
  };
  const handleUnarchiveSession = async (sessionId: string) => {
    try {
      await unarchiveSession(sessionId);
      toast('已取消归档', 'success');
      await loadSessions();
    } catch (e) {
      console.error('取消归档失败:', e);
    }
  };
  const backToHome = () => { setShowChatView(false); setMessages([]); dispatch(setPage('dashboard')); };
  const toggleProcess = useCallback((msgId: string) => { setMessages(prev => prev.map(m => m.id === msgId ? { ...m, processExpanded: !(m.processExpanded ?? false) } : m)); }, []);

  // 当前项目（顶栏 F2：项目文件夹 + Git 分支信息）
  const currentProject = projects.find(p => p.projectId === currentProjectId) ?? null;
  // 流式 UI 仅在「正在查看的会话 = 流式会话」时渲染（切换会话互不串扰）
  const streamingVisible = isStreaming && currentSessionId === streamingSessionId;

  if (!showChatView) {
    return (
      <div className="flex h-screen w-full main-bg">
        <SessionSidebar
          sessions={sessions}
          archivedSessions={archivedSessions}
          projects={projects}
          activeSessionId={currentSessionId}
          currentProjectId={currentProjectId}
          showArchived={showArchived}
          onToggleArchived={() => setShowArchived(prev => !prev)}
          onSelectSession={loadConversation}
          onCreateSession={handleCreateSessionIn}
          onArchive={handleArchiveSession}
          onUnarchive={handleUnarchiveSession}
          onPin={togglePin}
          loading={loadingSessions}
          user={currentUser}
          onBackHome={backToHome}
          streamingSessionId={streamingSessionId}
        />
        <ChatHomeView onStartNewChat={startNewChat} onSwitchTab={(tab) => setActiveTab(tab)} />
      </div>
    );
  }

  return (
    <div className="flex h-screen w-full chat-bg">
      {/* 左侧会话列表 - 始终显示（项目树形分组） */}
      <SessionSidebar
        sessions={sessions}
        archivedSessions={archivedSessions}
        projects={projects}
        activeSessionId={currentSessionId}
        currentProjectId={currentProjectId}
        showArchived={showArchived}
        onToggleArchived={() => setShowArchived(prev => !prev)}
        onSelectSession={loadConversation}
        onCreateSession={handleCreateSessionIn}
        onArchive={handleArchiveSession}
        onUnarchive={handleUnarchiveSession}
        onPin={togglePin}
        loading={loadingSessions}
        user={currentUser}
        onBackHome={backToHome}
        streamingSessionId={streamingSessionId}
      />

      {/* 右侧聊天区域 */}
      <div className="relative flex-1 flex flex-col h-full">
      {/* 任务流程卡片（S9 F5）：模型提交任务清单后右上角浮动展示 */}
      {plan && planSessionId === currentSessionId && <PlanCard title={plan.title} steps={plan.steps} streaming={streamingVisible} />}
      <header className="flex items-center justify-between px-6 py-4 border-b backdrop-blur-sm flex-shrink-0" style={{ background: 'rgba(8,18,17,0.5)', borderColor: 'rgba(0,184,148,0.08)' }}>
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400/20 to-tech-600/20 flex items-center justify-center flex-shrink-0">
            <svg className="w-4 h-4 text-tech-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" /></svg>
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-sm font-medium text-ink-100 truncate">{currentTitle}</h3>
              {/* F2：项目文件夹与 Git 分支信息（无项目会话显示默认空间） */}
              {currentProject ? (
                <>
                  <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-tech-500/10 border border-tech-500/20 text-[11px] text-tech-300"
                    title={`项目工作空间：${currentProject.worktree || currentProject.repoLocalPath || '项目目录'}`}>
                    <FolderOpen className="w-3 h-3" />
                    {currentProject.projectName}
                  </span>
                  {currentProject.repoBranch && (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-ink-800/60 border border-ink-700/60 text-[11px] text-ink-300"
                      title={`Git 分支：${currentProject.repoBranch}${currentProject.headCommit ? ` @ ${currentProject.headCommit.slice(0, 7)}` : ''}`}>
                      <GitBranch className="w-3 h-3 text-tech-400" />
                      {currentProject.repoBranch}
                    </span>
                  )}
                </>
              ) : (
                currentSessionId && (
                  <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-ink-800/60 border border-ink-700/60 text-[11px] text-ink-400"
                    title="未关联项目：文件操作在用户默认工作空间执行">
                    <MessageSquare className="w-3 h-3" />
                    默认空间
                  </span>
                )
              )}
              {/* F3：会话文件更改 chip（点击展开/收起右侧编辑面板） */}
              {fileChanges.length > 0 && currentSessionId && fileChangesSessionId === currentSessionId && (
                <button onClick={() => setFilePanelOpen(prev => !prev)}
                  className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md text-[11px] border transition-all ${
                    filePanelOpen
                      ? 'text-tech-300 bg-tech-500/10 border-tech-500/30'
                      : 'text-ink-300 bg-ink-800/60 border-ink-700/60 hover:border-tech-500/30'
                  }`}
                  title="查看本次会话编辑的所有文件">
                  <FileDiff className="w-3 h-3" />
                  {fileChanges.length} 个文件已更改
                  <span className="text-green-400/80">+{fileChanges.reduce((s, c) => s + (c.additions || 0), 0)}</span>
                  <span className="text-red-400/70">-{fileChanges.reduce((s, c) => s + (c.deletions || 0), 0)}</span>
                </button>
              )}
            </div>
            <p className="text-xs text-ink-500">{currentProject ? `项目会话 · ${currentProject.projectCode || ''}` : '文档助手'}</p>
          </div>
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
      <div ref={scrollContainerRef} className="flex-1 overflow-y-auto scrollbar-thin px-6 py-6 space-y-5">
        {messages.map(msg => (
          <MessageItem
            key={msg.id}
            msg={msg}
            isStreaming={isStreaming}
            regeneratingId={regeneratingId}
            onRegenerate={regenerateMessage}
            onToggleProcess={toggleProcess}
          />
        ))}
        {streamingVisible && process && hasProcessActivity(process.items) && (
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
        {streamingVisible && (!process || !hasProcessActivity(process.items)) && (
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
          <textarea value={input} onChange={e => setInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } }} rows={2} placeholder="输入消息... (Enter 发送)" className="w-full px-4 py-3 border rounded-xl text-ink-100 placeholder-ink-500 focus:outline-none input-ink resize-none scrollbar-thin transition-all" style={{ background: 'rgba(21,40,38,0.5)', borderColor: 'rgba(0,184,148,0.1)' }} disabled={streamingVisible} />
          <div className="absolute bottom-3 right-3 flex items-center gap-2">
            <button onClick={sendMessage} disabled={streamingVisible || !input.trim()} className="p-2 btn-primary text-white rounded-lg disabled:opacity-50" aria-label="发送"><Send className="w-4 h-4" /></button>
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

      {/* 右侧文件编辑面板（S9 F3）：默认折叠，「更改」chip 或工具行触发打开 */}
      {filePanelOpen && currentSessionId && (
        <FileEditorPanel
          sessionId={currentSessionId}
          changes={fileChanges}
          onClose={() => setFilePanelOpen(false)}
          onChangesRefresh={() => loadFileChanges(currentSessionId)}
        />
      )}
    </div>
  );
}
