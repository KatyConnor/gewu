// AI 聊天服务 — 对接后端 AiChatController
import { API_ENDPOINTS, API_CONFIG } from './api';
import { getAccessToken } from './token';

/**
 * 获取 SSE 流式请求的基础 URL。
 * 流式请求必须直连后端，不能经过 Next.js 代理（代理会缓冲响应）。
 */
function getStreamBaseUrl(): string {
  // 优先使用环境变量配置的直连地址
  if (typeof window !== 'undefined' && process.env.NEXT_PUBLIC_API_BASE) {
    return process.env.NEXT_PUBLIC_API_BASE;
  }
  // 开发环境默认使用本地后端
  return 'http://localhost:8081/api';
}

// 后端统一响应结构
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

// 聊天请求
export interface ChatRequest {
  message: string;
  agentId?: string;
  sessionId?: string;
  model?: string;
  /** Agent 模式: assistant/expert/creative/precise */
  agentMode?: string;
  /** 思维模式: chain-of-thought/tree-of-thought/react/step-by-step/socratic */
  thinkingStyle?: string;
}

// 聊天响应
export interface ChatResponse {
  messageId: string;
  content: string;
  finishReason: string;
  toolCalls?: Array<{
    id: string;
    name: string;
    arguments: string;
  }>;
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
}

// 网络搜索结果条目（与后端 ChatStreamEvent.SearchItemInfo 同构）
export interface SearchItem {
  url: string;
  title: string;
  snippet: string;
  source: string;
  adopted: boolean;
  discarded: boolean;
  discardReason?: string;
  confidence: number;
}

// 网络搜索综合信息
export interface WebSearchInfo {
  query: string;
  results: SearchItem[];
  adoptedCount: number;
  discardedCount: number;
}

// 正确性判断结论
export interface VerifyInfo {
  method: string;
  query: string;
  totalResults: number;
  adoptedCount: number;
  discardedCount: number;
}

// 文件卡片信息
export interface FileInfo {
  fileName: string;
  fileType: string;
  mimeType: string;
  fileSize: number;
  downloadUrl: string;
  previewContent: string;
  source: string;
}

// 流式事件
export interface ChatStreamEvent {
  type: string;
  content?: string;
  reasoning?: string;
  toolCall?: {
    id: string;
    name: string;
    arguments: string;
  };
  toolResult?: {
    toolCallId: string;
    output: string;
  };
  webSearch?: WebSearchInfo;
  verify?: VerifyInfo;
  file?: FileInfo;
  errorMessage?: string;
}

// 模型信息
export interface ModelInfo {
  provider: string;
  name: string;
  modelName: string;
  displayName: string;
  description: string;
  supported: boolean;
}

/**
 * 同步聊天
 */
export async function chat(data: ChatRequest): Promise<ChatResponse> {
  const token = getAccessToken();
  const res = await fetch(`/api${API_ENDPOINTS.CHAT}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  });

  if (!res.ok) {
    throw new Error(`聊天请求失败: ${res.status}`);
  }

  const json: ApiResponse<ChatResponse> = await res.json();
  if (!json || json.code !== 10000) {
    throw new Error(json?.message || '聊天失败');
  }
  return json.data;
}

/**
 * 流式聊天 — 使用 fetch + ReadableStream 接收 SSE 流
 * @param data 聊天请求
 * @param callbacks 回调函数
 */
export async function chatStream(
  data: ChatRequest,
  callbacks: {
    onContent: (text: string) => void;
    onThinking?: (text: string) => void;
    onStatus?: (status: string) => void;
    onToolCall?: (toolCall: { id: string; name: string; arguments: string }) => void;
    onToolExecuting?: (toolCall: { id: string; name: string; arguments: string }) => void;
    onToolResult?: (result: { toolCallId: string; output: string }) => void;
    // 网络搜索推理增强回调
    onWebSearchStart?: (status: string) => void;
    onWebSearchResult?: (info: WebSearchInfo) => void;
    onWebVerifying?: (status: string) => void;
    onWebVerdict?: (verdict: VerifyInfo) => void;
    onFile?: (file: FileInfo) => void;
    onError?: (error: string) => void;
    onComplete?: () => void;
  }
): Promise<void> {
  const token = getAccessToken();
  // 流式请求直连后端，避免 Next.js 代理缓冲响应
  const streamBaseUrl = getStreamBaseUrl();
  const res = await fetch(`${streamBaseUrl}${API_ENDPOINTS.CHAT_STREAM}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  });

  if (!res.ok) {
    throw new Error(`流式聊天请求失败: ${res.status}`);
  }

  const reader = res.body?.getReader();
  if (!reader) {
    throw new Error('无法读取响应流');
  }

  const decoder = new TextDecoder();
  let buffer = '';
  let completed = false;

  // 幂等的完成回调，确保 onComplete 只被调用一次
  const safeComplete = () => {
    if (completed) return;
    completed = true;
    callbacks.onComplete?.();
  };

  // 幂等的错误回调，确保 onError 后不再触发 onComplete
  const safeError = (msg: string) => {
    if (completed) return;
    completed = true;
    callbacks.onError?.(msg);
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

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
            // done 事件触发完成并终止读取
            if (event.type === 'done') {
              safeComplete();
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
          if (event.type === 'done') {
            safeComplete();
            return;
          }
          if (event.type === 'error') {
            safeError(event.errorMessage || 'AI 处理失败');
            return;
          }
          handleStreamEvent(event, callbacks);
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
    reader.releaseLock();
  }
}

/**
 * 处理流式事件（done/error 已在主循环中处理，此处仅处理内容/工具事件）
 */
function handleStreamEvent(
  event: ChatStreamEvent,
  callbacks: {
    onContent: (text: string) => void;
    onThinking?: (text: string) => void;
    onStatus?: (status: string) => void;
    onToolCall?: (toolCall: { id: string; name: string; arguments: string }) => void;
    onToolExecuting?: (toolCall: { id: string; name: string; arguments: string }) => void;
    onToolResult?: (result: { toolCallId: string; output: string }) => void;
    onWebSearchStart?: (status: string) => void;
    onWebSearchResult?: (info: WebSearchInfo) => void;
    onWebVerifying?: (status: string) => void;
    onWebVerdict?: (verdict: VerifyInfo) => void;
    onFile?: (file: FileInfo) => void;
    onError?: (error: string) => void;
    onComplete?: () => void;
  }
): void {
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
    case 'file':
      if (event.file && callbacks.onFile) {
        callbacks.onFile(event.file);
      }
      break;
  }
}

/**
 * 获取可用模型列表
 */
export async function listModels(): Promise<ModelInfo[]> {
  const token = getAccessToken();
  const res = await fetch(`/api${API_ENDPOINTS.CHAT_MODELS}`, {
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });

  if (!res.ok) {
    throw new Error(`获取模型列表失败: ${res.status}`);
  }

  const json: ApiResponse<ModelInfo[]> = await res.json();
  if (!json || json.code !== 10000) {
    throw new Error(json?.message || '获取模型列表失败');
  }
  return json.data;
}