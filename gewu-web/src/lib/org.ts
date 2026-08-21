// 机构管理 API - 对接后端 OrgController
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
  const res = await authFetch(`${BASE}/v1/orgs`);
  return handleResponse<OrgDTO[]>(res);
}

/** 创建机构 */
export async function createOrg(data: {
  parentId?: string;
  orgName: string;
  orgCode: string;
  sortOrder?: number;
}): Promise<OrgDTO> {
  const res = await authFetch(`${BASE}/v1/orgs`, { method: 'POST', body: JSON.stringify(data) });
  return handleResponse<OrgDTO>(res);
}

/** 更新机构 */
export async function updateOrg(orgId: string, data: {
  parentId?: string;
  orgName?: string;
  orgCode?: string;
  sortOrder?: number;
}): Promise<OrgDTO> {
  const res = await authFetch(`${BASE}/v1/orgs/${orgId}`, { method: 'PUT', body: JSON.stringify(data) });
  return handleResponse<OrgDTO>(res);
}

/** 删除机构（存在子机构或用户时无法删除） */
export async function deleteOrg(orgId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orgs/${orgId}`, { method: 'DELETE' });
  return handleResponse<void>(res);
}
