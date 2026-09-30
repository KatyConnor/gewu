/**
 * 会话文件变更 API（S9 F3，D0-11 迁移）——右侧文件编辑面板的数据源。
 * 对接引擎 API Profile 的 `/sessions/{id}/file-changes` 端点族（与平台
 * SessionFileChangeController 同构，docs/design/42 §7.5/§10.1）。
 * 原 gewu-web/src/lib/sessionFileChanges.ts 的 axios 实现改为 ChatTransport 注入，
 * 双运行时（本地引擎 / 平台网关）共用（ADR-004）。
 */

import type { ApiEnvelope } from '../protocol/index';
import { RESULT_OK } from '../protocol/index';
import { authHeaders, doFetch, type ChatTransport } from './index';

/** 文件变更记录 */
export interface FileChangeDTO {
  path: string;
  changeType: 'CREATE' | 'MODIFY' | 'DELETE';
  additions: number;
  deletions: number;
  updatedAt?: number;
}

/** 文件差异（审查视图） */
export interface FileDiffDTO {
  path: string;
  changeType: string;
  /** 修改前内容（首次修改前快照；新增文件为 null） */
  before: string | null;
  /** 当前内容 */
  after: string | null;
  /** +/- 行标注的简易 diff 文本 */
  diffText: string | null;
}

/** 回合文件汇总（最近一次 AI 回复的修改，撤销数据源） */
export interface TurnFileChangeDTO {
  path: string;
  additions: number;
  deletions: number;
}

/** 撤销逐路径结果 */
export interface UndoResultDTO {
  path: string;
  success: boolean;
  reason?: string;
}

async function unwrap<T>(p: Promise<Response>): Promise<T> {
  const res = await p;
  const json: ApiEnvelope<T> = await res.json();
  if (!json || json.code !== RESULT_OK) {
    throw new Error(json?.message || '请求失败');
  }
  return json.data;
}

/** 会话文件变更 API（按传输创建；同一传输可复用单例） */
export function createSessionFileChangesApi(transport: ChatTransport) {
  const base = transport.baseUrl;
  return {
    /** 会话变更文件列表（驱动「N 个文件已更改」chip 与面板列表） */
    async listFileChanges(sessionId: string): Promise<FileChangeDTO[]> {
      return unwrap(doFetch(transport, `${base}/v1/sessions/${sessionId}/file-changes`, {
        headers: authHeaders(transport),
      }));
    },
    /** 变更差异（审查视图） */
    async getFileDiff(sessionId: string, path: string): Promise<FileDiffDTO> {
      return unwrap(doFetch(transport, `${base}/v1/sessions/${sessionId}/file-changes/diff?path=${encodeURIComponent(path)}`, {
        headers: authHeaders(transport),
      }));
    },
    /** 读取当前文件内容（打开/编辑视图） */
    async getFileContent(sessionId: string, path: string): Promise<string> {
      return unwrap(doFetch(transport, `${base}/v1/sessions/${sessionId}/file-changes/content?path=${encodeURIComponent(path)}`, {
        headers: authHeaders(transport),
      }));
    },
    /** 保存文件内容（编辑写回工作空间） */
    async saveFileContent(sessionId: string, path: string, content: string): Promise<void> {
      return unwrap(doFetch(transport, `${base}/v1/sessions/${sessionId}/file-changes/content?path=${encodeURIComponent(path)}`, {
        method: 'PUT',
        headers: authHeaders(transport, { 'Content-Type': 'application/json' }),
        body: JSON.stringify({ content }),
      }));
    },
    /** 最近一次回复的修改汇总（回合快照；无快照返回空数组） */
    async getTurnFileChanges(sessionId: string): Promise<TurnFileChangeDTO[]> {
      return unwrap(doFetch(transport, `${base}/v1/sessions/${sessionId}/file-changes/turn`, {
        headers: authHeaders(transport),
      }));
    },
    /** 撤销最近一次回复的文件修改（paths 空=全部撤销；路径仅限回合快照白名单） */
    async undoFileChanges(sessionId: string, paths?: string[]): Promise<UndoResultDTO[]> {
      return unwrap(doFetch(transport, `${base}/v1/sessions/${sessionId}/file-changes/undo`, {
        method: 'POST',
        headers: authHeaders(transport, { 'Content-Type': 'application/json' }),
        body: JSON.stringify(paths && paths.length > 0 ? { paths } : {}),
      }));
    },
  };
}

/** 单实例 API 的类型（FileEditorPanel 的注入契约） */
export type SessionFileChangesApi = ReturnType<typeof createSessionFileChangesApi>;
