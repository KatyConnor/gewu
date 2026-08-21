'use client';
import { useState, useEffect, useCallback } from 'react';
import { useDispatch } from 'react-redux';
import { Plus, Settings, FileText, Play, Pause, MessageSquare, Loader2 } from 'lucide-react';
import { listAgents, type AgentDTO } from '@/lib/agent';
import { setPage, setPendingAgentId } from '@/store';

export default function MyAgentsPage() {
  const dispatch = useDispatch();
  const [agents, setAgents] = useState<AgentDTO[]>([]);
  const [loading, setLoading] = useState(true);

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

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div><h1 className="text-2xl font-semibold text-ink-50">我的智能体</h1><p className="text-ink-400 text-sm mt-1">管理您创建和收藏的智能体</p></div>
        <button className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"><Plus className="w-4 h-4" />创建智能体</button>
      </header>
      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : agents.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无智能体</div>
      ) : (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {agents.map(agent => {
          const isRunning = agent.status === 1;
          return (
            <div key={agent.agentId} className="glass-dark rounded-xl p-5 card-hover">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-xl bg-tech-500/15 flex items-center justify-center text-xl">🤖</div>
                <div className="flex-1">
                  <h3 className="text-sm font-semibold text-ink-50">{agent.agentName}</h3>
                  <span className={`text-xs flex items-center gap-1 ${isRunning ? 'text-green-400' : 'text-ink-500'}`}>
                    <div className={`w-1.5 h-1.5 rounded-full ${isRunning ? 'bg-green-400' : 'bg-ink-600'}`} />{isRunning ? '运行中' : '已暂停'}
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
                <button className="flex-1 py-1.5 text-xs border border-tech-500/15 text-tech-400 rounded-lg hover:bg-tech-500/10 flex items-center justify-center gap-1"><Settings className="w-3 h-3" />配置</button>
                <button className="flex-1 py-1.5 text-xs border border-tech-500/15 text-ink-300 rounded-lg hover:bg-ink-800/50 flex items-center justify-center gap-1"><FileText className="w-3 h-3" />日志</button>
                <button className="py-1.5 px-2 text-xs border border-tech-500/15 rounded-lg hover:bg-ink-800/50 flex items-center justify-center">
                  {isRunning ? <Pause className="w-3 h-3 text-gold-400" /> : <Play className="w-3 h-3 text-green-400" />}
                </button>
              </div>
            </div>
          );
        })}
      </div>
      )}
    </div>
  );
}
