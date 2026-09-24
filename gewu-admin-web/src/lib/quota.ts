// 配额套餐与用户偏好服务 — 对接 QuotaPlanController / UserPreferenceController
import { getAccessToken } from './token';

// 后端统一响应结构
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

// 窗口类型
export type QuotaWindowType = 'FIVE_HOUR' | 'WEEK' | 'MONTH' | 'QUARTER';

export const QUOTA_WINDOW_LABELS: Record<QuotaWindowType, string> = {
  FIVE_HOUR: '5 小时',
  WEEK: '每周',
  MONTH: '每月',
  QUARTER: '每季',
};

// 套餐窗口项
export interface QuotaWindowItem {
  windowType: QuotaWindowType | string;
  tokenLimit: number;
}

// 套餐
export interface QuotaPlan {
  id: string;
  planName: string;
  description?: string;
  status: number; // 1=启用 2=停用
  items: QuotaWindowItem[];
}

// 创建/更新套餐请求
export interface SaveQuotaPlanRequest {
  planName: string;
  description?: string;
  status?: number;
  items: QuotaWindowItem[];
}

// 用户偏好
export interface UserPreference {
  quotaAlertThreshold: number;
  quotaBlockEnabled: boolean;
}

// 单窗口配额状态（用户侧余量展示）
export interface QuotaWindowStatus {
  windowType: QuotaWindowType | string;
  tokenLimit: number;
  usedTokens: number;
  utilization: number;
}

// 配额预检结果
export interface QuotaPreflightResult {
  bound: boolean;
  planName?: string;
  windows: QuotaWindowStatus[];
  maxUtilization: number;
  blockEnabled: boolean;
  shouldBlock: boolean;
  alertThreshold: number;
  alertTriggered: boolean;
  remainingTokens?: number | null;
}

async function request<T>(url: string, init?: RequestInit, errorPrefix = '请求失败'): Promise<T> {
  const token = getAccessToken();
  const res = await fetch(url, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers || {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
  if (!res.ok) throw new Error(`${errorPrefix}: ${res.status}`);
  const json: ApiResponse<T> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || errorPrefix);
  return json.data;
}

const PLANS_BASE = typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE
  ? `${process.env.NEXT_PUBLIC_API_BASE}/v1/quota/plans`
  : '/api/v1/quota/plans';
const PREFS_BASE = typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE
  ? `${process.env.NEXT_PUBLIC_API_BASE}/v1/users/me/preferences`
  : '/api/v1/users/me/preferences';
const MY_QUOTA_BASE = typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE
  ? `${process.env.NEXT_PUBLIC_API_BASE}/v1/users/me/quota`
  : '/api/v1/users/me/quota';

/** 套餐列表（管理员） */
export async function listQuotaPlans(): Promise<QuotaPlan[]> {
  return request<QuotaPlan[]>(PLANS_BASE, undefined, '获取套餐列表失败');
}

/** 创建套餐（管理员） */
export async function createQuotaPlan(data: SaveQuotaPlanRequest): Promise<QuotaPlan> {
  return request<QuotaPlan>(PLANS_BASE, { method: 'POST', body: JSON.stringify(data) }, '创建套餐失败');
}

/** 更新套餐（管理员） */
export async function updateQuotaPlan(planId: string, data: SaveQuotaPlanRequest): Promise<QuotaPlan> {
  return request<QuotaPlan>(`${PLANS_BASE}/${planId}`, { method: 'PUT', body: JSON.stringify(data) }, '更新套餐失败');
}

/** 删除套餐（管理员） */
export async function deleteQuotaPlan(planId: string): Promise<void> {
  await request<void>(`${PLANS_BASE}/${planId}`, { method: 'DELETE' }, '删除套餐失败');
}

/** 绑定用户套餐（管理员） */
export async function bindUserPlan(userId: string, planId: string): Promise<void> {
  await request<void>(`${PLANS_BASE}/bindings/${userId}/${planId}`, { method: 'POST' }, '绑定用户套餐失败');
}

/** 解绑用户套餐（管理员） */
export async function unbindUserPlan(userId: string): Promise<void> {
  await request<void>(`${PLANS_BASE}/bindings/${userId}`, { method: 'DELETE' }, '解绑用户套餐失败');
}

/** 查询我的偏好 */
export async function getMyPreferences(): Promise<UserPreference> {
  return request<UserPreference>(PREFS_BASE, undefined, '获取偏好失败');
}

/** 查询我的套餐余量（未绑定套餐时 bound=false） */
export async function getMyQuota(): Promise<QuotaPreflightResult> {
  return request<QuotaPreflightResult>(MY_QUOTA_BASE, undefined, '获取配额余量失败');
}

/** 更新我的偏好 */
export async function updateMyPreferences(data: Partial<UserPreference>): Promise<UserPreference> {
  return request<UserPreference>(PREFS_BASE, { method: 'PUT', body: JSON.stringify(data) }, '更新偏好失败');
}
