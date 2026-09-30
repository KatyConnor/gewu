// AI 聊天服务 — web 侧传输绑定（D0-13 绞杀者迁移）
//
// 实现已抽离至共享包 @gewu/agent-ui（docs/design/42 ADR-003/004）：
//   - 协议类型与事件常量：@gewu/agent-ui/protocol
//   - SSE 消费器与端点实现：@gewu/agent-ui/api-client（consumeChatSse/看门狗/幂等/分支路由）
// 本文件仅保留 web 特有的传输绑定（基址 / 令牌 / Next 代理规避），全部导出名保持不变，
// 既有页面导入零改动。桌面端（gewu-desktop）以自己的 transport 复用同一实现。

import {
  postChat,
  streamChat,
  regenerateStream,
  fetchModels,
  type ChatTransport,
} from '@gewu/agent-ui/api-client';
import type { ChatRequest, ChatStreamCallbacks, ChatResponse, ModelInfo } from '@gewu/agent-ui/protocol';
import { getAccessToken } from './token';

// 类型与工具函数：原样从共享包再导出（保持 '@/lib/chat' 导入路径兼容）
export type {
  ChatRequest,
  ChatResponse,
  ChatStreamCallbacks,
  ChatStreamEvent,
  ExecutionStats,
  FileInfo,
  ModelInfo,
  PlanStepInfo,
  SearchItem,
  SubagentStatus,
  AskUserPayload,
  VerifyInfo,
  WebSearchInfo,
  ToolCallInfo,
  ToolResultInfo,
} from '@gewu/agent-ui/protocol';
export { generateClientId, ChatEventType, planPathOf } from '@gewu/agent-ui/protocol';

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

/** web 传输绑定：普通请求走 Next 代理（/api），流式直连后端 */
const transport: ChatTransport = {
  baseUrl: '/api',
  streamBaseUrl: getStreamBaseUrl(),
  getAccessToken,
};

export function chat(data: ChatRequest): Promise<ChatResponse> {
  return postChat(transport, data);
}

export function chatStream(data: ChatRequest, callbacks: ChatStreamCallbacks): Promise<void> {
  return streamChat(transport, data, callbacks);
}

export function regenerateMessageStream(
  sessionId: string,
  messageId: string,
  callbacks: ChatStreamCallbacks
): Promise<void> {
  return regenerateStream(transport, sessionId, messageId, callbacks);
}

export function listModels(): Promise<ModelInfo[]> {
  return fetchModels(transport);
}
