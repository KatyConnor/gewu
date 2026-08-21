// 菜单管理 API - 对接后端 MenuController
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

export interface MenuDTO {
  menuId: string;
  parentId: string | null;
  menuName: string;
  menuType: number; // 1目录 2菜单 3按钮
  path: string | null;
  icon: string | null;
  sortOrder: number;
  permissionCode: string | null;
  visible: number;
  children: MenuDTO[];
}

// ==================== 菜单 API ====================

/** 当前用户可见菜单树 */
export async function listCurrentMenus(): Promise<MenuDTO[]> {
  const res = await authFetch(`${BASE}/v1/menus/current`);
  return handleResponse<MenuDTO[]>(res);
}

/** 全量菜单树（管理员） */
export async function listMenus(): Promise<MenuDTO[]> {
  const res = await authFetch(`${BASE}/v1/menus`);
  return handleResponse<MenuDTO[]>(res);
}

/** 创建菜单 */
export async function createMenu(data: {
  parentId?: string;
  menuName: string;
  menuType?: number;
  path?: string;
  icon?: string;
  sortOrder?: number;
  permissionCode?: string;
  visible?: number;
}): Promise<MenuDTO> {
  const res = await authFetch(`${BASE}/v1/menus`, { method: 'POST', body: JSON.stringify(data) });
  return handleResponse<MenuDTO>(res);
}

/** 更新菜单 */
export async function updateMenu(menuId: string, data: {
  parentId?: string;
  menuName?: string;
  menuType?: number;
  path?: string;
  icon?: string;
  sortOrder?: number;
  permissionCode?: string;
  visible?: number;
}): Promise<MenuDTO> {
  const res = await authFetch(`${BASE}/v1/menus/${menuId}`, { method: 'PUT', body: JSON.stringify(data) });
  return handleResponse<MenuDTO>(res);
}

/** 删除菜单（存在子菜单时无法删除） */
export async function deleteMenu(menuId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/menus/${menuId}`, { method: 'DELETE' });
  return handleResponse<void>(res);
}

/** 角色已分配的菜单ID列表 */
export async function listRoleMenus(roleId: string): Promise<string[]> {
  const res = await authFetch(`${BASE}/v1/menus/roles/${roleId}`);
  return handleResponse<string[]>(res);
}

/** 分配角色菜单（替换） */
export async function assignRoleMenus(roleId: string, menuIds: string[]): Promise<void> {
  const res = await authFetch(`${BASE}/v1/menus/roles/${roleId}`, { method: 'PUT', body: JSON.stringify({ menuIds }) });
  return handleResponse<void>(res);
}
