'use client';
import { useEffect, useState } from 'react';
import { GitPullRequest, CircleDot, ExternalLink, Loader2, Plus, X, RefreshCw } from 'lucide-react';
import Modal from '@/components/ui/Modal';
import { useToast } from '@/components/ui/Toast';
import {
  listMRs, createMR, getMR, ciOf, listIssues, getIssue, commentMR, commentIssue,
  listReviews, submitReview, capabilities,
  type MergeRequest, type CiStatus, type VcsIssue, type VcsReview, type VcsCapabilities,
} from '@/lib/vcs';
import {
  gitBranches,
  type GitProject, type GitBranches,
} from '@/lib/dev-workspace';

/**
 * 托管平台协作面板（托管商无关适配层前端）：
 * 合并请求（列表/发起/详情+CI）+ Issue（列表/详情含评论）。
 * 能力前提由后端校验（token 凭证 + 平台可识别），不满足时展示降级提示。
 */
interface Props {
  project: GitProject;
  /** 当前分支（发起 MR 的默认源分支） */
  currentBranch: string;
  onClose: () => void;
}

const fmtTime = (ms?: number) =>
  ms ? new Date(ms).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';

const stateBadge = (state: string) => {
  if (state === 'open') return 'bg-green-500/15 text-green-400';
  if (state === 'merged') return 'bg-tech-500/15 text-tech-400';
  if (state === 'failure') return 'bg-cinnabar-500/15 text-cinnabar-300';
  if (state === 'success') return 'bg-green-500/15 text-green-400';
  if (state === 'pending') return 'bg-gold-500/15 text-gold-400';
  return 'bg-ink-800 text-ink-500';
};

