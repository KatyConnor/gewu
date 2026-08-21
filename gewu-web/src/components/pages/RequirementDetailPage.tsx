'use client';
import { useState, useEffect, useCallback } from 'react';
import { Plus } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import RequirementHeader, { statusConfig } from './RequirementHeader';
import ReviewTimeline from './ReviewTimeline';
import TaskList from './TaskList';
import CommentsTab from './CommentsTab';
import { EditDialog, ReviewDialog, TaskDialog } from './RequirementDialogs';
import {
  getRequirement, updateRequirement, updateRequirementStatus, deleteRequirement,
  getReviews, submitReviewOpinion, getTasks, createTask, updateTask, deleteTask,
  getComments, createComment, deleteComment,
  type RequirementDTO, type RequirementReviewDTO, type RequirementTaskDTO, type RequirementCommentDTO,
} from '@/lib/requirement';

type TabType = 'overview' | 'reviews' | 'tasks' | 'comments';

// ==================== 主组件 ====================

interface Props {
  requirementId: string;
  onBack: () => void;
}

export default function RequirementDetailPage({ requirementId, onBack }: Props) {
  const [requirement, setRequirement] = useState<RequirementDTO | null>(null);
  const [activeTab, setActiveTab] = useState<TabType>('overview');
  const [reviews, setReviews] = useState<RequirementReviewDTO[]>([]);
  const [tasks, setTasks] = useState<RequirementTaskDTO[]>([]);
  const [comments, setComments] = useState<RequirementCommentDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [showEdit, setShowEdit] = useState(false);
  const [showReviewDialog, setShowReviewDialog] = useState(false);
  const [showTaskDialog, setShowTaskDialog] = useState(false);

  const toast = useToast();

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [req, revs, tasks, comments] = await Promise.all([
        getRequirement(requirementId),
        getReviews(requirementId),
        getTasks(requirementId),
        getComments(requirementId),
      ]);
      setRequirement(req);
      setReviews(revs);
      setTasks(tasks);
      setComments(comments);
    } catch {
      toast('加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [requirementId, toast]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleStatusChange = async (newStatus: string) => {
    if (!confirm(`确定将需求状态变更为「${statusConfig[newStatus]?.label}」吗？`)) return;
    try {
      const updated = await updateRequirementStatus(requirementId, newStatus);
      setRequirement(updated);
      toast('状态更新成功', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleDelete = async () => {
    if (!confirm('确定要删除此需求吗？此操作不可恢复。')) return;
    try {
      await deleteRequirement(requirementId);
      toast('需求已删除', 'success');
      onBack();
    } catch {
      toast('删除失败', 'error');
    }
  };

  const handleReviewSubmit = async (reviewType: string, result: string, comment: string) => {
    try {
      await submitReviewOpinion(requirementId, { reviewType, reviewResult: result, reviewComment: comment });
      toast('评审意见已提交', 'success');
      setShowReviewDialog(false);
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleTaskCreate = async (title: string, assigneeId: string, estimatedHours: number) => {
    try {
      await createTask(requirementId, { title, assigneeId, estimatedHours });
      toast('任务创建成功', 'success');
      setShowTaskDialog(false);
      loadData();
    } catch {
      toast('创建失败', 'error');
    }
  };

  const handleTaskUpdate = async (taskId: string, data: Record<string, unknown>) => {
    try {
      await updateTask(taskId, data);
      toast('任务已更新', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleTaskDelete = async (taskId: string) => {
    if (!confirm('确定删除此任务？')) return;
    try {
      await deleteTask(taskId);
      toast('任务已删除', 'success');
      loadData();
    } catch {
      toast('删除失败', 'error');
    }
  };

  const handleCommentAdd = async (content: string) => {
    try {
      await createComment(requirementId, { content });
      toast('评论已添加', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
    }
  };

  const handleCommentDelete = async (commentId: string) => {
    if (!confirm('确定删除此评论？')) return;
    try {
      await deleteComment(commentId);
      toast('评论已删除', 'success');
      loadData();
    } catch {
      toast('删除失败', 'error');
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="w-6 h-6 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (!requirement) {
    return (
      <div className="text-center py-16 text-ink-500">需求不存在</div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto">
      {/* 顶部导航 + 基本信息 */}
      {requirement && (
        <RequirementHeader
          requirement={requirement}
          onBack={onBack}
          onEdit={() => setShowEdit(true)}
          onDelete={handleDelete}
          onStatusChange={handleStatusChange}
        />
      )}

      {/* Tab 切换 */}
      <div className="flex gap-1 mb-4 p-1 rounded-lg bg-ink-800/30 border border-tech-500/10 w-fit">
        {[
          { id: 'overview', label: '概览' },
          { id: 'reviews', label: `评审 (${reviews.length})` },
          { id: 'tasks', label: `任务 (${tasks.length})` },
          { id: 'comments', label: `评论 (${comments.length})` },
        ].map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id as TabType)}
            className={`px-4 py-2 text-sm rounded-md transition-all ${
              activeTab === tab.id ? 'bg-tech-500/20 text-tech-400' : 'text-ink-400 hover:text-ink-200'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab 内容 */}
      {activeTab === 'overview' && (
        <div className="glass-dark rounded-xl p-5 border border-tech-500/10">
          <h3 className="text-sm font-medium text-ink-200 mb-3">需求描述</h3>
          {requirement.description ? (
            <MarkdownRenderer content={requirement.description} />
          ) : (
            <p className="text-sm text-ink-500">暂无描述</p>
          )}
        </div>
      )}

      {activeTab === 'reviews' && (
        <div className="glass-dark rounded-xl p-5 border border-tech-500/10">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-medium text-ink-200">评审流程</h3>
            <button onClick={() => setShowReviewDialog(true)} className="flex items-center gap-1 px-3 py-1.5 text-xs btn-primary text-white rounded-lg">
              <Plus className="w-3 h-3" />添加评审
            </button>
          </div>
          <ReviewTimeline reviews={reviews} currentStatus={requirement.status} />
        </div>
      )}

      {activeTab === 'tasks' && (
        <div className="glass-dark rounded-xl p-5 border border-tech-500/10">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-medium text-ink-200">任务列表</h3>
            <button onClick={() => setShowTaskDialog(true)} className="flex items-center gap-1 px-3 py-1.5 text-xs btn-primary text-white rounded-lg">
              <Plus className="w-3 h-3" />创建任务
            </button>
          </div>
          <TaskList
            tasks={tasks}
            onAddTask={() => setShowTaskDialog(true)}
            onUpdateTask={handleTaskUpdate}
            onDeleteTask={handleTaskDelete}
          />
        </div>
      )}

      {activeTab === 'comments' && (
        <CommentsTab
          comments={comments}
          onAdd={handleCommentAdd}
          onDelete={handleCommentDelete}
        />
      )}

      {/* 弹框 */}
      {showReviewDialog && (
        <ReviewDialog
          onClose={() => setShowReviewDialog(false)}
          onSubmit={handleReviewSubmit}
        />
      )}

      {showTaskDialog && (
        <TaskDialog
          onClose={() => setShowTaskDialog(false)}
          onSubmit={handleTaskCreate}
        />
      )}

      {showEdit && (
        <EditDialog
          requirement={requirement}
          onClose={() => setShowEdit(false)}
          onSubmit={async (data) => {
            try {
              const updated = await updateRequirement(requirementId, data);
              setRequirement(updated);
              toast('需求已更新', 'success');
              setShowEdit(false);
            } catch {
              toast('更新失败', 'error');
            }
          }}
        />
      )}
    </div>
  );
}
