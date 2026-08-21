'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus, Search, LayoutGrid, List, Eye, Edit, X } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import {
  listRequirements, createRequirement, updateRequirement, updateRequirementStatus,
  submitReview, getRequirementStats, type RequirementDTO, type RequirementStatsDTO,
} from '@/lib/requirement';
import RequirementEditor from './RequirementEditor';
import RequirementBoard from './RequirementBoard';

// ==================== 常量定义 ====================

const typeConfig: Record<string, { label: string; icon: string; color: string }> = {
  STORY: { label: '用户故事', icon: '📖', color: 'bg-blue-500/10 text-blue-400 border-blue-500/20' },
  TASK: { label: '任务', icon: '✅', color: 'bg-green-500/10 text-green-400 border-green-500/20' },
  BUG: { label: '缺陷', icon: '', color: 'bg-danger-500/10 text-danger-400 border-danger-500/20' },
  IMPROVEMENT: { label: '改进', icon: '⬆️', color: 'bg-tech-500/10 text-tech-400 border-tech-500/20' },
  FEATURE: { label: '功能需求', icon: '✨', color: 'bg-purple-500/10 text-purple-400 border-purple-500/20' },
  EPIC: { label: '史诗', icon: '🏔️', color: 'bg-gold-500/10 text-gold-400 border-gold-500/20' },
};

const priorityConfig: Record<number, { label: string; color: string }> = {
  0: { label: 'P0 紧急', color: 'bg-danger-500/10 text-danger-400 border-danger-500/20' },
  1: { label: 'P1 高', color: 'bg-gold-500/10 text-gold-400 border-gold-500/20' },
  2: { label: 'P2 中', color: 'bg-tech-500/10 text-tech-400 border-tech-500/20' },
  3: { label: 'P3 低', color: 'bg-ink-500/10 text-ink-400 border-ink-500/20' },
};

const statusConfig: Record<string, { label: string; color: string; nextStatus?: string; nextLabel?: string }> = {
  DRAFT: { label: '草稿', color: 'bg-ink-500/10 text-ink-400 border-ink-500/20', nextStatus: 'PENDING_REVIEW', nextLabel: '发起评审' },
  PENDING_REVIEW: { label: '待需求评审', color: 'bg-gold-500/10 text-gold-400 border-gold-500/20' },
  IN_REVIEW: { label: '评审中', color: 'bg-tech-500/10 text-tech-400 border-tech-500/20' },
  APPROVED: { label: '已通过', color: 'bg-green-500/10 text-green-400 border-green-500/20', nextStatus: 'DESIGN', nextLabel: '进入设计' },
  DESIGN: { label: '设计', color: 'bg-purple-500/10 text-purple-400 border-purple-500/20', nextStatus: 'PENDING_DEV', nextLabel: '开始开发' },
  PENDING_DEV: { label: '待开发', color: 'bg-blue-500/10 text-blue-400 border-blue-500/20', nextStatus: 'IN_DEV', nextLabel: '开始开发' },
  IN_DEV: { label: '开发中', color: 'bg-cyber-500/10 text-cyber-400 border-cyber-500/20', nextStatus: 'DEV_COMPLETED', nextLabel: '开发完成' },
  DEV_COMPLETED: { label: '开发完成', color: 'bg-green-500/10 text-green-400 border-green-500/20', nextStatus: 'SMOKE_TEST', nextLabel: '冒烟测试' },
  SMOKE_TEST: { label: '冒烟测试', color: 'bg-gold-500/10 text-gold-400 border-gold-500/20', nextStatus: 'SIT_TEST', nextLabel: 'SIT测试' },
  SIT_TEST: { label: 'SIT测试', color: 'bg-tech-500/10 text-tech-400 border-tech-500/20', nextStatus: 'UAT_TEST', nextLabel: 'UAT测试' },
  UAT_TEST: { label: 'UAT测试', color: 'bg-purple-500/10 text-purple-400 border-purple-500/20', nextStatus: 'RELEASED', nextLabel: '上线' },
  RELEASED: { label: '已上线', color: 'bg-green-500/10 text-green-400 border-green-500/20', nextStatus: 'ARCHIVED', nextLabel: '归档' },
  ARCHIVED: { label: '已归档', color: 'bg-ink-500/10 text-ink-400 border-ink-500/20' },
  CANCELLED: { label: '已取消', color: 'bg-danger-500/10 text-danger-400 border-danger-500/20' },
};

