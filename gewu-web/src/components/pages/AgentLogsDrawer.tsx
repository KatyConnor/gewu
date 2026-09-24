'use client';
/**
 * 智能体执行日志抽屉（"我的智能体"卡片日志按钮）：展示该智能体的对话执行记录。
 * 数据源：GET /v1/agents/executions/agent/{agentId}（每次对话一条，按开始时间倒序）。
 */
import { useState, useEffect, useCallback } from 'react';
import { useDispatch } from 'react-redux';
import { X, CheckCircle, XCircle, Clock, Loader2, ChevronDown, ChevronRight, MessageSquare, RefreshCw } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { listAgentExecutions, type AgentDTO, type AgentExecutionDTO } from '@/lib/agent';
import { setPage } from '@/store';

/** 执行状态 → 徽章样式（与后端 agent_execution.status 取值对齐） */
const execStatus: Record<string, { color: string; bg: string; label: string }> = {
  completed: { color: 'text-green-400', bg: 'bg-green-500/10', label: '成功' },
  failed: { color: 'text-cinnabar-400', bg: 'bg-cinnabar-500/10', label: '失败' },
  running: { color: 'text-gold-400', bg: 'bg-gold-500/10', label: '运行中' },
  pending: { color: 'text-ink-400', bg: 'bg-ink-700/50', label: '等待中' },
  cancelled: { color: 'text-ink-400', bg: 'bg-ink-700/50', label: '已取消' },
};

/** 兼容秒/毫秒双精度时间戳（部分链路存秒级） */
function formatTime(ts?: number): string {
  if (!ts) return '-';
  try { return new Date(ts > 1e12 ? ts : ts * 1000).toLocaleString('zh-CN'); } catch { return '-'; }
}

