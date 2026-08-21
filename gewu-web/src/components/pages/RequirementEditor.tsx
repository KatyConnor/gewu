'use client';
import { useState, useEffect } from 'react';
import { X, Save, Send, Upload, Link } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import RelatedSelector from '@/components/ui/RelatedSelector';
import type { RequirementDTO } from '@/lib/requirement';

// ==================== 常量 ====================

const typeConfig: Record<string, { label: string; icon: string }> = {
  STORY: { label: '用户故事', icon: '📖' },
  TASK: { label: '任务', icon: '✅' },
  BUG: { label: '缺陷', icon: '🐛' },
  IMPROVEMENT: { label: '改进', icon: '⬆️' },
  FEATURE: { label: '功能需求', icon: '✨' },
  EPIC: { label: '史诗', icon: '🏔️' },
};

const priorityConfig: Record<number, { label: string }> = {
  0: { label: 'P0 紧急' },
  1: { label: 'P1 高' },
  2: { label: 'P2 中' },
  3: { label: 'P3 低' },
};

// ==================== 组件 Props ====================

interface RequirementEditorProps {
  requirement?: RequirementDTO; // 编辑时传入，创建时为空
  onClose: () => void;
  onSubmit: (data: Record<string, unknown>) => Promise<void>;
}

// ==================== 主组件 ====================

