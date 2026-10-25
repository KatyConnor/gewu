'use client';
import { useState, useEffect, useCallback } from 'react';
import { ClipboardList, Plus, Search, Loader2, X, Pencil, Trash2, Send } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import Modal from '@/components/ui/Modal';
import {
  listProjects, listProjectMembers, type ProjectMemberView,
} from '@/lib/project';
import {
  listRequirements, createRequirement, updateRequirement, updateRequirementStatus,
  submitRequirementReview, deleteRequirement, type AdminRequirementDTO, type RequirementPayload,
} from '@/lib/requirement';

// ==================== 常量 ====================

const REQUIREMENT_TYPE_OPTIONS = [
  { value: 'STORY', label: '用户故事' },
  { value: 'TASK', label: '任务' },
  { value: 'BUG', label: '缺陷' },
  { value: 'IMPROVEMENT', label: '改进' },
  { value: 'FEATURE', label: '功能需求' },
  { value: 'EPIC', label: '史诗' },
];

const REQUIREMENT_PRIORITY_OPTIONS = [
  { value: 0, label: 'P0 紧急' },
  { value: 1, label: 'P1 高' },
  { value: 2, label: 'P2 中' },
  { value: 3, label: 'P3 低' },
];

const REQUIREMENT_STATUS_OPTIONS = [
  'DRAFT', 'PENDING_REVIEW', 'IN_REVIEW', 'APPROVED', 'DESIGN', 'PENDING_DESIGN_REVIEW',
  'DESIGN_IN_REVIEW', 'PLANNING', 'TASK_ASSIGNED', 'PENDING_DEV', 'IN_DEV', 'DEV_COMPLETED',
  'SMOKE_TEST', 'SIT_TEST', 'UAT_TEST', 'RELEASED', 'ARCHIVED', 'CANCELLED',
];

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿', PENDING_REVIEW: '待需求评审', IN_REVIEW: '评审中', APPROVED: '已通过',
  DESIGN: '设计', PENDING_DESIGN_REVIEW: '待设计评审', DESIGN_IN_REVIEW: '设计评审中',
  PLANNING: '计划制定', TASK_ASSIGNED: '任务分配', PENDING_DEV: '待开发', IN_DEV: '开发中',
  DEV_COMPLETED: '开发完成', SMOKE_TEST: '冒烟测试', SIT_TEST: 'SIT测试', UAT_TEST: 'UAT测试',
  RELEASED: '已上线', ARCHIVED: '已归档', CANCELLED: '已取消', PROTOTYPE: '原型',
};

function statusLabel(status: string): string {
  return STATUS_LABELS[status] || status;
}

function statusColor(status: string): string {
  if (status === 'RELEASED' || status === 'APPROVED') return 'text-green-400';
  if (status === 'CANCELLED' || status === 'ARCHIVED') return 'text-ink-400';
  if (status === 'DRAFT' || status === 'PENDING_REVIEW' || status === 'DESIGN') return 'text-gold-400';
  return 'text-tech-400';
}

interface RequirementForm {
  id: string;
  title: string;
  description: string;
  type: string;
  priority: number;
  projectId: string;
  assigneeId: string;
  dueDate: string;
  storyPoint: string;
  estimatedHours: string;
  status: string;
}

const EMPTY_FORM: RequirementForm = {
  id: '', title: '', description: '', type: 'STORY', priority: 2,
  projectId: '', assigneeId: '', dueDate: '', storyPoint: '', estimatedHours: '', status: 'DRAFT',
};

// ==================== 主组件 ====================

