'use client';
import { useState, useEffect, useCallback } from 'react';
import { useSelector } from 'react-redux';
import { CheckCircle, XCircle, Loader2, ClipboardCheck, Link2, ShieldCheck, UserCheck, Lock } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import {
  listPendingAudits, approveAudit, rejectAudit, type PendingAuditDTO,
  listPendingApprovals, approveHitl, rejectHitl, type ApprovalRequestDTO,
  verifyAuditChain, listAuditChain, type AuditChainRecordDTO,
} from '@/lib/audit';
import type { RootState } from '@/store';

type Tab = 'content' | 'hitl' | 'chain';

const typeLabels: Record<string, string> = {
  SKILL_PUBLISH: '技能发布',
  AGENT_MARKET: '智能体上架',
};
const typeColors: Record<string, string> = {
  SKILL_PUBLISH: 'text-tech-400',
  AGENT_MARKET: 'text-cyber-400',
};

function formatTime(ts?: number): string {
  if (!ts) return '-';
  try { return new Date(ts > 1e12 ? ts : ts * 1000).toLocaleString('zh-CN'); } catch { return '-'; }
}

export default function AuditCenterPage() {
  const toast = useToast();
  const [tab, setTab] = useState<Tab>('content');

  // 内容审批（技能/广场）
  const [audits, setAudits] = useState<PendingAuditDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState<string | null>(null);

  // HITL 审批
  const [hitlList, setHitlList] = useState<ApprovalRequestDTO[]>([]);
  const [hitlLoading, setHitlLoading] = useState(false);
  const [hitlProcessing, setHitlProcessing] = useState<string | null>(null);

  // 登录态（WFO-07 审批人圈定过滤用）
  const currentUserId = useSelector((s: RootState) => s.app.user.userId);
  const userRoles = useSelector((s: RootState) => s.app.user.roles);
  /** 待办可见性过滤：未指定 assignee 全员可见；指定后仅本人/角色成员可见 */
  const visibleHitl = hitlList.filter(r => {
    if (!r.assigneeId && !r.assigneeRole) return true;
    if (r.assigneeId && r.assigneeId === currentUserId) return true;
    if (r.assigneeRole && (userRoles ?? []).includes(r.assigneeRole)) return true;
    return false;
  });

  // 审计链
  const [chain, setChain] = useState<AuditChainRecordDTO[]>([]);
  const [chainLoading, setChainLoading] = useState(false);
  const [chainValid, setChainValid] = useState<boolean | null>(null);
  const [chainVerifying, setChainVerifying] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setAudits((await listPendingAudits()) || []);
    } catch {
      toast('加载失败，可能无管理员权限', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  const loadHitl = useCallback(async () => {
    setHitlLoading(true);
    try {
      setHitlList((await listPendingApprovals()) || []);
    } catch {
      setHitlList([]);
    } finally {
      setHitlLoading(false);
    }
  }, []);

  const loadChain = useCallback(async () => {
    setChainLoading(true);
    try {
      setChain((await listAuditChain(100)) || []);
    } catch {
      setChain([]);
    } finally {
      setChainLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (tab === 'hitl' && !hitlLoading && hitlList.length === 0) loadHitl();
    if (tab === 'chain' && !chainLoading && chain.length === 0) loadChain();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const handleApprove = async (a: PendingAuditDTO) => {
    setProcessing(a.targetId);
    try {
      await approveAudit(a.auditType, a.targetId);
      toast('已通过', 'success');
      load();
    } catch (e) {
      toast('操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally { setProcessing(null); }
  };

  const handleReject = async (a: PendingAuditDTO) => {
    const reason = prompt('请输入拒绝原因');
    if (!reason?.trim()) { toast('拒绝需填写原因', 'error'); return; }
    setProcessing(a.targetId);
    try {
      await rejectAudit(a.auditType, a.targetId, reason);
      toast('已拒绝', 'success');
      load();
    } catch (e) {
      toast('操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally { setProcessing(null); }
  };

  // HITL 审批操作（批准后编排执行恢复）
  const handleHitlApprove = async (r: ApprovalRequestDTO) => {
    setHitlProcessing(r.id);
    try {
      await approveHitl(r.id);
      toast('已批准，编排执行恢复', 'success');
      loadHitl();
    } catch (e) {
      toast('操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally { setHitlProcessing(null); }
  };

  const handleHitlReject = async (r: ApprovalRequestDTO) => {
    const reason = prompt('请输入驳回原因');
    if (!reason?.trim()) { toast('驳回需填写原因', 'error'); return; }
    setHitlProcessing(r.id);
    try {
      await rejectHitl(r.id, reason);
      toast('已驳回', 'success');
      loadHitl();
    } catch (e) {
      toast('操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally { setHitlProcessing(null); }
  };

  const handleVerifyChain = async () => {
    setChainVerifying(true);
    try {
      const valid = await verifyAuditChain();
      setChainValid(valid);
      toast(valid ? '审计链完整性验证通过' : '审计链存在篡改！', valid ? 'success' : 'error');
    } catch (e) {
      toast('校验失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally { setChainVerifying(false); }
  };

  const tabs: { key: Tab; label: string; icon: React.ReactNode; count?: number }[] = [
    { key: 'content', label: '内容审批', icon: <ClipboardCheck className="w-3.5 h-3.5" />, count: audits.length },
    { key: 'hitl', label: 'HITL 审批', icon: <UserCheck className="w-3.5 h-3.5" />, count: visibleHitl.length },
    { key: 'chain', label: '审计链', icon: <Link2 className="w-3.5 h-3.5" />, count: chain.length },
  ];

  return (
    <div>
      <header className="mb-6">
        <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><ClipboardCheck className="w-6 h-6 text-tech-400" />审批中心</h1>
        <p className="text-ink-400 text-sm mt-1">内容审核、编排人工介入（HITL）审批与 WORM 审计链查询</p>
      </header>

      {/* 标签页 */}
      <div className="flex items-center gap-2 mb-6">
        {tabs.map(t => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className={`flex items-center gap-2 px-4 py-2 text-sm rounded-lg border transition-colors ${tab === t.key
              ? 'bg-tech-500/10 border-tech-500/30 text-tech-400'
              : 'border-tech-500/10 text-ink-400 hover:text-ink-200'}`}>
            {t.icon}{t.label}
            {t.count !== undefined && t.count > 0 && (
              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-cinnabar-500/20 text-cinnabar-400">{t.count}</span>
            )}
          </button>
        ))}
      </div>

      {/* 内容审批 */}
      {tab === 'content' && (
        loading ? (
          <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
        ) : audits.length === 0 ? (
          <div className="text-center py-20 text-ink-500 text-sm">暂无待审核事项</div>
        ) : (
          <div className="space-y-4">
            {audits.map(a => (
              <div key={a.auditType + a.targetId} className="glass-dark rounded-xl p-5">
                <div className="flex items-start justify-between mb-3">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center text-tech-400 text-lg">{a.emoji || '📋'}</div>
                    <div>
                      <h3 className="text-sm font-semibold text-ink-50">{a.name}</h3>
                      <p className="text-xs text-ink-500">{a.category || '未分类'} · {formatTime(a.createdAt)} · 创建者: {a.createdBy || '-'}</p>
                    </div>
                  </div>
                  <span className={`text-xs px-2 py-1 rounded bg-tech-500/10 ${typeColors[a.auditType] || 'text-ink-400'}`}>{typeLabels[a.auditType] || a.auditType}</span>
                </div>
                {a.description ? <p className="text-xs text-ink-400 mb-2">{a.description}</p> : null}
                {a.content ? (
                  <div className="bg-ink-800/40 rounded-lg p-3 mb-3">
                    <p className="text-[10px] text-ink-500 mb-1">内容</p>
                    <p className="text-xs text-ink-300 whitespace-pre-wrap max-h-40 overflow-y-auto scrollbar-thin">{a.content}</p>
                  </div>
                ) : null}
                <div className="flex gap-2">
                  <button onClick={() => handleApprove(a)} disabled={processing === a.targetId} className="flex items-center gap-1 px-4 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50"><CheckCircle className="w-3 h-3" />通过</button>
                  <button onClick={() => handleReject(a)} disabled={processing === a.targetId} className="flex items-center gap-1 px-4 py-1.5 text-xs border border-cinnabar-500/20 text-cinnabar-400 rounded-lg hover:bg-cinnabar-500/10 disabled:opacity-50"><XCircle className="w-3 h-3" />拒绝</button>
                </div>
              </div>
            ))}
          </div>
        )
      )}

      {/* HITL 审批 */}
      {tab === 'hitl' && (
        <div>
          <div className="flex items-center justify-between mb-4">
            <p className="text-xs text-ink-500">编排执行触发人工介入时在此审批，批准后执行自动恢复</p>
            <button onClick={loadHitl} className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-ink-300 border border-tech-500/20 rounded-lg hover:bg-tech-500/10">
              <Loader2 className={`w-3 h-3 ${hitlLoading ? 'animate-spin' : 'hidden'}`} />刷新
            </button>
          </div>
          {hitlLoading ? (
            <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
          ) : visibleHitl.length === 0 ? (
            <div className="text-center py-20 text-ink-500 text-sm">暂无可处理的 HITL 审批（指定审批人的请求仅对其可见）</div>
          ) : (
            <div className="space-y-4">
              {visibleHitl.map(r => (
                <div key={r.id} className="glass-dark rounded-xl p-5">
                  <div className="flex items-start justify-between mb-3">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-lg bg-gold-500/10 flex items-center justify-center">
                        <UserCheck className="w-5 h-5 text-gold-400" />
                      </div>
                      <div>
                        <h3 className="text-sm font-semibold text-ink-50">{r.approvalType || 'MANUAL_REVIEW'}</h3>
                        <p className="text-xs text-ink-500">
                          执行 {r.executionId || '-'} · 节点 {r.nodeId || '-'} · 超时 {formatTime(r.timeoutAt)}
                          {r.assigneeId ? ` · 指定审批人` : ''}
                        </p>
                      </div>
                    </div>
                    <span className="text-xs px-2 py-1 rounded bg-gold-500/10 text-gold-400">待审批</span>
                  </div>
                  {r.payload ? (
                    <div className="bg-ink-800/40 rounded-lg p-3 mb-3">
                      <p className="text-[10px] text-ink-500 mb-1">审批上下文</p>
                      <p className="text-xs text-ink-300 whitespace-pre-wrap max-h-40 overflow-y-auto scrollbar-thin font-mono">{r.payload}</p>
                    </div>
                  ) : null}
                  <div className="flex gap-2">
                    <button onClick={() => handleHitlApprove(r)} disabled={hitlProcessing === r.id} className="flex items-center gap-1 px-4 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50">
                      {hitlProcessing === r.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <CheckCircle className="w-3 h-3" />}批准并恢复执行
                    </button>
                    <button onClick={() => handleHitlReject(r)} disabled={hitlProcessing === r.id} className="flex items-center gap-1 px-4 py-1.5 text-xs border border-cinnabar-500/20 text-cinnabar-400 rounded-lg hover:bg-cinnabar-500/10 disabled:opacity-50">
                      <XCircle className="w-3 h-3" />驳回
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* 审计链 */}
      {tab === 'chain' && (
        <div>
          <div className="flex items-center justify-between mb-4">
            <p className="text-xs text-ink-500">WORM 链式哈希审计记录，不可篡改、可独立审查</p>
            <div className="flex items-center gap-2">
              {chainValid !== null && (
                <span className={`flex items-center gap-1 text-xs px-2 py-1 rounded ${chainValid ? 'bg-green-500/10 text-green-400' : 'bg-cinnabar-500/10 text-cinnabar-400'}`}>
                  <ShieldCheck className="w-3 h-3" />{chainValid ? '链完整' : '检测到篡改'}
                </span>
              )}
              <button onClick={handleVerifyChain} disabled={chainVerifying}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 disabled:opacity-50">
                {chainVerifying ? <Loader2 className="w-3 h-3 animate-spin" /> : <ShieldCheck className="w-3 h-3" />}校验完整性
              </button>
            </div>
          </div>
          {chainLoading ? (
            <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
          ) : chain.length === 0 ? (
            <div className="text-center py-20 text-ink-500 text-sm">暂无审计记录，执行编排后自动产生</div>
          ) : (
            <div className="space-y-2">
              {chain.map(rec => (
                <div key={rec.id} className="glass-dark rounded-xl px-5 py-4">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3 min-w-0">
                      <Link2 className="w-4 h-4 text-tech-400 shrink-0" />
                      <div className="min-w-0">
                        <p className="text-xs text-ink-200">
                          <span className="text-tech-400">{rec.eventType || '-'}</span>
                          {' · '}{rec.action || '-'}
                          {rec.executionId ? ` · 执行 ${rec.executionId}` : ''}
                        </p>
                        <p className="text-[10px] text-ink-600 font-mono truncate">
                          hash {rec.hashCurrent ? rec.hashCurrent.slice(0, 24) + '...' : '-'} · prev {rec.hashPrevious ? rec.hashPrevious.slice(0, 16) + '...' : 'genesis'}
                        </p>
                      </div>
                    </div>
                    <span className="text-[10px] text-ink-500 shrink-0 ml-4">{formatTime(rec.createdAt)}</span>
                  </div>
                  {rec.decisionTrace ? (
                    <p className="text-[10px] text-ink-500 mt-2 font-mono bg-ink-800/40 rounded p-2 whitespace-pre-wrap max-h-24 overflow-y-auto scrollbar-thin">{rec.decisionTrace}</p>
                  ) : null}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
