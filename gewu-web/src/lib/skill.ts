// Skill 管理 API - 对接后端 SkillController
// T4.3 收敛：统一走 request.ts（axios 拦截器：token 注入/401 跳转）
import { request } from './request';
import { API_ENDPOINTS } from './api';

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
  return unwrap(request.get<ApiResponse<SkillDTO[]>>(API_ENDPOINTS.SKILLS));
}

/** 获取技能广场列表 */
export async function listSkillLibrary(): Promise<SkillDTO[]> {
  return unwrap(request.get<ApiResponse<SkillDTO[]>>(API_ENDPOINTS.SKILL_LIBRARY));
}

/** 安装技能 */
export async function installSkill(id: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`${API_ENDPOINTS.SKILL_LIBRARY}/${id}/install`));
}

/** 卸载技能 */
export async function uninstallSkill(id: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`${API_ENDPOINTS.SKILLS}/${id}`));
}

/** 创建技能 */
export async function createSkill(data: CreateSkillCommand): Promise<SkillDTO> {
  return unwrap(request.post<ApiResponse<SkillDTO>>(API_ENDPOINTS.SKILLS, data));
}

/** 更新技能 */
export async function updateSkill(skillId: string, data: UpdateSkillCommand): Promise<SkillDTO> {
  return unwrap(request.put<ApiResponse<SkillDTO>>(`${API_ENDPOINTS.SKILLS}/${skillId}`, data));
}

/** 发布技能（提交审核） */
export async function publishSkill(skillId: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`${API_ENDPOINTS.SKILLS}/${skillId}/publish`));
}

/** 审核技能（管理员） */
export async function auditSkill(skillId: string, approved: boolean, reason?: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`${API_ENDPOINTS.SKILLS}/${skillId}/audit`,
    { approved, reason }));
}

/** 待审核技能列表（管理员） */
export async function listPendingSkills(): Promise<SkillDTO[]> {
  return unwrap(request.get<ApiResponse<SkillDTO[]>>(`${API_ENDPOINTS.SKILLS}/pending`));
}
