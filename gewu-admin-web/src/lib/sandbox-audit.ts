// 沙箱审计查询 — 对接 admin-server SandboxAuditController
import { request } from './request';

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

export interface SandboxAuditItem {
  logId: string;
  sandboxId: string;
  action: string;
  status?: string;
  detail?: string;
  operatorId?: string;
  createdAt?: number;
}

/** 获取所有沙箱审计日志（管理员） */
export async function listSandboxAudits(): Promise<SandboxAuditItem[]> {
  const res = await request.get<ApiResponse<SandboxAuditItem[]>>('/v1/sandbox-audits/audit');
  if (!res || res.code !== 10000) throw new Error(res?.message || '获取沙箱审计失败');
  return res.data;
}

/** 获取指定沙箱的审计日志 */
export async function getSandboxAudits(sandboxId: string): Promise<SandboxAuditItem[]> {
  const res = await request.get<ApiResponse<SandboxAuditItem[]>>(`/v1/sandbox-audits/${sandboxId}/audit`);
  if (!res || res.code !== 10000) throw new Error(res?.message || '获取沙箱审计失败');
  return res.data;
}
