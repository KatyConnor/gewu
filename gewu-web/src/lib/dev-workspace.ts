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

/** 工作空间项目 = 我的项目中分配给当前用户的项目（Git 地址与凭证来自项目配置） */
export interface GitProject {
  projectId: string;
  projectName: string;
  repoUrl: string;
  repoBranch: string;
  localPath: string;
  cloneStatus: string;
  /** 最近一次 clone 失败的 stderr 摘要（ready 时为空） */
  cloneError?: string;
  lastSyncAt?: number;
  headCommit?: string;
  createdAt?: number;
  /** 项目级凭证类型: token/ssh_key/password（密文不出后端） */
  repoCredentialType?: string;
  /** 项目级仓库用户名（password 型配套展示） */
  repoUsername?: string;
  /** 项目是否已配置仓库凭证 */
  hasRepoCredential?: boolean;
}

export interface ExecResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  duration: number;
}

/** 结构化文件树条目（IDEA 式工作台文件树数据源） */
export interface FileEntry {
  name: string;
  dir: boolean;
  size: number;
  mtime: number;
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

export async function listProjects(): Promise<GitProject[]> {
  return unwrap<GitProject[]>(request.get<ApiResponse<GitProject[]>>('/v1/dev-workspaces/projects'));
}

/** 拉取项目代码：仓库未就绪时后端按项目配置的 Git 地址与凭证自动克隆 */
export async function gitPull(projectId: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/pull`));
}

/** 单条工作区变更（git status porcelain 解析，已按暂存/未跟踪归类） */
export interface GitChange {
  path: string;
  renameFrom?: string;
  staged: boolean;
  untracked: boolean;
  statusDesc: string;
}

export interface GitStatus {
  branch: string;
  upstream?: string;
  ahead: number;
  behind: number;
  changes: GitChange[];
}

export interface GitLogEntry {
  hash: string;
  shortHash: string;
  author: string;
  timestamp: number;
  message: string;
}

export interface GitBranches {
  current: string;
  local: string[];
  remote: string[];
}

export interface GitStash {
  index: number;
  message: string;
}

// ==================== Git 常规操作（IDEA 式 Git 菜单） ====================

export async function gitStatus(projectId: string): Promise<GitStatus> {
  return unwrap<GitStatus>(request.get<ApiResponse<GitStatus>>(`/v1/dev-workspaces/projects/${projectId}/git/status`));
}

export async function gitLog(projectId: string, limit?: number): Promise<GitLogEntry[]> {
  return unwrap<GitLogEntry[]>(request.get<ApiResponse<GitLogEntry[]>>(`/v1/dev-workspaces/projects/${projectId}/git/log`, { params: { limit } }));
}

export async function gitBranches(projectId: string): Promise<GitBranches> {
  return unwrap<GitBranches>(request.get<ApiResponse<GitBranches>>(`/v1/dev-workspaces/projects/${projectId}/git/branches`));
}

export async function gitStashList(projectId: string): Promise<GitStash[]> {
  return unwrap<GitStash[]>(request.get<ApiResponse<GitStash[]>>(`/v1/dev-workspaces/projects/${projectId}/git/stashes`));
}

/** 单文件差异（vs HEAD；未跟踪新文件返回空串，调用方回落展示全文） */
export async function gitFileDiff(projectId: string, path: string): Promise<string> {
  return unwrap<string>(request.get<ApiResponse<string>>(`/v1/dev-workspaces/projects/${projectId}/git/diff`, { params: { path } }));
}

export async function gitCommit(projectId: string, data: { message: string; files?: string[]; amend?: boolean }): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/commit`, data));
}

/** 推送当前分支（远程无同名分支时 -u 建立跟踪） */
export async function gitPush(projectId: string): Promise<string> {
  return unwrap<string>(request.post<ApiResponse<string>>(`/v1/dev-workspaces/projects/${projectId}/push`));
}

export async function gitFetch(projectId: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/fetch`));
}

export async function gitCreateBranch(projectId: string, name: string, checkout?: boolean): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/branch`, { name, checkout }));
}

export async function gitCheckout(projectId: string, branch: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/checkout`, { branch }));
}

export async function gitDeleteBranch(projectId: string, name: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/branch-delete`, { name }));
}

export async function gitMerge(projectId: string, branch: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/merge`, { branch }));
}

export async function gitStash(projectId: string, message?: string): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/stash`, { message }));
}

export async function gitStashPop(projectId: string, index: number): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/stash-pop`, { index }));
}

/** 回滚：跟踪文件还原到 HEAD；未跟踪/新增文件从磁盘清除 */
export async function gitRollback(projectId: string, paths: string[], untrackedPaths: string[]): Promise<void> {
  await unwrap<void>(request.post<ApiResponse<void>>(`/v1/dev-workspaces/projects/${projectId}/git/rollback`, { paths, untrackedPaths }));
}

export async function buildProject(projectId: string, command: string): Promise<ExecResult> {
  return unwrap<ExecResult>(request.post<ApiResponse<ExecResult>>(`/v1/dev-workspaces/projects/${projectId}/build`, { command }));
}

export async function runProject(projectId: string, command: string): Promise<ExecResult> {
  return unwrap<ExecResult>(request.post<ApiResponse<ExecResult>>(`/v1/dev-workspaces/projects/${projectId}/run`, { command }));
}

/** 结构化目录列表（文件树数据源；path 为卷内相对路径） */
export async function listFileEntries(path: string): Promise<FileEntry[]> {
  return unwrap<FileEntry[]>(request.get<ApiResponse<FileEntry[]>>('/v1/dev-workspaces/files/entries', { params: { path } }));
}
