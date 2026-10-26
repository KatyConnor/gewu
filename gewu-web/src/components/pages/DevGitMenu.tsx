'use client';
import { useCallback, useEffect, useState } from 'react';
import {
  GitBranch, GitCommitHorizontal, ArrowDownToLine, ArrowUpFromLine, RefreshCw,
  Download, Archive, ArchiveRestore, Undo2, History, Loader2, ChevronDown, X,
  Tag as TagIcon, GitCompare, GitPullRequest,
} from 'lucide-react';
import Modal from '@/components/ui/Modal';
import { useToast } from '@/components/ui/Toast';
import { confirmDialog } from '@/components/ui/ConfirmDialog';
import {
  gitPull, gitFetch, gitStatus, gitLog, gitBranches, gitStashList,
  gitCommit, gitPush, gitCreateBranch, gitCheckout, gitDeleteBranch, gitMerge,
  gitStash, gitStashPop, gitRollback, gitFileDiff, readFile,
  listTags, createTag, deleteTag, compareBranches,
  listReflog, resetHard, cherryPick, revertCommit, downloadArchive,
  type GitProject, type GitStatus, type GitLogEntry, type GitBranches, type GitStash, type GitTag, type BranchCompare, type ReflogEntry,
} from '@/lib/dev-workspace';
import { listReleases, createRelease, uploadReleaseAsset, type VcsRelease } from '@/lib/vcs';
import DevCollabModal from './DevCollabModal';

/**
 * 开发工作空间「Git」菜单（IDEA Git 菜单复刻）：
 * 提交/更新项目/推送/获取/分支/暂存/回滚/历史，常规操作统一收口于顶部导航栏一个入口。
 * 快捷键对齐 IDEA：Ctrl+K 提交、Ctrl+T 更新项目、Ctrl+Shift+K 推送。
 */
interface Props {
  project: GitProject;
  /** 变更类操作完成后回调（刷新项目状态/文件树） */
  onRefresh: () => void | Promise<void>;
}

type DialogKind = 'commit' | 'push' | 'branches' | 'unstash' | 'rollback' | 'history' | 'tags' | 'compare' | 'collab' | 'reflog' | null;

const fmtTime = (epochSec?: number) => {
  if (!epochSec) return '';
  return new Date(epochSec * 1000).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
};

