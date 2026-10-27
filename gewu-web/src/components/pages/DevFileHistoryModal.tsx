'use client';
// 文件历史版本弹窗（IDEA Show History 风格）：
// 左列 = 最近提交（含未提交的工作区对比项），右侧 = MonacoDiff 左右对照（左=修改前，右=修改后）。
// 数据全部经沙箱 exec 的 git 命令获取（git log --follow / git show），无后端改动。
import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, Loader2 } from 'lucide-react';
import Modal from '@/components/ui/Modal';
import MonacoDiff from '@/components/pages/MonacoDiff';
import { execCommand } from '@/lib/dev-workspace';
import { monacoLangOf } from '@/lib/fileLanguage';

interface HistEntry {
  rev: string;
  author: string;
  time: string;
  message: string;
  /** 该提交时文件所在路径（--follow 跟随改名，旧提交可能是旧路径；取内容必须用它） */
  filePath: string;
  /** 变更状态（A/M/D/R100…，来自 --name-status） */
  status: string;
}

export default function DevFileHistoryModal({ open, project, path, worktreeContent, theme, onClose }: {
  open: boolean;
  project: { projectId: string; localPath: string } | null;
  /** 文件卷内绝对路径（localPath/rel） */
  path: string | null;
  /** 工作区当前内容（用于「未提交的修改」对比项；null=该文件未打开过/不可比） */
  worktreeContent?: string | null;
  /** diff 编辑器主题（跟随代码编辑区选择，避免全局主题被 vs-dark 污染） */
  theme?: string;
  onClose: () => void;
}) {
  const [entries, setEntries] = useState<HistEntry[]>([]);
  const [dirtyFirst, setDirtyFirst] = useState(false);
  const [sel, setSel] = useState(-1); // -1 = 未提交的修改对比项
  const [diff, setDiff] = useState<{ original: string; modified: string } | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [diffLoading, setDiffLoading] = useState(false);
  const [emptyHint, setEmptyHint] = useState(false);
  /** diff 导航：变更块起点行列表（降序无关，升序存储）与 diff 编辑器实例 */
  const changeStartsRef = useRef<number[]>([]);
  const diffEdRef = useRef<{ getModifiedEditor: () => { revealLineInCenter(lineNumber: number): void; setPosition(p: { lineNumber: number; column: number }): void; getPosition(): { lineNumber: number } | null } } | null>(null);
  const diffLang = path ? monacoLangOf(path) : 'plaintext';

  const git = useCallback(async (command: string, timeout = 20) => {
    const r = await execCommand(command, timeout);
    return (r.stdout || '').replace(/\u0000/g, '');
  }, []);

  // 打开时拉取提交历史 + 判断工作区是否有未提交修改
  useEffect(() => {
    if (!open || !project || !path) return;
    const rel = path.slice((project.localPath || '').length + 1);
    let cancelled = false;
    setListLoading(true); setEntries([]); setSel(-1); setDiff(null); setEmptyHint(false); changeStartsRef.current = [];
    (async () => {
      try {
        // --follow --name-status：每条提交附带其当时的状态与文件路径（改名跟随），取内容必须用各自路径
        const logOut = await git(`git -C /workspace/${project.localPath} log --follow -n 50 --name-status --format=%H%x09%an%x09%at%x09%s -- "${rel}"`);
        const list: HistEntry[] = [];
        for (const line of logOut.split('\n')) {
          if (!line) continue;
          const tab = line.indexOf('\t');
          const first = tab >= 0 ? line.slice(0, tab) : line;
          // 格式行 = 首段恰为 40 位完整哈希；其余（无前导 TAB 的 R100/A/M/D 行）为该提交的状态行
          if (tab >= 0 && /^[0-9a-f]{40}$/.test(first)) {
            const [, author, at, ...msg] = line.split('\t');
            list.push({ rev: first, author, time: new Date(Number(at) * 1000).toLocaleString(), message: msg.join('\t'), filePath: rel, status: 'M' });
          } else {
            const cur = list[list.length - 1];
            if (!cur) continue;
            const parts = line.split('\t');
            cur.status = parts[0] || 'M';
            cur.filePath = parts[parts.length - 1] || rel; // R/C 为 old\tnew，取新路径
          }
        }
        const head = await git(`git -C /workspace/${project.localPath} show HEAD:"${rel}"`);
        if (!cancelled) {
          setEntries(list);
          const dirty = worktreeContent != null && head !== worktreeContent;
          setDirtyFirst(dirty);
          if (dirty) setSel(-1);
          else if (list.length > 0) setSel(0);
        }
      } catch { /* git 异常按空历史处理 */ }
      finally { if (!cancelled) setListLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [open, project?.projectId, project?.localPath, path, git]);

  // 选中项 → 左右版本内容（左=修改前，右=修改后）
  useEffect(() => {
    if (!open || !project || !path || sel < 0 && !dirtyFirst) return;
    const rel = path.slice((project.localPath || '').length + 1);
    let cancelled = false;
    setDiffLoading(true);
    (async () => {
      try {
        let original = '';
        let modified = '';
        if (sel === -1 && dirtyFirst) {
          original = await git(`git -C /workspace/${project.localPath} show HEAD:"${rel}"`);
          modified = worktreeContent ?? '';
        } else {
          const e = entries[sel];
          if (e) {
            const deleted = e.status.startsWith('D');
            // 右 = 本提交时该文件内容（用本条记录的当时路径）
            modified = deleted ? '' : await git(`git -C /workspace/${project.localPath} show ${e.rev}:"${e.filePath}"`);
            // 左 = 上一版本内容：优先取相邻下一条记录的路径（改名跟随），根提交/窗口末尾无父则左侧为空
            const next = entries[sel + 1];
            if (next) {
              const nextPath = next.filePath || e.filePath;
              original = next.status.startsWith('D') ? '' : await git(`git -C /workspace/${project.localPath} show ${next.rev}:"${nextPath}"`);
            } else {
              const parents = await git(`git -C /workspace/${project.localPath} rev-list --parents -n 1 ${e.rev}`);
              const parentRev = parents.trim().split(' ')[1];
              original = parentRev ? await git(`git -C /workspace/${project.localPath} show ${parentRev}:"${e.filePath}"`) : '';
            }
          }
        }
        // 两边都读不到内容 → 不渲染空 diff，给出明确提示
        if (!cancelled) {
          setEmptyHint(!original && !modified);
          if (!original && !modified) { setDiff(null); changeStartsRef.current = []; }
          else {
            setDiff({ original, modified });
            // 变更块起点（1 基行号）：供「上一处/下一处差异」导航
            const a = original.split('\n'), b = modified.split('\n');
            let st = 0;
            while (st < a.length && st < b.length && a[st] === b[st]) st++;
            let ae = a.length, be = b.length;
            while (ae > st && be > st && a[ae - 1] === b[be - 1]) { ae--; be--; }
            const A = a.slice(st, ae), B = b.slice(st, be);
            const starts: number[] = [];
            if (A.length * B.length > 4000000 || A.length === 0 || B.length === 0) {
              if (A.length !== B.length) starts.push(st + 1); // 整块改动/单侧增删：一处即可达
            } else {
              const m = A.length, n = B.length;
              const dp = new Uint32Array((m + 1) * (n + 1));
              for (let i = m - 1; i >= 0; i--) for (let j = n - 1; j >= 0; j--) dp[i * (n + 1) + j] = A[i] === B[j] ? dp[(i + 1) * (n + 1) + j + 1] + 1 : Math.max(dp[(i + 1) * (n + 1) + j], dp[i * (n + 1) + j + 1]);
              const changed = new Array(n).fill(false);
              let i = 0, j = 0;
              while (i < m && j < n) {
                if (A[i] === B[j]) { i++; j++; }
                else { changed[j] = true; if (dp[(i + 1) * (n + 1) + j] >= dp[i * (n + 1) + j + 1]) i++; else j++; }
              }
              while (j < n) { changed[j] = true; j++; }
              for (let k = 0; k < n; k++) if (changed[k] && (k === 0 || !changed[k - 1])) starts.push(st + k + 1);
            }
            changeStartsRef.current = starts;
          }
        }
      } catch {
        if (!cancelled) { setDiff(null); setEmptyHint(true); }
      } finally { if (!cancelled) setDiffLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [sel, dirtyFirst, open, project?.projectId, project?.localPath, path, entries, worktreeContent, git]);

  /** 跳转到上一处/下一处差异块（以右侧编辑器光标行为基准） */
  const gotoDiff = (dir: 1 | -1) => {
    const ed = diffEdRef.current?.getModifiedEditor?.();
    const starts = changeStartsRef.current;
    if (!ed || !starts.length) return;
    const cur = ed.getPosition()?.lineNumber ?? (dir === 1 ? 0 : Number.MAX_SAFE_INTEGER);
    const target = dir === 1 ? starts.find(l => l > cur) : [...starts].reverse().find(l => l < cur);
    if (target == null) return;
    ed.revealLineInCenter(target);
    ed.setPosition({ lineNumber: target, column: 1 });
  };

  const selLabel = sel === -1 && dirtyFirst ? '工作区（未提交） vs HEAD'
    : entries[sel] ? `${entries[sel].message.slice(0, 40)} vs 上一版本` : '';

  return (
    <Modal open={open} onClose={onClose} zIndex="z-[75]" ariaLabel="文件历史版本"
      panelClassName="glass-dark rounded-xl w-full max-w-[1573px] h-[96vh] p-4 border border-tech-500/15 shadow-2xl flex flex-col">
      <div className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="text-sm font-semibold text-ink-50 min-w-0 truncate">
          历史版本<span className="text-xs text-ink-500 font-normal ml-2">{path?.slice((project?.localPath || '').length + 1)}</span>
        </div>
        <button onClick={onClose} className="text-ink-500 hover:text-ink-200 text-xs">关闭</button>
      </div>
      <div className="flex-1 min-h-0 flex gap-3">
        {/* 左列：提交列表 */}
        <div className="w-60 flex-shrink-0 overflow-y-auto scrollbar-thin border-r border-tech-500/10 pr-2">
          {listLoading ? (
            <div className="flex items-center justify-center py-10"><Loader2 className="w-4 h-4 text-tech-400 animate-spin" /></div>
          ) : !dirtyFirst && entries.length === 0 ? (
            <div className="text-center py-8 text-ink-500 text-xs">该文件暂无提交历史</div>
          ) : (
            <div className="space-y-1">
              {dirtyFirst && (
                <button onClick={() => setSel(-1)}
                  className={`w-full text-left px-2 py-1.5 rounded-lg text-xs transition-all ${sel === -1 ? 'bg-tech-500/15 text-tech-400' : 'text-ink-300 hover:bg-tech-500/5'}`}>
                  <div className="font-medium">未提交的修改</div>
                  <div className="text-[10px] text-ink-500">工作区 vs HEAD</div>
                </button>
              )}
              {entries.map((e, i) => (
                <button key={e.rev} onClick={() => setSel(i)}
                  className={`w-full text-left px-2 py-1.5 rounded-lg text-xs transition-all ${sel === i ? 'bg-tech-500/15 text-tech-400' : 'text-ink-300 hover:bg-tech-500/5'}`}>
                  <div className="truncate font-medium">{e.message}</div>
                  <div className="text-[10px] text-ink-500 truncate">{e.author} · {e.time} · {e.rev.slice(0, 7)}</div>
                </button>
              ))}
            </div>
          )}
        </div>
        {/* 右侧：左右对照 diff */}
        <div className="flex-1 min-w-0 flex flex-col">
          <div className="text-[10px] text-ink-500 mb-1.5 flex-shrink-0 flex items-center gap-2">
            <span className="truncate">{selLabel || '选择左侧版本查看差异'}</span>
            <span className="ml-auto flex items-center gap-1 flex-shrink-0">
              <button onClick={() => gotoDiff(-1)} disabled={diffLoading || !diff || changeStartsRef.current.length === 0}
                className="p-1 text-ink-400 hover:text-tech-400 hover:bg-tech-500/10 rounded transition-all disabled:opacity-40" title="上一处差异">
                <ChevronUp className="w-3.5 h-3.5" />
              </button>
              <button onClick={() => gotoDiff(1)} disabled={diffLoading || !diff || changeStartsRef.current.length === 0}
                className="p-1 text-ink-400 hover:text-tech-400 hover:bg-tech-500/10 rounded transition-all disabled:opacity-40" title="下一处差异">
                <ChevronDown className="w-3.5 h-3.5" />
              </button>
            </span>
          </div>
          <div className="flex-1 min-h-0 border border-tech-500/10 rounded-lg overflow-hidden">
            {diffLoading ? (
              <div className="flex items-center justify-center h-full"><Loader2 className="w-4 h-4 text-tech-400 animate-spin" /></div>
            ) : diff ? (
              <MonacoDiff original={diff.original} modified={diff.modified} language={diffLang} path={path || 'diff'} theme={theme} onReady={(ed) => { diffEdRef.current = ed; }} />
            ) : emptyHint ? (
              <div className="flex items-center justify-center h-full text-ink-500 text-xs">该版本未读取到文件内容（可能为空文件，或文件路径在历史中变更且不在跟踪窗口内）</div>
            ) : (
              <div className="flex items-center justify-center h-full text-ink-500 text-xs">选择左侧版本查看差异</div>
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
}
