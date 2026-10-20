/**
 * @gewu/agent-ui 事件协议（D0-8）。
 *
 * 单一事实来源：与后端 gewu-agent-engine `AgentEvent` / gewu-application `ChatStreamEvent`
 * 逐字段同构（docs/design/42 ADR-004）。协议演进纪律：事件类型只增不改；
 * DTO 字段只增不变语义；破坏性变更须走版本协商（平台 /platform/info）。
 *
 * 基线：HEAD beee59a 协议 fixture 快照（实施计划 V1.2 D0-6）。
 */

// ==================== 事件类型常量（与 Java AgentEvent 常量逐字对齐） ====================

export const ChatEventType = {
  // 基础 8 种
  STATUS: 'status',
  THINKING: 'thinking',
  CONTENT: 'content',
  TOOL_CALL: 'tool_call',
  TOOL_EXECUTING: 'tool_executing',
  TOOL_RESULT: 'tool_result',
  DONE: 'done',
  ERROR: 'error',
  // S9 扩展
  CONTENT_RESET: 'content_reset',
  PLAN_CREATED: 'plan_created',
  PLAN_UPDATED: 'plan_updated',
  SUBAGENT_STATUS: 'subagent_status',
  ASK_USER: 'ask_user',
  // 预算（方案 A，真实发射）
  BUDGET_WARNING: 'budget_warning',
  BUDGET_EXCEEDED: 'budget_exceeded',
  // 治理/验证
  CONFIDENCE_CHECK: 'confidence_check',
  VERIFICATION_RESULT: 'verification_result',
  REFLECTION: 'reflection',
  EXPERIENCE_SAVED: 'experience_saved',
  FAILURE_RECORDED: 'failure_recorded',
  // wenshi 推理系（平台形态）
  WEB_SEARCH_START: 'web_search_start',
  WEB_SEARCH_RESULT: 'web_search_result',
  WEB_VERIFYING: 'web_verifying',
  WEB_VERDICT: 'web_verdict',
  FILE: 'file',
  // SSE 传输层
  /** 服务端 20s 心跳帧，消费端忽略未知 type 即可自然兼容 */
  PING: 'ping',
  /** SSE 流结束哨兵 */
  DONE_SENTINEL: '[DONE]',
} as const;

/** done 事件 finishReason 取值（方案 A 语义） */
export type FinishReason = 'stop' | 'length' | 'rounds' | 'loop' | 'budget' | 'error';

// ==================== 统一响应信封（与 gewu-common Result 同构） ====================

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  timestamp?: number;
  /** 历史前端信封字段，后端实际不返回（以 code===10000 为准） */
  success?: boolean;
}

/** 成功码（gewu-common ResultCode） */
export const RESULT_OK = 10000;

// ==================== 请求 / 响应 DTO ====================

/** 聊天请求（平台 AiChatController ChatRequest 同构） */
export interface ChatRequest {
  message: string;
  agentId?: string;
  sessionId?: string;
  model?: string;
  /** Agent 模式: assistant/expert/creative/precise */
  agentMode?: string;
  /** 思维模式: chain-of-thought/tree-of-thought/react/step-by-step/socratic */
  thinkingStyle?: string;
  /**
   * 客户端幂等 ID：同一次发送动作的重复请求（网络重试/流式重放）后端直接
   * 返回已有消息，避免重复调 LLM 与重复落库。不传时自动生成（单次请求唯一）。
   */
  clientId?: string;
}

/** 生成客户端幂等 ID。优先 crypto.randomUUID，回退时间戳+随机数。 */
export function generateClientId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 同步聊天响应 */
export interface ChatResponse {
  messageId: string;
  content: string;
  finishReason: string;
  toolCalls?: Array<{ id: string; name: string; arguments: string }>;
  usage?: { promptTokens: number; completionTokens: number; totalTokens: number };
}

// ==================== 事件载荷（与 ChatStreamEvent 嵌套类型同构） ====================

/** 网络搜索结果条目（ChatStreamEvent.SearchItemInfo 同构） */
export interface SearchItem {
  url: string;
  title: string;
  snippet: string;
  source: string;
  adopted: boolean;
  discarded: boolean;
  discardReason?: string;
  confidence: number;
}

