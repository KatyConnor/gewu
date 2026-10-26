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
