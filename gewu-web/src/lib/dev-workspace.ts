// 开发工作空间 API - 对接后端 DevWorkspaceController
// T4.3 收敛：统一走 request.ts（axios 拦截器：token 注入 / 401 清除并跳登录），
// 不再手写 fetch —— 此前 401 后页面滞留导致"克隆失败 401"等迷惑现象
import { request } from './request';

/** 后端统一响应信封 */
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

/** 信封解包（menu.ts 试点范式） */
async function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  const res = await p;
  if (!res || res.code !== 10000) throw new Error(res?.message || '请求失败');
  return res.data;
}

// ==================== 类型 ====================

export interface DevWorkspaceInfo {
  workspaceId: string;
  userId: string;
  mode: string;
  devSandboxId?: string;
  sandboxStatus?: string;
  sandboxStatusDesc?: string;
  mountPath?: string;
}

export interface GitProject {
  projectId: string;
  projectName: string;
  repoUrl: string;
  repoBranch: string;
  localPath: string;
  cloneStatus: string;
  lastSyncAt?: number;
  headCommit?: string;
  createdAt?: number;
}

export interface GitCredential {
  credentialId: string;
  credName: string;
  credType: string;
  gitHost?: string;
  hasPublicKey?: boolean;
  createdAt?: number;
}

export interface ExecResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  duration: number;
}

// ==================== API ====================

export async function getDevWorkspace(): Promise<DevWorkspaceInfo> {
  return unwrap<DevWorkspaceInfo>(request.get<ApiResponse<DevWorkspaceInfo>>('/v1/dev-workspaces'));
}

export async function startSandbox(): Promise<unknown> {
  return unwrap<unknown>(request.post<ApiResponse<unknown>>('/v1/dev-workspaces/sandbox/start'));
}

export async function stopSandbox(): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>('/v1/dev-workspaces/sandbox/stop'));
}

export async function listFiles(path?: string): Promise<string> {
  return unwrap<string>(request.get<ApiResponse<string>>('/v1/dev-workspaces/files', { params: { path } }));
}

export async function readFile(path: string): Promise<string> {
  // 后端返回 text/plain 原文（非信封），恒等 transformResponse 避免 axios 二次解析
  return request.get<string>('/v1/dev-workspaces/files/content', {
    params: { path },
    transformResponse: [(data: string) => data],
  });
}

export async function writeFile(path: string, content: string): Promise<void> {
  await unwrap<void>(request.put<ApiResponse<void>>('/v1/dev-workspaces/files/content', { path, content }));
}

export async function uploadFile(path: string, file: File): Promise<void> {
  const formData = new FormData();
  formData.append('file', file);
  // axios 检测到 FormData 会接管 Content-Type 并补 boundary
  await unwrap<void>(request.post<ApiResponse<void>>('/v1/dev-workspaces/files/upload', formData, {
    params: { path },
    headers: { 'Content-Type': 'multipart/form-data' },
  }));
}

export async function deleteFile(path: string): Promise<void> {
  await unwrap<void>(request.delete<ApiResponse<void>>('/v1/dev-workspaces/files', { params: { path } }));
}

export async function execCommand(command: string, timeout?: number): Promise<ExecResult> {
  return unwrap<ExecResult>(request.post<ApiResponse<ExecResult>>('/v1/dev-workspaces/exec', { command, timeout }));
}

export async function cloneRepo(repoUrl: string, projectName: string, repoBranch?: string): Promise<GitProject> {
  return unwrap<GitProject>(request.post<ApiResponse<GitProject>>('/v1/dev-workspaces/projects/clone', { repoUrl, projectName, repoBranch }));
}

export async function listProjects(): Promise<GitProject[]> {
  return unwrap<GitProject[]>(request.get<ApiResponse<GitProject[]>>('/v1/dev-workspaces/projects'));
}

export async function gitPull(projectId: string): Promise<GitProject> {
  return unwrap<GitProject>(request.post<ApiResponse<GitProject>>(`/v1/dev-workspaces/projects/${projectId}/pull`));
}

export async function gitPush(projectId: string, message: string): Promise<string> {
  return unwrap<string>(request.post<ApiResponse<string>>(`/v1/dev-workspaces/projects/${projectId}/push`, { message }));
}

export async function buildProject(projectId: string, command: string): Promise<ExecResult> {
  return unwrap<ExecResult>(request.post<ApiResponse<ExecResult>>(`/v1/dev-workspaces/projects/${projectId}/build`, { command }));
}

export async function runProject(projectId: string, command: string): Promise<ExecResult> {
  return unwrap<ExecResult>(request.post<ApiResponse<ExecResult>>(`/v1/dev-workspaces/projects/${projectId}/run`, { command }));
}

export async function addGitCredential(credName: string, credType: string, credValue: string, gitHost?: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>('/v1/dev-workspaces/git-credentials', { credName, credType, credValue, gitHost }));
}

export async function listGitCredentials(): Promise<GitCredential[]> {
  return unwrap<GitCredential[]>(request.get<ApiResponse<GitCredential[]>>('/v1/dev-workspaces/git-credentials'));
}

export async function deleteGitCredential(credentialId: string): Promise<void> {
  await unwrap<void>(request.delete<ApiResponse<void>>(`/v1/dev-workspaces/git-credentials/${credentialId}`));
}
