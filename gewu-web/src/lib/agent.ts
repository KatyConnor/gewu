// Agent 管理 API - 对接后端 AgentController
import { getAccessToken } from './token';
import { API_ENDPOINTS } from './api';
import type { SkillDTO } from './skill';

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
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}?page=${page}&size=${size}`);
  return handleResponse<PageResult<AgentDTO>>(res);
}

/** 创建 Agent */
export async function createAgent(data: CreateAgentCommand): Promise<AgentDTO> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<AgentDTO>(res);
}

/** 获取 Agent 详情 */
export async function getAgent(agentId: string): Promise<AgentDTO> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}/${agentId}`);
  return handleResponse<AgentDTO>(res);
}

/** 更新 Agent */
export async function updateAgent(agentId: string, data: UpdateAgentCommand): Promise<AgentDTO> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}/${agentId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
  return handleResponse<AgentDTO>(res);
}

/** 删除 Agent */
export async function deleteAgent(id: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}/${id}`, { method: 'DELETE' });
  return handleResponse<void>(res);
}

/** 获取广场 Agent 列表 */
export async function listMarketAgents(): Promise<AgentMarketDTO[]> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENT_MARKET}`);
  return handleResponse<AgentMarketDTO[]>(res);
}

/** 安装广场 Agent */
export async function installAgent(id: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENT_MARKET}/${id}/install`, { method: 'POST' });
  return handleResponse<void>(res);
}

/** 发布 Agent 到广场 */
export async function publishAgent(data: PublishAgentCommand): Promise<AgentMarketDTO> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENT_MARKET}`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return handleResponse<AgentMarketDTO>(res);
}

/** 获取智能体已挂载的技能列表 */
export async function listAgentSkills(agentId: string): Promise<SkillDTO[]> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}/${agentId}/skills`);
  return handleResponse<SkillDTO[]>(res);
}

/** 挂载技能到智能体 */
export async function mountSkill(agentId: string, skillId: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}/${agentId}/skills/${skillId}`, { method: 'POST' });
  return handleResponse<void>(res);
}

/** 卸载智能体技能 */
export async function unmountSkill(agentId: string, skillId: string): Promise<void> {
  const res = await authFetch(`${BASE}${API_ENDPOINTS.AGENTS}/${agentId}/skills/${skillId}`, { method: 'DELETE' });
  return handleResponse<void>(res);
}