export interface WebSearchInfo {
  query: string;
  results: SearchItem[];
  adoptedCount: number;
  discardedCount: number;
}

export interface VerifyInfo {
  method: string;
  query: string;
  totalResults: number;
  adoptedCount: number;
  discardedCount: number;
}

export interface FileInfo {
  fileName: string;
  fileType: string;
  mimeType: string;
  fileSize: number;
  downloadUrl: string;
  previewContent: string;
  source: string;
}

/** 任务计划步骤（S9 F5：内置 plan_task 工具产生） */
export interface PlanStepInfo {
  id: string;
  text: string;
  status: 'pending' | 'in_progress' | 'done';
}

/** 执行统计（done 事件 metadata：完成透明度，区分"AI 自主收尾"与"被限制收尾"） */
export interface ExecutionStats {
  /** 工具调用轮次 */
  rounds?: number;
  /** 执行总耗时（毫秒） */
  elapsedMs?: number;
  /** 估算 token 消耗（流式按字符量折算） */
  tokenEstimated?: number;
  /** 时间预算滚动续期次数 */
  timeRenewals?: number;
  /** 轮次预算滚动扩容次数 */
  roundsRenewed?: number;
  /** token 预算是否不限量（未绑定套餐用户） */
  tokenUnlimited?: boolean;
}

/** 工具调用载荷 */
export interface ToolCallInfo {
  id: string;
  name: string;
  arguments: string;
}

export interface ToolResultInfo {
  toolCallId: string;
  output: string;
}

/** 流式事件（与后端 ChatStreamEvent 逐字段同构） */
export interface ChatStreamEvent {
  type: string;
  content?: string;
  reasoning?: string;
  toolCall?: ToolCallInfo;
  toolResult?: ToolResultInfo;
  webSearch?: WebSearchInfo;
  verify?: VerifyInfo;
  file?: FileInfo;
  errorMessage?: string;
  /** LLM 完成原因（done 事件携带：stop/length/rounds/loop/budget） */
  finishReason?: string;
  /** 任务计划标题（plan_created / plan_updated / done 事件携带） */
  planTitle?: string;
  /** 任务计划步骤（plan_created / plan_updated / done 事件携带） */
  plan?: PlanStepInfo[];
  /** 执行统计（done 事件携带） */
  metadata?: ExecutionStats;
}

/** 子代理生命周期载荷（subagent_status 事件 metadata） */
export interface SubagentStatus {
  subagentId: string;
  index: number;
  name: string;
  status: 'running' | 'success' | 'failed';
  result?: string;
  durationMs?: number;
}

/** 写授权载荷（approval_required 事件，D1-21） */
export interface ApprovalRequestPayload {
  approvalId: string;
  tool: string;
  path: string;
  /** 修改前内容（新建文件为 null） */
  before: string | null;
  /** 将写入的内容 */
  after: string | null;
}

/** HITL 问答载荷（ask_user 事件 metadata） */
export interface AskUserPayload {
  askId: string;
  question: string;
  options: string[];
}

// ==================== 平台 REST DTO（web lib/session、lib/project 原定义迁入，单一源） ====================

/** 会话 DTO（与后端 SessionDTO 同构；S9 五列语义见 docs/design/42 §7.3） */
export interface SessionDTO {
  sessionId: string;
  title: string;
  type: number;
  typeDesc: string;
  projectId?: string;
  status: number;
  statusDesc: string;
  isPublic: number;
  pinned?: number;
  slug?: string;
  shareUrl?: string;
  messageCount: number;
  lastMessageAt: number | null;
  agent?: string;
  directory?: string;
  workspaceId?: string;
  createdAt: number;
  createdBy?: string;
}

