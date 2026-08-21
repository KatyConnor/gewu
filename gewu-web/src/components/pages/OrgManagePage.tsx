'use client';
import { useState, useEffect, useCallback } from 'react';
import { Loader2, X, Plus, Trash2, Building2, Edit3, ChevronRight, ChevronDown, Users } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { listOrgTree, createOrg, updateOrg, deleteOrg, type OrgDTO } from '@/lib/org';

export default function OrgManagePage() {
  const toast = useToast();
  const [orgs, setOrgs] = useState<OrgDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [showModal, setShowModal] = useState(false);
  const [editingOrg, setEditingOrg] = useState<OrgDTO | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({ parentId: '', orgName: '', orgCode: '', sortOrder: 0 });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listOrgTree();
      setOrgs(data || []);
      // 默认展开所有节点
      const allIds = new Set<string>();
      const collect = (items: OrgDTO[]) => items.forEach(o => { allIds.add(o.orgId); collect(o.children || []); });
      collect(data || []);
      setExpandedIds(allIds);
    } catch {
      toast('加载失败，可能无机构管理权限', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const toggleExpand = (id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const openCreate = (parentId?: string) => {
    setEditingOrg(null);
    setForm({ parentId: parentId || '', orgName: '', orgCode: '', sortOrder: 0 });
    setShowModal(true);
  };

  const openEdit = (org: OrgDTO) => {
    setEditingOrg(org);
    setForm({ parentId: org.parentId || '', orgName: org.orgName, orgCode: org.orgCode, sortOrder: org.sortOrder || 0 });
    setShowModal(true);
  };

  const handleSave = async () => {
    if (!form.orgName.trim() || !form.orgCode.trim()) return;
    setSaving(true);
    try {
      const data = {
        parentId: form.parentId || undefined,
        orgName: form.orgName,
        orgCode: form.orgCode,
        sortOrder: form.sortOrder,
      };
      if (editingOrg) {
        await updateOrg(editingOrg.orgId, data);
        toast('机构已更新', 'success');
      } else {
        await createOrg(data);
        toast('机构已创建', 'success');
      }
      setShowModal(false);
      load();
    } catch (e) {
      toast((editingOrg ? '更新' : '创建') + '失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (org: OrgDTO) => {
    if (!confirm(`确认删除机构「${org.orgName}」？`)) return;
    try {
      await deleteOrg(org.orgId);
      toast('机构已删除', 'success');
      load();
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  // 扁平化用于父机构下拉
  const flatOrgs: { id: string; label: string; depth: number }[] = [];
  const collectFlat = (items: OrgDTO[], depth: number) => {
    items.forEach(o => {
      flatOrgs.push({ id: o.orgId, label: o.orgName, depth });
      collectFlat(o.children || [], depth + 1);
    });
  };
  collectFlat(orgs, 0);

  const renderTree = (items: OrgDTO[], depth: number): React.ReactNode => {
    return items.map(org => {
      const hasChildren = org.children && org.children.length > 0;
      const isExpanded = expandedIds.has(org.orgId);
      return (
        <div key={org.orgId}>
          <div
            className="flex items-center gap-2 px-2 py-2 rounded hover:bg-tech-500/5 transition-colors"
            style={{ paddingLeft: `${depth * 20 + 8}px` }}
          >
            {hasChildren ? (
              <button onClick={() => toggleExpand(org.orgId)} className="text-ink-500 hover:text-tech-400 flex-shrink-0">
                {isExpanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
              </button>
            ) : (
              <span className="w-3.5 flex-shrink-0" />
            )}
            <Building2 className="w-4 h-4 text-tech-400/70 flex-shrink-0" />
            <span className="text-sm text-ink-200 flex-1 truncate">{org.orgName}</span>
            <span className="text-[10px] text-ink-500 flex-shrink-0">{org.orgCode}</span>
            <span className="text-[10px] text-tech-400/60 flex items-center gap-0.5 flex-shrink-0">
              <Users className="w-3 h-3" />{org.userCount || 0}
            </span>
            <button onClick={() => openCreate(org.orgId)} className="p-1 text-ink-500 hover:text-tech-400 flex-shrink-0" title="添加子机构">
              <Plus className="w-3.5 h-3.5" />
            </button>
            <button onClick={() => openEdit(org)} className="p-1 text-ink-500 hover:text-tech-400 flex-shrink-0" title="编辑">
              <Edit3 className="w-3.5 h-3.5" />
            </button>
            <button onClick={() => handleDelete(org)} className="p-1 text-ink-500 hover:text-cinnabar-400 flex-shrink-0" title="删除">
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
          {hasChildren && isExpanded && renderTree(org.children, depth + 1)}
        </div>
      );
    });
  };

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2">
            <Building2 className="w-6 h-6 text-tech-400" />机构管理
          </h1>
          <p className="text-ink-400 text-sm mt-1">管理组织架构树</p>
        </div>
        <button onClick={() => openCreate()} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" />创建机构
        </button>
      </header>

      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-6 h-6 text-tech-400 animate-spin" />
        </div>
      ) : orgs.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无机构数据</div>
      ) : (
        <div className="glass-dark rounded-xl p-4">
          {renderTree(orgs, 0)}
        </div>
      )}

      {showModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">{editingOrg ? '编辑机构' : '创建机构'}</h3>
              <button onClick={() => setShowModal(false)} className="text-ink-500 hover:text-ink-300">
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="space-y-3">
              <div>
                <label className="block text-xs text-ink-400 mb-1">父机构</label>
                <select
                  value={form.parentId}
                  onChange={e => setForm({ ...form, parentId: e.target.value })}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                >
                  <option value="">（顶级机构）</option>
                  {flatOrgs.filter(o => o.id !== editingOrg?.orgId).map(o => (
                    <option key={o.id} value={o.id}>
                      {'　'.repeat(o.depth)}{o.label}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">机构名称 <span className="text-cinnabar-400">*</span></label>
                <input
                  value={form.orgName}
                  onChange={e => setForm({ ...form, orgName: e.target.value })}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">机构编码 <span className="text-cinnabar-400">*</span></label>
                <input
                  value={form.orgCode}
                  onChange={e => setForm({ ...form, orgCode: e.target.value })}
                  placeholder="如 HQ / RND"
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">排序号</label>
                <input
                  type="number"
                  value={form.sortOrder}
                  onChange={e => setForm({ ...form, sortOrder: Number(e.target.value) })}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                />
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setShowModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button
                onClick={handleSave}
                disabled={saving || !form.orgName.trim() || !form.orgCode.trim()}
                className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2"
              >
                {saving && <Loader2 className="w-4 h-4 animate-spin" />}
                {editingOrg ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
