// 开发工作空间 API - 对接后端 DevWorkspaceController
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}
const BASE = getBaseUrl();

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
  const res = await authFetch(`${BASE}/v1/dev-workspaces`);
  return handleResponse<DevWorkspaceInfo>(res);
}

export async function startSandbox(): Promise<unknown> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/sandbox/start`, { method: 'POST' });
  return handleResponse(res);
}

export async function stopSandbox(): Promise<void> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/sandbox/stop`, { method: 'POST' });
  await handleResponse<void>(res);
}

export async function listFiles(path?: string): Promise<string> {
  const params = path ? `?path=${encodeURIComponent(path)}` : '';
  const res = await authFetch(`${BASE}/v1/dev-workspaces/files${params}`);
  return handleResponse<string>(res);
}

export async function readFile(path: string): Promise<string> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/files/content?path=${encodeURIComponent(path)}`);
  if (!res.ok) throw new Error(`读取失败: ${res.status}`);
  return res.text();
}

export async function writeFile(path: string, content: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/files/content`, {
    method: 'PUT',
    body: JSON.stringify({ path, content }),
  });
  await handleResponse<void>(res);
}

export async function uploadFile(path: string, file: File): Promise<void> {
  const formData = new FormData();
  formData.append('file', file);
  const token = getAccessToken();
  const res = await fetch(`${BASE}/v1/dev-workspaces/files/upload?path=${encodeURIComponent(path)}`, {
    method: 'POST',
    body: formData,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`上传失败: ${res.status}`);
}

export async function deleteFile(path: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/files?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
  await handleResponse<void>(res);
}

export async function execCommand(command: string, timeout?: number): Promise<ExecResult> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/exec`, {
    method: 'POST',
    body: JSON.stringify({ command, timeout }),
  });
  return handleResponse<ExecResult>(res);
}

export async function cloneRepo(repoUrl: string, projectName: string, repoBranch?: string): Promise<GitProject> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/projects/clone`, {
    method: 'POST',
    body: JSON.stringify({ repoUrl, projectName, repoBranch }),
  });
  return handleResponse<GitProject>(res);
}

export async function listProjects(): Promise<GitProject[]> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/projects`);
  return handleResponse<GitProject[]>(res);
}

export async function gitPull(projectId: string): Promise<GitProject> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/projects/${projectId}/pull`, { method: 'POST' });
  return handleResponse<GitProject>(res);
}

export async function gitPush(projectId: string, message: string): Promise<string> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/projects/${projectId}/push`, {
    method: 'POST',
    body: JSON.stringify({ message }),
  });
  return handleResponse<string>(res);
}

export async function buildProject(projectId: string, command: string): Promise<ExecResult> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/projects/${projectId}/build`, {
    method: 'POST',
    body: JSON.stringify({ command }),
  });
  return handleResponse<ExecResult>(res);
}

export async function runProject(projectId: string, command: string): Promise<ExecResult> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/projects/${projectId}/run`, {
    method: 'POST',
    body: JSON.stringify({ command }),
  });
  return handleResponse<ExecResult>(res);
}

export async function addGitCredential(credName: string, credType: string, credValue: string, gitHost?: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/git-credentials`, {
    method: 'POST',
    body: JSON.stringify({ credName, credType, credValue, gitHost }),
  });
  await handleResponse<void>(res);
}

export async function listGitCredentials(): Promise<GitCredential[]> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/git-credentials`);
  return handleResponse<GitCredential[]>(res);
}

export async function deleteGitCredential(credentialId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/dev-workspaces/git-credentials/${credentialId}`, { method: 'DELETE' });
  await handleResponse<void>(res);
}
