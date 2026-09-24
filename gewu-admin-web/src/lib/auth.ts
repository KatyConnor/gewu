// 认证服务 — 对接主应用 Auth API（登录在主应用完成，token 与管理端同源互认）
import { getAccessToken, getRefreshToken } from './token';
import { API_ENDPOINTS } from './api';

/** 主应用认证基地址（登录/刷新在主应用 8081） */
const AUTH_BASE = typeof window !== 'undefined' && process.env.NEXT_PUBLIC_AUTH_BASE
  ? `${process.env.NEXT_PUBLIC_AUTH_BASE}/api`
  : 'http://localhost:8081/api';

// 后端统一响应结构
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

// 登录请求
export interface LoginRequest {
  username: string;
  password: string;
}

// 注册请求
export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  displayName: string;
  phone?: string;
}

// Token 响应
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  userId: string;
  username: string;
  displayName: string;
  roles: string[];
  permissions: string[];
}

// 刷新令牌请求
export interface RefreshRequest {
  refreshToken: string;
}

/**
 * 用户登录
 * @throws Error 登录失败时抛出错误，message 为后端返回的错误信息
 */
export async function login(data: LoginRequest): Promise<TokenResponse> {
  const res = await fetch(`${AUTH_BASE}${API_ENDPOINTS.AUTH_LOGIN}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  const json: ApiResponse<TokenResponse> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '登录失败');
  return json.data;
}

/**
 * 用户注册
 * @throws Error 注册失败时抛出错误
 */
export async function register(data: RegisterRequest): Promise<TokenResponse> {
  const res = await fetch(`${AUTH_BASE}${API_ENDPOINTS.AUTH_REGISTER}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  const json: ApiResponse<TokenResponse> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '注册失败');
  return json.data;
}

/**
 * 刷新令牌
 */
export async function refreshToken(data: RefreshRequest): Promise<TokenResponse> {
  const refresh = getRefreshToken();
  const res = await fetch(`${AUTH_BASE}${API_ENDPOINTS.AUTH_REFRESH}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(refresh ? { Authorization: `Bearer ${refresh}` } : {}),
    },
    body: JSON.stringify(data),
  });
  const json: ApiResponse<TokenResponse> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '刷新令牌失败');
  return json.data;
}

/**
 * 用户登出
 */
export async function logout(): Promise<void> {
  try {
    const token = getAccessToken();
    await fetch(`${AUTH_BASE}${API_ENDPOINTS.AUTH_LOGOUT}`, {
      method: 'POST',
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });
  } catch {
    // 登出失败不影响前端清理
  }
}
