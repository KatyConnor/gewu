'use client';
import { useState, useEffect, useCallback } from 'react';
import { Search, Star, Download, Bot, Code, FileText, BarChart3, Shield, Zap, MessageSquare, Loader2 } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import { listMarketAgents, installAgent, type AgentMarketDTO } from '@/lib/agent';

const categories = [
  { id: 'all', label: '全部', icon: Bot },
  { id: 'document', label: '文档处理', icon: FileText },
  { id: 'code', label: '代码开发', icon: Code },
  { id: 'data', label: '数据分析', icon: BarChart3 },
  { id: 'security', label: '安全审计', icon: Shield },
  { id: 'meeting', label: '会议协作', icon: MessageSquare },
  { id: 'design', label: '产品设计', icon: Zap },
];

export default function AgentMarketPage() {
  const toast = useToast();
  const [agents, setAgents] = useState<AgentMarketDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [searchText, setSearchText] = useState('');
  const [installing, setInstalling] = useState<string | null>(null);

  const loadAgents = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listMarketAgents();
      setAgents(data || []);
    } catch (e) {
      console.error('加载广场智能体失败:', e);
      setAgents([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadAgents(); }, [loadAgents]);

  const handleInstall = async (id: string) => {
    setInstalling(id);
    try {
      await installAgent(id);
      toast('安装成功', 'success');
    } catch (e) {
      toast('安装失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setInstalling(null);
    }
  };

  const filtered = agents.filter(a => {
    const matchCat = selectedCategory === 'all' || a.category === selectedCategory;
    const matchSearch = !searchText || a.agentName?.includes(searchText) || a.description?.includes(searchText) || a.tags?.some(t => t.includes(searchText));
    return matchCat && matchSearch;
  });

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-semibold text-ink-50">智能体广场</h1>
        <p className="text-ink-400 text-sm mt-1">发现和使用社区共享的智能体</p>
      </header>

      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="搜索智能体..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <CustomSelect value={selectedCategory} onChange={setSelectedCategory} className="w-32" options={categories.map(c => ({ value: c.id, label: c.label }))} />
      </div>

      <div className="flex gap-2 mb-6 flex-wrap">
        {categories.map(cat => (
          <button key={cat.id} onClick={() => setSelectedCategory(cat.id)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs transition-all ${selectedCategory === cat.id ? 'bg-tech-500/15 text-tech-400 border border-tech-500/20' : 'bg-ink-800/40 text-ink-400 border border-transparent hover:border-tech-500/10'}`}>
            <cat.icon className="w-3.5 h-3.5" />
            {cat.label}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-16 text-ink-500">
          <Bot className="w-12 h-12 mx-auto mb-3 opacity-30" />
          <p>未找到匹配的智能体</p>
        </div>
      ) : (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        {filtered.map(agent => (
          <div key={agent.marketId} className="glass-dark rounded-xl p-5 card-hover cursor-pointer group">
            <div className="flex items-start justify-between mb-3">
              <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-tech-500/20 to-tech-600/10 flex items-center justify-center text-2xl">
                {agent.emoji || '🤖'}
              </div>
              <div className="flex items-center gap-2">
                <span className="text-[10px] px-1.5 py-0.5 bg-tech-500/10 text-tech-400 rounded-full">已发布</span>
                {agent.stars ? (
                <div className="flex items-center gap-1 text-xs text-gold-400">
                  <Star className="w-3 h-3" fill="currentColor" />
                  {(agent.stars / 1000).toFixed(1)}k
                </div>
                ) : null}
              </div>
            </div>
            <h3 className="text-sm font-semibold text-ink-50 mb-1 group-hover:text-tech-400 transition-colors">{agent.agentName}</h3>
            <p className="text-xs text-ink-500 mb-3 line-clamp-2">{agent.description || '无描述'}</p>
            {agent.tags && agent.tags.length > 0 ? (
            <div className="flex flex-wrap gap-1.5 mb-3">
              {agent.tags.map(tag => (
                <span key={tag} className="text-[10px] px-1.5 py-0.5 bg-ink-800/50 text-ink-400 rounded">{tag}</span>
              ))}
            </div>
            ) : null}
            <div className="flex items-center justify-between pt-3 border-t border-glass-border">
              <span className="text-[10px] text-ink-600">{agent.author || '-'} · {agent.installCount || 0} 次安装</span>
              <button onClick={() => handleInstall(agent.marketId)} disabled={installing === agent.marketId} className="flex items-center gap-1 text-xs text-tech-400 hover:text-tech-300 transition-colors disabled:opacity-50">
                {installing === agent.marketId ? <Loader2 className="w-3 h-3 animate-spin" /> : <Download className="w-3 h-3" />}
                安装
              </button>
            </div>
          </div>
        ))}
      </div>
      )}
    </div>
  );
}
