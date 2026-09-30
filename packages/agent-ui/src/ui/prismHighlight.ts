/**
 * Prism 逐行语法着色（审查 diff 的行内高亮专用）。
 * Prism.highlight 输出自带 HTML 转义可直接注入；逐行 tokenize 的已知局限：
 * 跨行注释/字符串的续行不着色（单行缺上下文）。不支持的语言回退纯文本。
 */
import Prism from 'prismjs';
import 'prismjs/components/prism-typescript';
import 'prismjs/components/prism-jsx';
import 'prismjs/components/prism-tsx';
import 'prismjs/components/prism-java';
import 'prismjs/components/prism-python';
import 'prismjs/components/prism-go';
import 'prismjs/components/prism-rust';
import 'prismjs/components/prism-c';
import 'prismjs/components/prism-cpp';
import 'prismjs/components/prism-csharp';
import 'prismjs/components/prism-bash';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-json';
import 'prismjs/components/prism-sql';
import 'prismjs/components/prism-ini';
import 'prismjs/components/prism-toml';
import 'prismjs/components/prism-markdown';
import 'prismjs/components/prism-groovy';

const PRISM_LANG_BY_EXT: Record<string, string> = {
  ts: 'typescript', tsx: 'tsx', js: 'javascript', jsx: 'jsx',
  java: 'java', py: 'python', go: 'go', rs: 'rust',
  c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp', hpp: 'cpp', cs: 'csharp',
  sh: 'bash', bash: 'bash', yml: 'yaml', yaml: 'yaml', json: 'json',
  xml: 'markup', html: 'markup', md: 'markdown', sql: 'sql',
  css: 'css', toml: 'toml', ini: 'ini', properties: 'ini', gradle: 'groovy',
};

/** 按路径扩展名推断 Prism 语法名；不支持返回 null（调用方回退纯文本） */
export function prismLangOf(path: string): string | null {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  return PRISM_LANG_BY_EXT[ext] ?? null;
}

/** 着色结果缓存：diff 行文本高度重复（上下文/相邻渲染），按 lang+text 记忆 */
const cache = new Map<string, string>();
const CACHE_LIMIT = 10_000;

/** 单行着色为 HTML；超长行/未知语言/异常时返回 null */
export function highlightLine(text: string, lang: string): string | null {
  const grammar = Prism.languages[lang];
  if (!grammar || text.length > 1000) return null;
  const key = `${lang}\u0000${text}`;
  const hit = cache.get(key);
  if (hit !== undefined) return hit;
  let html: string;
  try {
    html = Prism.highlight(text, grammar, lang);
  } catch {
    return null;
  }
  if (cache.size >= CACHE_LIMIT) cache.clear();
  cache.set(key, html);
  return html;
}
