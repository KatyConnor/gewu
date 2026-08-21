// 工作流 API - 对接后端 WorkflowController / WorkflowInstanceController
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

/** 工作流定义 */
export interface WorkflowDTO {
  workflowId: string;
  workflowName: string;
  description?: string;
  version?: number;
  /** 0=草稿 1=已发布 2=已归档 */
  status: number;
  statusDesc?: string;
  category?: string;
  config?: string;
  publishedAt?: number;
  createdAt?: number;
  createdBy?: string;
  nodeCount?: number;
}

/** 工作流实例 */
export interface WorkflowInstanceDTO {
  instanceId: string;
  workflowId: string;
  workflowVersion?: number;
  title?: string;
  status: string;
  statusDesc?: string;
  initiatorId?: string;
  initiatorName?: string;
  currentNodeId?: string;
  currentNodeName?: string;
  variables?: string;
  startedAt?: number;
  completedAt?: number;
  createdAt?: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
}

export interface WorkflowNodeDTO {
  nodeId: string;
  workflowId: string;
  nodeName: string;
  nodeType?: string;
  nodeConfig?: string;
  sortOrder?: number;
}

// ==================== 工作流定义 API ====================

export async function listWorkflows(page = 1, size = 50): Promise<PageResult<WorkflowDTO>> {
  const res = await authFetch(`${BASE}/v1/workflows?page=${page}&size=${size}`);
  return handleResponse(res);
}

export async function getWorkflow(workflowId: string): Promise<WorkflowDTO> {
  const res = await authFetch(`${BASE}/v1/workflows/${workflowId}`);
  return handleResponse(res);
}

export async function createWorkflow(command: {
  workflowName: string;
  description?: string;
  category?: string;
  config?: string;
}): Promise<WorkflowDTO> {
  const res = await authFetch(`${BASE}/v1/workflows`, {
    method: 'POST',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

export async function updateWorkflow(
  workflowId: string,
  command: { workflowName?: string; description?: string; category?: string; config?: string }
): Promise<WorkflowDTO> {
  const res = await authFetch(`${BASE}/v1/workflows/${workflowId}`, {
    method: 'PUT',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

export async function deleteWorkflow(workflowId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/workflows/${workflowId}`, { method: 'DELETE' });
  await handleResponse(res);
}

export async function publishWorkflow(workflowId: string): Promise<WorkflowDTO> {
  const res = await authFetch(`${BASE}/v1/workflows/${workflowId}/publish`, { method: 'POST' });
  return handleResponse(res);
}

export async function archiveWorkflow(workflowId: string): Promise<WorkflowDTO> {
  const res = await authFetch(`${BASE}/v1/workflows/${workflowId}/archive`, { method: 'POST' });
  return handleResponse(res);
}

export async function getWorkflowNodes(workflowId: string): Promise<WorkflowNodeDTO[]> {
  const res = await authFetch(`${BASE}/v1/workflows/${workflowId}/nodes`);
  return handleResponse(res);
}

// ==================== 工作流实例 API ====================

export async function startInstance(
  workflowId: string,
  command: { title?: string; variables?: string }
): Promise<WorkflowInstanceDTO> {
  const res = await authFetch(`${BASE}/v1/workflows/instances/${workflowId}/start`, {
    method: 'POST',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

export async function listMyInstances(page = 1, size = 20): Promise<PageResult<WorkflowInstanceDTO>> {
  const res = await authFetch(`${BASE}/v1/workflows/instances/my?page=${page}&size=${size}`);
  return handleResponse(res);
}

export async function listInstances(
  workflowId?: string,
  page = 1,
  size = 20
): Promise<PageResult<WorkflowInstanceDTO>> {
  const wfParam = workflowId ? `&workflowId=${encodeURIComponent(workflowId)}` : '';
  const res = await authFetch(`${BASE}/v1/workflows/instances?page=${page}&size=${size}${wfParam}`);
  return handleResponse(res);
}

export async function completeInstanceNode(
  instanceId: string,
  command: { comment?: string; variables?: string; approved?: boolean }
): Promise<void> {
  const res = await authFetch(`${BASE}/v1/workflows/instances/${instanceId}/complete`, {
    method: 'PUT',
    body: JSON.stringify(command),
  });
  await handleResponse(res);
}

export async function suspendInstance(instanceId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/workflows/instances/${instanceId}/suspend`, { method: 'PUT' });
  await handleResponse(res);
}

export async function resumeInstance(instanceId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/workflows/instances/${instanceId}/resume`, { method: 'PUT' });
  await handleResponse(res);
}

export async function terminateInstance(instanceId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/workflows/instances/${instanceId}/terminate`, { method: 'PUT' });
  await handleResponse(res);
}
