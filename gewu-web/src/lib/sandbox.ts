// 沙箱 API - 对接后端 SandboxController（沙箱服务）
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

// ==================== 类型定义 ====================

export interface SandboxDTO {
  sandboxId: string;
  sandboxName?: string;
  image?: string;
  status?: string;
  statusDesc?: string;
  cpuCores?: number;
  memoryMb?: number;
  diskMb?: number;
  networkEnabled?: boolean;
  timeoutSeconds?: number;
  runtime?: string;
  createdAt?: number;
  startedAt?: number;
  stoppedAt?: number;
  ip?: string;
  ports?: string;
}

// ==================== API ====================

export async function listSandboxes(): Promise<SandboxDTO[]> {
  const res = await authFetch(`${BASE}/v1/sandboxes`);
  return handleResponse(res);
}

export async function getSandbox(id: string): Promise<SandboxDTO> {
  const res = await authFetch(`${BASE}/v1/sandboxes/${id}`);
  return handleResponse(res);
}

export async function createSandbox(command: {
  sandboxName: string;
  image: string;
  cpuCores?: number;
  memoryMb?: number;
  diskMb?: number;
  networkEnabled?: boolean;
  timeout?: number;
}): Promise<SandboxDTO> {
  const res = await authFetch(`${BASE}/v1/sandboxes`, {
    method: 'POST',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

export async function startSandbox(id: string): Promise<SandboxDTO> {
  const res = await authFetch(`${BASE}/v1/sandboxes/${id}/start`, { method: 'POST' });
  return handleResponse(res);
}

export async function stopSandbox(id: string): Promise<SandboxDTO> {
  const res = await authFetch(`${BASE}/v1/sandboxes/${id}/stop`, { method: 'POST' });
  return handleResponse(res);
}

export async function deleteSandbox(id: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/sandboxes/${id}`, { method: 'DELETE' });
  await handleResponse(res);
}

export async function renewSandboxExpire(id: string, expireSeconds: number): Promise<void> {
  const res = await authFetch(`${BASE}/v1/sandboxes/${id}/expire`, {
    method: 'PUT',
    body: JSON.stringify({ expireSeconds }),
  });
  await handleResponse(res);
}
