// IDEA 键位映射（IDEA keymap 对齐 · 编辑能力迁移 Phase 2）。
// 在编辑器 onMount 后调用；覆盖 Monaco 默认 VS Code 语义的冲突键位
//（Monaco standalone 中 addCommand 后注册优先于内置同键绑定）。
// 动作优先复用 Monaco 内置 action id（lineOperations/caseOperations/smartSelect/multicursor），
// 缺省语义（大小写切换/列选择开关）就地实现。宿主钩子（跳行/书签/包裹等需页面交互的能力）可选传入。
import type { Monaco } from '@monaco-editor/react';
import type { editor as MonacoEditorAPI } from 'monaco-editor';

export interface IdeaKeybindingHooks {
  /** Ctrl+G 跳行（缺省走 Monaco 内置 gotoLine quick access） */
  onGotoLine?: () => void;
  /** F11 行书签切换 */
  onToggleBookmark?: () => void;
  /** Shift+F11 书签列表 */
  onShowBookmarks?: () => void;
  /** Ctrl+Alt+T Surround With */
  onSurroundWith?: () => void;
  /** Ctrl+Alt+L 格式化 */
  onFormat?: () => void;
  /** Alt+F7 查找用法 */
  onFindUsages?: () => void;
  /** Ctrl+B 跳转定义 */
  onGotoDefinition?: () => void;
  /** Ctrl+Shift+A 查找动作（命令面板） */
  onFindAction?: () => void;
  /** Ctrl+Shift+V 多剪贴板粘贴 */
  onPasteHistory?: () => void;
  /** Shift+F6 重命名（词法近似） */
  onRename?: () => void;
  /** F2 / Shift+F2 错误导航 */
  onJumpToNextError?: () => void;
  onJumpToPrevError?: () => void;
  /** Ctrl+F12 结构视图 */
  onGotoStructure?: () => void;
}

/** 选区文本大小写切换：大写占优转小写，否则转大写（IDEA Ctrl+Shift+U） */
function toggleCase(ed: MonacoEditorAPI.IStandaloneCodeEditor): void {
  const sel = ed.getSelection();
  if (!sel || sel.isEmpty()) return;
  const cur = ed.getModel()?.getValueInRange(sel) || '';
  const isUpper = cur === cur.toUpperCase();
  ed.trigger('idea', isUpper ? 'editor.action.transformToLowercase' : 'editor.action.transformToUppercase', null);
}

/** 列选择模式开关（IDEA Alt+Shift+Insert） */
function toggleColumnSelection(ed: MonacoEditorAPI.IStandaloneCodeEditor, monaco: Monaco): void {
  const cur = ed.getOption(monaco.editor.EditorOption.columnSelection);
  ed.updateOptions({ columnSelection: !cur });
}

/**
 * 行操作自实现（不依赖内置 action id 的跨版本兼容）：
 * 多选区按行号降序处理（自后向前），避免行号偏移相互影响。
 */

/** 复制行/选区向下（IDEA Ctrl+D；整行无选区=复制整行，选区=复制选区文本） */
function copyLinesDown(ed: MonacoEditorAPI.IStandaloneCodeEditor, monaco: Monaco): void {
  const model = ed.getModel();
  const sels = ed.getSelections();
  if (!model || !sels || !sels.length) return;
  const eol = model.getEOL();
  const edits: Array<{ range: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number }; text: string; forceMoveMarkers?: boolean }> = [];
  const newSels: Array<InstanceType<typeof monaco.Selection>> = [];
  for (const sel of [...sels].sort((a, b) => b.startLineNumber - a.startLineNumber)) {
    const s = sel.startLineNumber, e = sel.endLineNumber;
    const fullLines = sel.isEmpty() || (sel.startColumn === 1 && sel.endColumn === model.getLineMaxColumn(e));
    if (fullLines) {
      const text = model.getValueInRange({ startLineNumber: s, startColumn: 1, endLineNumber: e, endColumn: model.getLineMaxColumn(e) });
      edits.push({ range: new monaco.Range(e, model.getLineMaxColumn(e), e, model.getLineMaxColumn(e)), text: eol + text, forceMoveMarkers: true });
      const span = e - s;
      newSels.push(new monaco.Selection(s + 1 + span, sel.startColumn, e + 1 + span, sel.endColumn));
    } else {
      const text = model.getValueInRange(sel);
      edits.push({ range: new monaco.Range(e, sel.endColumn, e, sel.endColumn), text, forceMoveMarkers: true });
      newSels.push(sel);
    }
  }
  ed.executeEdits('idea-copy-lines', edits);
  if (newSels.length) ed.setSelections(newSels);
}

