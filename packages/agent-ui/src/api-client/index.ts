/**
 * @gewu/agent-ui API 客户端（D0-9）。
 *
 * 自 gewu-web/src/lib/chat.ts 抽离（基线 beee59a）：consumeChatSse 解析器
 * （残包保留 / 空闲看门狗 / clientId 幂等 / 子代理分支路由 / content_reset 分发）
 * 与传输层解耦——web（网关/Next 代理）与桌面（本地引擎 127.0.0.1 / 平台网关）
 * 通过注入 {@link ChatTransport} 复用同一实现。
 */

import {
  type ApiEnvelope,
  type ChatRequest,
  type ChatResponse,
  type ChatStreamCallbacks,
  type ChatStreamEvent,
  type ModelInfo,
  RESULT_OK,
  generateClientId,
  planPathOf,
} from '../protocol/index';

// ==================== 传输层抽象 ====================

/** 传输配置：双运行时（本地引擎 / 平台网关）仅以不同 transport 区分（ADR-004） */
export interface ChatTransport {
  /** 普通请求基址（web: '/api'；桌面单机: 'http://127.0.0.1:{port}/api/v1' + 路径差异见 paths） */
  baseUrl: string;
  /** SSE 流式直连基址（缺省同 baseUrl；web 因代理缓冲需直连后端） */
  streamBaseUrl?: string;
  /** 访问令牌提供者（桌面单机返回临时引擎令牌；平台形态返回 JWT；null=匿名） */
  getAccessToken?: () => string | null | undefined;
  /** 额外请求头（桌面单机: X-Engine-Token 等） */
  extraHeaders?: Record<string, string>;
  /** 可注入 fetch（测试/IPC 桥接），缺省全局 fetch */
  fetchImpl?: typeof fetch;
}

/** 引擎 API Profile 路径（与平台 REST 同构，docs/design/42 §10.1） */
export const CHAT_PATHS = {
  chat: '/v1/ai/chat',
  stream: '/v1/ai/chat/stream',
  models: '/v1/ai/models',
  regenerate: (sessionId: string, messageId: string) =>
    `/v1/ai/sessions/${sessionId}/messages/${messageId}/regenerate`,
} as const;

export function doFetch(transport: ChatTransport, url: string, init: RequestInit): Promise<Response> {
  const f = transport.fetchImpl ?? fetch;
  return f(url, init);
}

export function authHeaders(transport: ChatTransport, extra?: Record<string, string>): Record<string, string> {
  const token = transport.getAccessToken?.();
  return {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(transport.extraHeaders ?? {}),
    ...(extra ?? {}),
  };
}

/** 从非 2xx 响应中提取后端 JSON 信封的 message（失败时回退默认文案） */
export async function extractErrorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = await res.json();
    if (body?.message) return body.message;
  } catch {
    /* 非 JSON 响应保持默认文案 */
  }
  return fallback;
}

// ==================== 同步 / 模型端点 ====================

/** 同步聊天（POST {chat}） */
export async function postChat(transport: ChatTransport, data: ChatRequest): Promise<ChatResponse> {
  const res = await doFetch(transport, `${transport.baseUrl}${CHAT_PATHS.chat}`, {
    method: 'POST',
    headers: authHeaders(transport, { 'Content-Type': 'application/json' }),
    body: JSON.stringify({ clientId: generateClientId(), ...data }),
  });
  if (!res.ok) {
    throw new Error(await extractErrorMessage(res, `聊天请求失败: ${res.status}`));
  }
  const json: ApiEnvelope<ChatResponse> = await res.json();
  if (!json || json.code !== RESULT_OK) {
    throw new Error(json?.message || '聊天失败');
  }
  return json.data;
}

/** 获取可用模型列表（GET {models}） */
export async function fetchModels(transport: ChatTransport): Promise<ModelInfo[]> {
  const res = await doFetch(transport, `${transport.baseUrl}${CHAT_PATHS.models}`, {
    headers: authHeaders(transport),
  });
  if (!res.ok) {
    throw new Error(`获取模型列表失败: ${res.status}`);
  }
  const json: ApiEnvelope<ModelInfo[]> = await res.json();
  if (!json || json.code !== RESULT_OK) {
    throw new Error(json?.message || '获取模型列表失败');
  }
  return json.data;
}

