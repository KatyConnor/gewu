'use client';
import { useState, useEffect, useCallback } from 'react';
import dynamic from 'next/dynamic';
import {
  Plus, Search, Play, Pause, Square, Trash2, Send, Loader2,
  Network, CheckCircle, XCircle, Clock, Zap, Eye, PencilRuler, History, RotateCcw, ArrowDownToLine,
} from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import {
  listGraphs, createGraph, activateGraph, deactivateGraph, deleteGraph, executeGraph,
  listExecutions, pauseExecution, resumeExecution, cancelExecution,
  executeGraphStream, resumeGraphStream, getGraph, listGraphVersions, rollbackGraphVersion,
  type OrchestrationGraphEntity, type OrchestrationExecutionEntity, type OrchestrationGraphVersionEntity,
} from '@/lib/orchestration';
import { ORCHESTRATION_TEMPLATES, findTemplate } from '@/lib/orchestrationTemplates';
import { serializeDefinition } from '@/lib/orchestrationDesigner';

// 设计器含 React Flow 画布，仅客户端渲染
const OrchestrationDesigner = dynamic(() => import('@/components/pages/OrchestrationDesigner'), {
  ssr: false,
  loading: () => (
    <div className="flex items-center justify-center py-20 text-ink-500">
      <Loader2 className="w-6 h-6 animate-spin mr-2" />加载设计器...
    </div>
  ),
});

interface StreamEvent {
  type: string;
  content?: string;
  reasoning?: string;
  nodeId?: string;
  errorMessage?: string;
  metadata?: Record<string, unknown>;
}

const statusBadge: Record<string, { color: string; bg: string; label: string }> = {
  draft: { color: 'text-ink-400', bg: 'bg-ink-500/10', label: '草稿' },
  active: { color: 'text-tech-400', bg: 'bg-tech-500/10', label: '已激活' },
  RUNNING: { color: 'text-green-400', bg: 'bg-green-500/10', label: '运行中' },
  PAUSED: { color: 'text-gold-400', bg: 'bg-gold-500/10', label: '已暂停' },
  SUCCEEDED: { color: 'text-green-400', bg: 'bg-green-500/10', label: '已成功' },
  FAILED: { color: 'text-cinnabar-400', bg: 'bg-cinnabar-500/10', label: '失败' },
  CANCELLED: { color: 'text-ink-500', bg: 'bg-ink-500/10', label: '已取消' },
  PENDING: { color: 'text-ink-400', bg: 'bg-ink-500/10', label: '等待中' },
};

function badge(status?: string) {
  return statusBadge[status || ''] || { color: 'text-ink-400', bg: 'bg-ink-500/10', label: status || '-' };
}

function formatTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts > 1e12 ? ts : ts * 1000);
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

const triggerLabel: Record<string, string> = {
  MANUAL: '手动', API: 'API', AGENT_TOOL: 'Agent工具', SCHEDULE: '定时', WEBHOOK: 'Webhook',
};

/** JSON 美化（定义对比展示用，解析失败返回原文） */
function prettyJson(text?: string): string {
  if (!text) return '';
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
}