export default function RequirementManagePage() {
  const toast = useToast();
  const [requirements, setRequirements] = useState<AdminRequirementDTO[]>([]);
  const [projects, setProjects] = useState<{ projectId: string; projectName: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [filterType, setFilterType] = useState('');
  const [filterStatus, setFilterStatus] = useState('');
  const [filterPriority, setFilterPriority] = useState<number | undefined>(undefined);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize] = useState(20);
  const [total, setTotal] = useState(0);

  const [form, setForm] = useState<RequirementForm | null>(null);
  const [members, setMembers] = useState<ProjectMemberView[]>([]);
  const [membersLoading, setMembersLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const loadRequirements = useCallback(async (kw?: string, page = currentPage) => {
    setLoading(true);
    try {
      const result = await listRequirements({
        page, size: pageSize, keyword: kw,
        type: filterType || undefined, status: filterStatus || undefined,
        priority: filterPriority,
      });
      setRequirements(result.records || []);
      setTotal(result.total || 0);
    } catch (e) {
      console.error('加载需求失败:', e);
      toast('加载失败，可能无需求管理权限', 'error');
      setRequirements([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [currentPage, pageSize, filterType, filterStatus, filterPriority, toast]);

  useEffect(() => {
    loadRequirements();
    listProjects({ page: 1, size: 100 })
      .then(result => setProjects(result.records.map(p => ({ projectId: p.projectId, projectName: p.projectName }))))
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearch = () => {
    setCurrentPage(1);
    loadRequirements(keyword, 1);
  };

  const handlePageChange = (page: number) => {
    setCurrentPage(page);
    loadRequirements(keyword, page);
  };

  // ==================== 分派：负责人限绑定项目成员 ====================

  const loadMembers = async (projectId: string) => {
    if (!projectId) { setMembers([]); return; }
    setMembersLoading(true);
    try {
      setMembers(await listProjectMembers(projectId));
    } catch {
      toast('项目成员加载失败', 'error');
      setMembers([]);
    } finally {
      setMembersLoading(false);
    }
  };

  const handleFormProjectChange = async (projectId: string) => {
    setForm(prev => prev ? { ...prev, projectId, assigneeId: '' } : prev);
    await loadMembers(projectId);
  };

  // ==================== 弹框/保存/删除 ====================

  const openCreate = () => setForm({ ...EMPTY_FORM });

  const openEdit = (r: AdminRequirementDTO) => {
    setForm({
      id: r.id,
      title: r.title,
      description: r.description || '',
      type: r.type,
      priority: r.priority,
      projectId: r.projectId,
      assigneeId: r.assigneeId || '',
      dueDate: r.dueDate ? new Date(r.dueDate).toISOString().slice(0, 10) : '',
      storyPoint: r.storyPoint != null ? String(r.storyPoint) : '',
      estimatedHours: r.estimatedHours != null ? String(r.estimatedHours) : '',
      status: r.status,
    });
    loadMembers(r.projectId);
  };

  const handleSave = async () => {
    if (!form) return;
    if (!form.title.trim()) { toast('请填写需求标题', 'error'); return; }
    if (!form.projectId) { toast('需求必须绑定项目', 'error'); return; }

    const payload: RequirementPayload = {
      title: form.title.trim(),
      description: form.description.trim() || undefined,
      type: form.type,
      priority: form.priority,
      projectId: form.projectId,
      // 空串=清空分派，非空=分派（后端校验项目成员身份）
      assigneeId: form.assigneeId,
      dueDate: form.dueDate ? new Date(form.dueDate).getTime() : undefined,
      storyPoint: form.storyPoint ? Number(form.storyPoint) : undefined,
      estimatedHours: form.estimatedHours ? Number(form.estimatedHours) : undefined,
    };

    setSaving(true);
    try {
      if (form.id) {
        const updated = await updateRequirement(form.id, payload);
        // 编辑弹框里同时改了状态则一并流转
        if (form.status && form.status !== updated.status) {
          await updateRequirementStatus(form.id, form.status);
        }
        toast('需求已更新', 'success');
      } else {
        const created = await createRequirement(payload);
        if (form.status && form.status !== 'DRAFT') {
          await updateRequirementStatus(created.id, form.status);
        }
        toast('需求已创建并分派', 'success');
      }
      setForm(null);
      loadRequirements(keyword);
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleSubmitReview = async (r: AdminRequirementDTO) => {
    if (!window.confirm(`将需求「${r.title}」提交评审？`)) return;
    try {
      await submitRequirementReview(r.id, 'REQUIREMENT');
      toast('已提交评审', 'success');
      loadRequirements(keyword);
    } catch (e) {
      toast('提交失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const confirmDelete = async (r: AdminRequirementDTO) => {
    if (!window.confirm(`确认删除需求「${r.title}」？（逻辑删除；MinIO 附件对象暂不级联清理）`)) return;
    try {
      await deleteRequirement(r.id);
      toast('需求已删除', 'success');
      loadRequirements(keyword);
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const inputCls = 'w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30';
  const labelCls = 'block text-xs text-ink-400 mb-1';
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  return (
    <div>
      <header className="mb-6">
        <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><ClipboardList className="w-6 h-6 text-tech-400" />需求管理</h1>
        <p className="text-ink-400 text-sm mt-1">需求唯一创建入口：需求必须绑定项目，分派对象必须是该项目成员；用户在格物平台「我的需求」可见</p>
      </header>

      <div className="flex items-center gap-3 mb-5">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()} placeholder="搜索需求标题/编号..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <select value={filterType} onChange={e => setFilterType(e.target.value)} className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-200 outline-none">
          <option value="">全部类型</option>
          {REQUIREMENT_TYPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select value={filterStatus} onChange={e => setFilterStatus(e.target.value)} className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-200 outline-none">
          <option value="">全部状态</option>
          {REQUIREMENT_STATUS_OPTIONS.map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}
        </select>
        <select value={filterPriority ?? ''} onChange={e => setFilterPriority(e.target.value ? Number(e.target.value) : undefined)} className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-200 outline-none">
          <option value="">全部优先级</option>
          {REQUIREMENT_PRIORITY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <button onClick={handleSearch} className="px-4 py-2 btn-primary text-white text-sm rounded-lg">搜索</button>
        <button onClick={openCreate} className="ml-auto px-4 py-2 btn-primary text-white text-sm rounded-lg flex items-center gap-1.5"><Plus className="w-4 h-4" />新建需求</button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : requirements.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无需求，点击右上角新建</div>
      ) : (
        <div className="glass-dark rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-ink-500 border-b border-tech-500/10">
                <th className="text-left px-4 py-2.5 font-medium">需求</th>
                <th className="text-left px-4 py-2.5 font-medium">类型</th>
                <th className="text-left px-4 py-2.5 font-medium">优先级</th>
                <th className="text-left px-4 py-2.5 font-medium">所属项目</th>
                <th className="text-left px-4 py-2.5 font-medium">负责人</th>
                <th className="text-left px-4 py-2.5 font-medium">状态</th>
                <th className="text-right px-4 py-2.5 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {requirements.map(r => (
                <tr key={r.id} className="border-b border-tech-500/5 hover:bg-tech-500/5">
                  <td className="px-4 py-2.5">
                    <div className="font-medium text-ink-100 truncate max-w-[240px]">{r.title}</div>
                    <div className="text-xs text-ink-500">{r.requirementCode || '-'}</div>
                  </td>
                  <td className="px-4 py-2.5 text-ink-300 text-xs">{REQUIREMENT_TYPE_OPTIONS.find(o => o.value === r.type)?.label || r.type}</td>
                  <td className="px-4 py-2.5 text-xs text-ink-300">P{r.priority ?? 2}</td>
                  <td className="px-4 py-2.5 text-ink-300 text-xs">{r.projectName || '-'}</td>
                  <td className="px-4 py-2.5 text-ink-300 text-xs">{r.assigneeName || <span className="text-ink-500">未分派</span>}</td>
                  <td className="px-4 py-2.5"><span className={`text-xs ${statusColor(r.status)}`}>● {statusLabel(r.status)}</span></td>
                  <td className="px-4 py-2.5 text-right">
                    <div className="flex items-center justify-end gap-2">
                      {(r.status === 'DRAFT' || r.status === 'PENDING_REVIEW') && (
                        <button onClick={() => handleSubmitReview(r)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-tech-400 hover:bg-tech-500/10 flex items-center gap-1"><Send className="w-3 h-3" />提交评审</button>
                      )}
                      <button onClick={() => openEdit(r)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-ink-300 hover:bg-tech-500/10 flex items-center gap-1"><Pencil className="w-3 h-3" />编辑</button>
                      <button onClick={() => confirmDelete(r)} className="text-xs px-2 py-1 rounded border border-cinnabar-500/20 text-cinnabar-400 hover:bg-cinnabar-500/10 flex items-center gap-1"><Trash2 className="w-3 h-3" />删除</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* 分页 */}
      {total > pageSize && (
        <div className="flex items-center justify-between mt-4">
          <span className="text-sm text-ink-400">共 {total} 条需求</span>
          <div className="flex items-center gap-2">
            <button onClick={() => handlePageChange(Math.max(1, currentPage - 1))} disabled={currentPage === 1}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50">上一页</button>
            <span className="text-sm text-ink-300">{currentPage} / {totalPages}</span>
            <button onClick={() => handlePageChange(Math.min(totalPages, currentPage + 1))} disabled={currentPage >= totalPages}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50">下一页</button>
          </div>
        </div>
      )}

      {/* 需求表单弹窗（新建/编辑） */}
      {form && (
        <Modal
          open
          onClose={() => setForm(null)}
          panelClassName="glass-dark rounded-2xl w-full max-w-2xl p-6 shadow-2xl border border-tech-500/10 max-h-[90vh] overflow-y-auto scrollbar-thin"
          ariaLabel="需求表单"
        >
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-lg font-semibold text-ink-50">{form.id ? '编辑需求' : '新建需求'}</h3>
            <button onClick={() => setForm(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
          </div>
          <div className="space-y-3">
            <div>
              <label className={labelCls}>需求标题 *</label>
              <input value={form.title} onChange={e => setForm({ ...form, title: e.target.value })} placeholder="请输入需求标题" className={inputCls} />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className={labelCls}>需求类型</label>
                <select value={form.type} onChange={e => setForm({ ...form, type: e.target.value })} className={inputCls}>
                  {REQUIREMENT_TYPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </div>
              <div>
                <label className={labelCls}>优先级</label>
                <select value={form.priority} onChange={e => setForm({ ...form, priority: Number(e.target.value) })} className={inputCls}>
                  {REQUIREMENT_PRIORITY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </div>
              <div>
                <label className={labelCls}>{form.id ? '需求状态' : '需求状态（创建后默认草稿）'}</label>
                <select value={form.status} onChange={e => setForm({ ...form, status: e.target.value })} className={inputCls} disabled={!form.id}>
                  {REQUIREMENT_STATUS_OPTIONS.map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}
                </select>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls}>绑定项目 *</label>
                <select value={form.projectId} onChange={e => handleFormProjectChange(e.target.value)} className={inputCls}>
                  <option value="">请选择项目</option>
                  {projects.map(p => <option key={p.projectId} value={p.projectId}>{p.projectName}</option>)}
                </select>
              </div>
              <div>
                <label className={labelCls}>负责人（必须是绑定项目成员）</label>
                <select value={form.assigneeId} onChange={e => setForm({ ...form, assigneeId: e.target.value })}
                  className={inputCls} disabled={!form.projectId || membersLoading}>
                  <option value="">{!form.projectId ? '请先选择项目' : (membersLoading ? '成员加载中...' : '（暂不分派）')}</option>
                  {members.map(m => (
                    <option key={m.userId} value={m.userId}>
                      {m.displayName || m.userId}{m.roleCode === 'TECH_LEAD' ? '（技术负责人）' : m.roleCode === 'OWNER' ? '（所有者）' : ''}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <label className={labelCls}>需求描述（Markdown）</label>
              <textarea value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} rows={4} placeholder="需求背景、目标与验收标准" className={`${inputCls} resize-none`} />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className={labelCls}>截止日期</label>
                <input type="date" value={form.dueDate} onChange={e => setForm({ ...form, dueDate: e.target.value })} className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>故事点</label>
                <input type="number" value={form.storyPoint} onChange={e => setForm({ ...form, storyPoint: e.target.value })} placeholder="如 5" className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>预估工时（小时）</label>
                <input type="number" value={form.estimatedHours} onChange={e => setForm({ ...form, estimatedHours: e.target.value })} placeholder="如 40" className={inputCls} />
              </div>
            </div>
          </div>
          <div className="flex justify-end gap-3 mt-5">
            <button onClick={() => setForm(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
            <button onClick={handleSave} disabled={saving} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{saving && <Loader2 className="w-4 h-4 animate-spin" />}{form.id ? '保存' : '创建并分派'}</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