function formatDuration(ms?: number): string {
  if (ms == null) return '-';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

interface AgentLogsDrawerProps {
  agent: AgentDTO | null;
  onClose: () => void;
}

const PAGE_SIZE = 10;

export default function AgentLogsDrawer({ agent, onClose }: AgentLogsDrawerProps) {
  const toast = useToast();
  const dispatch = useDispatch();
  const [records, setRecords] = useState<AgentExecutionDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPageNum] = useState(1);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  const load = useCallback(async (agentId: string, pageNum: number) => {
    setLoading(true);
    try {
      const data = await listAgentExecutions(agentId, pageNum, PAGE_SIZE);
      setRecords(data.records || []);
      setTotal(data.total || 0);
    } catch (e) {
      toast('加载执行记录失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setRecords([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    if (agent) {
      setPageNum(1);
      setExpanded({});
      load(agent.agentId, 1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent]);

  if (!agent) return null;

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const openSession = (sessionId: string) => {
    window.location.hash = `#/chat?sessionId=${sessionId}`;
    dispatch(setPage('chat'));
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex justify-end modal-overlay" onClick={onClose}>
      <div className="w-full max-w-lg h-full overflow-y-auto glass-dark border-l border-tech-500/10 p-6" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-6">
          <div>
            <h3 className="text-lg font-semibold text-ink-50">执行日志</h3>
            <p className="text-xs text-ink-500 mt-0.5 truncate max-w-[320px]">{agent.agentName}</p>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={() => load(agent.agentId, page)} disabled={loading} className="p-1.5 text-ink-500 hover:text-ink-300 rounded disabled:opacity-40" title="刷新">
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button onClick={onClose} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
          </div>
        </div>

        {loading && records.length === 0 ? (
          <div className="flex items-center justify-center py-16"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
        ) : records.length === 0 ? (
          <div className="text-center py-16 text-ink-500 text-sm">暂无执行记录</div>
        ) : (
          <div className="space-y-2">
            {records.map(rec => {
              const st = execStatus[rec.status] || execStatus.pending;
              const isOpen = !!expanded[rec.executionId];
              return (
                <div key={rec.executionId} className="bg-ink-800/40 rounded-xl px-4 py-3 border border-tech-500/10">
                  <button onClick={() => setExpanded(prev => ({ ...prev, [rec.executionId]: !isOpen }))} className="w-full flex items-center gap-2.5 text-left">
                    {rec.status === 'running' ? <Loader2 className="w-4 h-4 text-gold-400 animate-spin shrink-0" />
                      : rec.status === 'completed' ? <CheckCircle className="w-4 h-4 text-green-400 shrink-0" />
                      : rec.status === 'failed' ? <XCircle className="w-4 h-4 text-cinnabar-400 shrink-0" />
                      : <Clock className="w-4 h-4 text-ink-500 shrink-0" />}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className={`text-xs px-2 py-0.5 rounded-full shrink-0 ${st.bg} ${st.color}`}>{st.label}</span>
                        <span className="text-xs text-ink-200 truncate">{rec.input || '-'}</span>
                      </div>
                      <div className="flex items-center gap-3 mt-1 text-[10px] text-ink-600 font-mono">
                        <span>{formatTime(rec.startedAt)}</span>
                        <span>耗时 {formatDuration(rec.durationMs)}</span>
                        {rec.tokensUsed != null && <span>{rec.tokensUsed} tokens</span>}
                      </div>
                    </div>
                    {isOpen ? <ChevronDown className="w-4 h-4 text-ink-500 shrink-0" /> : <ChevronRight className="w-4 h-4 text-ink-500 shrink-0" />}
                  </button>
                  {isOpen && (
                    <div className="mt-3 space-y-2 border-t border-ink-800/60 pt-3">
                      <div>
                        <p className="text-[10px] text-ink-500 mb-1">输入</p>
                        <div className="font-mono text-xs text-ink-300 bg-ink-800/40 rounded p-2 max-h-40 overflow-y-auto scrollbar-thin whitespace-pre-wrap break-all">{rec.input || '-'}</div>
                      </div>
                      {rec.status === 'failed' ? (
                        <div>
                          <p className="text-[10px] text-cinnabar-400 mb-1">错误信息</p>
                          <div className="font-mono text-xs text-cinnabar-300 bg-cinnabar-500/5 rounded p-2 max-h-40 overflow-y-auto scrollbar-thin whitespace-pre-wrap break-all">{rec.errorMessage || '-'}</div>
                        </div>
                      ) : (
                        <div>
                          <p className="text-[10px] text-ink-500 mb-1">输出</p>
                          <div className="font-mono text-xs text-ink-300 bg-ink-800/40 rounded p-2 max-h-40 overflow-y-auto scrollbar-thin whitespace-pre-wrap break-all">{rec.output || '-'}</div>
                        </div>
                      )}
                      {rec.sessionId && (
                        <button onClick={() => openSession(rec.sessionId!)} className="flex items-center gap-1.5 px-2.5 py-1 text-xs text-tech-400 border border-tech-500/15 rounded-lg hover:bg-tech-500/10">
                          <MessageSquare className="w-3 h-3" />打开会话
                        </button>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {total > PAGE_SIZE && (
          <div className="flex items-center justify-between mt-4 pt-3 border-t border-tech-500/10 text-xs text-ink-500">
            <span>共 {total} 条</span>
            <div className="flex items-center gap-2">
              <button disabled={page <= 1 || loading} onClick={() => { const p = page - 1; setPageNum(p); load(agent.agentId, p); }} className="px-2 py-1 rounded border border-tech-500/10 disabled:opacity-40 hover:bg-tech-500/10">上一页</button>
              <span>{page} / {totalPages}</span>
              <button disabled={page >= totalPages || loading} onClick={() => { const p = page + 1; setPageNum(p); load(agent.agentId, p); }} className="px-2 py-1 rounded border border-tech-500/10 disabled:opacity-40 hover:bg-tech-500/10">下一页</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