export default function OrchestrationPage() {
  const [graphs, setGraphs] = useState<OrchestrationGraphEntity[]>([]);
  const [executions, setExecutions] = useState<OrchestrationExecutionEntity[]>([]);
  const [loading, setLoading] = useState(true);
  const [execLoading, setExecLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [searchText, setSearchText] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [formName, setFormName] = useState('');
  const [formMode, setFormMode] = useState('PIPELINE');
  const [formDefinition, setFormDefinition] = useState('');
  const [formTemplate, setFormTemplate] = useState('blank');
  // 执行面板
  const [execGraph, setExecGraph] = useState<OrchestrationGraphEntity | null>(null);
  const [execInput, setExecInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamEvents, setStreamEvents] = useState<StreamEvent[]>([]);
  // 设计器视图：非空时整页切换为画布编排
  const [designerGraph, setDesignerGraph] = useState<OrchestrationGraphEntity | null>(null);
  // 版本历史弹窗（WFO-01/02）
  const [versionGraph, setVersionGraph] = useState<OrchestrationGraphEntity | null>(null);
  const [versions, setVersions] = useState<OrchestrationGraphVersionEntity[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [selectedVersion, setSelectedVersion] = useState<OrchestrationGraphVersionEntity | null>(null);
  const toast = useToast();

  const loadGraphs = useCallback(async () => {
    setLoading(true);
    try {
      setGraphs(await listGraphs());
    } catch (e) {
      toast(e instanceof Error ? e.message : '加载编排图失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  const loadExecutions = useCallback(async () => {
    setExecLoading(true);
    try {
      setExecutions(await listExecutions());
    } catch { /* 执行列表加载失败不阻塞 */ }
    finally { setExecLoading(false); }
  }, []);

  useEffect(() => { loadGraphs(); loadExecutions(); }, [loadGraphs, loadExecutions]);

  const filtered = graphs.filter(g => {
    const matchStatus = statusFilter === 'all' || g.status === statusFilter;
    const matchSearch = !searchText || g.graphName.includes(searchText);
    return matchStatus && matchSearch;
  });

  const handleCreate = async () => {
    if (!formName.trim()) return;
    setBusy('create');
    try {
      // 后端要求定义非空：留空时给空图骨架，创建后直接进入设计器补全
      const created = await createGraph({
        name: formName.trim(),
        graphDefinition: formDefinition.trim() || '{"nodes":[],"edges":[]}',
        mode: formMode,
      });
      toast('编排图已创建，已进入设计器', 'success');
      setShowCreateModal(false);
      setFormName(''); setFormDefinition('');
      await loadGraphs();
      setDesignerGraph(created);
    } catch (e) {
      toast(e instanceof Error ? e.message : '创建失败', 'error');
    } finally {
      setBusy(null);
    }
  };

  const handleActivate = async (graphId: string) => {
    setBusy(graphId);
    try {
      await activateGraph(graphId);
      toast('编排图已激活（当前定义已发布为版本快照）', 'success');
      await loadGraphs();
    } catch (e) {
      toast(e instanceof Error ? e.message : '激活失败', 'error');
    } finally { setBusy(null); }
  };

  // 下架（WFO-02）：active -> draft，重新可编辑
  const handleDeactivate = async (graphId: string) => {
    setBusy(graphId);
    try {
      await deactivateGraph(graphId);
      toast('已下架编排图，进入可编辑状态', 'success');
      await loadGraphs();
    } catch (e) {
      toast(e instanceof Error ? e.message : '下架失败', 'error');
    } finally { setBusy(null); }
  };

  const handleOpenVersions = async (graph: OrchestrationGraphEntity) => {
    setVersionGraph(graph);
    setSelectedVersion(null);
    setVersionsLoading(true);
    try {
      setVersions(await listGraphVersions(graph.id));
    } catch (e) {
      toast(e instanceof Error ? e.message : '加载版本失败', 'error');
    } finally { setVersionsLoading(false); }
  };

  // 回滚版本（WFO-02）：版本快照写回草稿定义，需重新激活生效
  const handleRollback = async (graphId: string, versionId: string) => {
    setBusy(versionId);
    try {
      await rollbackGraphVersion(graphId, versionId);
      toast('已回滚到该版本（写入草稿），重新激活后生效', 'success');
      const refreshed = await getGraph(graphId);
      setVersionGraph(refreshed);
      setVersions(await listGraphVersions(graphId));
      await loadGraphs();
    } catch (e) {
      toast(e instanceof Error ? e.message : '回滚失败', 'error');
    } finally { setBusy(null); }
  };

  const handleDelete = async (graphId: string) => {
    setBusy(graphId);
    try {
      await deleteGraph(graphId);
      toast('已删除编排图', 'success');
      await loadGraphs();
    } catch (e) {
      toast(e instanceof Error ? e.message : '删除失败', 'error');
    } finally { setBusy(null); }
  };

  // 同步执行
  const handleExecute = async (graph: OrchestrationGraphEntity) => {
    setBusy(graph.id);
    try {
      const exec = await executeGraph(graph.id, execGraph?.id === graph.id ? execInput : '');
      toast(`执行完成: ${exec.status}`, exec.status === 'SUCCEEDED' ? 'success' : 'error');
      await loadExecutions();
      if (exec.finalOutput) {
        setExecGraph(graph);
        setStreamEvents([{ type: 'result', content: exec.finalOutput }]);
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : '执行失败', 'error');
    } finally { setBusy(null); }
  };

  // 流式执行（SSE 事件实时展示）
  const handleExecuteStream = (graph: OrchestrationGraphEntity) => {
    setExecGraph(graph);
    setStreamEvents([]);
    setStreaming(true);
    executeGraphStream(
      graph.id,
      execInput,
      event => setStreamEvents(prev => [...prev.slice(-200), event]),
      () => { setStreaming(false); toast('流式执行完成', 'success'); loadExecutions(); },
      err => { setStreaming(false); toast(err.message, 'error'); }
    );
  };

  // 生命周期操作（暂停/取消；恢复单独处理以支持续跑流，WFO-09）
  const handleLifecycle = async (executionId: string, action: 'pause' | 'cancel') => {
    setBusy(executionId);
    try {
      if (action === 'pause') await pauseExecution(executionId);
      else await cancelExecution(executionId);
      toast(`已${action === 'pause' ? '暂停' : '取消'}执行`, 'success');
      await loadExecutions();
    } catch (e) {
      toast(e instanceof Error ? e.message : '操作失败', 'error');
    } finally { setBusy(null); }
  };

  // 断点续跑两步化（WFO-09）：恢复 → resumable=true 时自动续订续跑事件流到执行事件面板
  const handleResumeWithStream = async (exec: OrchestrationExecutionEntity) => {
    setBusy(exec.id);
    try {
      const resumable = await resumeExecution(exec.id);
      toast('已恢复执行', 'success');
      await loadExecutions();
      if (!resumable) {
        toast('引擎无断点检查点（服务可能已重启且检查点不可用），请重新发起一次执行', 'error');
        return;
      }
      setExecGraph(prev => (prev && prev.id === exec.graphId
        ? prev
        : { id: exec.graphId, graphName: `续跑 ${exec.id.slice(0, 8)}…`, status: 'active' }));
      setStreamEvents([]);
      setStreaming(true);
      resumeGraphStream(
        exec.id,
        event => setStreamEvents(prev => [...prev.slice(-200), event]),
        () => { setStreaming(false); toast('断点续跑完成', 'success'); loadExecutions(); },
        err => { setStreaming(false); toast(err.message, 'error'); }
      );
    } catch (e) {
      toast(e instanceof Error ? e.message : '恢复失败', 'error');
    } finally { setBusy(null); }
  };

  // 设计器视图：画布编排整页替换列表
  if (designerGraph) {
    return (
      <OrchestrationDesigner
        graph={designerGraph}
        onBack={() => { setDesignerGraph(null); loadGraphs(); }}
        onSaved={loadGraphs}
      />
    );
  }

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">编排引擎</h1>
          <p className="text-ink-400 text-sm mt-1">编排图管理、执行与生命周期控制</p>
        </div>
        <button onClick={() => setShowCreateModal(true)} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" />创建编排图
        </button>
      </header>

      {/* 统计 */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">编排图总数</p>
          <p className="text-2xl font-bold text-ink-50">{graphs.length}</p>
        </div>
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">已激活</p>
          <p className="text-2xl font-bold text-tech-400">{graphs.filter(g => g.status === 'active').length}</p>
        </div>
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">运行中执行</p>
          <p className="text-2xl font-bold text-green-400">{executions.filter(e => e.status === 'RUNNING').length}</p>
        </div>
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">累计执行</p>
          <p className="text-2xl font-bold text-ink-200">{executions.length}</p>
        </div>
      </div>

      {/* 搜索筛选 */}
      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="搜索编排图..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <CustomSelect value={statusFilter} onChange={setStatusFilter} className="w-28" options={[
          { value: 'all', label: '所有状态' },
          { value: 'draft', label: '草稿' },
          { value: 'active', label: '已激活' },
        ]} />
        <button onClick={loadExecutions} className="ml-auto flex items-center gap-2 px-3 py-2 text-sm text-ink-300 hover:text-ink-100 border border-tech-500/20 rounded-lg">
          <Clock className="w-4 h-4" />刷新执行
        </button>
      </div>

      {/* 编排图列表 */}
      {loading ? (
        <div className="flex items-center justify-center py-12 text-ink-500">
          <Loader2 className="w-6 h-6 animate-spin mr-2" />加载编排图...
        </div>
      ) : (
        <div className="space-y-3 mb-10">
          {filtered.map(g => {
            const st = badge(g.status);
            const isBusy = busy === g.id;
            return (
              <div key={g.id} className={`glass-dark rounded-xl p-5 card-hover ${isBusy ? 'opacity-60' : ''}`}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center">
                      <Network className="w-5 h-5 text-tech-400" />
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="text-sm font-semibold text-ink-50">{g.graphName}</h3>
                        <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${st.bg} ${st.color}`}>{st.label}</span>
                        <span className="text-[10px] text-ink-500">v{g.version || '1'}</span>
                        <span className="text-[10px] text-ink-500">{g.orchestrationMode || 'PIPELINE'}</span>
                      </div>
                      <p className="text-xs text-ink-500 mt-0.5">
                        {g.id} · 创建于 {formatTime(g.createdAt)}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-1">
                    <button onClick={() => setDesignerGraph(g)} disabled={isBusy}
                      className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-cyan-300 border border-cyan-500/20 rounded-lg hover:bg-cyan-500/10 transition-colors disabled:opacity-40"
                      title={g.status === 'active' ? '查看编排图（只读，支持运行预览）' : '在设计器中编排'}>
                      <PencilRuler className="w-3.5 h-3.5" />{g.status === 'active' ? '查看' : '设计'}
                    </button>
                    {g.status !== 'active' ? (
                      <button onClick={() => handleActivate(g.id)} disabled={isBusy}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 transition-colors">
                        <Send className="w-3.5 h-3.5" />激活
                      </button>
                    ) : (
                      <button onClick={() => handleDeactivate(g.id)} disabled={isBusy}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-gold-400 border border-gold-500/20 rounded-lg hover:bg-gold-500/10 transition-colors"
                        title="下架为草稿，重新可编辑；执行与历史数据保留">
                        <ArrowDownToLine className="w-3.5 h-3.5" />下架
                      </button>
                    )}
                    <button onClick={() => handleOpenVersions(g)} disabled={isBusy}
                      className="p-1.5 text-ink-400 hover:text-tech-400 rounded transition-colors" title="版本历史">
                      <History className="w-4 h-4" />
                    </button>
                    <button onClick={() => handleExecute(g)} disabled={isBusy || g.status !== 'active'}
                      className="p-1.5 text-ink-400 hover:text-green-400 rounded transition-colors disabled:opacity-40" title={g.status === 'active' ? '同步执行' : '请先激活'}>
                      {isBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                    </button>
                    <button onClick={() => handleExecuteStream(g)} disabled={streaming || g.status !== 'active'}
                      className="p-1.5 text-ink-400 hover:text-cyan-400 rounded transition-colors disabled:opacity-40" title="流式执行">
                      <Zap className="w-4 h-4" />
                    </button>
                    <button onClick={() => handleDelete(g.id)} disabled={isBusy}
                      className="p-1.5 text-ink-400 hover:text-cinnabar-400 rounded transition-colors" title="删除">
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
          {filtered.length === 0 && (
            <div className="text-center py-14 text-ink-500">
              <Network className="w-12 h-12 mx-auto mb-3 opacity-30" />
              <p>暂无编排图，点击右上角创建</p>
            </div>
          )}
        </div>
      )}

      {/* 流式执行事件面板 */}
      {execGraph && (
        <div className="glass-dark rounded-xl p-5 mb-10">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <Eye className="w-4 h-4 text-tech-400" />
              <h3 className="text-sm font-semibold text-ink-50">执行事件 - {execGraph.graphName}</h3>
              {streaming && <Loader2 className="w-4 h-4 animate-spin text-tech-400" />}
            </div>
            <button onClick={() => { setExecGraph(null); setStreamEvents([]); }} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
          </div>
          <div className="flex items-center gap-2 mb-3">
            <input type="text" value={execInput} onChange={e => setExecInput(e.target.value)} placeholder="执行输入（可选）..."
              className="flex-1 px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" />
            <button onClick={() => handleExecuteStream(execGraph)} disabled={streaming}
              className="flex items-center gap-1.5 px-3 py-2 text-xs btn-primary text-white rounded-lg disabled:opacity-50">
              {streaming ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Zap className="w-3.5 h-3.5" />}
              {streaming ? '执行中' : '再次执行'}
            </button>
          </div>
          <div className="max-h-60 overflow-y-auto scrollbar-thin space-y-1 font-mono text-xs">
            {streamEvents.map((ev, i) => (
              <div key={i} className="flex gap-2">
                <span className="text-ink-600 shrink-0">[{ev.type}]</span>
                <span className={ev.type === 'error' ? 'text-cinnabar-400' : 'text-ink-300'}>
                  {ev.content || ev.reasoning || ev.errorMessage || (ev.nodeId ? `node: ${ev.nodeId}` : JSON.stringify(ev.metadata || {}))}
                </span>
              </div>
            ))}
            {streamEvents.length === 0 && <p className="text-ink-500">等待事件...</p>}
          </div>
        </div>
      )}

      {/* 执行历史 */}
      <div>
        <h2 className="text-base font-semibold text-ink-50 mb-4 flex items-center gap-2">
          <Clock className="w-4 h-4 text-tech-400" />执行历史
          {execLoading && <Loader2 className="w-4 h-4 animate-spin text-ink-500" />}
        </h2>
        <div className="space-y-2">
          {executions.map(exec => {
            const st = badge(exec.status);
            const isBusy = busy === exec.id;
            const runnable = exec.status === 'RUNNING' || exec.status === 'PAUSED';
            return (
              <div key={exec.id} className={`glass-dark rounded-xl px-5 py-4 flex items-center justify-between ${isBusy ? 'opacity-60' : ''}`}>
                <div className="flex items-center gap-3 min-w-0">
                  {exec.status === 'SUCCEEDED' ? <CheckCircle className="w-4 h-4 text-green-400 shrink-0" />
                    : exec.status === 'FAILED' ? <XCircle className="w-4 h-4 text-cinnabar-400 shrink-0" />
                    : <Clock className="w-4 h-4 text-gold-400 shrink-0" />}
                  <div className="min-w-0">
                    <p className="text-xs text-ink-200 truncate">{exec.id}</p>
                    <p className="text-[10px] text-ink-500">
                      图 {exec.graphId} · {formatTime(exec.startedAt || exec.createdAt)}
                      {exec.tokenUsed ? ` · ${exec.tokenUsed} tokens` : ''}
                      {exec.currentNodeId ? ` · 节点 ${exec.currentNodeId}` : ''}
                      {exec.triggerType && triggerLabel[exec.triggerType] ? ` · ${triggerLabel[exec.triggerType]}触发` : ''}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0 ml-4">
                  <span className={`text-xs px-2 py-0.5 rounded-full ${st.bg} ${st.color}`}>{st.label}</span>
                  {runnable && (
                    <>
                      {exec.status === 'RUNNING' ? (
                        <button onClick={() => handleLifecycle(exec.id, 'pause')} disabled={isBusy} className="p-1.5 text-gold-400 hover:text-gold-300 rounded" title="暂停">
                          {isBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Pause className="w-4 h-4" />}
                        </button>
                      ) : (
                        <button onClick={() => handleResumeWithStream(exec)} disabled={isBusy} className="p-1.5 text-green-400 hover:text-green-300 rounded" title="恢复（自动续跑）">
                          {isBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                        </button>
                      )}
                      <button onClick={() => handleLifecycle(exec.id, 'cancel')} disabled={isBusy} className="p-1.5 text-ink-400 hover:text-cinnabar-400 rounded" title="取消">
                        <Square className="w-4 h-4" />
                      </button>
                    </>
                  )}
                </div>
              </div>
            );
          })}
          {!execLoading && executions.length === 0 && (
            <div className="text-center py-10 text-ink-500 text-sm">暂无执行记录，激活编排图后点击执行</div>
          )}
        </div>
      </div>

      {/* 版本历史弹窗（WFO-01/02：不可变版本快照 + 回滚） */}
      {versionGraph && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setVersionGraph(null)}>
          <div className="glass-dark rounded-2xl w-full max-w-3xl p-6 shadow-2xl animate-fade-up border border-tech-500/10 max-h-[85vh] overflow-y-auto scrollbar-thin" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-base font-semibold text-ink-50">版本历史 - {versionGraph.graphName}</h3>
                <p className="text-xs text-ink-500 mt-0.5">
                  当前 v{versionGraph.version || '1'} · {versionGraph.status === 'active' ? '已激活' : '草稿'}
                  {versionGraph.status !== 'draft' && ' · 回滚需先下架'}
                </p>
              </div>
              <button onClick={() => setVersionGraph(null)} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
            </div>
            {versionsLoading ? (
              <div className="flex items-center justify-center py-10 text-ink-500">
                <Loader2 className="w-5 h-5 animate-spin mr-2" />加载版本...
              </div>
            ) : versions.length === 0 ? (
              <p className="text-center py-10 text-ink-500 text-sm">该图还没有版本快照（从未激活过）。激活时当前定义会被发布为版本 v1。</p>
            ) : (
              <div className="space-y-2">
                {versions.map(v => (
                  <div key={v.id} className={`glass rounded-xl px-4 py-3 flex items-center justify-between ${selectedVersion?.id === v.id ? 'border border-tech-500/30' : ''}`}>
                    <div className="min-w-0">
                      <p className="text-sm text-ink-100">v{v.version}
                        <span className="text-[10px] text-ink-500 ml-2">{v.orchestrationMode || 'PIPELINE'}</span>
                        {versionGraph.version === String(v.version) && versionGraph.status === 'active'
                          && <span className="text-[10px] text-tech-400 ml-2">当前执行版本</span>}
                      </p>
                      <p className="text-[10px] text-ink-500 mt-0.5">激活于 {formatTime(v.activatedAt || v.createdAt)}</p>
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <button onClick={() => setSelectedVersion(selectedVersion?.id === v.id ? null : v)}
                        className="flex items-center gap-1 px-2.5 py-1.5 text-xs text-cyan-300 border border-cyan-500/20 rounded-lg hover:bg-cyan-500/10 transition-colors">
                        <Eye className="w-3.5 h-3.5" />{selectedVersion?.id === v.id ? '收起定义' : '查看定义'}
                      </button>
                      <button onClick={() => handleRollback(versionGraph.id, v.id)}
                        disabled={busy === v.id || versionGraph.status !== 'draft'}
                        className="flex items-center gap-1 px-2.5 py-1.5 text-xs text-gold-400 border border-gold-500/20 rounded-lg hover:bg-gold-500/10 transition-colors disabled:opacity-40"
                        title={versionGraph.status !== 'draft' ? '仅草稿状态可回滚，请先下架' : '将该版本快照写回草稿定义'}>
                        {busy === v.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RotateCcw className="w-3.5 h-3.5" />}回滚
                      </button>
                    </div>
                  </div>
                ))}
                {selectedVersion && (
                  <div className="grid grid-cols-2 gap-3 mt-3">
                    <div>
                      <p className="text-xs text-ink-400 mb-1">当前草稿定义</p>
                      <pre className="glass rounded-lg p-3 text-[10px] font-mono text-ink-300 max-h-64 overflow-auto scrollbar-thin whitespace-pre-wrap">{prettyJson(versionGraph.graphDefinition)}</pre>
                    </div>
                    <div>
                      <p className="text-xs text-tech-400 mb-1">版本 v{selectedVersion.version} 定义</p>
                      <pre className="glass rounded-lg p-3 text-[10px] font-mono text-ink-300 max-h-64 overflow-auto scrollbar-thin whitespace-pre-wrap">{prettyJson(selectedVersion.graphDefinition)}</pre>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* 创建编排图弹窗 */}
      {showCreateModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowCreateModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-lg p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="text-base font-semibold text-ink-50">创建编排图</h3>
              <button onClick={() => setShowCreateModal(false)} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
            </div>
            <div className="space-y-4">
              <div>
                <label className="block text-xs text-ink-400 mb-1">名称 <span className="text-cinnabar-400">*</span></label>
                <input type="text" value={formName} onChange={e => setFormName(e.target.value)} placeholder="编排图名称"
                  className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">从模板开始</label>
                <CustomSelect value={formTemplate} onChange={value => {
                  setFormTemplate(value);
                  const template = findTemplate(value);
                  if (template) {
                    setFormDefinition(serializeDefinition(template.definition));
                    setFormMode(template.mode);
                  } else {
                    setFormDefinition('');
                  }
                }} options={[
                  { value: 'blank', label: '空白图（推荐，进设计器拖拽）' },
                  ...ORCHESTRATION_TEMPLATES.map(t => ({ value: t.id, label: `${t.name}（${t.mode}）` })),
                ]} />
                {formTemplate !== 'blank' && (
                  <p className="mt-1 text-[10px] text-ink-500">{findTemplate(formTemplate)?.description}</p>
                )}
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">编排模式</label>
                <CustomSelect value={formMode} onChange={setFormMode} options={[
                  { value: 'PIPELINE', label: '流水线（Pipeline）' },
                  { value: 'SWARM', label: '群体协作（Swarm）' },
                  { value: 'SUPERVISOR', label: '监督者（Supervisor）' },
                  { value: 'DEBATE', label: '辩论共识（Debate）' },
                ]} />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">图定义 JSON（可选，留空创建空图）</label>
                <textarea rows={5} value={formDefinition} onChange={e => setFormDefinition(e.target.value)}
                  placeholder={'{\n  "nodes": [{ "nodeId": "n1", "type": "AGENT", "roleCode": "DEVELOPER" }],\n  "edges": []\n}'}
                  className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 font-mono resize-none" />
                <p className="mt-1 text-[10px] text-ink-500">推荐留空或选模板：创建后进入可视化设计器拖拽编排，无需手写 JSON。</p>
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowCreateModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleCreate} disabled={busy === 'create' || !formName.trim()}
                className="flex items-center gap-2 px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50">
                {busy === 'create' && <Loader2 className="w-3.5 h-3.5 animate-spin" />}创建
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
