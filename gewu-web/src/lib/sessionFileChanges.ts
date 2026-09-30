// 绞杀者迁移（D0-11）：实现已抽离至 @gewu/agent-ui/api-client（ChatTransport 注入版），
// web 侧导出网关传输绑定的单例函数，既有导入路径保持兼容。
import { createSessionFileChangesApi } from '@gewu/agent-ui/api-client';
import { getAccessToken } from './token';

export type {
  FileChangeDTO, FileDiffDTO, TurnFileChangeDTO, UndoResultDTO, SessionFileChangesApi,
} from '@gewu/agent-ui/api-client';

export const sessionFileChangesApi = createSessionFileChangesApi({
  baseUrl: '/api',
  getAccessToken,
});

export const listFileChanges = sessionFileChangesApi.listFileChanges;
export const getFileDiff = sessionFileChangesApi.getFileDiff;
export const getFileContent = sessionFileChangesApi.getFileContent;
export const saveFileContent = sessionFileChangesApi.saveFileContent;
export const getTurnFileChanges = sessionFileChangesApi.getTurnFileChanges;
export const undoFileChanges = sessionFileChangesApi.undoFileChanges;
