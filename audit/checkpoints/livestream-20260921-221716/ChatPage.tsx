'use client';
import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Send, Settings, Share2, Clock, RefreshCw, Link2, Link2Off, AlertTriangle, FolderOpen, GitBranch, MessageSquare, FileDiff, Loader2, Bot, PanelRightClose } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { setPage, setPendingAgentId, type RootState } from '@/store';
import CustomSelect from '@/components/ui/Select';
import { Message, type ExecutionStats } from '@/types';
import AIProcessTimeline from './AIProcessTimeline';
import AskUserDialog, { type PendingAsk } from './AskUserDialog';
import ChatHomeView from './ChatHomeView';
import SessionSidebar from './SessionSidebar';
import PlanCard from './PlanCard';
import FileEditorPanel, { FILE_PANEL_MIN_WIDTH } from './FileEditorPanel';
import TurnChangesBar from './TurnChangesBar';
import { chatStream, regenerateMessageStream, generateClientId, type PlanStepInfo } from '@/lib/chat';
import { listFileChanges, getTurnFileChanges, undoFileChanges, type FileChangeDTO, type TurnFileChangeDTO } from '@/lib/sessionFileChanges';
import { createProcessStreamHandler, hasProcessActivity, type ProcessSnapshot, type ProcessItem, ProcessTracker } from '@/lib/agentProcess';
import { listActiveModels, type ModelConfig } from '@/lib/model-config';
import { listAgents } from '@/lib/agent';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import FileCard from '@/components/ui/FileCard';
import ChatErrorBanner from '@/components/ui/ChatErrorBanner';
import { classifyChatError, truncationNotice, isRetryableChatError, type ChatErrorInfo } from '@/lib/chatErrors';
import type { FileInfo } from '@/types';
import {
  listMySessions, createSession, updateSession,
  listMessages, pinSession, shareSession, unshareSession,
  archiveSession, unarchiveSession, getSession, getSessionRunStatus, answerSessionAsk,
  getRunProcess, type SessionDTO, type RunStatusDTO, type CompactProcessEntry,
} from '@/lib/session';
import { listMyProjects, type ProjectDTO } from '@/lib/project';


/**
 * 单条历史消息（S9 性能修复）：React.memo 隔离——输入按键/流式 chunk 引发的
 * 父组件重渲染不再重跑全部历史消息的 markdown 解析与代码高亮。
 */
