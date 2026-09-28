// 工作流 API - 对接后端 WorkflowController / WorkflowInstanceController
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
  return unwrap(request.get<ApiResponse<PageResult<WorkflowDTO>>>(`/v1/workflows?page=${page}&size=${size}`));
}

export async function getWorkflow(workflowId: string): Promise<WorkflowDTO> {
  return unwrap(request.get<ApiResponse<WorkflowDTO>>(`/v1/workflows/${workflowId}`));
}

export async function createWorkflow(command: {
  workflowName: string;
  description?: string;
  category?: string;
  config?: string;
}): Promise<WorkflowDTO> {
  return unwrap(request.post<ApiResponse<WorkflowDTO>>('/v1/workflows', command));
}

export async function updateWorkflow(
  workflowId: string,
  command: { workflowName?: string; description?: string; category?: string; config?: string }
): Promise<WorkflowDTO> {
  return unwrap(request.put<ApiResponse<WorkflowDTO>>(`/v1/workflows/${workflowId}`, command));
}

export async function deleteWorkflow(workflowId: string): Promise<void> {
  return unwrap(request.delete<ApiResponse<void>>(`/v1/workflows/${workflowId}`));
}

export async function publishWorkflow(workflowId: string): Promise<WorkflowDTO> {
  return unwrap(request.post<ApiResponse<WorkflowDTO>>(`/v1/workflows/${workflowId}/publish`));
}

export async function archiveWorkflow(workflowId: string): Promise<WorkflowDTO> {
  return unwrap(request.post<ApiResponse<WorkflowDTO>>(`/v1/workflows/${workflowId}/archive`));
}

export async function getWorkflowNodes(workflowId: string): Promise<WorkflowNodeDTO[]> {
  return unwrap(request.get<ApiResponse<WorkflowNodeDTO[]>>(`/v1/workflows/${workflowId}/nodes`));
}

// ==================== 工作流图（设计器保存/回显，P5） ====================

/** 图节点（与后端 SaveWorkflowGraphCommand.WorkflowNodeDTO 同构） */
export interface GraphNodeDTO {
  nodeId: string;
  nodeName: string;
  nodeType: string;
  /** 节点 config（JSON 文本） */
  config?: string;
  positionX?: number;
  positionY?: number;
  sortOrder?: number;
}

/** 图流转（与后端 WorkflowTransitionDTO 同构） */
export interface GraphTransitionDTO {
  transitionId?: string;
  fromNodeId: string;
  toNodeId: string;
  conditionExpr?: string | null;
  label?: string | null;
  sortOrder?: number;
}

export interface WorkflowGraphDTO {
  nodes: GraphNodeDTO[];
  transitions: GraphTransitionDTO[];
}

/** 读取工作流图（画布回显） */
export async function getWorkflowGraph(workflowId: string): Promise<WorkflowGraphDTO> {
  return unwrap(request.get<ApiResponse<WorkflowGraphDTO>>(`/v1/workflows/${workflowId}/graph`));
}

/** 覆盖保存工作流图（节点/流转全量替换；草稿态可保存） */
export async function saveWorkflowGraph(workflowId: string, graph: WorkflowGraphDTO): Promise<void> {
  await unwrap(request.put<ApiResponse<void>>(`/v1/workflows/${workflowId}/graph`, graph));
}

// ==================== 工作流实例 API ====================

export async function startInstance(
  workflowId: string,
  command: { title?: string; variables?: string }
): Promise<WorkflowInstanceDTO> {
  return unwrap(request.post<ApiResponse<WorkflowInstanceDTO>>(
    `/v1/workflows/instances/${workflowId}/start`, command));
}

export async function listMyInstances(page = 1, size = 20): Promise<PageResult<WorkflowInstanceDTO>> {
  return unwrap(request.get<ApiResponse<PageResult<WorkflowInstanceDTO>>>(
    `/v1/workflows/instances/my?page=${page}&size=${size}`));
}

export async function listInstances(
  workflowId?: string,
  page = 1,
  size = 20
): Promise<PageResult<WorkflowInstanceDTO>> {
  const wfParam = workflowId ? `&workflowId=${encodeURIComponent(workflowId)}` : '';
  return unwrap(request.get<ApiResponse<PageResult<WorkflowInstanceDTO>>>(
    `/v1/workflows/instances?page=${page}&size=${size}${wfParam}`));
}

/**
 * 完成指定节点实例（P1 内核重构：按 nodeInstanceId 精确完成，幂等）。
 */
export async function completeInstanceNode(
  instanceId: string,
  nodeInstanceId: string,
  command: { comment?: string; variables?: string; approved?: boolean }
): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(
    `/v1/workflows/instances/${instanceId}/nodes/${nodeInstanceId}/complete`, command));
}

export async function suspendInstance(instanceId: string): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`/v1/workflows/instances/${instanceId}/suspend`));
}

export async function resumeInstance(instanceId: string): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`/v1/workflows/instances/${instanceId}/resume`));
}

export async function terminateInstance(instanceId: string): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(`/v1/workflows/instances/${instanceId}/terminate`));
}
