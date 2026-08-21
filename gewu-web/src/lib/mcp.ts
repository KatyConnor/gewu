// MCP Server 管理 API - 对接后端 McpServerController
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

export interface McpServerDTO {
  id: string;
  name: string;
  transport: string;
  command?: string;
  args?: string;
  url?: string;
  tools?: string[];
  status: number;
  description?: string;
}

/** 后端分页结果（PageResult） */
interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** 后端原始 DTO：主键字段为 serverId，暂无 tools 字段 */
interface McpServerApiDTO {
  serverId: string;
  name: string;
  description?: string;
  transport?: string;
  command?: string;
  args?: string;
  url?: string;
  status?: number;
}

/** 后端 serverId 转换为前端 id，供页面作为 key 及操作参数使用 */
function toMcpServerDTO(dto: McpServerApiDTO): McpServerDTO {
  return {
    id: dto.serverId,
    name: dto.name,
    transport: dto.transport ?? 'stdio',
    command: dto.command,
    args: dto.args,
    url: dto.url,
    status: dto.status ?? 0,
    description: dto.description,
  };
}

// ==================== API 函数 ====================

/** 获取 MCP Server 列表（后端返回分页对象，需解包 records） */
export async function listMcpServers(): Promise<McpServerDTO[]> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.MCP_SERVER}`);
  const page = await handleResponse<PageResult<McpServerApiDTO>>(res);
  return (page?.records ?? []).map(toMcpServerDTO);
}

/** 创建 MCP Server */
export async function createMcpServer(data: Partial<McpServerDTO>): Promise<McpServerDTO> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.MCP_SERVER}`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return toMcpServerDTO(await handleResponse<McpServerApiDTO>(res));
}

/** 删除 MCP Server */
export async function deleteMcpServer(id: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.MCP_SERVER}/${id}`, { method: 'DELETE' });
  return handleResponse<void>(res);
}

/** 激活 MCP Server（重新连接） */
export async function activateMcpServer(id: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.MCP_SERVER}/${id}/activate`, { method: 'POST' });
  return handleResponse<void>(res);
}
