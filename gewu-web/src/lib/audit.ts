// 审批中心 API - 对接后端 AuditCenterController
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

export interface PendingAuditDTO {
  /** 审批类型：SKILL_PUBLISH / AGENT_MARKET */
  auditType: string;
  targetId: string;
  name: string;
  description?: string;
  content?: string;
  category?: string;
  emoji?: string;
  createdAt?: number;
  createdBy?: string;
}

// ==================== API 函数 ====================

/** 待审核列表（聚合技能发布 + 智能体上架） */
export async function listPendingAudits(): Promise<PendingAuditDTO[]> {
  return unwrap(request.get<ApiResponse<PendingAuditDTO[]>>('/v1/admin/audits/pending'));
}

/** 审批通过 */
export async function approveAudit(auditType: string, targetId: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`/v1/admin/audits/${auditType}/${targetId}/approve`));
}

/** 审批拒绝 */
export async function rejectAudit(auditType: string, targetId: string, reason?: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`/v1/admin/audits/${auditType}/${targetId}/reject`,
    { reason }));
}

// ==================== HITL 审批（编排人工介入） ====================

/** HITL 审批请求（编排执行暂停时产生） */
export interface ApprovalRequestDTO {
  id: string;
  executionId?: string;
  nodeId?: string;
  approvalType?: string;
  payload?: string;
  status: string;
  approver?: string;
  approvalComment?: string;
  approvedAt?: number;
  timeoutAt?: number;
  createdAt?: number;
}

/** 待处理 HITL 审批列表 */
export async function listPendingApprovals(): Promise<ApprovalRequestDTO[]> {
  return unwrap(request.get<ApiResponse<ApprovalRequestDTO[]>>('/v1/approvals/pending'));
}

/** 批准 HITL 审批（恢复编排执行） */
export async function approveHitl(requestId: string, comment?: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`/v1/approvals/${requestId}/approve`, { comment }));
}

/** 驳回 HITL 审批 */
export async function rejectHitl(requestId: string, comment?: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`/v1/approvals/${requestId}/reject`, { comment }));
}

// ==================== WORM 审计链 ====================

/** 审计链记录（链式 SHA-256 哈希，不可篡改） */
export interface AuditChainRecordDTO {
  id: string;
  eventType?: string;
  executionId?: string;
  actor?: string;
  action?: string;
  decisionTrace?: string;
  hashPrevious?: string;
  hashCurrent?: string;
  verified?: number;
  createdAt?: number;
}

/** 校验审计链完整性（逐条重算哈希检测篡改） */
export async function verifyAuditChain(): Promise<boolean> {
  const res = await unwrap(request.get<ApiResponse<boolean>>('/v1/audit-chain/verify'));
  return Boolean(res);
}

/** 审计记录列表 */
export async function listAuditChain(limit = 100): Promise<AuditChainRecordDTO[]> {
  return unwrap(request.get<ApiResponse<AuditChainRecordDTO[]>>(`/v1/audit-chain?limit=${limit}`));
}

/** 按执行实例查询审计记录 */
export async function queryAuditChainByExecution(executionId: string): Promise<AuditChainRecordDTO[]> {
  return unwrap(request.get<ApiResponse<AuditChainRecordDTO[]>>(
    `/v1/audit-chain/executions/${encodeURIComponent(executionId)}`));
}
