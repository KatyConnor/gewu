/**
 * diff 展示块切分：把全文件 diff 行切成「hunk 行块 + 折叠块」序列。
 * GitHub unified diff 风格——变更行向前后各扩 N 行上下文，窗口外的
 * 连续未变行折叠为可展开块（审查视图用，行数据来自 lineDiff）。
 */
import type { DiffRow } from './lineDiff';

export type DiffBlock =
  | { kind: 'rows'; rows: DiffRow[] }
  | { kind: 'collapsed'; rows: DiffRow[]; count: number };

/** 上下文行数（与 GitHub 默认一致） */
export const DIFF_CONTEXT_LINES = 3;

/** 未变行游程超过该值才折叠（上下文 ×2 + 至少保留 4 行真实间隔） */
const COLLAPSE_THRESHOLD = DIFF_CONTEXT_LINES * 2 + 4;

/**
 * 切分展示块。相邻变更间距 ≤ 上下文×2 时自然合并为同一 hunk；
 * 未命中任何窗口的行必为 same 行，折叠成块并保留原行供展开。
 */
export function buildDiffHunks(rows: DiffRow[], contextLines = DIFF_CONTEXT_LINES): DiffBlock[] {
  const keep = new Array<boolean>(rows.length).fill(false);
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].type === 'same') continue;
    const from = Math.max(0, i - contextLines);
    const to = Math.min(rows.length - 1, i + contextLines);
    for (let j = from; j <= to; j++) {
      keep[j] = true;
    }
  }

  const blocks: DiffBlock[] = [];
  let i = 0;
  while (i < rows.length) {
    const start = i;
    if (keep[i]) {
      while (i < rows.length && keep[i]) i++;
      blocks.push({ kind: 'rows', rows: rows.slice(start, i) });
    } else {
      while (i < rows.length && !keep[i]) i++;
      blocks.push({ kind: 'collapsed', rows: rows.slice(start, i), count: i - start });
    }
  }
  // 无任何变更时不应出现折叠块（全文件未变没有审查意义），整体平铺
  if (blocks.length === 1 && blocks[0].kind === 'collapsed') {
    return [{ kind: 'rows', rows: blocks[0].rows }];
  }
  return blocks;
}
