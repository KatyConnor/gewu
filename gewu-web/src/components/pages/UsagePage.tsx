'use client';
import { useState, useEffect, useCallback } from 'react';
import { Loader2, Coins, BarChart3, MessageSquare } from 'lucide-react';
import { getUser } from '@/lib/token';
import { getUsageStats, getUsageDetail, type UsageStats, type UsageDetail, type UsageGranularity, type RecentExecution } from '@/lib/stats';

const statusLabels: Record<string, { label: string; color: string }> = {
  SUCCEEDED: { label: '成功', color: 'bg-green-500' },
  FAILED: { label: '失败', color: 'bg-cinnabar-500' },
  RUNNING: { label: '运行中', color: 'bg-gold-500' },
  PAUSED: { label: '已暂停', color: 'bg-gold-500' },
  CANCELLED: { label: '已取消', color: 'bg-ink-500' },
  PENDING: { label: '等待中', color: 'bg-ink-500' },
};

const GRANULARITIES: { id: UsageGranularity; label: string }[] = [
  { id: 'day', label: '每日' },
  { id: 'week', label: '近七天(按周)' },
  { id: 'month', label: '每月' },
  { id: 'quarter', label: '每季' },
  { id: 'year', label: '每年' },
];

const GRANULARITY_RANGE_DAYS: Record<UsageGranularity, number> = {
  day: 30, week: 84, month: 365, quarter: 730, year: 1460,
};

