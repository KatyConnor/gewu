// 认证服务 — 对接后端 Auth API
import { request } from './request';
import { API_ENDPOINTS } from './api';

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
  const res = await request.post<ApiResponse<TokenResponse>>(
    API_ENDPOINTS.AUTH_LOGIN,
    data
  );
  if (!res || res.code !== 10000) {
    throw new Error(res?.message || '登录失败');
  }
  return res.data;
}

/**
 * 用户注册
 * @throws Error 注册失败时抛出错误
 */
export async function register(data: RegisterRequest): Promise<TokenResponse> {
  const res = await request.post<ApiResponse<TokenResponse>>(
    API_ENDPOINTS.AUTH_REGISTER,
    data
  );
  if (!res || res.code !== 10000) {
    throw new Error(res?.message || '注册失败');
  }
  return res.data;
}

/**
 * 刷新令牌
 */
export async function refreshToken(data: RefreshRequest): Promise<TokenResponse> {
  const res = await request.post<ApiResponse<TokenResponse>>(
    API_ENDPOINTS.AUTH_REFRESH,
    data
  );
  if (!res || res.code !== 10000) {
    throw new Error(res?.message || '刷新令牌失败');
  }
  return res.data;
}

/**
 * 用户登出
 */
export async function logout(): Promise<void> {
  try {
    await request.post<ApiResponse<void>>(API_ENDPOINTS.AUTH_LOGOUT);
  } catch {
    // 登出失败不影响前端清理
  }
}
