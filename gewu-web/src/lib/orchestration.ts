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

export async function activateGraph(graphId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/graphs/${graphId}/activate`, { method: 'PUT' });
  await handleResponse(res);
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

export async function resumeExecution(executionId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/executions/${executionId}/resume`, { method: 'POST' });
  await handleResponse(res);
}

export async function cancelExecution(executionId: string): Promise<void> {
  const res = await authFetch(`${BASE}/v1/orchestration/executions/${executionId}/cancel`, { method: 'POST' });
  await handleResponse(res);
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