function formatTokens(n?: number): string {
  if (!n) return '0';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

function formatTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts > 1e12 ? ts : ts * 1000);
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export default function UsagePage() {
  const isAdmin = (getUser()?.roles || []).includes('ADMIN');
  const [stats, setStats] = useState<UsageStats | null>(null);
  const [detail, setDetail] = useState<UsageDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(true);
  const [granularity, setGranularity] = useState<UsageGranularity>('day');
  const [scopeAll, setScopeAll] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await getUsageStats();
        if (!cancelled) setStats(data);
      } catch { /* 静默降级为空态 */ }
      finally { if (!cancelled) setLoading(false); }
    })();
    return () => { cancelled = true; };
  }, []);

  const loadDetail = useCallback(async () => {
    setDetailLoading(true);
    try {
      const canAll = isAdmin && scopeAll;
      setDetail(await getUsageDetail(granularity, GRANULARITY_RANGE_DAYS[granularity], canAll));
    } catch { /* 静默降级为空态 */ }
    finally { setDetailLoading(false); }
  }, [granularity, scopeAll, isAdmin]);

  useEffect(() => { loadDetail(); }, [loadDetail]);

  const kpis = [
    { label: '累计 Token 用量', value: formatTokens(stats?.totalTokenUsed) },
    { label: '累计成本（元）', value: stats ? stats.totalCost.toFixed(4) : '-' },
    { label: '执行实例总数', value: String(Object.values(stats?.byStatus || {}).reduce((a, b) => a + b, 0)) },
    { label: '近期执行记录', value: String(stats?.recentExecutions?.length || 0) },
  ];

  const statusEntries = Object.entries(stats?.byStatus || {});
  const maxBucketTokens = Math.max(1, ...(detail?.tokensSeries || []).map(b => b.totalTokens));
  const maxBucketMessages = Math.max(1, ...(detail?.messageSeries || []).map(b => b.total));

  return (
    <div>
      <header className="mb-8"><h1 className="text-2xl font-semibold text-ink-50">用量统计</h1><p className="text-ink-400 text-sm mt-1">Token 消耗、成本与消息数多维统计</p></header>

      <div className="grid grid-cols-4 gap-4 mb-8">
        {kpis.map(k => (
          <div key={k.label} className="glass-dark rounded-xl p-5">
            <p className="text-xs text-ink-500 mb-1">{k.label}</p>
            {loading ? <Loader2 className="w-5 h-5 text-ink-600 animate-spin" /> : <p className="text-2xl font-bold text-ink-50">{k.value}</p>}
          </div>
        ))}
      </div>

      {/* 用量明细统计 */}
      <div className="glass-dark rounded-xl p-6 mb-6">
        <div className="flex items-center justify-between mb-5 flex-wrap gap-3">
          <h3 className="text-sm font-semibold text-ink-50 flex items-center gap-1.5"><BarChart3 className="w-3.5 h-3.5 text-tech-400" />用量明细</h3>
          <div className="flex items-center gap-2">
            {isAdmin && (
              <button onClick={() => setScopeAll(prev => !prev)}
                className={`px-3 py-1.5 text-xs rounded-lg border transition-all ${scopeAll ? 'bg-tech-500/15 text-tech-400 border-tech-500/30' : 'text-ink-400 border-ink-700/60 hover:text-ink-200'}`}>
                {scopeAll ? '全局数据' : '仅本人'}
              </button>
            )}
            <div className="flex gap-1 p-1 bg-ink-800/30 rounded-lg">
              {GRANULARITIES.map(g => (
                <button key={g.id} onClick={() => setGranularity(g.id)}
                  className={`px-2.5 py-1 text-xs rounded-md transition-all ${granularity === g.id ? 'bg-tech-500/15 text-tech-400' : 'text-ink-400 hover:text-ink-200'}`}>
                  {g.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {detailLoading ? (
          <div className="flex items-center justify-center py-10 text-ink-500"><Loader2 className="w-5 h-5 animate-spin mr-2" />加载中...</div>
        ) : !detail ? (
          <div className="text-center py-10 text-ink-500 text-sm">暂无统计数据</div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
            {/* tokens/成本时序 */}
            <div>
              <div className="grid grid-cols-3 gap-3 mb-4">
                <div className="bg-ink-800/30 rounded-lg p-3">
                  <p className="text-[10px] text-ink-500 mb-0.5">范围内 tokens</p>
                  <p className="text-lg font-bold text-ink-50">{formatTokens(detail.totalTokens)}</p>
                </div>
                <div className="bg-ink-800/30 rounded-lg p-3">
                  <p className="text-[10px] text-ink-500 mb-0.5">范围内成本（元）</p>
                  <p className="text-lg font-bold text-ink-50">{detail.totalCost.toFixed(4)}</p>
                </div>
                <div className="bg-ink-800/30 rounded-lg p-3">
                  <p className="text-[10px] text-ink-500 mb-0.5">范围内消息数</p>
                  <p className="text-lg font-bold text-ink-50">{detail.totalMessages}</p>
                </div>
              </div>
              <p className="text-xs text-ink-500 mb-2">Token 消耗趋势（{detail.granularity === 'day' ? '按日' : detail.granularity === 'week' ? '按周' : detail.granularity === 'month' ? '按月' : detail.granularity === 'quarter' ? '按季' : '按年'}）</p>
              <div className="space-y-1.5 max-h-64 overflow-y-auto scrollbar-thin pr-1">
                {detail.tokensSeries.map(b => (
                  <div key={b.bucket} className="flex items-center gap-2">
                    <span className="text-[10px] text-ink-500 w-20 flex-shrink-0">{b.bucket}</span>
                    <div className="flex-1 h-2.5 bg-ink-800/60 rounded-full overflow-hidden">
                      <div className="h-full bg-gradient-to-r from-tech-500/70 to-tech-400/50 rounded-full" style={{ width: `${Math.round((b.totalTokens / maxBucketTokens) * 100)}%` }} />
                    </div>
                    <span className="text-[10px] text-ink-400 w-24 text-right flex-shrink-0">
                      {formatTokens(b.totalTokens)} tok · ¥{b.cost.toFixed(4)}
                    </span>
                  </div>
                ))}
                {detail.tokensSeries.length === 0 && <p className="text-xs text-ink-600 py-4 text-center">范围内无用量记录</p>}
              </div>
            </div>

            {/* 按模型合计 + 消息数 */}
            <div>
              <p className="text-xs text-ink-500 mb-2">按模型合计（范围内）</p>
              <div className="overflow-x-auto mb-5">
                <table className="w-full text-xs">
                  <thead><tr className="border-b border-tech-500/10">
                    <th className="text-left py-2 px-2 text-ink-500 font-medium">模型</th>
                    <th className="text-right py-2 px-2 text-ink-500 font-medium">输入</th>
                    <th className="text-right py-2 px-2 text-ink-500 font-medium">输出</th>
                    <th className="text-right py-2 px-2 text-ink-500 font-medium">总量</th>
                    <th className="text-right py-2 px-2 text-ink-500 font-medium">成本（元）</th>
                  </tr></thead>
                  <tbody>
                    {detail.modelTotals.map(m => (
                      <tr key={m.modelId} className="border-b border-ink-800/40">
                        <td className="py-2 px-2 text-ink-200 font-mono">{m.modelId}</td>
                        <td className="py-2 px-2 text-right text-ink-400">{formatTokens(m.inputTokens)}</td>
                        <td className="py-2 px-2 text-right text-ink-400">{formatTokens(m.outputTokens)}</td>
                        <td className="py-2 px-2 text-right text-ink-100">{formatTokens(m.totalTokens)}</td>
                        <td className="py-2 px-2 text-right text-gold-400">{m.cost.toFixed(4)}</td>
                      </tr>
                    ))}
                    {detail.modelTotals.length === 0 && (
                      <tr><td colSpan={5} className="py-4 text-center text-ink-600">范围内无模型用量</td></tr>
                    )}
                  </tbody>
                </table>
              </div>

              <p className="text-xs text-ink-500 mb-2 flex items-center gap-1.5"><MessageSquare className="w-3 h-3" />消息数趋势（用户 / 智能体）</p>
              <div className="space-y-1.5 max-h-52 overflow-y-auto scrollbar-thin pr-1">
                {detail.messageSeries.map(b => (
                  <div key={b.bucket} className="flex items-center gap-2">
                    <span className="text-[10px] text-ink-500 w-20 flex-shrink-0">{b.bucket}</span>
                    <div className="flex-1 h-2.5 bg-ink-800/60 rounded-full overflow-hidden">
                      <div className="h-full bg-gradient-to-r from-cyber-500/60 to-tech-400/40 rounded-full" style={{ width: `${Math.round((b.total / maxBucketMessages) * 100)}%` }} />
                    </div>
                    <span className="text-[10px] text-ink-400 w-24 text-right flex-shrink-0">
                      用户 {b.userMessages} · 智能体 {b.agentMessages}
                    </span>
                  </div>
                ))}
                {detail.messageSeries.length === 0 && <p className="text-xs text-ink-600 py-4 text-center">范围内无消息记录</p>}
              </div>
            </div>
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 gap-6">
        {/* 状态分布 */}
        <div className="glass-dark rounded-xl p-6">
          <h3 className="text-sm font-semibold text-ink-500 mb-4">执行状态分布</h3>
          {loading ? (
            <div className="flex items-center justify-center py-8 text-ink-500"><Loader2 className="w-5 h-5 animate-spin mr-2" />加载中...</div>
          ) : statusEntries.length === 0 ? (
            <div className="text-center py-8 text-ink-500 text-sm">暂无执行记录</div>
          ) : (
            <div className="space-y-3">
              {statusEntries.map(([status, count]) => {
                const total = Math.max(1, Object.values(stats?.byStatus || {}).reduce((a, b) => a + b, 0));
                const pct = Math.round((count / total) * 100);
                const conf = statusLabels[status] || { label: status, color: 'bg-ink-500' };
                return (
                  <div key={status} className="flex items-center justify-between">
                    <span className="text-xs text-ink-300 w-16">{conf.label}</span>
                    <div className="flex-1 mx-3 h-2 bg-ink-800 rounded-full overflow-hidden">
                      <div className={`h-full ${conf.color} rounded-full`} style={{ width: `${pct}%` }} />
                    </div>
                    <span className="text-xs text-ink-400">{count} 次</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 近期执行消耗 */}
        <div className="glass-dark rounded-xl p-6">
          <h3 className="text-sm font-semibold text-ink-500 mb-4 flex items-center gap-1.5"><Coins className="w-3.5 h-3.5" />近期执行消耗</h3>
          {loading ? (
            <div className="flex items-center justify-center py-8 text-ink-500"><Loader2 className="w-5 h-5 animate-spin mr-2" />加载中...</div>
          ) : !stats?.recentExecutions?.length ? (
            <div className="text-center py-8 text-ink-500 text-sm">暂无执行消耗记录</div>
          ) : (
            <div className="space-y-2 max-h-72 overflow-y-auto scrollbar-thin">
              {stats.recentExecutions.map((e: RecentExecution) => {
                const conf = statusLabels[e.status] || { label: e.status, color: 'bg-ink-500' };
                return (
                  <div key={e.executionId} className="flex items-center justify-between p-2.5 rounded-lg hover:bg-ink-800/30 transition-colors">
                    <div className="min-w-0">
                      <p className="text-xs text-ink-300 truncate font-mono">{e.executionId}</p>
                      <p className="text-[10px] text-ink-600">{formatTime(e.startedAt)}</p>
                    </div>
                    <div className="flex items-center gap-3 shrink-0 ml-2">
                      <span className="text-xs text-ink-400">{formatTokens(e.tokenUsed)} tokens</span>
                      {e.costConsumed ? <span className="text-[10px] text-gold-400">¥{Number(e.costConsumed).toFixed(4)}</span> : null}
                      <span className="text-[10px] text-ink-500">{conf.label}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
