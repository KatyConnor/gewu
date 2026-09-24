'use client';
/**
 * 智能体创建/编辑共用弹窗（从 AgentManagePage 抽取，供智能体管理与我的智能体两页复用）。
 * editingAgent 传值 = 编辑模式（含技能挂载管理），传 null = 创建模式。
 */
import { useState, useEffect } from 'react';
import { X, Loader2 } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import { createAgent, updateAgent, listAgentSkills, mountSkill, unmountSkill, type AgentDTO, type UpdateAgentCommand } from '@/lib/agent';
import { listSkillLibrary, type SkillDTO } from '@/lib/skill';

interface AgentEditModalProps {
  visible: boolean;
  editingAgent: AgentDTO | null;
  onClose: () => void;
  /** 保存成功后回调（父页面刷新列表） */
  onSaved: () => void;
}

export default function AgentEditModal({ visible, editingAgent, onClose, onSaved }: AgentEditModalProps) {
  const toast = useToast();
  const [formName, setFormName] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [formProvider, setFormProvider] = useState('qwen');
  const [formModel, setFormModel] = useState('qwen-plus');
  const [formSystemPrompt, setFormSystemPrompt] = useState('');
  const [formModelConfig, setFormModelConfig] = useState('');
  const [saving, setSaving] = useState(false);
  const [allSkills, setAllSkills] = useState<SkillDTO[]>([]);
  const [agentSkillIds, setAgentSkillIds] = useState<Set<string>>(new Set());

  // 打开时回填表单（编辑）或重置（创建），编辑模式同时加载技能库与已挂载技能
  useEffect(() => {
    if (!visible) return;
    if (editingAgent) {
      setFormName(editingAgent.agentName);
      setFormDesc(editingAgent.description || '');
      setFormProvider(editingAgent.modelProvider || 'qwen');
      setFormModel(editingAgent.modelName || 'qwen-plus');
      setFormSystemPrompt(editingAgent.systemPrompt || '');
      setFormModelConfig(editingAgent.modelConfig || '');
      listSkillLibrary().then(setAllSkills).catch(() => setAllSkills([]));
      listAgentSkills(editingAgent.agentId).then(s => setAgentSkillIds(new Set(s.map(k => k.skillId)))).catch(() => setAgentSkillIds(new Set()));
    } else {
      setFormName(''); setFormDesc(''); setFormProvider('qwen'); setFormModel('qwen-plus');
      setFormSystemPrompt(''); setFormModelConfig('');
    }
  }, [visible, editingAgent]);

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
      onClose();
      onSaved();
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  if (!visible) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={onClose}>
      <div className="glass-dark rounded-2xl w-full max-w-2xl p-6 shadow-2xl animate-fade-up border border-tech-500/10 max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-semibold text-ink-50">{editingAgent ? '编辑智能体' : '创建智能体'}</h3>
          <button onClick={onClose} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
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
          <button onClick={onClose} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
          <button onClick={handleSave} disabled={saving || !formName.trim()} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">
            {saving && <Loader2 className="w-4 h-4 animate-spin" />}{editingAgent ? '保存' : '创建'}
          </button>
        </div>
      </div>
    </div>
  );
}
