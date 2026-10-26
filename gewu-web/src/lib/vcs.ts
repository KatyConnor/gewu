// 托管平台协作 API — 对接后端 VcsCollabController（托管商无关适配层）
// 能力前提：项目配置 token 型凭证 + repoUrl 平台可识别；不满足时后端报错降级。
import { request } from './request';

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  return p.then((res) => {
    if (!res || res.code !== 10000) throw new Error(res?.message || '请求失败');
    return res.data;
  });
}

export interface MergeRequest {
  number: number;
  title: string;
  description?: string;
  /** open / merged / closed */
  state: string;
  sourceBranch: string;
  targetBranch: string;
  webUrl?: string;
  author?: string;
  createdAt?: number;
  headSha?: string;
}

export interface CiStatus {
  /** none / pending / success / failure / canceled */
  overall: string;
  ref: string;
  webUrl?: string;
  items: Array<{ name?: string; state: string; webUrl?: string }>;
}

export interface VcsIssue {
  number: number;
  title: string;
  description?: string;
  state: string;
  webUrl?: string;
  author?: string;
  createdAt?: number;
  comments: Array<{ author: string; body: string; createdAt?: number }>;
}

export async function listMRs(projectId: string, state: 'open' | 'all' | 'merged' | 'closed' = 'open'): Promise<MergeRequest[]> {
  return unwrap<MergeRequest[]>(request.get<ApiResponse<MergeRequest[]>>(`/v1/vcs/${projectId}/mrs`, { params: { state } }));
}

export async function createMR(projectId: string, data: { sourceBranch: string; targetBranch: string; title: string; description?: string }): Promise<MergeRequest> {
  return unwrap<MergeRequest>(request.post<ApiResponse<MergeRequest>>(`/v1/vcs/${projectId}/mrs`, data));
}

export async function getMR(projectId: string, number: number): Promise<MergeRequest> {
  return unwrap<MergeRequest>(request.get<ApiResponse<MergeRequest>>(`/v1/vcs/${projectId}/mrs/${number}`));
}

export async function ciOf(projectId: string, ref: string): Promise<CiStatus> {
  return unwrap<CiStatus>(request.get<ApiResponse<CiStatus>>(`/v1/vcs/${projectId}/ci`, { params: { ref } }));
}

export async function listIssues(projectId: string, state: 'open' | 'closed' | 'all' = 'open'): Promise<VcsIssue[]> {
  return unwrap<VcsIssue[]>(request.get<ApiResponse<VcsIssue[]>>(`/v1/vcs/${projectId}/issues`, { params: { state } }));
}

export async function getIssue(projectId: string, number: number): Promise<VcsIssue> {
  return unwrap<VcsIssue>(request.get<ApiResponse<VcsIssue>>(`/v1/vcs/${projectId}/issues/${number}`));
}

// ==================== P2 写操作（评论 / Release） ====================

export interface VcsRelease {
  tagName: string;
  name?: string;
  notes?: string;
  state?: string;
  prerelease?: boolean;
  webUrl?: string;
  createdAt?: number;
  assets: string[];
}

/** Issue 评论（按平台原生评论通道） */
export async function commentIssue(projectId: string, number: number, body: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/vcs/${projectId}/issues/${number}/comments`, { body }));
}

/** MR 评论（按平台原生评论通道） */
export async function commentMR(projectId: string, number: number, body: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/vcs/${projectId}/mrs/${number}/comments`, { body }));
}

export async function listReleases(projectId: string): Promise<VcsRelease[]> {
  return unwrap<VcsRelease[]>(request.get<ApiResponse<VcsRelease[]>>(`/v1/vcs/${projectId}/releases`));
}

/** 基于已推送的 tag 创建 Release */
export async function createRelease(projectId: string, data: { tagName: string; name?: string; notes?: string; prerelease?: boolean }): Promise<VcsRelease> {
  return unwrap<VcsRelease>(request.post<ApiResponse<VcsRelease>>(`/v1/vcs/${projectId}/releases`, data));
}

// ==================== P3：评审写 / Deploy Key / Release 附件 ====================

export interface VcsReview {
  /** APPROVED / CHANGES_REQUESTED / COMMENTED */
  state: string;
  reviewer: string;
  body?: string;
  submittedAt?: number;
}

export interface VcsCapabilities {
  platform: string;
  reviewWrite: boolean;
  deployKey: boolean;
  releaseAsset: boolean;
}

export async function capabilities(projectId: string): Promise<VcsCapabilities> {
  return unwrap<VcsCapabilities>(request.get<ApiResponse<VcsCapabilities>>(`/v1/vcs/${projectId}/capabilities`));
}

export async function listReviews(projectId: string, mrNumber: number): Promise<VcsReview[]> {
  return unwrap<VcsReview[]>(request.get<ApiResponse<VcsReview[]>>(`/v1/vcs/${projectId}/mrs/${mrNumber}/reviews`));
}

/** 提交评审：APPROVE / REQUEST_CHANGES / COMMENT（按能力矩阵开放） */
export async function submitReview(projectId: string, mrNumber: number, event: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT', body?: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/vcs/${projectId}/mrs/${mrNumber}/review`, { event, body }));
}

/** Deploy Key 注册闭环：沙箱生成 ed25519 → 平台注册 → 私钥落凭证并注入沙箱 */
export async function registerDeployKey(projectId: string, title: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/vcs/${projectId}/deploy-key`, { title }));
}

/** Release 附件上传（沙箱文件经后端中转） */
export async function uploadReleaseAsset(projectId: string, tagName: string, sandboxPath: string, assetName?: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/vcs/${projectId}/releases/${encodeURIComponent(tagName)}/assets`, { sandboxPath, assetName }));
}
