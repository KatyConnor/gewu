// MCP Server 管理 API - 对接后端 McpServerController
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
  const page = await unwrap(request.get<ApiResponse<PageResult<McpServerApiDTO>>>(
    API_ENDPOINTS.MCP_SERVER));
  return (page?.records ?? []).map(toMcpServerDTO);
}

/** 创建 MCP Server */
export async function createMcpServer(data: Partial<McpServerDTO>): Promise<McpServerDTO> {
  return toMcpServerDTO(await unwrap(
    request.post<ApiResponse<McpServerApiDTO>>(API_ENDPOINTS.MCP_SERVER, data)));
}

/** 删除 MCP Server */
export async function deleteMcpServer(id: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`${API_ENDPOINTS.MCP_SERVER}/${id}`));
}

/** 激活 MCP Server（重新连接） */
export async function activateMcpServer(id: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`${API_ENDPOINTS.MCP_SERVER}/${id}/activate`));
}
