// Agent 管理 API - 对接后端 AgentController
// T4.3 收敛：统一走 request.ts（axios 拦截器：token 注入/401 跳转），
// 原 authFetch/handleResponse 双轨封装已移除。
import { request } from './request';
import { API_ENDPOINTS } from './api';
import type { SkillDTO } from './skill';

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

/** 分页查询结果（与后端 PageResult 对齐，同 project.ts 范式） */
export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** Agent 数据传输对象（字段与后端 AgentDTO 一致） */
export interface AgentDTO {
  agentId: string;
  agentName: string;
  description?: string;
  modelProvider?: string;
  modelName?: string;
  modelConfig?: string;
  systemPrompt?: string;
  status: number;
  statusDesc?: string;
  version?: number;
  createdAt?: number;
  createdBy?: string;
  /** 对话次数（后端暂未提供，预留字段，页面用 || 0 兜底） */
  conversations?: number;
}

/** 创建 Agent 请求体（与后端 CreateAgentCommand 一致） */
export interface CreateAgentCommand {
  agentName: string;
  description?: string;
  modelProvider: string;
  modelName: string;
  modelConfig?: string;
  systemPrompt?: string;
  status?: number;
}

/** 广场智能体 DTO（与后端 AgentMarketDTO 一致） */
export interface AgentMarketDTO {
  marketId: string;
  agentId?: string;
  agentName: string;
  description?: string;
  modelProvider?: string;
  modelName?: string;
  modelConfig?: string;
  systemPrompt?: string;
  emoji?: string;
  category?: string;
  tags?: string[];
  stars?: number;
  installCount?: number;
  author?: string;
  status?: number;
  version?: number;
  createdAt?: number;
  createdBy?: string;
}

/** 更新 Agent 请求体（与后端 UpdateAgentCommand 一致，字段可空） */
export interface UpdateAgentCommand {
  agentName?: string;
  description?: string;
  modelProvider?: string;
  modelName?: string;
  modelConfig?: string;
  systemPrompt?: string;
  status?: number;
}

/** 发布 Agent 到广场命令 */
export interface PublishAgentCommand {
  agentId: string;
  emoji?: string;
  category?: string;
  tags?: string[];
}

// ==================== API 函数 ====================

/** 获取 Agent 列表（分页） */
export async function listAgents(page = 1, size = 20): Promise<PageResult<AgentDTO>> {
  return unwrap(request.get<ApiResponse<PageResult<AgentDTO>>>(
    `${API_ENDPOINTS.AGENTS}?page=${page}&size=${size}`));
}

/** 创建 Agent */
export async function createAgent(data: CreateAgentCommand): Promise<AgentDTO> {
  return unwrap(request.post<ApiResponse<AgentDTO>>(API_ENDPOINTS.AGENTS, data));
}

/** 获取 Agent 详情 */
export async function getAgent(agentId: string): Promise<AgentDTO> {
  return unwrap(request.get<ApiResponse<AgentDTO>>(`${API_ENDPOINTS.AGENTS}/${agentId}`));
}

/** 更新 Agent */
export async function updateAgent(agentId: string, data: UpdateAgentCommand): Promise<AgentDTO> {
  return unwrap(request.put<ApiResponse<AgentDTO>>(`${API_ENDPOINTS.AGENTS}/${agentId}`, data));
}

/** 删除 Agent */
export async function deleteAgent(id: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`${API_ENDPOINTS.AGENTS}/${id}`));
}

/** 获取广场 Agent 列表 */
export async function listMarketAgents(): Promise<AgentMarketDTO[]> {
  return unwrap(request.get<ApiResponse<AgentMarketDTO[]>>(API_ENDPOINTS.AGENT_MARKET));
}

/** 安装广场 Agent */
export async function installAgent(id: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`${API_ENDPOINTS.AGENT_MARKET}/${id}/install`));
}

/** 发布 Agent 到广场 */
export async function publishAgent(data: PublishAgentCommand): Promise<AgentMarketDTO> {
  return unwrap(request.post<ApiResponse<AgentMarketDTO>>(API_ENDPOINTS.AGENT_MARKET, data));
}

/** 获取智能体已挂载的技能列表 */
export async function listAgentSkills(agentId: string): Promise<SkillDTO[]> {
  return unwrap(request.get<ApiResponse<SkillDTO[]>>(`${API_ENDPOINTS.AGENTS}/${agentId}/skills`));
}

/** 挂载技能到智能体 */
export async function mountSkill(agentId: string, skillId: string): Promise<void> {
  return unwrap(request.post<ApiResponse<void>>(`${API_ENDPOINTS.AGENTS}/${agentId}/skills/${skillId}`));
}

/** 卸载智能体技能 */
export async function unmountSkill(agentId: string, skillId: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`${API_ENDPOINTS.AGENTS}/${agentId}/skills/${skillId}`));
}
