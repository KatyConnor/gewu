// 管理端项目管理 API - 对接后端 AdminProjectController（project:manage 权限门）
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

export interface AdminProjectDTO {
  projectId: string;
  projectName: string;
  projectCode: string | null;
  shortName: string | null;
  systemName: string | null;
  /** 技术负责人（姓名展示冗余） */
  techLead: string | null;
  /** 团队成员（逗号分隔姓名展示冗余） */
  teamMembers: string | null;
  /** 技术负责人用户 ID（编辑回显选择器） */
  techLeadUserId: string | null;
  /** 团队成员用户 ID 列表（编辑回显选择器） */
  memberUserIds: string[];
  description: string | null;
  visibility: number;
  status: number;
  ownerId: string;
  ownerName: string | null;
  memberCount: number;
  createdAt: number;
  techStack: string | null;
  worktree: string | null;
  vcs: string | null;
  iconUrl: string | null;
  iconColor: string | null;
  repoUrl: string | null;
  platformType?: string;
  apiBase?: string;
  repoBranch: string | null;
  cloneStatus: string | null;
  headCommit: string | null;
  repoLocalPath: string | null;
  currentPhase: string | null;
  initiatedAt: number | null;
  closedAt: number | null;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

/** 创建/更新命令（techLeadUserId 空串=清除负责人；memberUserIds 全量替换） */
export interface ProjectPayload {
  projectName: string;
  shortName?: string;
  systemName?: string;
  description?: string;
  visibility?: number;
  status?: number;
  techStack?: string;
  vcs?: string;
  worktree?: string;
  iconColor?: string;
  repoUrl?: string;
  platformType?: string;
  apiBase?: string;
  repoCredentialType?: string;
  repoUsername?: string;
  /** 非空白时重新加密落库；不传保持现有凭证 */
  repoCredential?: string;
  techLeadUserId?: string;
  memberUserIds?: string[];
}

/** 项目列表：keyword 模糊匹配名称/编号/所属系统/技术负责人 */
export async function listProjects(params: {
  page: number;
  size: number;
  keyword?: string;
  status?: number;
}): Promise<PageResult<AdminProjectDTO>> {
  const qs = new URLSearchParams();
  qs.set('page', String(params.page));
  qs.set('size', String(params.size));
  if (params.keyword && params.keyword.trim()) qs.set('keyword', params.keyword.trim());
  if (params.status !== undefined) qs.set('status', String(params.status));
  return unwrap(request.get<ApiResponse<PageResult<AdminProjectDTO>>>(`/v1/projects?${qs.toString()}`));
}

export async function getProject(projectId: string): Promise<AdminProjectDTO> {
  return unwrap(request.get<ApiResponse<AdminProjectDTO>>(`/v1/projects/${projectId}`));
}

export async function createProject(payload: ProjectPayload): Promise<AdminProjectDTO> {
  return unwrap(request.post<ApiResponse<AdminProjectDTO>>('/v1/projects', payload));
}

export async function updateProject(projectId: string, payload: ProjectPayload): Promise<AdminProjectDTO> {
  return unwrap(request.put<ApiResponse<AdminProjectDTO>>(`/v1/projects/${projectId}`, payload));
}

export async function deleteProject(projectId: string): Promise<void> {
  await unwrap(request.delete<ApiResponse<null>>(`/v1/projects/${projectId}`));
}

// ==================== 项目成员（需求分派选择器数据源） ====================

export interface ProjectMemberView {
  userId: string;
  displayName: string | null;
  roleCode: string;
}

/** 项目成员列表：需求分派时负责人必须从项目成员中选择（后端强校验） */
export async function listProjectMembers(projectId: string): Promise<ProjectMemberView[]> {
  return unwrap(request.get<ApiResponse<ProjectMemberView[]>>(`/v1/projects/${projectId}/members`));
}
