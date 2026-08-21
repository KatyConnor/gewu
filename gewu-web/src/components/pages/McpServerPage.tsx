'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus, Search, Trash2, CheckCircle, XCircle, RefreshCw, Server, Zap, Globe, Lock, Loader2 } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { listMcpServers, createMcpServer, deleteMcpServer, activateMcpServer, type McpServerDTO } from '@/lib/mcp';

const statusConfig: Record<string, { color: string; bg: string; icon: React.ReactNode; label: string }> = {
  active: { color: 'text-green-400', bg: 'bg-green-500/10', icon: <CheckCircle className="w-3.5 h-3.5" />, label: '已连接' },
  disconnected: { color: 'text-ink-400', bg: 'bg-ink-700/50', icon: <XCircle className="w-3.5 h-3.5" />, label: '未连接' },
  error: { color: 'text-cinnabar-400', bg: 'bg-cinnabar-500/10', icon: <XCircle className="w-3.5 h-3.5" />, label: '连接失败' },
};

const transportConfig: Record<string, { icon: React.ReactNode; label: string }> = {
  stdio: { icon: <Zap className="w-3.5 h-3.5" />, label: 'Stdio' },
  sse: { icon: <Globe className="w-3.5 h-3.5" />, label: 'SSE' },
  streamable_http: { icon: <Lock className="w-3.5 h-3.5" />, label: 'HTTP' },
};

