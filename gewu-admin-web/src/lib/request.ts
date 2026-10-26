// HTTP 请求工具
import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';
import { API_CONFIG } from './api';
import { getAccessToken } from './token';
import { store, clearAuth } from '@/store';

// 创建 axios 实例
// 开发环境：baseURL 为 '/api'，由 Next.js rewrites 代理到 localhost:8081
const instance: AxiosInstance = axios.create({
  baseURL: API_CONFIG.BASE_URL,
  timeout: API_CONFIG.TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
});

// 请求拦截器
instance.interceptors.request.use(
  (config) => {
    // 从 token 管理工具获取 accessToken
    const token = getAccessToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 响应拦截器 — 返回 response.data（剥离 AxiosResponse 外壳）
instance.interceptors.response.use(
  (response) => {
    return response.data;
  },
  (error) => {
    if (error.response) {
      switch (error.response.status) {
        case 401:
          // 未授权（含令牌过期/无效）：清凭证并切到独立登录页（clearAuth 内含 clearTokens）
          store.dispatch(clearAuth());
          break;
        case 403:
          console.error('没有权限访问');
          break;
        case 404:
          console.error('请求资源不存在');
          break;
        case 500:
          console.error('服务器内部错误');
          break;
      }
    }
    return Promise.reject(error);
  }
);

/**
 * 类型安全的请求封装 — 响应拦截器已剥离 AxiosResponse 外壳，
 * post/get/put/delete 直接返回 response.data（即 T 类型）。
 */
export const request = {
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> => {
    return instance.post(url, data, config) as unknown as Promise<T>;
  },
  get: <T>(url: string, config?: AxiosRequestConfig): Promise<T> => {
    return instance.get(url, config) as unknown as Promise<T>;
  },
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> => {
    return instance.put(url, data, config) as unknown as Promise<T>;
  },
  delete: <T>(url: string, config?: AxiosRequestConfig): Promise<T> => {
    return instance.delete(url, config) as unknown as Promise<T>;
  },
};

export default request;
