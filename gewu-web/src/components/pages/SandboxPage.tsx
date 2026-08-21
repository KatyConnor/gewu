'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus, Search, Play, Square, RefreshCw, Trash2, CheckCircle, XCircle, Loader2, Terminal } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import {
  listSandboxes, createSandbox, startSandbox, stopSandbox, deleteSandbox, renewSandboxExpire,
  type SandboxDTO,
} from '@/lib/sandbox';

const statusConfig: Record<string, { color: string; bg: string; icon: React.ReactNode; label: string }> = {
  running: { color: 'text-green-400', bg: 'bg-green-500/10', icon: <CheckCircle className="w-3.5 h-3.5" />, label: '运行中' },
  stopped: { color: 'text-ink-400', bg: 'bg-ink-700/50', icon: <Square className="w-3.5 h-3.5" />, label: '已停止' },
  creating: { color: 'text-tech-400', bg: 'bg-tech-500/10', icon: <Loader2 className="w-3.5 h-3.5 animate-spin" />, label: '创建中' },
  error: { color: 'text-cinnabar-400', bg: 'bg-cinnabar-500/10', icon: <XCircle className="w-3.5 h-3.5" />, label: '异常' },
};

function formatTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts > 1e12 ? ts : ts * 1000);
  return `${d.getMonth() + 1}-${d.getDate()}`;
}

