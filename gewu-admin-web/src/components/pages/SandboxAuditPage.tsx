'use client';
import { useState, useEffect, useCallback } from 'react';
import { Loader2, ShieldCheck } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card';
import { listSandboxAudits, type SandboxAuditItem } from '@/lib/sandbox-audit';

function formatTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts > 1e12 ? ts : ts * 1000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
}

/** 沙箱审计（管理端）：审计日志查询 */
export default function SandboxAuditPage() {
  const toast = useToast();
  const [logs, setLogs] = useState<SandboxAuditItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setLogs(await listSandboxAudits());
    } catch (err) {
      toast(err instanceof Error ? err.message : '加载沙箱审计失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const filtered = keyword.trim()
    ? logs.filter(l => (l.sandboxId || '').includes(keyword.trim())
        || (l.action || '').toUpperCase().includes(keyword.trim().toUpperCase())
        || (l.operatorId || '').includes(keyword.trim()))
    : logs;

  return (
    <div>
      <header className="mb-8 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><ShieldCheck className="w-6 h-6 text-tech-400" />沙箱审计</h1>
          <p className="text-ink-400 text-sm mt-1">沙箱创建/启停/销毁/命令执行的安全审计日志</p>
        </div>
        <input type="text" value={keyword} onChange={e => setKeyword(e.target.value)}
          placeholder="按沙箱 ID / 动作 / 操作人过滤"
          className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 font-mono text-xs w-72" />
      </header>
      <Card>
        <CardHeader><CardTitle>审计日志（{filtered.length} 条）</CardTitle></CardHeader>
        <CardContent>
          {loading ? (
            <div className="flex items-center justify-center py-10 text-ink-500"><Loader2 className="w-5 h-5 animate-spin mr-2" />加载中...</div>
          ) : filtered.length === 0 ? (
            <div className="text-center py-10 text-ink-500 text-sm">暂无审计记录</div>
          ) : (
            <div className="overflow-x-auto max-h-[60vh] overflow-y-auto scrollbar-thin">
              <table className="w-full text-sm">
                <thead><tr className="border-b border-tech-500/10">
                  <th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">时间</th>
                  <th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">动作</th>
                  <th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">沙箱 ID</th>
                  <th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">操作人</th>
                  <th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">详情</th>
                </tr></thead>
                <tbody>
                  {filtered.map(l => (
                    <tr key={l.logId} className="border-b border-tech-500/5 hover:bg-ink-800/20 transition-colors">
                      <td className="py-2.5 px-3 text-ink-400 text-xs font-mono">{formatTime(l.createdAt)}</td>
                      <td className="py-2.5 px-3"><span className="text-[10px] px-1.5 py-0.5 rounded bg-tech-500/10 text-tech-300">{l.action}</span></td>
                      <td className="py-2.5 px-3 text-ink-300 text-xs font-mono">{l.sandboxId}</td>
                      <td className="py-2.5 px-3 text-ink-400 text-xs font-mono">{l.operatorId || '-'}</td>
                      <td className="py-2.5 px-3 text-ink-400 text-xs max-w-md truncate" title={l.detail}>{l.detail || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