/** 删除行（IDEA Ctrl+Y：光标行/选区行整行删除） */
function deleteLines(ed: MonacoEditorAPI.IStandaloneCodeEditor, monaco: Monaco): void {
  const model = ed.getModel();
  const sels = ed.getSelections();
  if (!model || !sels || !sels.length) return;
  const last = model.getLineCount();
  const edits: Array<{ range: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number }; text: string }> = [];
  for (const sel of [...sels].sort((a, b) => b.startLineNumber - a.startLineNumber)) {
    const s = sel.startLineNumber, e = sel.endLineNumber;
    if (e === last && s === 1) { edits.push({ range: new monaco.Range(1, 1, last, model.getLineMaxColumn(last)), text: '' }); continue; }
    const isLastBlock = e === last;
    // 删除整行含换行：到下一行行首；文件末尾块则从上一行行尾删到末尾
    const range = isLastBlock
      ? new monaco.Range(s - 1, model.getLineMaxColumn(s - 1), e, model.getLineMaxColumn(e))
      : new monaco.Range(s, 1, e + 1, 1);
    edits.push({ range, text: '' });
  }
  ed.executeEdits('idea-delete-lines', edits);
}

/** 合并行（IDEA Ctrl+Shift+J：选区行合并为单行，空白规整） */
function joinLines(ed: MonacoEditorAPI.IStandaloneCodeEditor, monaco: Monaco): void {
  const model = ed.getModel();
  const sel = ed.getSelection();
  if (!model || !sel) return;
  const start = sel.startLineNumber;
  const end = sel.isEmpty() ? Math.min(start + 1, model.getLineCount()) : sel.endLineNumber;
  if (start === end) return;
  const parts: string[] = [];
  for (let l = start; l <= end; l++) parts.push(model.getLineContent(l).trim());
  ed.executeEdits('idea-join-lines', [{ range: new monaco.Range(start, 1, end, model.getLineMaxColumn(end)), text: parts.filter(Boolean).join(' ') }]);
}

/** 注册 IDEA 键位（keydown capture 拦截器：编辑器 DOM 捕获阶段先于 Monaco 内置键位解析，
 *  保证 IDEA 键位稳定覆盖 Monaco/VS Code 默认冲突键；hooks 缺省项不占用键位） */
export function registerIdeaKeybindings(
  ed: MonacoEditorAPI.IStandaloneCodeEditor,
  monaco: Monaco,
  hooks: IdeaKeybindingHooks = {},
): void {
  const tr = (id: string) => () => ed.trigger('idea', id, null);
  // 键位表：key 规范 = mod(+mod)* + key（key 一律小写；F 键/方向键/Insert 保持原名）
  const map = new Map<string, () => void>();
  const bind = (combo: string, fn: () => void) => map.set(combo, fn);

  // ===== 行操作（IDEA 语义；行操作自实现保证跨版本兼容）=====
  bind('ctrl+d', () => copyLinesDown(ed, monaco));
  bind('ctrl+y', () => deleteLines(ed, monaco));
  bind('alt+shift+arrowup', tr('editor.action.moveLinesUpAction'));
  bind('alt+shift+arrowdown', tr('editor.action.moveLinesDownAction'));
  bind('ctrl+shift+j', () => joinLines(ed, monaco));
  bind('ctrl+shift+u', () => toggleCase(ed));
  bind('ctrl+u', tr('editor.action.transpose'));
  // ===== 选择 =====
  bind('ctrl+w', tr('editor.action.smartSelect.expand'));
  bind('ctrl+shift+w', tr('editor.action.smartSelect.shrink'));
  bind('alt+j', tr('editor.action.addSelectionToNextFindMatch'));
  bind('alt+shift+j', tr('editor.action.addSelectionToPreviousFindMatch'));
  bind('ctrl+alt+shift+j', tr('editor.action.selectHighlights'));
  bind('alt+shift+insert', () => toggleColumnSelection(ed, monaco));
  // ===== 导航 =====
  bind('ctrl+g', () => (hooks.onGotoLine ? hooks.onGotoLine() : tr('editor.action.gotoLine')()));
  if (hooks.onToggleBookmark) bind('f11', () => hooks.onToggleBookmark!());
  if (hooks.onShowBookmarks) bind('shift+f11', () => hooks.onShowBookmarks!());
  if (hooks.onGotoDefinition) bind('ctrl+b', () => hooks.onGotoDefinition!());
  if (hooks.onFindUsages) bind('alt+f7', () => hooks.onFindUsages!());
  if (hooks.onGotoStructure) bind('ctrl+f12', () => hooks.onGotoStructure!());
  // ===== 动作 =====
  if (hooks.onSurroundWith) bind('ctrl+alt+t', () => hooks.onSurroundWith!());
  if (hooks.onFormat) bind('ctrl+alt+l', () => hooks.onFormat!());
  if (hooks.onFindAction) bind('ctrl+shift+a', () => hooks.onFindAction!());
  if (hooks.onPasteHistory) bind('ctrl+shift+v', () => hooks.onPasteHistory!());
  if (hooks.onRename) bind('shift+f6', () => hooks.onRename!());
  if (hooks.onJumpToNextError) bind('f2', () => hooks.onJumpToNextError!());
  if (hooks.onJumpToPrevError) bind('shift+f2', () => hooks.onJumpToPrevError!());

  // keydown capture：编辑器 DOM 捕获阶段拦截（Monaco 自身 keydown 在同节点冒泡注册）
  const domNode = ed.getDomNode();
  const handler = (e: KeyboardEvent) => {
    if (e.repeat) return;
    const mods: string[] = [];
    if (e.ctrlKey || e.metaKey) mods.push('ctrl');
    if (e.altKey) mods.push('alt');
    if (e.shiftKey) mods.push('shift');
    let key = e.key;
    if (!/^F\d+$/.test(key) && !key.startsWith('Arrow') && key !== 'Insert' && key !== 'Delete') {
      key = key.toLowerCase();
    }
    const fn = map.get([...mods, key.toLowerCase()].join('+'));
    if (fn) {
      e.preventDefault();
      e.stopPropagation();
      fn();
    }
  };
  if (domNode) domNode.addEventListener('keydown', handler, true);
  ed.onDidDispose(() => { domNode?.removeEventListener('keydown', handler, true); });
}

