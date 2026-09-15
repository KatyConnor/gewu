// 模型配置服务 — 对接后端 ModelConfigController
import { getAccessToken } from './token';

// 后端统一响应结构
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

// 供应商
export interface Provider {
  id: string;
  providerCode: string;
  providerName: string;
  baseUrl: string;
  description: string;
  logoLetter: string;
  logoColor: string;
  textColor: string;
  status: number; // 1=启用 2=停用
  modelsCount?: number;
}

// 模型配置
export interface ModelConfig {
  id: string;
  providerId: string;
  providerCode: string;
  providerName: string;
  modelName: string;
  modelId: string;
  modelParams: string;
  description: string;
  status: number; // 1=启用 2=停用
}

// 创建供应商请求
export interface CreateProviderRequest {
  providerName: string;
  providerCode: string;
  baseUrl: string;
  apiKey?: string;
  description?: string;
  logoLetter?: string;
  logoColor?: string;
  textColor?: string;
  enableImmediately?: boolean;
}

// 更新供应商请求
export interface UpdateProviderRequest {
  providerName?: string;
  baseUrl?: string;
  apiKey?: string;
  description?: string;
  logoLetter?: string;
  logoColor?: string;
  textColor?: string;
}

// 创建模型请求
export interface CreateModelRequest {
  providerId: string;
  modelName: string;
  modelId: string;
  modelParams?: string;
  description?: string;
  enableImmediately?: boolean;
}

// 更新模型请求
export interface UpdateModelRequest {
  providerId?: string;
  modelName?: string;
  modelParams?: string;
  description?: string;
}

const API_BASE = typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE
  ? `${process.env.NEXT_PUBLIC_API_BASE}/v1/models`
  : '/api/v1/models';

/**
 * 获取所有供应商
 */
export async function listProviders(): Promise<Provider[]> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/providers`, {
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`获取供应商列表失败: ${res.status}`);
  const json: ApiResponse<Provider[]> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '获取供应商列表失败');
  return json.data;
}

/**
 * 获取已启用的供应商
 */
export async function listActiveProviders(): Promise<Provider[]> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/providers/active`, {
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`获取供应商列表失败: ${res.status}`);
  const json: ApiResponse<Provider[]> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '获取供应商列表失败');
  return json.data;
}

/**
 * 创建供应商
 */
export async function createProvider(data: CreateProviderRequest): Promise<Provider> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/providers`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`创建供应商失败: ${res.status}`);
  const json: ApiResponse<Provider> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '创建供应商失败');
  return json.data;
}

/**
 * 更新供应商
 */
export async function updateProvider(providerId: string, data: UpdateProviderRequest): Promise<Provider> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/providers/${providerId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`更新供应商失败: ${res.status}`);
  const json: ApiResponse<Provider> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '更新供应商失败');
  return json.data;
}

/**
 * 切换供应商状态
 */
export async function toggleProviderStatus(providerId: string): Promise<void> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/providers/${providerId}/toggle`, {
    method: 'POST',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`切换供应商状态失败: ${res.status}`);
}

/**
 * 删除供应商
 */
export async function deleteProvider(providerId: string): Promise<void> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/providers/${providerId}`, {
    method: 'DELETE',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`删除供应商失败: ${res.status}`);
}

/**
 * 获取所有模型
 */
export async function listModels(): Promise<ModelConfig[]> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}`, {
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`获取模型列表失败: ${res.status}`);
  const json: ApiResponse<ModelConfig[]> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '获取模型列表失败');
  return json.data;
}

/**
 * 获取已启用的模型（用于会话页面模型选择）
 */
export async function listActiveModels(): Promise<ModelConfig[]> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/active`, {
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`获取模型列表失败: ${res.status}`);
  const json: ApiResponse<ModelConfig[]> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '获取模型列表失败');
  return json.data;
}

/**
 * 创建模型
 */
export async function createModel(data: CreateModelRequest): Promise<ModelConfig> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`创建模型失败: ${res.status}`);
  const json: ApiResponse<ModelConfig> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '创建模型失败');
  return json.data;
}

/**
 * 更新模型
 */
export async function updateModel(modelId: string, data: UpdateModelRequest): Promise<ModelConfig> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/${modelId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`更新模型失败: ${res.status}`);
  const json: ApiResponse<ModelConfig> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '更新模型失败');
  return json.data;
}

/**
 * 切换模型状态
 */
export async function toggleModelStatus(modelId: string): Promise<void> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/${modelId}/toggle`, {
    method: 'POST',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`切换模型状态失败: ${res.status}`);
}

/**
 * 删除模型
 */
export async function deleteModel(modelId: string): Promise<void> {
  const token = getAccessToken();
  const res = await fetch(`${API_BASE}/${modelId}`, {
    method: 'DELETE',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`删除模型失败: ${res.status}`);
}