export default function SandboxPage() {
  const toast = useToast();
  const [sandboxes, setSandboxes] = useState<SandboxDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [searchText, setSearchText] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [formName, setFormName] = useState('');
  const [formImage, setFormImage] = useState('ubuntu:22.04');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setSandboxes((await listSandboxes()) || []);
    } catch (e) {
      toast(e instanceof Error ? e.message : '加载沙箱列表失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const filtered = sandboxes.filter(sb => {
    const matchStatus = statusFilter === 'all' || sb.status === statusFilter;
    const matchSearch = !searchText
      || (sb.sandboxName || '').includes(searchText)
      || (sb.image || '').includes(searchText);
    return matchStatus && matchSearch;
  });

  const stats = {
    total: sandboxes.length,
    running: sandboxes.filter(s => s.status === 'running').length,
    stopped: sandboxes.filter(s => s.status === 'stopped').length,
    error: sandboxes.filter(s => s.status === 'error').length,
  };

  const handleCreate = async () => {
    if (!formName.trim() || !formImage.trim()) return;
    setBusy('create');
    try {
      await createSandbox({ sandboxName: formName.trim(), image: formImage.trim() });
      toast('沙箱创建请求已提交', 'success');
      setShowCreateModal(false);
      setFormName('');
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : '创建失败', 'error');
    } finally { setBusy(null); }
  };

  const handleStart = async (id: string) => {
    setBusy(id);
    try {
      await startSandbox(id);
      toast('沙箱已启动', 'success');
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : '启动失败', 'error');
    } finally { setBusy(null); }
  };

  const handleStop = async (id: string) => {
    setBusy(id);
    try {
      await stopSandbox(id);
      toast('沙箱已停止', 'success');
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : '停止失败', 'error');
    } finally { setBusy(null); }
  };

  const handleRestart = async (id: string) => {
    setBusy(id);
    try {
      await stopSandbox(id);
      await startSandbox(id);
      toast('沙箱已重启', 'success');
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : '重启失败', 'error');
    } finally { setBusy(null); }
  };

  const handleRenew = async (id: string) => {
    setBusy(id);
    try {
      await renewSandboxExpire(id, 3600);
      toast('有效期已延长 1 小时', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : '续期失败', 'error');
    } finally { setBusy(null); }
  };

  const handleDestroy = async (id: string) => {
    if (!confirm('确认销毁该沙箱？数据将不可恢复')) return;
    setBusy(id);
    try {
      await deleteSandbox(id);
      toast('沙箱已销毁', 'success');
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : '销毁失败', 'error');
    } finally { setBusy(null); }
  };

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">沙箱安全</h1>
          <p className="text-ink-400 text-sm mt-1">隔离执行环境，安全运行代码和命令</p>
        </div>
        <button onClick={() => setShowCreateModal(true)} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" />创建沙箱
        </button>
      </header>

      {/* 统计 */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">沙箱总数</p><p className="text-2xl font-bold text-ink-50">{stats.total}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">运行中</p><p className="text-2xl font-bold text-green-400">{stats.running}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">已停止</p><p className="text-2xl font-bold text-ink-400">{stats.stopped}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">异常</p><p className="text-2xl font-bold text-cinnabar-400">{stats.error}</p></div>
      </div>

      {/* 搜索和筛选 */}
      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="搜索沙箱..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <CustomSelect value={statusFilter} onChange={setStatusFilter} className="w-28" options={[
          { value: 'all', label: '所有状态' },
          { value: 'running', label: '运行中' },
          { value: 'stopped', label: '已停止' },
          { value: 'creating', label: '创建中' },
          { value: 'error', label: '异常' },
        ]} />
      </div>

      {/* 加载中 */}
      {loading && (
        <div className="flex items-center justify-center py-16 text-ink-500">
          <Loader2 className="w-6 h-6 animate-spin mr-2" />加载沙箱列表...
        </div>
      )}

      {/* 沙箱列表 */}
      {!loading && (
        <div className="space-y-3">
          {filtered.map(sb => {
            const status = statusConfig[sb.status || ''] || statusConfig.stopped;
            const isBusy = busy === sb.sandboxId;
            return (
              <div key={sb.sandboxId} className={`glass-dark rounded-xl p-5 card-hover ${isBusy ? 'opacity-60' : ''}`}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${status.bg}`}>
                      <Terminal className={`w-5 h-5 ${status.color}`} />
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="text-sm font-semibold text-ink-50">{sb.sandboxName || sb.sandboxId}</h3>
                        <span className={`flex items-center gap-1 text-xs ${status.color}`}>{status.icon}{sb.statusDesc || status.label}</span>
                      </div>
                      <p className="text-xs text-ink-500 mt-0.5">
                        {sb.image || '-'} · {sb.cpuCores || '-'}核/{sb.memoryMb || '-'}MB
                        {sb.networkEnabled ? ' · 联网' : ' · 隔离'}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-6">
                    <div className="text-center"><p className="text-xs text-ink-500">IP</p><p className="text-xs font-mono text-ink-300">{sb.ip || '-'}</p></div>
                    <div className="text-center"><p className="text-xs text-ink-500">创建时间</p><p className="text-xs text-ink-300">{formatTime(sb.createdAt)}</p></div>
                    <div className="text-center"><p className="text-xs text-ink-500">运行时</p><p className="text-xs text-ink-300">{sb.runtime || '-'}</p></div>
                    <div className="flex items-center gap-1">
                      {sb.status === 'stopped' && (
                        <button onClick={() => handleStart(sb.sandboxId)} disabled={isBusy} className="p-1.5 text-ink-400 hover:text-green-400 rounded transition-colors" title="启动">
                          {isBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                        </button>
                      )}
                      {sb.status === 'running' && (
                        <button onClick={() => handleStop(sb.sandboxId)} disabled={isBusy} className="p-1.5 text-ink-400 hover:text-gold-400 rounded transition-colors" title="停止">
                          {isBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Square className="w-4 h-4" />}
                        </button>
                      )}
                      <button onClick={() => handleRestart(sb.sandboxId)} disabled={isBusy} className="p-1.5 text-ink-400 hover:text-tech-400 rounded transition-colors" title="重启">
                        <RefreshCw className="w-4 h-4" />
                      </button>
                      <button onClick={() => handleRenew(sb.sandboxId)} disabled={isBusy} className="p-1.5 text-ink-400 hover:text-cyan-400 rounded transition-colors" title="续期1小时">
                        <RefreshCw className="w-4 h-4" />
                      </button>
                      <button onClick={() => handleDestroy(sb.sandboxId)} disabled={isBusy} className="p-1.5 text-ink-400 hover:text-cinnabar-400 rounded transition-colors" title="销毁">
                        <Trash2 className="w-4 h-4" />
                      </button>
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
          <Terminal className="w-12 h-12 mx-auto mb-3 opacity-30" />
          <p>未找到匹配的沙箱</p>
        </div>
      )}

      {/* 创建沙箱弹窗 */}
      {showCreateModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowCreateModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-lg p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="text-base font-semibold text-ink-50">创建沙箱</h3>
              <button onClick={() => setShowCreateModal(false)} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
            </div>
            <div className="space-y-4">
              <div>
                <label className="block text-xs text-ink-400 mb-1">沙箱名称 <span className="text-cinnabar-400">*</span></label>
                <input type="text" value={formName} onChange={e => setFormName(e.target.value)} placeholder="如：代码审查箱"
                  className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">镜像 <span className="text-cinnabar-400">*</span></label>
                <CustomSelect value={formImage} onChange={setFormImage} options={[
                  { value: 'ubuntu:22.04', label: 'ubuntu:22.04' },
                  { value: 'node:20-alpine', label: 'node:20-alpine' },
                  { value: 'python:3.12', label: 'python:3.12' },
                ]} />
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
