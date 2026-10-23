// 用户选项查询 — 新建项目"团队与角色"选择器只读数据源
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const BASE = `${getBaseUrl()}/v1`;

/** 用户选项（启用中的平台用户，不含凭证等敏感字段） */
export interface UserOption {
  userId: string;
  username: string;
  displayName: string;
  email?: string;
}

/** 启用用户选项列表：keyword 模糊匹配显示名/用户名/邮箱 */
export async function listUserOptions(keyword?: string): Promise<UserOption[]> {
  const token = getAccessToken();
  const qs = keyword ? `?keyword=${encodeURIComponent(keyword)}` : '';
  const res = await fetch(`${BASE}/user-options${qs}`, {
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`请求失败: ${res.status}`);
  const json = await res.json().catch(() => null);
  if (!json || json.code !== 10000) {
    throw new Error(json?.message || '服务响应异常，请稍后重试');
  }
  return json.data;
}