// ==================== 流式端点 ====================

/** 流式聊天（POST {stream}，SSE 直连） */
export async function streamChat(
  transport: ChatTransport,
  data: ChatRequest,
  callbacks: ChatStreamCallbacks
): Promise<void> {
  const base = transport.streamBaseUrl ?? transport.baseUrl;
  const res = await doFetch(transport, `${base}${CHAT_PATHS.stream}`, {
    method: 'POST',
    headers: authHeaders(transport, { 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
    body: JSON.stringify({ clientId: generateClientId(), ...data }),
  });
  if (!res.ok) {
    throw new Error(await extractErrorMessage(res, `流式聊天请求失败: ${res.status}`));
  }
  return consumeChatSse(res, callbacks);
}

/** 消息重新生成（T3.3 后端契约：删除目标 AI 消息前最近一条 user 消息起的历史后重跑） */
export async function regenerateStream(
  transport: ChatTransport,
  sessionId: string,
  messageId: string,
  callbacks: ChatStreamCallbacks
): Promise<void> {
  const base = transport.streamBaseUrl ?? transport.baseUrl;
  const res = await doFetch(transport, `${base}${CHAT_PATHS.regenerate(sessionId, messageId)}`, {
    method: 'POST',
    headers: authHeaders(transport, { Accept: 'text/event-stream' }),
  });
  if (!res.ok) {
    throw new Error(await extractErrorMessage(res, `重新生成请求失败: ${res.status}`));
  }
  return consumeChatSse(res, callbacks);
}

// ==================== SSE 消费器（自 chat.ts 逐行抽离，行为保持一致） ====================

/** 读取并消费 SSE 响应流（streamChat / regenerateStream 共用实现） */
export async function consumeChatSse(
  res: Response,
  callbacks: ChatStreamCallbacks
): Promise<void> {
  const reader = res.body?.getReader();
  if (!reader) {
    throw new Error('无法读取响应流');
  }

  const decoder = new TextDecoder();
  let buffer = '';
  let completed = false;

  // 空闲看门狗（S9）：服务端有心跳（20s ping），超过 60s 无任何数据 = 连接真断，
  // 主动报错而非无限等待。每个数据块到达即重置。
  const IDLE_TIMEOUT_MS = 60_000;
  let idleTimer: ReturnType<typeof setTimeout> | null = null;
  const clearIdleTimer = () => {
    if (idleTimer !== null) {
      clearTimeout(idleTimer);
      idleTimer = null;
    }
  };
  const resetIdleTimer = () => {
    clearIdleTimer();
    idleTimer = setTimeout(() => {
      safeError('连接空闲超时：超过 60 秒未收到任何数据，请检查网络后重试');
      try {
        reader.cancel();
      } catch {
        /* 忽略 */
      }
    }, IDLE_TIMEOUT_MS);
  };

  // 幂等的完成回调，确保 onComplete 只被调用一次（携带 done 事件的 finishReason）
  const safeComplete = (finishReason?: string) => {
    if (completed) return;
    completed = true;
    clearIdleTimer();
    callbacks.onComplete?.(finishReason);
  };

  // 幂等的错误回调，确保 onError 后不再触发 onComplete
  const safeError = (msg: string) => {
    if (completed) return;
    completed = true;
    clearIdleTimer();
    callbacks.onError?.(msg);
  };

  try {
    resetIdleTimer();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      resetIdleTimer();

      buffer += decoder.decode(value, { stream: true });

      // 解析 SSE 事件（以 data: 开头的行）
      const lines = buffer.split('\n');
      buffer = lines.pop() || ''; // 保留最后不完整的行

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const dataStr = line.substring(5).trim();
          if (!dataStr || dataStr === '[DONE]') {
            safeComplete();
            return;
          }

          try {
            const event: ChatStreamEvent = JSON.parse(dataStr);
            // 二期：子代理分支事件（带 subagentId 标签）路由给子流消费方——
            // 不进主时间线，也不得触发主循环的 done/error 终止逻辑（分支 done/error 均带标签）
            const evMeta = event.metadata as unknown as Record<string, unknown> | undefined;
            if (
              evMeta &&
              typeof evMeta.subagentId === 'string' &&
              evMeta.subagentId &&
              event.type !== 'subagent_status'
            ) {
              callbacks.onSubagentEvent?.(evMeta.subagentId, event);
              continue;
            }
            // done 事件触发完成并终止读取（透传 finishReason 供截断提示；
            // done 携带最终计划快照时先回调 onPlan 再完成）
            if (event.type === 'done') {
              if (event.plan && callbacks.onPlan) {
                callbacks.onPlan(event.planTitle || '', event.plan, planPathOf(event));
              }
              // 执行统计先于完成回调（完成透明度：自然收尾展示轮次/耗时）
              if (event.metadata && callbacks.onExecutionStats) {
                callbacks.onExecutionStats(event.metadata);
              }
              safeComplete(event.finishReason);
              return;
            }
            // error 事件触发错误回调
            if (event.type === 'error') {
              safeError(event.errorMessage || 'AI 处理失败');
              return;
            }
            handleStreamEvent(event, callbacks);
          } catch {
            // 非 JSON 格式，忽略
          }
        }
      }
    }

    // 处理缓冲区剩余数据
    if (buffer.startsWith('data:')) {
      const dataStr = buffer.substring(5).trim();
      if (dataStr && dataStr !== '[DONE]') {
        try {
          const event: ChatStreamEvent = JSON.parse(dataStr);
          // 二期：分支事件路由（同主循环；缓冲区尾部残余数据）
          const evMeta = event.metadata as unknown as Record<string, unknown> | undefined;
          if (
            evMeta &&
            typeof evMeta.subagentId === 'string' &&
            evMeta.subagentId &&
            event.type !== 'subagent_status'
          ) {
            callbacks.onSubagentEvent?.(evMeta.subagentId, event);
          } else if (event.type === 'done') {
            if (event.metadata && callbacks.onExecutionStats) {
              callbacks.onExecutionStats(event.metadata);
            }
            safeComplete(event.finishReason);
            return;
          } else if (event.type === 'error') {
            safeError(event.errorMessage || 'AI 处理失败');
            return;
          } else {
            handleStreamEvent(event, callbacks);
          }
        } catch {
          // 忽略
        }
      }
    }

    // 流正常结束，触发完成回调
    safeComplete();
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '流式读取异常';
    safeError(message);
  } finally {
    clearIdleTimer();
    reader.releaseLock();
  }
}