export default function DevGitMenu({ project, onRefresh }: Props) {
  const toast = useToast();
  const [menuOpen, setMenuOpen] = useState(false);
  const [dialog, setDialog] = useState<DialogKind>(null);
  const [busy, setBusy] = useState<string | null>(null); // 正在执行的操作标签
  const [status, setStatus] = useState<GitStatus | null>(null);

  // 提交对话框
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [commitMsg, setCommitMsg] = useState('');
  const [preview, setPreview] = useState<{ path: string; text: string; isNewFile: boolean } | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  // 推送/历史
  const [logs, setLogs] = useState<GitLogEntry[]>([]);
  // 分支
  const [branchData, setBranchData] = useState<GitBranches | null>(null);
  const [newBranch, setNewBranch] = useState('');
  // stash
  const [stashList, setStashList] = useState<GitStash[]>([]);
  const [stashMsg, setStashMsg] = useState('');
  // 回滚
  const [rbSelected, setRbSelected] = useState<Set<string>>(new Set());
  // 标签 / 分支对比 / 协作面板
  const [tags, setTags] = useState<GitTag[] | null>(null);
  const [tagForm, setTagForm] = useState({ name: '', message: '', push: true });
  const [cmpBranches, setCmpBranches] = useState<GitBranches | null>(null);
  const [cmpBase, setCmpBase] = useState('');
  const [cmpResult, setCmpResult] = useState<BranchCompare | null>(null);
  const [collabOpen, setCollabOpen] = useState(false);
  const [releases, setReleases] = useState<VcsRelease[] | null>(null);
  const [reflogList, setReflogList] = useState<ReflogEntry[] | null>(null);

  const projectId = project.projectId;

  const refreshStatus = useCallback(async (silent = true) => {
    try {
      setStatus(await gitStatus(projectId));
    } catch (e) {
      if (!silent) toast('状态获取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  }, [projectId, toast]);

  useEffect(() => { setStatus(null); refreshStatus(); }, [refreshStatus]);

  /** 变更类操作完成后的统一收尾 */
  const afterChange = useCallback(async (msg: string) => {
    toast(msg, 'success');
    await Promise.all([refreshStatus(false), Promise.resolve(onRefresh())]);
  }, [onRefresh, refreshStatus, toast]);

  const run = async (tag: string, fn: () => Promise<unknown>, okMsg: string, done?: () => void | Promise<void>) => {
    if (busy) return;
    setBusy(tag);
    try {
      await fn();
      await afterChange(okMsg);
      await done?.();
    } catch (e) {
      toast(`${okMsg.split('成功')[0]}失败: ` + (e instanceof Error ? e.message : String(e)), 'error');
      await refreshStatus(false).catch(() => undefined);
    } finally {
      setBusy(null);
    }
  };

  // ==================== 直接执行类菜单项 ====================

  const doUpdate = () => run('update', () => gitPull(projectId), '更新成功');
  const doFetch = () => run('fetch', () => gitFetch(projectId), '获取成功');
  const doStashNow = () => run('stash', () => gitStash(projectId, ''), '暂存成功');

  // ==================== 对话框装载 ====================

  const openDialog = async (kind: DialogKind) => {
    setMenuOpen(false);
    setDialog(kind);
    if (kind === 'commit' || kind === 'rollback') {
      const st = await gitStatus(projectId).catch(() => null);
      if (st) setStatus(st);
      setCommitMsg('');
      setSelected(new Set((st?.changes || []).map(c => c.path)));
      setRbSelected(new Set((st?.changes || []).map(c => c.path)));
      setPreview(null);
    } else if (kind === 'push' || kind === 'history') {
      setLogs(await gitLog(projectId, kind === 'push' ? 10 : 100).catch(() => []));
      if (kind === 'push') refreshStatus(false).catch(() => undefined);
    } else if (kind === 'branches' || kind === 'compare') {
      const bs = await gitBranches(projectId).catch(() => null);
      setBranchData(bs);
      setCmpBranches(bs);
      setNewBranch('');
      if (kind === 'compare') {
        setCmpBase('');
        setCmpResult(null);
      }
    } else if (kind === 'tags') {
      setTags(await listTags(projectId).catch(() => []));
      setReleases(await listReleases(projectId).catch(() => []));
      setTagForm({ name: '', message: '', push: true });
    } else if (kind === 'reflog') {
      setReflogList(await listReflog(projectId).catch(() => []));
    } else if (kind === 'unstash') {
      setStashList(await gitStashList(projectId).catch(() => []));
      setStashMsg('');
    }
  };

  // 快捷键（IDEA 对齐）：Ctrl+K 提交 / Ctrl+T 更新 / Ctrl+Shift+K 推送
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
      const k = e.key.toLowerCase();
      if (k === 'k' && !e.shiftKey) { e.preventDefault(); if (!dialog) openDialog('commit'); }
      else if (k === 't' && !e.shiftKey) { e.preventDefault(); if (!dialog) doUpdate(); }
      else if (k === 'k' && e.shiftKey) { e.preventDefault(); if (!dialog) openDialog('push'); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dialog, busy, projectId]);

  // ==================== 弹框动作 ====================

  const loadPreview = async (path: string, untracked: boolean) => {
    setPreviewLoading(true);
    try {
      if (untracked) {
        const content = await readFile(`${project.localPath}/${path}`);
        setPreview({ path, text: content, isNewFile: true });
      } else {
        setPreview({ path, text: await gitFileDiff(projectId, path), isNewFile: false });
      }
    } catch (e) {
      setPreview({ path, text: '差异加载失败: ' + (e instanceof Error ? e.message : String(e)), isNewFile: false });
    } finally {
      setPreviewLoading(false);
    }
  };

  const doCommit = (alsoPush: boolean) => run('commit', async () => {
    await gitCommit(projectId, { message: commitMsg.trim(), files: Array.from(selected) });
    if (alsoPush) await gitPush(projectId);
  }, alsoPush ? '提交并推送成功' : '提交成功', () => setDialog(null));

  const doPush = () => run('push', () => gitPush(projectId), '推送成功', () => setDialog(null));

  const doBranchAction = async (action: 'checkout' | 'merge' | 'delete', name: string) => {
    if (action === 'delete' && !(await confirmDialog({ message: `确认删除本地分支 ${name}？（未合入当前分支的会被拒绝）`, danger: true }))) return;
    if (action === 'merge' && !(await confirmDialog({ message: `确认将 ${name} 合并到当前分支？` }))) return;
    run(`branch-${action}`, async () => {
      if (action === 'checkout') await gitCheckout(projectId, name);
      else if (action === 'merge') await gitMerge(projectId, name);
      else await gitDeleteBranch(projectId, name);
    }, action === 'checkout' ? `已切换到 ${name}` : action === 'merge' ? `合并 ${name} 成功` : `分支 ${name} 已删除`,
      async () => { setBranchData(await gitBranches(projectId).catch(() => null)); });
  };

  const doCreateBranch = () => {
    const name = newBranch.trim();
    if (!name) return;
    run('branch-create', () => gitCreateBranch(projectId, name, true), `已创建并切换到 ${name}`,
      async () => {
        setNewBranch('');
        setBranchData(await gitBranches(projectId).catch(() => null));
      });
  };

  const doStash = () => run('stash', () => gitStash(projectId, stashMsg.trim() || undefined), '暂存成功',
    async () => { setStashList(await gitStashList(projectId).catch(() => [])); setStashMsg(''); });

  const doStashPop = (index: number) => run(`stash-pop-${index}`, () => gitStashPop(projectId, index), '弹出成功',
    async () => { setStashList(await gitStashList(projectId).catch(() => [])); });

  // ==================== 标签 / 分支对比 ====================

  const doCreateTag = () => {
    if (!tagForm.name.trim() || !tagForm.message.trim()) return;
    run('tag-create', () => createTag(projectId, tagForm.name.trim(), tagForm.message.trim(), tagForm.push),
      `标签 ${tagForm.name.trim()} 已创建${tagForm.push ? '并推送' : ''}`,
      async () => {
        setTags(await listTags(projectId).catch(() => []));
        setTagForm({ name: '', message: '', push: true });
      });
  };

  const doPublishRelease = (tagName: string) => {
    const name = prompt(`Release 名称（基于 tag ${tagName}）:`, tagName);
    if (name === null) return;
    const notes = prompt('Release 说明（可空）:', '') ?? '';
    run(`release-${tagName}`, () => createRelease(projectId, { tagName, name: name.trim() || tagName, notes }),
      `Release ${name.trim() || tagName} 已创建`,
      async () => setReleases(await listReleases(projectId).catch(() => [])));
  };

  const doCherryPick = async (hash: string, message: string) => {
    if (!(await confirmDialog({ message: `摘取提交 ${message || hash} 到当前分支？` }))) return;
    run(`cp-${hash}`, () => cherryPick(projectId, hash), '摘取成功', async () => {
      await onRefresh();
    });
  };

  const doRevert = async (hash: string, message: string) => {
    if (!(await confirmDialog({ message: `撤销提交 ${message || hash}？（生成反向提交，不改历史）` }))) return;
    run(`rv-${hash}`, () => revertCommit(projectId, hash), '撤销成功', async () => {
      await onRefresh();
    });
  };

  const doExport = async (hash: string) => {
    try {
      const p = await downloadArchive(projectId, hash);
      toast(`已导出归档: ${p}`, 'success');
    } catch (e) {
      toast('归档导出失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const doResetHard = async (selector: string, sha: string, subject: string) => {
    if (!(await confirmDialog({ message: `危险操作：将工作区与分支强制回退到 ${selector}（${subject}）？\n未提交的修改将全部丢失！`, danger: true, confirmText: '确认硬重置' }))) return;
    if (!(await confirmDialog({ message: '再次确认：确定硬重置？', danger: true, confirmText: '确认硬重置' }))) return;
    run(`reset-${sha}`, () => resetHard(projectId, sha), '已硬重置', async () => {
      setReflogList(await listReflog(projectId).catch(() => []));
      await onRefresh();
    });
  };

  const doUploadAsset = (tagName: string) => {
    const sandboxPath = prompt(`附件的工作空间路径（沙箱内文件，如 projects/${projectId}/repo/dist/app.jar）:`);
    if (!sandboxPath) return;
    run(`asset-${tagName}`, () => uploadReleaseAsset(projectId, tagName, sandboxPath.trim()),
      `附件已上传到 Release ${tagName}`);
  };

  const doDeleteTag = async (name: string) => {
    if (!(await confirmDialog({ message: `确认删除标签 ${name}？`, danger: true }))) return;
    run(`tag-del-${name}`, () => deleteTag(projectId, name, true), `标签 ${name} 已删除`,
      async () => setTags(await listTags(projectId).catch(() => [])));
  };

  const doCompare = () => {
    if (!cmpBase.trim()) return;
    run('compare', () => compareBranches(projectId, cmpBase.trim(), status?.branch || project.repoBranch),
      '对比完成', () => undefined);
  };

  const doRollback = async () => {
    const st = status;
    if (!st) return;
    const tracked: string[] = [];
    const untracked: string[] = [];
    st.changes.filter(c => rbSelected.has(c.path)).forEach(c => {
      // IDEA Rollback 语义：新文件（未跟踪/暂存新增）从磁盘清除，其余还原到 HEAD
      if (c.untracked || (c.staged && c.statusDesc === '新增')) untracked.push(c.path);
      else tracked.push(c.path);
    });
    if (tracked.length + untracked.length === 0) return;
    if (!(await confirmDialog({ message: `确认回滚 ${tracked.length + untracked.length} 个文件的更改？此操作不可撤销。`, danger: true }))) return;
    run('rollback', () => gitRollback(projectId, tracked, untracked), '回滚成功', () => setDialog(null));
  };

  // ==================== 渲染 ====================

  const changes = status?.changes || [];
  const dirtyCount = changes.length;

  const menuItem = (label: string, icon: React.ReactNode, onClick: () => void, kbd?: string, tag?: string, danger = false) => (
    <button onClick={onClick} disabled={!!busy}
      className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm rounded-lg transition-all disabled:opacity-50 ${danger ? 'text-cinnabar-300 hover:bg-cinnabar-500/10' : 'text-ink-200 hover:bg-tech-500/10 hover:text-tech-400'}`}>
      {busy === tag ? <Loader2 className="w-4 h-4 animate-spin flex-shrink-0" /> : <span className="flex-shrink-0 flex">{icon}</span>}
      <span className="flex-1 text-left">{label}</span>
      {kbd && <kbd className="text-[10px] text-ink-500 border border-ink-700/60 rounded px-1.5 py-0.5 font-mono">{kbd}</kbd>}
    </button>
  );

  const modalShell = 'glass-dark rounded-2xl w-full p-5 shadow-2xl border border-tech-500/10';

  return (
    <div className="relative flex-shrink-0">
      {menuOpen && <div className="fixed inset-0 z-20" onClick={() => setMenuOpen(false)} />}
      <button onClick={() => { setMenuOpen(v => !v); if (!menuOpen) refreshStatus().catch(() => undefined); }}
        className={`relative flex items-center gap-1.5 px-2.5 py-1.5 text-xs rounded-lg border transition-all ${
          menuOpen ? 'border-tech-500/30 bg-tech-500/15 text-tech-400' : 'border-ink-700/60 text-ink-200 hover:text-tech-400 hover:border-tech-500/30'
        }`}
        title="Git 操作菜单">
        <GitBranch className="w-3.5 h-3.5" />Git
        {dirtyCount > 0 && (
          <span className="absolute -top-1.5 -right-1.5 min-w-[16px] h-4 px-1 rounded-full bg-tech-500 text-white text-[9px] font-semibold flex items-center justify-center">{dirtyCount}</span>
        )}
        <ChevronDown className={`w-3 h-3 text-ink-500 transition-transform ${menuOpen ? 'rotate-180' : ''}`} />
      </button>

      {menuOpen && (
        <div className="absolute right-0 top-full z-30 mt-1.5 glass-dark rounded-xl border border-tech-500/15 shadow-2xl p-1.5 w-60">
          <div className="px-3 py-1.5 mb-1 border-b border-tech-500/10 flex items-center justify-between text-[10px] text-ink-500">
            <span className="font-mono truncate">{status?.branch || project.repoBranch || '-'}</span>
            <span>{dirtyCount > 0 ? `${dirtyCount} 处更改` : '无更改'}</span>
          </div>
          {menuItem('提交…', <GitCommitHorizontal className="w-4 h-4" />, () => openDialog('commit'), 'Ctrl+K', 'commit-open')}
          {menuItem('更新项目', <ArrowDownToLine className="w-4 h-4" />, doUpdate, 'Ctrl+T', 'update')}
          {menuItem('推送…', <ArrowUpFromLine className="w-4 h-4" />, () => openDialog('push'), 'Ctrl+Shift+K', 'push-open')}
          {menuItem('获取', <Download className="w-4 h-4" />, doFetch, undefined, 'fetch')}
          <div className="h-px bg-tech-500/10 my-1" />
          {menuItem('合并请求…', <GitPullRequest className="w-4 h-4" />, () => { setMenuOpen(false); setCollabOpen(true); }, undefined, 'collab-open')}
          {menuItem('分支…', <GitBranch className="w-4 h-4" />, () => openDialog('branches'), undefined, 'branches-open')}
          {menuItem('暂存更改', <Archive className="w-4 h-4" />, doStashNow, undefined, 'stash')}
          {menuItem('取消暂存…', <ArchiveRestore className="w-4 h-4" />, () => openDialog('unstash'), undefined, 'unstash-open')}
          <div className="h-px bg-tech-500/10 my-1" />
          {menuItem('回滚…', <Undo2 className="w-4 h-4" />, () => openDialog('rollback'), undefined, 'rollback-open')}
          {menuItem('标签…', <TagIcon className="w-4 h-4" />, () => openDialog('tags'), undefined, 'tags-open')}
          {menuItem('分支对比…', <GitCompare className="w-4 h-4" />, () => openDialog('compare'), undefined, 'compare-open')}
          {menuItem('显示历史', <History className="w-4 h-4" />, () => openDialog('history'), undefined, 'history-open')}
          {menuItem('恢复…', <Undo2 className="w-4 h-4" />, () => openDialog('reflog'), undefined, 'reflog-open')}
        </div>
      )}

      {/* ============ 提交对话框（IDEA Commit：变更勾选 + 差异预览 + 提交信息） ============ */}
      <Modal open={dialog === 'commit'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-3xl max-h-[85vh] flex flex-col`} ariaLabel="提交更改">
        <div className="flex items-center justify-between mb-3 flex-shrink-0">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><GitCommitHorizontal className="w-4 h-4 text-tech-400" />提交更改 · {status?.branch}</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <div className="flex gap-3 flex-1 min-h-0">
          <div className="w-72 flex-shrink-0 flex flex-col min-h-0">
            <div className="flex items-center justify-between text-[10px] text-ink-500 mb-1.5 px-1">
              <span>变更文件（{changes.length}）</span>
              <button className="text-tech-400 hover:underline"
                onClick={() => setSelected(new Set(selected.size === changes.length ? [] : changes.map(c => c.path)))}>
                {selected.size === changes.length ? '取消全选' : '全选'}
              </button>
            </div>
            <div className="flex-1 min-h-0 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-1">
              {changes.length === 0 && <div className="text-center py-8 text-xs text-ink-500">没有更改</div>}
              {changes.map(c => {
                const checked = selected.has(c.path);
                return (
                  <button key={c.path} onClick={() => {
                    const next = new Set(selected);
                    if (checked) next.delete(c.path); else next.add(c.path);
                    setSelected(next);
                    loadPreview(c.path, c.untracked);
                  }} title={c.renameFrom ? `${c.renameFrom} → ${c.path}` : c.path}
                    className={`w-full flex items-center gap-2 px-2 py-1.5 rounded text-left transition-all ${preview?.path === c.path ? 'bg-tech-500/15' : 'hover:bg-tech-500/5'}`}>
                    <span className={`w-3.5 h-3.5 rounded border flex-shrink-0 flex items-center justify-center text-[8px] ${checked ? 'bg-tech-500 border-tech-500 text-white' : 'border-ink-600'}`}>{checked ? '✓' : ''}</span>
                    <span className={`text-[10px] px-1 rounded flex-shrink-0 ${c.untracked ? 'bg-gold-500/15 text-gold-400' : c.staged ? 'bg-green-500/15 text-green-400' : 'bg-tech-500/15 text-tech-400'}`}>{c.statusDesc}</span>
                    <span className="text-xs text-ink-200 truncate flex-1">{c.path}</span>
                  </button>
                );
              })}
            </div>
          </div>
          <div className="flex-1 min-w-0 flex flex-col gap-2">
            <div className="text-[10px] text-ink-500 px-1 flex items-center justify-between">
              <span>差异预览{preview ? ` · ${preview.path}` : ''}</span>
              {preview?.isNewFile && <span className="text-gold-400">新文件（全文）</span>}
            </div>
            <div className="flex-1 min-h-0 overflow-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-3">
              {previewLoading ? <div className="flex justify-center py-8"><Loader2 className="w-4 h-4 text-tech-400 animate-spin" /></div>
                : preview ? <pre className="text-[11px] font-mono text-ink-300 whitespace-pre-wrap break-all">{preview.text || '（无差异）'}</pre>
                : <div className="text-center py-8 text-xs text-ink-500">点击左侧文件查看差异</div>}
            </div>
            <textarea value={commitMsg} onChange={e => setCommitMsg(e.target.value)} rows={2} placeholder="提交信息（Commit message）"
              className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 resize-none flex-shrink-0" />
          </div>
        </div>
        <div className="flex justify-end gap-2 mt-3 flex-shrink-0">
          <button onClick={() => setDialog(null)} className="px-4 py-1.5 text-sm text-ink-300 hover:text-ink-100">取消</button>
          <button onClick={() => doCommit(true)} disabled={busy !== null || selected.size === 0 || !commitMsg.trim()}
            className="px-4 py-1.5 text-sm text-ink-200 border border-ink-700/60 rounded-lg hover:text-tech-400 disabled:opacity-50 flex items-center gap-1.5">
            {busy === 'commit' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArrowUpFromLine className="w-3.5 h-3.5" />}提交并推送
          </button>
          <button onClick={() => doCommit(false)} disabled={busy !== null || selected.size === 0 || !commitMsg.trim()}
            className="px-5 py-1.5 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-1.5">
            {busy === 'commit' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <GitCommitHorizontal className="w-3.5 h-3.5" />}提交
          </button>
        </div>
      </Modal>

      {/* ============ 推送对话框（待推送提交列表） ============ */}
      <Modal open={dialog === 'push'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-lg`} ariaLabel="推送">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><ArrowUpFromLine className="w-4 h-4 text-tech-400" />推送 · {status?.branch || '-'}</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <p className="text-xs text-ink-400 mb-2">
          {status?.upstream
            ? `领先 ${status.ahead} 个提交${status.behind > 0 ? `，落后 ${status.behind} 个（建议先更新项目）` : ''}`
            : '当前分支尚无上游，推送时将自动建立跟踪'}
        </p>
        <div className="max-h-56 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-2 space-y-1">
          {logs.length === 0 && <div className="text-center py-6 text-xs text-ink-500">暂无提交记录</div>}
          {logs.map(l => (
            <div key={l.hash} className="flex items-center gap-2 text-xs">
              <span className="font-mono text-tech-400 flex-shrink-0">{l.shortHash}</span>
              <span className="text-ink-200 truncate flex-1" title={l.message}>{l.message}</span>
              <span className="text-ink-600 flex-shrink-0 text-[10px]">{l.author} · {fmtTime(l.timestamp)}</span>
            </div>
          ))}
        </div>
        <div className="flex justify-end gap-2 mt-4">
          <button onClick={() => setDialog(null)} className="px-4 py-1.5 text-sm text-ink-300 hover:text-ink-100">取消</button>
          <button onClick={doPush} disabled={busy !== null}
            className="px-5 py-1.5 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-1.5">
            {busy === 'push' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArrowUpFromLine className="w-3.5 h-3.5" />}推送
          </button>
        </div>
      </Modal>

      {/* ============ 分支对话框（列表/检出/合并/删除/新建） ============ */}
      <Modal open={dialog === 'branches'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-md`} ariaLabel="分支管理">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><GitBranch className="w-4 h-4 text-tech-400" />分支 · {branchData?.current || '-'}</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <div className="flex gap-2 mb-3">
          <input value={newBranch} onChange={e => setNewBranch(e.target.value)} placeholder="新分支名（如 feature/xxx）"
            className="flex-1 px-3 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" />
          <button onClick={doCreateBranch} disabled={!newBranch.trim() || busy !== null}
            className="px-3 py-1.5 btn-primary text-white text-xs rounded-lg disabled:opacity-50 flex items-center gap-1">
            {busy === 'branch-create' ? <Loader2 className="w-3 h-3 animate-spin" /> : null}创建并检出
          </button>
        </div>
        <div className="max-h-72 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-1.5 space-y-0.5">
          {branchData?.local.map(b => (
            <div key={b} className={`flex items-center gap-2 px-2 py-1.5 rounded ${b === branchData.current ? 'bg-tech-500/15' : 'hover:bg-tech-500/5'}`}>
              <GitBranch className={`w-3.5 h-3.5 flex-shrink-0 ${b === branchData.current ? 'text-tech-400' : 'text-ink-500'}`} />
              <span className={`text-sm truncate flex-1 ${b === branchData.current ? 'text-tech-400' : 'text-ink-200'}`}>{b}</span>
              {b === branchData.current ? (
                <span className="text-[10px] text-tech-400 flex-shrink-0">当前</span>
              ) : (
                <div className="flex items-center gap-1.5 flex-shrink-0 text-[11px]">
                  <button onClick={() => doBranchAction('checkout', b)} disabled={!!busy} className="text-ink-300 hover:text-tech-400 disabled:opacity-50">检出</button>
                  <button onClick={() => doBranchAction('merge', b)} disabled={!!busy} className="text-ink-300 hover:text-gold-400 disabled:opacity-50">合并</button>
                  <button onClick={() => doBranchAction('delete', b)} disabled={!!busy} className="text-ink-500 hover:text-cinnabar-400 disabled:opacity-50">删除</button>
                </div>
              )}
            </div>
          ))}
          {branchData && branchData.local.length === 0 && <div className="text-center py-6 text-xs text-ink-500">无其他本地分支</div>}
        </div>
        {branchData && branchData.remote.length > 0 && (
          <div className="mt-2 text-[10px] text-ink-600 px-1 truncate" title={branchData.remote.join('  ')}>
            远程：{branchData.remote.join('、')}
          </div>
        )}
      </Modal>

      {/* ============ 取消暂存（stash）对话框 ============ */}
      <Modal open={dialog === 'unstash'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-md`} ariaLabel="取消暂存">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><ArchiveRestore className="w-4 h-4 text-tech-400" />取消暂存</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <div className="max-h-64 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-1.5 space-y-0.5 mb-3">
          {stashList.length === 0 && <div className="text-center py-6 text-xs text-ink-500">无暂存记录</div>}
          {stashList.map(s => (
            <div key={s.index} className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-tech-500/5">
              <span className="font-mono text-[10px] text-ink-500 flex-shrink-0">{'stash@{'}{s.index}{'}'}</span>
              <span className="text-xs text-ink-200 truncate flex-1" title={s.message}>{s.message || '（无备注）'}</span>
              <button onClick={async () => { if (await confirmDialog({ message: `弹出 stash@{${s.index}} 到当前工作区？` })) doStashPop(s.index); }}
                disabled={!!busy} className="text-[11px] text-ink-300 hover:text-tech-400 disabled:opacity-50 flex items-center gap-1 flex-shrink-0">
                {busy === `stash-pop-${s.index}` ? <Loader2 className="w-3 h-3 animate-spin" /> : null}弹出
              </button>
            </div>
          ))}
        </div>
        <div className="flex gap-2">
          <input value={stashMsg} onChange={e => setStashMsg(e.target.value)} placeholder="暂存备注（可选）"
            className="flex-1 px-3 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" />
          <button onClick={doStash} disabled={!!busy || dirtyCount === 0}
            className="px-3 py-1.5 border border-ink-700/60 rounded-lg text-xs text-ink-200 hover:text-tech-400 disabled:opacity-50 flex items-center gap-1.5 flex-shrink-0">
            {busy === 'stash' ? <Loader2 className="w-3 h-3 animate-spin" /> : <Archive className="w-3 h-3" />}暂存当前更改{dirtyCount > 0 ? `（${dirtyCount}）` : ''}
          </button>
        </div>
      </Modal>

      {/* ============ 回滚对话框 ============ */}
      <Modal open={dialog === 'rollback'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-md`} ariaLabel="回滚更改">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><Undo2 className="w-4 h-4 text-cinnabar-400" />回滚更改</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <p className="text-[11px] text-cinnabar-300/80 mb-2 px-1">修改/删除将还原到 HEAD；新文件将从磁盘删除。此操作不可撤销。</p>
        <div className="max-h-64 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-1.5">
          {changes.length === 0 && <div className="text-center py-6 text-xs text-ink-500">没有可回滚的更改</div>}
          {changes.map(c => {
            const checked = rbSelected.has(c.path);
            return (
              <button key={c.path} onClick={() => {
                const next = new Set(rbSelected);
                if (checked) next.delete(c.path); else next.add(c.path);
                setRbSelected(next);
              }} className="w-full flex items-center gap-2 px-2 py-1.5 rounded text-left hover:bg-tech-500/5">
                <span className={`w-3.5 h-3.5 rounded border flex-shrink-0 flex items-center justify-center text-[8px] ${checked ? 'bg-cinnabar-500 border-cinnabar-500 text-white' : 'border-ink-600'}`}>{checked ? '✓' : ''}</span>
                <span className="text-[10px] px-1 rounded bg-tech-500/15 text-tech-400 flex-shrink-0">{c.statusDesc}</span>
                <span className="text-xs text-ink-200 truncate flex-1 text-left" title={c.path}>{c.path}</span>
              </button>
            );
          })}
        </div>
        <div className="flex justify-end gap-2 mt-4">
          <button onClick={() => setDialog(null)} className="px-4 py-1.5 text-sm text-ink-300 hover:text-ink-100">取消</button>
          <button onClick={doRollback} disabled={busy !== null || rbSelected.size === 0}
            className="px-5 py-1.5 bg-cinnabar-500/90 hover:bg-cinnabar-500 text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-1.5">
            {busy === 'rollback' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Undo2 className="w-3.5 h-3.5" />}回滚（{rbSelected.size}）
          </button>
        </div>
      </Modal>

      {/* ============ 标签管理对话框 ============ */}
      <Modal open={dialog === 'tags'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-md`} ariaLabel="标签管理">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><TagIcon className="w-4 h-4 text-tech-400" />标签 · {status?.branch || project.repoBranch}</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <div className="flex gap-2 mb-3">
          <input value={tagForm.name} onChange={e => setTagForm(f => ({ ...f, name: e.target.value }))} placeholder="标签名（如 v1.0.0）"
            className="w-32 px-2.5 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none focus:border-tech-500/30" />
          <input value={tagForm.message} onChange={e => setTagForm(f => ({ ...f, message: e.target.value }))} placeholder="标签说明"
            className="flex-1 px-2.5 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none focus:border-tech-500/30" />
          <label className="flex items-center gap-1 text-[11px] text-ink-400 flex-shrink-0">
            <input type="checkbox" checked={tagForm.push} onChange={e => setTagForm(f => ({ ...f, push: e.target.checked }))} />推送
          </label>
          <button onClick={doCreateTag} disabled={!tagForm.name.trim() || !tagForm.message.trim() || busy !== null}
            className="px-3 py-1.5 btn-primary text-white text-xs rounded-lg disabled:opacity-50 flex items-center gap-1 flex-shrink-0">
            {busy === 'tag-create' ? <Loader2 className="w-3 h-3 animate-spin" /> : null}创建
          </button>
        </div>
        <div className="max-h-64 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-1.5 space-y-0.5">
          {tags !== null && tags.length === 0 && <div className="text-center py-6 text-xs text-ink-500">无标签</div>}
          {tags !== null && tags.map(t => (
            <div key={t.name} className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-tech-500/5">
              <TagIcon className="w-3.5 h-3.5 text-gold-400 flex-shrink-0" />
              <span className="text-sm text-ink-200 flex-1 truncate">{t.name}</span>
              <span className="text-[10px] text-ink-600 flex-shrink-0">{t.date} · {t.sha}</span>
              <button onClick={() => doPublishRelease(t.name)} disabled={!!busy}
                className="text-[11px] text-ink-500 hover:text-tech-400 disabled:opacity-50 flex-shrink-0" title="基于此 tag 创建 Release">发布</button>
              <button onClick={() => doDeleteTag(t.name)} disabled={!!busy}
                className="text-[11px] text-ink-600 hover:text-cinnabar-400 disabled:opacity-50 flex-shrink-0">删除</button>
            </div>
          ))}
        </div>
        <div className="mt-3 pt-2 border-t border-tech-500/10">
          <p className="text-[10px] text-ink-500 mb-1 px-1">已发布 Release</p>
          {releases !== null && releases.length === 0 && <p className="text-[10px] text-ink-600 px-1">（无）</p>}
          {releases !== null && releases.map(r => (
            <div key={r.tagName} className="flex items-center gap-2 px-1 py-0.5 text-[11px]">
              <span className="text-gold-400 flex-shrink-0">{r.tagName}</span>
              <span className="text-ink-300 truncate flex-1">{r.name}</span>
              {r.webUrl && <a href={r.webUrl} target="_blank" rel="noreferrer" className="text-ink-600 hover:text-tech-400 flex-shrink-0">打开</a>}
            </div>
          ))}
        </div>
      </Modal>

      {/* ============ 分支对比对话框 ============ */}
      <Modal open={dialog === 'compare'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-xl max-h-[80vh] flex flex-col`} ariaLabel="分支对比">
        <div className="flex items-center justify-between mb-3 flex-shrink-0">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><GitCompare className="w-4 h-4 text-tech-400" />分支对比</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <div className="flex items-center gap-2 mb-3 flex-shrink-0">
          <select value={cmpBase} onChange={e => setCmpBase(e.target.value)}
            className="flex-1 px-2 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none">
            <option value="">基准分支（base）…</option>
            {(cmpBranches?.local || []).map(b => <option key={b} value={b}>{b}</option>)}
          </select>
          <span className="text-ink-600 text-xs">←</span>
          <span className="px-2 py-1.5 text-xs font-mono text-tech-400 bg-ink-900/40 rounded-lg border border-tech-500/10 flex-shrink-0">{status?.branch || project.repoBranch}（当前）</span>
          <button onClick={doCompare} disabled={!cmpBase || busy !== null}
            className="px-3 py-1.5 btn-primary text-white text-xs rounded-lg disabled:opacity-50 flex items-center gap-1 flex-shrink-0">
            {busy === 'compare' ? <Loader2 className="w-3 h-3 animate-spin" /> : <GitCompare className="w-3 h-3" />}对比
          </button>
        </div>
        {cmpResult && (
          <div className="flex-1 min-h-0 overflow-y-auto space-y-3">
            <div>
              <p className="text-[10px] text-ink-500 mb-1 px-1">独有提交（{cmpResult.commits.length}）</p>
              <div className="rounded-lg border border-tech-500/10 bg-ink-900/30 p-2 space-y-1">
                {cmpResult.commits.length === 0 && <p className="text-xs text-ink-600 text-center py-2">无独有提交</p>}
                {cmpResult.commits.map(c => (
                  <div key={c.hash} className="flex items-center gap-2 text-xs">
                    <span className="font-mono text-tech-400 flex-shrink-0">{c.shortHash}</span>
                    <span className="text-ink-200 truncate flex-1" title={c.message}>{c.message}</span>
                    <span className="text-[10px] text-ink-600 flex-shrink-0">{c.author}</span>
                  </div>
                ))}
              </div>
            </div>
            <div>
              <p className="text-[10px] text-ink-500 mb-1 px-1">差异统计（diff --stat）</p>
              <pre className="rounded-lg border border-tech-500/10 bg-ink-900/30 p-2.5 text-[11px] font-mono text-ink-300 whitespace-pre-wrap break-all overflow-x-auto">{cmpResult.statText || '（无差异）'}</pre>
            </div>
          </div>
        )}
      </Modal>

      {/* ============ 误操作恢复对话框（reflog，危险操作） ============ */}
      <Modal open={dialog === 'reflog'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-xl max-h-[80vh] flex flex-col`} ariaLabel="误操作恢复">
        <div className="flex items-center justify-between mb-2 flex-shrink-0">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><Undo2 className="w-4 h-4 text-cinnabar-400" />误操作恢复（reflog）</h3>
          <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
        </div>
        <p className="text-[11px] text-cinnabar-300/80 mb-2 px-1 flex-shrink-0">将工作区与分支强制回退到所选操作点；未提交的修改将全部丢失。</p>
        <div className="flex-1 min-h-0 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-1.5 space-y-0.5">
          {reflogList === null && <div className="flex justify-center py-8"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>}
          {reflogList !== null && reflogList.length === 0 && <div className="text-center py-6 text-xs text-ink-500">无操作记录</div>}
          {reflogList !== null && reflogList.map(r => (
            <div key={r.selector} className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-tech-500/5">
              <span className="font-mono text-[10px] text-ink-600 flex-shrink-0">{r.selector}</span>
              <span className="font-mono text-[11px] text-tech-400 flex-shrink-0">{r.shortSha}</span>
              <span className="text-xs text-ink-300 truncate flex-1" title={r.subject}>{r.subject}</span>
              <button onClick={() => doResetHard(r.selector, r.sha, r.subject)} disabled={!!busy}
                className="text-[11px] text-cinnabar-300 hover:text-cinnabar-400 disabled:opacity-50 flex-shrink-0">恢复到这里</button>
            </div>
          ))}
        </div>
      </Modal>

      {/* ============ 托管平台协作面板（MR/Issue，适配层） ============ */}
      {collabOpen && (
        <DevCollabModal
          project={project}
          currentBranch={status?.branch || project.repoBranch || 'main'}
          onClose={() => setCollabOpen(false)}
        />
      )}

      {/* ============ 历史对话框 ============ */}
      <Modal open={dialog === 'history'} onClose={() => setDialog(null)}
        panelClassName={`${modalShell} max-w-xl`} ariaLabel="提交历史">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-base font-semibold text-ink-50 flex items-center gap-2"><History className="w-4 h-4 text-tech-400" />提交历史 · {status?.branch || project.repoBranch}</h3>
          <div className="flex items-center gap-2">
            <button onClick={() => { setLogs([]); gitLog(projectId, 100).then(setLogs).catch(() => undefined); }}
              className="p-1 text-ink-500 hover:text-tech-400" title="刷新"><RefreshCw className="w-3.5 h-3.5" /></button>
            <button onClick={() => setDialog(null)} className="p-1 text-ink-500 hover:text-ink-200"><X className="w-4 h-4" /></button>
          </div>
        </div>
        <div className="max-h-96 overflow-y-auto rounded-lg border border-tech-500/10 bg-ink-900/30 p-2 space-y-1">
          {logs.length === 0 && <div className="text-center py-6 text-xs text-ink-500">加载中…</div>}
          {logs.map(l => (
            <div key={l.hash} className="flex items-start gap-2.5 px-2 py-1.5 rounded hover:bg-tech-500/5">
              <span className="font-mono text-xs text-tech-400 flex-shrink-0 mt-0.5">{l.shortHash}</span>
              <div className="flex-1 min-w-0">
                <p className="text-sm text-ink-100 truncate" title={l.message}>{l.message}</p>
                <p className="text-[10px] text-ink-600">{l.author} · {fmtTime(l.timestamp)}</p>
              </div>
              <div className="flex items-center gap-1.5 flex-shrink-0 text-[10px] mt-0.5">
                <button onClick={() => doCherryPick(l.hash, l.message)} disabled={!!busy} className="text-ink-500 hover:text-tech-400 disabled:opacity-50">摘取</button>
                <button onClick={() => doRevert(l.hash, l.message)} disabled={!!busy} className="text-ink-500 hover:text-gold-400 disabled:opacity-50">撤销</button>
                <button onClick={() => doExport(l.hash)} disabled={!!busy} className="text-ink-500 hover:text-ink-200 disabled:opacity-50">导出</button>
              </div>
            </div>
          ))}
        </div>
      </Modal>
    </div>
  );
}
