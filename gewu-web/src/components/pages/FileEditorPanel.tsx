'use client';
// 绞杀者迁移（D0-11）：实现已抽离至 @gewu/agent-ui/chat-ui/filepanel，
// web 侧仅注入网关传输的 file-changes API（桌面端注入本地引擎 API，同构复用）。
import { createSessionFileChangesApi } from '@gewu/agent-ui/api-client';
import { FileEditorPanel as FileEditorPanelImpl, FILE_PANEL_MIN_WIDTH } from '@gewu/agent-ui/chat-ui/filepanel';
import { getAccessToken } from '@/lib/token';

export { FILE_PANEL_MIN_WIDTH };
export type { FileChangeDTO, FileDiffDTO, TurnFileChangeDTO, UndoResultDTO } from '@gewu/agent-ui/api-client';

const fileChangesApi = createSessionFileChangesApi({ baseUrl: '/api', getAccessToken });

export default function FileEditorPanel(props: Omit<Parameters<typeof FileEditorPanelImpl>[0], 'api'>) {
  return <FileEditorPanelImpl {...props} api={fileChangesApi} />;
}
