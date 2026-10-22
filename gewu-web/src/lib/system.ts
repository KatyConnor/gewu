// 应用系统选项查询 — 管理端应用系统台账（app_system 表）只读数据源（设计文档 58 关联）
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const BASE = `${getBaseUrl()}/v1`;

/** 应用系统选项（管理端配置的启用系统） */
export interface AppSystemOption {
  systemId: string;
  systemCode: string;
  systemName: string;
  shortName?: string;
  systemType: string;
  ownerName?: string;
}

/** 启用系统选项列表：keyword 模糊匹配系统名称/编码/简称 */
export async function listAppSystemOptions(keyword?: string): Promise<AppSystemOption[]> {
  const token = getAccessToken();
  const qs = keyword ? `?keyword=${encodeURIComponent(keyword)}` : '';
  const res = await fetch(`${BASE}/app-system-options${qs}`, {
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!res.ok) throw new Error(`请求失败: ${res.status}`);
  const json = await res.json().catch(() => null);
  if (!json || json.code !== 10000) {
    throw new Error(json?.message || '服务响应异常，请稍后重试');
  }
  return json.data;
}
