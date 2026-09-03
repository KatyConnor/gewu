// 菜单管理 API - 对接后端 MenuController
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
  return unwrap(request.get<ApiResponse<MenuDTO[]>>('/v1/menus/current'));
}

/** 全量菜单树（管理员） */
export async function listMenus(): Promise<MenuDTO[]> {
  return unwrap(request.get<ApiResponse<MenuDTO[]>>('/v1/menus'));
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
  return unwrap(request.post<ApiResponse<MenuDTO>>('/v1/menus', data));
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
  return unwrap(request.put<ApiResponse<MenuDTO>>(`/v1/menus/${menuId}`, data));
}

/** 删除菜单（存在子菜单时无法删除） */
export async function deleteMenu(menuId: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`/v1/menus/${menuId}`));
}

/** 角色已分配的菜单ID列表 */
export async function listRoleMenus(roleId: string): Promise<string[]> {
  return unwrap(request.get<ApiResponse<string[]>>(`/v1/menus/roles/${roleId}`));
}

/** 分配角色菜单（替换） */
export async function assignRoleMenus(roleId: string, menuIds: string[]): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`/v1/menus/roles/${roleId}`, { menuIds }));
}