export default function RequirementEditor({ requirement, onClose, onSubmit }: RequirementEditorProps) {
  const toast = useToast();

  // 表单字段
  const [title, setTitle] = useState(requirement?.title || '');
  const [description, setDescription] = useState(requirement?.description || '');
  const [type, setType] = useState(requirement?.type || 'STORY');
  const [priority, setPriority] = useState(requirement?.priority ?? 2);
  const [storyPoint, setStoryPoint] = useState(requirement?.storyPoint || 0);
  const [estimatedHours, setEstimatedHours] = useState(requirement?.estimatedHours || 0);
  const [dueDate, setDueDate] = useState(requirement?.dueDate ? new Date(requirement.dueDate).toISOString().split('T')[0] : '');

  // 关联选择
  const [projectId, setProjectId] = useState(requirement?.projectId || '');
  const [sessionIds, setSessionIds] = useState(requirement?.sessionIds || '');
  const [documentIds, setDocumentIds] = useState(requirement?.documentIds || '');

  // 预览模式
  const [showPreview, setShowPreview] = useState(false);

  // 提交状态
  const [submitting, setSubmitting] = useState(false);

  // 表单验证
  const [errors, setErrors] = useState<Record<string, string>>({});

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!title.trim()) newErrors.title = '标题不能为空';
    if (title.length > 256) newErrors.title = '标题不能超过 256 个字符';
    if (storyPoint < 0) newErrors.storyPoint = '故事点不能为负数';
    if (estimatedHours < 0) newErrors.estimatedHours = '预估工时不能为负数';
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (action: 'save' | 'submit') => {
    if (!validate()) return;
    setSubmitting(true);
    try {
      const data: Record<string, unknown> = {
        title,
        description,
        type,
        priority,
        storyPoint,
        estimatedHours,
        projectId: projectId || undefined,
        sessionIds: sessionIds || undefined,
        documentIds: documentIds || undefined,
        dueDate: dueDate ? new Date(dueDate).getTime() : undefined,
      };
      await onSubmit(data);
      toast(action === 'save' ? '需求已保存' : '需求已提交评审', 'success');
      onClose();
    } catch {
      toast('操作失败', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60" onClick={onClose}>
      <div
        className="glass-dark rounded-xl w-full max-w-4xl max-h-[90vh] flex flex-col border border-tech-500/20 shadow-2xl"
        onClick={e => e.stopPropagation()}
      >
        {/* 弹框标题 */}
        <div className="flex items-center justify-between p-4 border-b border-tech-500/10">
          <h3 className="text-lg font-semibold text-ink-50">
            {requirement ? '编辑需求' : '新建需求'}
          </h3>
          <button
            onClick={onClose}
            className="p-2 text-ink-400 hover:text-ink-200 rounded-lg hover:bg-ink-800/30"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* 弹框内容 */}
        <div className="flex-1 overflow-y-auto p-6">
          <div className="grid grid-cols-2 gap-6">
            {/* 左侧：表单字段 */}
            <div className="space-y-4">
              {/* 标题 */}
              <div>
                <label className="text-xs text-ink-400 mb-1 block">需求标题 *</label>
                <input
                  value={title}
                  onChange={e => setTitle(e.target.value)}
                  placeholder="请输入需求标题"
                  className={`w-full px-3 py-2 bg-ink-800/50 border rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none ${
                    errors.title ? 'border-danger-500/50' : 'border-tech-500/10'
                  }`}
                />
                {errors.title && <p className="text-[10px] text-danger-400 mt-1">{errors.title}</p>}
              </div>

              {/* 类型 + 优先级 */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">需求类型</label>
                  <select
                    value={type}
                    onChange={e => setType(e.target.value)}
                    className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
                  >
                    {Object.entries(typeConfig).map(([key, cfg]) => (
                      <option key={key} value={key}>
                        {cfg.icon} {cfg.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">优先级</label>
                  <select
                    value={priority}
                    onChange={e => setPriority(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
                  >
                    {Object.entries(priorityConfig).map(([key, cfg]) => (
                      <option key={key} value={key}>{cfg.label}</option>
                    ))}
                  </select>
                </div>
              </div>

              {/* 故事点 + 预估工时 */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">故事点</label>
                  <input
                    type="number"
                    value={storyPoint || ''}
                    onChange={e => setStoryPoint(Number(e.target.value))}
                    placeholder="0"
                    className={`w-full px-3 py-2 bg-ink-800/50 border rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none ${
                      errors.storyPoint ? 'border-danger-500/50' : 'border-tech-500/10'
                    }`}
                  />
                  {errors.storyPoint && <p className="text-[10px] text-danger-400 mt-1">{errors.storyPoint}</p>}
                </div>
                <div>
                  <label className="text-xs text-ink-400 mb-1 block">预估工时（小时）</label>
                  <input
                    type="number"
                    value={estimatedHours || ''}
                    onChange={e => setEstimatedHours(Number(e.target.value))}
                    placeholder="0"
                    className={`w-full px-3 py-2 bg-ink-800/50 border rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none ${
                      errors.estimatedHours ? 'border-danger-500/50' : 'border-tech-500/10'
                    }`}
                  />
                  {errors.estimatedHours && <p className="text-[10px] text-danger-400 mt-1">{errors.estimatedHours}</p>}
                </div>
              </div>

              {/* 截止日期 */}
              <div>
                <label className="text-xs text-ink-400 mb-1 block">截止日期</label>
                <input
                  type="date"
                  value={dueDate}
                  onChange={e => setDueDate(e.target.value)}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
                />
              </div>

              {/* 关联项目 */}
              <RelatedSelector
                label="关联项目"
                type="project"
                value={projectId}
                onChange={setProjectId}
                multiple={false}
              />

              {/* 关联会话 */}
              <RelatedSelector
                label="关联会话"
                type="session"
                value={sessionIds}
                onChange={setSessionIds}
              />

              {/* 关联文档 */}
              <RelatedSelector
                label="关联文档"
                type="document"
                value={documentIds}
                onChange={setDocumentIds}
              />
            </div>

            {/* 右侧：Markdown 描述编辑器 */}
            <div className="flex flex-col">
              <div className="flex items-center justify-between mb-1">
                <label className="text-xs text-ink-400">需求描述（Markdown）</label>
                <button
                  onClick={() => setShowPreview(!showPreview)}
                  className="text-[10px] text-tech-400 hover:text-tech-300"
                >
                  {showPreview ? '编辑' : '预览'}
                </button>
              </div>
              {showPreview ? (
                <div className="flex-1 min-h-[300px] p-4 bg-ink-800/30 rounded-lg border border-tech-500/10 overflow-y-auto">
                  {description ? (
                    <MarkdownRenderer content={description} />
                  ) : (
                    <p className="text-sm text-ink-500">暂无描述</p>
                  )}
                </div>
              ) : (
                <textarea
                  value={description}
                  onChange={e => setDescription(e.target.value)}
                  placeholder="请输入需求描述，支持 Markdown 格式..."
                  className="flex-1 min-h-[300px] px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none resize-none font-mono"
                />
              )}
              {/* Markdown 工具栏 */}
              {!showPreview && (
                <div className="flex items-center gap-2 mt-2">
                  <button
                    onClick={() => setDescription(prev => prev + '**粗体**')}
                    className="px-2 py-1 text-xs text-ink-400 hover:text-ink-200 bg-ink-800/50 rounded"
                  >
                    <b>B</b>
                  </button>
                  <button
                    onClick={() => setDescription(prev => prev + '*斜体*')}
                    className="px-2 py-1 text-xs text-ink-400 hover:text-ink-200 bg-ink-800/50 rounded"
                  >
                    <i>I</i>
                  </button>
                  <button
                    onClick={() => setDescription(prev => prev + '## 标题')}
                    className="px-2 py-1 text-xs text-ink-400 hover:text-ink-200 bg-ink-800/50 rounded"
                  >
                    H2
                  </button>
                  <button
                    onClick={() => setDescription(prev => prev + '- 列表项')}
                    className="px-2 py-1 text-xs text-ink-400 hover:text-ink-200 bg-ink-800/50 rounded"
                  >
                    • 列表
                  </button>
                  <button
                    onClick={() => setDescription(prev => prev + '```\n代码块\n```')}
                    className="px-2 py-1 text-xs text-ink-400 hover:text-ink-200 bg-ink-800/50 rounded"
                  >
                    &lt;/&gt;
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* 弹框底部按钮 */}
        <div className="flex items-center justify-between p-4 border-t border-tech-500/10">
          <div className="flex items-center gap-2">
            <button
              onClick={() => navigator.clipboard.writeText(description)}
              className="flex items-center gap-1 px-3 py-2 text-xs text-ink-400 hover:text-ink-200 bg-ink-800/50 rounded-lg"
            >
              <Upload className="w-3 h-3" />复制描述
            </button>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200"
            >
              取消
            </button>
            <button
              onClick={() => handleSubmit('save')}
              disabled={submitting || !title.trim()}
              className="flex items-center gap-1 px-4 py-2 text-sm text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 disabled:opacity-50"
            >
              <Save className="w-4 h-4" />保存草稿
            </button>
            {!requirement && (
              <button
                onClick={() => handleSubmit('submit')}
                disabled={submitting || !title.trim()}
                className="flex items-center gap-1 px-4 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50"
              >
                <Send className="w-4 h-4" />提交评审
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
