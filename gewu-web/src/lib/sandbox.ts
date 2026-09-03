// 沙箱 API - 对接后端 SandboxController（沙箱服务）
// T4.3 收敛：统一走 request.ts（axios 拦截器：token 注入/401 跳转）
import { request } from './request';

// ==================== 类型定义 ====================

/** 后端统一响应信封 */
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

/** 信封解包（session.ts 试点范式） */
async function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  const res = await p;
  if (!res || res.code !== 10000) throw new Error(res?.message || '请求失败');
  return res.data;
}

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
  return unwrap(request.get<ApiResponse<SandboxDTO[]>>('/v1/sandboxes'));
}

export async function getSandbox(id: string): Promise<SandboxDTO> {
  return unwrap(request.get<ApiResponse<SandboxDTO>>(`/v1/sandboxes/${id}`));
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
  return unwrap(request.post<ApiResponse<SandboxDTO>>('/v1/sandboxes', command));
}

export async function startSandbox(id: string): Promise<SandboxDTO> {
  return unwrap(request.post<ApiResponse<SandboxDTO>>(`/v1/sandboxes/${id}/start`));
}

export async function stopSandbox(id: string): Promise<SandboxDTO> {
  return unwrap(request.post<ApiResponse<SandboxDTO>>(`/v1/sandboxes/${id}/stop`));
}

export async function deleteSandbox(id: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`/v1/sandboxes/${id}`));
}

export async function renewSandboxExpire(id: string, expireSeconds: number): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`/v1/sandboxes/${id}/expire`, { expireSeconds }));
}
