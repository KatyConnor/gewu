'use client';
import { useState } from 'react';
import { Send } from 'lucide-react';
import type { RequirementCommentDTO } from '@/lib/requirement';

// ==================== 组件 Props ====================

interface CommentsTabProps {
  comments: RequirementCommentDTO[];
  onAdd: (content: string) => void;
  onDelete: (commentId: string) => void;
}

// ==================== 主组件 ====================

export default function CommentsTab({ comments, onAdd, onDelete }: CommentsTabProps) {
  const [content, setContent] = useState('');

  const handleSubmit = () => {
    if (!content.trim()) return;
    onAdd(content);
    setContent('');
  };

  return (
    <div className="space-y-4">
      {/* 发表评论 */}
      <div className="glass-dark rounded-xl p-4 border border-tech-500/10">
        <textarea
          value={content}
          onChange={e => setContent(e.target.value)}
          placeholder="发表评论..."
          rows={3}
          className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none resize-none"
        />
        <div className="flex justify-end mt-2">
          <button
            onClick={handleSubmit}
            disabled={!content.trim()}
            className="flex items-center gap-1 px-4 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50"
          >
            <Send className="w-3 h-3" />发表
          </button>
        </div>
      </div>

      {/* 评论列表 */}
      {comments.length === 0 ? (
        <p className="text-sm text-ink-500 text-center py-8">暂无评论</p>
      ) : (
        <div className="space-y-3">
          {comments.map(comment => (
            <div key={comment.id} className="glass-dark rounded-lg p-4 border border-tech-500/10">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs text-ink-300">{comment.createdByName || '匿名用户'}</span>
                <div className="flex items-center gap-2">
                  <span className="text-[10px] text-ink-500">
                    {new Date(comment.createdAt).toLocaleString('zh-CN')}
                  </span>
                  <button onClick={() => onDelete(comment.id)} className="text-[10px] text-cinnabar-400 hover:text-cinnabar-300">
                    删除
                  </button>
                </div>
              </div>
              <p className="text-sm text-ink-200">{comment.content}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
