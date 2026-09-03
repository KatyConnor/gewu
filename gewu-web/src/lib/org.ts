// 机构管理 API - 对接后端 OrgController
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

export interface OrgDTO {
  orgId: string;
  parentId: string | null;
  orgName: string;
  orgCode: string;
  sortOrder: number;
  userCount: number;
  children: OrgDTO[];
}

// ==================== 机构 API ====================

/** 机构树（含用户数） */
export async function listOrgTree(): Promise<OrgDTO[]> {
  return unwrap(request.get<ApiResponse<OrgDTO[]>>('/v1/orgs'));
}

/** 创建机构 */
export async function createOrg(data: {
  parentId?: string;
  orgName: string;
  orgCode: string;
  sortOrder?: number;
}): Promise<OrgDTO> {
  return unwrap(request.post<ApiResponse<OrgDTO>>('/v1/orgs', data));
}

/** 更新机构 */
export async function updateOrg(orgId: string, data: {
  parentId?: string;
  orgName?: string;
  orgCode?: string;
  sortOrder?: number;
}): Promise<OrgDTO> {
  return unwrap(request.put<ApiResponse<OrgDTO>>(`/v1/orgs/${orgId}`, data));
}

/** 删除机构（存在子机构或用户时无法删除） */
export async function deleteOrg(orgId: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`/v1/orgs/${orgId}`));
}
