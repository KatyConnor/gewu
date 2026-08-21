// 角色权限管理 API - 对接后端 RoleController/PermissionController
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

export interface RoleDTO {
  roleId: string;
  roleName: string;
  roleCode: string;
  description?: string;
  isSystem?: number;
  sortOrder?: number;
  /** 数据范围: 1全部 2本部门 3本部门及以下 4本人 */
  dataScope?: number;
  permissionCodes: string[];
  userCount?: number;
}

export interface PermissionDTO {
  permissionId: string;
  permissionCode: string;
  permissionName: string;
  resourceType?: string;
  action?: string;
  description?: string;
}

// ==================== 角色 API ====================

/** 角色列表（含权限码与用户数） */
export async function listRoles(): Promise<RoleDTO[]> {
  const res = await authFetch(`${BASE}/v1/roles`);
  return handleResponse<RoleDTO[]>(res);
}

/** 创建角色 */
export async function createRole(data: { roleName: string; roleCode: string; description?: string; sortOrder?: number }): Promise<RoleDTO> {
  const res = await authFetch(`${BASE}/v1/roles`, { method: 'POST', body: JSON.stringify(data) });
  return handleResponse<RoleDTO>(res);
}

/** 更新角色 */
export async function updateRole(roleId: string, data: { roleName?: string; description?: string; sortOrder?: number; dataScope?: number }): Promise<RoleDTO> {
  const res = await authFetch(`${BASE}/v1/roles/${roleId}`, { method: 'PUT', body: JSON.stringify(data) });
  return handleResponse<RoleDTO>(res);
}

/** 删除角色（系统预置不可删） */
export async function deleteRole(roleId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/roles/${roleId}`, { method: 'DELETE' });
  return handleResponse<void>(res);
}

/** 分配角色权限（替换） */
export async function assignPermissions(roleId: string, permissionCodes: string[]): Promise<void> {
  const res = await authFetch(`${BASE}/v1/roles/${roleId}/permissions`, { method: 'PUT', body: JSON.stringify({ permissionCodes }) });
  return handleResponse<void>(res);
}

// ==================== 权限 API ====================

/** 权限列表 */
export async function listPermissions(): Promise<PermissionDTO[]> {
  const res = await authFetch(`${BASE}/v1/permissions`);
  return handleResponse<PermissionDTO[]>(res);
}

/** 创建权限 */
export async function createPermission(data: { permissionCode: string; permissionName: string; resourceType?: string; action?: string; description?: string }): Promise<PermissionDTO> {
  const res = await authFetch(`${BASE}/v1/permissions`, { method: 'POST', body: JSON.stringify(data) });
  return handleResponse<PermissionDTO>(res);
}
