'use client';
import { useState, useEffect, useCallback } from 'react';
import { Search, Download, Loader2 } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { listSkillLibrary, installSkill, type SkillDTO } from '@/lib/skill';

export default function SkillLibraryPage() {
  const toast = useToast();
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchText, setSearchText] = useState('');
  const [installing, setInstalling] = useState<string | null>(null);

  const loadSkills = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listSkillLibrary();
      setSkills(data || []);
    } catch (e) {
      console.error('加载技能库失败:', e);
      setSkills([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadSkills(); }, [loadSkills]);

  const handleInstall = async (id: string) => {
    setInstalling(id);
    try {
      await installSkill(id);
      toast('安装成功', 'success');
    } catch (e) {
      toast('安装失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setInstalling(null);
    }
  };

  const filtered = skills.filter(s => !searchText || s.skillName?.includes(searchText) || s.description?.includes(searchText));

  return (
    <div>
      <header className="mb-8"><h1 className="text-2xl font-semibold text-ink-50">技能库</h1><p className="text-ink-400 text-sm mt-1">浏览和集成社区共享的技能模块</p></header>
      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="搜索技能..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
      </div>
      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无技能</div>
      ) : (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {filtered.map(skill => (
          <div key={skill.skillId} className="glass-dark rounded-xl p-5 card-hover">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 rounded-lg flex items-center justify-center bg-tech-500/10 text-tech-400">
                <Download className="w-5 h-5" />
              </div>
              <div><h3 className="text-sm font-semibold text-ink-50">{skill.skillName}</h3><p className="text-xs text-ink-500">{skill.description || '无描述'}</p></div>
            </div>
            <div className="flex items-center justify-between mt-3">
              <span className="text-xs text-ink-500">👤 {skill.installCount || 0} 次安装</span>
              <button onClick={() => handleInstall(skill.skillId)} disabled={installing === skill.skillId} className="px-3 py-1 text-xs btn-primary text-white rounded-lg disabled:opacity-50 flex items-center gap-1">
                {installing === skill.skillId && <Loader2 className="w-3 h-3 animate-spin" />}安装
              </button>
            </div>
          </div>
        ))}
      </div>
      )}
    </div>
  );
}
