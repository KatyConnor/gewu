// 角色权限管理 API - 对接后端 RoleController/PermissionController
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
  return unwrap(request.get<ApiResponse<RoleDTO[]>>('/v1/roles'));
}

/** 创建角色 */
export async function createRole(data: { roleName: string; roleCode: string; description?: string; sortOrder?: number }): Promise<RoleDTO> {
  return unwrap(request.post<ApiResponse<RoleDTO>>('/v1/roles', data));
}

/** 更新角色 */
export async function updateRole(roleId: string, data: { roleName?: string; description?: string; sortOrder?: number; dataScope?: number }): Promise<RoleDTO> {
  return unwrap(request.put<ApiResponse<RoleDTO>>(`/v1/roles/${roleId}`, data));
}

/** 删除角色（系统预置不可删） */
export async function deleteRole(roleId: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`/v1/roles/${roleId}`));
}

/** 分配角色权限（替换） */
export async function assignPermissions(roleId: string, permissionCodes: string[]): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`/v1/roles/${roleId}/permissions`, { permissionCodes }));
}

// ==================== 权限 API ====================

/** 权限列表 */
export async function listPermissions(): Promise<PermissionDTO[]> {
  return unwrap(request.get<ApiResponse<PermissionDTO[]>>('/v1/permissions'));
}

/** 创建权限 */
export async function createPermission(data: { permissionCode: string; permissionName: string; resourceType?: string; action?: string; description?: string }): Promise<PermissionDTO> {
  return unwrap(request.post<ApiResponse<PermissionDTO>>('/v1/permissions', data));
}
