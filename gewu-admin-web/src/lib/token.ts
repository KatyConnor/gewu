// Token 管理工具 — 管理 accessToken/refreshToken 的存储与读取

const ACCESS_TOKEN_KEY = 'gewu-access-token';
const REFRESH_TOKEN_KEY = 'gewu-refresh-token';
const USER_KEY = 'gewu-user';

export interface StoredUser {
  userId: string;
  username: string;
  displayName: string;
  roles: string[];
  permissions: string[];
}

/** 保存令牌 */
export function saveTokens(accessToken: string, refreshToken: string): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

/** 获取 accessToken */
export function getAccessToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

/** 获取 refreshToken */
export function getRefreshToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

/** 清除令牌 */
export function clearTokens(): void {
  if (typeof window === 'undefined') return;
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

/** 保存用户信息 */
export function saveUser(user: StoredUser): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/** 获取用户信息 */
export function getUser(): StoredUser | null {
  if (typeof window === 'undefined') return null;
  const data = localStorage.getItem(USER_KEY);
  if (!data) return null;
  try {
    return JSON.parse(data);
  } catch {
    return null;
  }
}

/** 检查是否已登录 */
export function isAuthenticated(): boolean {
  return !!getAccessToken();
}

/** 检查 JWT 令牌是否过期 */
export function isTokenExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    const exp = payload.exp;
    if (!exp) return false;
    // 添加 30 秒缓冲，避免边界情况
    return Date.now() >= (exp * 1000) - 30000;
  } catch {
    return true;
  }
}

/** 检查当前令牌是否有效（存在且未过期） */
export function isTokenValid(): boolean {
  const token = getAccessToken();
  if (!token) return false;
  return !isTokenExpired(token);
}