/** 处理流式事件（done/error 已在主循环中处理，此处仅处理内容/工具事件） */
export function handleStreamEvent(event: ChatStreamEvent, callbacks: ChatStreamCallbacks): void {
  switch (event.type) {
    case 'content':
      if (event.content) {
        callbacks.onContent(event.content);
      }
      break;
    case 'thinking':
      if (event.reasoning && callbacks.onThinking) {
        callbacks.onThinking(event.reasoning);
      }
      break;
    case 'status':
      if (event.content && callbacks.onStatus) {
        callbacks.onStatus(event.content);
      }
      break;
    case 'content_reset':
      // 截断重试：清空已累积的部分正文，旧内容将被整体重新生成
      if (callbacks.onContentReset) {
        callbacks.onContentReset(event.content || '');
      }
      break;
    case 'tool_call':
      if (event.toolCall && callbacks.onToolCall) {
        callbacks.onToolCall(event.toolCall);
      }
      break;
    case 'tool_executing':
      if (event.toolCall && callbacks.onToolExecuting) {
        callbacks.onToolExecuting(event.toolCall);
      }
      break;
    case 'tool_result':
      if (event.toolResult && callbacks.onToolResult) {
        callbacks.onToolResult(event.toolResult);
      }
      break;
    case 'web_search_start':
      if (callbacks.onWebSearchStart) {
        callbacks.onWebSearchStart(event.content || '正在搜索网络资源...');
      }
      break;
    case 'web_search_result':
      if (event.webSearch && callbacks.onWebSearchResult) {
        callbacks.onWebSearchResult(event.webSearch);
      }
      break;
    case 'web_verifying':
      if (callbacks.onWebVerifying) {
        callbacks.onWebVerifying(event.content || '正在验证搜索结果正确性...');
      }
      break;
    case 'web_verdict':
      if (event.verify && callbacks.onWebVerdict) {
        callbacks.onWebVerdict(event.verify);
      }
      break;
    case 'experience_saved':
      if (callbacks.onExperienceSaved) {
        callbacks.onExperienceSaved(event.content || '执行经验已沉淀至长期记忆');
      }
      break;
    case 'failure_recorded':
      if (callbacks.onFailureRecorded) {
        callbacks.onFailureRecorded(event.content || '失败已记录');
      }
      break;
    case 'plan_created':
    case 'plan_updated':
      // 任务计划（S9 F5）：模型经内置 plan_task 工具提交的任务清单
      if (event.plan && callbacks.onPlan) {
        callbacks.onPlan(event.planTitle || '', event.plan, planPathOf(event));
      }
      break;
    case 'ask_user':
      // HITL 问答（ask_user）：流在此挂起等待用户回答，回答经回答端点恢复执行
      if (callbacks.onAskUser) {
        const meta = (event.metadata || {}) as unknown as Record<string, unknown>;
        callbacks.onAskUser({
          askId: String(meta.askId || ''),
          question: String(meta.question || ''),
          options: Array.isArray(meta.options) ? (meta.options as string[]) : [],
        });
      }
      break;
    case 'approval_required':
      // 写授权弹窗（D1-21）：流挂起等待用户决策
      if (callbacks.onApprovalRequired) {
        const meta = (event.metadata || {}) as unknown as Record<string, unknown>;
        callbacks.onApprovalRequired({
          approvalId: String(meta.approvalId || ''),
          tool: String(meta.tool || ''),
          path: String(meta.path || ''),
          before: typeof meta.before === 'string' ? meta.before : null,
          after: typeof meta.after === 'string' ? meta.after : null,
        });
      }
      break;
    case 'subagent_status':
      // 子代理生命周期（二期）：running/success/failed 行渲染在主时间线
      if (callbacks.onSubagentStatus) {
        const meta = (event.metadata || {}) as unknown as Record<string, unknown>;
        callbacks.onSubagentStatus({
          subagentId: String(meta.subagentId || ''),
          index: Number(meta.index ?? 0),
          name: String(meta.name || ''),
          status:
            meta.status === 'failed' ? 'failed' : meta.status === 'success' ? 'success' : 'running',
          result: typeof meta.result === 'string' ? meta.result : undefined,
          durationMs: Number(meta.durationMs ?? 0),
        });
      }
      break;
    case 'budget_exceeded':
      // 预算熔断（S9）：此前被静默忽略，表现为「无报错无结果地断开」
      if (callbacks.onBudgetExceeded) {
        callbacks.onBudgetExceeded(event.errorMessage || '任务预算已用尽');
      }
      break;
    case 'budget_warning':
      // 预算告警（S9 方案A）：70%/90% 阈值与时间滚动续期的非阻塞提示
      if (event.content && callbacks.onBudgetWarning) {
        callbacks.onBudgetWarning(event.content);
      }
      break;
    case 'file':
      if (event.file && callbacks.onFile) {
        callbacks.onFile(event.file);
      }
      break;
    default:
      // 未知事件类型优雅忽略（前向兼容：协议只增不改，旧消费端自然兼容新事件）
      break;
  }
}

// 会话文件变更 API（S9 F3，传输注入版）
export * from './sessionFileChanges';
