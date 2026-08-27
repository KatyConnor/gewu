// 会话记录服务 - 对接后端 SessionController
// T4.3 收敛：统一走 request.ts（axios 拦截器：token 注入/401 跳转），
// 原 authFetch/handleFetch 双轨封装已移除。
import { request } from './request';

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
  pinned?: number;
  slug?: string;
  shareUrl?: string;
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
  clientId?: string;
}

/** 分页查询 */
export interface PageQuery {
  page?: number;
  size?: number;
}

/** 分页结果 */
export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

/** 后端统一响应信封 */
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

// ==================== 信封解包 ====================

async function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  const res = await p;
  if (!res || res.code !== 10000) throw new Error(res?.message || '请求失败');
  return res.data;
}

// ==================== 会话 API ====================

/** 获取我的会话列表 */
export async function listMySessions(page = 1, size = 50): Promise<PageResult<SessionDTO>> {
  return unwrap(request.get<ApiResponse<PageResult<SessionDTO>>>(
    `/v1/sessions/my?page=${page}&size=${size}`));
}

/** 获取单个会话 */
export async function getSession(sessionId: string): Promise<SessionDTO> {
  return unwrap(request.get<ApiResponse<SessionDTO>>(`/v1/sessions/${sessionId}`));
}

/** 创建新会话 */
export async function createSession(data: CreateSessionRequest): Promise<SessionDTO> {
  return unwrap(request.post<ApiResponse<SessionDTO>>('/v1/sessions', data));
}

/** 更新会话 */
export async function updateSession(sessionId: string, data: UpdateSessionRequest): Promise<SessionDTO> {
  return unwrap(request.put<ApiResponse<SessionDTO>>(`/v1/sessions/${sessionId}`, data));
}

/** 删除会话（软删除） */
export async function deleteSession(sessionId: string): Promise<void> {
  await unwrap(request.delete<ApiResponse<void>>(`/v1/sessions/${sessionId}`));
}

// ==================== 会话增值生命周期（T3.3） ====================

/** 归档会话 */
export async function archiveSession(sessionId: string): Promise<SessionDTO> {
  return unwrap(request.put<ApiResponse<SessionDTO>>(`/v1/sessions/${sessionId}/archive`));
}

/** 取消归档 */
export async function unarchiveSession(sessionId: string): Promise<SessionDTO> {
  return unwrap(request.put<ApiResponse<SessionDTO>>(`/v1/sessions/${sessionId}/unarchive`));
}

/** 开启分享（幂等，返回 slug/shareUrl） */
export async function shareSession(sessionId: string): Promise<SessionDTO> {
  return unwrap(request.post<ApiResponse<SessionDTO>>(`/v1/sessions/${sessionId}/share`));
}

/** 取消分享 */
export async function unshareSession(sessionId: string): Promise<SessionDTO> {
  return unwrap(request.delete<ApiResponse<SessionDTO>>(`/v1/sessions/${sessionId}/share`));
}

/** 置顶 / 取消置顶 */
export async function pinSession(sessionId: string, pinned: boolean): Promise<SessionDTO> {
  const url = `/v1/sessions/${sessionId}/pin`;
  return unwrap(pinned
    ? request.put<ApiResponse<SessionDTO>>(url)
    : request.delete<ApiResponse<SessionDTO>>(url));
}

// ==================== 消息 API ====================

/** 获取会话消息列表 */
export async function listMessages(sessionId: string, page = 1, size = 100): Promise<PageResult<MessageDTO>> {
  return unwrap(request.get<ApiResponse<PageResult<MessageDTO>>>(
    `/v1/sessions/${sessionId}/messages?page=${page}&size=${size}`));
}

/** 发送消息 */
export async function sendMessage(sessionId: string, data: SendMessageRequest): Promise<MessageDTO> {
  return unwrap(request.post<ApiResponse<MessageDTO>>(`/v1/sessions/${sessionId}/messages`, data));
}
