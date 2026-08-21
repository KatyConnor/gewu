'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus, Search, Play, Pause, Archive, Trash2, Edit, Copy, CheckCircle, XCircle, Bot, Eye, MoreVertical, Loader2, X, Upload, MessageSquare } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import { listAgents, createAgent, deleteAgent, updateAgent, publishAgent, listAgentSkills, mountSkill, unmountSkill, type AgentDTO, type UpdateAgentCommand } from '@/lib/agent';
import { listSkillLibrary, type SkillDTO } from '@/lib/skill';
import { useDispatch } from 'react-redux';
import { setPage, setPendingAgentId } from '@/store';

const statusConfig: Record<string, { color: string; bg: string; icon: React.ReactNode; label: string }> = {
  draft: { color: 'text-ink-400', bg: 'bg-ink-700/50', icon: <Edit className="w-3.5 h-3.5" />, label: '草稿' },
  published: { color: 'text-tech-400', bg: 'bg-tech-500/10', icon: <CheckCircle className="w-3.5 h-3.5" />, label: '已发布' },
  active: { color: 'text-green-400', bg: 'bg-green-500/10', icon: <Play className="w-3.5 h-3.5" />, label: '运行中' },
  inactive: { color: 'text-gold-400', bg: 'bg-gold-500/10', icon: <Pause className="w-3.5 h-3.5" />, label: '已暂停' },
  archived: { color: 'text-ink-400', bg: 'bg-ink-700/50', icon: <Archive className="w-3.5 h-3.5" />, label: '已下架' },
  error: { color: 'text-cinnabar-400', bg: 'bg-cinnabar-500/10', icon: <XCircle className="w-3.5 h-3.5" />, label: '异常' },
  destroyed: { color: 'text-cinnabar-400', bg: 'bg-cinnabar-500/10', icon: <Trash2 className="w-3.5 h-3.5" />, label: '已销毁' },
};

function formatDate(ts?: number): string {
  if (!ts) return '-';
  try { return new Date(ts).toLocaleString('zh-CN'); } catch { return '-'; }
}

