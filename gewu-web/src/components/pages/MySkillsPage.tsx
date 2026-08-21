'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus, Settings, Trash2, Loader2, X, Upload } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import { listMySkills, uninstallSkill, createSkill, updateSkill, publishSkill, type SkillDTO, type CreateSkillCommand, type UpdateSkillCommand } from '@/lib/skill';

function formatDate(ts?: number): string {
  if (!ts) return '-';
  try { return new Date(ts).toLocaleDateString('zh-CN'); } catch { return '-'; }
}

export default function MySkillsPage() {
  const toast = useToast();
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editingSkill, setEditingSkill] = useState<SkillDTO | null>(null);
  const [formName, setFormName] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [formCategory, setFormCategory] = useState('');
  const [formContent, setFormContent] = useState('');
  const [formEmoji, setFormEmoji] = useState('⚡');
  const [formStatus, setFormStatus] = useState('1');
  const [saving, setSaving] = useState(false);

  const loadSkills = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listMySkills();
      setSkills(data || []);
    } catch (e) {
      console.error('加载技能失败:', e);
      setSkills([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadSkills(); }, [loadSkills]);

  const resetForm = () => {
    setFormName(''); setFormDesc(''); setFormCategory(''); setFormContent(''); setFormEmoji('⚡'); setFormStatus('1');
  };

  const openCreate = () => { resetForm(); setEditingSkill(null); setShowModal(true); };

  const openEdit = (skill: SkillDTO) => {
    setEditingSkill(skill);
    setFormName(skill.skillName);
    setFormDesc(skill.description || '');
    setFormCategory(skill.category || '');
    setFormContent(skill.content || '');
    setFormEmoji(skill.emoji || '⚡');
    setFormStatus(String(skill.status ?? 1));
    setShowModal(true);
  };

  const handleSave = async () => {
    if (!formName.trim()) return;
    setSaving(true);
    try {
      if (editingSkill) {
        const cmd: UpdateSkillCommand = {
          skillName: formName, description: formDesc, category: formCategory || undefined,
          content: formContent, emoji: formEmoji, status: Number(formStatus),
        };
        await updateSkill(editingSkill.skillId, cmd);
        toast('技能已更新', 'success');
      } else {
        const cmd: CreateSkillCommand = {
          skillName: formName, description: formDesc, category: formCategory || undefined,
          content: formContent, emoji: formEmoji, status: Number(formStatus),
        };
        await createSkill(cmd);
        toast('技能创建成功', 'success');
      }
      setShowModal(false);
      resetForm();
      setEditingSkill(null);
      loadSkills();
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleUninstall = async (id: string) => {
    if (!confirm('确认卸载此技能？')) return;
    try {
      await uninstallSkill(id);
      toast('技能已卸载', 'success');
      loadSkills();
    } catch (e) {
      toast('卸载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handlePublish = async (id: string) => {
    try {
      await publishSkill(id);
      toast('已提交审核，等待管理员处理', 'success');
      loadSkills();
    } catch (e) {
      toast('发布失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div><h1 className="text-2xl font-semibold text-ink-50">我的技能</h1><p className="text-ink-400 text-sm mt-1">管理您创建和安装的技能</p></div>
        <button onClick={openCreate} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"><Plus className="w-4 h-4" />创建技能</button>
      </header>
      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : skills.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无技能，点击"创建技能"添加</div>
      ) : (
      <div className="space-y-3">
        {skills.map(skill => {
          const enabled = skill.status !== 0;
          return (
            <div key={skill.skillId} className="glass-dark rounded-xl p-5 flex items-center justify-between card-hover">
              <div className="flex items-center gap-4">
                <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center text-tech-400 text-lg">{skill.emoji || '⚡'}</div>
                <div><h3 className="text-sm font-semibold text-ink-50">{skill.skillName}</h3><p className="text-xs text-ink-500">{formatDate(skill.createdAt)} · v{skill.version ?? 1}{skill.category ? ' · ' + skill.category : ''}</p></div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-xs flex items-center gap-1 ${enabled ? 'text-green-400' : 'text-ink-500'}`}>
                  <div className={`w-1.5 h-1.5 rounded-full ${enabled ? 'bg-green-400' : 'bg-ink-600'}`} />{enabled ? '已启用' : '已禁用'}
                </span>
                {skill.publishStatus === 1 ? (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-gold-500/10 text-gold-400">{skill.publishStatusDesc || '待审核'}</span>
                ) : skill.publishStatus === 2 ? (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-tech-500/10 text-tech-400">{skill.publishStatusDesc || '已发布'}</span>
                ) : skill.publishStatus === 3 ? (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-cinnabar-500/10 text-cinnabar-400">{skill.publishStatusDesc || '已拒绝'}</span>
                ) : null}
                {(skill.publishStatus === 0 || skill.publishStatus === 3) && (
                  <button onClick={() => handlePublish(skill.skillId)} className="px-3 py-1.5 text-xs border border-tech-500/15 text-tech-400 rounded-lg hover:bg-tech-500/10"><Upload className="w-3 h-3 inline mr-1" />发布</button>
                )}
                <button onClick={() => openEdit(skill)} className="px-3 py-1.5 text-xs border border-tech-500/15 text-tech-400 rounded-lg hover:bg-tech-500/10"><Settings className="w-3 h-3 inline mr-1" />配置</button>
                <button onClick={() => handleUninstall(skill.skillId)} className="px-3 py-1.5 text-xs border border-danger-500/15 text-danger-400 rounded-lg hover:bg-danger-500/10"><Trash2 className="w-3 h-3 inline mr-1" />卸载</button>
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
              <h3 className="text-lg font-semibold text-ink-50">{editingSkill ? '编辑技能' : '创建技能'}</h3>
              <button onClick={() => setShowModal(false)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div><label className="block text-xs text-ink-400 mb-1">技能名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={formName} onChange={e => setFormName(e.target.value)} placeholder="输入技能名称" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">图标</label><input type="text" value={formEmoji} onChange={e => setFormEmoji(e.target.value)} placeholder="⚡" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div><label className="block text-xs text-ink-400 mb-1">分类</label><input type="text" value={formCategory} onChange={e => setFormCategory(e.target.value)} placeholder="如：代码开发" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">状态</label><CustomSelect value={formStatus} onChange={setFormStatus} options={[{ value: '1', label: '启用' }, { value: '0', label: '禁用' }]} /></div>
              </div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><textarea rows={2} value={formDesc} onChange={e => setFormDesc(e.target.value)} placeholder="描述技能的功能和用途" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">技能内容/定义</label><textarea rows={4} value={formContent} onChange={e => setFormContent(e.target.value)} placeholder="技能的具体内容或指令定义" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSave} disabled={saving || !formName.trim()} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">
                {saving && <Loader2 className="w-4 h-4 animate-spin" />}{editingSkill ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
