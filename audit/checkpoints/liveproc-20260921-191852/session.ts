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
  workspaceId?: string;
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
  /** 助手消息元数据 JSON（过程时间线摘要，S9） */
  metadata?: string;
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

/** 我的会话列表过滤参数（S9 F1：项目分组/归档视图） */
export interface SessionListFilter {
  /** 精确匹配项目 ID */
  projectId?: string;
  /** true=仅无项目的默认空间会话 */
  defaultSpace?: boolean;
  /** 状态精确过滤（0 进行中/1 已完成/2 已归档）；缺省=排除已归档 */
  status?: number;
}

/** 获取我的会话列表（支持项目/默认空间/归档过滤） */
export async function listMySessions(
  page = 1,
  size = 50,
  filter?: SessionListFilter
): Promise<PageResult<SessionDTO>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (filter?.projectId) params.set('projectId', filter.projectId);
  if (filter?.defaultSpace) params.set('defaultSpace', 'true');
  if (filter?.status !== undefined) params.set('status', String(filter.status));
  return unwrap(request.get<ApiResponse<PageResult<SessionDTO>>>(
    `/v1/sessions/my?${params.toString()}`));
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

/**
 * 获取会话消息列表。
 * order='desc' 取最新一页（后端反转为升序返回）——修复长会话固定取最旧
 * 100 条导致最新消息被截断的问题；缺省 'asc' 保持旧行为。
 */
export async function listMessages(
  sessionId: string, page = 1, size = 100, order?: 'asc' | 'desc'
): Promise<PageResult<MessageDTO>> {
  const orderParam = order ? `&order=${order}` : '';
  return unwrap(request.get<ApiResponse<PageResult<MessageDTO>>>(
    `/v1/sessions/${sessionId}/messages?page=${page}&size=${size}${orderParam}`));
}

/** 发送消息 */
export async function sendMessage(sessionId: string, data: SendMessageRequest): Promise<MessageDTO> {
  return unwrap(request.post<ApiResponse<MessageDTO>>(`/v1/sessions/${sessionId}/messages`, data));
}

// ==================== 运行状态（断连不中断修复） ====================

/** 会话运行状态：active=true 表示有聊天任务仍在后台执行；pendingAsk 非空=有 ask_user 挂起问 */
export interface RunStatusDTO {
  active: boolean;
  status: 'RUNNING' | 'DONE' | 'FAILED' | 'IDLE';
  startedAt: number;
  elapsedMs: number;
  pendingAsk?: PendingAskDTO | null;
}

/** 挂起问（ask_user 等待用户回答，断连重进后据此恢复问答框） */
export interface PendingAskDTO {
  askId: string;
  question: string;
  options: string[];
}

/** 查询会话是否有正在后台执行的聊天任务（离开页面后任务继续跑，重进据此轮询等待） */
export async function getSessionRunStatus(sessionId: string): Promise<RunStatusDTO> {
  return unwrap(request.get<ApiResponse<RunStatusDTO>>(`/v1/sessions/${sessionId}/run/status`));
}

/** 回答会话挂起问（answer 为空=跳过），后端恢复任务执行 */
export async function answerSessionAsk(sessionId: string, askId: string, answer: string): Promise<void> {
  await unwrap(request.post<ApiResponse<void>>(
    `/v1/sessions/${sessionId}/ask/${askId}/answer`, { answer }));
}
