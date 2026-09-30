/**
 * 行级 LCS diff（过程时间线编辑条目专用，零依赖轻量实现）。
 * 口径与后端 ReactAgentExecutor#diffLineCounts 一致：公共前后缀裁剪 +
 * 中段 LCS；中段规模超限时降级为整块删+增，避免 O(n²) 内存。
 */
export interface DiffRow {
  type: 'del' | 'add' | 'same';
  /** 旧文件行号（del/same 行有效） */
  oldNo: number;
  /** 新文件行号（add/same 行有效） */
  newNo: number;
  text: string;
}

export interface LineDiffResult {
  additions: number;
  deletions: number;
  rows: DiffRow[];
}

/** 中段 LCS 规模上限（≈800×800 单元格） */
const MAX_MIDDLE_CELLS = 640_000;

type DiffOp = { type: 'del' | 'add' | 'same'; text: string };

/** 行级差异：oldText → newText，行号 1 起算（startLine 为差异块在文件中的起始行） */
export function lineDiff(oldText: string, newText: string, startLine = 1): LineDiffResult {
  const a = oldText.split('\n');
  const b = newText.split('\n');
  let pre = 0;
  while (pre < a.length && pre < b.length && a[pre] === b[pre]) pre++;
  let sufA = a.length;
  let sufB = b.length;
  while (sufA > pre && sufB > pre && a[sufA - 1] === b[sufB - 1]) {
    sufA--;
    sufB--;
  }

  const rows: DiffRow[] = [];
  for (let i = 0; i < pre; i++) {
    rows.push({ type: 'same', oldNo: startLine + i, newNo: startLine + i, text: a[i] });
  }
  let oldNo = startLine + pre;
  let newNo = startLine + pre;
  for (const op of diffMiddle(a.slice(pre, sufA), b.slice(pre, sufB))) {
    if (op.type === 'del') rows.push({ type: 'del', oldNo: oldNo++, newNo, text: op.text });
    else if (op.type === 'add') rows.push({ type: 'add', oldNo, newNo: newNo++, text: op.text });
    else rows.push({ type: 'same', oldNo: oldNo++, newNo: newNo++, text: op.text });
  }
  for (let i = 0; sufA + i < a.length; i++) {
    rows.push({ type: 'same', oldNo: startLine + sufA + i, newNo: startLine + sufB + i, text: a[sufA + i] });
  }
  return {
    additions: rows.filter(r => r.type === 'add').length,
    deletions: rows.filter(r => r.type === 'del').length,
    rows,
  };
}

/** 中段差异：LCS 动态规划 + 回溯；空侧/超规模降级为整块删+增 */
function diffMiddle(a: string[], b: string[]): DiffOp[] {
  const m = a.length;
  const n = b.length;
  if (m === 0) return b.map(text => ({ type: 'add' as const, text }));
  if (n === 0) return a.map(text => ({ type: 'del' as const, text }));
  if (m * n > MAX_MIDDLE_CELLS) {
    return [
      ...a.map(text => ({ type: 'del' as const, text })),
      ...b.map(text => ({ type: 'add' as const, text })),
    ];
  }
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0));
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const ops: DiffOp[] = [];
  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (a[i] === b[j]) {
      ops.push({ type: 'same', text: a[i] });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      ops.push({ type: 'del', text: a[i++] });
    } else {
      ops.push({ type: 'add', text: b[j++] });
    }
  }
  while (i < m) ops.push({ type: 'del', text: a[i++] });
  while (j < n) ops.push({ type: 'add', text: b[j++] });
  return ops;
}
