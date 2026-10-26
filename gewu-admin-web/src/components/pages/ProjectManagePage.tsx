'use client';
import { useState, useEffect, useCallback } from 'react';
import {
  FolderKanban, Plus, Search, Loader2, X, Pencil, Trash2, Check, ChevronDown,
  LayoutGrid, List, BarChart3, Eye, FolderOpen, Calendar, Users,
} from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import Modal from '@/components/ui/Modal';
import { listAppSystems, type AppSystemDTO } from '@/lib/appsystem';
import { listUserOptions, type UserOption } from '@/lib/user';
import {
  listProjects, createProject, updateProject, deleteProject,
  type AdminProjectDTO, type ProjectPayload,
} from '@/lib/project';

// ==================== 常量 ====================

const PROJECT_STATUS_OPTIONS = [
  { value: 1, label: '进行中' },
  { value: 2, label: '待立项' },
  { value: 3, label: '已上线' },
  { value: 4, label: '已暂停' },
  { value: 5, label: '已取消' },
];

const VCS_OPTIONS = [
  { value: 'git', label: 'Git' },
  { value: 'svn', label: 'SVN' },
  { value: 'mercurial', label: 'Mercurial' },
  { value: 'perforce', label: 'Perforce' },
];

/** 与用户侧「我的项目」一致的 17 阶段展示映射 */
const phaseDisplayMap: Record<string, string> = {
  RESEARCH: '项目调研', PROTOTYPE: '原型设计', INITIATION: '立项中',
  REQUIREMENT_REVIEW: '需求评审', DESIGN_REVIEW: '设计评审', ESTIMATION: '工作量评估',
  PLANNING: '计划制定', DEVELOPMENT: '开发中', SELF_TEST: '开发自测',
  SMOKE_TEST: '冒烟测试', PENDING_SIT: '待SIT测试', SIT_TEST: 'SIT测试',
  PENDING_UAT: '待UAT测试', UAT_TEST: 'UAT测试', PENDING_RELEASE: '等待上线',
  RELEASED: '上线完成', CLOSED: '已结项',
};

const phaseOrder: Record<string, number> = {
  RESEARCH: 1, PROTOTYPE: 2, INITIATION: 3, REQUIREMENT_REVIEW: 4,
  DESIGN_REVIEW: 5, ESTIMATION: 6, PLANNING: 7, DEVELOPMENT: 8,
  SELF_TEST: 9, SMOKE_TEST: 10, PENDING_SIT: 11, SIT_TEST: 12,
  PENDING_UAT: 13, UAT_TEST: 14, PENDING_RELEASE: 15, RELEASED: 16, CLOSED: 17,
};

function statusLabel(status: number): string {
  return PROJECT_STATUS_OPTIONS.find(o => o.value === status)?.label || String(status);
}

function statusColor(status: number): string {
  if (status === 1) return 'text-tech-400';
  if (status === 2) return 'text-gold-400';
  if (status === 3) return 'text-green-400';
  return 'text-ink-400';
}

function getPhaseProgress(currentPhase: string): { current: number; total: number; pct: number } {
  const current = phaseOrder[currentPhase] || 1;
  return { current, total: 17, pct: Math.round((current / 17) * 100) };
}

function getPhaseBadgeColor(phaseCode: string): string {
  const order = phaseOrder[phaseCode] || 0;
  if (order <= 4) return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
  if (order <= 8) return 'bg-tech-500/10 text-tech-400 border-tech-500/20';
  if (order <= 12) return 'bg-gold-500/10 text-gold-400 border-gold-500/20';
  if (order <= 16) return 'bg-green-500/10 text-green-400 border-green-500/20';
  return 'bg-ink-500/10 text-ink-400 border-ink-500/20';
}

function getStatusBadgeColor(status: number): string {
  if (status === 1) return 'bg-tech-500/10 text-tech-400 border-tech-500/20';
  if (status === 2) return 'bg-gold-500/10 text-gold-400 border-gold-500/20';
  if (status === 3) return 'bg-green-500/10 text-green-400 border-green-500/20';
  return 'bg-ink-500/10 text-ink-400 border-ink-500/20';
}

// ==================== 视图类型 ====================

type ViewType = 'kanban' | 'list' | 'gantt';

/** 项目表单状态（projectId 为空 = 新建） */
interface ProjectForm {
  projectId: string;
  projectName: string;
  shortName: string;
  systemName: string;
  description: string;
  visibility: number;
  status: number;
  techStack: string;
  vcs: string;
  worktree: string;
  iconColor: string;
  repoUrl: string;
  platformType: string;
  apiBase: string;
  authMode: 'credential' | 'password';
  credType: 'token' | 'ssh_key';
  credential: string;
  repoUsername: string;
  repoPassword: string;
  techLeadUserId: string;
  techLeadName: string;
  memberUsers: UserOption[];
}

