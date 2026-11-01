// 文件结构大纲（IDEA Structure View / Ctrl+F12 数据源）。
// 词法级正则解析（非 AST）：覆盖 Java/TS/JS/Python/Go/Rust/C 系/MD/YAML/XML 常用符号。
// 用途：结构弹层跳转、符号检索建议；精度对齐 IDE 语义为近似实现。

export interface OutlineSymbol {
  /** 符号名（方法名/类名/标题/键名） */
  name: string;
  /** 行号（1-based） */
  line: number;
  /** 类型徽标（class/interface/method/function/field/heading/key） */
  kind: string;
  /** 树深度（MD 标题层级/YAML 键层级） */
  depth: number;
}

const JAVA_VIS = String.raw`(?:public|protected|private|static|final|abstract|synchronized|native|transient|volatile|default|\s)*`;

/** 按语言解析大纲（扩展名驱动） */
export function parseOutline(content: string, path: string): OutlineSymbol[] {
  const lower = path.toLowerCase();
  const lines = content.split('\n');
  const out: OutlineSymbol[] = [];

  const push = (name: string, line: number, kind: string, depth = 0) =>
    out.push({ name, line: line + 1, kind, depth });

  if (/\.(java|kt|scala|cs|groovy)$/i.test(lower)) {
    lines.forEach((l, i) => {
      let m: RegExpExecArray | null;
      if ((m = /\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)/.exec(l))) push(m[1], i, 'class');
      else if ((m = new RegExp(`${JAVA_VIS}([A-Za-z_$][\\w$]*(?:<[^>()]*>)?(?:\\[\\])?)\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{]*\\)\\s*(?:throws [^{]+)?\\{`).exec(l)))
        push(m[2], i, 'method');
    });
    return out;
  }
  if (/\.(ts|tsx|js|jsx|mjs|cjs)$/i.test(lower)) {
    lines.forEach((l, i) => {
      let m: RegExpExecArray | null;
      if ((m = /\b(?:export\s+)?(?:default\s+)?(?:abstract\s+)?class\s+([A-Za-z_$][\w$]*)/.exec(l))) push(m[1], i, 'class');
      else if ((m = /\b(?:export\s+)?interface\s+([A-Za-z_$][\w$]*)/.exec(l))) push(m[1], i, 'interface');
      else if ((m = /\b(?:export\s+)?type\s+([A-Za-z_$][\w$]*)\s*=/.exec(l))) push(m[1], i, 'type');
      else if ((m = /\b(?:export\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)/.exec(l))) push(m[1], i, 'function');
      else if ((m = /\b(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:function|\([^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>)/.exec(l))) push(m[1], i, 'function');
      else if ((m = /^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=/.exec(l))) push(m[1], i, 'field');
    });
    return out;
  }
  if (/\.py$/i.test(lower)) {
    lines.forEach((l, i) => {
      const m = /^(\s*)(?:async\s+)?def\s+([A-Za-z_]\w*)|^(\s*)class\s+([A-Za-z_]\w*)/.exec(l);
      if (!m) return;
      const indent = (m[1] || m[3] || '').length;
      push(m[2] || m[4]!, i, m[2] ? 'function' : 'class', Math.floor(indent / 4));
    });
    return out;
  }
  if (/\.go$/i.test(lower)) {
    lines.forEach((l, i) => {
      let m: RegExpExecArray | null;
      if ((m = /^func\s+(?:\([^)]*\)\s*)?([A-Za-z_]\w*)/.exec(l))) push(m[1], i, 'function');
      else if ((m = /^type\s+([A-Za-z_]\w*)\s+(?:struct|interface)/.exec(l))) push(m[1], i, 'type');
    });
    return out;
  }
  if (/\.rs$/i.test(lower)) {
    lines.forEach((l, i) => {
      let m: RegExpExecArray | null;
      if ((m = /^\s*(?:pub\s+)?fn\s+([A-Za-z_]\w*)/.exec(l))) push(m[1], i, 'function');
      else if ((m = /^\s*(?:pub\s+)?(?:struct|enum|trait)\s+([A-Za-z_]\w*)/.exec(l))) push(m[1], i, 'class');
      else if ((m = /^\s*impl(?:<[^>]*>)?\s+([A-Za-z_:<>\w\s]+)/.exec(l))) push(m[1].trim(), i, 'impl');
    });
    return out;
  }
  if (/\.(c|cc|h|hpp|cpp)$/i.test(lower)) {
    lines.forEach((l, i) => {
      let m: RegExpExecArray | null;
      if ((m = new RegExp(`${JAVA_VIS}([A-Za-z_][\\w:\\s<>*&]*?)\\s+([A-Za-z_~][\\w]*)\\s*\\([^;]*\\)\\s*\\{`).exec(l))) push(m[2], i, 'function');
      else if ((m = /\b(?:class|struct)\s+([A-Za-z_]\w*)/.exec(l))) push(m[1], i, 'class');
    });
    return out;
  }
  if (/\.(md|markdown)$/i.test(lower)) {
    lines.forEach((l, i) => {
      const m = /^(#{1,6})\s+(.+?)\s*#*$/.exec(l);
      if (m) push(m[2].trim(), i, 'heading', m[1].length - 1);
    });
    return out;
  }
  if (/\.(ya?ml)$/i.test(lower)) {
    lines.forEach((l, i) => {
      const m = /^(\s*)([A-Za-z_][\w.\-]*)\s*:/.exec(l);
      if (m) push(m[2], i, 'key', Math.floor(m[1].length / 2));
    });
    return out;
  }
  if (/\.(xml|xsl|xsd|html?|vue)$/i.test(lower)) {
    lines.forEach((l, i) => {
      const m = /^\s*<([A-Za-z_][\w.:\-]*)/.exec(l);
      if (m && !/^(\?|!)/.test(m[1])) push(`<${m[1]}>`, i, 'tag');
    });
    return out;
  }
  // 通用：TODO/分隔注释不解析，返回空（结构视图显示"未识别符号"）
  return out;
}

/** 按语言猜测「符号声明行」grep 模式（Go to Symbol / 跳转定义 的项目内检索） */
export function declPatternOf(word: string, path: string): string {
  const lower = (path || '').toLowerCase();
  const w = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  if (/\.java$/i.test(lower)) return `(${w}\\s*\\(|class ${w}\\b|interface ${w}\\b|enum ${w}\\b)`;
  if (/\.(ts|tsx|js|jsx)$/i.test(lower)) return `(function ${w}\\b|const ${w}\\b|class ${w}\\b|interface ${w}\\b|export ${w}\\b)`;
  if (/\.py$/i.test(lower)) return `(def ${w}\\b|class ${w}\\b)`;
  if (/\.go$/i.test(lower)) return `(func ${w}\\b|type ${w}\\b)`;
  if (/\.rs$/i.test(lower)) return `(fn ${w}\\b|struct ${w}\\b|enum ${w}\\b|trait ${w}\\b)`;
  // 通用：任意声明形式
  return `(def ${w}\\b|function ${w}\\b|class ${w}\\b|interface ${w}\\b|const ${w}\\b|func ${w}\\b|${w}\\s*\\()`;
}