type ViewType = 'table' | 'board';

export default function RequirementsPage() {
  const [requirements, setRequirements] = useState<RequirementDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState<RequirementStatsDTO | null>(null);
  const [viewType, setViewType] = useState<ViewType>('table');
  const [showCreate, setShowCreate] = useState(false);
  const [editingRequirement, setEditingRequirement] = useState<RequirementDTO | null>(null);
  const [keyword, setKeyword] = useState('');
  const [filterType, setFilterType] = useState('');
  const [filterPriority, setFilterPriority] = useState<number | undefined>(undefined);
  const [filterStatus, setFilterStatus] = useState('');
  const [currentPage, setCurrentPage] = useState(1);
  const [total, setTotal] = useState(0);

  const toast = useToast();

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [result, statsResult] = await Promise.all([
        listRequirements({
          page: currentPage,
          size: 20,
          keyword: keyword || undefined,
          type: filterType || undefined,
          priority: filterPriority,
          status: filterStatus || undefined,
        }),
        getRequirementStats(),
      ]);
      setRequirements(result.records || []);
      setTotal(result.total || 0);
      setStats(statsResult);
    } catch {
      setRequirements([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [currentPage, keyword, filterType, filterPriority, filterStatus]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleSearch = () => {
    setCurrentPage(1);
    loadData();
  };

  const handleReset = () => {
    setKeyword('');
    setFilterType('');
    setFilterPriority(undefined);
    setFilterStatus('');
    setCurrentPage(1);
  };

  const handleCreate = async (data: Record<string, unknown>) => {
    try {
      await createRequirement(data);
      toast('需求创建成功', 'success');
      setShowCreate(false);
      loadData();
    } catch {
      toast('创建失败', 'error');
    }
  };

  const handleEditSubmit = async (data: Record<string, unknown>) => {
    if (!editingRequirement) return;
    try {
      await updateRequirement(editingRequirement.id, data);
      toast('需求已更新', 'success');
      setEditingRequirement(null);
      loadData();
    } catch {
      toast('更新失败', 'error');
    }
  };

  const handleCancel = async (id: string, title: string) => {
    if (!confirm(`确定要取消需求 "${title}" 吗？此操作不可恢复。`)) return;
    try {
      await updateRequirementStatus(id, 'CANCELLED');
      toast('需求已取消', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleAdvance = async (requirement: RequirementDTO) => {
    const cfg = statusConfig[requirement.status];
    if (!cfg?.nextStatus) return;
    try {
      if (requirement.status === 'DRAFT') {
        await submitReview(requirement.id, 'REQUIREMENT');
      } else {
        await updateRequirementStatus(requirement.id, cfg.nextStatus);
      }
      toast('操作成功', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleStatusChange = async (id: string, newStatus: string) => {
    try {
      await updateRequirementStatus(id, newStatus);
      toast('状态更新成功', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleBoardDragEnd = async (requirementId: string, newStatus: string) => {
    try {
      await updateRequirementStatus(requirementId, newStatus);
      toast('状态已更新', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleRequirementClick = (id: string) => {
    window.location.hash = `#/requirements/${id}`;
  };

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
          <h1 className="text-2xl font-semibold text-ink-50">需求管理</h1>
          <p className="text-ink-400 text-sm mt-1">跟踪和管理产品需求全生命周期</p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"
        >
          <Plus className="w-4 h-4" />新建需求
        </button>
      </header>

      {/* 统计卡片 */}
      {stats && (
        <div className="grid grid-cols-4 gap-4 mb-6">
          {Object.entries(stats.statusCount).slice(0, 4).map(([status, count]) => (
            <div key={status} className="glass-dark rounded-xl p-4 text-center">
              <p className="text-2xl font-bold text-tech-400">{count}</p>
              <p className="text-xs text-ink-500 mt-1">{statusConfig[status]?.label || status}</p>
            </div>
          ))}
          <div className="glass-dark rounded-xl p-4 text-center">
            <p className="text-2xl font-bold text-ink-200">{stats.totalCount}</p>
            <p className="text-xs text-ink-500 mt-1">总需求数</p>
          </div>
        </div>
      )}

      {/* 搜索区域 */}
      <div className="glass-dark rounded-xl p-4 mb-4 border border-tech-500/10">
        <div className="grid grid-cols-4 gap-3">
          <input
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleSearch()}
            placeholder="搜索需求标题或编号..."
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
          />
          <select
            value={filterType}
            onChange={e => setFilterType(e.target.value)}
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
          >
            <option value="">全部类型</option>
            {Object.entries(typeConfig).map(([key, cfg]) => (
              <option key={key} value={key}>{cfg.icon} {cfg.label}</option>
            ))}
          </select>
          <select
            value={filterPriority ?? ''}
            onChange={e => setFilterPriority(e.target.value ? Number(e.target.value) : undefined)}
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
          >
            <option value="">全部优先级</option>
            <option value="0">P0 紧急</option>
            <option value="1">P1 高</option>
            <option value="2">P2 中</option>
            <option value="3">P3 低</option>
          </select>
          <select
            value={filterStatus}
            onChange={e => setFilterStatus(e.target.value)}
            className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
          >
            <option value="">全部状态</option>
            {Object.entries(statusConfig).map(([key, cfg]) => (
              <option key={key} value={key}>{cfg.label}</option>
            ))}
          </select>
        </div>
        <div className="flex items-center justify-between mt-3">
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-1 p-1 rounded-lg bg-ink-800/30 border border-tech-500/10">
              <button
                onClick={() => setViewType('table')}
                className={`flex items-center gap-1 px-3 py-1 text-xs rounded ${viewType === 'table' ? 'bg-tech-500/20 text-tech-400' : 'text-ink-400'}`}
              >
                <List className="w-3 h-3" />表格
              </button>
              <button
                onClick={() => setViewType('board')}
                className={`flex items-center gap-1 px-3 py-1 text-xs rounded ${viewType === 'board' ? 'bg-tech-500/20 text-tech-400' : 'text-ink-400'}`}
              >
                <LayoutGrid className="w-3 h-3" />看板
              </button>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={handleReset} className="px-4 py-1.5 text-sm text-ink-400 hover:text-ink-200">重置</button>
            <button onClick={handleSearch} className="flex items-center gap-1.5 px-4 py-1.5 text-sm btn-primary text-white rounded-lg">
              <Search className="w-4 h-4" />搜索
            </button>
          </div>
        </div>
      </div>

      {/* 表格视图 */}
      {viewType === 'table' && (
        <div className="glass-dark rounded-xl overflow-hidden border border-tech-500/10">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-tech-500/10 bg-ink-800/30">
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">编号</th>
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">标题</th>
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">类型</th>
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">优先级</th>
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">状态</th>
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">负责人</th>
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">截止日期</th>
                <th className="text-left py-3 px-4 text-xs text-ink-500 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {requirements.map(r => {
                const typeCfg = typeConfig[r.type] || typeConfig.STORY;
                const priorityCfg = priorityConfig[r.priority] || priorityConfig[2];
                const statusCfg = statusConfig[r.status] || statusConfig.DRAFT;
                return (
                  <tr
                    key={r.id}
                    className="border-b border-tech-500/5 hover:bg-ink-800/20 transition-colors"
                  >
                    <td className="py-3 px-4 text-xs text-ink-500">{r.requirementCode}</td>
                    <td className="py-3 px-4 text-ink-100 font-medium">{r.title}</td>
                    <td className="py-3 px-4"><span className={`text-xs px-2 py-0.5 rounded border ${typeCfg.color}`}>{typeCfg.icon} {typeCfg.label}</span></td>
                    <td className="py-3 px-4"><span className={`text-xs px-2 py-0.5 rounded border ${priorityCfg.color}`}>{priorityCfg.label}</span></td>
                    <td className="py-3 px-4"><span className={`text-xs px-2 py-0.5 rounded border ${statusCfg.color}`}>{statusCfg.label}</span></td>
                    <td className="py-3 px-4 text-ink-300">{r.assigneeName || '-'}</td>
                    <td className="py-3 px-4 text-ink-400">{r.dueDate ? new Date(r.dueDate).toLocaleDateString('zh-CN') : '-'}</td>
                    <td className="py-3 px-4">
                      <div className="flex items-center gap-1">
                        {statusCfg.nextStatus && (
                          <button
                            onClick={() => handleAdvance(r)}
                            className="text-[10px] px-2 py-1 text-tech-400 hover:bg-tech-500/10 rounded whitespace-nowrap"
                          >
                            {statusCfg.nextLabel}
                          </button>
                        )}
                        <button
                          onClick={() => { window.location.hash = `#/requirements/${r.id}`; }}
                          className="text-tech-400 hover:bg-tech-500/10 rounded p-1"
                          title="查看"
                        >
                          <Eye className="w-3.5 h-3.5" />
                        </button>
                        {r.status === 'DRAFT' && (
                          <button
                            onClick={() => setEditingRequirement(r)}
                            className="text-gold-400 hover:bg-gold-500/10 rounded p-1"
                            title="编辑"
                          >
                            <Edit className="w-3.5 h-3.5" />
                          </button>
                        )}
                        {r.status !== 'ARCHIVED' && r.status !== 'CANCELLED' && (
                          <button
                            onClick={() => handleCancel(r.id, r.title)}
                            className="text-cinnabar-400 hover:bg-cinnabar-500/10 rounded p-1"
                            title="取消"
                          >
                            <X className="w-3.5 h-3.5" />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {requirements.length === 0 && (
            <div className="text-center py-12 text-ink-500">暂无需求数据</div>
          )}
        </div>
      )}

      {/* 看板视图 */}
      {viewType === 'board' && (
        <RequirementBoard
          requirements={requirements}
          onStatusChange={handleBoardDragEnd}
          onRequirementClick={handleRequirementClick}
        />
      )}

      {/* 分页 */}
      {total > 20 && (
        <div className="flex items-center justify-between mt-4 pt-4 border-t border-tech-500/10">
          <span className="text-sm text-ink-400">共 {total} 条需求</span>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
              disabled={currentPage === 1}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50"
            >
              上一页
            </button>
            <span className="text-sm text-ink-300">{currentPage} / {Math.ceil(total / 20)}</span>
            <button
              onClick={() => setCurrentPage(p => Math.min(Math.ceil(total / 20), p + 1))}
              disabled={currentPage >= Math.ceil(total / 20)}
              className="px-3 py-1.5 text-sm bg-ink-800/50 border border-tech-500/10 rounded text-ink-200 disabled:opacity-50"
            >
              下一页
            </button>
          </div>
        </div>
      )}

      {/* 新建需求弹框 */}
      {showCreate && (
        <RequirementEditor
          onClose={() => setShowCreate(false)}
          onSubmit={handleCreate}
        />
      )}

      {/* 编辑需求弹框 */}
      {editingRequirement && (
        <RequirementEditor
          requirement={editingRequirement}
          onClose={() => setEditingRequirement(null)}
          onSubmit={handleEditSubmit}
        />
      )}
    </div>
  );
}


