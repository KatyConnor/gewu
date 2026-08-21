// 需求管理 API — 对接后端 RequirementController
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const BASE = `${getBaseUrl()}/v1/requirements`;

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

export interface RequirementDTO {
  id: string;
  requirementCode: string;
  title: string;
  description: string;
  type: string;
  typeDesc: string;
  typeIcon: string;
  priority: number;
  priorityDesc: string;
  status: string;
  statusDesc: string;
  assigneeId: string;
  assigneeName: string;
  reporterId: string;
  reporterName: string;
  designerId: string;
  developerId: string;
  testerId: string;
  parentId: string;
  projectId: string;
  sessionIds: string;
  documentIds: string;
  designDoc: string;
  planDoc: string;
  testDoc: string;
  storyPoint: number;
  estimatedHours: number;
  actualHours: number;
  dueDate: number;
  startedAt: number;
  completedAt: number;
  releasedAt: number;
  cancelledAt: number;
  cancelReason: string;
  gitBranch?: string;
  createdAt: number;
  createdBy: string;
}

export interface RequirementReviewDTO {
  id: string;
  requirementId: string;
  reviewType: string;
  reviewTypeDesc: string;
  reviewerId: string;
  reviewerName: string;
  reviewResult: string;
  reviewResultDesc: string;
  reviewComment: string;
  reviewAttachments: string;
  reviewOrder: number;
  completedAt: number;
  createdAt: number;
  createdBy: string;
}

export interface RequirementTaskDTO {
  id: string;
  requirementId: string;
  taskCode: string;
  title: string;
  description: string;
  assigneeId: string;
  assigneeName: string;
  status: string;
  statusDesc: string;
  estimatedHours: number;
  actualHours: number;
  startedAt: number;
  completedAt: number;
  createdAt: number;
  createdBy: string;
}

export interface RequirementCommentDTO {
  id: string;
  requirementId: string;
  content: string;
  parentId: string;
  attachments: string;
  createdAt: number;
  createdBy: string;
  createdByName: string;
}

export interface RequirementStatsDTO {
  statusCount: Record<string, number>;
  typeCount: Record<string, number>;
  priorityCount: Record<string, number>;
  totalCount: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export interface RequirementQuery {
  page?: number;
  size?: number;
  keyword?: string;
  type?: string;
  priority?: number;
  status?: string;
  assigneeId?: string;
  projectId?: string;
}

// ==================== 需求 API ====================

export async function listRequirements(query: RequirementQuery = {}): Promise<PageResult<RequirementDTO>> {
  const params = new URLSearchParams();
  if (query.page) params.set('page', query.page.toString());
  if (query.size) params.set('size', query.size.toString());
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.type) params.set('type', query.type);
  if (query.priority !== undefined) params.set('priority', query.priority.toString());
  if (query.status) params.set('status', query.status);
  if (query.assigneeId) params.set('assigneeId', query.assigneeId);
  if (query.projectId) params.set('projectId', query.projectId);

  const res = await authFetch(`${BASE}?${params.toString()}`);
  return handleResponse<PageResult<RequirementDTO>>(res);
}

export async function getRequirement(id: string): Promise<RequirementDTO> {
  const res = await authFetch(`${BASE}/${id}`);
  return handleResponse<RequirementDTO>(res);
}

export async function createRequirement(data: Record<string, unknown>): Promise<RequirementDTO> {
  const res = await authFetch(BASE, { method: 'POST', body: JSON.stringify(data) });
  return handleResponse<RequirementDTO>(res);
}

export async function updateRequirement(id: string, data: Record<string, unknown>): Promise<RequirementDTO> {
  const res = await authFetch(`${BASE}/${id}`, { method: 'PUT', body: JSON.stringify(data) });
  return handleResponse<RequirementDTO>(res);
}

