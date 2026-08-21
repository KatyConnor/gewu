'use client';
import { CheckCircle, Clock, AlertCircle, MessageSquare } from 'lucide-react';
import type { RequirementReviewDTO } from '@/lib/requirement';

// ==================== 常量 ====================

const reviewStageConfig: Record<string, { label: string; icon: string; order: number }> = {
  REQUIREMENT: { label: '需求评审', icon: '📋', order: 1 },
  DESIGN: { label: '设计评审', icon: '🎨', order: 2 },
  TEST: { label: '测试案例评审', icon: '🧪', order: 3 },
};

const reviewResultConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  APPROVED: {
    label: '通过',
    color: 'text-green-400',
    icon: <CheckCircle className="w-4 h-4" />,
  },
  REJECTED: {
    label: '驳回',
    color: 'text-danger-400',
    icon: <AlertCircle className="w-4 h-4" />,
  },
  PENDING: {
    label: '待评审',
    color: 'text-gold-400',
    icon: <Clock className="w-4 h-4" />,
  },
};

// ==================== 组件 Props ====================

interface ReviewTimelineProps {
  reviews: RequirementReviewDTO[];
  currentStatus: string;
}

// ==================== 主组件 ====================

export default function ReviewTimeline({ reviews, currentStatus }: ReviewTimelineProps) {
  // 按评审阶段分组
  const groupedReviews = reviews.reduce((acc, review) => {
    const stage = review.reviewType;
    if (!acc[stage]) acc[stage] = [];
    acc[stage].push(review);
    return acc;
  }, {} as Record<string, RequirementReviewDTO[]>);

  // 按顺序排序评审阶段
  const sortedStages = Object.keys(groupedReviews).sort(
    (a, b) => (reviewStageConfig[a]?.order || 99) - (reviewStageConfig[b]?.order || 99)
  );

  if (reviews.length === 0) {
    return (
      <div className="text-center py-8">
        <MessageSquare className="w-8 h-8 text-ink-600 mx-auto mb-2" />
        <p className="text-sm text-ink-500">暂无评审记录</p>
        <p className="text-xs text-ink-600 mt-1">提交评审后将在此显示评审流程</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {sortedStages.map((stage, stageIndex) => {
        const stageConfig = reviewStageConfig[stage] || { label: stage, icon: '📋', order: 99 };
        const stageReviews = groupedReviews[stage];
        const isLastStage = stageIndex === sortedStages.length - 1;

        return (
          <div key={stage} className="relative">
            {/* 阶段标题 */}
            <div className="flex items-center gap-2 mb-4">
              <span className="text-lg">{stageConfig.icon}</span>
              <h4 className="text-sm font-medium text-ink-200">{stageConfig.label}</h4>
              <span className="text-xs text-ink-500">({stageReviews.length} 条记录)</span>
            </div>

            {/* 时间线 */}
            <div className="space-y-4">
              {stageReviews.map((review, reviewIndex) => {
                const resultConfig = reviewResultConfig[review.reviewResult] || reviewResultConfig.PENDING;
                const isLastReview = reviewIndex === stageReviews.length - 1;

                return (
                  <div key={review.id} className="relative pl-8">
                    {/* 时间线节点 */}
                    <div className={`absolute left-0 top-0 w-8 h-8 rounded-full flex items-center justify-center ${
                      review.reviewResult === 'APPROVED' ? 'bg-green-500/20 text-green-400' :
                      review.reviewResult === 'REJECTED' ? 'bg-danger-500/20 text-danger-400' :
                      'bg-gold-500/20 text-gold-400'
                    }`}>
                      {resultConfig.icon}
                    </div>

                    {/* 连接线 */}
                    {!isLastReview && (
                      <div className="absolute left-4 top-8 w-0.5 h-full bg-tech-500/20" />
                    )}
                    {!isLastStage && isLastReview && (
                      <div className="absolute left-4 top-8 w-0.5 h-full bg-tech-500/20" />
                    )}

                    {/* 评审内容卡片 */}
                    <div className="glass-dark rounded-lg p-4 border border-tech-500/10">
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-2">
                          <span className={`text-xs px-2 py-0.5 rounded ${
                            review.reviewResult === 'APPROVED' ? 'bg-green-500/10 text-green-400' :
                            review.reviewResult === 'REJECTED' ? 'bg-danger-500/10 text-danger-400' :
                            'bg-gold-500/10 text-gold-400'
                          }`}>
                            {resultConfig.label}
                          </span>
                          <span className="text-xs text-ink-300">{review.reviewerName || '评审人'}</span>
                        </div>
                        <span className="text-[10px] text-ink-500">
                          {review.completedAt ? new Date(review.completedAt).toLocaleString('zh-CN') : '-'}
                        </span>
                      </div>

                      {review.reviewComment && (
                        <div className="mt-2 p-3 bg-ink-800/30 rounded-lg border border-tech-500/5">
                          <p className="text-xs text-ink-400 leading-relaxed">{review.reviewComment}</p>
                        </div>
                      )}

                      {review.reviewAttachments && (
                        <div className="mt-2 flex items-center gap-2">
                          <span className="text-[10px] text-ink-500">附件:</span>
                          <span className="text-[10px] text-tech-400">查看附件</span>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        );
      })}

      {/* 当前状态指示 */}
      <div className="mt-8 pt-4 border-t border-tech-500/10">
        <div className="flex items-center gap-2">
          <Clock className="w-4 h-4 text-tech-400" />
          <span className="text-xs text-ink-400">当前状态:</span>
          <span className="text-xs text-tech-400">{currentStatus}</span>
        </div>
      </div>
    </div>
  );
}
