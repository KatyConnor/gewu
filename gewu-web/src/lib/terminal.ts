// 网页终端客户端（设计文档 44）：ticket 申领 + WS 连接管理 + 自动重连 + 心跳
import { request } from './request';

interface ApiResponse<T> { code: number; message?: string; data: T }

function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  return p.then((body) => {
    if (!body || body.code !== 10000) throw new Error(body?.message || '请求失败');
    return body.data;
  });
}

/** 申领一次性终端凭据（后端同时确保 dev 沙箱就绪） */
export async function requestTerminalTicket(): Promise<{ ticket: string; sandboxId: string }> {
  return unwrap(request.post<ApiResponse<{ ticket: string; sandboxId: string }>>(
    '/v1/workspaces/terminal-ticket'));
}

/** WS 基址：默认与 API 同源（生产 nginx 升级转发）；开发环境前端 :5001 → 后端 :8081 直连 */
function wsBase(): string {
  const explicit = process.env.NEXT_PUBLIC_WS_BASE;
  if (explicit) return explicit.replace(/\/+$/, '');
  if (typeof window !== 'undefined' && window.location.port === '5001') {
    return `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.hostname}:8081`;
  }
  if (typeof window !== 'undefined') {
    return `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}`;
  }
  return 'ws://localhost:8081';
}

export type TerminalStatus = 'connecting' | 'ready' | 'closed' | 'error';

export interface TerminalClientOpts {
  cols: number;
  rows: number;
  onOutput(data: ArrayBuffer): void;
  onStatus(status: TerminalStatus, detail?: string): void;
}

/**
 * 终端连接客户端：一次性 ticket → WS（interface 反代 → sandbox PTY）。
 * 异常断开自动重连（最多 3 次，每次重新申领 ticket——ticket 单次有效）；
 * 用户主动关闭不重连。心跳 60s 防空闲回收。
 */
export class TerminalClient {
  private ws: WebSocket | null = null;
  private closedByUser = false;
  private retry = 0;
  private heartbeat: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly opts: TerminalClientOpts) {}

  connect(): void {
    this.closedByUser = false;
    this.opts.onStatus('connecting');
    requestTerminalTicket()
      .then(({ ticket }) => {
        if (this.closedByUser) return;
        const url = `${wsBase()}/api/v1/workspaces/terminal/ws`
            + `?ticket=${encodeURIComponent(ticket)}&cols=${this.opts.cols}&rows=${this.opts.rows}`;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';
        this.ws = ws;

        ws.onopen = () => {
          this.retry = 0;
          this.startHeartbeat();
        };
        ws.onmessage = (ev) => {
          if (typeof ev.data === 'string') {
            this.handleControl(ev.data);
          } else {
            this.opts.onOutput(ev.data as ArrayBuffer);
          }
        };
        ws.onclose = () => {
          this.stopHeartbeat();
          if (this.closedByUser) return;
          if (this.retry < 3) {
            this.retry += 1;
            this.opts.onStatus('connecting', `连接断开，正在重连（${this.retry}/3）…`);
            setTimeout(() => {
              if (!this.closedByUser) this.connect();
            }, 1000 * this.retry);
          } else {
            this.opts.onStatus('closed', '连接已断开，请重新打开终端');
          }
        };
        ws.onerror = () => {
          // onclose 会随后触发，统一在 onclose 处理重连
        };
      })
      .catch((e) => {
        this.opts.onStatus('error', e instanceof Error ? e.message : '凭据申领失败');
      });
  }

  sendInput(data: Uint8Array): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(data);
    }
  }

  resize(cols: number, rows: number): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'resize', cols, rows }));
    }
  }

  dispose(): void {
    this.closedByUser = true;
    this.stopHeartbeat();
    if (this.ws) {
      try { this.ws.close(); } catch { /* 已关闭 */ }
      this.ws = null;
    }
  }

  private handleControl(raw: string): void {
    try {
      const msg = JSON.parse(raw) as { type: string; message?: string };
      if (msg.type === 'ready') {
        this.opts.onStatus('ready');
      } else if (msg.type === 'error') {
        this.opts.onStatus('error', msg.message || '终端错误');
      } else if (msg.type === 'closed') {
        this.stopHeartbeat();
        this.opts.onStatus('closed', msg.message || '终端会话已结束');
      } else if (msg.type === 'exit') {
        this.stopHeartbeat();
        this.opts.onStatus('closed', msg.message || 'shell 已退出，请重新打开终端');
      }
      // pong 静默
    } catch {
      // 非 JSON 控制帧忽略
    }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeat = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'ping' }));
      }
    }, 60_000);
  }

  private stopHeartbeat(): void {
    if (this.heartbeat) {
      clearInterval(this.heartbeat);
      this.heartbeat = null;
    }
  }
}
