'use client';
import { useState, useEffect, useCallback } from 'react';
import { useDispatch } from 'react-redux';
import { Plus, Settings, FileText, Play, Pause, MessageSquare, Loader2 } from 'lucide-react';
import { listAgents, updateAgent, type AgentDTO } from '@/lib/agent';
import { useToast } from '@/components/ui/Toast';
import AgentEditModal from './AgentEditModal';
import AgentLogsDrawer from './AgentLogsDrawer';
import { setPage, setPendingAgentId } from '@/store';

export default function MyAgentsPage() {
  const dispatch = useDispatch();
  const toast = useToast();
  const [agents, setAgents] = useState<AgentDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editingAgent, setEditingAgent] = useState<AgentDTO | null>(null);
  const [logsAgent, setLogsAgent] = useState<AgentDTO | null>(null);
  const [toggling, setToggling] = useState<Record<string, boolean>>({});

  const loadAgents = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listAgents();
      setAgents(data.records || []);
    } catch (e) {
      console.error('加载智能体失败:', e);
      setAgents([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadAgents(); }, [loadAgents]);

  /** 暂停/恢复运行：status 0=暂停 1=运行（与后端 resolveStatusDesc 语义一致） */
  const handleToggleStatus = async (agent: AgentDTO) => {
    if (toggling[agent.agentId]) return;
    const next = agent.status === 1 ? 0 : 1;
    setToggling(prev => ({ ...prev, [agent.agentId]: true }));
    try {
      await updateAgent(agent.agentId, { status: next });
      setAgents(prev => prev.map(a => (a.agentId === agent.agentId ? { ...a, status: next } : a)));
      toast(next === 1 ? '已恢复运行' : '已暂停，新对话将被拒绝', 'success');
    } catch (e) {
      toast('操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setToggling(prev => ({ ...prev, [agent.agentId]: false }));
    }
  };

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div><h1 className="text-2xl font-semibold text-ink-50">我的智能体</h1><p className="text-ink-400 text-sm mt-1">管理您创建和收藏的智能体</p></div>
        <button onClick={() => { setEditingAgent(null); setShowModal(true); }} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"><Plus className="w-4 h-4" />创建智能体</button>
      </header>
      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : agents.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无智能体</div>
      ) : (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {agents.map(agent => {
          const isRunning = agent.status === 1;
          const isToggling = !!toggling[agent.agentId];
          return (
            <div key={agent.agentId} className="glass-dark rounded-xl p-5 card-hover">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-xl bg-tech-500/15 flex items-center justify-center text-xl">🤖</div>
                <div className="flex-1">
                  <h3 className="text-sm font-semibold text-ink-50">{agent.agentName}</h3>
                  <span className={`text-xs flex items-center gap-1 ${isRunning ? 'text-green-400' : 'text-gold-400'}`}>
                    <div className={`w-1.5 h-1.5 rounded-full ${isRunning ? 'bg-green-400' : 'bg-gold-400'}`} />{isRunning ? '运行中' : '已暂停'}
                  </span>
                </div>
              </div>
              <div className="space-y-3 mb-4">
                <div className="flex items-center justify-between text-xs"><span className="text-ink-500">对话次数</span><span className="text-ink-200">{agent.conversations || 0} 次</span></div>
                <div className="flex items-center justify-between text-xs"><span className="text-ink-500">模型</span><span className="text-ink-200">{agent.modelProvider || '-'}/{agent.modelName || '-'}</span></div>
              </div>
              <p className="text-xs text-ink-500 mb-3 line-clamp-2">{agent.description || '无描述'}</p>
              <div className="flex gap-2">
                <button onClick={() => { dispatch(setPendingAgentId(agent.agentId)); dispatch(setPage('chat')); }} className="flex-1 py-1.5 text-xs border border-tech-500/15 text-tech-400 rounded-lg hover:bg-tech-500/10 flex items-center justify-center gap-1"><MessageSquare className="w-3 h-3" />对话</button>
                <button onClick={() => { setEditingAgent(agent); setShowModal(true); }} className="flex-1 py-1.5 text-xs border border-tech-500/15 text-tech-400 rounded-lg hover:bg-tech-500/10 flex items-center justify-center gap-1"><Settings className="w-3 h-3" />配置</button>
                <button onClick={() => setLogsAgent(agent)} className="flex-1 py-1.5 text-xs border border-tech-500/15 text-ink-300 rounded-lg hover:bg-ink-800/50 flex items-center justify-center gap-1"><FileText className="w-3 h-3" />日志</button>
                <button onClick={() => handleToggleStatus(agent)} disabled={isToggling} className="py-1.5 px-2 text-xs border border-tech-500/15 rounded-lg hover:bg-ink-800/50 flex items-center justify-center disabled:opacity-50" title={isRunning ? '暂停运行' : '恢复运行'}>
                  {isToggling ? <Loader2 className="w-3 h-3 text-ink-400 animate-spin" /> : isRunning ? <Pause className="w-3 h-3 text-gold-400" /> : <Play className="w-3 h-3 text-green-400" />}
                </button>
              </div>
            </div>
          );
        })}
      </div>
      )}

      <AgentEditModal visible={showModal} editingAgent={editingAgent} onClose={() => setShowModal(false)} onSaved={loadAgents} />
      <AgentLogsDrawer agent={logsAgent} onClose={() => setLogsAgent(null)} />
    </div>
  );
}
