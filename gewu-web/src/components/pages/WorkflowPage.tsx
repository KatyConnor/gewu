'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus, Search, Play, Pause, GitBranch, Clock, CheckCircle, XCircle, MoreVertical, Edit, RotateCcw, Eye, Square, Send, Loader2 } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import WorkflowCanvas from './WorkflowCanvas';
import { useToast } from '@/components/ui/Toast';
import {
  listWorkflows, createWorkflow, updateWorkflow, deleteWorkflow,
  publishWorkflow, archiveWorkflow, startInstance, listInstances,
  suspendInstance, terminateInstance, type WorkflowDTO, type WorkflowInstanceDTO,
} from '@/lib/workflow';

// 设计状态：0=draft, 1=published, 2=archived（后端状态码）
type DesignStatus = 'draft' | 'published' | 'archived';
// 实例运行状态（由实例列表推导）
type RunStatus = 'stopped' | 'running' | 'suspended';

interface WorkflowItem {
  workflowId: string;
  name: string;
  desc: string;
  designStatus: DesignStatus;
  runStatus: RunStatus;
  nodes: number;
  version: number;
  lastRunAt?: number;
  instanceCount: number;
}

const designStatusConfig: Record<DesignStatus, { color: string; icon: React.ReactNode; label: string; bgColor: string }> = {
  draft: { color: 'text-ink-400', icon: <Edit className="w-3.5 h-3.5" />, label: '草稿', bgColor: 'bg-ink-500/10' },
  published: { color: 'text-tech-400', icon: <Send className="w-3.5 h-3.5" />, label: '已发布', bgColor: 'bg-tech-500/10' },
  archived: { color: 'text-ink-500', icon: <Square className="w-3.5 h-3.5" />, label: '已归档', bgColor: 'bg-ink-500/10' },
};

const runStatusConfig: Record<RunStatus, { color: string; icon: React.ReactNode; label: string; bgColor: string }> = {
  stopped: { color: 'text-ink-500', icon: <Square className="w-3.5 h-3.5" />, label: '已停止', bgColor: 'bg-ink-500/10' },
  running: { color: 'text-green-400', icon: <CheckCircle className="w-3.5 h-3.5" />, label: '运行中', bgColor: 'bg-green-500/10' },
  suspended: { color: 'text-gold-400', icon: <Pause className="w-3.5 h-3.5" />, label: '已暂停', bgColor: 'bg-gold-500/10' },
};

/** 实例状态 -> 页面运行状态 */
function toRunStatus(instances: WorkflowInstanceDTO[]): RunStatus {
  if (instances.some(i => i.status === 'RUNNING')) return 'running';
  if (instances.some(i => i.status === 'SUSPENDED')) return 'suspended';
  return 'stopped';
}

function formatTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts > 1e12 ? ts : ts * 1000);
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

type View = 'list' | 'canvas';
type FilterType = 'all' | 'draft' | 'published' | 'archived' | 'running' | 'suspended';

