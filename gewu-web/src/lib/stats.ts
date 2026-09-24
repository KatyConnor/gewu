// 统计 API - 对接后端 StatsController
// T4.3 收敛：统一走 request.ts（axios 拦截器：token 注入/401 跳转）
import { request } from './request';

// ==================== 类型定义 ====================

/** 后端统一响应信封 */
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

/** 信封解包（session.ts 试点范式） */
async function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  const res = await p;
  if (!res || res.code !== 10000) throw new Error(res?.message || '请求失败');
  return res.data;
}

export interface SessionSummary {
  sessionId: string;
  title?: string;
  typeDesc?: string;
  statusDesc?: string;
  messageCount?: number;
  lastMessageAt?: number;
  agent?: string;
}

export interface DashboardStats {
  agentTracked: number;
  avgSuccessRate: number;
  trustDistribution: Record<string, number>;
  executionTotal: number;
  executionSucceeded: number;
  executionFailed: number;
  executionRunning: number;
  recentSessions?: SessionSummary[];
}

export interface RecentExecution {
  executionId: string;
  graphId: string;
  status: string;
  tokenUsed?: number;
  costConsumed?: number;
  startedAt?: number;
  completedAt?: number;
}

export interface UsageStats {
  totalTokenUsed: number;
  totalCost: number;
  byStatus: Record<string, number>;
  /** 对话执行（agent_execution）样本量（T4.1） */
  chatExecutionTotal?: number;
  recentExecutions: RecentExecution[];
}

// ==================== API ====================

export async function getDashboardStats(): Promise<DashboardStats> {
  return unwrap(request.get<ApiResponse<DashboardStats>>('/v1/stats/dashboard'));
}

export async function getUsageStats(): Promise<UsageStats> {
  return unwrap(request.get<ApiResponse<UsageStats>>('/v1/stats/usage'));
}

// ==================== 用量明细统计（配额与统计专项） ====================

export type UsageGranularity = 'day' | 'week' | 'month' | 'quarter' | 'year';

/** 按模型分组的合计 */
export interface ModelTotal {
  modelId: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cost: number;
}

/** 单时间桶的 tokens/成本（含按模型分组） */
export interface BucketUsage {
  bucket: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cost: number;
  models: ModelTotal[];
}

/** 单时间桶的消息数 */
export interface BucketMessages {
  bucket: string;
  userMessages: number;
  agentMessages: number;
  total: number;
}

export interface UsageDetail {
  granularity: string;
  from: number;
  to: number;
  /** SELF=本人 / ALL=全局（管理员） */
  scope: string;
  tokensSeries: BucketUsage[];
  messageSeries: BucketMessages[];
  modelTotals: ModelTotal[];
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  totalCost: number;
  totalUserMessages: number;
  totalAgentMessages: number;
  totalMessages: number;
}

/**
 * 用量明细统计：tokens/成本（按模型分组与合计）+ 消息数（用户/智能体/合计）。
 * @param all true=全局（仅 ADMIN 生效），false=仅本人
 */
export async function getUsageDetail(
  granularity: UsageGranularity,
  days: number,
  all = false
): Promise<UsageDetail> {
  return unwrap(request.get<ApiResponse<UsageDetail>>(
    `/v1/stats/usage-detail?granularity=${granularity}&days=${days}&all=${all}`
  ));
}