/** 项目 DTO（与后端 ProjectDTO 同构） */
export interface ProjectDTO {
  projectId: string;
  projectName: string;
  projectCode: string;
  shortName?: string;
  systemName?: string;
  /** 团队成员（逗号分隔姓名） */
  teamMembers?: string;
  techLead?: string;
  description: string;
  visibility: number;
  status: number;
  ownerId: string;
  ownerName: string;
  memberCount: number;
  createdAt: number;
  techStack: string;
  worktree: string;
  iconUrl: string;
  iconColor: string;
  currentPhase: string;
  initiatedAt: number;
  closedAt: number;
  repoUrl?: string;
  /** 当前 Git 分支名（会话顶栏展示，S9 F2） */
  repoBranch?: string;
  cloneStatus?: string;
  headCommit?: string;
  repoLocalPath?: string;
}

/** 聊天错误解析结果（web lib/chatErrors 原定义迁入） */
export interface ChatErrorInfo {
  raw: string;
  /** 第一行摘要（折叠态展示） */
  title: string;
  /** 错误分类 */
  category: string;
  /** 可能原因 */
  cause: string;
  /** 建议处理 */
  suggestion: string;
  /** 发生时间（毫秒时间戳） */
  time: number;
  /** warning 级：非错误但需用户知晓（如回复截断），提示框用琥珀色系 */
  level?: 'error' | 'warning';
}

// ==================== 模型信息 ====================

export interface ModelInfo {
  provider: string;
  name: string;
  modelName: string;
  displayName: string;
  description: string;
  supported: boolean;
}

// ==================== 流式回调集合（chatStream / regenerate 共用） ====================

export interface ChatStreamCallbacks {
  onContent: (text: string) => void;
  onThinking?: (text: string) => void;
  onStatus?: (status: string) => void;
  /** 截断重试前清空已累积的部分正文（旧内容将被整体重新生成，S9） */
  onContentReset?: (note: string) => void;
  onToolCall?: (toolCall: ToolCallInfo) => void;
  onToolExecuting?: (toolCall: ToolCallInfo) => void;
  onToolResult?: (result: ToolResultInfo) => void;
  onWebSearchStart?: (status: string) => void;
  onWebSearchResult?: (info: WebSearchInfo) => void;
  onWebVerifying?: (status: string) => void;
  onWebVerdict?: (verdict: VerifyInfo) => void;
  onFile?: (file: FileInfo) => void;
  /** 执行经验沉淀通知（完成后写入长期记忆） */
  onExperienceSaved?: (note: string) => void;
  /** 失败已记录通知（进入失败案例库） */
  onFailureRecorded?: (reason: string) => void;
  /** 任务计划更新（S9 F5）；planPath=计划文件路径（有则可"查看完整计划"） */
  onPlan?: (title: string, steps: PlanStepInfo[], planPath?: string) => void;
  /** 用户问答（HITL）：ask_user 工具挂起等待用户回答 */
  onAskUser?: (ask: AskUserPayload) => void;
  /** 写授权请求（D1-21）：流挂起等待用户 diff 确认，决策经 /approvals/{id}/decide 恢复 */
  onApprovalRequired?: (req: ApprovalRequestPayload) => void;
  /** 子代理生命周期：running/success/failed 行渲染在主时间线 */
  onSubagentStatus?: (st: SubagentStatus) => void;
  /** 子代理分支内部事件（带 subagentId 标签）：调用方路由到对应子流桶 */
  onSubagentEvent?: (subagentId: string, event: ChatStreamEvent) => void;
  /** 任务预算熔断：已保留部分进度，需重发继续 */
  onBudgetExceeded?: (message: string) => void;
  /** 预算告警（70%/90%/续期）：非阻塞提示剩余预算 */
  onBudgetWarning?: (message: string) => void;
  /** 执行统计（done 事件 metadata） */
  onExecutionStats?: (stats: ExecutionStats) => void;
  onError?: (error: string) => void;
  /** 完成回调，携带 LLM finishReason（length=回复可能被截断） */
  onComplete?: (finishReason?: string) => void;
}

/** 从事件 metadata 提取 planPath（计划文件落盘路径） */
export function planPathOf(event: ChatStreamEvent): string | undefined {
  const meta = event.metadata as unknown as Record<string, unknown> | undefined;
  const p = meta?.planPath;
  return typeof p === 'string' && p ? p : undefined;
}