export default function AgentManagePage() {
  const toast = useToast();
  const dispatch = useDispatch();
  const [agents, setAgents] = useState<AgentDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchText, setSearchText] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [showModal, setShowModal] = useState(false);
  const [showDetailDrawer, setShowDetailDrawer] = useState(false);
  const [selectedAgent, setSelectedAgent] = useState<AgentDTO | null>(null);
  const [actionMenuOpen, setActionMenuOpen] = useState<string | null>(null);
  const [editingAgent, setEditingAgent] = useState<AgentDTO | null>(null);
  const [formName, setFormName] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [formProvider, setFormProvider] = useState('qwen');
  const [formModel, setFormModel] = useState('qwen-plus');
  const [formSystemPrompt, setFormSystemPrompt] = useState('');
  const [formModelConfig, setFormModelConfig] = useState('');
  const [saving, setSaving] = useState(false);
  const [allSkills, setAllSkills] = useState<SkillDTO[]>([]);
  const [agentSkillIds, setAgentSkillIds] = useState<Set<string>>(new Set());
  const [detailSkills, setDetailSkills] = useState<SkillDTO[]>([]);

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

  const resetForm = () => {
    setFormName(''); setFormDesc(''); setFormProvider('qwen'); setFormModel('qwen-plus');
    setFormSystemPrompt(''); setFormModelConfig('');
  };

  const openCreate = () => { resetForm(); setEditingAgent(null); setShowModal(true); };

  const loadAllSkills = useCallback(() => {
    listSkillLibrary().then(setAllSkills).catch(() => setAllSkills([]));
  }, []);

  const openEdit = (agent: AgentDTO) => {
    setEditingAgent(agent);
    setFormName(agent.agentName);
    setFormDesc(agent.description || '');
    setFormProvider(agent.modelProvider || 'qwen');
    setFormModel(agent.modelName || 'qwen-plus');
    setFormSystemPrompt(agent.systemPrompt || '');
    setFormModelConfig(agent.modelConfig || '');
    setActionMenuOpen(null);
    loadAllSkills();
    listAgentSkills(agent.agentId).then(s => setAgentSkillIds(new Set(s.map(k => k.skillId)))).catch(() => setAgentSkillIds(new Set()));
    setShowModal(true);
  };

  const handleToggleSkill = async (skillId: string) => {
    if (!editingAgent) return;
    const next = new Set(agentSkillIds);
    try {
      if (next.has(skillId)) { await unmountSkill(editingAgent.agentId, skillId); next.delete(skillId); }
      else { await mountSkill(editingAgent.agentId, skillId); next.add(skillId); }
      setAgentSkillIds(next);
    } catch (e) {
      toast('操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleSave = async () => {
    if (!formName.trim()) return;
    setSaving(true);
    try {
      if (editingAgent) {
        const cmd: UpdateAgentCommand = {
          agentName: formName, description: formDesc, modelProvider: formProvider,
          modelName: formModel, systemPrompt: formSystemPrompt || undefined,
          modelConfig: formModelConfig || undefined,
        };
        await updateAgent(editingAgent.agentId, cmd);
        toast('智能体已更新', 'success');
      } else {
        await createAgent({
          agentName: formName, description: formDesc, modelProvider: formProvider,
          modelName: formModel, status: 1,
          systemPrompt: formSystemPrompt || undefined, modelConfig: formModelConfig || undefined,
        });
        toast('智能体创建成功', 'success');
      }
      setShowModal(false);
      resetForm();
      setEditingAgent(null);
      loadAgents();
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('确认销毁此智能体？此操作不可撤销。')) return;
    setActionMenuOpen(null);
    setShowDetailDrawer(false);
    try {
      await deleteAgent(id);
      toast('智能体已销毁', 'success');
      loadAgents();
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleCopy = async (agent: AgentDTO) => {
    setActionMenuOpen(null);
    setSaving(true);
    try {
      await createAgent({
        agentName: agent.agentName + ' (副本)', description: agent.description,
        modelProvider: agent.modelProvider || 'qwen', modelName: agent.modelName || 'qwen-plus',
        systemPrompt: agent.systemPrompt, modelConfig: agent.modelConfig, status: 1,
      });
      toast('智能体已复制', 'success');
      loadAgents();
    } catch (e) {
      toast('复制失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handlePublish = async (agent: AgentDTO) => {
    setActionMenuOpen(null);
    try {
      await publishAgent({ agentId: agent.agentId, emoji: '🤖' });
      toast('已发布到广场', 'success');
    } catch (e) {
      toast('发布失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const filtered = agents.filter(a => {
    const status = String(a.status === 1 ? 'active' : a.status === 0 ? 'inactive' : 'draft');
    const matchStatus = statusFilter === 'all' || status === statusFilter;
    const matchSearch = !searchText || (a.agentName?.includes(searchText)) || (a.description?.includes(searchText)) || (a.agentId?.includes(searchText));
    return matchStatus && matchSearch;
  });

  const stats = {
    total: agents.length,
    active: agents.filter(a => a.status === 1).length,
    inactive: agents.filter(a => a.status === 0).length,
    archived: agents.filter(a => a.status === 2).length,
  };

  const getAgentStatus = (a: AgentDTO) => a.status === 1 ? 'active' : a.status === 0 ? 'inactive' : 'draft';

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">智能体管理</h1>
          <p className="text-ink-400 text-sm mt-1">创建、管理和监控智能体全生命周期</p>
        </div>
        <button onClick={openCreate} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" />创建智能体
        </button>
      </header>

      <div className="grid grid-cols-4 gap-4 mb-6">
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">智能体总数</p><p className="text-2xl font-bold text-ink-50">{stats.total}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">运行中</p><p className="text-2xl font-bold text-green-400">{stats.active}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">已暂停</p><p className="text-2xl font-bold text-gold-400">{stats.inactive}</p></div>
        <div className="glass-dark rounded-xl p-4"><p className="text-xs text-ink-500 mb-1">已下架</p><p className="text-2xl font-bold text-ink-400">{stats.archived}</p></div>
      </div>

      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="搜索智能体..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <CustomSelect value={statusFilter} onChange={setStatusFilter} className="w-28" options={[
          { value: 'all', label: '所有状态' },
          { value: 'active', label: '运行中' },
          { value: 'inactive', label: '已暂停' },
          { value: 'archived', label: '已下架' },
        ]} />
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无智能体数据</div>
      ) : (
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {filtered.map(agent => {
          const statusKey = getAgentStatus(agent);
          const status = statusConfig[statusKey] || statusConfig.draft;
          return (
            <div key={agent.agentId} className="glass-dark rounded-xl p-5 card-hover">
              <div className="flex items-start justify-between mb-3">
                <div className="flex items-center gap-3">
                  <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${status.bg}`}>
                    <Bot className={`w-5 h-5 ${status.color}`} />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="text-sm font-semibold text-ink-50">{agent.agentName}</h3>
                      <span className={`flex items-center gap-1 text-xs ${status.color}`}>{status.icon}{status.label}</span>
                    </div>
                    <p className="text-xs text-ink-500 mt-0.5">{agent.agentId}</p>
                  </div>
                </div>
                <div className="relative">
                  <button onClick={() => setActionMenuOpen(actionMenuOpen === agent.agentId ? null : agent.agentId)} className="p-1 text-ink-500 hover:text-ink-300 rounded transition-colors">
                    <MoreVertical className="w-4 h-4" />
                  </button>
                  {actionMenuOpen === agent.agentId && (
                     <div className="absolute right-0 top-full mt-1 w-44 glass-dark rounded-lg border border-tech-500/10 shadow-xl z-50 py-1">
                      <button onClick={() => { setSelectedAgent(agent); listAgentSkills(agent.agentId).then(setDetailSkills).catch(() => setDetailSkills([])); setShowDetailDrawer(true); setActionMenuOpen(null); }} className="w-full flex items-center gap-2 px-3 py-2 text-xs text-ink-300 hover:bg-tech-500/10"><Eye className="w-3.5 h-3.5" />查看详情</button>
                      <button onClick={() => { dispatch(setPendingAgentId(agent.agentId)); dispatch(setPage('chat')); setActionMenuOpen(null); }} className="w-full flex items-center gap-2 px-3 py-2 text-xs text-tech-400 hover:bg-tech-500/10"><MessageSquare className="w-3.5 h-3.5" />开始对话</button>
                      <button onClick={() => openEdit(agent)} className="w-full flex items-center gap-2 px-3 py-2 text-xs text-ink-300 hover:bg-tech-500/10"><Edit className="w-3.5 h-3.5" />编辑配置</button>
                      <button onClick={() => handleCopy(agent)} className="w-full flex items-center gap-2 px-3 py-2 text-xs text-ink-300 hover:bg-tech-500/10"><Copy className="w-3.5 h-3.5" />复制智能体</button>
                      <button onClick={() => handlePublish(agent)} className="w-full flex items-center gap-2 px-3 py-2 text-xs text-ink-300 hover:bg-tech-500/10"><Upload className="w-3.5 h-3.5" />发布到广场</button>
                      <button onClick={() => handleDelete(agent.agentId)} className="w-full flex items-center gap-2 px-3 py-2 text-xs text-cinnabar-400 hover:bg-tech-500/10"><Trash2 className="w-3.5 h-3.5" />销毁</button>
                    </div>
                  )}
                </div>
              </div>
              <p className="text-xs text-ink-500 mb-3 line-clamp-2">{agent.description}</p>
              <div className="flex items-center gap-4 text-xs text-ink-500">
                <span>{agent.modelProvider || '-'} / {agent.modelName || '-'}</span>
                <span>{agent.conversations || 0} 次对话</span>
              </div>
            </div>
          );
        })}
      </div>
      )}

      {showModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-2xl p-6 shadow-2xl animate-fade-up border border-tech-500/10 max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-lg font-semibold text-ink-50">{editingAgent ? '编辑智能体' : '创建智能体'}</h3>
              <button onClick={() => setShowModal(false)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div><label className="block text-xs text-ink-400 mb-1">智能体名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={formName} onChange={e => setFormName(e.target.value)} placeholder="输入智能体名称" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">模型提供商</label><CustomSelect value={formProvider} onChange={setFormProvider} options={[{ value: 'qwen', label: '通义千问' }, { value: 'deepseek', label: 'DeepSeek' }, { value: 'zhipu', label: '智谱' }]} /></div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div><label className="block text-xs text-ink-400 mb-1">模型名称</label><CustomSelect value={formModel} onChange={setFormModel} options={[{ value: 'qwen-plus', label: 'qwen-plus' }, { value: 'qwen-turbo', label: 'qwen-turbo' }, { value: 'deepseek-chat', label: 'deepseek-chat' }]} /></div>
                <div><label className="block text-xs text-ink-400 mb-1">模型参数(JSON)</label><input type="text" value={formModelConfig} onChange={e => setFormModelConfig(e.target.value)} placeholder='{"temperature":0.7}' className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
              </div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><textarea rows={2} value={formDesc} onChange={e => setFormDesc(e.target.value)} placeholder="描述智能体的功能和用途" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">系统提示词</label><textarea rows={4} value={formSystemPrompt} onChange={e => setFormSystemPrompt(e.target.value)} placeholder="定义智能体的角色、能力与行为约束" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none" /></div>
              {editingAgent && (
              <div>
                <label className="block text-xs text-ink-400 mb-2">已挂载技能 <span className="text-ink-500">（点击挂载/卸载，内容将注入对话）</span></label>
                <div className="flex flex-wrap gap-2 max-h-32 overflow-y-auto scrollbar-thin">
                  {allSkills.length === 0 ? <span className="text-xs text-ink-500">暂无可挂载技能</span> : allSkills.map(sk => (
                    <button key={sk.skillId} type="button" onClick={() => handleToggleSkill(sk.skillId)} className={`px-2.5 py-1 text-xs rounded-lg border transition-all ${agentSkillIds.has(sk.skillId) ? 'bg-tech-500/15 text-tech-400 border-tech-500/30' : 'bg-ink-800/40 text-ink-400 border-tech-500/10 hover:border-tech-500/20'}`}>{sk.emoji || '⚡'} {sk.skillName}{agentSkillIds.has(sk.skillId) ? ' ✓' : ''}</button>
                  ))}
                </div>
              </div>
              )}
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSave} disabled={saving || !formName.trim()} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">
                {saving && <Loader2 className="w-4 h-4 animate-spin" />}{editingAgent ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}

      {showDetailDrawer && selectedAgent && (
        <div className="fixed inset-0 z-50 flex justify-end modal-overlay" onClick={() => setShowDetailDrawer(false)}>
          <div className="w-full max-w-lg h-full overflow-y-auto glass-dark border-l border-tech-500/10 p-6" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-lg font-semibold text-ink-50">智能体详情</h3>
              <button onClick={() => setShowDetailDrawer(false)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-6">
              <div className="flex items-center gap-4">
                <div className={`w-14 h-14 rounded-xl flex items-center justify-center ${(statusConfig[getAgentStatus(selectedAgent)] || statusConfig.draft).bg}`}>
                  <Bot className={`w-7 h-7 ${(statusConfig[getAgentStatus(selectedAgent)] || statusConfig.draft).color}`} />
                </div>
                <div><h4 className="text-base font-semibold text-ink-50">{selectedAgent.agentName}</h4><p className="text-xs text-ink-500">{selectedAgent.agentId}</p></div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-ink-800/40 rounded-lg p-3"><p className="text-xs text-ink-500 mb-1">状态</p><p className={`text-sm font-medium ${(statusConfig[getAgentStatus(selectedAgent)] || statusConfig.draft).color}`}>{(statusConfig[getAgentStatus(selectedAgent)] || statusConfig.draft).label}</p></div>
                <div className="bg-ink-800/40 rounded-lg p-3"><p className="text-xs text-ink-500 mb-1">对话次数</p><p className="text-sm font-medium text-ink-200">{selectedAgent.conversations || 0}</p></div>
                <div className="bg-ink-800/40 rounded-lg p-3"><p className="text-xs text-ink-500 mb-1">模型</p><p className="text-sm font-medium text-ink-200">{selectedAgent.modelProvider || '-'}/{selectedAgent.modelName || '-'}</p></div>
                <div className="bg-ink-800/40 rounded-lg p-3"><p className="text-xs text-ink-500 mb-1">版本</p><p className="text-sm font-medium text-ink-200">v{selectedAgent.version ?? 0}</p></div>
                <div className="bg-ink-800/40 rounded-lg p-3"><p className="text-xs text-ink-500 mb-1">创建时间</p><p className="text-sm font-medium text-ink-200">{formatDate(selectedAgent.createdAt)}</p></div>
                <div className="bg-ink-800/40 rounded-lg p-3"><p className="text-xs text-ink-500 mb-1">创建者</p><p className="text-sm font-medium text-ink-200">{selectedAgent.createdBy || '-'}</p></div>
              </div>
              <div><h5 className="text-sm font-medium text-ink-300 mb-2">描述</h5><p className="text-sm text-ink-400">{selectedAgent.description || '无描述'}</p></div>
              {selectedAgent.systemPrompt ? (
              <div><h5 className="text-sm font-medium text-ink-300 mb-2">系统提示词</h5><p className="text-sm text-ink-400 whitespace-pre-wrap">{selectedAgent.systemPrompt}</p></div>
              ) : null}
              {selectedAgent.modelConfig ? (
              <div><h5 className="text-sm font-medium text-ink-300 mb-2">模型参数</h5><p className="text-sm text-ink-400 font-mono">{selectedAgent.modelConfig}</p></div>
              ) : null}
              <div><h5 className="text-sm font-medium text-ink-300 mb-2">已挂载技能</h5>
                {detailSkills.length === 0 ? <p className="text-sm text-ink-500">未挂载技能</p> : (
                <div className="flex flex-wrap gap-2">
                  {detailSkills.map(sk => <span key={sk.skillId} className="px-2.5 py-1 text-xs bg-tech-500/10 text-tech-400 rounded-lg border border-tech-500/15">{sk.emoji || '⚡'} {sk.skillName}</span>)}
                </div>
                )}
              </div>
              <div><h5 className="text-sm font-medium text-ink-300 mb-2">生命周期操作</h5>
                <button onClick={() => handleDelete(selectedAgent.agentId)} className="flex items-center gap-1.5 px-3 py-1.5 text-xs bg-cinnabar-500/10 text-cinnabar-400 border border-cinnabar-500/20 rounded-lg hover:bg-cinnabar-500/20 transition-colors"><Trash2 className="w-3 h-3" />销毁</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
