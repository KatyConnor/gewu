'use client';
import { useState, useEffect, useCallback } from 'react';
import { useDispatch } from 'react-redux';
import { Plus, Eye, Edit, Trash2, FolderOpen, Calendar, Users, Search, LayoutGrid, List, BarChart3, Pause, X } from 'lucide-react';
import { setPage } from '@/store';
import { useToast } from '@/components/ui/Toast';
import { listMyProjects, deleteProject, type ProjectDTO } from '@/lib/project';
import ProjectDetailPage from './ProjectDetailPage';

// ==================== 常量定义 ====================

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

const statusMap: Record<number, string> = {
  1: '进行中',
  2: '待立项',
  3: '已上线',
  4: '已暂停',
  5: '已取消',
};

// ==================== 辅助函数 ====================

function getPhaseProgress(currentPhase: string): { current: number; total: number; pct: number } {
  const current = phaseOrder[currentPhase] || 1;
  const total = 17;
  return { current, total, pct: Math.round((current / total) * 100) };
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

// ==================== 主组件 ====================

export default function ProjectsPage() {
  const [projects, setProjects] = useState<ProjectDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');
  const [newVisibility, setNewVisibility] = useState(0);
  const [newTechStack, setNewTechStack] = useState('');
  const [newVcs, setNewVcs] = useState('');
  const [newWorktree, setNewWorktree] = useState('');
  const [newIconColor, setNewIconColor] = useState('#00b894');
  const [detailProjectId, setDetailProjectId] = useState<string | null>(null);

  // 编辑弹框
  const [showEdit, setShowEdit] = useState(false);
  const [editProjectId, setEditProjectId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [editCode, setEditCode] = useState('');
  const [editDesc, setEditDesc] = useState('');

  // 搜索和分页
  const [viewType, setViewType] = useState<ViewType>('kanban');
  const [searchProjectName, setSearchProjectName] = useState('');
  const [searchProjectCode, setSearchProjectCode] = useState('');
  const [searchOwnerName, setSearchOwnerName] = useState('');
  const [searchStatus, setSearchStatus] = useState<number | undefined>(undefined);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize] = useState(20);
  const [total, setTotal] = useState(0);

  const dispatch = useDispatch();
  const toast = useToast();

  const loadProjects = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listMyProjects({
        page: currentPage,
        size: pageSize,
        projectName: searchProjectName || undefined,
        projectCode: searchProjectCode || undefined,
        ownerName: searchOwnerName || undefined,
        status: searchStatus,
      });
      setProjects(result.records || []);
      setTotal(result.total || 0);
    } catch {
      setProjects([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [currentPage, pageSize, searchProjectName, searchProjectCode, searchOwnerName, searchStatus]);

  useEffect(() => { loadProjects(); }, [loadProjects]);

  const handleSearch = () => {
    setCurrentPage(1);
    loadProjects();
  };

  const handleReset = () => {
    setSearchProjectName('');
    setSearchProjectCode('');
    setSearchOwnerName('');
    setSearchStatus(undefined);
    setCurrentPage(1);
  };

  const handleCreate = async () => {
    if (!newName.trim()) return;
    try {
      const { createProject } = await import('@/lib/project');
      await createProject({
        projectName: newName,
        description: newDesc || undefined,
        visibility: newVisibility,
        techStack: newTechStack || undefined,
        vcs: newVcs || undefined,
        worktree: newWorktree || undefined,
        iconColor: newIconColor,
      });
      toast('项目创建成功', 'success');
      setShowCreate(false);
      setNewName('');
      setNewDesc('');
      setNewVisibility(0);
      setNewTechStack('');
      setNewVcs('');
      setNewWorktree('');
      setNewIconColor('#00b894');
      loadProjects();
    } catch {
      toast('创建失败', 'error');
    }
  };

  const handleDelete = async (projectId: string, name: string) => {
    if (!confirm(`确定要删除项目 "${name}" 吗？此操作不可恢复。`)) return;
    try {
      await deleteProject(projectId);
      toast('项目已删除', 'success');
      loadProjects();
    } catch {
      toast('删除失败', 'error');
    }
  };

  const handleEdit = (projectId: string) => {
    const project = projects.find(p => p.projectId === projectId);
    if (!project) return;
    setEditProjectId(projectId);
    setEditName(project.projectName);
    setEditDesc(project.description || '');
    setEditCode(project.projectCode || '');
    setShowEdit(true);
  };

  const handleSaveEdit = async () => {
    if (!editProjectId || !editName.trim()) return;
    try {
      const { updateProject } = await import('@/lib/project');
      await updateProject(editProjectId, {
        projectName: editName,
        description: editDesc,
      });
      toast('项目已更新', 'success');
      setShowEdit(false);
      setEditProjectId(null);
      loadProjects();
    } catch {
      toast('更新失败', 'error');
    }
  };

  const handlePause = async (projectId: string, name: string) => {
    if (!confirm(`确定要暂停项目 "${name}" 吗？`)) return;
    try {
      const { updateProject } = await import('@/lib/project');
      await updateProject(projectId, { status: 4 });
      toast('项目已暂停', 'success');
      loadProjects();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleCancel = async (projectId: string, name: string) => {
    if (!confirm(`确定要取消项目 "${name}" 吗？`)) return;
    try {
      const { updateProject } = await import('@/lib/project');
      await updateProject(projectId, { status: 5 });
      toast('项目已取消', 'success');
      loadProjects();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const viewDetail = (projectId: string) => {
    setDetailProjectId(projectId);
  };

  // 项目详情视图
  if (detailProjectId) {
    return <ProjectDetailPage projectId={detailProjectId} onBack={() => setDetailProjectId(null)} />;
  }

  // 加载中
  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="w-6 h-6 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div>
      {/* 页面标题 */}
      <header className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">项目管理</h1>
          <p className="text-ink-400 text-sm mt-1">管理和追踪项目生命周期</p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"
        >
          <Plus className="w-4 h-4" />新建项目
        </button>
      </header>

      {/* 搜索区域 */}
      <div className="glass-dark rounded-xl p-4 mb-4 border border-tech-500/10">
        <div className="grid grid-cols-4 gap-3">
          <input
            value={searchProjectName}
            onChange={e => setSearchProjectName(e.target.value)}
            placeholder="项目名称"
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
          />
          <input
            value={searchProjectCode}
            onChange={e => setSearchProjectCode(e.target.value)}
            placeholder="项目编号"
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
          />
          <input
            value={searchOwnerName}
            onChange={e => setSearchOwnerName(e.target.value)}
            placeholder="项目负责人"
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
          />
          <select
            value={searchStatus ?? ''}
            onChange={e => setSearchStatus(e.target.value ? Number(e.target.value) : undefined)}
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
          >
            <option value="">全部状态</option>
            <option value="1">进行中</option>
            <option value="2">待立项</option>
            <option value="3">已上线</option>
          </select>
        </div>
        <div className="flex items-center justify-end gap-2 mt-3">
          <button onClick={handleReset} className="px-4 py-1.5 text-sm text-ink-400 hover:text-ink-200">重置</button>
          <button onClick={handleSearch} className="flex items-center gap-1.5 px-4 py-1.5 text-sm btn-primary text-white rounded-lg">
            <Search className="w-4 h-4" />搜索
          </button>
        </div>
      </div>

      {/* 视图切换 Tab */}
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

      {/* 创建项目弹框 */}
      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setShowCreate(false)}>
          <div className="glass-dark rounded-xl p-6 w-full max-w-lg border border-tech-500/20 max-h-[85vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-semibold text-ink-50 mb-4">新建项目</h3>
            <div className="space-y-3">
              <div>
                <label className="text-xs text-ink-400 mb-1 block">项目名称 *</label>
                <input
                  value={newName}
                  onChange={e => setNewName(e.target.value)}
                  placeholder="请输入项目名称"
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
                />
              </div>
              <div>
                <label className="text-xs text-ink-400 mb-1 block">项目编号</label>
                <input
                  disabled
                  placeholder="保存后自动生成"
                  className="w-full px-3 py-2 bg-ink-800/30 border border-tech-500/10 rounded-lg text-sm text-ink-500 placeholder-ink-500 outline-none cursor-not-allowed"
                />
              </div>
              <div>
                <label className="text-xs text-ink-400 mb-1 block">项目描述</label>
                <textarea
                  value={newDesc}
                  onChange={e => setNewDesc(e.target.value)}
                  placeholder="项目描述（可选）"
                  rows={3}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none resize-none"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">可见性</label>
                  <select
                    value={newVisibility}
                    onChange={e => setNewVisibility(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
                  >
                    <option value={0}>私有</option>
                    <option value={1}>团队可见</option>
                    <option value={2}>公开</option>
                  </select>
                </div>
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">图标颜色</label>
                  <div className="flex items-center gap-2">
                    <input
                      type="color"
                      value={newIconColor}
                      onChange={e => setNewIconColor(e.target.value)}
                      className="w-10 h-9 rounded border border-tech-500/10 bg-ink-800/50 cursor-pointer"
                    />
                    <input
                      value={newIconColor}
                      onChange={e => setNewIconColor(e.target.value)}
                      placeholder="#00b894"
                      className="flex-1 px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
                    />
                  </div>
                </div>
              </div>
              <div>
                <label className="text-xs text-ink-400 mb-1 block">技术栈</label>
                <input
                  value={newTechStack}
                  onChange={e => setNewTechStack(e.target.value)}
                  placeholder="如：React, Node.js, PostgreSQL"
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">版本控制</label>
                  <input
                    value={newVcs}
                    onChange={e => setNewVcs(e.target.value)}
                    placeholder="如：Git"
                    className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
                  />
                </div>
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">工作空间</label>
                  <input
                    value={newWorktree}
                    onChange={e => setNewWorktree(e.target.value)}
                    placeholder="如：/workspace/project"
                    className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
                  />
                </div>
              </div>
            </div>
            <div className="flex gap-2 justify-end mt-4 pt-3 border-t border-tech-500/10">
              <button onClick={() => setShowCreate(false)} className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200">取消</button>
              <button onClick={handleCreate} disabled={!newName.trim()} className="px-4 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50">创建</button>
            </div>
          </div>
        </div>
      )}

      {/* 编辑项目弹框 */}
      {showEdit && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setShowEdit(false)}>
          <div className="glass-dark rounded-xl p-6 w-full max-w-md border border-tech-500/20" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-semibold text-ink-50 mb-4">编辑项目</h3>
            <input
              value={editName}
              onChange={e => setEditName(e.target.value)}
              placeholder="项目名称"
              className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none mb-3"
            />
            <input
              value={editCode}
              readOnly
              disabled
              placeholder="项目编号"
              className="w-full px-3 py-2 bg-ink-800/30 border border-tech-500/10 rounded-lg text-sm text-ink-300 placeholder-ink-500 outline-none mb-3 cursor-not-allowed"
            />
            <textarea
              value={editDesc}
              onChange={e => setEditDesc(e.target.value)}
              placeholder="项目描述（可选）"
              rows={3}
              className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none resize-none mb-4"
            />
            <div className="flex gap-2 justify-end">
              <button onClick={() => setShowEdit(false)} className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200">取消</button>
              <button onClick={handleSaveEdit} disabled={!editName.trim()} className="px-4 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50">保存</button>
            </div>
          </div>
        </div>
      )}

      {/* 视图内容 */}
      {viewType === 'kanban' && <KanbanView projects={projects} onViewDetail={viewDetail} onEdit={handleEdit} onPause={handlePause} onCancel={handleCancel} />}
      {viewType === 'list' && <ListView projects={projects} onViewDetail={viewDetail} onDelete={handleDelete} />}
      {viewType === 'gantt' && <GanttView projects={projects} />}

      {/* 分页 */}
      {total > pageSize && (
        <div className="flex items-center justify-between mt-4 pt-4 border-t border-tech-500/10">
          <span className="text-sm text-ink-400">共 {total} 个项目</span>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
              disabled={currentPage === 1}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50"
            >
              上一页
            </button>
            <span className="text-sm text-ink-300">{currentPage} / {Math.ceil(total / pageSize)}</span>
            <button
              onClick={() => setCurrentPage(p => Math.min(Math.ceil(total / pageSize), p + 1))}
              disabled={currentPage >= Math.ceil(total / pageSize)}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50"
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ==================== 看板视图 ====================

function KanbanView({ projects, onViewDetail, onEdit, onPause, onCancel }: {
  projects: ProjectDTO[];
  onViewDetail: (id: string) => void;
  onEdit: (id: string) => void;
  onPause: (id: string, name: string) => void;
  onCancel: (id: string, name: string) => void;
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
                  <div key={p.projectId} className="glass-dark rounded-lg p-4 border border-tech-500/5 hover:border-tech-500/15 transition-all">
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
                      <span className="flex items-center gap-1"><Calendar className="w-3 h-3" />{p.initiatedAt ? new Date(p.initiatedAt).toLocaleDateString('zh-CN') : '-'}</span>
                      <span className="flex items-center gap-1"><Users className="w-3 h-3" />{p.ownerName || '-'}</span>
                    </div>

                    {/* 操作按钮 */}
                    <div className="grid grid-cols-4 gap-1">
                      <button onClick={() => onViewDetail(p.projectId)} className="flex items-center justify-center gap-1 text-[10px] text-tech-400 hover:bg-tech-500/10 rounded py-1.5 transition-colors">
                        <Eye className="w-3 h-3" />查看
                      </button>
                      <button onClick={() => onEdit(p.projectId)} className="flex items-center justify-center gap-1 text-[10px] text-gold-400 hover:bg-gold-500/10 rounded py-1.5 transition-colors">
                        <Edit className="w-3 h-3" />编辑
                      </button>
                      <button onClick={() => onPause(p.projectId, p.projectName)} className="flex items-center justify-center gap-1 text-[10px] text-orange-400 hover:bg-orange-500/10 rounded py-1.5 transition-colors">
                        <Pause className="w-3 h-3" />暂停
                      </button>
                      <button onClick={() => onCancel(p.projectId, p.projectName)} className="flex items-center justify-center gap-1 text-[10px] text-cinnabar-400 hover:bg-cinnabar-500/10 rounded py-1.5 transition-colors">
                        <X className="w-3 h-3" />取消
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

function ListView({ projects, onViewDetail, onDelete }: {
  projects: ProjectDTO[];
  onViewDetail: (id: string) => void;
  onDelete: (id: string, name: string) => void;
}) {
  if (projects.length === 0) {
    return (
      <div className="text-center py-16">
        <FolderOpen className="w-12 h-12 text-ink-600 mx-auto mb-3" />
        <p className="text-ink-500">暂无项目</p>
      </div>
    );
  }

  return (
    <div className="glass-dark rounded-xl border border-tech-500/10 overflow-hidden">
      <table className="w-full">
        <thead>
          <tr className="border-b border-tech-500/10 bg-ink-800/30">
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">项目编号</th>
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">项目名称</th>
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">创建时间</th>
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">立项时间</th>
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">状态</th>
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">项目负责人</th>
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">工作空间</th>
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">操作</th>
          </tr>
        </thead>
        <tbody>
          {projects.map(p => (
            <tr key={p.projectId} className="border-b border-tech-500/5 hover:bg-ink-800/20">
              <td className="px-4 py-3 text-sm text-ink-200">{p.projectCode || '-'}</td>
              <td className="px-4 py-3 text-sm font-medium text-ink-100">{p.projectName}</td>
              <td className="px-4 py-3 text-sm text-ink-400">{p.createdAt ? new Date(p.createdAt).toLocaleDateString('zh-CN') : '-'}</td>
              <td className="px-4 py-3 text-sm text-ink-400">{p.initiatedAt ? new Date(p.initiatedAt).toLocaleDateString('zh-CN') : '-'}</td>
              <td className="px-4 py-3">
                <span className={`text-xs px-2 py-0.5 rounded border ${getStatusBadgeColor(p.status)}`}>
                  {statusMap[p.status] || '-'}
                </span>
              </td>
              <td className="px-4 py-3 text-sm text-ink-300">{p.ownerName || '-'}</td>
              <td className="px-4 py-3 text-sm text-ink-400 truncate max-w-[200px]">{p.worktree || '-'}</td>
              <td className="px-4 py-3">
                <div className="flex items-center gap-1">
                  <button onClick={() => onViewDetail(p.projectId)} className="text-tech-400 hover:bg-tech-500/10 rounded p-1"><Eye className="w-3.5 h-3.5" /></button>
                  <button onClick={() => onDelete(p.projectId, p.projectName)} className="text-cinnabar-400 hover:bg-cinnabar-500/10 rounded p-1"><Trash2 className="w-3.5 h-3.5" /></button>
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

function GanttView({ projects }: { projects: ProjectDTO[] }) {
  if (projects.length === 0) {
    return (
      <div className="text-center py-16">
        <FolderOpen className="w-12 h-12 text-ink-600 mx-auto mb-3" />
        <p className="text-ink-500">暂无项目</p>
      </div>
    );
  }

  // 简易甘特图实现
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
