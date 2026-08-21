'use client';
import { useState } from 'react';
import { type RequirementDTO } from '@/lib/requirement';

// ==================== 常量 ====================

const typeConfig: Record<string, { label: string; icon: string }> = {
  STORY: { label: '用户故事', icon: '📖' },
  TASK: { label: '任务', icon: '✅' },
  BUG: { label: '缺陷', icon: '🐛' },
  IMPROVEMENT: { label: '改进', icon: '⬆️' },
  FEATURE: { label: '功能需求', icon: '✨' },
  EPIC: { label: '史诗', icon: '️' },
};

const priorityConfig: Record<number, { label: string }> = {
  0: { label: 'P0 紧急' },
  1: { label: 'P1 高' },
  2: { label: 'P2 中' },
  3: { label: 'P3 低' },
};

// ==================== 编辑需求弹框 ====================

interface EditDialogProps {
  requirement: RequirementDTO;
  onClose: () => void;
  onSubmit: (data: Record<string, unknown>) => void;
}

export function EditDialog({ requirement, onClose, onSubmit }: EditDialogProps) {
  const [title, setTitle] = useState(requirement.title);
  const [description, setDescription] = useState(requirement.description || '');
  const [type, setType] = useState(requirement.type);
  const [priority, setPriority] = useState(requirement.priority);
  const [storyPoint, setStoryPoint] = useState(requirement.storyPoint || 0);
  const [estimatedHours, setEstimatedHours] = useState(requirement.estimatedHours || 0);

  const handleSubmit = () => {
    if (!title.trim()) return;
    onSubmit({ title, description, type, priority, storyPoint, estimatedHours });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div className="glass-dark rounded-xl p-6 w-full max-w-lg border border-tech-500/20 max-h-[85vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-ink-50 mb-4">编辑需求</h3>
        <div className="space-y-3">
          <input
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="需求标题 *"
            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
          />
          <textarea
            value={description}
            onChange={e => setDescription(e.target.value)}
            placeholder="需求描述"
            rows={6}
            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none resize-none"
          />
          <div className="grid grid-cols-2 gap-3">
            <select value={type} onChange={e => setType(e.target.value)} className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none">
              {Object.entries(typeConfig).map(([key, cfg]) => (
                <option key={key} value={key}>{cfg.icon} {cfg.label}</option>
              ))}
            </select>
            <select value={priority} onChange={e => setPriority(Number(e.target.value))} className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none">
              {Object.entries(priorityConfig).map(([key, cfg]) => (
                <option key={key} value={key}>{cfg.label}</option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <input type="number" value={storyPoint || ''} onChange={e => setStoryPoint(Number(e.target.value))} placeholder="故事点" className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none" />
            <input type="number" value={estimatedHours || ''} onChange={e => setEstimatedHours(Number(e.target.value))} placeholder="预估工时" className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none" />
          </div>
        </div>
        <div className="flex gap-2 justify-end mt-4 pt-3 border-t border-tech-500/10">
          <button onClick={onClose} className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200">取消</button>
          <button onClick={handleSubmit} disabled={!title.trim()} className="px-4 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50">保存</button>
        </div>
      </div>
    </div>
  );
}

// ==================== 评审弹框 ====================

interface ReviewDialogProps {
  onClose: () => void;
  onSubmit: (reviewType: string, result: string, comment: string) => void;
}

export function ReviewDialog({ onClose, onSubmit }: ReviewDialogProps) {
  const [reviewType, setReviewType] = useState('REQUIREMENT');
  const [result, setResult] = useState('APPROVED');
  const [comment, setComment] = useState('');

  const handleSubmit = () => {
    onSubmit(reviewType, result, comment);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div className="glass-dark rounded-xl p-6 w-full max-w-md border border-tech-500/20" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-ink-50 mb-4">提交评审意见</h3>
        <div className="space-y-3">
          <select
            value={reviewType}
            onChange={e => setReviewType(e.target.value)}
            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
          >
            <option value="REQUIREMENT">需求评审</option>
            <option value="DESIGN">设计评审</option>
            <option value="TEST">测试案例评审</option>
          </select>
          <select
            value={result}
            onChange={e => setResult(e.target.value)}
            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
          >
            <option value="APPROVED">通过</option>
            <option value="REJECTED">驳回</option>
          </select>
          <textarea
            value={comment}
            onChange={e => setComment(e.target.value)}
            placeholder="评审意见..."
            rows={4}
            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none resize-none"
          />
        </div>
        <div className="flex gap-2 justify-end mt-4 pt-3 border-t border-tech-500/10">
          <button onClick={onClose} className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200">取消</button>
          <button onClick={handleSubmit} className="px-4 py-2 text-sm btn-primary text-white rounded-lg">提交</button>
        </div>
      </div>
    </div>
  );
}

// ==================== 任务弹框 ====================

interface TaskDialogProps {
  onClose: () => void;
  onSubmit: (title: string, assigneeId: string, estimatedHours: number) => void;
}

export function TaskDialog({ onClose, onSubmit }: TaskDialogProps) {
  const [title, setTitle] = useState('');
  const [estimatedHours, setEstimatedHours] = useState(0);

  const handleSubmit = () => {
    if (!title.trim()) return;
    onSubmit(title, '', estimatedHours);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div className="glass-dark rounded-xl p-6 w-full max-w-md border border-tech-500/20" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-ink-50 mb-4">创建任务</h3>
        <div className="space-y-3">
          <input
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="任务标题 *"
            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
          />
          <input
            type="number"
            value={estimatedHours || ''}
            onChange={e => setEstimatedHours(Number(e.target.value))}
            placeholder="预估工时（小时）"
            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none"
          />
        </div>
        <div className="flex gap-2 justify-end mt-4 pt-3 border-t border-tech-500/10">
          <button onClick={onClose} className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200">取消</button>
          <button onClick={handleSubmit} disabled={!title.trim()} className="px-4 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50">创建</button>
        </div>
      </div>
    </div>
  );
}
