'use client';
import { useState, useEffect, useCallback } from 'react';
import { useToast } from '@/components/ui/Toast';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import RequirementHeader from './RequirementHeader';
import ReviewTimeline from './ReviewTimeline';
import TaskList from './TaskList';
import CommentsTab from './CommentsTab';
import {
  getRequirement, getReviews, getTasks, getComments, createComment,
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

  const handleCommentAdd = async (content: string) => {
    try {
      await createComment(requirementId, { content });
      toast('评论已添加', 'success');
      loadData();
    } catch {
      toast('操作失败', 'error');
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
      {/* 顶部导航 + 基本信息（只读：需求管理已迁移管理端） */}
      {requirement && (
        <RequirementHeader requirement={requirement} onBack={onBack} />
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
          <h3 className="text-sm font-medium text-ink-200 mb-4">评审流程</h3>
          <ReviewTimeline reviews={reviews} currentStatus={requirement.status} />
        </div>
      )}

      {activeTab === 'tasks' && (
        <div className="glass-dark rounded-xl p-5 border border-tech-500/10">
          <h3 className="text-sm font-medium text-ink-200 mb-4">任务列表</h3>
          <TaskList
            tasks={tasks}
            editable={false}
            onAddTask={() => {}}
            onUpdateTask={() => {}}
            onDeleteTask={() => {}}
          />
        </div>
      )}

      {activeTab === 'comments' && (
        <CommentsTab
          comments={comments}
          onAdd={handleCommentAdd}
        />
      )}
    </div>
  );
}
