'use client';
import { useState, useEffect, useCallback } from 'react';
import { CheckCircle, XCircle, Loader2, ShieldCheck } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { listPendingSkills, auditSkill, type SkillDTO } from '@/lib/skill';

function formatDate(ts?: number): string {
  if (!ts) return '-';
  try { return new Date(ts).toLocaleString('zh-CN'); } catch { return '-'; }
}

export default function SkillAuditPage() {
  const toast = useToast();
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [auditing, setAuditing] = useState<string | null>(null);

  const loadSkills = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listPendingSkills();
      setSkills(data || []);
    } catch (e) {
      console.error('加载待审核技能失败:', e);
      setSkills([]);
      toast('加载失败，可能无管理员权限', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { loadSkills(); }, [loadSkills]);

  const handleAudit = async (id: string, approved: boolean) => {
    let reason: string | undefined;
    if (!approved) {
      reason = prompt('请输入拒绝原因') || '';
      if (!reason.trim()) { toast('拒绝需填写原因', 'error'); return; }
    }
    setAuditing(id);
    try {
      await auditSkill(id, approved, reason);
      toast(approved ? '已通过审核，技能已进入公共库' : '已拒绝', 'success');
      loadSkills();
    } catch (e) {
      toast('审核失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setAuditing(null);
    }
  };

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><ShieldCheck className="w-6 h-6 text-tech-400" />技能审核</h1>
        <p className="text-ink-400 text-sm mt-1">审核用户提交发布的技能，通过后进入公共技能库</p>
      </header>
      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : skills.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无待审核技能</div>
      ) : (
      <div className="space-y-4">
        {skills.map(skill => (
          <div key={skill.skillId} className="glass-dark rounded-xl p-5">
            <div className="flex items-start justify-between mb-3">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center text-tech-400 text-lg">{skill.emoji || '⚡'}</div>
                <div>
                  <h3 className="text-sm font-semibold text-ink-50">{skill.skillName}</h3>
                  <p className="text-xs text-ink-500">{skill.category || '未分类'} · v{skill.version ?? 1} · {formatDate(skill.createdAt)} · 创建者: {skill.createdBy || '-'}</p>
                </div>
              </div>
              <span className="text-xs px-2 py-1 rounded bg-gold-500/10 text-gold-400">待审核</span>
            </div>
            {skill.description ? <p className="text-xs text-ink-400 mb-2">{skill.description}</p> : null}
            {skill.content ? (
              <div className="bg-ink-800/40 rounded-lg p-3 mb-3">
                <p className="text-[10px] text-ink-500 mb-1">技能内容</p>
                <p className="text-xs text-ink-300 whitespace-pre-wrap max-h-40 overflow-y-auto scrollbar-thin">{skill.content}</p>
              </div>
            ) : null}
            {skill.tags && skill.tags.length > 0 ? (
              <div className="flex flex-wrap gap-1.5 mb-3">
                {skill.tags.map(t => <span key={t} className="text-[10px] px-1.5 py-0.5 bg-ink-800/50 text-ink-400 rounded">{t}</span>)}
              </div>
            ) : null}
            <div className="flex gap-2">
              <button onClick={() => handleAudit(skill.skillId, true)} disabled={auditing === skill.skillId} className="flex items-center gap-1 px-4 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50"><CheckCircle className="w-3 h-3" />通过</button>
              <button onClick={() => handleAudit(skill.skillId, false)} disabled={auditing === skill.skillId} className="flex items-center gap-1 px-4 py-1.5 text-xs border border-cinnabar-500/20 text-cinnabar-400 rounded-lg hover:bg-cinnabar-500/10 disabled:opacity-50"><XCircle className="w-3 h-3" />拒绝</button>
            </div>
          </div>
        ))}
      </div>
      )}
    </div>
  );
}
