// 入口调度文件 API — 对接后端 EntryFileController（设计文档 56 §6.2）
import { getAccessToken } from './token';

function getBaseUrl(): string {
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  return 'http://localhost:8081/api';
}

const BASE = `${getBaseUrl()}/v1`;

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
  const json = await res.json().catch(() => null);
  if (!json || json.code !== 10000) {
    throw new Error(json?.message || '服务响应异常，请稍后重试');
  }
  return json.data;
}

export type EntryFileName = 'GEWU.md' | 'AGENTS.md';

/** 裁决视图（source=project:AGENTS.md@1.3.0 等；content=null 表示无入口文件） */
export interface EntryView {
  present: boolean;
  source: string;
  content: string | null;
  meta: string;
  upgradeHint?: string | null;
}

/** 读取全局入口文件（MinIO 权威源） */
export async function readGlobalEntryFile(name: EntryFileName): Promise<{ exists: boolean; content: string }> {
  const res = await authFetch(`${BASE}/me/entry-file?name=${encodeURIComponent(name)}`);
  return handleResponse(res);
}

/** 保存全局入口文件（MinIO 直写 + workspace_file 建行 + M2V 入队） */
export async function saveGlobalEntryFile(name: EntryFileName, content: string): Promise<void> {
  const res = await authFetch(`${BASE}/me/entry-file`, {
    method: 'PUT',
    body: JSON.stringify({ name, content }),
  });
  await handleResponse(res);
}

/** 全局层裁决预览（设置页） */
export async function resolveGlobalEntry(): Promise<EntryView> {
  const res = await authFetch(`${BASE}/me/entry-file/resolve`);
  return handleResponse(res);
}

/** 项目入口裁决视图（含升级提示） */
export async function resolveProjectEntry(projectId: string): Promise<EntryView> {
  const res = await authFetch(`${BASE}/projects/${encodeURIComponent(projectId)}/entry-file`);
  return handleResponse(res);
}

/** 读取项目入口文件（沙箱卷内仓库根） */
export async function readProjectEntryFile(
  projectId: string, name: EntryFileName,
): Promise<{ exists: boolean; content: string; path: string }> {
  const res = await authFetch(
    `${BASE}/projects/${encodeURIComponent(projectId)}/entry-file/file?name=${encodeURIComponent(name)}`);
  return handleResponse(res);
}

/** 初始化项目入口文件（平台模板；仅 clone 成功后可调） */
export async function initProjectEntryFile(
  projectId: string, name: EntryFileName, content?: string,
): Promise<void> {
  const res = await authFetch(`${BASE}/projects/${encodeURIComponent(projectId)}/entry-file/init`, {
    method: 'POST',
    body: JSON.stringify({ name, content }),
  });
  await handleResponse(res);
}
