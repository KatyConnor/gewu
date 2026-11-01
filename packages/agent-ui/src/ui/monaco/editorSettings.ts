// 编辑器偏好设置（IDEA Settings/Preferences 对齐：全局生效 + localStorage 持久化）。
// 定义于 agent-ui（Monaco 组件同层），宿主可 re-export 后接入设置面板。
export interface EditorSettings {
  /** 编辑器字号（10–24） */
  fontSize: number;
  /** 缩进宽度（2/4/8） */
  tabSize: number;
  /** 空格缩进（false = Tab 字符） */
  insertSpaces: boolean;
  /** 软换行（IDEA 默认关，格物默认开以适配中文阅读） */
  wordWrap: boolean;
  /** 右侧缩略图 */
  minimap: boolean;
  /** 空白符显示：仅选区 / 全部 / 关闭 */
  renderWhitespace: 'selection' | 'all' | 'none';
  /** 相对行号 */
  relativeLineNumbers: boolean;
  /** 粘性滚动（作用域签名钉在编辑器顶部） */
  stickyScroll: boolean;
  /** 括号配对着色 */
  bracketPairs: boolean;
  /** 自动保存（IDEA 默认开：修改后防抖写盘） */
  autoSave: boolean;
  /** 保存时格式化 */
  formatOnSave: boolean;
}

export const DEFAULT_EDITOR_SETTINGS: EditorSettings = {
  fontSize: 12,
  tabSize: 4,
  insertSpaces: true,
  wordWrap: true,
  minimap: false,
  renderWhitespace: 'selection',
  relativeLineNumbers: false,
  stickyScroll: false,
  bracketPairs: true,
  autoSave: true,
  formatOnSave: false,
};

const SETTINGS_KEY = 'dw-editor-settings';

/** 读取偏好（损坏/缺项回落默认值，向前兼容新增字段） */
export function loadEditorSettings(): EditorSettings {
  if (typeof window === 'undefined') return { ...DEFAULT_EDITOR_SETTINGS };
  try {
    const raw = window.localStorage.getItem(SETTINGS_KEY);
    if (!raw) return { ...DEFAULT_EDITOR_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<EditorSettings>;
    return { ...DEFAULT_EDITOR_SETTINGS, ...parsed };
  } catch {
    return { ...DEFAULT_EDITOR_SETTINGS };
  }
}

export function saveEditorSettings(s: EditorSettings): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(SETTINGS_KEY, JSON.stringify(s));
  } catch { /* 隐私模式等写入失败忽略 */ }
}
