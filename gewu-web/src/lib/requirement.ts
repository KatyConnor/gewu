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

// ==================== 需求 API（用户侧只读"我的需求"：管理端分派 assignee=当前用户） ====================

export async function listMyRequirements(query: RequirementQuery = {}): Promise<PageResult<RequirementDTO>> {
  const params = new URLSearchParams();
  if (query.page) params.set('page', query.page.toString());
  if (query.size) params.set('size', query.size.toString());
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.type) params.set('type', query.type);
  if (query.priority !== undefined) params.set('priority', query.priority.toString());
  if (query.status) params.set('status', query.status);
  if (query.projectId) params.set('projectId', query.projectId);

  const res = await authFetch(`${BASE}/my?${params.toString()}`);
  return handleResponse<PageResult<RequirementDTO>>(res);
}

export async function getRequirement(id: string): Promise<RequirementDTO> {
  const res = await authFetch(`${BASE}/${id}`);
  return handleResponse<RequirementDTO>(res);
}

export async function getRequirementStats(): Promise<RequirementStatsDTO> {
  const res = await authFetch(`${BASE}/stats`);
  return handleResponse<RequirementStatsDTO>(res);
}

// ==================== 评审 API（只读） ====================

export async function getReviews(requirementId: string): Promise<RequirementReviewDTO[]> {
  const res = await authFetch(`${BASE}/${requirementId}/reviews`);
  return handleResponse<RequirementReviewDTO[]>(res);
}

// ==================== 任务 API（只读） ====================

export async function getTasks(requirementId: string): Promise<RequirementTaskDTO[]> {
  const res = await authFetch(`${BASE}/${requirementId}/tasks`);
  return handleResponse<RequirementTaskDTO[]>(res);
}

// ==================== 评论 API（查看 + 协作参与） ====================

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

// ==================== 需求文件空间（只读） ====================

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

