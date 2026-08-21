// 用户管理 API - 对接后端 UserController（管理员功能）
import { getAccessToken } from './token';
import { API_ENDPOINTS } from './api';

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
  let url = `${BASE}${API_ENDPOINTS.USERS}?page=${page}&size=${size}`;
  if (keyword) url += `&keyword=${encodeURIComponent(keyword)}`;
  if (orgId) url += `&orgId=${encodeURIComponent(orgId)}`;
  const res = await authFetch(url);
  return handleResponse<PageResult<UserDTO>>(res);
}

/** 更新用户状态（1=启用 2=禁用 3=锁定） */
export async function updateUserStatus(userId: string, status: number): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.USERS}/${userId}/status`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  });
  return handleResponse<void>(res);
}

/** 重置用户密码 */
export async function resetPassword(userId: string, newPassword: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.USERS}/${userId}/reset-password`, {
    method: 'POST',
    body: JSON.stringify({ newPassword }),
  });
  return handleResponse<void>(res);
}

/** 分配角色 */
export async function assignRoles(userId: string, roleCodes: string[]): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.USERS}/${userId}/roles`, {
    method: 'PUT',
    body: JSON.stringify({ roleCodes }),
  });
  return handleResponse<void>(res);
}

/** 分配机构 */
export async function assignOrg(userId: string, orgId: string | null): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.USERS}/${userId}/org`, {
    method: 'PUT',
    body: JSON.stringify({ orgId }),
  });
  return handleResponse<void>(res);
}