const EMPTY_FORM: ProjectForm = {
  projectId: '', projectName: '', shortName: '', systemName: '', description: '',
  visibility: 0, status: 1, techStack: '', vcs: 'git', worktree: '', iconColor: '#00b894',
  repoUrl: '', platformType: '', apiBase: '', authMode: 'credential', credType: 'token', credential: '',
  repoUsername: '', repoPassword: '',
  techLeadUserId: '', techLeadName: '', memberUsers: [],
};

// ==================== 主组件 ====================

export default function ProjectManagePage() {
  const toast = useToast();
  const [projects, setProjects] = useState<AdminProjectDTO[]>([]);
  const [systems, setSystems] = useState<AppSystemDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [searchStatus, setSearchStatus] = useState<number | undefined>(undefined);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize] = useState(20);
  const [total, setTotal] = useState(0);

  // 视图切换：看板/列表/甘特图（与用户侧「我的项目」同布局）
  const [viewType, setViewType] = useState<ViewType>('kanban');

  const [form, setForm] = useState<ProjectForm | null>(null);
  const [saving, setSaving] = useState(false);

  // 用户选择弹框：lead=技术负责人（单选），members=团队成员（多选）
  const [userPickerMode, setUserPickerMode] = useState<'lead' | 'members' | null>(null);
  const [userKeyword, setUserKeyword] = useState('');
  const [userOptions, setUserOptions] = useState<UserOption[]>([]);
  const [userLoading, setUserLoading] = useState(false);
  /** 弹框内暂存选择，点确认才回显到表单 */
  const [userPicked, setUserPicked] = useState<UserOption[]>([]);

  const loadProjects = useCallback(async (kw?: string, page = currentPage) => {
    setLoading(true);
    try {
      const result = await listProjects({ page, size: pageSize, keyword: kw, status: searchStatus });
      setProjects(result.records || []);
      setTotal(result.total || 0);
    } catch (e) {
      console.error('加载项目失败:', e);
      toast('加载失败，可能无项目管理权限', 'error');
      setProjects([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [currentPage, pageSize, searchStatus, toast]);

  useEffect(() => {
    loadProjects();
    listAppSystems().then(list => setSystems(list.filter(s => s.status === 1))).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearch = () => {
    setCurrentPage(1);
    loadProjects(keyword, 1);
  };

  const handlePageChange = (page: number) => {
    setCurrentPage(page);
    loadProjects(keyword, page);
  };

  const openCreate = () => setForm({ ...EMPTY_FORM });

  const openEdit = (p: AdminProjectDTO) => setForm({
    projectId: p.projectId,
    projectName: p.projectName,
    shortName: p.shortName || '',
    systemName: p.systemName || '',
    description: p.description || '',
    visibility: p.visibility ?? 0,
    status: p.status,
    techStack: p.techStack || '',
    vcs: p.vcs || 'git',
    worktree: p.worktree || '',
    iconColor: p.iconColor || '#00b894',
    repoUrl: p.repoUrl || '',
    platformType: p.platformType || '',
    apiBase: p.apiBase || '',
    authMode: 'credential',
    credType: 'token',
    credential: '',
    repoUsername: '',
    repoPassword: '',
    techLeadUserId: p.techLeadUserId || '',
    techLeadName: p.techLead || '',
    memberUsers: (p.memberUserIds || []).map(id => ({ userId: id, username: '', displayName: '' })),
  });

  // ==================== 用户选择弹框 ====================

  const loadUserOptions = async (kw?: string) => {
    setUserLoading(true);
    try {
      setUserOptions(await listUserOptions(kw ?? userKeyword));
    } catch (e) {
      toast('用户清单加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setUserOptions([]);
    } finally {
      setUserLoading(false);
    }
  };

  const openUserPicker = (mode: 'lead' | 'members') => {
    if (!form) return;
    setUserKeyword('');
    setUserPickerMode(mode);
    // 打开时以当前表单值为暂存初值（编辑回显时成员可能只有 ID 无姓名，选中后由选项补全）
    setUserPicked(mode === 'lead'
      ? (form.techLeadUserId ? [{ userId: form.techLeadUserId, username: '', displayName: form.techLeadName }] : [])
      : form.memberUsers);
    loadUserOptions('');
  };

  const toggleUserPicked = (user: UserOption) => {
    setUserPicked(prev => {
      if (userPickerMode === 'lead') return [user];
      return prev.some(u => u.userId === user.userId)
        ? prev.filter(u => u.userId !== user.userId)
        : [...prev, user];
    });
  };

  const confirmUserPicker = () => {
    if (!form) return;
    if (userPickerMode === 'lead') {
      const lead = userPicked[0] ?? null;
      setForm({ ...form, techLeadUserId: lead ? lead.userId : '', techLeadName: lead ? lead.displayName : '' });
    } else {
      setForm({ ...form, memberUsers: userPicked });
    }
    setUserPickerMode(null);
  };

  // ==================== 保存/删除 ====================

  const handleSave = async () => {
    if (!form) return;
    if (!form.projectName.trim()) { toast('请填写项目名称', 'error'); return; }
    // 凭证二选一：password 模式要求用户名与密码成对出现
    let repoCredentialType: string | undefined;
    let repoUsername: string | undefined;
    let repoCredential: string | undefined;
    if (form.authMode === 'credential') {
      if (form.credential.trim()) {
        repoCredentialType = form.credType;
        repoCredential = form.credential;
      }
    } else if (form.repoUsername.trim() || form.repoPassword) {
      if (!form.repoUsername.trim() || !form.repoPassword) {
        toast('用户名密码认证需同时填写用户名与密码', 'error');
        return;
      }
      repoCredentialType = 'password';
      repoUsername = form.repoUsername;
      repoCredential = form.repoPassword;
    }

    const payload: ProjectPayload = {
      projectName: form.projectName.trim(),
      shortName: form.shortName.trim() || undefined,
      systemName: form.systemName || undefined,
      description: form.description.trim() || undefined,
      visibility: form.visibility,
      status: form.status,
      techStack: form.techStack.trim() || undefined,
      vcs: form.vcs || undefined,
      worktree: form.worktree.trim() || undefined,
      iconColor: form.iconColor || undefined,
      repoUrl: form.repoUrl.trim() || undefined,
      platformType: form.platformType || undefined,
      apiBase: form.apiBase.trim() || undefined,
      repoCredentialType,
      repoUsername,
      // 编辑时留空 = 不改现有凭证；新建时留空 = 不设凭证
      repoCredential: form.projectId ? (repoCredential ?? undefined) : repoCredential,
      // 团队整队语义：负责人空串=清除；成员数组全量替换
      techLeadUserId: form.techLeadUserId,
      memberUserIds: form.memberUsers.map(u => u.userId),
    };

    setSaving(true);
    try {
      if (form.projectId) {
        await updateProject(form.projectId, payload);
        toast('项目已更新', 'success');
      } else {
        await createProject(payload);
        toast('项目已创建并分发', 'success');
      }
      setForm(null);
      loadProjects(keyword);
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const confirmDelete = async (p: AdminProjectDTO) => {
    if (!window.confirm(`确认删除项目「${p.projectName}」？项目对技术负责人与团队成员即刻不可见（逻辑删除；沙箱等运行产物暂不级联清理）。`)) return;
    try {
      await deleteProject(p.projectId);
      toast('项目已删除', 'success');
      loadProjects(keyword);
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
        <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><FolderKanban className="w-6 h-6 text-tech-400" />项目管理</h1>
        <p className="text-ink-400 text-sm mt-1">项目唯一创建入口：创建后分发给指定技术负责人与团队成员，用户在格物平台「我的项目」可见</p>
      </header>

      <div className="flex items-center gap-3 mb-5">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()} placeholder="搜索项目名称/编号/所属系统/技术负责人..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <select value={searchStatus ?? ''} onChange={e => setSearchStatus(e.target.value ? Number(e.target.value) : undefined)}
          className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-200 outline-none">
          <option value="">全部状态</option>
          {PROJECT_STATUS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <button onClick={handleSearch} className="px-4 py-2 btn-primary text-white text-sm rounded-lg">搜索</button>
        <button onClick={openCreate} className="ml-auto px-4 py-2 btn-primary text-white text-sm rounded-lg flex items-center gap-1.5"><Plus className="w-4 h-4" />新建项目</button>
      </div>

      {/* 视图切换 Tab（看板/列表/甘特图，与用户侧「我的项目」同布局） */}
      <div className="mb-4 flex items-center gap-2">
        <div className="flex items-center gap-1 p-1 rounded-lg bg-ink-800/30 border border-tech-500/10">
          <button
            onClick={() => setViewType('kanban')}
            className={`flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-all ${
              viewType === 'kanban' ? 'bg-tech-500/20 text-tech-400' : 'text-ink-400 hover:text-ink-200'
            }`}
          >
            <LayoutGrid className="w-4 h-4" />看板
          </button>
          <button
            onClick={() => setViewType('list')}
            className={`flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-all ${
              viewType === 'list' ? 'bg-tech-500/20 text-tech-400' : 'text-ink-400 hover:text-ink-200'
            }`}
          >
            <List className="w-4 h-4" />列表
          </button>
          <button
            onClick={() => setViewType('gantt')}
            className={`flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-all ${
              viewType === 'gantt' ? 'bg-tech-500/20 text-tech-400' : 'text-ink-400 hover:text-ink-200'
            }`}
          >
            <BarChart3 className="w-4 h-4" />甘特图
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : projects.length === 0 ? (
        <div className="text-center py-16">
          <FolderOpen className="w-12 h-12 text-ink-600 mx-auto mb-3" />
          <p className="text-ink-500 text-sm">暂无项目，点击右上角新建</p>
        </div>
      ) : (
        <>
          {viewType === 'kanban' && <KanbanView projects={projects} onOpen={openEdit} onDelete={confirmDelete} />}
          {viewType === 'list' && <ListView projects={projects} onOpen={openEdit} onDelete={confirmDelete} />}
          {viewType === 'gantt' && <GanttView projects={projects} />}
        </>
      )}

      {/* 分页 */}
      {total > pageSize && (
        <div className="flex items-center justify-between mt-4">
          <span className="text-sm text-ink-400">共 {total} 个项目</span>
          <div className="flex items-center gap-2">
            <button onClick={() => handlePageChange(Math.max(1, currentPage - 1))} disabled={currentPage === 1}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50">上一页</button>
            <span className="text-sm text-ink-300">{currentPage} / {totalPages}</span>
            <button onClick={() => handlePageChange(Math.min(totalPages, currentPage + 1))} disabled={currentPage >= totalPages}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50">下一页</button>
          </div>
        </div>
      )}

      {/* 项目表单弹窗（新建/编辑）；form 为对象态，条件渲染 Modal（进场动画，关闭仅经关闭图标/按钮/ESC） */}
      {form && (
        <Modal
          open
          onClose={() => setForm(null)}
          panelClassName="glass-dark rounded-2xl w-full max-w-2xl p-6 shadow-2xl border border-tech-500/10 max-h-[90vh] overflow-y-auto scrollbar-thin"
          ariaLabel="项目表单"
        >
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">{form.projectId ? '编辑项目' : '新建项目'}</h3>
              <button onClick={() => setForm(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-4">
              {/* 基础信息 */}
              <fieldset>
                <legend className="text-xs font-semibold text-tech-400 mb-2">基础信息</legend>
                <div className="space-y-3">
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className={labelCls}>项目名称 *</label>
                      <input value={form.projectName} onChange={e => setForm({ ...form, projectName: e.target.value })} placeholder="请输入项目名称" className={inputCls} />
                    </div>
                    <div>
                      <label className={labelCls}>项目简称</label>
                      <input value={form.shortName} onChange={e => setForm({ ...form, shortName: e.target.value })} placeholder="如：gewu-platform" className={inputCls} />
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className={labelCls}>所属系统（应用系统台账）</label>
                      <select value={form.systemName} onChange={e => setForm({ ...form, systemName: e.target.value })} className={inputCls}>
                        <option value="">（未关联）</option>
                        {systems.map(s => <option key={s.systemId} value={s.systemName}>{s.systemName}{s.systemCode ? `（${s.systemCode}）` : ''}</option>)}
                      </select>
                    </div>
                    <div>
                      <label className={labelCls}>{form.projectId ? '项目状态' : '项目状态（创建后默认进行中）'}</label>
                      <select value={form.status} onChange={e => setForm({ ...form, status: Number(e.target.value) })} className={inputCls} disabled={!form.projectId}>
                        {PROJECT_STATUS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </select>
                    </div>
                  </div>
                  <div>
                    <label className={labelCls}>项目描述</label>
                    <textarea value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} rows={2} placeholder="项目描述（可选）" className={`${inputCls} resize-none`} />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className={labelCls}>可见性</label>
                      <select value={form.visibility} onChange={e => setForm({ ...form, visibility: Number(e.target.value) })} className={inputCls}>
                        <option value={0}>私有</option>
                        <option value={1}>团队可见</option>
                        <option value={2}>公开</option>
                      </select>
                    </div>
                    <div>
                      <label className={labelCls}>图标颜色</label>
                      <div className="flex items-center gap-2">
                        <input type="color" value={form.iconColor} onChange={e => setForm({ ...form, iconColor: e.target.value })} className="w-10 h-9 rounded border border-tech-500/10 bg-ink-800/50 cursor-pointer" />
                        <input value={form.iconColor} onChange={e => setForm({ ...form, iconColor: e.target.value })} placeholder="#00b894" className={`${inputCls} flex-1`} />
                      </div>
                    </div>
                  </div>
                </div>
              </fieldset>

              {/* 团队与角色（搜索回显） */}
              <fieldset>
                <legend className="text-xs font-semibold text-tech-400 mb-2">团队与角色（分发给指定用户，用户端「我的项目」可见）</legend>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className={labelCls}>技术负责人（单选，作为项目所有者）</label>
                    <button type="button" onClick={() => openUserPicker('lead')}
                      className={`w-full flex items-center justify-between gap-2 px-3 py-2 bg-ink-800/50 border rounded-lg text-sm text-left ${form.techLeadName ? 'border-tech-500/30 text-ink-100' : 'border-tech-500/10 text-ink-500'} hover:border-tech-500/30`}>
                      <span className="truncate">{form.techLeadName || '点击搜索用户选择（可选）'}</span>
                      <ChevronDown className="w-4 h-4 flex-shrink-0 text-ink-500" />
                    </button>
                    {form.techLeadName && (
                      <button type="button" onClick={() => setForm({ ...form, techLeadUserId: '', techLeadName: '' })}
                        className="mt-1 text-[10px] text-ink-500 hover:text-ink-300">清除技术负责人</button>
                    )}
                  </div>
                  <div>
                    <label className={labelCls}>团队成员{form.memberUsers.length > 0 ? `（已选 ${form.memberUsers.length} 人）` : ''}</label>
                    <button type="button" onClick={() => openUserPicker('members')}
                      className={`w-full flex items-center justify-between gap-2 px-3 py-2 bg-ink-800/50 border rounded-lg text-sm text-left ${form.memberUsers.length > 0 ? 'border-tech-500/30 text-ink-100' : 'border-tech-500/10 text-ink-500'} hover:border-tech-500/30`}>
                      <span className="truncate">{form.memberUsers.length > 0 ? form.memberUsers.map(u => u.displayName || u.userId).join('、') : '点击搜索用户选择，可多选'}</span>
                      <ChevronDown className="w-4 h-4 flex-shrink-0 text-ink-500" />
                    </button>
                    {form.memberUsers.length > 0 && (
                      <button type="button" onClick={() => setForm({ ...form, memberUsers: [] })}
                        className="mt-1 text-[10px] text-ink-500 hover:text-ink-300">清除团队成员</button>
                    )}
                  </div>
                </div>
              </fieldset>

              {/* 技术配置 */}
              <fieldset>
                <legend className="text-xs font-semibold text-tech-400 mb-2">技术配置</legend>
                <div className="space-y-3">
                  <div>
                    <label className={labelCls}>技术栈</label>
                    <input value={form.techStack} onChange={e => setForm({ ...form, techStack: e.target.value })} placeholder="如：React, Node.js, PostgreSQL" className={inputCls} />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className={labelCls}>版本控制</label>
                      <select value={form.vcs} onChange={e => setForm({ ...form, vcs: e.target.value })} className={inputCls}>
                        {VCS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </select>
                    </div>
                    <div>
                      <label className={labelCls}>工作空间</label>
                      <input value={form.worktree} onChange={e => setForm({ ...form, worktree: e.target.value })} placeholder="如：/workspace/proj-x" className={inputCls} />
                    </div>
                  </div>
                </div>
              </fieldset>

              {/* 仓库配置 */}
              <fieldset>
                <legend className="text-xs font-semibold text-tech-400 mb-2">仓库配置（可选）</legend>
                <div className="space-y-3">
                  <div>
                    <label className={labelCls}>仓库地址</label>
                    <input value={form.repoUrl} onChange={e => setForm({ ...form, repoUrl: e.target.value })} placeholder="如：https://github.com/org/repo.git" className={inputCls} />
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-xs text-ink-500 mb-1">托管平台（可选，自建实例必选）</label>
                        <select value={form.platformType} onChange={e => setForm({ ...form, platformType: e.target.value })} className={inputCls}>
                          <option value="">自动识别域名</option>
                          <option value="github">GitHub</option>
                          <option value="gitlab">GitLab（含自建）</option>
                          <option value="gitee">Gitee</option>
                          <option value="gitea">Gitea/Gogs（自建）</option>
                        </select>
                      </div>
                      <div>
                        <label className="block text-xs text-ink-500 mb-1">自建 API 地址（可选）</label>
                        <input value={form.apiBase} onChange={e => setForm({ ...form, apiBase: e.target.value })} placeholder="如 https://git.internal/api/v4" className={inputCls} />
                      </div>
                    </div>
                  </div>
                  <div>
                    <label className={labelCls}>访问认证（二选一，可选）</label>
                    <div className="grid grid-cols-2 gap-2 mb-3">
                      <button type="button" onClick={() => setForm({ ...form, authMode: 'credential' })}
                        className={`px-3 py-2 text-sm rounded-lg border transition-all ${form.authMode === 'credential' ? 'border-tech-500/40 bg-tech-500/10 text-tech-300' : 'border-tech-500/10 text-ink-400 hover:text-ink-200'}`}>
                        仓库访问凭证
                      </button>
                      <button type="button" onClick={() => setForm({ ...form, authMode: 'password' })}
                        className={`px-3 py-2 text-sm rounded-lg border transition-all ${form.authMode === 'password' ? 'border-tech-500/40 bg-tech-500/10 text-tech-300' : 'border-tech-500/10 text-ink-400 hover:text-ink-200'}`}>
                        用户名密码
                      </button>
                    </div>
                    {form.authMode === 'credential' ? (
                      <div className="space-y-3">
                        <div>
                          <label className={labelCls}>凭证类型</label>
                          <select value={form.credType} onChange={e => setForm({ ...form, credType: e.target.value as 'token' | 'ssh_key' })} className={inputCls}>
                            <option value="token">访问令牌（Access Token）</option>
                            <option value="ssh_key">SSH 私钥（仓库公钥对应的私钥）</option>
                          </select>
                        </div>
                        <div>
                          <label className={labelCls}>凭证内容{form.projectId ? '（留空保持现有凭证）' : ''}</label>
                          <textarea value={form.credential} onChange={e => setForm({ ...form, credential: e.target.value })}
                            placeholder={form.credType === 'token' ? '粘贴访问令牌' : '粘贴 SSH 私钥（-----BEGIN OPENSSH PRIVATE KEY-----）'}
                            rows={form.credType === 'ssh_key' ? 3 : 2}
                            className={`${inputCls} resize-none font-mono text-xs`} />
                        </div>
                      </div>
                    ) : (
                      <div className="grid grid-cols-2 gap-3">
                        <div>
                          <label className={labelCls}>用户</label>
                          <input value={form.repoUsername} onChange={e => setForm({ ...form, repoUsername: e.target.value })} placeholder="仓库用户名" autoComplete="off" className={inputCls} />
                        </div>
                        <div>
                          <label className={labelCls}>密码{form.projectId ? '（留空保持现有凭证）' : ''}</label>
                          <input type="password" value={form.repoPassword} onChange={e => setForm({ ...form, repoPassword: e.target.value })} placeholder="仓库密码" autoComplete="new-password" className={inputCls} />
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </fieldset>
            </div>
            <div className="flex justify-end gap-3 mt-5">
              <button onClick={() => setForm(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSave} disabled={saving} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{saving && <Loader2 className="w-4 h-4 animate-spin" />}{form.projectId ? '保存' : '创建并分发'}</button>
            </div>
        </Modal>
      )}

      {/* 用户选择弹框（lead 单选 / members 多选） */}
      {userPickerMode && (
        <Modal
          open
          onClose={() => setUserPickerMode(null)}
          panelClassName="glass-dark rounded-2xl w-full max-w-xl p-6 shadow-2xl border border-tech-500/10 max-h-[85vh] overflow-y-auto scrollbar-thin"
          ariaLabel="选择用户"
        >
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-lg font-semibold text-ink-50">{userPickerMode === 'lead' ? '选择技术负责人' : '选择团队成员'}</h3>
              <button onClick={() => setUserPickerMode(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="flex items-center gap-2 mb-3">
              <div className="relative flex-1">
                <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-ink-500" />
                <input
                  value={userKeyword}
                  onChange={e => setUserKeyword(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && loadUserOptions()}
                  placeholder="输入显示名/用户名/邮箱查询"
                  className="w-full pl-8 pr-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
                />
              </div>
              <button onClick={() => loadUserOptions()} disabled={userLoading}
                className="px-3 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50">查询</button>
            </div>
            {userPickerMode === 'members' && userPicked.length > 0 && (
              <p className="text-[11px] text-tech-300 mb-2 truncate">
                已选 {userPicked.length} 人：{userPicked.map(u => u.displayName || u.userId).join('、')}
              </p>
            )}
            {userLoading ? (
              <div className="flex justify-center py-6">
                <div className="w-5 h-5 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
              </div>
            ) : userOptions.length === 0 ? (
              <p className="text-ink-500 text-sm text-center py-6">无匹配用户</p>
            ) : (
              <div className="space-y-1.5 max-h-80 overflow-y-auto">
                {userOptions.map(u => {
                  const picked = userPicked.some(p => p.userId === u.userId);
                  return (
                    <button key={u.userId} type="button"
                      onClick={() => toggleUserPicked(u)}
                      className={`w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg border text-left transition-colors ${
                        picked ? 'border-tech-500/40 bg-tech-500/10' : 'border-tech-500/10 hover:border-tech-500/30'
                      }`}>
                      <div className="min-w-0">
                        <p className="text-sm text-ink-100 truncate">{u.displayName}</p>
                        <p className="text-[10px] text-ink-500 truncate">{u.username}{u.email ? ` · ${u.email}` : ''}</p>
                      </div>
                      {picked && <Check className="w-4 h-4 text-tech-400 flex-shrink-0" />}
                    </button>
                  );
                })}
              </div>
            )}
            <div className="flex items-center justify-between mt-4 pt-3 border-t border-tech-500/10">
              <p className="text-[10px] text-ink-600">
                {userPickerMode === 'lead' ? '技术负责人只能选择一位' : '可多选，确认后回显到表单'}
              </p>
              <div className="flex gap-2">
                <button onClick={() => setUserPickerMode(null)}
                  className="px-3 py-1.5 text-sm text-ink-400 hover:text-ink-200">取消</button>
                <button onClick={confirmUserPicker}
                  className="px-4 py-1.5 text-sm btn-primary text-white rounded-lg">确认</button>
              </div>
            </div>
        </Modal>
      )}
    </div>
  );
}

// ==================== 看板视图（待立项/进行中/已上线） ====================

function KanbanView({ projects, onOpen, onDelete }: {
  projects: AdminProjectDTO[];
  onOpen: (p: AdminProjectDTO) => void;
  onDelete: (p: AdminProjectDTO) => void;
}) {
  const columns = [
    { title: '待立项', status: 2, color: 'border-gold-500/20 bg-gold-500/5' },
    { title: '进行中', status: 1, color: 'border-tech-500/20 bg-tech-500/5' },
    { title: '已上线', status: 3, color: 'border-green-500/20 bg-green-500/5' },
  ];

  return (
    <div className="grid grid-cols-3 gap-4">
      {columns.map(col => {
        const colProjects = projects.filter(p => p.status === col.status);
        return (
          <div key={col.status} className={`rounded-xl border ${col.color} p-4 min-h-[400px]`}>
            <h3 className="text-sm font-semibold text-ink-200 mb-3">{col.title} ({colProjects.length})</h3>
            <div className="space-y-3">
              {colProjects.map(p => {
                const progress = getPhaseProgress(p.currentPhase || 'RESEARCH');
                return (
                  <div key={p.projectId}
                    onClick={() => onOpen(p)}
                    className="glass-dark rounded-lg p-4 border border-tech-500/5 hover:border-tech-500/15 transition-all cursor-pointer">
                    {/* 项目标题 + 图标 */}
                    <div className="flex items-start gap-3 mb-3">
                      {p.iconUrl ? (
                        <img src={p.iconUrl} alt="" className="w-10 h-10 rounded-lg flex-shrink-0" />
                      ) : (
                        <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${p.iconColor ? `bg-[${p.iconColor}]` : 'bg-gradient-to-br from-tech-400 to-tech-600'}`}>
                          <FolderOpen className="w-5 h-5 text-white" />
                        </div>
                      )}
                      <div className="flex-1 min-w-0">
                        <h4 className="text-sm font-medium text-ink-100 truncate">{p.projectName}</h4>
                        {p.projectCode && <p className="text-[10px] text-ink-500 mt-0.5">编号：{p.projectCode}</p>}
                      </div>
                    </div>

                    {/* 项目描述 */}
                    {p.description && (
                      <p className="text-[11px] text-ink-400 mb-3 line-clamp-2">{p.description}</p>
                    )}

                    {/* 阶段进度 */}
                    <div className="mb-3">
                      <div className="h-1.5 bg-ink-700 rounded-full overflow-hidden">
                        <div className="h-full bg-gradient-to-r from-tech-500 to-cyber-400" style={{ width: `${progress.pct}%` }} />
                      </div>
                      <div className="flex items-center justify-between mt-1">
                        <span className={`text-[10px] px-1.5 py-0.5 rounded border ${getPhaseBadgeColor(p.currentPhase || 'RESEARCH')}`}>
                          {phaseDisplayMap[p.currentPhase || 'RESEARCH']}
                        </span>
                        <span className="text-[10px] text-ink-500">{progress.current}/17</span>
                      </div>
                    </div>

                    {/* 项目信息 */}
                    <div className="flex items-center justify-between text-[10px] text-ink-400 mb-3 pb-2 border-b border-tech-500/5">
                      <span className="flex items-center gap-1"><Calendar className="w-3 h-3" />{p.initiatedAt ? new Date(p.initiatedAt).toLocaleDateString('zh-CN') : (p.createdAt ? new Date(p.createdAt).toLocaleDateString('zh-CN') : '-')}</span>
                      <span className="flex items-center gap-1"><Users className="w-3 h-3" />{p.techLead || p.ownerName || '-'}</span>
                    </div>

                    {/* 操作按钮（点击卡片本体 = 打开编辑） */}
                    <div className="grid grid-cols-2 gap-1">
                      <button onClick={e => { e.stopPropagation(); onOpen(p); }} className="flex items-center justify-center gap-1 text-[10px] text-tech-400 hover:bg-tech-500/10 rounded py-1.5 transition-colors">
                        <Eye className="w-3 h-3" />查看 / 编辑
                      </button>
                      <button onClick={e => { e.stopPropagation(); onDelete(p); }} className="flex items-center justify-center gap-1 text-[10px] text-cinnabar-400 hover:bg-cinnabar-500/10 rounded py-1.5 transition-colors">
                        <Trash2 className="w-3 h-3" />删除
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ==================== 列表视图 ====================

function ListView({ projects, onOpen, onDelete }: {
  projects: AdminProjectDTO[];
  onOpen: (p: AdminProjectDTO) => void;
  onDelete: (p: AdminProjectDTO) => void;
}) {
  return (
    <div className="glass-dark rounded-xl overflow-hidden">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-xs text-ink-500 border-b border-tech-500/10">
            <th className="text-left px-4 py-2.5 font-medium">项目</th>
            <th className="text-left px-4 py-2.5 font-medium">所属系统</th>
            <th className="text-left px-4 py-2.5 font-medium">技术负责人</th>
            <th className="text-left px-4 py-2.5 font-medium">团队成员</th>
            <th className="text-left px-4 py-2.5 font-medium">状态</th>
            <th className="text-left px-4 py-2.5 font-medium">创建时间</th>
            <th className="text-right px-4 py-2.5 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          {projects.map(p => (
            <tr key={p.projectId} className="border-b border-tech-500/5 hover:bg-tech-500/5">
              <td className="px-4 py-2.5">
                <div className="flex items-center gap-2">
                  <span className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ background: p.iconColor || '#00b894' }} />
                  <div className="min-w-0">
                    <button onClick={() => onOpen(p)} className="font-medium text-ink-100 truncate max-w-[220px] block text-left hover:text-tech-300">{p.projectName}</button>
                    <div className="text-xs text-ink-500">{p.projectCode || '-'}</div>
                  </div>
                </div>
              </td>
              <td className="px-4 py-2.5 text-ink-300 text-xs">{p.systemName || '-'}</td>
              <td className="px-4 py-2.5 text-ink-300 text-xs">{p.techLead || '-'}</td>
              <td className="px-4 py-2.5 text-ink-300 text-xs">
                <span className="truncate block max-w-[160px]" title={p.teamMembers || ''}>
                  {p.memberCount > 0 ? `${p.memberCount} 人` : '-'}{p.teamMembers ? `（${p.teamMembers}）` : ''}
                </span>
              </td>
              <td className="px-4 py-2.5"><span className={`text-xs ${statusColor(p.status)}`}>● {statusLabel(p.status)}</span></td>
              <td className="px-4 py-2.5 text-xs text-ink-400">{p.createdAt ? new Date(p.createdAt).toLocaleDateString('zh-CN') : '-'}</td>
              <td className="px-4 py-2.5 text-right">
                <div className="flex items-center justify-end gap-2">
                  <button onClick={() => onOpen(p)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-ink-300 hover:bg-tech-500/10 flex items-center gap-1"><Eye className="w-3 h-3" />查看</button>
                  <button onClick={() => onOpen(p)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-ink-300 hover:bg-tech-500/10 flex items-center gap-1"><Pencil className="w-3 h-3" />编辑</button>
                  <button onClick={() => onDelete(p)} className="text-xs px-2 py-1 rounded border border-cinnabar-500/20 text-cinnabar-400 hover:bg-cinnabar-500/10 flex items-center gap-1"><Trash2 className="w-3 h-3" />删除</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ==================== 甘特图视图 ====================

function GanttView({ projects }: { projects: AdminProjectDTO[] }) {
  const today = new Date();
  const monthLabels = Array.from({ length: 6 }, (_, i) => {
    const d = new Date(today.getFullYear(), today.getMonth() + i, 1);
    return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'short' });
  });

  return (
    <div className="glass-dark rounded-xl border border-tech-500/10 p-4">
      <div className="overflow-x-auto">
        <div className="min-w-[800px]">
          {/* 月份表头 */}
          <div className="grid grid-cols-[200px_1fr] border-b border-tech-500/10 pb-2 mb-2">
            <div className="text-xs font-medium text-ink-400">项目</div>
            <div className="grid grid-cols-6 gap-0">
              {monthLabels.map((m, i) => (
                <div key={i} className="text-xs text-ink-500 text-center border-l border-tech-500/5 first:border-l-0 py-1">{m}</div>
              ))}
            </div>
          </div>
          {/* 项目行 */}
          {projects.map(p => {
            const startDate = p.initiatedAt ? new Date(p.initiatedAt) : new Date(p.createdAt);
            const endDate = p.closedAt ? new Date(p.closedAt) : today;
            const startMonth = (startDate.getFullYear() - today.getFullYear()) * 12 + (startDate.getMonth() - today.getMonth());
            const durationMonths = Math.max(1, Math.ceil((endDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24 * 30)));
            const leftPercent = Math.max(0, startMonth / 6 * 100);
            const widthPercent = Math.min(100 - leftPercent, durationMonths / 6 * 100);

            return (
              <div key={p.projectId} className="grid grid-cols-[200px_1fr] py-2 border-b border-tech-500/5 items-center">
                <div className="text-sm text-ink-200 truncate pr-4">{p.projectName}</div>
                <div className="relative h-6 bg-ink-800/30 rounded">
                  <div
                    className={`absolute h-full rounded ${getStatusBadgeColor(p.status).split(' ')[0]} opacity-60`}
                    style={{ left: `${leftPercent}%`, width: `${widthPercent}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
