// 管理端需求管理 API - 对接后端 AdminRequirementController（requirement:manage 权限门）
// 统一走 request.ts（axios 拦截器：token 注入/401 跳转）
import { request } from './request';

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

async function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  const res = await p;
  if (!res || res.code !== 10000) throw new Error(res?.message || '请求失败');
  return res.data;
}

export interface AdminRequirementDTO {
  id: string;
  requirementCode: string | null;
  title: string;
  description: string | null;
  type: string;
  priority: number;
  status: string;
  assigneeId: string | null;
  assigneeName: string | null;
  reporterId: string | null;
  reporterName: string | null;
  projectId: string;
  projectName: string | null;
  dueDate: number | null;
  storyPoint: number | null;
  estimatedHours: number | null;
  createdAt: number;
  startedAt: number | null;
  completedAt: number | null;
  cancelReason: string | null;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export interface RequirementPayload {
  title: string;
  description?: string;
  type?: string;
  priority?: number;
  /** 必填：需求必须绑定项目 */
  projectId: string;
  /** 分派负责人（必须是绑定项目成员，后端强校验） */
  assigneeId?: string;
  dueDate?: number;
  storyPoint?: number;
  estimatedHours?: number;
  designDoc?: string;
  planDoc?: string;
  testDoc?: string;
}

/** 需求列表：keyword 模糊匹配标题/编号 */
export async function listRequirements(params: {
  page: number;
  size: number;
  keyword?: string;
  type?: string;
  priority?: number;
  status?: string;
  projectId?: string;
}): Promise<PageResult<AdminRequirementDTO>> {
  const qs = new URLSearchParams();
  qs.set('page', String(params.page));
  qs.set('size', String(params.size));
  if (params.keyword && params.keyword.trim()) qs.set('keyword', params.keyword.trim());
  if (params.type) qs.set('type', params.type);
  if (params.priority !== undefined) qs.set('priority', String(params.priority));
  if (params.status) qs.set('status', params.status);
  if (params.projectId) qs.set('projectId', params.projectId);
  return unwrap(request.get<ApiResponse<PageResult<AdminRequirementDTO>>>(`/v1/requirements?${qs.toString()}`));
}

export async function getRequirement(id: string): Promise<AdminRequirementDTO> {
  return unwrap(request.get<ApiResponse<AdminRequirementDTO>>(`/v1/requirements/${id}`));
}

export async function createRequirement(payload: RequirementPayload): Promise<AdminRequirementDTO> {
  return unwrap(request.post<ApiResponse<AdminRequirementDTO>>('/v1/requirements', payload));
}

export async function updateRequirement(id: string, payload: RequirementPayload): Promise<AdminRequirementDTO> {
  return unwrap(request.put<ApiResponse<AdminRequirementDTO>>(`/v1/requirements/${id}`, payload));
}

export async function updateRequirementStatus(id: string, status: string, reason?: string): Promise<AdminRequirementDTO> {
  return unwrap(request.put<ApiResponse<AdminRequirementDTO>>(`/v1/requirements/${id}/status`, { status, reason }));
}

export async function submitRequirementReview(id: string, reviewType: string): Promise<void> {
  await unwrap(request.post<ApiResponse<null>>(`/v1/requirements/${id}/submit-review`, { reviewType }));
}

export async function deleteRequirement(id: string): Promise<void> {
  await unwrap(request.delete<ApiResponse<null>>(`/v1/requirements/${id}`));
}