export default function DevCollabModal({ project, currentBranch, onClose }: Props) {
  const toast = useToast();
  const [tab, setTab] = useState<'mr' | 'issue'>('mr');
  const [mrState, setMrState] = useState<'open' | 'merged' | 'closed' | 'all'>('open');
  const [mrs, setMrs] = useState<MergeRequest[] | null>(null);
  const [issues, setIssues] = useState<VcsIssue[] | null>(null);
  const [issueState, setIssueState] = useState<'open' | 'closed' | 'all'>('open');
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [branches, setBranches] = useState<GitBranches | null>(null);
  const [form, setForm] = useState({ source: currentBranch, target: '', title: '' });
  const [detail, setDetail] = useState<MergeRequest | null>(null);
  const [ci, setCi] = useState<CiStatus | null>(null);
  const [ciLoading, setCiLoading] = useState(false);
  const [issueDetail, setIssueDetail] = useState<VcsIssue | null>(null);
  const [mrComment, setMrComment] = useState('');
  const [issueComment, setIssueComment] = useState('');
  const [caps, setCaps] = useState<VcsCapabilities | null>(null);
  const [reviews, setReviews] = useState<VcsReview[] | null>(null);

  const loadMRs = async (st: typeof mrState) => {
    setMrs(null);
    try {
      setMrs(await listMRs(project.projectId, st));
    } catch (e) {
      toast('合并请求加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setMrs([]);
    }
  };
  const loadIssues = async (st: typeof issueState) => {
    setIssues(null);
    try {
      setIssues(await listIssues(project.projectId, st));
    } catch (e) {
      toast('Issue 加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setIssues([]);
    }
  };

  useEffect(() => {
    capabilities(project.projectId).then(setCaps).catch(() => setCaps(null));
    loadMRs(mrState);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mrState]);
  useEffect(() => {
    loadIssues(issueState);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issueState]);

  const openCreate = async () => {
    setCreating(true);
    const bs = await gitBranches(project.projectId).catch(() => null);
    setBranches(bs);
    setForm(f => ({ ...f, target: bs?.current || f.target }));
  };

  const doCreate = async () => {
    if (!form.source || !form.target || !form.title.trim()) return;
    setBusy(true);
    try {
      const mr = await createMR(project.projectId, {
        sourceBranch: form.source, targetBranch: form.target, title: form.title.trim(),
      });
      toast(`合并请求 #${mr.number} 已创建`, 'success');
      setCreating(false);
      setMrState('open');
      loadMRs('open');
    } catch (e) {
      toast('创建失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setBusy(false);
    }
  };

  const openMR = async (n: number) => {
    setBusy(true);
    try {
      setDetail(await getMR(project.projectId, n));
      setCi(null);
    } catch (e) {
      toast('详情加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setBusy(false);
    }
  };

  const loadCi = async (sha: string) => {
    setCiLoading(true);
    try {
      setCi(await ciOf(project.projectId, sha));
    } catch (e) {
      toast('CI 状态查询失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setCiLoading(false);
    }
  };

  const doCommentMR = async () => {
    if (!detail || !mrComment.trim()) return;
    setBusy(true);
    try {
      await commentMR(project.projectId, detail.number, mrComment.trim());
      setMrComment('');
      const d = await getMR(project.projectId, detail.number);
      setDetail(d);
      toast('评论已发表', 'success');
    } catch (e) {
      toast('评论失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setBusy(false);
    }
  };

  const doCommentIssue = async () => {
    if (!issueDetail || !issueComment.trim()) return;
    setBusy(true);
    try {
      await commentIssue(project.projectId, issueDetail.number, issueComment.trim());
      setIssueComment('');
      setIssueDetail(await getIssue(project.projectId, issueDetail.number));
      toast('评论已发表', 'success');
    } catch (e) {
      toast('评论失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setBusy(false);
    }
  };

  const doReview = async (event: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT') => {
    if (!detail) return;
    const body = mrComment.trim();
    if (event !== 'APPROVE' && !body) {
      toast('驳回/评论需填写说明', 'error');
      return;
    }
    setBusy(true);
    try {
      await submitReview(project.projectId, detail.number, event, body);
      toast(event === 'APPROVE' ? '已批准' : event === 'REQUEST_CHANGES' ? '已要求修改' : '评论已提交', 'success');
      setMrComment('');
      setReviews(await listReviews(project.projectId, detail.number).catch(() => []));
    } catch (e) {
      toast('评审提交失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setBusy(false);
    }
  };

  const openIssue = async (n: number) => {
    setBusy(true);
    try {
      setIssueDetail(await getIssue(project.projectId, n));
    } catch (e) {
      toast('详情加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onClose={onClose} panelClassName="glass-dark rounded-2xl w-full max-w-2xl p-5 shadow-2xl border border-tech-500/10 max-h-[85vh] flex flex-col" ariaLabel="托管平台协作">
      <div className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="flex items-center gap-2">
          <GitPullRequest className="w-4 h-4 text-tech-400" />
          <h3 className="text-base font-semibold text-ink-50">托管平台协作 · {project.projectName}</h3>
        </div>
        <div className="flex items-center gap-1">
          {(['mr', 'issue'] as const).map(t => (
            <button key={t} onClick={() => setTab(t)}
              className={`px-2.5 py-1 text-xs rounded-lg transition-all ${tab === t ? 'bg-tech-500/15 text-tech-400' : 'text-ink-500 hover:text-ink-200'}`}>
              {t === 'mr' ? '合并请求' : 'Issue'}
            </button>
          ))}
          <button onClick={onClose} className="p-1 text-ink-500 hover:text-ink-200 ml-1"><X className="w-4 h-4" /></button>
        </div>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto">
        {tab === 'mr' ? (
          creating ? (
            <div className="space-y-2.5">
              <div className="flex gap-2">
                <input value={form.source} onChange={e => setForm(f => ({ ...f, source: e.target.value }))} placeholder="源分支"
                  className="flex-1 px-3 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none focus:border-tech-500/30" />
                <span className="text-ink-600 self-center">→</span>
                <select value={form.target} onChange={e => setForm(f => ({ ...f, target: e.target.value }))}
                  className="flex-1 px-2 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none">
                  <option value="">选择目标分支…</option>
                  {(branches?.local || []).map(b => <option key={b} value={b}>{b}</option>)}
                </select>
              </div>
              <input value={form.title} onChange={e => setForm(f => ({ ...f, title: e.target.value }))} placeholder="标题（必填）"
                className="w-full px-3 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none focus:border-tech-500/30" />
              <div className="flex justify-end gap-2">
                <button onClick={() => setCreating(false)} className="px-3 py-1 text-xs text-ink-400 hover:text-ink-200">取消</button>
                <button onClick={doCreate} disabled={busy || !form.source || !form.target || !form.title.trim()}
                  className="px-3 py-1 btn-primary text-white text-xs rounded-lg disabled:opacity-50 flex items-center gap-1">
                  {busy ? <Loader2 className="w-3 h-3 animate-spin" /> : <Plus className="w-3 h-3" />}创建
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className="flex items-center gap-1.5 mb-2">
                {([['open', '开放'], ['merged', '已合并'], ['closed', '已关闭'], ['all', '全部']] as const).map(([v, label]) => (
                  <button key={v} onClick={() => setMrState(v)}
                    className={`px-2 py-0.5 text-[11px] rounded ${mrState === v ? 'bg-tech-500/15 text-tech-400' : 'text-ink-500 hover:text-ink-200'}`}>{label}</button>
                ))}
                <button onClick={openCreate} className="ml-auto flex items-center gap-1 px-2 py-1 text-[11px] btn-primary text-white rounded-lg">
                  <Plus className="w-3 h-3" />发起合并请求
                </button>
              </div>
              {mrs === null && <div className="flex justify-center py-10"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>}
              {mrs !== null && mrs.length === 0 && <div className="text-center py-10 text-xs text-ink-500">无合并请求</div>}
              {mrs !== null && mrs.map(m => (
                <button key={m.number} onClick={() => openMR(m.number)}
                  className="w-full flex items-center gap-2 px-2.5 py-2 rounded-lg text-left hover:bg-tech-500/5">
                  <span className={`text-[10px] px-1.5 rounded flex-shrink-0 ${stateBadge(m.state)}`}>{m.state}</span>
                  <span className="text-xs text-ink-100 truncate flex-1" title={m.title}>{m.title}</span>
                  <span className="text-[10px] text-ink-600 flex-shrink-0">#{m.number} · {m.author}</span>
                </button>
              ))}
              {detail && (
                <div className="mt-3 rounded-lg border border-tech-500/15 bg-ink-900/40 p-3 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-tech-400">#{detail.number} {detail.title}</span>
                    {detail.webUrl && (
                      <a href={detail.webUrl} target="_blank" rel="noreferrer" className="text-[10px] text-ink-500 hover:text-tech-400 flex items-center gap-1">
                        平台打开<ExternalLink className="w-3 h-3" />
                      </a>
                    )}
                  </div>
                  <p className="text-[11px] text-ink-400 font-mono">{detail.sourceBranch} → {detail.targetBranch}</p>
                  {detail.description && <p className="text-[11px] text-ink-300 whitespace-pre-wrap break-all">{detail.description}</p>}
                  <div className="flex items-center gap-2">
                    <button onClick={() => detail.headSha && loadCi(detail.headSha)} disabled={ciLoading || !detail.headSha}
                      className="px-2 py-1 text-[11px] border border-ink-700/60 rounded text-ink-300 hover:text-tech-400 disabled:opacity-40 flex items-center gap-1">
                      {ciLoading ? <Loader2 className="w-3 h-3 animate-spin" /> : <RefreshCw className="w-3 h-3" />}查询 CI
                    </button>
                    {ci && <span className={`text-[10px] px-1.5 rounded ${stateBadge(ci.overall)}`}>CI: {ci.overall}</span>}
                  </div>
                  {ci && ci.items.length > 0 && (
                    <div className="space-y-0.5">
                      {ci.items.map((it, i) => (
                        <div key={i} className="flex items-center gap-2 text-[11px]">
                          <span className={`px-1 rounded ${stateBadge(it.state)}`}>{it.state}</span>
                          <span className="text-ink-400 truncate flex-1">{it.name || it.webUrl}</span>
                        </div>
                      ))}
                    </div>
                  )}
                  {caps?.reviewWrite && (
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className="text-[10px] text-ink-500 flex-shrink-0">评审：</span>
                      <button onClick={() => doReview('APPROVE')} disabled={!!busy}
                        className="px-2 py-0.5 text-[11px] rounded border border-green-500/30 text-green-400 hover:bg-green-500/10 disabled:opacity-40">批准</button>
                      <button onClick={() => doReview('REQUEST_CHANGES')} disabled={!!busy}
                        className="px-2 py-0.5 text-[11px] rounded border border-cinnabar-500/30 text-cinnabar-300 hover:bg-cinnabar-500/10 disabled:opacity-40">要求修改</button>
                      <button onClick={() => doReview('COMMENT')} disabled={!!busy}
                        className="px-2 py-0.5 text-[11px] rounded border border-ink-700/60 text-ink-300 hover:text-tech-400 disabled:opacity-40">仅评论</button>
                    </div>
                  )}
                  {reviews && reviews.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {reviews.slice(0, 8).map((r, i) => (
                        <span key={i} className={`text-[10px] px-1.5 rounded ${r.state === 'APPROVED' ? 'bg-green-500/15 text-green-400' : r.state === 'CHANGES_REQUESTED' ? 'bg-cinnabar-500/15 text-cinnabar-300' : 'bg-ink-800 text-ink-500'}`}>
                          {r.reviewer} · {r.state}
                        </span>
                      ))}
                    </div>
                  )}
                  <div className="flex items-center gap-1.5 pt-1 border-t border-tech-500/10">
                    <input value={mrComment} onChange={e => setMrComment(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter' && mrComment.trim()) doCommentMR(); }}
                      placeholder="发表评论…"
                      className="flex-1 px-2 py-1 bg-ink-800/50 border border-tech-500/10 rounded text-[11px] text-ink-100 outline-none focus:border-tech-500/30" />
                    <button onClick={doCommentMR} disabled={!!busy || !mrComment.trim()}
                      className="px-2 py-1 text-[11px] border border-ink-700/60 rounded text-ink-200 hover:text-tech-400 disabled:opacity-40">评论</button>
                  </div>
                </div>
              )}
            </>
          )
        ) : (
          <>
            <div className="flex items-center gap-1.5 mb-2">
              {([['open', '开放'], ['closed', '已关闭'], ['all', '全部']] as const).map(([v, label]) => (
                <button key={v} onClick={() => setIssueState(v)}
                  className={`px-2 py-0.5 text-[11px] rounded ${issueState === v ? 'bg-tech-500/15 text-tech-400' : 'text-ink-500 hover:text-ink-200'}`}>{label}</button>
              ))}
            </div>
            {issues === null && <div className="flex justify-center py-10"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>}
            {issues !== null && issues.length === 0 && <div className="text-center py-10 text-xs text-ink-500">无 Issue</div>}
            {issues !== null && issues.map(i => (
              <button key={i.number} onClick={() => openIssue(i.number)}
                className="w-full flex items-center gap-2 px-2.5 py-2 rounded-lg text-left hover:bg-tech-500/5">
                <CircleDot className={`w-3.5 h-3.5 flex-shrink-0 ${i.state === 'open' ? 'text-green-400' : 'text-ink-600'}`} />
                <span className="text-xs text-ink-100 truncate flex-1" title={i.title}>{i.title}</span>
                <span className="text-[10px] text-ink-600 flex-shrink-0">#{i.number} · {i.author}</span>
              </button>
            ))}
            {issueDetail && (
              <div className="mt-3 rounded-lg border border-tech-500/15 bg-ink-900/40 p-3 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-tech-400">#{issueDetail.number} {issueDetail.title}</span>
                  {issueDetail.webUrl && (
                    <a href={issueDetail.webUrl} target="_blank" rel="noreferrer" className="text-[10px] text-ink-500 hover:text-tech-400 flex items-center gap-1">
                      平台打开<ExternalLink className="w-3 h-3" />
                    </a>
                  )}
                </div>
                {issueDetail.description && <p className="text-[11px] text-ink-300 whitespace-pre-wrap break-all">{issueDetail.description}</p>}
                <div className="space-y-1.5 pt-1 border-t border-tech-500/10">
                  {issueDetail.comments.length === 0 && <p className="text-[10px] text-ink-600 pt-1">（无评论）</p>}
                  {issueDetail.comments.map((c, i) => (
                    <div key={i} className="text-[11px]">
                      <span className="text-tech-400">{c.author}</span>
                      <span className="text-ink-600 ml-1">{fmtTime(c.createdAt)}</span>
                      <p className="text-ink-300 whitespace-pre-wrap break-all mt-0.5">{c.body}</p>
                    </div>
                  ))}
                </div>
                <div className="flex items-center gap-1.5 pt-1 border-t border-tech-500/10">
                  <input value={issueComment} onChange={e => setIssueComment(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter' && issueComment.trim()) doCommentIssue(); }}
                    placeholder="发表评论…"
                    className="flex-1 px-2 py-1 bg-ink-800/50 border border-tech-500/10 rounded text-[11px] text-ink-100 outline-none focus:border-tech-500/30" />
                  <button onClick={doCommentIssue} disabled={!!busy || !issueComment.trim()}
                    className="px-2 py-1 text-[11px] border border-ink-700/60 rounded text-ink-200 hover:text-tech-400 disabled:opacity-40">评论</button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
