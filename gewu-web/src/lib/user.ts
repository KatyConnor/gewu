// 用户管理 API - 对接后端 UserController（管理员功能）
// T4.3 收敛：统一走 request.ts（axios 拦截器：token 注入/401 跳转）
import { request } from './request';
import { API_ENDPOINTS } from './api';

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

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface UserDTO {
  userId: string;
  username: string;
  email: string;
  phone?: string;
  displayName?: string;
  avatarUrl?: string;
  status: number;
  lastLoginAt?: number;
  orgId?: string;
  orgName?: string;
  roleCodes: string[];
}

/** 角色选项（对应 V1 种子 9 角色） */
export const ROLE_OPTIONS = [
  { value: 'ADMIN', label: '系统管理员' },
  { value: 'ARCHITECT', label: '架构师' },
  { value: 'PRODUCT_MANAGER', label: '产品经理' },
  { value: 'BACKEND_DEV', label: '后端开发' },
  { value: 'FRONTEND_DEV', label: '前端开发' },
  { value: 'TESTER', label: '测试工程师' },
  { value: 'OPS_ENGINEER', label: '运维工程师' },
  { value: 'SECURITY_ENGINEER', label: '安全工程师' },
  { value: 'USER', label: '普通用户' },
];

// ==================== API 函数 ====================

/** 用户列表（管理员，支持搜索与机构过滤） */
export async function listUsers(page = 1, size = 20, keyword?: string, orgId?: string): Promise<PageResult<UserDTO>> {
  let url = `${API_ENDPOINTS.USERS}?page=${page}&size=${size}`;
  if (keyword) url += `&keyword=${encodeURIComponent(keyword)}`;
  if (orgId) url += `&orgId=${encodeURIComponent(orgId)}`;
  return unwrap(request.get<ApiResponse<PageResult<UserDTO>>>(url));
}

/** 更新用户状态（1=启用 2=禁用 3=锁定） */
export async function updateUserStatus(userId: string, status: number): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`${API_ENDPOINTS.USERS}/${userId}/status`, { status }));
}

/** 重置用户密码 */
export async function resetPassword(userId: string, newPassword: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`${API_ENDPOINTS.USERS}/${userId}/reset-password`,
    { newPassword }));
}

/** 分配角色 */
export async function assignRoles(userId: string, roleCodes: string[]): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`${API_ENDPOINTS.USERS}/${userId}/roles`, { roleCodes }));
}

/** 分配机构 */
export async function assignOrg(userId: string, orgId: string | null): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`${API_ENDPOINTS.USERS}/${userId}/org`, { orgId }));
}
