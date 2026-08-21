// 统计 API - 对接后端 StatsController
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const BASE = getBaseUrl();

async function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const token = getAccessToken();
  return fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  });
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`请求失败: ${res.status}`);
  const json = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '请求失败');
  return json.data;
}

// ==================== 类型定义 ====================

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
  recentExecutions: RecentExecution[];
}

// ==================== API ====================

export async function getDashboardStats(): Promise<DashboardStats> {
  const res = await authFetch(`${BASE}/v1/stats/dashboard`);
  return handleResponse(res);
}

export async function getUsageStats(): Promise<UsageStats> {
  const res = await authFetch(`${BASE}/v1/stats/usage`);
  return handleResponse(res);
}
