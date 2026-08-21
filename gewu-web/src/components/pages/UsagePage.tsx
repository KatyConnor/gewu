'use client';
import { useState, useEffect } from 'react';
import { Loader2, Coins } from 'lucide-react';
import { getUsageStats, type UsageStats, type RecentExecution } from '@/lib/stats';

const statusLabels: Record<string, { label: string; color: string }> = {
  SUCCEEDED: { label: '成功', color: 'bg-green-500' },
  FAILED: { label: '失败', color: 'bg-cinnabar-500' },
  RUNNING: { label: '运行中', color: 'bg-gold-500' },
  PAUSED: { label: '已暂停', color: 'bg-gold-500' },
  CANCELLED: { label: '已取消', color: 'bg-ink-500' },
  PENDING: { label: '等待中', color: 'bg-ink-500' },
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
  const [stats, setStats] = useState<UsageStats | null>(null);
  const [loading, setLoading] = useState(true);

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

  const kpis = [
    { label: '累计 Token 用量', value: formatTokens(stats?.totalTokenUsed) },
    { label: '累计成本（元）', value: stats ? stats.totalCost.toFixed(4) : '-' },
    { label: '执行实例总数', value: String(Object.values(stats?.byStatus || {}).reduce((a, b) => a + b, 0)) },
    { label: '近期执行记录', value: String(stats?.recentExecutions?.length || 0) },
  ];

  const statusEntries = Object.entries(stats?.byStatus || {});

  return (
    <div>
      <header className="mb-8"><h1 className="text-2xl font-semibold text-ink-50">用量统计</h1><p className="text-ink-400 text-sm mt-1">编排执行资源消耗与状态分布</p></header>

      <div className="grid grid-cols-4 gap-4 mb-8">
        {kpis.map(k => (
          <div key={k.label} className="glass-dark rounded-xl p-5">
            <p className="text-xs text-ink-500 mb-1">{k.label}</p>
            {loading ? <Loader2 className="w-5 h-5 text-ink-600 animate-spin" /> : <p className="text-2xl font-bold text-ink-50">{k.value}</p>}
          </div>
        ))}
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
