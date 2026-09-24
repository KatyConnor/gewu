'use client';
import { useState, useEffect } from 'react';
import { useDispatch } from 'react-redux';
import { setPage } from '@/store';
import { MessageSquare, Bot, Activity, Zap, Loader2 } from 'lucide-react';
import { ClickableCard } from '@/components/ui/ClickableCard';
import { getDashboardStats, type DashboardStats } from '@/lib/stats';

function formatTime(ts?: number): string {
  if (!ts) return '-';
  const diff = Date.now() - (ts > 1e12 ? ts : ts * 1000);
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`;
  return `${Math.floor(diff / 86_400_000)}天前`;
}

export default function DashboardPage() {
  const dispatch = useDispatch();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await getDashboardStats();
        if (!cancelled) setStats(data);
      } catch { /* 静默降级为空态 */ }
      finally { if (!cancelled) setLoading(false); }
    })();
    return () => { cancelled = true; };
  }, []);

  const cards = [
    { label: '有执行记录的智能体', value: stats ? String(stats.agentTracked) : '-', change: `信任分布 L0:${stats?.trustDistribution?.L0 || 0} L1:${stats?.trustDistribution?.L1 || 0} L2:${stats?.trustDistribution?.L2 || 0} L3:${stats?.trustDistribution?.L3 || 0}`, icon: Bot, color: 'text-cyber-400', bg: 'bg-cyber-500/10' },
    { label: '平均任务成功率', value: stats ? `${stats.avgSuccessRate.toFixed(1)}%` : '-', change: `${stats?.executionSucceeded || 0} 次执行成功`, icon: Activity, color: 'text-tech-400', bg: 'bg-tech-500/10' },
    { label: '编排执行总数', value: stats ? String(stats.executionTotal) : '-', change: `失败 ${stats?.executionFailed || 0} 次`, icon: Zap, color: 'text-gold-400', bg: 'bg-gold-500/10' },
    { label: '运行中执行', value: stats ? String(stats.executionRunning) : '-', change: stats?.executionRunning ? '编排正在执行' : '当前无运行中执行', icon: MessageSquare, color: 'text-cinnabar-400', bg: 'bg-cinnabar-500/10' },
  ];

  return (
    <div>
      {/* 背景科技纹理 */}
      <div className="absolute inset-0 pointer-events-none opacity-[0.015]" style={{ backgroundImage: 'linear-gradient(rgba(0,184,148,0.5) 1px, transparent 1px), linear-gradient(90deg, rgba(0,184,148,0.5) 1px, transparent 1px)', backgroundSize: '80px 80px' }} />

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        {cards.map(s => (
          <div key={s.label} className="glass-dark rounded-xl p-5 card-hover group">
            <div className="flex items-center justify-between mb-3">
              <span className="text-ink-400 text-sm">{s.label}</span>
              <div className={`w-9 h-9 rounded-lg ${s.bg} flex items-center justify-center group-hover:bg-opacity-20 transition-colors`}>
                <s.icon className={`w-4 h-4 ${s.color}`} />
              </div>
            </div>
            {loading ? (
              <Loader2 className="w-6 h-6 text-ink-600 animate-spin" />
            ) : (
              <>
                <p className="text-2xl font-bold text-ink-50">{s.value}</p>
                <p className={`text-xs ${s.color} mt-1 font-medium truncate`}>{s.change}</p>
              </>
            )}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-3 gap-6">
        {/* 最近会话（真实数据） */}
        <div className="col-span-2 glass-dark rounded-xl p-6">
          <div className="flex items-center justify-between mb-5">
            <h2 className="text-lg font-semibold text-ink-50">最近会话</h2>
            <button onClick={() => dispatch(setPage('chat'))} className="text-sm text-tech-400 hover:text-tech-300">查看全部 -&gt;</button>
          </div>
          <div className="space-y-2">
            {loading && (
              <div className="flex items-center justify-center py-10 text-ink-500">
                <Loader2 className="w-5 h-5 animate-spin mr-2" />加载会话数据...
              </div>
            )}
            {!loading && (!stats?.recentSessions || stats.recentSessions.length === 0) && (
              <div className="text-center py-10 text-ink-500 text-sm">暂无会话记录，点击右上角新建对话</div>
            )}
            {!loading && stats?.recentSessions?.slice(0, 6).map(session => (
              <ClickableCard key={session.sessionId} onClick={() => {
                // 直达对应会话：经 ChatPage hash 入口按 sessionId 打开（修复此前只翻页
                // 不带入 sessionId，用户落在空会话首页误以为"内容丢了"）
                window.location.hash = `#/chat?sessionId=${encodeURIComponent(session.sessionId)}`;
                dispatch(setPage('chat'));
              }} className="flex items-center gap-4 p-3.5 rounded-lg hover:bg-tech-500/5 transition-all group border border-transparent hover:border-tech-500/10">
                <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center flex-shrink-0">
                  <MessageSquare className="w-5 h-5 text-tech-400" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm text-ink-100 truncate group-hover:text-tech-400 transition-colors">{session.title || '未命名会话'}</p>
                  <p className="text-xs text-ink-500 mt-0.5">智能体：{session.agent || '默认'} · {formatTime(session.lastMessageAt)}</p>
                </div>
                <span className="text-xs text-ink-500">{session.messageCount || 0}条消息</span>
              </ClickableCard>
            ))}
          </div>
        </div>

        {/* 编排执行概览 */}
        <div className="glass-dark rounded-xl p-6">
          <div className="flex items-center justify-between mb-5">
            <h2 className="text-lg font-semibold text-ink-50">执行概览</h2>
            <span className="text-xs bg-tech-500/10 text-tech-400 px-2 py-1 rounded-full">{stats?.executionTotal || 0} 次执行</span>
          </div>
          <div className="space-y-3">
            {[
              { label: '成功', value: stats?.executionSucceeded || 0, color: 'bg-green-500' },
              { label: '失败', value: stats?.executionFailed || 0, color: 'bg-cinnabar-500' },
              { label: '运行/暂停', value: stats?.executionRunning || 0, color: 'bg-gold-500' },
            ].map(item => {
              const total = Math.max(1, stats?.executionTotal || 0);
              const pct = Math.round((item.value / total) * 100);
              return (
                <div key={item.label} className="p-3 rounded-lg border border-transparent hover:border-tech-500/10 transition-all">
                  <div className="flex items-center justify-between mb-1.5">
                    <p className="text-sm text-ink-200">{item.label}</p>
                    <span className="text-xs text-ink-500">{item.value} 次（{pct}%）</span>
                  </div>
                  <div className="h-1.5 bg-ink-700 rounded-full overflow-hidden">
                    <div className={`h-full ${item.color} rounded-full transition-all`} style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
          {/* 快速操作 */}
          <div className="mt-5 pt-5 border-t border-tech-500/8">
            <p className="text-xs text-ink-500 mb-3">快速操作</p>
            <div className="grid grid-cols-2 gap-2">
              <button onClick={() => dispatch(setPage('chat'))} className="flex items-center gap-2 px-3 py-2 bg-tech-500/8 rounded-lg text-xs text-tech-400 hover:bg-tech-500/15 border border-tech-500/10 hover:border-tech-500/20 transition-all">
                <MessageSquare className="w-3.5 h-3.5" />新建对话
              </button>
              <button onClick={() => dispatch(setPage('orchestration'))} className="flex items-center gap-2 px-3 py-2 bg-tech-500/8 rounded-lg text-xs text-tech-400 hover:bg-tech-500/15 border border-tech-500/10 hover:border-tech-500/20 transition-all">
                <Zap className="w-3.5 h-3.5" />编排执行
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
