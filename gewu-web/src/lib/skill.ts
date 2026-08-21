// Skill 管理 API - 对接后端 SkillController
import { getAccessToken } from './token';
import { API_ENDPOINTS } from './api';

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

/** 技能 DTO（与后端 SkillDTO 一致） */
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
  version?: number;
  /** 发布状态：0=私有 1=待审核 2=已发布 3=已拒绝 */
  publishStatus?: number;
  publishStatusDesc?: string;
  createdAt?: number;
  createdBy?: string;
}

/** 创建技能命令 */
export interface CreateSkillCommand {
  skillName: string;
  description?: string;
  category?: string;
  content?: string;
  emoji?: string;
  tags?: string[];
  status?: number;
}

/** 更新技能命令（字段可空） */
export interface UpdateSkillCommand {
  skillName?: string;
  description?: string;
  category?: string;
  content?: string;
  emoji?: string;
  tags?: string[];
  status?: number;
}

// ==================== API 函数 ====================

/** 获取我的技能列表 */
export async function listMySkills(): Promise<SkillDTO[]> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILLS}`);
  return handleResponse<SkillDTO[]>(res);
}

/** 获取技能广场列表 */
export async function listSkillLibrary(): Promise<SkillDTO[]> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILL_LIBRARY}`);
  return handleResponse<SkillDTO[]>(res);
}

/** 安装技能 */
export async function installSkill(id: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILL_LIBRARY}/${id}/install`, { method: 'POST' });
  return handleResponse<void>(res);
}

/** 卸载技能 */
export async function uninstallSkill(id: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILLS}/${id}`, { method: 'DELETE' });
  return handleResponse<void>(res);
}

/** 创建技能 */
export async function createSkill(data: CreateSkillCommand): Promise<SkillDTO> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILLS}`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<SkillDTO>(res);
}

/** 更新技能 */
export async function updateSkill(skillId: string, data: UpdateSkillCommand): Promise<SkillDTO> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILLS}/${skillId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
  return handleResponse<SkillDTO>(res);
}

/** 发布技能（提交审核） */
export async function publishSkill(skillId: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILLS}/${skillId}/publish`, { method: 'POST' });
  return handleResponse<void>(res);
}

/** 审核技能（管理员） */
export async function auditSkill(skillId: string, approved: boolean, reason?: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILLS}/${skillId}/audit`, {
    method: 'POST',
    body: JSON.stringify({ approved, reason }),
  });
  return handleResponse<void>(res);
}

/** 待审核技能列表（管理员） */
export async function listPendingSkills(): Promise<SkillDTO[]> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.SKILLS}/pending`);
  return handleResponse<SkillDTO[]>(res);
}
