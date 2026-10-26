'use client';
// Monaco 文件编辑器（S9 F3）：自托管 public/monaco 资源加载（免 CDN）。
// 仅客户端渲染（React.lazy 客户端懒加载引入）。
// 编辑工作空间增强（IDEA 对齐）：默认主题可配（含 gewu-dark 定制主题）、
// 自动补全（词级 + 语言 live 模板）、括号配对着色、Ctrl+滚轮缩放、
// 光标位置回调与跳行定位（全局搜索命中跳转用）。新 props 全部可选，旧调用点行为不变。
import { useEffect, useRef } from 'react';
import Editor, { loader, type Monaco } from '@monaco-editor/react';
import type { Position, editor as MonacoEditorAPI } from 'monaco-editor';

let configured = false;
function ensureLoader() {
  if (configured) return;
  configured = true;
  // 自托管 AMD 资源（web public/monaco；桌面 vite public 同构复制）。
  // 相对路径兼容 file://（Electron 生产态）与 dev server；
  // 宿主可经 __GEWU_MONACO_VS_PATH__ 覆盖。
  const g = globalThis as { __GEWU_MONACO_VS_PATH__?: string };
  loader.config({ paths: { vs: g.__GEWU_MONACO_VS_PATH__ || './monaco/vs' } });
}

/** gewu-dark 定制主题（墨绿暗色，对齐开发工作空间界面色系） */
const GEWU_DARK_THEME = {
  base: 'vs-dark' as const,
  inherit: true,
  rules: [
    { token: 'comment', foreground: '6b8578', fontStyle: 'italic' },
    { token: 'keyword', foreground: '7dd3fc' },
    { token: 'string', foreground: 'a5d6a7' },
    { token: 'number', foreground: 'fbbf72' },
    { token: 'type', foreground: '86efac' },
    { token: 'identifier', foreground: 'd6e5dd' },
  ],
  colors: {
    'editor.background': '#0a1614',
    'editor.foreground': '#d6e5dd',
    'editor.lineHighlightBackground': '#11281f',
    // Monaco 主题色值必须带 # 前缀——缺 # 解析失败会回退纯红（用户实报：行号变红）
    'editorLineNumber.foreground': '#3f5b4f',
    'editorLineNumber.activeForeground': '#86efac',
    'editorCursor.foreground': '#34d399',
    'editor.selectionBackground': '#1e4638',
    'editorIndentGuide.background': '#163029',
    'editorIndentGuide.activeBackground': '#28544a',
    'editorWidget.background': '#0d1f1a',
    'editorWidget.border': '#1e3a32',
    'editorSuggestWidget.selectedBackground': '#1e4638',
  },
};

/** IDEA live 模板子集（高频骨架代码，label → 触发词） */
const LIVE_TEMPLATES: Record<string, Array<{ label: string; detail: string; body: string }>> = {
  java: [
    { label: 'psvm', detail: 'public static void main', body: 'public static void main(String[] args) {\n\t$0\n}' },
    { label: 'sout', detail: 'System.out.println()', body: 'System.out.println($0);' },
    { label: 'souf', detail: 'System.out.printf()', body: 'System.out.printf($0);' },
    { label: 'fori', detail: 'for 循环（索引）', body: 'for (int ${1:i} = 0; ${1:i} < ${2:limit}; ${1:i}++) {\n\t$0\n}' },
    { label: 'psfi', detail: 'public static final int', body: 'public static final int ${1:NAME} = $0;' },
  ],
  python: [
    { label: 'main', detail: 'if __name__ == "__main__"', body: 'def main():\n\t$0\n\n\nif __name__ == "__main__":\n\tmain()\n' },
    { label: 'p', detail: 'print()', body: 'print($0)' },
    { label: 'def', detail: '函数定义', body: 'def ${1:fn}(${2:args}):\n\t$0' },
  ],
  go: [
    { label: 'main', detail: 'func main()', body: 'func main() {\n\t$0\n}' },
    { label: 'pf', detail: 'fmt.Println()', body: 'fmt.Println($0)' },
    { label: 'forr', detail: 'for range 循环', body: 'for ${1:k}, ${2:v} := range ${3:coll} {\n\t$0\n}' },
  ],
  rust: [
    { label: 'fnm', detail: 'fn main()', body: 'fn main() {\n\t$0\n}' },
    { label: 'pln', detail: 'println!()', body: 'println!($0);' },
    { label: 'impl', detail: 'impl 块', body: 'impl ${1:Trait} for ${2:Type} {\n\t$0\n}' },
  ],
  cpp: [
    { label: 'main', detail: 'int main()（含 iostream）', body: '#include <iostream>\n\nint main() {\n\t$0\n\treturn 0;\n}\n' },
    { label: 'cout', detail: 'std::cout', body: 'std::cout << $0 << std::endl;' },
  ],
  javascript: [
    { label: 'clg', detail: 'console.log()', body: 'console.log($0);' },
    { label: 'fn', detail: '箭头函数', body: 'const ${1:fn} = (${2:args}) => {\n\t$0\n};' },
  ],
  typescript: [
    { label: 'clg', detail: 'console.log()', body: 'console.log($0);' },
    { label: 'fn', detail: '箭头函数', body: 'const ${1:fn} = (${2:args}: ${3:type}) => {\n\t$0\n};' },
    { label: 'rfc', detail: 'React 函数组件', body: 'export default function ${1:Component}() {\n\treturn (\n\t\t<div>$0</div>\n\t);\n}' },
  ],
  html: [
    { label: 'html5', detail: 'HTML5 骨架', body: '<!DOCTYPE html>\n<html lang="zh-CN">\n<head>\n\t<meta charset="UTF-8">\n\t<meta name="viewport" content="width=device-width, initial-scale=1.0">\n\t<title>${1:标题}</title>\n</head>\n<body>\n\t$0\n</body>\n</html>' },
  ],
};

