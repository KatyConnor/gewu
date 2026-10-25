// 应用系统管理 API - 对接后端 AppSystemController
// 统一走 request.ts（axios 拦截器：token 注入/401 跳转）
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

export interface AppSystemDTO {
  systemId: string;
  systemCode: string;
  systemName: string;
  shortName: string | null;
  systemType: string;
  orgId: string | null;
  orgName: string | null;
  ownerName: string;
  ownerContact: string | null;
  repoUrl: string | null;
  criticality: number;
  status: number;
  description: string | null;
  sortOrder: number;
  serviceCount: number;
  createdAt: number;
  updatedAt: number;
}

export interface AppServiceDTO {
  serviceId: string;
  systemId: string;
  serviceCode: string;
  serviceName: string;
  serviceType: string;
  baseUrl: string | null;
  healthPath: string | null;
  version: string | null;
  ownerName: string;
  status: number;
  description: string | null;
  sortOrder: number;
  createdAt: number;
  updatedAt: number;
}

// ==================== 枚举展示映射 ====================

/** 系统分类 */
export const SYSTEM_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'BUSINESS', label: '业务系统' },
  { value: 'PLATFORM', label: '平台系统' },
  { value: 'MIDDLEWARE', label: '中间件' },
  { value: 'DATA', label: '数据系统' },
  { value: 'TOOL', label: '工具支撑' },
];

/** 服务类型 */
export const SERVICE_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'BACKEND', label: '后端服务' },
  { value: 'FRONTEND', label: '前端应用' },
  { value: 'GATEWAY', label: '网关' },
  { value: 'DATABASE', label: '数据库' },
  { value: 'MIDDLEWARE', label: '中间件' },
  { value: 'SCHEDULE', label: '定时任务' },
  { value: 'MQ', label: '消息队列' },
];

/** 重要等级：1核心 2重要 3一般 */
export const CRITICALITY_OPTIONS: { value: number; label: string; color: string }[] = [
  { value: 1, label: '核心', color: 'text-cinnabar-400' },
  { value: 2, label: '重要', color: 'text-gold-400' },
  { value: 3, label: '一般', color: 'text-ink-400' },
];

export const STATUS_OPTIONS: { value: number; label: string }[] = [
  { value: 1, label: '启用' },
  { value: 2, label: '停用' },
];

export function systemTypeLabel(value: string): string {
  return SYSTEM_TYPE_OPTIONS.find(o => o.value === value)?.label || value;
}

export function serviceTypeLabel(value: string): string {
  return SERVICE_TYPE_OPTIONS.find(o => o.value === value)?.label || value;
}

export function criticalityLabel(value: number): { label: string; color: string } {
  return CRITICALITY_OPTIONS.find(o => o.value === value) || { label: String(value), color: 'text-ink-400' };
}

// ==================== 应用系统 API ====================

/** 系统列表（keyword 模糊匹配编码/名称/简称） */
export async function listAppSystems(keyword?: string): Promise<AppSystemDTO[]> {
  const query = keyword && keyword.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
  return unwrap(request.get<ApiResponse<AppSystemDTO[]>>(`/v1/app-systems${query}`));
}

export interface AppSystemPayload {
  systemName: string;
  shortName?: string;
  systemType: string;
  orgId?: string | null;
  ownerName: string;
  ownerContact?: string;
  repoUrl?: string;
  criticality: number;
  status?: number;
  description?: string;
  sortOrder?: number;
}

/** 创建应用系统 */
export async function createAppSystem(data: AppSystemPayload & { systemCode: string }): Promise<AppSystemDTO> {
  return unwrap(request.post<ApiResponse<AppSystemDTO>>('/v1/app-systems', data));
}

/** 更新应用系统（编码不可变） */
export async function updateAppSystem(systemId: string, data: AppSystemPayload): Promise<AppSystemDTO> {
  return unwrap(request.put<ApiResponse<AppSystemDTO>>(`/v1/app-systems/${systemId}`, data));
}

/** 删除应用系统（存在子模块服务时无法删除） */
export async function deleteAppSystem(systemId: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`/v1/app-systems/${systemId}`));
}

// ==================== 子模块服务 API ====================

/** 服务列表 */
export async function listAppServices(systemId: string): Promise<AppServiceDTO[]> {
  return unwrap(request.get<ApiResponse<AppServiceDTO[]>>(`/v1/app-systems/${systemId}/services`));
}

export interface AppServicePayload {
  serviceName: string;
  serviceType: string;
  baseUrl?: string;
  healthPath?: string;
  version?: string;
  ownerName: string;
  status?: number;
  description?: string;
  sortOrder?: number;
}

/** 创建子模块服务 */
export async function createAppService(systemId: string, data: AppServicePayload & { serviceCode: string }): Promise<AppServiceDTO> {
  return unwrap(request.post<ApiResponse<AppServiceDTO>>(`/v1/app-systems/${systemId}/services`, data));
}

/** 更新子模块服务（编码不可变） */
export async function updateAppService(systemId: string, serviceId: string, data: AppServicePayload): Promise<AppServiceDTO> {
  return unwrap(request.put<ApiResponse<AppServiceDTO>>(`/v1/app-systems/${systemId}/services/${serviceId}`, data));
}

/** 删除子模块服务 */
export async function deleteAppService(systemId: string, serviceId: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`/v1/app-systems/${systemId}/services/${serviceId}`));
}
