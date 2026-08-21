// 权限码检查 hook - 基于当前用户 permissions 做按钮/功能级控制
import { useSelector } from 'react-redux';
import type { RootState } from '@/store';

/**
 * 读取当前用户权限码，提供 hasPermission 等检查函数.
 * 后端 TokenDTO/UserDTO 下发 permissions，登录时存入 store.
 */
export function usePermission() {
  const permissions = useSelector((s: RootState) => s.app.user?.permissions || []);

  /** 是否拥有指定权限码 */
  const hasPermission = (code: string): boolean => permissions.includes(code);

  /** 是否拥有任意一个权限码 */
  const hasAnyPermission = (codes: string[]): boolean =>
    codes.some((c) => permissions.includes(c));

  /** 是否拥有全部权限码 */
  const hasAllPermissions = (codes: string[]): boolean =>
    codes.every((c) => permissions.includes(c));

  return { permissions, hasPermission, hasAnyPermission, hasAllPermissions };
}
