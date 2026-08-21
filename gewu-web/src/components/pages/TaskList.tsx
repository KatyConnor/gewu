'use client';
import { useState } from 'react';
import { Plus, Edit3, Trash2, CheckCircle, Clock, PlayCircle, User } from 'lucide-react';
import type { RequirementTaskDTO } from '@/lib/requirement';

// ==================== 常量 ====================

const taskStatusConfig: Record<string, { label: string; color: string; icon: React.ReactNode; nextStatus?: string; nextLabel?: string }> = {
  PENDING: {
    label: '待处理',
    color: 'bg-ink-500/10 text-ink-400 border-ink-500/20',
    icon: <Clock className="w-3 h-3" />,
    nextStatus: 'IN_PROGRESS',
    nextLabel: '开始',
  },
  IN_PROGRESS: {
    label: '进行中',
    color: 'bg-tech-500/10 text-tech-400 border-tech-500/20',
    icon: <PlayCircle className="w-3 h-3" />,
    nextStatus: 'COMPLETED',
    nextLabel: '完成',
  },
  COMPLETED: {
    label: '已完成',
    color: 'bg-green-500/10 text-green-400 border-green-500/20',
    icon: <CheckCircle className="w-3 h-3" />,
  },
};

// ==================== 组件 Props ====================

interface TaskListProps {
  tasks: RequirementTaskDTO[];
  onAddTask: () => void;
  onUpdateTask: (taskId: string, data: Record<string, unknown>) => void;
  onDeleteTask: (taskId: string) => void;
  editable?: boolean; // 是否可编辑
}

// ==================== 主组件 ====================