export default function McpServerPage() {
  const [servers, setServers] = useState<McpServerDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchText, setSearchText] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [formName, setFormName] = useState('');
  const [formTransport, setFormTransport] = useState('stdio');
  const [formCommand, setFormCommand] = useState('');
  const [formUrl, setFormUrl] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [creating, setCreating] = useState(false);

  const loadServers = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listMcpServers();
      setServers(data || []);
    } catch (e) {
      console.error('加载 MCP 服务失败:', e);
      setServers([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadServers(); }, [loadServers]);

  const handleCreate = async () => {
    if (!formName.trim()) return;
    setCreating(true);
    try {
      await createMcpServer({ name: formName, transport: formTransport, command: formCommand, url: formUrl, description: formDesc, status: 1 });
      setShowCreateModal(false);
      setFormName(''); setFormCommand(''); setFormUrl(''); setFormDesc('');
      loadServers();
    } catch (e) {
      alert('添加失败: ' + (e instanceof Error ? e.message : String(e)));
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('确认删除此 MCP 服务？')) return;
    try {
      await deleteMcpServer(id);
      loadServers();
    } catch (e) {
      alert('删除失败: ' + (e instanceof Error ? e.message : String(e)));
    }
  };

  const handleRefresh = async (id: string) => {
    try {
      await activateMcpServer(id);
      loadServers();
    } catch (e) {
      alert('刷新失败: ' + (e instanceof Error ? e.message : String(e)));
    }
  };

  const getStatus = (s: McpServerDTO) => s.status === 1 ? 'active' : s.status === 0 ? 'disconnected' : 'error';

  const filtered = servers.filter(s => {
    const status = getStatus(s);
    const matchStatus = statusFilter === 'all' || status === statusFilter;
    const matchSearch = !searchText || s.name?.includes(searchText) || s.description?.includes(searchText);
    return matchStatus && matchSearch;
  });

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">MCP Server</h1>
          <p className="text-ink-400 text-sm mt-1">管理 Model Context Protocol 服务连接</p>
        </div>
        <button onClick={() => setShowCreateModal(true)} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" />添加服务
        </button>
      </header>

      <div className="grid grid-cols-4 gap-4 mb-6">
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">服务总数</p><p className="text-2xl font-bold text-ink-50">{servers.length}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">已连接</p><p className="text-2xl font-bold text-green-400">{servers.filter(s => s.status === 1).length}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">可用工具</p><p className="text-2xl font-bold text-tech-400">{servers.reduce((a, b) => a + (b.tools?.length || 0), 0)}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">异常</p><p className="text-2xl font-bold text-cinnabar-400">{servers.filter(s => s.status === 2).length}</p></div>
      </div>

      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="搜索服务..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <CustomSelect value={statusFilter} onChange={setStatusFilter} className="w-28" options={[
          { value: 'all', label: '所有状态' },
          { value: 'active', label: '已连接' },
          { value: 'disconnected', label: '未连接' },
          { value: 'error', label: '连接失败' },
        ]} />
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : (
      <div className="space-y-3">
        {filtered.map(server => {
          const status = statusConfig[getStatus(server)] || statusConfig.disconnected;
          const transport = transportConfig[server.transport] || transportConfig.stdio;
          return (
            <div key={server.id} className="glass-dark rounded-xl p-5 card-hover">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-4">
                  <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${status.bg}`}>
                    <Server className={`w-5 h-5 ${status.color}`} />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="text-sm font-semibold text-ink-50">{server.name}</h3>
                      <span className={`flex items-center gap-1 text-xs ${status.color}`}>{status.icon}{status.label}</span>
                      <span className="flex items-center gap-1 text-[10px] px-1.5 py-0.5 bg-ink-800/50 text-ink-400 rounded">{transport.icon}{transport.label}</span>
                    </div>
                    <p className="text-xs text-ink-500 mt-0.5">{server.description || '无描述'}</p>
                  </div>
                </div>
                <div className="flex items-center gap-6">
                  <div className="text-center"><p className="text-xs text-ink-500">工具数</p><p className="text-sm font-medium text-ink-200">{server.tools?.length || 0}</p></div>
                  <div className="text-center max-w-32"><p className="text-xs text-ink-500">端点</p><p className="text-xs font-mono text-ink-400 truncate">{server.url || server.command || '-'}</p></div>
                  <div className="flex items-center gap-1">
                    <button onClick={() => handleRefresh(server.id)} className="p-1.5 text-ink-400 hover:text-tech-400 rounded transition-colors" title="刷新"><RefreshCw className="w-4 h-4" /></button>
                    <button onClick={() => handleDelete(server.id)} className="p-1.5 text-ink-400 hover:text-cinnabar-400 rounded transition-colors" title="删除"><Trash2 className="w-4 h-4" /></button>
                  </div>
                </div>
              </div>
            </div>
          );
        })}
        {filtered.length === 0 && (
          <div className="text-center py-16 text-ink-500">
            <Server className="w-12 h-12 mx-auto mb-3 opacity-30" />
            <p>未找到匹配的 MCP 服务</p>
          </div>
        )}
      </div>
      )}

      {showCreateModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowCreateModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-lg p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="text-base font-semibold text-ink-50">添加 MCP 服务</h3>
              <button onClick={() => setShowCreateModal(false)} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
            </div>
            <div className="space-y-4">
              <div><label className="block text-xs text-ink-400 mb-1">服务名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={formName} onChange={e => setFormName(e.target.value)} placeholder="输入服务名称" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">传输方式</label><CustomSelect value={formTransport} onChange={setFormTransport} options={[{ value: 'stdio', label: 'Stdio (本地命令)' }, { value: 'sse', label: 'SSE (服务器推送)' }, { value: 'streamable_http', label: 'Streamable HTTP' }]} /></div>
              <div className="grid grid-cols-2 gap-3">
                <div><label className="block text-xs text-ink-400 mb-1">命令</label><input type="text" value={formCommand} onChange={e => setFormCommand(e.target.value)} placeholder="例如: npx" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">URL</label><input type="text" value={formUrl} onChange={e => setFormUrl(e.target.value)} placeholder="SSE/HTTP 端点" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
              </div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><textarea rows={2} value={formDesc} onChange={e => setFormDesc(e.target.value)} placeholder="描述服务功能" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowCreateModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleCreate} disabled={creating || !formName.trim()} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">
                {creating && <Loader2 className="w-4 h-4 animate-spin" />}添加
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
