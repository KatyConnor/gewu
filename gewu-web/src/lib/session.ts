// 会话记录服务 — 对接后端 SessionController
import { API_ENDPOINTS } from './api';
import { getAccessToken } from './token';

// ==================== 类型定义 ====================

/** 会话 DTO */
export interface SessionDTO {
  sessionId: string;
  title: string;
  type: number;
  typeDesc: string;
  projectId?: string;
  status: number;
  statusDesc: string;
  isPublic: number;
  messageCount: number;
  lastMessageAt: number | null;
  agent?: string;
  directory?: string;
  createdAt: number;
  createdBy?: string;
}

/** 创建会话请求 */
export interface CreateSessionRequest {
  title?: string;
  type?: number;
  projectId?: string;
  agent?: string;
  directory?: string;
  isPublic?: number;
}

/** 更新会话请求 */
export interface UpdateSessionRequest {
  title?: string;
  status?: number;
  isPublic?: number;
  agent?: string;
  directory?: string;
}

/** 消息 DTO */
export interface MessageDTO {
  messageId: string;
  sessionId: string;
  senderId: string;
  senderName: string;
  messageType: string;
  content: string;
  replyTo?: string;
  seq: number;
  edited: number;
  createdAt: number;
}

/** 发送消息请求 */
export interface SendMessageRequest {
  content: string;
  messageType?: string;
  replyTo?: string;
}

/** 分页查询 */
export interface PageQuery {
  page?: number;
  size?: number;
}

/** 分页结果 */
interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

/** 后端统一响应 */
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

// ==================== API 基础地址 ====================

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const API_BASE = `${getBaseUrl()}/v1/sessions`;
const MESSAGES_BASE = `${getBaseUrl()}/v1/sessions`;

// ==================== 会话 API ====================

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
  const json: ApiResponse<T> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '请求失败');
  return json.data;
}

/** 获取我的会话列表 */
export async function listMySessions(page = 1, size = 50): Promise<PageResult<SessionDTO>> {
  const res = await authFetch(`${API_BASE}/my?page=${page}&size=${size}`);
  return handleResponse<PageResult<SessionDTO>>(res);
}

/** 获取单个会话 */
export async function getSession(sessionId: string): Promise<SessionDTO> {
  const res = await authFetch(`${API_BASE}/${sessionId}`);
  return handleResponse<SessionDTO>(res);
}

/** 创建新会话 */
export async function createSession(data: CreateSessionRequest): Promise<SessionDTO> {
  const res = await authFetch(API_BASE, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<SessionDTO>(res);
}

/** 更新会话 */
export async function updateSession(sessionId: string, data: UpdateSessionRequest): Promise<SessionDTO> {
  const res = await authFetch(`${API_BASE}/${sessionId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
  return handleResponse<SessionDTO>(res);
}

/** 删除会话（软删除） */
export async function deleteSession(sessionId: string): Promise<void> {
  const res = await authFetch(`${API_BASE}/${sessionId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`删除失败: ${res.status}`);
  const json: ApiResponse<void> = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '删除失败');
}

// ==================== 消息 API ====================

/** 获取会话消息列表 */
export async function listMessages(sessionId: string, page = 1, size = 100): Promise<PageResult<MessageDTO>> {
  const res = await authFetch(`${MESSAGES_BASE}/${sessionId}/messages?page=${page}&size=${size}`);
  return handleResponse<PageResult<MessageDTO>>(res);
}

/** 发送消息 */
export async function sendMessage(sessionId: string, data: SendMessageRequest): Promise<MessageDTO> {
  const res = await authFetch(`${MESSAGES_BASE}/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<MessageDTO>(res);
}
