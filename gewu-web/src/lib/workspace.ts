// 工作空间 API - 对接后端 WorkspaceController
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

/** 上传文件专用 fetch（不设 Content-Type，让浏览器自动带 boundary） */
async function authFetchMultipart(url: string, formData: FormData): Promise<Response> {
  const token = getAccessToken();
  return fetch(url, {
    method: 'POST',
    body: formData,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
}

// ==================== 类型定义 ====================

export interface WorkspaceDTO {
  workspaceId: string;
  userId: string;
  workspaceName: string;
  quotaBytes: number;
  usedBytes: number;
  fileCount: number;
  usagePercent: number;
}

export interface FileNodeDTO {
  fileId: string;
  parentId?: string | null;
  fileName: string;
  fileType: number; // 1=目录 2=文件
  filePath: string;
  fileSize?: number;
  mimeType?: string;
  version?: number;
  childrenCount?: number;
  createdAt?: number;
  updatedAt?: number;
}

export interface SandboxInfo {
  sandboxId: string;
  sandboxName: string;
  status: string;
  statusDesc: string;
  image?: string;
  mountPath?: string;
  workspaceId?: string;
}

// ==================== API ====================

/** 获取/创建当前用户工作空间 */
export async function getMyWorkspace(): Promise<WorkspaceDTO> {
  const res = await authFetch(`${BASE}/v1/workspaces/me`);
  return handleResponse<WorkspaceDTO>(res);
}

/** 文件树列表 */
export async function listFiles(parentId?: string): Promise<FileNodeDTO[]> {
  const params = parentId ? `?parentId=${parentId}` : '';
  const res = await authFetch(`${BASE}/v1/workspaces/files${params}`);
  return handleResponse<FileNodeDTO[]>(res);
}

/** 创建目录 */
export async function createDirectory(parentId: string | null, dirName: string): Promise<FileNodeDTO> {
  const res = await authFetch(`${BASE}/v1/workspaces/files/dirs`, {
    method: 'POST',
    body: JSON.stringify({ parentId: parentId || undefined, dirName }),
  });
  return handleResponse<FileNodeDTO>(res);
}

/** 上传文件 */
export async function uploadFile(file: File, parentId?: string): Promise<FileNodeDTO> {
  const formData = new FormData();
  formData.append('file', file);
  const url = parentId
    ? `${BASE}/v1/workspaces/files/upload?parentId=${parentId}`
    : `${BASE}/v1/workspaces/files/upload`;
  const res = await authFetchMultipart(url, formData);
  return handleResponse<FileNodeDTO>(res);
}

/** 读取文件内容 */
export async function getFileContent(fileId: string): Promise<string> {
  const res = await authFetch(`${BASE}/v1/workspaces/files/${fileId}/content`);
  if (!res.ok) throw new Error(`读取失败: ${res.status}`);
  return res.text();
}

/** 保存文件内容 */
export async function saveFileContent(fileId: string, content: string): Promise<FileNodeDTO> {
  const res = await authFetch(`${BASE}/v1/workspaces/files/${fileId}/content`, {
    method: 'PUT',
    body: content,
  });
  return handleResponse<FileNodeDTO>(res);
}

/** 重命名/移动文件 */
export async function renameFile(fileId: string, fileName: string, parentId?: string | null): Promise<FileNodeDTO> {
  const res = await authFetch(`${BASE}/v1/workspaces/files/${fileId}`, {
    method: 'PUT',
    body: JSON.stringify({ fileName, parentId: parentId || undefined }),
  });
  return handleResponse<FileNodeDTO>(res);
}

/** 删除文件/目录 */
export async function deleteFile(fileId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/workspaces/files/${fileId}`, { method: 'DELETE' });
  await handleResponse<void>(res);
}

/** 获取下载链接 */
export async function getDownloadUrl(fileId: string): Promise<string> {
  const res = await authFetch(`${BASE}/v1/workspaces/files/${fileId}/download`);
  const data = await handleResponse<{ downloadUrl: string }>(res);
  return data.downloadUrl;
}

/** 创建工作空间沙箱 */
export async function createWorkspaceSandbox(template: string, sandboxName?: string): Promise<SandboxInfo> {
  const res = await authFetch(`${BASE}/v1/workspaces/sandboxes`, {
    method: 'POST',
    body: JSON.stringify({ template, sandboxName }),
  });
  return handleResponse<SandboxInfo>(res);
}
