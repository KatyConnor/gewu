/**
 * 文件语言识别工具：扩展名 → Monaco 语言名。
 * 从 FileEditorPanel 迁出供文件面板 / 文件卡片 / 工作空间页共用。
 */

/** Monaco 语言映射（未命中回退 plaintext） */
const MONACO_LANG_BY_EXT: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript', js: 'javascript', jsx: 'javascript',
  java: 'java', py: 'python', go: 'go', rs: 'rust', c: 'c', h: 'c',
  cpp: 'cpp', cs: 'csharp', sh: 'shell', bash: 'shell', yml: 'yaml', yaml: 'yaml',
  json: 'json', xml: 'xml', md: 'markdown', sql: 'sql', html: 'html', css: 'css',
  toml: 'ini', ini: 'ini', properties: 'ini', gradle: 'groovy',
};

/** 按路径扩展名推断 Monaco 语言名 */
export function monacoLangOf(path: string): string {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  return MONACO_LANG_BY_EXT[ext] || 'plaintext';
}

const MARKDOWN_EXTS = new Set(['md', 'markdown']);

/** 是否 Markdown 文件（决定打开时走阅读模式还是代码高亮） */
export function isMarkdownPath(path: string): boolean {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  return MARKDOWN_EXTS.has(ext);
}

/** 粗判二进制内容：含 NUL 字节即视为不可读文本（预览降级提示用） */
export function isProbablyBinary(text: string): boolean {
  return text.includes('\u0000');
}
