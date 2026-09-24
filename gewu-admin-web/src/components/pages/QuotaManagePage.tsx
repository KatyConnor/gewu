'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus, X, Edit3, Trash2, UserPlus } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import CustomSelect from '@/components/ui/Select';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card';
import { getUser } from '@/lib/token';
import { listUsers, type UserDTO } from '@/lib/user';
import {
  listQuotaPlans, createQuotaPlan, updateQuotaPlan, deleteQuotaPlan,
  bindUserPlan, unbindUserPlan,
  QUOTA_WINDOW_LABELS,
  type QuotaPlan, type QuotaWindowItem,
} from '@/lib/quota';

const WINDOW_ORDER = ['FIVE_HOUR', 'WEEK', 'MONTH', 'QUARTER'] as const;

/** 套餐管理（管理员）：配额总量账本下发——套餐 CRUD + 用户绑定 */
export default function QuotaManagePage() {
  const toast = useToast();
  const isAdmin = (getUser()?.roles || []).includes('ADMIN');
  const [plans, setPlans] = useState<QuotaPlan[]>([]);
  const [showModal, setShowModal] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState({ planName: '', description: '', status: 1, items: initItems() });
  const [bindUserId, setBindUserId] = useState<Record<string, string>>({});
  const [users, setUsers] = useState<UserDTO[]>([]);

  function initItems(): QuotaWindowItem[] {
    return WINDOW_ORDER.map(w => ({ windowType: w, tokenLimit: 0 }));
  }

  const loadData = useCallback(async () => {
    try {
      const [planList, userPage] = await Promise.all([
        listQuotaPlans(),
        listUsers(1, 100).catch(() => null),
      ]);
      setPlans(planList);
      if (userPage?.records) setUsers(userPage.records);
    } catch (err) {
      toast(err instanceof Error ? err.message : '加载套餐失败', 'error');
    }
  }, [toast]);

  useEffect(() => { if (isAdmin) loadData(); }, [isAdmin, loadData]);

  const openCreate = () => {
    setEditingId(null);
    setForm({ planName: '', description: '', status: 1, items: initItems() });
    setShowModal(true);
  };

  const openEdit = (plan: QuotaPlan) => {
    setEditingId(plan.id);
    const items = WINDOW_ORDER.map(w => {
      const found = plan.items.find(i => i.windowType === w);
      return { windowType: w, tokenLimit: found?.tokenLimit ?? 0 };
    });
    setForm({ planName: plan.planName, description: plan.description || '', status: plan.status, items });
    setShowModal(true);
  };

  const handleSave = async () => {
    if (!form.planName.trim()) {
      toast('请填写套餐名称', 'error');
      return;
    }
    const items = form.items.filter(i => i.tokenLimit > 0);
    if (items.length === 0) {
      toast('至少配置一个窗口的 token 上限', 'error');
      return;
    }
    const payload = {
      planName: form.planName,
      description: form.description || undefined,
      status: form.status,
      items,
    };
    try {
      if (editingId) {
        await updateQuotaPlan(editingId, payload);
        toast('套餐已更新', 'success');
      } else {
        await createQuotaPlan(payload);
        toast('套餐已创建', 'success');
      }
      setShowModal(false);
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '保存失败', 'error');
    }
  };

  const handleDelete = async (planId: string) => {
    try {
      await deleteQuotaPlan(planId);
      toast('套餐已删除', 'success');
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '删除失败', 'error');
    }
  };

  const handleBind = async (planId: string) => {
    const userId = (bindUserId[planId] || '').trim();
    if (!userId) {
      toast('请输入用户 ID', 'error');
      return;
    }
    try {
      await bindUserPlan(userId, planId);
      toast('已绑定', 'success');
      setBindUserId(prev => ({ ...prev, [planId]: '' }));
    } catch (err) {
      toast(err instanceof Error ? err.message : '绑定失败', 'error');
    }
  };

  const handleUnbind = async (planId: string) => {
    try {
      await unbindUserPlan(planId);
      toast('已解绑', 'success');
    } catch (err) {
      toast(err instanceof Error ? err.message : '解绑失败', 'error');
    }
  };

  if (!isAdmin) {
    return (
      <div className="p-8">
        <p className="text-sm text-ink-400">仅管理员可访问配额套餐管理。</p>
      </div>
    );
  }

  return (
    <div>
      <header className="mb-8 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">配额套餐管理</h1>
          <p className="text-ink-400 text-sm mt-1">为用户下发 5 小时 / 周 / 月 / 季维度的 token 总量账本</p>
        </div>
        <button onClick={openCreate} className="flex items-center gap-1.5 px-4 py-2 btn-primary text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" />新增套餐
        </button>
      </header>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {plans.map(plan => (
          <Card key={plan.id}>
            <CardHeader>
              <CardTitle className="flex items-center justify-between">
                <span className="flex items-center gap-2">
                  {plan.planName}
                  <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${plan.status === 1 ? 'bg-green-500/10 text-green-400' : 'bg-ink-700 text-ink-500'}`}>
                    {plan.status === 1 ? '启用' : '停用'}
                  </span>
                </span>
                <span className="flex items-center gap-1">
                  <button onClick={() => openEdit(plan)} className="p-1 text-ink-500 hover:text-tech-400 rounded" title="编辑"><Edit3 className="w-3.5 h-3.5" /></button>
                  <button onClick={() => handleDelete(plan.id)} className="p-1 text-ink-600 hover:text-cinnabar-400 rounded" title="删除"><Trash2 className="w-3.5 h-3.5" /></button>
                </span>
              </CardTitle>
            </CardHeader>
            <CardContent>
              {plan.description && <p className="text-xs text-ink-500 mb-3">{plan.description}</p>}
              <div className="space-y-1.5 mb-4">
                {plan.items.map(i => (
                  <div key={i.windowType} className="flex items-center justify-between text-xs">
                    <span className="text-ink-400">{QUOTA_WINDOW_LABELS[i.windowType as keyof typeof QUOTA_WINDOW_LABELS] || i.windowType}</span>
                    <span className="text-ink-200 font-mono">{i.tokenLimit.toLocaleString()} tokens</span>
                  </div>
                ))}
              </div>
              <div className="pt-3 border-t border-glass-border">
                <p className="text-[11px] text-ink-600 mb-2">绑定用户（输入用户 ID）</p>
                <div className="flex items-center gap-2">
                  <div className="flex-1">
                    <CustomSelect value={bindUserId[plan.id] || ''}
                      onChange={v => setBindUserId(prev => ({ ...prev, [plan.id]: v }))}
                      options={[{ value: '', label: '选择用户' },
                        ...users.map(u => ({ value: u.userId, label: `${u.username}${u.displayName ? `（${u.displayName}）` : ''}` }))]} />
                  </div>
                  <button onClick={() => handleBind(plan.id)} className="flex items-center gap-1 px-3 py-2 text-xs border border-tech-500/20 text-tech-400 rounded-lg hover:bg-tech-500/10">
                    <UserPlus className="w-3.5 h-3.5" />绑定
                  </button>
                  <button onClick={() => handleUnbind(plan.id)} className="px-3 py-2 text-xs text-ink-400 border border-ink-700/60 rounded-lg hover:text-ink-200">解绑</button>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
        {plans.length === 0 && <p className="text-sm text-ink-500">暂无套餐，点击右上角"新增套餐"创建。</p>}
      </div>

      {/* 套餐编辑弹窗 */}
      {showModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-lg p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="text-base font-semibold text-ink-50">{editingId ? '编辑套餐' : '新增套餐'}</h3>
              <button onClick={() => setShowModal(false)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-4">
              <div><label className="block text-xs text-ink-400 mb-1">套餐名称 <span className="text-cinnabar-400">*</span></label>
                <input type="text" value={form.planName} onChange={e => setForm({ ...form, planName: e.target.value })}
                  placeholder="如：标准版 / 专业版" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label>
                <input type="text" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })}
                  className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div>
                <label className="block text-xs text-ink-400 mb-2">各窗口 token 总量上限（0 或留空 = 该窗口不限）</label>
                <div className="space-y-2">
                  {form.items.map((item, idx) => (
                    <div key={item.windowType} className="flex items-center gap-3">
                      <span className="w-16 text-xs text-ink-300 flex-shrink-0">{QUOTA_WINDOW_LABELS[item.windowType as keyof typeof QUOTA_WINDOW_LABELS] || item.windowType}</span>
                      <input type="number" min={0} value={item.tokenLimit || ''}
                        onChange={e => setForm(prev => ({
                          ...prev,
                          items: prev.items.map((it, i) => i === idx ? { ...it, tokenLimit: Number(e.target.value) || 0 } : it),
                        }))}
                        placeholder="如 5000000" className="flex-1 px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 font-mono text-xs" />
                    </div>
                  ))}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <input type="checkbox" id="plan-status" checked={form.status === 1}
                  onChange={e => setForm({ ...form, status: e.target.checked ? 1 : 2 })} className="w-4 h-4 rounded accent-tech-500" />
                <label htmlFor="plan-status" className="text-sm text-ink-200">启用</label>
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSave} className="px-5 py-2 btn-primary text-white text-sm rounded-lg">保存</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
