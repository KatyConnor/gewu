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
