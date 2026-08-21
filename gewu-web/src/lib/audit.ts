// 审批中心 API - 对接后端 AuditCenterController
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const BASE = getBaseUrl();

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
  const json = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '请求失败');
  return json.data;
}

// ==================== 类型定义 ====================

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
  const res = await authFetch(`${BASE}/v1/admin/audits/pending`);
  return handleResponse<PendingAuditDTO[]>(res);
}

/** 审批通过 */
export async function approveAudit(auditType: string, targetId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/admin/audits/${auditType}/${targetId}/approve`, { method: 'POST' });
  return handleResponse<void>(res);
}

/** 审批拒绝 */
export async function rejectAudit(auditType: string, targetId: string, reason?: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/admin/audits/${auditType}/${targetId}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
  return handleResponse<void>(res);
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
  const res = await authFetch(`${BASE}/v1/approvals/pending`);
  return handleResponse<ApprovalRequestDTO[]>(res);
}

/** 批准 HITL 审批（恢复编排执行） */
export async function approveHitl(requestId: string, comment?: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/approvals/${requestId}/approve`, {
    method: 'POST',
    body: JSON.stringify({ comment }),
  });
  return handleResponse<void>(res);
}

/** 驳回 HITL 审批 */
export async function rejectHitl(requestId: string, comment?: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/approvals/${requestId}/reject`, {
    method: 'POST',
    body: JSON.stringify({ comment }),
  });
  return handleResponse<void>(res);
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
  const res = await authFetch(`${BASE}/v1/audit-chain/verify`);
  const json = await res.json();
  if (!json || json.code !== 10000) throw new Error(json?.message || '校验失败');
  return Boolean(json.data);
}

/** 审计记录列表 */
export async function listAuditChain(limit = 100): Promise<AuditChainRecordDTO[]> {
  const res = await authFetch(`${BASE}/v1/audit-chain?limit=${limit}`);
  return handleResponse<AuditChainRecordDTO[]>(res);
}

/** 按执行实例查询审计记录 */
export async function queryAuditChainByExecution(executionId: string): Promise<AuditChainRecordDTO[]> {
  const res = await authFetch(`${BASE}/v1/audit-chain/executions/${encodeURIComponent(executionId)}`);
  return handleResponse<AuditChainRecordDTO[]>(res);
}
