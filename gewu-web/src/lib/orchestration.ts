// 编排 API - 对接后端 OrchestrationController
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

/** 编排图定义 */
export interface OrchestrationGraphEntity {
  id: string;
  graphName: string;
  graphDefinition?: string;
  graphType?: string;
  orchestrationMode?: string;
  version?: string;
  /** draft/active */
  status?: string;
  createdAt?: number;
  createdBy?: string;
}

/** 编排执行实例 */
export interface OrchestrationExecutionEntity {
  id: string;
  graphId: string;
  status: string;
  versionId?: string;
  /** MANUAL/API/AGENT_TOOL/SCHEDULE/WEBHOOK */
  triggerType?: string;
  currentNodeId?: string;
  variables?: string;
  finalOutput?: string;
  errorMessage?: string;
  iterationCount?: number;
  tokenUsed?: number;
  startedAt?: number;
  completedAt?: number;
  createdAt?: number;
}

/** 编排图版本快照（WFO-01，不可变） */
export interface OrchestrationGraphVersionEntity {
  id: string;
  graphId: string;
  version: number;
  graphDefinition: string;
  orchestrationMode?: string;
  activatedBy?: string;
  activatedAt?: number;
  createdAt?: number;
}

// ==================== 编排图 API ====================

export async function listGraphs(status?: string): Promise<OrchestrationGraphEntity[]> {
  const param = status ? `?status=${encodeURIComponent(status)}` : '';
  const res = await authFetch(`${BASE}/v1/orchestration/graphs${param}`);
  return handleResponse(res);
}

export async function getGraph(graphId: string): Promise<OrchestrationGraphEntity> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}`);
  return handleResponse(res);
}

export async function createGraph(command: {
  name: string;
  graphDefinition?: string;
  graphType?: string;
  mode?: string;
}): Promise<OrchestrationGraphEntity> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs`, {
    method: 'POST',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

/** 角色目录项（设计器 AGENT 节点 roleCode 下拉数据源） */
export interface RoleOption {
  roleCode: string;
  roleName: string;
  sdlcPhase: string;
}

/** 工具目录项：source=CODE 代码级工具 / CONFIG 配置工具 */
export interface ToolOption {
  name: string;
  source: string;
  description?: string;
  toolType?: string;
}

/** 节点级执行记录（FR-14 回放数据源） */
export interface OrchestrationNodeExecution {
  id?: string;
  executionId?: string;
  nodeId: string;
  nodeType?: string;
  roleCode?: string;
  status: string;
  durationMs?: number;
  /** 成功前的额外尝试次数（WFO-05） */
  retryCount?: number;
  errorMessage?: string;
  startedAt?: number;
  completedAt?: number;
  createdAt?: number;
}

/**
 * 更新编排图定义（仅草稿可编辑，后端保存前执行图结构校验）。
 */
export async function updateGraph(graphId: string, command: {
  name?: string;
  graphDefinition: string;
  graphType?: string;
  mode?: string;
}): Promise<OrchestrationGraphEntity> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}`, {
    method: 'PUT',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

/** 角色目录（RoleRegistry 内置 12 SDLC 角色 + SPI 扩展） */
export async function listRoleCatalog(): Promise<RoleOption[]> {
  const res = await authFetch(`${BASE}/v1/orchestration/catalog/roles`);
  return handleResponse(res);
}

/** 工具目录（代码工具 + agent_tool 配置工具合并） */
export async function listToolCatalog(): Promise<ToolOption[]> {
  const res = await authFetch(`${BASE}/v1/orchestration/catalog/tools`);
  return handleResponse(res);
}

/** 查询执行的节点级记录（FR-14 回放数据源，按时间升序） */
export async function listNodeExecutions(executionId: string): Promise<OrchestrationNodeExecution[]> {
  const res = await authFetch(`${BASE}/v1/orchestration/executions/${executionId}/nodes`);
  return handleResponse(res);
}

export async function activateGraph(graphId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/activate`, { method: 'PUT' });
  await handleResponse(res);
}

/** 下架编排图（active -> draft，可重新编辑；执行与审批数据保留，WFO-02） */
export async function deactivateGraph(graphId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/deactivate`, { method: 'PUT' });
  await handleResponse(res);
}

/** 查询编排图版本快照列表（按版本号倒序，WFO-02） */
export async function listGraphVersions(graphId: string): Promise<OrchestrationGraphVersionEntity[]> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/versions`);
  return handleResponse(res);
}

/** 回滚到历史版本：将版本快照写回草稿定义（仅 draft 可回滚，WFO-02） */
export async function rollbackGraphVersion(graphId: string, versionId: string): Promise<OrchestrationGraphEntity> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/versions/${versionId}/rollback`, {
    method: 'POST',
  });
  return handleResponse(res);
}

/** 定时触发配置（WFC-02） */
export interface OrchestrationScheduleConfig {
  id?: string;
  graphId: string;
  cronExpr: string;
  timezone?: string;
  inputTemplate?: string;
  enabled?: number;
  lastFireAt?: number;
  nextFireAt?: number | null;
}

/** Webhook 触发配置（WFC-03，token 明文仅生成时返回一次） */
export interface OrchestrationWebhookConfig {
  id: string;
  graphId: string;
  enabled?: number;
  tokenHash?: string;
  createdAt?: number;
}

/** Webhook 凭证（regenerate/首次创建时携带明文 token） */
export interface WebhookCredential {
  webhookId: string;
  graphId: string;
  enabled: boolean;
  token?: string | null;
}

/** 保存定时触发配置（Cron 为 Spring 6 位语法，保存即校验并预计算下次触发时间） */
export async function upsertSchedule(graphId: string, command: {
  cronExpr: string;
  timezone?: string;
  inputTemplate?: string;
  enabled?: boolean;
}): Promise<OrchestrationScheduleConfig> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/schedule`, {
    method: 'PUT',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

/** 查询定时触发配置（未配置返回 null） */
export async function getSchedule(graphId: string): Promise<OrchestrationScheduleConfig | null> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/schedule`);
  return handleResponse(res);
}

/** 保存 Webhook 配置（regenerate=true 生成新 token，明文仅本次返回） */
export async function upsertWebhook(graphId: string, command: {
  enabled?: boolean;
  regenerate?: boolean;
}): Promise<WebhookCredential> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/webhook`, {
    method: 'PUT',
    body: JSON.stringify(command),
  });
  return handleResponse(res);
}

