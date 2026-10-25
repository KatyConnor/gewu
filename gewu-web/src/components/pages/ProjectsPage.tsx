'use client';
import { useState, useEffect, useCallback } from 'react';
import { Eye, FolderOpen, Calendar, Users, Search, LayoutGrid, List, BarChart3 } from 'lucide-react';
import { listMyProjects, type ProjectDTO } from '@/lib/project';
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
  const [detailProjectId, setDetailProjectId] = useState<string | null>(null);

  // 搜索和分页（"我的项目"按成员分发可见，无需按负责人检索）
  const [viewType, setViewType] = useState<ViewType>('kanban');
  const [searchProjectName, setSearchProjectName] = useState('');
  const [searchProjectCode, setSearchProjectCode] = useState('');
  const [searchStatus, setSearchStatus] = useState<number | undefined>(undefined);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize] = useState(20);
  const [total, setTotal] = useState(0);

  const loadProjects = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listMyProjects({
        page: currentPage,
        size: pageSize,
        projectName: searchProjectName || undefined,
        projectCode: searchProjectCode || undefined,
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
  }, [currentPage, pageSize, searchProjectName, searchProjectCode, searchStatus]);

  useEffect(() => { loadProjects(); }, [loadProjects]);

  const handleSearch = () => {
    setCurrentPage(1);
    loadProjects();
  };

  const handleReset = () => {
    setSearchProjectName('');
    setSearchProjectCode('');
    setSearchStatus(undefined);
    setCurrentPage(1);
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
      <header className="mb-6">
        <h1 className="text-2xl font-semibold text-ink-50">我的项目</h1>
        <p className="text-ink-400 text-sm mt-1">项目由管理后台统一创建并分发给技术负责人与团队成员，此处展示您参与的项目</p>
      </header>

      {/* 搜索区域 */}
      <div className="glass-dark rounded-xl p-4 mb-4 border border-tech-500/10">
        <div className="grid grid-cols-3 gap-3">
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

      {/* 视图内容 */}
      {viewType === 'kanban' && <KanbanView projects={projects} onViewDetail={viewDetail} />}
      {viewType === 'list' && <ListView projects={projects} onViewDetail={viewDetail} />}
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

function KanbanView({ projects, onViewDetail }: {
  projects: ProjectDTO[];
  onViewDetail: (id: string) => void;
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
                      <span className="flex items-center gap-1"><Users className="w-3 h-3" />{p.techLead || p.ownerName || '-'}</span>
                    </div>

                    {/* 操作按钮 */}
                    <div className="grid grid-cols-1 gap-1">
                      <button onClick={() => onViewDetail(p.projectId)} className="flex items-center justify-center gap-1 text-[10px] text-tech-400 hover:bg-tech-500/10 rounded py-1.5 transition-colors">
                        <Eye className="w-3 h-3" />查看
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

function ListView({ projects, onViewDetail }: {
  projects: ProjectDTO[];
  onViewDetail: (id: string) => void;
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
            <th className="text-left text-xs font-medium text-ink-400 px-4 py-3">技术负责人</th>
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
              <td className="px-4 py-3 text-sm text-ink-300">{p.techLead || p.ownerName || '-'}</td>
              <td className="px-4 py-3 text-sm text-ink-400 truncate max-w-[200px]">{p.worktree || '-'}</td>
              <td className="px-4 py-3">
                <div className="flex items-center gap-1">
                  <button onClick={() => onViewDetail(p.projectId)} className="text-tech-400 hover:bg-tech-500/10 rounded p-1"><Eye className="w-3.5 h-3.5" /></button>
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