export async function deleteRequirement(id: string): Promise<void> {
  const res = await authFetch(`${BASE}/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

export async function updateRequirementStatus(id: string, status: string, reason?: string): Promise<RequirementDTO> {
  const res = await authFetch(`${BASE}/${id}/status`, {
    method: 'PUT',
    body: JSON.stringify({ status, reason }),
  });
  return handleResponse<RequirementDTO>(res);
}

export async function submitReview(id: string, reviewType: string): Promise<void> {
  const res = await authFetch(`${BASE}/${id}/submit-review`, {
    method: 'POST',
    body: JSON.stringify({ reviewType }),
  });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

export async function getRequirementStats(): Promise<RequirementStatsDTO> {
  const res = await authFetch(`${BASE}/stats`);
  return handleResponse<RequirementStatsDTO>(res);
}

// ==================== 评审 API ====================

export async function getReviews(requirementId: string): Promise<RequirementReviewDTO[]> {
  const res = await authFetch(`${BASE}/${requirementId}/reviews`);
  return handleResponse<RequirementReviewDTO[]>(res);
}

export async function submitReviewOpinion(requirementId: string, data: {
  reviewType: string;
  reviewResult: string;
  reviewComment: string;
  reviewAttachments?: string;
}): Promise<void> {
  const res = await authFetch(`${BASE}/${requirementId}/reviews`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

// ==================== 任务 API ====================

export async function getTasks(requirementId: string): Promise<RequirementTaskDTO[]> {
  const res = await authFetch(`${BASE}/${requirementId}/tasks`);
  return handleResponse<RequirementTaskDTO[]>(res);
}

export async function createTask(requirementId: string, data: {
  title: string;
  description?: string;
  assigneeId?: string;
  estimatedHours?: number;
}): Promise<RequirementTaskDTO> {
  const res = await authFetch(`${BASE}/${requirementId}/tasks`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<RequirementTaskDTO>(res);
}

export async function updateTask(taskId: string, data: Record<string, unknown>): Promise<RequirementTaskDTO> {
  const res = await authFetch(`${BASE}/tasks/${taskId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
  return handleResponse<RequirementTaskDTO>(res);
}

export async function deleteTask(taskId: string): Promise<void> {
  const res = await authFetch(`${BASE}/tasks/${taskId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

// ==================== 评论 API ====================

export async function getComments(requirementId: string): Promise<RequirementCommentDTO[]> {
  const res = await authFetch(`${BASE}/${requirementId}/comments`);
  return handleResponse<RequirementCommentDTO[]>(res);
}

export async function createComment(requirementId: string, data: {
  content: string;
  parentId?: string;
  attachments?: string;
}): Promise<RequirementCommentDTO> {
  const res = await authFetch(`${BASE}/${requirementId}/comments`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<RequirementCommentDTO>(res);
}

export async function deleteComment(commentId: string): Promise<void> {
  const res = await authFetch(`${BASE}/comments/${commentId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

// ==================== 需求文件空间 ====================

export interface RequirementFile {
  id: string;
  requirementId: string;
  category: string;
  fileName: string;
  fileSize: number;
  mimeType?: string;
  version: number;
  createdAt: number;
}

/** 上传需求文件 */
export async function uploadRequirementFile(requirementId: string, category: string, file: File): Promise<RequirementFile> {
  const formData = new FormData();
  formData.append('file', file);
  const token = getAccessToken();
  const res = await fetch(`${BASE}/${requirementId}/files?category=${category}`, {
    method: 'POST',
    body: formData,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`上传失败: ${res.status}`);
  const json = await res.json();
  if (json.code !== 10000) throw new Error(json?.message || '上传失败');
  return json.data;
}

/** 需求文件列表 */
export async function listRequirementFiles(requirementId: string, category?: string): Promise<RequirementFile[]> {
  const params = category ? `?category=${category}` : '';
  const res = await authFetch(`${BASE}/${requirementId}/files${params}`);
  return handleResponse<RequirementFile[]>(res);
}

/** 读取文件内容 */
export async function getRequirementFileContent(requirementId: string, fileId: string): Promise<string> {
  const res = await authFetch(`${BASE}/${requirementId}/files/${fileId}/content`);
  if (!res.ok) throw new Error(`读取失败: ${res.status}`);
  return res.text();
}

/** 删除需求文件 */
export async function deleteRequirementFile(requirementId: string, fileId: string): Promise<void> {
  const res = await authFetch(`${BASE}/${requirementId}/files/${fileId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

/** 创建开发分支 */
export async function createRequirementBranch(requirementId: string): Promise<RequirementDTO> {
  const res = await authFetch(`${BASE}/${requirementId}/branch`, { method: 'POST' });
  return handleResponse<RequirementDTO>(res);
}