const MessageItem = React.memo(function MessageItem({ msg, isStreaming, regeneratingId, onRegenerate, onToggleProcess, canUndoTurn, undonePaths, onUndoTurnFiles, onReviewTurnFile, onOpenTurnFile, onProcessItemClick }: {
  msg: Message;
  isStreaming: boolean;
  regeneratingId: string | null;
  onRegenerate: (messageId: string) => void;
  onToggleProcess: (msgId: string) => void;
  /** 回合文件汇总条：仅最近一条汇总条可撤销（回合快照只保留最近一回合） */
  canUndoTurn?: boolean;
  undonePaths?: Set<string>;
  onUndoTurnFiles?: (msgId: string, paths?: string[]) => void;
  onReviewTurnFile?: (path: string) => void;
  onOpenTurnFile?: (path: string) => void;
  /** 过程时间线条目点击（二期：子智能体行 → 右侧面板） */
  onProcessItemClick?: (item: ProcessItem) => void;
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
            {/* 完成态（zcode 形态）：过程行折叠可查 + 最终正文渲染在过程之后——
                中间轮次叙述保留在时间线内原位置（用户实报问题3），仅最终
                执行结果/汇总由 msg.content 在整个过程最后输出 */}
            <AIProcessTimeline items={msg.process} streaming={false} totalMs={msg.processMs} expanded={msg.processExpanded ?? false} onToggle={() => onToggleProcess(msg.id)} onItemClick={onProcessItemClick} />
            <div className="mt-3">
              <MarkdownRenderer content={msg.content} />
            </div>
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
        {/* 完成透明度（用户实报：无法区分"任务干完了"与"被预算掐断"）：
            自然收尾且有多轮工具过程时展示执行统计；被限制收尾走 chatError 横幅 */}
        {msg.role === 'ai' && msg.stats && (msg.stats.rounds ?? 0) >= 3 && (
          <div className="mt-2 flex items-center gap-2 text-[11px] text-ink-500" data-testid="execution-stats">
            <span className="text-green-400">✓</span>
            <span>任务由 AI 自主完成收尾 · 共 {msg.stats.rounds} 轮 · 用时 {formatDurationMs(msg.stats.elapsedMs ?? 0)}</span>
            {(msg.stats.roundsRenewed ?? 0) > 0 && <span>· 轮次扩容 {msg.stats.roundsRenewed} 次</span>}
            {(msg.stats.timeRenewals ?? 0) > 0 && <span>· 时间续期 {msg.stats.timeRenewals} 次</span>}
          </div>
        )}
        {/* 回合文件汇总条（撤销功能）：有文件修改的 AI 回复尾部常驻，
            折叠显示统计，展开逐文件 撤销/审查/打开；仅最近一条可撤销 */}
        {msg.role === 'ai' && msg.turnFiles && msg.turnFiles.length > 0 && (
          <TurnChangesBar
            files={msg.turnFiles}
            undonePaths={undonePaths ?? new Set<string>()}
            canUndo={canUndoTurn ?? false}
            onUndoAll={() => onUndoTurnFiles?.(msg.id)}
            onUndoFile={(p) => onUndoTurnFiles?.(msg.id, [p])}
            onReview={(p) => onReviewTurnFile?.(p)}
            onOpen={(p) => onOpenTurnFile?.(p)}
          />
        )}
        <span className="text-[11px] text-ink-500 mt-1.5 block">{msg.timestamp}</span>
      </div>
      {msg.role === 'user' && <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-cyber-500 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-semibold text-white">张</span></div>}
    </div>
  );
});

/** 执行耗时格式化：不足 1 分钟显示秒，否则"X 分 Y 秒"（完成状态行用） */
function formatDurationMs(ms: number): string {
  if (ms < 60_000) return `${Math.max(1, Math.round(ms / 1000))} 秒`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return seconds > 0 ? `${minutes} 分 ${seconds} 秒` : `${minutes} 分钟`;
}

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

// 文件面板拖拽调宽约束：侧栏 w-72=288px；面板最窄=默认 520px（向右拖不越过默认位置），
// 最宽到「聊天区+面板」总宽的 1/2（边界最左移动到整个左右显示区域的 1/2 位置处）
const SIDEBAR_WIDTH_PX = 288;
const FILE_PANEL_WIDTH_KEY = 'gewu_file_panel_width';

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
  // 错误会话归属（用户实报问题2）：错误只属于发生它的会话——切换/新建会话不携带，
  // 与 planSessionId/fileChangesSessionId 同款配对标记，渲染时校验归属
  const [chatErrorSessionId, setChatErrorSessionId] = useState<string | null>(null);
  // 任务流程计划（S9 F5：模型经 plan_task 工具提交，右上角卡片渲染；planPath=计划文件可预览）
  const [plan, setPlan] = useState<{ title: string; steps: PlanStepInfo[]; planPath?: string } | null>(null);
  // HITL 问答（ask_user）：AI 提问挂起等待用户回答，回答后任务恢复执行
  const [pendingAsk, setPendingAsk] = useState<PendingAsk | null>(null);
  const pendingAskRef = useRef<PendingAsk | null>(null);
  useEffect(() => { pendingAskRef.current = pendingAsk; }, [pendingAsk]);
  // 子智能体子流（二期）：按 subagentId 分桶的流式时间线；点击行在右侧面板查看
  const [subProcesses, setSubProcesses] = useState<Record<string, { name: string; snapshot: ProcessSnapshot }>>({});
  const [activeSubagent, setActiveSubagent] = useState<{ id: string; name: string; result?: string } | null>(null);
  const [panelView, setPanelView] = useState<'files' | 'subagent'>('files');
  const subTrackersRef = useRef<Map<string, ProcessTracker>>(new Map());
  const subNamesRef = useRef<Map<string, string>>(new Map());
  const subResultsRef = useRef<Map<string, string>>(new Map());
  // 会话状态隔离（S9 修复）：流式过程属于发起它的会话，切换会话仅隐藏 UI，
  // 流在后台继续生成并照常落库；plan/fileChanges 各自带所属会话标记
  const [streamingSessionId, setStreamingSessionId] = useState<string | null>(null);
  const [planSessionId, setPlanSessionId] = useState<string | null>(null);
  const [fileChangesSessionId, setFileChangesSessionId] = useState<string | null>(null);
  // 回调内读取「当前正在查看的会话」需绕过闭包陈旧值，用 ref 镜像（effect 在 state 声明后绑定）
  const currentSessionIdRef = useRef<string | null>(null);
  // 流式会话本地现场（S9 修复）：切走流式会话时保存消息列表，切回时恢复——
  // 后端此刻尚未落库，从后端重载会用中间态冲掉本地已显示的用户消息
  const streamingMessagesCache = useRef<Map<string, Message[]>>(new Map());
  // 执行统计（完成透明度）：done 事件 metadata 暂存，完成落位时附着到消息
  const execStatsRef = useRef<ExecutionStats | null>(null);
  // 会话文件变更（S9 F3：流结束后拉取，驱动「更改」chip 与右侧编辑面板）
  const [fileChanges, setFileChanges] = useState<FileChangeDTO[]>([]);
  const [filePanelOpen, setFilePanelOpen] = useState(false);
  // 文件面板宽度（拖拽分隔条调整，localStorage 记忆；非法存储值回退默认 520）
  const [filePanelWidth, setFilePanelWidth] = useState(() => {
    if (typeof window === 'undefined') return FILE_PANEL_MIN_WIDTH;
    const saved = Number(window.localStorage.getItem(FILE_PANEL_WIDTH_KEY));
    return Number.isFinite(saved) && saved >= FILE_PANEL_MIN_WIDTH ? saved : FILE_PANEL_MIN_WIDTH;
  });
  const [panelDragging, setPanelDragging] = useState(false);
  // 回合汇总条（撤销功能）：面板外部打开请求（审查/打开定位 tab）+ 已撤销路径标记（msgId → paths）
  const [panelOpenRequest, setPanelOpenRequest] = useState<{ path: string; mode: 'diff' | 'edit'; seq: number } | null>(null);
  const [undoneByMsg, setUndoneByMsg] = useState<Record<string, string[]>>({});
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
  // 后台运行轮次（断连不中断修复）：离开页面后任务继续在服务端执行，
  // 重新进入会话时据此显示"任务执行中"提示条并轮询，完成后自动刷新消息
  const [activeRun, setActiveRun] = useState<{ sessionId: string; startedAt: number; elapsedMs: number } | null>(null);
  const runPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // 执行中任务的实时过程时间线（重进会话可见）：轮询 run/process 刷新，完成时清除
  const [liveRun, setLiveRun] = useState<{ sessionId: string; items: ProcessItem[]; startedAt: number } | null>(null);
  // 断流自动重连（网络类错误）：显示"第 N/10 次"重试状态，10 次耗尽提示终局错误
  const [retryState, setRetryState] = useState<{ sessionId: string; attempt: number; phase: 'reconnecting' | 'resending' } | null>(null);
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);
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

  // 从 URL hash 解析项目 ID 和会话 ID（projectId 可省略——Dashboard/外部入口仅带 sessionId）
  useEffect(() => {
    const hash = window.location.hash;
    const match = hash.match(/#\/chat\?(?:projectId=([^&]*)&)?sessionId=([^&]*)/);
    if (match) {
      const projectId = match[1];
      const sessionId = decodeURIComponent(match[2]);
      if (projectId) {
        setFilterProjectId(projectId);
        setCurrentProjectId(projectId);
      }
      // 自动加载指定会话：先在已加载列表中找，找不到则按 id 直接拉取
      // （修复：此前仅在前 50 条里找，目标会话不在列表时静默不加载）
      if (sessionId) {
        const targetSession = sessions.find(s => s.sessionId === sessionId);
        if (targetSession) {
          loadConversation(targetSession);
        } else {
          const openById = async () => {
            try {
              const dto = await getSession(sessionId);
              loadConversation(dto);
            } catch {
              // 会话不存在/无权限：以最小信息打开（消息区展示空态），不再静默失败
              loadConversation({
                sessionId, title: '会话', type: 1, typeDesc: '', status: 0, statusDesc: '',
                isPublic: 0, messageCount: 0, lastMessageAt: null, createdAt: Date.now(),
              });
            }
          };
          openById();
        }
      }
      // 清除 hash 避免重复触发
      window.location.hash = '';
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  const handleMessageComplete = (text: string, processItems: ProcessItem[], processMs: number, files?: FileInfo[], targetSessionId?: string | null, stats?: ExecutionStats, turnFiles?: TurnFileChangeDTO[]) => {
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
      stats,
      turnFiles,
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

  /** 拉取并解析会话消息（最新 100 条窗口，升序返回）：
   *  loadConversation / 重发回填 / 后台任务完成自动刷新 三处共用 */
  const fetchSessionMessages = async (sessionId: string): Promise<Message[]> => {
    const result = await listMessages(sessionId, 1, 100, 'desc');
    return (result.records || []).map(m => {
      // 解析 content 末尾的 <!--FILES:[...]--> 标记，恢复文件卡片
      let parsed = parseFilesFromContent(m.content || '');
      // 解析 content 末尾的 <!--PLAN:{...}--> 标记，恢复任务流程卡片（S9 F5）
      const planParsed = parsePlanFromContent(parsed.content);
      parsed = { ...parsed, content: planParsed.content };
      if (planParsed.plan) {
        // 最后一条带计划的 AI 消息恢复卡片（历史回放；planPath 供"查看完整计划"）
        setPlan({ ...planParsed.plan, planPath: planParsed.planPath });
      }
      // 还原过程时间线（S9 问题3修复）：metadata.process → msg.process，
      // 历史消息获得与 zcode 一致的「过程折叠 + 结果正文」形态
      const { process, processMs, stats, turnFiles } = parseProcessFromMetadata(m.metadata);
      return {
        id: m.messageId,
        fromBackend: true,
        role: m.messageType === 'assistant' ? 'ai' as const : 'user' as const,
        content: parsed.content,
        files: parsed.files,
        process,
        processMs,
        stats,
        turnFiles,
        timestamp: m.createdAt ? new Date(m.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '',
      };
    });
  };

  // ---- 后台运行轮次轮询（断连不中断修复）----
  /** 停止轮询并清空提示（切换会话/任务完成/卸载时调用） */
  const stopRunPolling = useCallback(() => {
    if (runPollRef.current) {
      clearInterval(runPollRef.current);
      runPollRef.current = null;
    }
    setActiveRun(null);
  }, []);

  /** 拉取执行中任务的实时过程快照（重进会话可见时间线；解析器与历史回放共用） */
  const refreshLiveProcess = useCallback(async (sessionId: string, startedAt: number) => {
    try {
      const rp = await getRunProcess(sessionId);
      setLiveRun({ sessionId, items: parseProcessItems(rp.items || []), startedAt: rp.startedAt || startedAt });
    } catch { /* 过程读取失败：保留横幅即可，下轮重试 */ }
  }, []);

  /** 已知任务活跃时启动轮询：每 5s 复查，结束后自动刷新消息与时间线（重连委托复用） */
  const startRunPolling = (sessionId: string, startedAt: number) => {
    if (runPollRef.current) clearInterval(runPollRef.current);
    setActiveRun({ sessionId, startedAt, elapsedMs: 0 });
    runPollRef.current = setInterval(async () => {
      try {
        const s = await getSessionRunStatus(sessionId);
        if (s.active) {
          setActiveRun({ sessionId, startedAt: s.startedAt, elapsedMs: s.elapsedMs });
          // 挂起问恢复（断连重进/轮询期间出现提问）：渲染问答框，用户回答后任务续跑
          if (s.pendingAsk && !pendingAskRef.current) {
            setPendingAsk({ ...s.pendingAsk, sessionId });
          }
          refreshLiveProcess(sessionId, s.startedAt);
        } else {
          if (runPollRef.current) { clearInterval(runPollRef.current); runPollRef.current = null; }
          setActiveRun(null);
          setLiveRun(null);
          // 后台任务完成：仅当用户仍停留在该会话时自动刷新（消息+时间线回填）
          if (currentSessionIdRef.current === sessionId) {
            try {
              setMessages(await fetchSessionMessages(sessionId));
              jumpToLatest();
            } catch { /* 刷新失败保留下次手动刷新 */ }
            loadFileChanges(sessionId);
          }
          setPendingAsk(null);
          loadSessions();
        }
      } catch { /* 单次状态查询失败，下轮重试 */ }
    }, 5000);
  };

  /** 会话打开时检查后台任务：进行中 → 提示条 + 5s 轮询，结束后自动刷新消息 */
  const checkRunStatus = async (sessionId: string) => {
    try {
      const runStatus = await getSessionRunStatus(sessionId);
      if (runStatus.active) {
        // 挂起问恢复（断连重进后重新渲染问答框）
        if (runStatus.pendingAsk) {
          setPendingAsk({ ...runStatus.pendingAsk, sessionId });
        }
        startRunPolling(sessionId, runStatus.startedAt);
        // 进入即拉取一次实时过程时间线（后续由轮询刷新）
        refreshLiveProcess(sessionId, runStatus.startedAt);
      } else {
        setPendingAsk(null);
        setLiveRun(null);
        stopRunPolling();
      }
    } catch {
      stopRunPolling();
    }
  };

  // 组件卸载：清理轮询定时器
  useEffect(() => () => {
    if (runPollRef.current) clearInterval(runPollRef.current);
  }, []);

  const loadConversation = async (session: SessionDTO) => {
    // 错误会话隔离（用户实报问题2）：报错只属于发生它的会话，切换会话即清除，
    // 不再跟随显示到其他历史会话（错误信息已随消息内容留存在原会话中）
    setChatError(null);
    setChatErrorSessionId(null);
    // 切换会话：终止上一个会话的后台任务轮询与重连循环展示（目标会话如有任务会重新开启）
    stopRunPolling();
    setRetryState(null);
    setLiveRun(null);
    // 切走流式会话前保存本地现场（用户消息/本地渲染尚未落库的部分）
    const leavingSessionId = currentSessionIdRef.current;
    if (leavingSessionId && leavingSessionId === streamingSessionId) {
      streamingMessagesCache.current.set(leavingSessionId, messages);
    }

    // 目标会话正在后台流式生成：不从后端重载（后端是未落库中间态），恢复本地现场，
    // 流式 UI（streamText/process/plan）由后台流继续实时更新
    if (session.sessionId === streamingSessionId) {
      setCurrentTitle(session.title);
      setCurrentSessionId(session.sessionId);
      setCurrentProjectId(session.projectId ?? null);
      setShowChatView(true);
      setShowArchived(false);
      const cached = streamingMessagesCache.current.get(session.sessionId);
      if (cached) {
        setMessages(cached);
      }
      jumpToLatest();
      return;
    }

    setCurrentTitle(session.title);
    setCurrentSessionId(session.sessionId);
    setCurrentProjectId(session.projectId ?? null);
    setShowChatView(true);
    setShowArchived(false);
    setPlan(null);
    setFileChanges([]);
    setFilePanelOpen(false);
    setUndoneByMsg({});
    loadFileChanges(session.sessionId);
    try {
      const msgs = await fetchSessionMessages(session.sessionId);
      setMessages(msgs);
      jumpToLatest();
    } catch {
      setMessages([]);
    }
    // 后台任务感知（断连不中断修复）：该会话有仍在执行的任务 → 提示条 + 轮询，
    // 完成后自动刷新；本会话正在本地流式时不查（现场由后台流驱动）
    if (session.sessionId !== streamingSessionId) {
      checkRunStatus(session.sessionId);
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
            setChatErrorSessionId(targetSession);
          }
        },
        onExecutionStats: (stats) => {
          execStatsRef.current = stats;
        },
        onError: (msg) => {
          // 会话隔离：后台重发流出错时只在停留于该会话时提示（用户实报问题2）
          if (currentSessionIdRef.current === targetSession) {
            setChatError(classifyChatError(msg));
            setChatErrorSessionId(targetSession);
          }
        },
        onComplete: (finishReason?: string) => {
          // 统计已由重载的历史消息携带（metadata.stats），此处仅需清引用防串扰
          execStatsRef.current = null;
          // 截断重试耗尽：明示不完整（S9）
          if (finishReason === 'length' && currentSessionIdRef.current === targetSession) {
            setChatError(truncationNotice());
            setChatErrorSessionId(targetSession);
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
      // 会话隔离：仅当用户仍停留在发起重发的会话时提示
      if (currentSessionIdRef.current === targetSession) {
        setChatError(classifyChatError((e as Error).message));
        setChatErrorSessionId(targetSession);
      }
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
        const msgs = await fetchSessionMessages(sessionId);
        setMessages(msgs);
        jumpToLatest();
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

  /** 解析 content 末尾的 <!--PLAN:{json}--> 标记，恢复任务流程卡片（S9 F5；planPath=计划文件） */
  function parsePlanFromContent(content: string): { content: string; plan?: { title: string; steps: PlanStepInfo[] }; planPath?: string } {
    const marker = '\n<!--PLAN:';
    const idx = content.lastIndexOf(marker);
    if (idx === -1) return { content };
    const endIdx = content.lastIndexOf('-->');
    if (endIdx === -1 || endIdx <= idx) return { content };
    try {
      const jsonStr = content.substring(idx + marker.length, endIdx);
      const parsed = JSON.parse(jsonStr) as { title?: string; steps?: PlanStepInfo[]; planPath?: string };
      if (!Array.isArray(parsed.steps) || parsed.steps.length === 0) return { content };
      return {
        content: content.substring(0, idx),
        plan: { title: parsed.title || '', steps: parsed.steps },
        planPath: parsed.planPath,
      };
    } catch {
      return { content };
    }
  }

  /** 解析 compact 过程条目数组为 ProcessItem[]（历史回放 metadata.process 与实时 run/process 快照共用）。
   *  k='ask' 条目过滤——提问交互由 pendingAsk 弹框承载，时间线不重复渲染 */
  function parseProcessItems(list: CompactProcessEntry[]): ProcessItem[] {
    return list
      .filter(p => p.k !== 'ask')
      .map((p, i) => p.k === 'tool'
        ? {
            kind: 'tool' as const, id: p.id || `tool-h${i}`, name: p.n || 'tool',
            args: p.a, result: p.r, status: 'done' as const,
            startedAt: p.s || 0, endedAt: p.e,
          }
        : p.k === 'subagent'
          ? {
              kind: 'subagent' as const, id: p.id || `sub-h${i}`, name: p.n || 'subagent',
              status: (p.st === 'failed' ? 'failed' : p.st === 'running' ? 'running' : 'success') as 'failed' | 'running' | 'success',
              startedAt: p.s || 0, endedAt: p.e, result: p.r,
            }
          : p.k === 'content'
            ? {
                kind: 'content' as const, id: `content-h${i}`, text: p.t || '',
              }
            : {
                kind: 'thinking' as const, id: `think-h${i}`, text: p.t || '',
                status: 'done' as const, startedAt: p.s || 0, endedAt: p.e,
              });
  }

  /** 解析消息 metadata 中的过程时间线摘要（S9 问题3修复）：还原为折叠过程视图，
   *  支持 thinking / tool / content（中间正文段，用户实报问题3）/ subagent 四类条目。
   *  processMs（用户实报：重进会话后时长显示"几秒"）：优先取后端记录的流式总耗时；
   *  旧数据缺失时以过程条目最大结束偏移 e 兜底推导（近似值，略偏小） */
  function parseProcessFromMetadata(metadata?: string): { process?: ProcessItem[]; processMs?: number; stats?: ExecutionStats; turnFiles?: TurnFileChangeDTO[] } {
    if (!metadata) return {};
    try {
      const meta = JSON.parse(metadata) as {
        process?: CompactProcessEntry[];
        processMs?: number;
        stats?: ExecutionStats;
        turnFiles?: TurnFileChangeDTO[];
      };
      // 执行统计（完成透明度）：历史回放还原"AI 自主收尾"状态行
      const stats = meta.stats && typeof meta.stats === 'object' ? meta.stats : undefined;
      // 回合文件汇总（撤销功能）：历史回放渲染 AI 消息尾部汇总条
      const turnFiles = Array.isArray(meta.turnFiles) && meta.turnFiles.length > 0 ? meta.turnFiles : undefined;
      if (Array.isArray(meta.process) && meta.process.length > 0) {
        const process = parseProcessItems(meta.process);
        const processMs = typeof meta.processMs === 'number' && meta.processMs > 0
          ? meta.processMs
          : Math.max(...meta.process.map(p => p.e ?? 0));
        return { process, processMs, stats, turnFiles };
      }
      return { stats, turnFiles };
    } catch { /* metadata 解析失败按无过程处理 */ }
    return {};
  }

  const sendMessage = async () => {
    if (isStreaming) {
      // 流式进行中（可能在后台会话）：不允许发起第二个流
      toast('有会话正在生成中，请等待完成或回到该会话查看', 'error');
      return;
    }
    // 后台运行防护（断连不中断修复）：该会话有任务仍在服务端执行，
    // 后端同会话并发防护也会拒绝——此处提前拦截给出更明确的提示
    if (activeRun && activeRun.sessionId === currentSessionId) {
      toast('上一轮任务仍在后台执行中，完成后将自动刷新', 'error');
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
    // 闭包内统一使用窄化后的常量（TS 无法跨闭包保持 if 可空窄化）
    const turnSid: string = sessionIdForTurn;
    const userMsg: Message = { id: Date.now().toString(), role: 'user', content: input, timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) };
    setMessages(prev => [...prev, userMsg]);
    const userMessage = input;
    setInput('');
    setIsStreaming(true);
    setStreamText('');
    setStreamingFiles([]);
    setProcess(null);
    setChatError(null); // 新消息开始时清除上一次的错误提示
    setChatErrorSessionId(null);
    setPlan(null); // 新消息开始时清除上一次的任务计划
    // 会话隔离：本轮流式过程归属该会话（切换会话仅隐藏 UI，流后台继续）
    setStreamingSessionId(turnSid);
    // 子流桶清理：本轮的子智能体分桶从零开始
    subTrackersRef.current.clear();
    subNamesRef.current.clear();
    subResultsRef.current.clear();
    setSubProcesses({});
    setActiveSubagent(null);
    setPanelView('files');
    setLiveRun(null);

    // 单轮流式尝试（断流重连场景会多次调用）：返回 null=成功；否则返回原始错误串。
    // 网络类可重试错误不在内部终局（保留现场交给外层重连循环）；
    // 不可重试错误在 onError/请求异常处直接终局展示。
    const attemptTurn = async (): Promise<string | null> => {
      let accumulatedText = '';
      let accumulatedFiles: FileInfo[] = [];
      let firstContentReceived = false;
      let completed = false;
      let failed: string | null = null;
      // 每次尝试使用独立的过程时间线累积器（重发恢复时旧现场已清空重建）
      const proc = createProcessStreamHandler(setProcess);
      setStreamText('');
      setStreamingFiles([]);
      setProcess(proc.tracker.snapshot());

      await chatStream(
        { message: userMessage, model: selectedModel, agentMode, thinkingStyle, sessionId: turnSid, agentId: agentId || undefined, clientId: turnClientId },
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
          onPlan: (title, steps, planPath) => {
            // 任务流程卡片（S9 F5）：plan_created/plan_updated/done 快照实时更新（planPath=计划文件）
            setPlan({ title, steps, planPath });
            setPlanSessionId(sessionIdForTurn);
          },
          onAskUser: (ask) => {
            // HITL 问答（ask_user）：AI 提问挂起，弹框等待用户回答（回答经专用端点恢复执行）
            setPendingAsk({ ...ask, sessionId: turnSid });
          },
          onSubagentStatus: (st) => {
            // 子代理生命周期（二期）：主时间线生命周期行 + 子流桶创建/完结
            if (st.status === 'running') {
              proc.tracker.addSubagent(st.subagentId, st.name);
              if (!subTrackersRef.current.has(st.subagentId)) {
                subTrackersRef.current.set(st.subagentId, new ProcessTracker());
                subNamesRef.current.set(st.subagentId, st.name);
              }
            } else {
              proc.tracker.updateSubagent(st.subagentId, { status: st.status, result: st.result });
              const t = subTrackersRef.current.get(st.subagentId);
              if (t) t.finish();
              subResultsRef.current.set(st.subagentId, st.result || '');
              setActiveSubagent(prev => prev && prev.id === st.subagentId ? { ...prev, result: st.result } : prev);
            }
            setProcess(proc.tracker.snapshot());
          },
          onSubagentEvent: (subagentId, ev) => {
            // 子代理分支事件：路由到对应子流桶（右侧面板按 AIProcessTimeline 同款效果流式渲染）
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
            const snap = t.snapshot();
            setSubProcesses(prev => ({
              ...prev,
              [subagentId]: { name: subNamesRef.current.get(subagentId) || subagentId, snapshot: snap },
            }));
          },
          onBudgetWarning: (message) => {
            // 预算告警（S9 方案A）：非阻塞 toast，仅当前查看的会话提示
            if (currentSessionIdRef.current === turnSid) {
              toast(message, 'info');
            }
          },
          onBudgetExceeded: (message) => {
            // 预算熔断可见化（S9）：仅当前查看的会话提示；后台完成照常落库
            if (currentSessionIdRef.current === turnSid) {
              setChatError({
                raw: message,
                title: '任务预算已用尽，执行已中止（已保留部分进度）',
                category: '预算熔断',
                cause: '本轮执行的时间/上下文预算达到上限（短消息触发轻量级预算配置，而会话任务是长程形态）。',
                suggestion: '已生成的部分内容已保存。点击重发可继续未完成的任务；会话任务的预算下限已提升，复发率将显著降低。',
                time: Date.now(),
                level: 'warning',
              });
              setChatErrorSessionId(turnSid);
            }
          },
          onExecutionStats: (stats) => {
            execStatsRef.current = stats;
          },
          onError: (error) => {
            if (completed) return;
            completed = true;
            proc.tracker.finish();
            // 网络类错误：不终局，交给外层重连循环（最多 10 次）
            if (isRetryableChatError(error)) {
              failed = error;
              return;
            }
            if (currentSessionIdRef.current === turnSid) {
              setChatError(classifyChatError(error));
              setChatErrorSessionId(turnSid);
            }
            // 最终正文拆分（用户实报问题3）：仅最后一轮输出作为消息正文，
            // 中间轮次叙述保留在过程时间线内；无最终正文时退化为错误提示
            const split = proc.tracker.splitFinalContent();
            // 始终显示回复消息：有内容则显示内容，否则显示错误信息（而非静默丢弃）
            handleMessageComplete(
              split.finalText || `⚠️ ${error}`,
              split.items,
              proc.tracker.elapsedMs,
              accumulatedFiles,
              turnSid
            );
          },
          onComplete: async (finishReason?: string) => {
            if (completed) return;
            completed = true;
            proc.tracker.finish();
            const snapshot = proc.tracker.snapshot();
            // 执行统计（完成透明度）：仅自然收尾携带 metadata，取走后清空防串扰
            const turnStats = finishReason === 'rounds' || finishReason === 'loop' || finishReason === 'budget'
              ? null : execStatsRef.current;
            execStatsRef.current = null;
            // 最终正文拆分（用户实报问题3）：仅最后一轮输出作为消息正文（最终执行
            // 结果/汇总在过程之后输出），中间轮次叙述保留在时间线内原位置
            const split = proc.tracker.splitFinalContent();
            const stillViewing = currentSessionIdRef.current === turnSid;
            // finishReason=length：重试预算耗尽后的部分回复，明示不完整（S9）
            if (finishReason === 'length' && stillViewing) {
              setChatError(truncationNotice());
              setChatErrorSessionId(turnSid);
            }
            // 限流收尾持久提示（用户实报：轮次/循环/预算总结此前只有一次性 toast，错过即无痕）
            if (stillViewing && (finishReason === 'rounds' || finishReason === 'loop' || finishReason === 'budget')) {
              setChatError({
                raw: `finishReason=${finishReason}`,
                title: finishReason === 'rounds' ? '任务已达工具调用轮次上限，已自动总结当前进度'
                  : finishReason === 'loop' ? '检测到重复执行循环，已自动总结当前进度'
                  : '任务预算已用尽，已自动总结当前进度',
                category: '执行限流',
                cause: '本轮执行触发了引擎的限额熔断（轮次上限/执行预算/循环检测）。',
                suggestion: '可基于总结内容重新发送"继续任务"接着执行；长任务建议拆分为多个子任务或分阶段推进。',
                time: Date.now(),
                level: 'warning',
              });
              setChatErrorSessionId(turnSid);
            }
            // F3：流结束后拉取会话文件变更（驱动「更改」chip 与编辑面板）
            if (turnSid) {
              loadFileChanges(turnSid);
            }
            // 回合文件汇总（撤销功能）：完成即取回合快照（快照保留至下一回合开始），
            // 附着到本地消息立即渲染汇总条；失败静默（历史回放仍有 metadata.turnFiles）
            let turnFiles: TurnFileChangeDTO[] | undefined;
            if (turnSid) {
              try {
                const fetched = await getTurnFileChanges(turnSid);
                if (fetched.length > 0) turnFiles = fetched;
              } catch { /* 无工作空间等场景静默 */ }
            }
            // 直接使用局部变量，避免在 state updater 中执行副作用。
            // React StrictMode（Next.js App Router 默认开启）会双重调用 updater 函数，
            // 若在 updater 内调用 handleMessageComplete（含 setMessages 副作用），
            // 会导致回复消息被添加两次，产生重复消息。
            if (accumulatedText) {
              // 会话隔离：带 targetSessionId，后台完成的会话不污染当前查看的列表。
              // 最终轮无正文但有中间叙述时回退全量拼接（与后端落库回退语义一致）
              handleMessageComplete(split.finalText || accumulatedText, split.items, snapshot.elapsedMs, accumulatedFiles, turnSid, turnStats ?? undefined, turnFiles);
            } else if (snapshot.items.some(i => i.kind === 'thinking' && i.text)) {
              // 模型生成了思考内容但未输出正式回复（token 上限不足导致截断）
              handleMessageComplete(
                '⚠️ AI 生成了思考过程但未能输出正式回复，可能是 token 上限不足导致截断。请尝试增大 max_tokens 或简化问题。',
                split.items,
                snapshot.elapsedMs,
                accumulatedFiles,
                turnSid
              );
            } else {
              setIsStreaming(false);
              setStreamText('');
              setProcess(null);
            }
            // 会话标题更新
            if (accumulatedText && turnSid && currentTitle === '新对话') {
              const newTitle = accumulatedText.length > 12
                ? accumulatedText.substring(0, 12).replace(/[\n\r]/g, '') + '...'
                : accumulatedText.replace(/[\n\r]/g, '');
              updateSession(turnSid, { title: newTitle }).then(() => {
                if (currentSessionIdRef.current === turnSid) {
                  setCurrentTitle(newTitle);
                }
                loadSessions();
              }).catch(() => {});
            }
          },
        }
      ).catch((e: unknown) => {
        // 请求层失败（连接未建立/中途断开）：网络类错误交给重连循环，其余直接终局
        const message = e instanceof Error ? e.message : '发送消息失败';
        failed = message;
        if (!isRetryableChatError(message) && currentSessionIdRef.current === turnSid) {
          setChatError(classifyChatError(message));
          setChatErrorSessionId(turnSid);
        }
      });
      return failed;
    };

    // 断流自动重连（上限 10 次，页面横幅动态显示次数与状态）：
    // 每轮先探测服务端——任务仍在跑则委托给任务轮询等它完成（衔接"断连不中断"语义），
    // 上一轮已在后台完成落库则直接重载恢复，确认服务端无本轮成果才幂等重发（同 clientId）
    const recoverConnection = async (baselineCount: number, firstError: string): Promise<string | null> => {
      const MAX_RETRIES = 10;
      for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        if (!mountedRef.current) return null; // 页面已卸载：静默终止（服务端任务照常完成落库）
        setRetryState({ sessionId: turnSid, attempt, phase: 'reconnecting' as const });
        await new Promise(r => setTimeout(r, Math.min(attempt, 5) * 1000)); // 递增退避 1s→5s
        if (!mountedRef.current) return null;
        let status: RunStatusDTO | null = null;
        let serverCount = -1;
        try {
          const [st, sess] = await Promise.all([getSessionRunStatus(turnSid), getSession(turnSid)]);
          status = st;
          serverCount = sess.messageCount;
        } catch { /* 网络仍未恢复：计一次失败重试 */ }
        if (status?.active) {
          // 服务端任务仍在执行（断连不中断）：清掉本地半截现场，委托任务轮询等待完成后自动刷新
          setRetryState(null);
          setStreamingSessionId(null);
          setIsStreaming(false);
          setStreamText('');
          setStreamingFiles([]);
          setProcess(null);
          setActiveRun({ sessionId: turnSid, startedAt: status.startedAt, elapsedMs: status.elapsedMs });
          startRunPolling(turnSid, status.startedAt);
          return null;
        }
        if (serverCount >= baselineCount + 2) {
          // 上一轮已在后台完成落库（断开期间跑完）：直接重载恢复，无需重发
          setRetryState(null);
          setStreamingSessionId(null);
          setIsStreaming(false);
          setStreamText('');
          setStreamingFiles([]);
          setProcess(null);
          try {
            setMessages(await fetchSessionMessages(turnSid));
            jumpToLatest();
            loadFileChanges(turnSid);
          } catch { /* 刷新失败保留下次进入手动刷新 */ }
          return null;
        }
        // 服务端无本轮成果：重发同一消息恢复（同 clientId 幂等，用户消息不会重复落库）
        setRetryState({ sessionId: turnSid, attempt, phase: 'resending' as const });
        const retryErr = await attemptTurn();
        if (retryErr === null) {
          setRetryState(null);
          return null;
        }
        if (!isRetryableChatError(retryErr)) {
          if (/13004|正在执行/.test(retryErr)) continue; // 重发撞上活跃任务：下轮探测会转为委托等待
          setRetryState(null);
          return retryErr;
        }
      }
      setRetryState(null);
      return firstError;
    };

    // 发送前本地消息数（重连探测以此判断后台是否已完整落库：完整轮 = baseline + 2）
    const baselineCount = messages.length;
    // 整轮固定 clientId：重发恢复时后端按其幂等去重，用户消息不会重复落库
    const turnClientId = generateClientId();
    let failure: string | null = await attemptTurn();
    if (failure && isRetryableChatError(failure)) {
      failure = await recoverConnection(baselineCount, failure);
    }
    if (failure === null) return;
    // 终局清理
    setIsStreaming(false);
    setStreamText('');
    setStreamingFiles([]);
    setProcess(null);
    setStreamingSessionId(null);
    if (!isRetryableChatError(failure)) return; // 不可重试错误已在 onError/请求异常处终局展示
    // 重试 10 次耗尽：终局错误提示（重试过程已由横幅动态展示）
    if (currentSessionIdRef.current === turnSid) {
      const info = classifyChatError(failure);
      setChatError({
        ...info,
        title: '自动重试 10 次仍未恢复连接',
        cause: `${info.cause} 已在断开后自动重试连接 10 次均失败。`,
        suggestion: '请检查网络与后端服务状态后重新发送；若任务已在服务端完成，重新进入会话即可看到结果。',
        time: Date.now(),
      });
      setChatErrorSessionId(turnSid);
    }
    // 落位一条错误消息终结本轮（后端用户消息已即时落库；后台完成的回复重进会话可见）
    handleMessageComplete(`⚠️ ${failure}`, [], 0, [], turnSid);
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
      setChatError(null);
      setChatErrorSessionId(null);
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
  const backToHome = () => { setShowChatView(false); setMessages([]); setChatError(null); setChatErrorSessionId(null); setRetryState(null); dispatch(setPage('dashboard')); };
  const toggleProcess = useCallback((msgId: string) => { setMessages(prev => prev.map(m => m.id === msgId ? { ...m, processExpanded: !(m.processExpanded ?? false) } : m)); }, []);

  /** 切换/打开会话后直接聚焦到对话最新一条记录（瞬时定位；
   *  跳底后即处于"贴近底部"状态，后续高度变化由既有跟随滚动接管） */
  const jumpToLatest = useCallback(() => {
    const jump = () => {
      const el = scrollContainerRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    };
    requestAnimationFrame(jump);
    setTimeout(jump, 120); // 长会话 markdown 渲染高度后置增长时二次校准
  }, []);

  // ---- 右侧文件面板拖拽调宽（分隔条仅面板打开时渲染）----
  /** 宽度夹取：[默认 520, max(520, (窗口宽 − 侧栏 288) / 2)]；窗口缩小后渲染时同样收敛 */
  const clampPanelWidth = (w: number) => {
    if (typeof window === 'undefined') return w;
    const max = Math.max(FILE_PANEL_MIN_WIDTH, Math.floor((window.innerWidth - SIDEBAR_WIDTH_PX) / 2));
    return Math.min(max, Math.max(FILE_PANEL_MIN_WIDTH, w));
  };

  const handlePanelDividerMouseDown = (e: React.MouseEvent) => {
    e.preventDefault();
    // 拖拽现场走闭包对象（起点/实时宽度），window 级监听保证移出窗口不丢事件
    const drag = { startX: e.clientX, startWidth: filePanelWidth, width: filePanelWidth };
    setPanelDragging(true);
    document.body.style.userSelect = 'none'; // 拖拽中禁止选中文本
    const onMove = (ev: MouseEvent) => {
      drag.width = clampPanelWidth(drag.startWidth - (ev.clientX - drag.startX));
      setFilePanelWidth(drag.width);
    };
    const onUp = () => {
      setPanelDragging(false);
      document.body.style.userSelect = '';
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      try {
        window.localStorage.setItem(FILE_PANEL_WIDTH_KEY, String(drag.width));
      } catch {
        // localStorage 不可用（隐私模式/已被禁用）时仅放弃宽度记忆，不影响本次拖拽结果
      }
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };

  const handlePanelDividerDoubleClick = () => {
    setFilePanelWidth(FILE_PANEL_MIN_WIDTH);
    try {
      window.localStorage.setItem(FILE_PANEL_WIDTH_KEY, String(FILE_PANEL_MIN_WIDTH));
    } catch {
      // 同上：存储不可用时仅放弃持久化，复位行为本身不受影响
    }
  };

  // 当前项目（顶栏 F2：项目文件夹 + Git 分支信息）
  const currentProject = projects.find(p => p.projectId === currentProjectId) ?? null;
  // 流式 UI 仅在「正在查看的会话 = 流式会话」时渲染（切换会话互不串扰）
  const streamingVisible = isStreaming && currentSessionId === streamingSessionId;
  // 后台运行提示（断连不中断修复）：当前查看的会话有任务仍在服务端执行——
  // 显示"任务执行中"提示条、轮询等待完成后自动刷新、期间禁用发送
  const runActiveHere = !!activeRun && activeRun.sessionId === currentSessionId && !streamingVisible;
  // 最后一条携带回合文件汇总的 AI 消息（撤销仅对它可用：快照只保留最近一回合）
  const latestTurnMsgId = [...messages].reverse().find(m => m.turnFiles && m.turnFiles.length > 0)?.id;

  /** 撤销最近一次回复的文件修改（全部或单文件）：成功后刷新变更面板并标记已撤销 */
  const handleUndoTurnFiles = useCallback(async (msgId: string, paths?: string[]) => {
    if (!currentSessionId) return;
    try {
      const results = await undoFileChanges(currentSessionId, paths);
      const undone = results.filter(r => r.success).map(r => r.path);
      const failed = results.filter(r => !r.success);
      if (failed.length > 0) {
        toast(`撤销失败：${failed.map(f => `${f.path}（${f.reason || '未知原因'}）`).join('、')}`, 'error');
      } else {
        toast(paths && paths.length === 1 ? '已撤销该文件的修改' : '已撤销本次回复的全部文件修改', 'success');
      }
      if (undone.length > 0) {
        setUndoneByMsg(prev => ({ ...prev, [msgId]: [...(prev[msgId] ?? []), ...undone] }));
        loadFileChanges(currentSessionId);
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : '撤销失败', 'error');
    }
  }, [currentSessionId, loadFileChanges, toast]);

  /** 点击过程时间线的子智能体行（实时或历史）：右侧面板切换为该智能体的过程/聚合结果 */
  const handleProcessItemClick = useCallback((item: ProcessItem) => {
    if (item.kind !== 'subagent') return;
    setActiveSubagent({ id: item.id, name: item.name, result: item.result });
    setPanelView('subagent');
    setFilePanelOpen(true);
  }, []);

  /** 汇总条「审查/打开」：展开文件面板并定位到对应 tab（openRequest 消费后自动清空） */
  const openTurnFile = useCallback((path: string, mode: 'diff' | 'edit') => {
    if (!currentSessionId) return;
    setPanelView('files');
    setFilePanelOpen(true);
    setPanelOpenRequest(prev => ({ path, mode, seq: (prev?.seq ?? 0) + 1 }));
  }, [currentSessionId]);

  /** 「查看完整计划」：右侧面板以 Markdown 阅读模式预览工作空间 plan/ 目录下的计划文件 */
  const openPlanPreview = useCallback((path: string) => {
    if (!currentSessionId) return;
    setPanelView('files');
    setFilePanelOpen(true);
    setPanelOpenRequest(prev => ({ path, mode: 'edit', seq: (prev?.seq ?? 0) + 1 }));
  }, [currentSessionId]);

  /** 提交问答（选中项/自定义输入；空=跳过，AI 自主决策继续）→ 任务恢复执行 */
  const submitAskAnswer = async (askId: string, answer: string) => {
    if (!pendingAsk || !currentSessionId) return;
    try {
      await answerSessionAsk(currentSessionId, askId, answer);
      setPendingAsk(null);
      toast('回答已提交，任务继续执行中', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : '提交失败', 'error');
    }
  };

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
    <div className="flex h-screen w-full chat-bg overflow-x-hidden">
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

      {/* 右侧聊天区域（min-w-0：允许收缩到内容最小宽度以下，防止撑出页面级横向滚动条） */}
      <div className="relative flex-1 min-w-0 flex flex-col h-full">
      {/* 任务流程卡片（S9 F5）：模型提交任务清单后右上角浮动展示；有计划文件时支持"查看完整计划" */}
      {plan && planSessionId === currentSessionId && (
        <PlanCard
          title={plan.title}
          steps={plan.steps}
          streaming={streamingVisible}
          planPath={plan.planPath}
          onViewFullPlan={openPlanPreview}
        />
      )}
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
                <button onClick={() => { setPanelView('files'); setFilePanelOpen(prev => !prev); }}
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
      {/* 后台任务提示条（断连不中断修复）：任务离开页面后继续执行，回到会话可见进度 */}
      {runActiveHere && activeRun && (
        <div className="mx-6 mt-3 flex items-center gap-2 px-3 py-2 rounded-lg border border-tech-500/20 bg-tech-500/5 text-xs text-tech-300 flex-shrink-0" data-testid="run-active-banner">
          <span className="w-1.5 h-1.5 rounded-full bg-tech-400 animate-pulse flex-shrink-0" />
          任务正在后台执行中 · 已运行 {formatDurationMs(activeRun.elapsedMs)} · 完成后将自动刷新
        </div>
      )}
      {/* 断流自动重连提示条：转圈 + 递增次数，10 次耗尽后转入终局错误提示 */}
      {retryState && retryState.sessionId === currentSessionId && (
        <div className="mx-6 mt-3 flex items-center gap-2 px-3 py-2 rounded-lg border border-gold-500/25 bg-gold-500/5 text-xs text-gold-300 flex-shrink-0" data-testid="retry-banner">
          <Loader2 className="w-3.5 h-3.5 animate-spin flex-shrink-0" />
          <span>连接中断，正在重试连接…</span>
          <span className="font-medium text-gold-400">第 {retryState.attempt}/10 次</span>
          <span className="text-ink-500">{retryState.phase === 'resending' ? '· 恢复发送中' : '· 探测服务中'}</span>
        </div>
      )}
      <div ref={scrollContainerRef} className="flex-1 overflow-y-auto scrollbar-thin px-6 py-6 space-y-5">
        {messages.map(msg => {
          const undone = undoneByMsg[msg.id];
          return (
            <MessageItem
              key={msg.id}
              msg={msg}
              isStreaming={isStreaming}
              regeneratingId={regeneratingId}
              onRegenerate={regenerateMessage}
              onToggleProcess={toggleProcess}
              canUndoTurn={msg.id === latestTurnMsgId && !isStreaming}
              undonePaths={undone ? new Set(undone) : undefined}
              onUndoTurnFiles={handleUndoTurnFiles}
              onReviewTurnFile={(p) => openTurnFile(p, 'diff')}
              onOpenTurnFile={(p) => openTurnFile(p, 'edit')}
              onProcessItemClick={handleProcessItemClick}
            />
          );
        })}
        {streamingVisible && process && hasProcessActivity(process.items) && (
          <div className="flex gap-4 animate-fade-up">
            <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-bold text-white">AI</span></div>
            <div className="flex-1 max-w-3xl">
              <AIProcessTimeline items={process.items} status={process.status} streaming startAt={process.startedAt} expanded onToggle={() => {}} onItemClick={handleProcessItemClick} />
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
        {/* 重进会话（断连不中断）：后台执行中任务的实时过程时间线，轮询刷新直至完成 */}
        {activeRun && activeRun.sessionId === currentSessionId && !streamingVisible
          && liveRun && liveRun.sessionId === currentSessionId && liveRun.items.length > 0 && (
          <div className="flex gap-4 animate-fade-up" data-testid="live-run-timeline">
            <div className="w-8 h-8 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0 mt-1"><span className="text-xs font-bold text-white">AI</span></div>
            <div className="flex-1 max-w-3xl">
              <AIProcessTimeline
                items={liveRun.items}
                streaming
                startAt={liveRun.startedAt}
                expanded
                onToggle={() => {}}
                onItemClick={handleProcessItemClick}
              />
            </div>
          </div>
        )}
        {chatError && chatErrorSessionId === currentSessionId && (
          <div className="flex gap-4 animate-fade-up">
            <div className="w-8 h-8 rounded-md bg-red-500/10 border border-red-500/20 flex items-center justify-center flex-shrink-0 mt-1">
              <AlertTriangle className="w-4 h-4 text-red-400" />
            </div>
            <div className="flex-1 max-w-3xl">
              <ChatErrorBanner info={chatError} onClose={() => { setChatError(null); setChatErrorSessionId(null); }} />
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
          <textarea value={input} onChange={e => setInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } }} rows={2} placeholder={runActiveHere ? '上一轮任务仍在后台执行中，完成后将自动刷新…' : '输入消息... (Enter 发送)'} className="w-full px-4 py-3 border rounded-xl text-ink-100 placeholder-ink-500 focus:outline-none input-ink resize-none scrollbar-thin transition-all" style={{ background: 'rgba(21,40,38,0.5)', borderColor: 'rgba(0,184,148,0.1)' }} disabled={streamingVisible || runActiveHere || !!pendingAsk} />
          <div className="absolute bottom-3 right-3 flex items-center gap-2">
            <button onClick={sendMessage} disabled={streamingVisible || runActiveHere || !!pendingAsk || !input.trim()} className="p-2 btn-primary text-white rounded-lg disabled:opacity-50" aria-label="发送"><Send className="w-4 h-4" /></button>
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

      {/* 拖拽分隔条 + 右侧面板：文件模式（变更列表/diff/编辑）或 子智能体模式（流式过程/聚合结果） */}
      {filePanelOpen && currentSessionId && (
        <>
          <div
            role="separator"
            aria-orientation="vertical"
            aria-label="拖拽调整面板宽度，双击恢复默认"
            title="拖拽调整面板宽度 · 双击恢复默认"
            onMouseDown={handlePanelDividerMouseDown}
            onDoubleClick={handlePanelDividerDoubleClick}
            className={`group/divider w-1 flex-shrink-0 cursor-col-resize flex items-center justify-center transition-colors ${
              panelDragging ? 'bg-tech-500/15' : 'hover:bg-tech-500/10'
            }`}
          >
            <div className={`h-8 w-0.5 rounded-full transition-colors ${
              panelDragging ? 'bg-tech-400/70' : 'bg-transparent group-hover/divider:bg-tech-400/40'
            }`} />
          </div>
          {panelView === 'subagent' && activeSubagent ? (
            /* ===== 子智能体模式：该分支的流式过程时间线（与主时间线同款效果）+ 完成后的聚合结果 ===== */
            <div className="flex-shrink-0 border-l flex flex-col h-full" style={{ width: clampPanelWidth(filePanelWidth), borderColor: 'rgba(0,184,148,0.1)' }}>
              <div className="flex items-center gap-2 px-3 py-2 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
                <Bot className="w-3.5 h-3.5 text-cyber-400 flex-shrink-0" />
                <span className="text-xs font-medium text-ink-200 flex-1 truncate" title={activeSubagent.name}>
                  子智能体 · {activeSubagent.name}
                </span>
                <button onClick={() => setFilePanelOpen(false)} className="p-1.5 text-ink-500 hover:text-tech-400 rounded-md transition-all"
                  aria-label="关闭面板" title="收起面板"><PanelRightClose className="w-4 h-4" /></button>
              </div>
              <div className="flex-1 min-h-0 overflow-y-auto scrollbar-thin px-3 py-3">
                {(() => {
                  const live = subProcesses[activeSubagent.id];
                  return (
                    <>
                      {live && hasProcessActivity(live.snapshot.items) && (
                        <AIProcessTimeline
                          items={live.snapshot.items}
                          status={live.snapshot.status}
                          streaming={streamingVisible}
                          startAt={live.snapshot.startedAt}
                          expanded
                          onToggle={() => {}}
                        />
                      )}
                      {!live && !activeSubagent.result && (
                        <p className="text-xs text-ink-600 py-4">该子智能体的过程数据仅在其执行期间实时展示。</p>
                      )}
                      {activeSubagent.result && (
                        <div className={live ? 'mt-3 border-t pt-3' : ''} style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
                          <p className="text-[11px] text-ink-500 mb-1.5">聚合结果</p>
                          <MarkdownRenderer content={activeSubagent.result} />
                        </div>
                      )}
                    </>
                  );
                })()}
              </div>
            </div>
          ) : (
            <FileEditorPanel
              sessionId={currentSessionId}
              changes={fileChanges}
              width={clampPanelWidth(filePanelWidth)}
              openRequest={panelOpenRequest}
              onOpenRequestConsumed={() => setPanelOpenRequest(null)}
              onClose={() => setFilePanelOpen(false)}
              onChangesRefresh={() => loadFileChanges(currentSessionId)}
            />
          )}
        </>
      )}

      {/* HITL 问答弹框（ask_user）：AI 提问挂起等待用户选择/输入，提交后任务恢复执行 */}
      {pendingAsk && pendingAsk.sessionId === currentSessionId && (
        <AskUserDialog
          ask={pendingAsk}
          onSubmit={submitAskAnswer}
          onSkip={(askId) => { void submitAskAnswer(askId, ''); }}
        />
      )}
    </div>
  );
}
