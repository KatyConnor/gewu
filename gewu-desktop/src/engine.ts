/**
 * 连接管理器（D1-6 渲染层侧）：等待引擎就绪 → 构建 ChatTransport 直连本地引擎。
 * 令牌经 preload 交付（主进程 EngineSupervisor 生成，每次启动更换，§5.2）。
 *
 * 自愈语义（V1.2 实测教训）：引擎崩溃自动重启后令牌更换，渲染层旧令牌会收到 401；
 * EngineConnection 在 401/网络错误时自动刷新连接信息并重试一次。
 */
import type { ChatTransport } from '@gewu/agent-ui/api-client';

export interface EngineInfo {
  state: 'booting' | 'ready' | 'crashed' | 'stopped' | 'degraded';
  port: number | null;
  token: string | null;
  pid: number | null;
}

declare global {
  interface Window {
    gewu: {
      getEngineInfo(): Promise<EngineInfo>;
      restartEngine(): Promise<EngineInfo>;
      onEngineStatus(cb: (s: { state: string; detail?: unknown; info?: EngineInfo }) => void): () => void;
      saveModelKey(p: { providerCode: string; apiKey: string; displayName?: string; baseUrl?: string; modelsJson?: string }): Promise<{ ok: boolean }>;
      listSavedProviders(): Promise<Array<{ code: string; displayName?: string; baseUrl?: string }>>;
      terminal: {
        create: (opts?: { cwd?: string; cols?: number; rows?: number; shell?: string }) =>
          Promise<{ tabId: string; backend: string }>;
        write: (p: { tabId: string; data: string }) => Promise<void>;
        resize: (p: { tabId: string; cols: number; rows: number }) => Promise<void>;
        kill: (p: { tabId: string }) => Promise<void>;
        onData: (cb: (d: { tabId: string; data: string }) => void) => () => void;
        onExit: (cb: (d: { tabId: string; code: number }) => void) => () => void;
      };
    };
  }
}

export function waitEngineReady(timeoutMs = 150_000): Promise<EngineInfo> {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(async () => {
      const info = await window.gewu.getEngineInfo();
      if (info.state === 'ready' && info.port && info.token) {
        clearInterval(timer);
        resolve(info);
      } else if (Date.now() > deadline) {
        clearInterval(timer);
        reject(new Error('引擎启动超时'));
      }
    }, 500);
    window.gewu.onEngineStatus((s) => {
      if (s.state === 'ready' && s.info?.port && s.info?.token) {
        clearInterval(timer);
        resolve(s.info);
      }
    });
  });
}

/** 本地引擎连接：transport + 401/网络错误自愈（刷新令牌重试一次） */
export class EngineConnection {
  private info: EngineInfo | null = null;
  private transport: ChatTransport | null = null;

  update(info: EngineInfo) {
    this.info = info;
    if (this.transport) {
      // 既有 transport 原地刷新（streamChat 每次调用时读取）
      this.transport.baseUrl = `http://127.0.0.1:${info.port}/api`;
      this.transport.extraHeaders = { 'X-Engine-Token': info.token || '' };
    }
  }

  /** 公开只读访问（FileEditorPanel 的 file-changes API 注入用；连接自愈时对象原地更新） */
  transportFor(): ChatTransport | null {
    return this.getTransport();
  }

  getTransport(): ChatTransport | null {
    if (!this.info || this.info.state !== 'ready' || !this.info.port) return null;
    if (!this.transport) {
      this.transport = {
        baseUrl: `http://127.0.0.1:${this.info.port}/api`,
        getAccessToken: () => null,
        extraHeaders: { 'X-Engine-Token': this.info.token || '' },
      };
    }
    return this.transport;
  }

  /** JSON 数据面请求：Result 信封解包，返回 data（401 自愈同 fetch） */
  async data(path: string, init?: RequestInit): Promise<unknown> {
    const res = await this.fetch(path, init);
    if (!res.ok) throw new Error(`HTTP ${res.status} ${path}`);
    const env = (await res.json()) as { code: number; message: string; data: unknown };
    if (env.code !== 10000) throw new Error(env.message || `业务错误 ${env.code}`);
    return env.data;
  }

  /** 带自愈的请求：401 或网络错误 → 刷新引擎信息重试一次 */
  async fetch(path: string, init?: RequestInit): Promise<Response> {
    const t = this.getTransport();
    if (!t) throw new Error('引擎未就绪');
    try {
      const res = await fetch(`${t.baseUrl}${path}`, { ...init, headers: { ...t.extraHeaders, ...(init?.headers || {}) } });
      if (res.status === 401) {
        return await this.refreshAndRetry(path, init);
      }
      return res;
    } catch (e) {
      // 网络级失败（引擎可能已带新令牌重启）：刷新后重试一次
      return await this.refreshAndRetry(path, init, e);
    }
  }

  private async refreshAndRetry(path: string, init: RequestInit | undefined, cause?: unknown): Promise<Response> {
    const fresh = await window.gewu.getEngineInfo();
    if (fresh.state === 'ready' && fresh.port && fresh.token) {
      this.update(fresh);
      const t = this.getTransport()!;
      return fetch(`${t.baseUrl}${path}`, { ...init, headers: { ...t.extraHeaders, ...(init?.headers || {}) } });
    }
    throw cause instanceof Error ? cause : new Error('引擎未就绪（已重启，请重试）');
  }

  /** 带自愈的流式对话（401/网络错误 → 刷新令牌重试一次） */
  async streamChat(path: string, body: unknown, callbacks: ChatStreamCallbacks): Promise<void> {
    const { consumeChatSse, extractErrorMessage } = await import('@gewu/agent-ui/api-client');
    const attempt = async (): Promise<Response> => {
      const t = this.getTransport();
      if (!t) throw new Error('引擎未就绪');
      return fetch(`${t.baseUrl}${path}`, {
        method: 'POST',
        headers: { ...t.extraHeaders, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(body),
      });
    };
    let res: Response;
    try {
      res = await attempt();
      if (res.status === 401) {
        await this.refreshAndRetry(path, undefined);
        res = await attempt();
      }
    } catch (e) {
      await this.refreshAndRetry(path, undefined).catch(() => {
        throw e;
      });
      res = await attempt();
    }
    if (!res.ok) {
      throw new Error(await extractErrorMessage(res, `流式聊天请求失败: ${res.status}`));
    }
    return consumeChatSse(res, callbacks);
  }
}

import type { ChatStreamCallbacks } from '@gewu/agent-ui/protocol';