export default function TaskList({ tasks, onAddTask, onUpdateTask, onDeleteTask, editable = true }: TaskListProps) {
  const [editingTaskId, setEditingTaskId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editEstimatedHours, setEditEstimatedHours] = useState(0);
  const [editActualHours, setEditActualHours] = useState(0);

  // 按状态分组任务
  const groupedTasks = tasks.reduce((acc, task) => {
    const status = task.status || 'PENDING';
    if (!acc[status]) acc[status] = [];
    acc[status].push(task);
    return acc;
  }, {} as Record<string, RequirementTaskDTO[]>);

  // 状态顺序
  const statusOrder = ['PENDING', 'IN_PROGRESS', 'COMPLETED'];

  // 统计信息
  const totalTasks = tasks.length;
  const completedTasks = tasks.filter(t => t.status === 'COMPLETED').length;
  const totalEstimatedHours = tasks.reduce((sum, t) => sum + (t.estimatedHours || 0), 0);
  const totalActualHours = tasks.reduce((sum, t) => sum + (t.actualHours || 0), 0);

  const handleStartEdit = (task: RequirementTaskDTO) => {
    setEditingTaskId(task.id);
    setEditTitle(task.title);
    setEditEstimatedHours(task.estimatedHours || 0);
    setEditActualHours(task.actualHours || 0);
  };

  const handleSaveEdit = (taskId: string) => {
    onUpdateTask(taskId, {
      title: editTitle,
      estimatedHours: editEstimatedHours,
      actualHours: editActualHours,
    });
    setEditingTaskId(null);
  };

  const handleCancelEdit = () => {
    setEditingTaskId(null);
  };

  const handleStatusChange = (task: RequirementTaskDTO) => {
    const currentConfig = taskStatusConfig[task.status || 'PENDING'];
    if (currentConfig.nextStatus) {
      onUpdateTask(task.id, {
        status: currentConfig.nextStatus,
        ...(currentConfig.nextStatus === 'COMPLETED' && !task.actualHours
          ? { actualHours: task.estimatedHours || 0 }
          : {}),
      });
    }
  };

  return (
    <div className="space-y-6">
      {/* 统计卡片 */}
      <div className="grid grid-cols-4 gap-3">
        <div className="glass-dark rounded-lg p-3 border border-tech-500/10">
          <p className="text-[10px] text-ink-500 uppercase mb-1">总任务</p>
          <p className="text-xl font-bold text-ink-200">{totalTasks}</p>
        </div>
        <div className="glass-dark rounded-lg p-3 border border-tech-500/10">
          <p className="text-[10px] text-ink-500 uppercase mb-1">已完成</p>
          <p className="text-xl font-bold text-green-400">{completedTasks}</p>
          {totalTasks > 0 && (
            <p className="text-[10px] text-ink-500 mt-1">
              完成率 {Math.round((completedTasks / totalTasks) * 100)}%
            </p>
          )}
        </div>
        <div className="glass-dark rounded-lg p-3 border border-tech-500/10">
          <p className="text-[10px] text-ink-500 uppercase mb-1">预估工时</p>
          <p className="text-xl font-bold text-tech-400">{totalEstimatedHours}h</p>
        </div>
        <div className="glass-dark rounded-lg p-3 border border-tech-500/10">
          <p className="text-[10px] text-ink-500 uppercase mb-1">实际工时</p>
          <p className="text-xl font-bold text-gold-400">{totalActualHours}h</p>
        </div>
      </div>

      {/* 任务列表 */}
      <div className="space-y-4">
        {statusOrder.map(status => {
          const statusTasks = groupedTasks[status] || [];
          const statusConfig = taskStatusConfig[status];
          if (!statusConfig || statusTasks.length === 0) return null;

          return (
            <div key={status}>
              <div className="flex items-center gap-2 mb-3">
                <span className={`inline-flex items-center gap-1 text-xs px-2 py-1 rounded border ${statusConfig.color}`}>
                  {statusConfig.icon}
                  {statusConfig.label}
                </span>
                <span className="text-xs text-ink-500">({statusTasks.length})</span>
              </div>

              <div className="space-y-2">
                {statusTasks.map(task => {
                  const isEditing = editingTaskId === task.id;

                  return (
                    <div
                      key={task.id}
                      className="glass-dark rounded-lg p-4 border border-tech-500/10 hover:border-tech-500/20 transition-all"
                    >
                      {isEditing ? (
                        /* 编辑模式 */
                        <div className="space-y-3">
                          <input
                            value={editTitle}
                            onChange={e => setEditTitle(e.target.value)}
                            className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
                          />
                          <div className="grid grid-cols-2 gap-3">
                            <div>
                              <label className="text-[10px] text-ink-500 mb-1 block">预估工时</label>
                              <input
                                type="number"
                                value={editEstimatedHours || ''}
                                onChange={e => setEditEstimatedHours(Number(e.target.value))}
                                className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
                              />
                            </div>
                            <div>
                              <label className="text-[10px] text-ink-500 mb-1 block">实际工时</label>
                              <input
                                type="number"
                                value={editActualHours || ''}
                                onChange={e => setEditActualHours(Number(e.target.value))}
                                className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none"
                              />
                            </div>
                          </div>
                          <div className="flex items-center gap-2 justify-end">
                            <button
                              onClick={handleCancelEdit}
                              className="px-3 py-1.5 text-xs text-ink-400 hover:text-ink-200"
                            >
                              取消
                            </button>
                            <button
                              onClick={() => handleSaveEdit(task.id)}
                              className="px-3 py-1.5 text-xs btn-primary text-white rounded-lg"
                            >
                              保存
                            </button>
                          </div>
                        </div>
                      ) : (
                        /* 显示模式 */
                        <div className="flex items-start justify-between">
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 mb-1">
                              <p className="text-sm text-ink-100 font-medium truncate">{task.title}</p>
                              <span className="text-[10px] text-ink-500 flex-shrink-0">{task.taskCode}</span>
                            </div>
                            {task.description && (
                              <p className="text-xs text-ink-400 line-clamp-2 mt-1">{task.description}</p>
                            )}
                            <div className="flex items-center gap-4 mt-2 text-[10px] text-ink-500">
                              {task.assigneeName && (
                                <span className="flex items-center gap-1">
                                  <User className="w-3 h-3" />
                                  {task.assigneeName}
                                </span>
                              )}
                              {task.estimatedHours !== undefined && task.estimatedHours > 0 && (
                                <span>预估：{task.estimatedHours}h</span>
                              )}
                              {task.actualHours !== undefined && task.actualHours > 0 && (
                                <span>实际：{task.actualHours}h</span>
                              )}
                              {task.completedAt && (
                                <span>
                                  完成：{new Date(task.completedAt).toLocaleDateString('zh-CN')}
                                </span>
                              )}
                            </div>
                          </div>

                          {/* 操作按钮 */}
                          {editable && (
                            <div className="flex items-center gap-1 ml-4 flex-shrink-0">
                              {statusConfig.nextStatus && (
                                <button
                                  onClick={() => handleStatusChange(task)}
                                  className="flex items-center gap-1 px-2 py-1 text-[10px] text-tech-400 hover:bg-tech-500/10 rounded"
                                >
                                  <PlayCircle className="w-3 h-3" />
                                  {statusConfig.nextLabel}
                                </button>
                              )}
                              <button
                                onClick={() => handleStartEdit(task)}
                                className="p-1 text-ink-400 hover:text-gold-400 hover:bg-gold-500/10 rounded"
                              >
                                <Edit3 className="w-3 h-3" />
                              </button>
                              <button
                                onClick={() => {
                                  if (confirm('确定删除此任务？')) onDeleteTask(task.id);
                                }}
                                className="p-1 text-ink-400 hover:text-cinnabar-400 hover:bg-cinnabar-500/10 rounded"
                              >
                                <Trash2 className="w-3 h-3" />
                              </button>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}

        {/* 空状态 */}
        {tasks.length === 0 && (
          <div className="text-center py-8">
            <Clock className="w-8 h-8 text-ink-600 mx-auto mb-2" />
            <p className="text-sm text-ink-500">暂无任务</p>
            {editable && (
              <p className="text-xs text-ink-600 mt-1">点击上方按钮创建第一个任务</p>
            )}
          </div>
        )}
      </div>

      {/* 添加任务按钮 */}
      {editable && (
        <button
          onClick={onAddTask}
          className="w-full flex items-center justify-center gap-2 p-3 rounded-lg border border-dashed border-tech-500/20 text-tech-400 hover:border-tech-500/40 hover:bg-tech-500/5 transition-all"
        >
          <Plus className="w-4 h-4" />
          <span className="text-sm">添加任务</span>
        </button>
      )}
    </div>
  );
}