export default function WorkflowPage() {
  const [searchText, setSearchText] = useState('');
  const [statusFilter, setStatusFilter] = useState<FilterType>('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [view, setView] = useState<View>('list');
  const [workflowName, setWorkflowName] = useState('');
  const [workflowDesc, setWorkflowDesc] = useState('');
  const [activeMenu, setActiveMenu] = useState<string | null>(null);
  const [editingWorkflowId, setEditingWorkflowId] = useState<string | null>(null);
  const [showRunHistory, setShowRunHistory] = useState(false);
  const [historyWorkflow, setHistoryWorkflow] = useState<WorkflowItem | null>(null);
  const [historyInstances, setHistoryInstances] = useState<WorkflowInstanceDTO[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [workflowList, setWorkflowList] = useState<WorkflowItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const toast = useToast();

  // 加载工作流列表 + 每个工作流的实例状态
  const loadWorkflows = useCallback(async () => {
    setLoading(true);
    try {
      const page = await listWorkflows(1, 100);
      const items: WorkflowItem[] = await Promise.all(page.records.map(async (wf: WorkflowDTO) => {
        let instances: WorkflowInstanceDTO[] = [];
        try {
          const instPage = await listInstances(wf.workflowId, 1, 50);
          instances = instPage.records;
        } catch { /* 实例查询失败不阻塞列表 */ }
        const lastRun = instances
          .map(i => i.startedAt || i.createdAt || 0)
          .filter(Boolean)
          .sort((a, b) => b - a)[0];
        return {
          workflowId: wf.workflowId,
          name: wf.workflowName,
          desc: wf.description || '',
          designStatus: (wf.status === 1 ? 'published' : wf.status === 2 ? 'archived' : 'draft') as DesignStatus,
          runStatus: toRunStatus(instances),
          nodes: wf.nodeCount || 0,
          version: wf.version || 1,
          lastRunAt: lastRun,
          instanceCount: instances.length,
        };
      }));
      setWorkflowList(items);
    } catch (e) {
      console.error(e);
      toast(e instanceof Error ? e.message : '加载工作流失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { loadWorkflows(); }, [loadWorkflows]);

  // Close dropdown when clicking outside
  useEffect(() => {
    const handler = () => setActiveMenu(null);
    if (activeMenu) {
      document.addEventListener('mousedown', handler);
      return () => document.removeEventListener('mousedown', handler);
    }
  }, [activeMenu]);

  const filtered = workflowList.filter(w => {
    const matchStatus = statusFilter === 'all'
      || w.designStatus === statusFilter
      || w.runStatus === statusFilter;
    const matchSearch = !searchText || w.name.includes(searchText) || w.desc.includes(searchText);
    return matchStatus && matchSearch;
  });

  // 创建工作流（真实 API）
  const handleCreate = async () => {
    if (!workflowName.trim()) return;
    setBusy('create');
    try {
      const wf = await createWorkflow({ workflowName: workflowName.trim(), description: workflowDesc || undefined });
      toast('工作流已创建，进入画布设计', 'success');
      setShowCreateModal(false);
      setEditingWorkflowId(wf.workflowId);
      setView('canvas');
    } catch (e) {
      toast(e instanceof Error ? e.message : '创建失败', 'error');
    } finally {
      setBusy(null);
    }
  };

  // 发布工作流（真实 API）
  const handlePublish = async (wfId: string) => {
    setBusy(wfId);
    try {
      await publishWorkflow(wfId);
      toast('工作流已发布，现在可以启动实例', 'success');
      await loadWorkflows();
    } catch (e) {
      toast(e instanceof Error ? e.message : '发布失败', 'error');
    } finally {
      setBusy(null);
      setActiveMenu(null);
    }
  };

  // 启动/停止实例（真实 API）
  const handleToggleRun = async (wf: WorkflowItem) => {
    setBusy(wf.workflowId);
    try {
      if (wf.runStatus === 'running') {
        const instPage = await listInstances(wf.workflowId, 1, 50);
        const running = instPage.records.find(i => i.status === 'RUNNING');
        if (running) await suspendInstance(running.instanceId);
        toast('运行中实例已暂停', 'success');
      } else {
        await startInstance(wf.workflowId, { title: `${wf.name} - 手动触发` });
        toast('工作流实例已启动', 'success');
      }
      await loadWorkflows();
    } catch (e) {
      toast(e instanceof Error ? e.message : '操作失败', 'error');
    } finally {
      setBusy(null);
    }
  };

  // 删除（真实 API）
  const handleDelete = async (wfId: string) => {
    setBusy(wfId);
    try {
      await deleteWorkflow(wfId);
      toast('已删除工作流', 'success');
      await loadWorkflows();
    } catch (e) {
      toast(e instanceof Error ? e.message : '删除失败', 'error');
    } finally {
      setBusy(null);
      setActiveMenu(null);
    }
  };

  // 归档（真实 API）
  const handleArchive = async (wfId: string) => {
    setBusy(wfId);
    try {
      await archiveWorkflow(wfId);
      toast('工作流已归档', 'success');
      await loadWorkflows();
    } catch (e) {
      toast(e instanceof Error ? e.message : '归档失败', 'error');
    } finally {
      setBusy(null);
      setActiveMenu(null);
    }
  };

  // 运行历史（真实 API）
  const handleShowHistory = async (wf: WorkflowItem) => {
    setHistoryWorkflow(wf);
    setShowRunHistory(true);
    setHistoryLoading(true);
    try {
      const instPage = await listInstances(wf.workflowId, 1, 50);
      setHistoryInstances(instPage.records);
    } catch (e) {
      toast(e instanceof Error ? e.message : '加载运行历史失败', 'error');
      setHistoryInstances([]);
    } finally {
      setHistoryLoading(false);
    }
  };

  // 保存配置（真实 API）——暂未接线，保留实现待接入工作流编辑弹窗
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const handleSaveConfig = async (wf: WorkflowItem, name: string, desc: string) => {
    setBusy(wf.workflowId);
    try {
      await updateWorkflow(wf.workflowId, { workflowName: name, description: desc });
      toast('配置已保存', 'success');
      await loadWorkflows();
    } catch (e) {
      toast(e instanceof Error ? e.message : '保存失败', 'error');
    } finally {
      setBusy(null);
    }
  };

  if (view === 'canvas') {
    return (
      <div>
        <WorkflowCanvas
          workflowId={editingWorkflowId ?? undefined}
          name={workflowName}
          description={workflowDesc}
          onBack={() => { setView('list'); setEditingWorkflowId(null); setWorkflowName(''); setWorkflowDesc(''); loadWorkflows(); }}
          onPublish={() => { if (editingWorkflowId) handlePublish(editingWorkflowId); setView('list'); }}
        />
      </div>
    );
  }

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">工作流</h1>
          <p className="text-ink-400 text-sm mt-1">自动化流程和任务编排</p>
        </div>
        <button onClick={() => setShowCreateModal(true)} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" />创建工作流
        </button>
      </header>

      {/* 统计 */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">总工作流</p>
          <p className="text-2xl font-bold text-ink-50">{workflowList.length}</p>
        </div>
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">草稿</p>
          <p className="text-2xl font-bold text-ink-400">{workflowList.filter(w => w.designStatus === 'draft').length}</p>
        </div>
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">已发布</p>
          <p className="text-2xl font-bold text-tech-400">{workflowList.filter(w => w.designStatus === 'published').length}</p>
        </div>
        <div className="glass-dark rounded-xl p-4">
          <p className="text-xs text-ink-500 mb-1">运行中实例</p>
          <p className="text-2xl font-bold text-green-400">{workflowList.filter(w => w.runStatus === 'running').length}</p>
        </div>
      </div>

      {/* 搜索和筛选 */}
      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="搜索工作流..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <CustomSelect value={statusFilter} onChange={v => setStatusFilter(v as FilterType)} className="w-28" options={[
          { value: 'all', label: '所有状态' },
          { value: 'draft', label: '草稿' },
          { value: 'published', label: '已发布' },
          { value: 'archived', label: '已归档' },
          { value: 'running', label: '运行中' },
          { value: 'suspended', label: '已暂停' },
        ]} />
      </div>

      {/* 加载中 */}
      {loading && (
        <div className="flex items-center justify-center py-16 text-ink-500">
          <Loader2 className="w-6 h-6 animate-spin mr-2" />加载工作流列表...
        </div>
      )}

      {/* 工作流列表 */}
      {!loading && (
        <div className="space-y-3">
          {filtered.map(wf => {
            const designSt = designStatusConfig[wf.designStatus];
            const runSt = runStatusConfig[wf.runStatus];
            const canRun = wf.designStatus === 'published';
            const isBusy = busy === wf.workflowId;
            return (
              <div key={wf.workflowId} className={`glass-dark rounded-xl p-5 card-hover relative ${activeMenu === wf.workflowId ? 'z-20' : 'z-0'} ${isBusy ? 'opacity-60' : ''}`}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center">
                      <GitBranch className="w-5 h-5 text-tech-400" />
                    </div>
                    <div>
                      <div className="flex items-center gap-2 flex-wrap">
                        <h3 className="text-sm font-semibold text-ink-50">{wf.name}</h3>
                        <span className="text-[10px] text-ink-500">v{wf.version}</span>
                        {/* 设计状态标签 */}
                        <span className={`flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-full ${designSt.bgColor} ${designSt.color}`}>
                          {designSt.icon}{designSt.label}
                        </span>
                        {/* 运行状态标签 - 仅已发布时显示 */}
                        {canRun && wf.instanceCount > 0 && (
                          <span className={`flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-full ${runSt.bgColor} ${runSt.color}`}>
                            {runSt.icon}{runSt.label}
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-ink-500 mt-0.5">{wf.desc || '暂无描述'}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-6">
                    <div className="text-center">
                      <p className="text-xs text-ink-500">实例数</p>
                      <p className="text-sm font-medium text-ink-200">{wf.instanceCount}</p>
                    </div>
                    <div className="text-center">
                      <p className="text-xs text-ink-500">节点</p>
                      <p className="text-sm font-medium text-ink-200">{wf.nodes}</p>
                    </div>
                    <div className="text-center">
                      <p className="text-xs text-ink-500">上次运行</p>
                      <p className="text-sm font-medium text-ink-200">{formatTime(wf.lastRunAt)}</p>
                    </div>
                    <div className="flex items-center gap-1">
                      {/* 编辑 - 进入画布 */}
                      <button onClick={() => { setWorkflowName(wf.name); setWorkflowDesc(wf.desc); setEditingWorkflowId(wf.workflowId); setView('canvas'); }}
                        className="p-1.5 text-ink-400 hover:text-tech-400 rounded transition-colors" title="编辑画布">
                        <Edit className="w-4 h-4" />
                      </button>
                      {/* 启动/暂停 - 仅已发布时可操作 */}
                      {canRun ? (
                        <button onClick={() => handleToggleRun(wf)} disabled={isBusy}
                          className={`p-1.5 rounded transition-colors disabled:cursor-wait ${wf.runStatus === 'running' ? 'text-gold-400 hover:text-gold-300' : 'text-ink-400 hover:text-green-400'}`}
                          title={wf.runStatus === 'running' ? '暂停' : '启动'}>
                          {isBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : wf.runStatus === 'running' ? <Pause className="w-4 h-4 text-gold-400" /> : <Play className="w-4 h-4" />}
                        </button>
                      ) : (
                        <button disabled className="p-1.5 text-ink-600 rounded cursor-not-allowed" title="请先发布工作流">
                          <Play className="w-4 h-4" />
                        </button>
                      )}
                      {/* 运行历史 */}
                      {canRun && (
                        <button onClick={() => handleShowHistory(wf)}
                          className="p-1.5 text-ink-400 hover:text-cyan-400 rounded transition-colors" title="运行历史">
                          <Clock className="w-4 h-4" />
                        </button>
                      )}
                      {/* 更多操作 */}
                      <div className="relative">
                        <button onClick={(e) => { e.stopPropagation(); setActiveMenu(activeMenu === wf.workflowId ? null : wf.workflowId); }}
                          className="p-1.5 text-ink-400 hover:text-ink-200 rounded transition-colors" title="更多操作">
                          <MoreVertical className="w-4 h-4" />
                        </button>
                        {activeMenu === wf.workflowId && (
                          <div className="absolute right-0 top-full mt-1 rounded-lg border shadow-xl py-1 min-w-[140px] z-[100] animate-fade-up"
                            style={{ background: '#0e1c1b', borderColor: 'rgba(0,184,148,0.15)' }}>
                            {/* 发布按钮 - 仅草稿时显示 */}
                            {wf.designStatus === 'draft' && (
                              <button onClick={() => handlePublish(wf.workflowId)}
                                className="w-full flex items-center gap-2 px-3 py-2 text-xs text-tech-400 hover:bg-tech-500/10 transition-colors">
                                <Send className="w-3.5 h-3.5" /> 发布
                              </button>
                            )}
                            {wf.designStatus === 'published' && (
                              <button onClick={() => handleArchive(wf.workflowId)}
                                className="w-full flex items-center gap-2 px-3 py-2 text-xs text-ink-200 hover:bg-tech-500/10 transition-colors">
                                <Square className="w-3.5 h-3.5" /> 归档
                              </button>
                            )}
                            {wf.runStatus !== 'stopped' && (
                              <button onClick={async () => {
                                setBusy(wf.workflowId);
                                try {
                                  const instPage = await listInstances(wf.workflowId, 1, 50);
                                  await Promise.all(instPage.records
                                    .filter(i => i.status === 'RUNNING' || i.status === 'SUSPENDED')
                                    .map(i => terminateInstance(i.instanceId)));
                                  toast('运行中实例已终止', 'success');
                                  await loadWorkflows();
                                } catch (e) {
                                  toast(e instanceof Error ? e.message : '终止失败', 'error');
                                } finally { setBusy(null); setActiveMenu(null); }
                              }}
                                className="w-full flex items-center gap-2 px-3 py-2 text-xs text-gold-400 hover:bg-gold-500/10 transition-colors">
                                <RotateCcw className="w-3.5 h-3.5" /> 终止全部实例
                              </button>
                            )}
                            <div className="border-t my-1" style={{ borderColor: 'rgba(0,184,148,0.08)' }} />
                            <button onClick={() => handleDelete(wf.workflowId)}
                              className="w-full flex items-center gap-2 px-3 py-2 text-xs text-cinnabar-400 hover:bg-cinnabar-500/10 transition-colors">
                              <XCircle className="w-3.5 h-3.5" /> 删除
                            </button>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {!loading && filtered.length === 0 && (
        <div className="text-center py-16 text-ink-500">
          <GitBranch className="w-12 h-12 mx-auto mb-3 opacity-30" />
          <p>未找到匹配的工作流</p>
        </div>
      )}

      {/* 创建工作流弹窗 */}
      {showCreateModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowCreateModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-lg p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="text-base font-semibold text-ink-50">创建工作流</h3>
              <button onClick={() => setShowCreateModal(false)} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
            </div>
            <div className="space-y-4">
              <div><label className="block text-xs text-ink-400 mb-1">工作流名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={workflowName} onChange={e => setWorkflowName(e.target.value)} placeholder="输入工作流名称" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><textarea rows={2} value={workflowDesc} onChange={e => setWorkflowDesc(e.target.value)} placeholder="描述工作流的用途和流程" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => { setShowCreateModal(false); setWorkflowName(''); setWorkflowDesc(''); }} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleCreate} disabled={busy === 'create' || !workflowName.trim()}
                className="flex items-center gap-2 px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50">
                {busy === 'create' && <Loader2 className="w-3.5 h-3.5 animate-spin" />}创建并设计
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 运行历史弹窗（真实实例数据） */}
      {showRunHistory && historyWorkflow && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowRunHistory(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-2xl p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-2">
                <Eye className="w-4 h-4 text-tech-400" />
                <h3 className="text-base font-semibold text-ink-50">运行历史 - {historyWorkflow.name}</h3>
              </div>
              <button onClick={() => setShowRunHistory(false)} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
            </div>
            <div className="space-y-2 max-h-80 overflow-y-auto scrollbar-thin">
              {historyLoading && (
                <div className="flex items-center justify-center py-8 text-ink-500">
                  <Loader2 className="w-5 h-5 animate-spin mr-2" />加载运行历史...
                </div>
              )}
              {!historyLoading && historyInstances.length === 0 && (
                <div className="text-center py-8 text-ink-500 text-sm">暂无运行记录，发布后点击启动按钮创建实例</div>
              )}
              {!historyLoading && historyInstances.map(inst => {
                const success = inst.status === 'COMPLETED';
                const failed = inst.status === 'TERMINATED' || inst.status === 'FAILED';
                return (
                  <div key={inst.instanceId} className="flex items-center justify-between px-4 py-3 rounded-lg hover:bg-ink-800/30 transition-colors">
                    <div className="flex items-center gap-3">
                      {success ? <CheckCircle className="w-4 h-4 text-green-400" />
                        : failed ? <XCircle className="w-4 h-4 text-cinnabar-400" />
                        : <Clock className="w-4 h-4 text-gold-400" />}
                      <div>
                        <p className="text-xs text-ink-200">{inst.title || inst.instanceId}</p>
                        <p className="text-[10px] text-ink-500">
                          开始 {formatTime(inst.startedAt || inst.createdAt)}
                          {inst.currentNodeName ? ` · 当前节点 ${inst.currentNodeName}` : ''}
                        </p>
                      </div>
                    </div>
                    <span className={`text-xs px-2 py-0.5 rounded-full ${success ? 'bg-green-500/10 text-green-400' : failed ? 'bg-cinnabar-500/10 text-cinnabar-400' : 'bg-gold-500/10 text-gold-400'}`}>
                      {inst.statusDesc || inst.status}
                    </span>
                  </div>
                );
              })}
            </div>
            <div className="flex justify-end gap-3 mt-5 pt-4 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
              <button onClick={() => setShowRunHistory(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">关闭</button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