/** 查询 Webhook 配置（只含哈希不含明文；未配置返回 null） */
export async function getWebhook(graphId: string): Promise<OrchestrationWebhookConfig | null> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/webhook`);
  return handleResponse(res);
}

export async function deleteGraph(graphId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}`, { method: 'DELETE' });
  await handleResponse(res);
}

// ==================== 执行 API ====================

export async function executeGraph(graphId: string, input?: string): Promise<OrchestrationExecutionEntity> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/execute`, {
    method: 'POST',
    body: JSON.stringify({ input: input || '' }),
  });
  return handleResponse(res);
}

export async function listExecutions(
  graphId?: string,
  status?: string
): Promise<OrchestrationExecutionEntity[]> {
  const params = new URLSearchParams();
  if (graphId) params.set('graphId', graphId);
  if (status) params.set('status', status);
  const qs = params.toString();
  const res = await authFetch(`${BASE}/v1/orchestration/executions${qs ? `?${qs}` : ''}`);
  return handleResponse(res);
}

export async function getExecution(executionId: string): Promise<OrchestrationExecutionEntity> {
  const res = await authFetch(`${BASE}/v1/orchestration/executions/${executionId}`);
  return handleResponse(res);
}

export async function pauseExecution(executionId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/executions/${executionId}/pause`, { method: 'POST' });
  await handleResponse(res);
}

/**
 * 恢复执行（两步操作第一步，WFO-09）：DB 状态置 RUNNING 并返回 resumable；
 * resumable=true 时调用 resumeGraphStream 订阅断点续跑事件流。
 */
export async function resumeExecution(executionId: string): Promise<boolean> {
  const res = await authFetch(`${BASE}/v1/orchestration/executions/${executionId}/resume`, { method: 'POST' });
  return handleResponse<boolean>(res);
}

export async function cancelExecution(executionId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/executions/${executionId}/cancel`, { method: 'POST' });
  await handleResponse(res);
}

/**
 * 断点续跑事件流（SSE，WFO-09）：resumable=true 时调用，
 * 从引擎检查点恢复执行（跳过已完成节点），事件结构同 executeGraphStream。
 * 返回 AbortController 供调用方中断流。
 */
export function resumeGraphStream(
  executionId: string,
  onEvent: (event: { type: string; content?: string; reasoning?: string; nodeId?: string; errorMessage?: string; metadata?: Record<string, unknown> }) => void,
  onDone: () => void,
  onError: (err: Error) => void
): AbortController {
  const controller = new AbortController();
  const token = getAccessToken();
  fetch(`${BASE}/v1/orchestration/executions/${executionId}/resume/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    signal: controller.signal,
  })
    .then(async res => {
      if (!res.ok || !res.body) throw new Error(`续跑流请求失败: ${res.status}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const blocks = buffer.split('\n\n');
        buffer = blocks.pop() || '';
        for (const block of blocks) {
          const dataLine = block.split('\n').find(l => l.startsWith('data:'));
          if (!dataLine) continue;
          const payload = dataLine.slice(5).trim();
          if (!payload || payload === '[DONE]') continue;
          try {
            onEvent(JSON.parse(payload));
          } catch { /* 跳过非 JSON 心跳行 */ }
        }
      }
      onDone();
    })
    .catch(err => {
      if ((err as Error).name !== 'AbortError') onError(err instanceof Error ? err : new Error(String(err)));
    });
  return controller;
}

/**
 * 流式执行编排图（SSE）：逐事件回调。
 * 返回 AbortController 供调用方中断流。
 */
export function executeGraphStream(
  graphId: string,
  input: string,
  onEvent: (event: { type: string; content?: string; reasoning?: string; nodeId?: string; errorMessage?: string; metadata?: Record<string, unknown> }) => void,
  onDone: () => void,
  onError: (err: Error) => void
): AbortController {
  const controller = new AbortController();
  const token = getAccessToken();
  fetch(`${BASE}/v1/orchestration/graphs/${graphId}/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ input }),
    signal: controller.signal,
  })
    .then(async res => {
      if (!res.ok || !res.body) throw new Error(`流式请求失败: ${res.status}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        // SSE 事件以空行分隔
        const blocks = buffer.split('\n\n');
        buffer = blocks.pop() || '';
        for (const block of blocks) {
          const dataLine = block.split('\n').find(l => l.startsWith('data:'));
          if (!dataLine) continue;
          const payload = dataLine.slice(5).trim();
          if (!payload || payload === '[DONE]') continue;
          try {
            onEvent(JSON.parse(payload));
          } catch { /* 跳过非 JSON 心跳行 */ }
        }
      }
      onDone();
    })
    .catch(err => {
      if ((err as Error).name !== 'AbortError') onError(err instanceof Error ? err : new Error(String(err)));
    });
  return controller;
}
