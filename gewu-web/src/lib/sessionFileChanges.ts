// 会话文件变更服务（S9 F3）——右侧文件编辑面板的数据源。
// 对接 SessionFileChangeController：变更列表（+N/-N）、diff 审查、当前内容读写。
import { request } from './request';

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

/** 后端统一响应信封 */
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

async function unwrap<T>(p: Promise<ApiResponse<T>>): Promise<T> {
  const res = await p;
  if (!res || res.code !== 10000) throw new Error(res?.message || '请求失败');
  return res.data;
}

/** 会话变更文件列表（会话结束时拉取，驱动「N 个文件已更改」chip 与面板列表） */
export async function listFileChanges(sessionId: string): Promise<FileChangeDTO[]> {
  return unwrap(request.get<ApiResponse<FileChangeDTO[]>>(
    `/v1/sessions/${sessionId}/file-changes`));
}

/** 变更差异（审查视图） */
export async function getFileDiff(sessionId: string, path: string): Promise<FileDiffDTO> {
  return unwrap(request.get<ApiResponse<FileDiffDTO>>(
    `/v1/sessions/${sessionId}/file-changes/diff`, { params: { path } }));
}

/** 读取当前文件内容（打开/编辑视图） */
export async function getFileContent(sessionId: string, path: string): Promise<string> {
  return unwrap(request.get<ApiResponse<string>>(
    `/v1/sessions/${sessionId}/file-changes/content`, { params: { path } }));
}

/** 保存文件内容（编辑写回工作空间） */
export async function saveFileContent(sessionId: string, path: string, content: string): Promise<void> {
  return unwrap(request.put<ApiResponse<void>>(
    `/v1/sessions/${sessionId}/file-changes/content`, { content }, { params: { path } }));
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

/** 最近一次回复的修改汇总（回合快照；无快照返回空数组） */
export async function getTurnFileChanges(sessionId: string): Promise<TurnFileChangeDTO[]> {
  return unwrap(request.get<ApiResponse<TurnFileChangeDTO[]>>(
    `/v1/sessions/${sessionId}/file-changes/turn`));
}

/** 撤销最近一次回复的文件修改（paths 空=全部撤销；路径仅限回合快照白名单） */
export async function undoFileChanges(sessionId: string, paths?: string[]): Promise<UndoResultDTO[]> {
  return unwrap(request.post<ApiResponse<UndoResultDTO[]>>(
    `/v1/sessions/${sessionId}/file-changes/undo`, paths && paths.length > 0 ? { paths } : {}));
}