/** Surround With 通用包裹模板（IDEA Ctrl+Alt+T；语言通用版，条件/循环占位空串） */
export const SURROUND_TEMPLATES: Array<{ key: string; label: string; head: (indent: string) => string; foot: (indent: string) => string; cursorOffsetInHead: number }> = [
  { key: 'if', label: 'if {}', head: (i) => `if () {\n`, foot: (i) => `\n${i}}`, cursorOffsetInHead: 4 },
  { key: 'ifelse', label: 'if / else', head: (i) => `if () {\n`, foot: (i) => `\n${i}} else {\n${i}}`, cursorOffsetInHead: 4 },
  { key: 'for', label: 'for {}', head: (i) => `for () {\n`, foot: (i) => `\n${i}}`, cursorOffsetInHead: 5 },
  { key: 'while', label: 'while {}', head: (i) => `while () {\n`, foot: (i) => `\n${i}}`, cursorOffsetInHead: 7 },
  { key: 'trycatch', label: 'try / catch', head: (i) => `try {\n`, foot: (i) => `\n${i}} catch (e) {\n${i}}`, cursorOffsetInHead: -1 },
  { key: 'tryfinally', label: 'try / finally', head: (i) => `try {\n`, foot: (i) => `\n${i}} finally {\n${i}}`, cursorOffsetInHead: -1 },
  { key: 'block', label: '{} 代码块', head: () => `{\n`, foot: (i) => `\n${i}}`, cursorOffsetInHead: -1 },
  { key: 'run', label: 'Runnable / lambda', head: () => `() -> {\n`, foot: (i) => `\n${i}}`, cursorOffsetInHead: -1 },
];

/**
 * 应用包裹（IDEA Surround With）：选区整体缩进一级后插入结构，光标定位到条件占位。
 * 语言通用（Java/JS/TS/Go/Rust/C 系均适用花括号结构）。
 */
export function applySurround(
  ed: MonacoEditorAPI.IStandaloneCodeEditor,
  tpl: { head: (indent: string) => string; foot: (indent: string) => string; cursorOffsetInHead: number },
): void {
  const model = ed.getModel();
  const sel = ed.getSelection();
  if (!model || !sel || sel.isEmpty()) return;
  const indent = model.getLineContent(sel.startLineNumber).match(/^\s*/)?.[0] || '';
  const innerIndent = indent + '    ';
  const text = model.getValueInRange(sel);
  const inner = text.split('\n').map((l, i) => (i === 0 ? l : innerIndent + l)).join('\n');
  const head = tpl.head(indent);
  const foot = tpl.foot(indent);
  const composed = head + inner + foot;
  ed.executeEdits('idea-surround', [{ range: sel, text: composed, forceMoveMarkers: true }]);
  // 光标定位：head 内条件占位（首行 `if (` 之后），无占位定位到末尾
  const pos: { lineNumber: number; column: number } =
    tpl.cursorOffsetInHead >= 0
      ? { lineNumber: sel.startLineNumber, column: indent.length + 1 + tpl.cursorOffsetInHead }
      : { lineNumber: sel.startLineNumber, column: indent.length + 1 };
  ed.setPosition(pos);
  ed.focus();
}
