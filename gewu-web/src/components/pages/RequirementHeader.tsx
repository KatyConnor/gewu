'use client';
import { ArrowLeft, Edit, Trash2, CheckCircle } from 'lucide-react';
import type { RequirementDTO } from '@/lib/requirement';

// ==================== 常量 ====================

const typeConfig: Record<string, { label: string; icon: string; color: string }> = {
  STORY: { label: '用户故事', icon: '📖', color: 'bg-blue-500/10 text-blue-400' },
  TASK: { label: '任务', icon: '✅', color: 'bg-green-500/10 text-green-400' },
  BUG: { label: '缺陷', icon: '🐛', color: 'bg-danger-500/10 text-danger-400' },
  IMPROVEMENT: { label: '改进', icon: '⬆️', color: 'bg-tech-500/10 text-tech-400' },
  FEATURE: { label: '功能需求', icon: '✨', color: 'bg-purple-500/10 text-purple-400' },
  EPIC: { label: '史诗', icon: '️', color: 'bg-gold-500/10 text-gold-400' },
};

const priorityConfig: Record<number, { label: string; color: string }> = {
  0: { label: 'P0 紧急', color: 'bg-danger-500/10 text-danger-400' },
  1: { label: 'P1 高', color: 'bg-gold-500/10 text-gold-400' },
  2: { label: 'P2 中', color: 'bg-tech-500/10 text-tech-400' },
  3: { label: 'P3 低', color: 'bg-ink-500/10 text-ink-400' },
};

const statusConfig: Record<string, { label: string; color: string; nextStatus?: string; nextLabel?: string }> = {
  DRAFT: { label: '草稿', color: 'bg-ink-500/10 text-ink-400', nextStatus: 'PENDING_REVIEW', nextLabel: '提交评审' },
  PENDING_REVIEW: { label: '待需求评审', color: 'bg-gold-500/10 text-gold-400' },
  IN_REVIEW: { label: '评审中', color: 'bg-tech-500/10 text-tech-400' },
  APPROVED: { label: '已通过', color: 'bg-green-500/10 text-green-400', nextStatus: 'DESIGN', nextLabel: '进入设计' },
  DESIGN: { label: '设计', color: 'bg-purple-500/10 text-purple-400', nextStatus: 'PENDING_DEV', nextLabel: '开始开发' },
  PENDING_DEV: { label: '待开发', color: 'bg-blue-500/10 text-blue-400', nextStatus: 'IN_DEV', nextLabel: '开始开发' },
  IN_DEV: { label: '开发中', color: 'bg-cyber-500/10 text-cyber-400', nextStatus: 'DEV_COMPLETED', nextLabel: '开发完成' },
  DEV_COMPLETED: { label: '开发完成', color: 'bg-green-500/10 text-green-400', nextStatus: 'SMOKE_TEST', nextLabel: '冒烟测试' },
  SMOKE_TEST: { label: '冒烟测试', color: 'bg-gold-500/10 text-gold-400', nextStatus: 'SIT_TEST', nextLabel: 'SIT 测试' },
  SIT_TEST: { label: 'SIT 测试', color: 'bg-tech-500/10 text-tech-400', nextStatus: 'UAT_TEST', nextLabel: 'UAT 测试' },
  UAT_TEST: { label: 'UAT 测试', color: 'bg-purple-500/10 text-purple-400', nextStatus: 'RELEASED', nextLabel: '上线' },
  RELEASED: { label: '已上线', color: 'bg-green-500/10 text-green-400', nextStatus: 'ARCHIVED', nextLabel: '归档' },
  ARCHIVED: { label: '已归档', color: 'bg-ink-500/10 text-ink-400' },
  CANCELLED: { label: '已取消', color: 'bg-danger-500/10 text-danger-400' },
};

// ==================== 组件 Props ====================

interface RequirementHeaderProps {
  requirement: RequirementDTO;
  onBack: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onStatusChange: (newStatus: string) => void;
}

// ==================== 主组件 ====================

export default function RequirementHeader({ requirement, onBack, onEdit, onDelete, onStatusChange }: RequirementHeaderProps) {
  const typeCfg = typeConfig[requirement.type] || typeConfig.STORY;
  const priorityCfg = priorityConfig[requirement.priority] || priorityConfig[2];
  const statusCfg = statusConfig[requirement.status] || statusConfig.DRAFT;

  return (
    <>
      {/* 顶部导航 */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <button onClick={onBack} className="p-2 text-ink-400 hover:text-ink-200 rounded-lg hover:bg-ink-800/30">
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div>
            <h1 className="text-xl font-semibold text-ink-50">{requirement.title}</h1>
            <p className="text-xs text-ink-500 mt-0.5">{requirement.requirementCode}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {statusCfg.nextStatus && (
            <button
              onClick={() => onStatusChange(statusCfg.nextStatus!)}
              className="flex items-center gap-1.5 px-4 py-2 text-sm btn-primary text-white rounded-lg"
            >
              <CheckCircle className="w-4 h-4" />{statusCfg.nextLabel}
            </button>
          )}
          <button
            onClick={onEdit}
            className="px-3 py-2 text-sm text-gold-400 border border-gold-500/20 rounded-lg hover:bg-gold-500/10"
          >
            <Edit className="w-4 h-4 inline mr-1" />编辑
          </button>
          <button
            onClick={onDelete}
            className="px-3 py-2 text-sm text-cinnabar-400 border border-cinnabar-500/20 rounded-lg hover:bg-cinnabar-500/10"
          >
            <Trash2 className="w-4 h-4 inline mr-1" />删除
          </button>
        </div>
      </div>

      {/* 基本信息卡片 */}
      <div className="glass-dark rounded-xl p-5 mb-4 border border-tech-500/10">
        <div className="grid grid-cols-4 gap-4 mb-4">
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">类型</p>
            <span className={`text-xs px-2 py-1 rounded ${typeCfg.color}`}>{typeCfg.icon} {typeCfg.label}</span>
          </div>
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">优先级</p>
            <span className={`text-xs px-2 py-1 rounded ${priorityCfg.color}`}>{priorityCfg.label}</span>
          </div>
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">状态</p>
            <span className={`text-xs px-2 py-1 rounded ${statusCfg.color}`}>{statusCfg.label}</span>
          </div>
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">负责人</p>
            <p className="text-sm text-ink-200">{requirement.assigneeName || '未分配'}</p>
          </div>
        </div>
        <div className="grid grid-cols-4 gap-4 pt-4 border-t border-tech-500/10">
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">提出人</p>
            <p className="text-sm text-ink-300">{requirement.reporterName || '-'}</p>
          </div>
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">故事点</p>
            <p className="text-sm text-ink-300">{requirement.storyPoint ?? '-'}</p>
          </div>
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">预估工时</p>
            <p className="text-sm text-ink-300">{requirement.estimatedHours ? `${requirement.estimatedHours}h` : '-'}</p>
          </div>
          <div>
            <p className="text-[10px] text-ink-500 uppercase mb-1">截止日期</p>
            <p className="text-sm text-ink-300">{requirement.dueDate ? new Date(requirement.dueDate).toLocaleDateString('zh-CN') : '-'}</p>
          </div>
        </div>
      </div>
    </>
  );
}

// 导出常量供其他组件使用
export { typeConfig, priorityConfig, statusConfig };
