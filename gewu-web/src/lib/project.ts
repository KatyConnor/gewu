// 项目管理 API — 对接后端 ProjectController + ProjectDocumentController
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const BASE = `${getBaseUrl()}/v1`;

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

export interface ProjectDTO {
  projectId: string;
  projectName: string;
  projectCode: string;
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

export interface ProjectPhaseDTO {
  id: string;
  projectId: string;
  phaseCode: string;
  phaseName: string;
  phaseOrder: number;
  status: number;
  statusDesc: string;
  startedAt: number;
  completedAt: number;
  agentId: string;
  revertible: boolean;
  documentCount: number;
}

export interface PhaseDocumentDTO {
  id: string;
  projectId: string;
  phaseCode: string;
  docName: string;
  docType: string;
  currentVersion: number;
  totalVersions: number;
  reviewStatus: string;
  reviewStatusDesc: string;
  reviewedBy: string;
  reviewedAt: number;
  reviewComment: string;
  createdAt: number;
  createdBy: string;
}

export interface DocumentVersionDTO {
  id: string;
  documentId: string;
  versionNo: number;
  fileUrl: string;
  contentMd5: string;
  changeSummary: string;
  changeSource: string;
  agentId: string;
  agentPrompt: string;
  fileSize: number;
  uploadedBy: string;
  uploadedAt: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

// ==================== 项目 API ====================

export async function listMyProjects(params: {
  page?: number;
  size?: number;
  projectName?: string;
  projectCode?: string;
  ownerName?: string;
  status?: number;
} = {}): Promise<PageResult<ProjectDTO>> {
  const searchParams = new URLSearchParams();
  if (params.page) searchParams.set('page', params.page.toString());
  if (params.size) searchParams.set('size', params.size.toString());
  if (params.projectName) searchParams.set('projectName', params.projectName);
  if (params.projectCode) searchParams.set('projectCode', params.projectCode);
  if (params.ownerName) searchParams.set('ownerName', params.ownerName);
  if (params.status !== undefined) searchParams.set('status', params.status.toString());

  const res = await authFetch(`${BASE}/projects/my?${searchParams.toString()}`);
  return handleResponse<PageResult<ProjectDTO>>(res);
}

export async function getProject(projectId: string): Promise<ProjectDTO> {
  const res = await authFetch(`${BASE}/projects/${projectId}`);
  return handleResponse<ProjectDTO>(res);
}

export interface CreateProjectRequest {
  projectName: string;
  description?: string;
  visibility?: number;
  techStack?: string;
  vcs?: string;
  worktree?: string;
  iconColor?: string;
}

export async function createProject(data: CreateProjectRequest): Promise<ProjectDTO> {
  const res = await authFetch(`${BASE}/projects`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<ProjectDTO>(res);
}

export async function updateProject(projectId: string, data: Record<string, unknown>): Promise<ProjectDTO> {
  const res = await authFetch(`${BASE}/projects/${projectId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
  return handleResponse<ProjectDTO>(res);
}

export async function deleteProject(projectId: string): Promise<void> {
  const res = await authFetch(`${BASE}/projects/${projectId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

// ==================== 阶段 API ====================

export async function getProjectPhases(projectId: string): Promise<ProjectPhaseDTO[]> {
  const res = await authFetch(`${BASE}/projects/${projectId}/phases`);
  return handleResponse<ProjectPhaseDTO[]>(res);
}

export async function startPhase(projectId: string, phaseCode: string): Promise<void> {
  const res = await authFetch(`${BASE}/projects/${projectId}/phases/${phaseCode}/start`, { method: 'PUT' });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

export async function completePhase(projectId: string, phaseCode: string): Promise<void> {
  const res = await authFetch(`${BASE}/projects/${projectId}/phases/${phaseCode}/complete`, { method: 'PUT' });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

export async function revertPhase(projectId: string, phaseCode: string): Promise<void> {
  const res = await authFetch(`${BASE}/projects/${projectId}/phases/${phaseCode}/revert`, { method: 'PUT' });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

// ==================== 文档 API ====================

export async function listPhaseDocuments(projectId: string, phaseCode: string): Promise<PhaseDocumentDTO[]> {
  const res = await authFetch(`${BASE}/projects/${projectId}/phases/${phaseCode}/docs`);
  return handleResponse<PhaseDocumentDTO[]>(res);
}

export async function getDocument(documentId: string): Promise<PhaseDocumentDTO> {
  const res = await authFetch(`${BASE}/documents/${documentId}`);
  return handleResponse<PhaseDocumentDTO>(res);
}

export async function getDocumentContent(documentId: string): Promise<string> {
  const res = await authFetch(`${BASE}/documents/${documentId}/content`);
  return handleResponse<string>(res);
}

export async function updateDocumentContent(documentId: string, data: { content: string; changeSummary?: string }): Promise<PhaseDocumentDTO> {
  const res = await authFetch(`${BASE}/documents/${documentId}/content`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
  return handleResponse<PhaseDocumentDTO>(res);
}

export async function deleteDocument(documentId: string): Promise<void> {
  const res = await authFetch(`${BASE}/documents/${documentId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
}

export async function uploadDocument(projectId: string, phaseCode: string, file: File): Promise<PhaseDocumentDTO> {
  const token = getAccessToken();
  const formData = new FormData();
  formData.append('file', file);
  const res = await fetch(`${BASE}/projects/${projectId}/phases/${phaseCode}/docs`, {
    method: 'POST',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: formData,
  });
  return handleResponse<PhaseDocumentDTO>(res);
}

export async function createMarkdownDoc(projectId: string, phaseCode: string, data: { docName: string; docType: string; content: string }): Promise<PhaseDocumentDTO> {
  const res = await authFetch(`${BASE}/projects/${projectId}/phases/${phaseCode}/docs/md`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<PhaseDocumentDTO>(res);
}

// ==================== 版本/审核 API ====================

export async function getDocumentVersions(documentId: string): Promise<DocumentVersionDTO[]> {
  const res = await authFetch(`${BASE}/documents/${documentId}/versions`);
  return handleResponse<DocumentVersionDTO[]>(res);
}

export async function getVersionContent(documentId: string, versionNo: number): Promise<string> {
  const res = await authFetch(`${BASE}/documents/${documentId}/versions/${versionNo}`);
  return handleResponse<string>(res);
}

export async function submitForReview(documentId: string): Promise<void> {
  const res = await authFetch(`${BASE}/documents/${documentId}/submit-review`, { method: 'POST' });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

export async function approveReview(documentId: string, agentId: string, comment?: string): Promise<void> {
  const res = await authFetch(`${BASE}/documents/${documentId}/agent-review/approve?agentId=${agentId}${comment ? `&comment=${encodeURIComponent(comment)}` : ''}`, { method: 'POST' });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

export async function rejectReview(documentId: string, agentId: string, comment: string): Promise<void> {
  const res = await authFetch(`${BASE}/documents/${documentId}/agent-review/reject?agentId=${agentId}&comment=${encodeURIComponent(comment)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`操作失败: ${res.status}`);
}

// ==================== 项目仓库管理 ====================

/** 克隆项目仓库 */
export async function cloneProjectRepo(projectId: string, repoUrl: string, branch?: string): Promise<ProjectDTO> {
  const params = `repoUrl=${encodeURIComponent(repoUrl)}${branch ? `&branch=${encodeURIComponent(branch)}` : ''}`;
  const res = await authFetch(`${BASE}/projects/${projectId}/repo/clone?${params}`, { method: 'POST' });
  return handleResponse<ProjectDTO>(res);
}

/** Git pull */
export async function gitPullProject(projectId: string): Promise<ProjectDTO> {
  const res = await authFetch(`${BASE}/projects/${projectId}/repo/pull`, { method: 'POST' });
  return handleResponse<ProjectDTO>(res);
}

/** 仓库状态 */
export async function getRepoStatus(projectId: string): Promise<ProjectDTO> {
  const res = await authFetch(`${BASE}/projects/${projectId}/repo/status`);
  return handleResponse<ProjectDTO>(res);
}
