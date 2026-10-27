'use client';
// 文件历史版本弹窗（IDEA Show History 风格）：
// 左列 = 最近提交（含未提交的工作区对比项），右侧 = MonacoDiff 左右对照（左=修改前，右=修改后）。
// 数据全部经沙箱 exec 的 git 命令获取（git log --follow / git show），无后端改动。
import { useCallback, useEffect, useRef, useState } from 'react';
import { Loader2 } from 'lucide-react';
import Modal from '@/components/ui/Modal';
import MonacoDiff from '@/components/pages/MonacoDiff';
import { execCommand } from '@/lib/dev-workspace';
import { monacoLangOf } from '@/lib/fileLanguage';

interface HistEntry {
  rev: string;
  author: string;
  time: string;
  message: string;
}

export default function DevFileHistoryModal({ open, project, path, worktreeContent, onClose }: {
  open: boolean;
  project: { projectId: string; localPath: string } | null;
  /** 文件卷内绝对路径（localPath/rel） */
  path: string | null;
  /** 工作区当前内容（用于「未提交的修改」对比项；null=该文件未打开过/不可比） */
  worktreeContent?: string | null;
  onClose: () => void;
}) {
  const [entries, setEntries] = useState<HistEntry[]>([]);
  const [dirtyFirst, setDirtyFirst] = useState(false);
  const [sel, setSel] = useState(-1); // -1 = 未提交的修改对比项
  const [diff, setDiff] = useState<{ original: string; modified: string } | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [diffLoading, setDiffLoading] = useState(false);
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
    setListLoading(true); setEntries([]); setSel(-1); setDiff(null);
    (async () => {
      try {
        const logOut = await git(`git -C /workspace/${project.localPath} log --follow -n 50 --format=%H%x09%an%x09%at%x09%s -- "${rel}"`);
        const list: HistEntry[] = logOut.split('\n').filter(Boolean).map(line => {
          const [rev, author, at, ...msg] = line.split('\t');
          return { rev, author, time: new Date(Number(at) * 1000).toLocaleString(), message: msg.join('\t') };
        });
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
            // rev-list --parents 无需重定向符号（exec 层 > < 会被 XSS 过滤转义）
            const parents = await git(`git -C /workspace/${project.localPath} rev-list --parents -n 1 ${e.rev}`);
            const parentRev = parents.trim().split(' ')[1];
            original = parentRev ? await git(`git -C /workspace/${project.localPath} show ${parentRev}:"${rel}"`) : '';
            modified = await git(`git -C /workspace/${project.localPath} show ${e.rev}:"${rel}"`);
          }
        }
        if (!cancelled) setDiff({ original, modified });
      } catch {
        if (!cancelled) setDiff({ original: '（版本内容读取失败）', modified: '' });
      } finally { if (!cancelled) setDiffLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [sel, dirtyFirst, open, project?.projectId, project?.localPath, path, entries, worktreeContent, git]);

  const selLabel = sel === -1 && dirtyFirst ? '工作区（未提交） vs HEAD'
    : entries[sel] ? `${entries[sel].message.slice(0, 40)} vs 上一版本` : '';

  return (
    <Modal open={open} onClose={onClose} zIndex="z-[75]" ariaLabel="文件历史版本"
      panelClassName="glass-dark rounded-xl w-full max-w-[1430px] h-[96vh] p-4 border border-tech-500/15 shadow-2xl flex flex-col">
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
          <div className="text-[10px] text-ink-500 mb-1.5 flex-shrink-0">{selLabel || '选择左侧版本查看差异'}</div>
          <div className="flex-1 min-h-0 border border-tech-500/10 rounded-lg overflow-hidden">
            {diffLoading ? (
              <div className="flex items-center justify-center h-full"><Loader2 className="w-4 h-4 text-tech-400 animate-spin" /></div>
            ) : diff ? (
              <MonacoDiff original={diff.original} modified={diff.modified} language={diffLang} path={path || 'diff'} />
            ) : (
              <div className="flex items-center justify-center h-full text-ink-500 text-xs">选择左侧版本查看差异</div>
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
}