let extrasRegistered = false;
/** 主题注册 + live 模板补全注册（monaco 就绪后执行一次） */
function ensureEditorExtras(monaco: Monaco) {
  monaco.editor.defineTheme('gewu-dark', GEWU_DARK_THEME);
  if (extrasRegistered) return;
  extrasRegistered = true;
  for (const [lang, templates] of Object.entries(LIVE_TEMPLATES)) {
    monaco.languages.registerCompletionItemProvider(lang, {
      provideCompletionItems(model: MonacoEditorAPI.ITextModel, position: Position) {
        const word = model.getWordUntilPosition(position);
        const range = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: word.startColumn,
          endColumn: word.endColumn,
        };
        return {
          suggestions: templates.map((t) => ({
            label: t.label,
            kind: monaco.languages.CompletionItemKind.Snippet,
            detail: `模板: ${t.detail}`,
            insertText: t.body,
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range,
          })),
        };
      },
    });
  }
}

export default function MonacoEditor({
  value, language, path, onChange, readOnly = false, wordWrap = 'on',
  theme, minimap = false, gotoLine, onCursor,
  breakpoints, conditionalBreakpoints, onToggleBreakpoint, currentLine, inlineValue, lineAfter, valueLookup,
}: {
  value: string;
  language: string;
  path: string;
  onChange?: (value: string | undefined) => void;
  /** 只读预览（图2 代码阅读视图；编辑态为 false） */
  readOnly?: boolean;
  /** 自动换行：预览关（横向滚动贴近 IDE），编辑默认开 */
  wordWrap?: 'on' | 'off';
  /** 编辑器主题（默认 vs-dark；开发工作空间传 gewu-dark 等可选项） */
  theme?: string;
  /** 右侧缩略图（IDEA 无此件，默认关；需要时开启） */
  minimap?: boolean;
  /** 打开后定位到指定行并居中（全局搜索命中跳转） */
  gotoLine?: number;
  /** 光标位置变化回调（状态栏 Ln,Col 展示） */
  onCursor?: (line: number, column: number) => void;
  /** 断点行集合（行号槽数点，IDEA Debug） */
  breakpoints?: number[];
  /** 条件断点行集合（琥珀色点，IDEA Debug） */
  conditionalBreakpoints?: number[];
  /** 行号槽点击切换断点（shiftKey=设置条件断点，IDEA Ctrl+点击语义） */
  onToggleBreakpoint?: (lineNumber: number, shiftKey?: boolean) => void;
  /** 调试当前执行行高亮（IDEA Debug） */
  currentLine?: number;
  /** 行尾内联变量值（IDEA inline values，调试停点快照） */
  inlineValue?: { line: number; text: string };
  /** 多行行尾标注（blame Annotate：每行作者/提交摘要） */
  lineAfter?: Array<{ line: number; text: string }>;
  /** 悬停取值（IDEA hover 求值：word → 变量值） */
  valueLookup?: (word: string) => string | undefined;
}) {
  void ensureLoader();
  const editorRef = useRef<MonacoEditorAPI.IStandaloneCodeEditor | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const decorationsRef = useRef<MonacoEditorAPI.IEditorDecorationsCollection | null>(null);
  // 最新回调经 ref 透传，避免装饰/鼠标监听反复重绑
  const toggleBpRef = useRef(onToggleBreakpoint);
  toggleBpRef.current = onToggleBreakpoint;
  const lookupRef = useRef(valueLookup);
  lookupRef.current = valueLookup;

  // 跳行定位：文件打开或 gotoLine 变化时居中目标行
  useEffect(() => {
    const ed = editorRef.current;
    if (!ed || !gotoLine || gotoLine < 1) return;
    ed.revealLineInCenter(gotoLine);
    ed.setPosition({ lineNumber: gotoLine, column: 1 });
    if (!readOnly) ed.focus();
  }, [gotoLine, path, readOnly]);

  // 断点槽 + 当前行装饰（IDEA Debug：红点断点、琥珀点条件断点、绿箭头当前执行行、行尾内联值）
  useEffect(() => {
    const monaco = monacoRef.current;
    const collection = decorationsRef.current;
    if (!monaco || !collection) return;
    const decorations: MonacoEditorAPI.IModelDeltaDecoration[] = [];
    (breakpoints || []).forEach((l) => {
      decorations.push({
        range: new monaco.Range(l, 1, l, 1),
        options: {
          linesDecorationsClassName: 'dw-bp-glyph',
          overviewRuler: { color: '#ef4444', position: monaco.editor.OverviewRulerLane.Right },
        },
      });
    });
    (conditionalBreakpoints || []).forEach((l) => {
      decorations.push({
        range: new monaco.Range(l, 1, l, 1),
        options: {
          linesDecorationsClassName: 'dw-bp-glyph-cond',
          overviewRuler: { color: '#f59e0b', position: monaco.editor.OverviewRulerLane.Right },
        },
      });
    });
    if (currentLine && currentLine > 0) {
      decorations.push({
        range: new monaco.Range(currentLine, 1, currentLine, 1),
        options: {
          isWholeLine: true,
          className: 'dw-current-line',
          linesDecorationsClassName: 'dw-current-arrow',
          overviewRuler: { color: '#34d399', position: monaco.editor.OverviewRulerLane.Right },
        },
      });
    }
    if (inlineValue && inlineValue.line > 0 && inlineValue.text) {
      decorations.push({
        range: new monaco.Range(inlineValue.line, 1, inlineValue.line, 1),
        options: {
          after: { content: '    ' + inlineValue.text, inlineClassName: 'dw-inline-val' },
        },
      });
    }
    collection.set(decorations);
    (lineAfter || []).forEach((la) => {
      if (la.line > 0 && la.text) {
        decorations.push({
          range: new monaco.Range(la.line, 1, la.line, 1),
          options: {
            after: { content: '   ' + la.text, inlineClassName: 'dw-annotate' },
          },
        });
      }
    });
    collection.set(decorations);
  }, [breakpoints, conditionalBreakpoints, currentLine, inlineValue, lineAfter, path]);

  return (
    <Editor
      height="100%"
      language={language}
      path={path}
      value={value}
      theme={theme || 'vs-dark'}
      onChange={onChange}
      onMount={(ed, monaco) => {
        editorRef.current = ed;
        monacoRef.current = monaco;
        decorationsRef.current = ed.createDecorationsCollection([]);
        ensureEditorExtras(monaco);
        if (theme && theme !== 'vs-dark') monaco.editor.setTheme(theme);
        if (onCursor) {
          ed.onDidChangeCursorPosition((e) => onCursor(e.position.lineNumber, e.position.column));
        }
        if (onToggleBreakpoint) {
          // 行号槽/边槽点击 = 切换断点；Shift+点击 = 条件断点（IDEA 交互）
          ed.onMouseDown((e) => {
            const t = e.target.type;
            const isGutter =
              t === monaco.editor.MouseTargetType.GUTTER_MARGIN ||
              t === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN ||
              t === monaco.editor.MouseTargetType.LINE_NUMBERS;
            if (isGutter && e.target.position) {
              toggleBpRef.current?.(e.target.position.lineNumber, e.event.shiftKey);
            }
          });
        }
        if (valueLookup) {
          // 悬停求值（IDEA Debug hover：word 命中调试变量时展示取值）
          const disposable = monaco.languages.registerHoverProvider('*', {
            provideHover(model: MonacoEditorAPI.ITextModel, position: Position) {
              const word = model.getWordAtPosition(position);
              if (!word) return null;
              const v = lookupRef.current?.(word.word);
              if (v === undefined || v === null || v === '') return null;
              return {
                range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
                contents: [{ value: '`' + word.word + '` = **' + v + '**' }],
              };
            },
          });
          ed.onDidDispose(() => disposable.dispose());
        }
      }}
      options={{
        minimap: { enabled: minimap },
        fontSize: 12,
        lineNumbersMinChars: 3,
        scrollBeyondLastLine: false,
        automaticLayout: true,
        wordWrap,
        tabSize: 4,
        readOnly,
        domReadOnly: readOnly,
        // 新版 Monaco 默认开启作用域固定条（stickyScroll），透明底与代码行重叠发花，阅读视图关闭
        stickyScroll: { enabled: false },
        // ===== IDEA 对齐编辑能力（S 编辑区升级） =====
        // 自动编码提示：词级 + 语言 live 模板 + 触发字符（字符串内提示路径/补全）
        quickSuggestions: { other: true, comments: false, strings: true },
        suggestOnTriggerCharacters: true,
        wordBasedSuggestions: 'currentDocument',
        tabCompletion: 'on',
        snippetSuggestions: 'top',
        // 括号配对着色与缩进参考线
        bracketPairColorization: { enabled: true },
        guides: { bracketPairs: 'active', indentation: true },
        // 交互手感：Ctrl+滚轮缩放（IDEA 同款）、平滑滚动/光标动画、粘贴自动格式化
        mouseWheelZoom: true,
        smoothScrolling: true,
        cursorSmoothCaretAnimation: 'on',
        formatOnPaste: true,
        // HTML/XML 标签联动重命名
        linkedEditing: true,
        renderLineHighlight: 'all',
        showFoldingControls: 'mouseover',
      }}
    />
  );
}
