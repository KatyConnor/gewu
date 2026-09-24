// 技能审核 — 对接 admin-server AdminSkillAuditController
import { request } from './request';

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

export interface SkillDTO {
  skillId: string;
  skillName: string;
  description?: string;
  category?: string;
  content?: string;
  emoji?: string;
  tags?: string[];
  installCount?: number;
  status?: number;
  version?: string;
  publishStatus?: number;
  publishStatusDesc?: string;
  createdAt?: number;
  createdBy?: string;
}

/** 待审核技能列表（管理员） */
export async function listPendingSkills(): Promise<SkillDTO[]> {
  const res = await request.get<ApiResponse<SkillDTO[]>>('/v1/skills/pending');
  if (!res || res.code !== 10000) throw new Error(res?.message || '获取待审核技能失败');
  return res.data;
}

/** 审核技能（管理员） */
export async function auditSkill(skillId: string, approved: boolean, reason?: string): Promise<void> {
  const res = await request.post<ApiResponse<void>>(`/v1/skills/${skillId}/audit`, {
    approved,
    reason: reason || undefined,
  });
  if (!res || res.code !== 10000) throw new Error(res?.message || '审核失败');
}
