'use client';
import { useState } from 'react';
import {
  DndContext,
  DragEndEvent,
  DragOverlay,
  DragStartEvent,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Clock, User, Calendar } from 'lucide-react';
import type { RequirementDTO } from '@/lib/requirement';

// ==================== 常量 ====================

const boardColumns: { id: string; label: string; color: string }[] = [
  { id: 'DRAFT', label: '草稿', color: 'border-ink-500/30' },
  { id: 'PENDING_REVIEW', label: '待评审', color: 'border-gold-500/30' },
  { id: 'IN_REVIEW', label: '评审中', color: 'border-tech-500/30' },
  { id: 'APPROVED', label: '已通过', color: 'border-green-500/30' },
  { id: 'IN_DEV', label: '开发中', color: 'border-cyber-500/30' },
  { id: 'TESTING', label: '测试中', color: 'border-purple-500/30' },
  { id: 'RELEASED', label: '已上线', color: 'border-green-500/30' },
];

const priorityConfig: Record<number, { label: string; color: string }> = {
  0: { label: 'P0', color: 'bg-danger-500/20 text-danger-400' },
  1: { label: 'P1', color: 'bg-gold-500/20 text-gold-400' },
  2: { label: 'P2', color: 'bg-tech-500/20 text-tech-400' },
  3: { label: 'P3', color: 'bg-ink-500/20 text-ink-400' },
};

const typeConfig: Record<string, { label: string; icon: string }> = {
  STORY: { label: '故事', icon: '' },
  TASK: { label: '任务', icon: '✅' },
  BUG: { label: '缺陷', icon: '' },
  IMPROVEMENT: { label: '改进', icon: '⬆️' },
  FEATURE: { label: '功能', icon: '✨' },
  EPIC: { label: '史诗', icon: '🏔️' },
};

// ==================== 组件 Props ====================

interface RequirementBoardProps {
  requirements: RequirementDTO[];
  onStatusChange: (requirementId: string, newStatus: string) => void;
  onRequirementClick: (requirementId: string) => void;
}

// ==================== 可排序卡片组件 ====================

function SortableRequirementCard({ requirement, isDragging }: { requirement: RequirementDTO; isDragging?: boolean }) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging: isSortableDragging,
  } = useSortable({ id: requirement.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  const priorityCfg = priorityConfig[requirement.priority] || priorityConfig[2];
  const typeCfg = typeConfig[requirement.type] || typeConfig.STORY;

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      className={`glass-dark rounded-lg p-3 border border-tech-500/10 hover:border-tech-500/20 transition-all cursor-pointer ${
        isDragging || isSortableDragging ? 'opacity-50 shadow-lg scale-105' : ''
      }`}
      onClick={(e) => {
        e.stopPropagation();
        if (!isDragging && !isSortableDragging) {
          // 需要通过父组件处理点击
        }
      }}
    >
      {/* 拖拽手柄 + 优先级 */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-1.5">
          <button {...listeners} className="p-1 text-ink-600 hover:text-ink-400 cursor-grab active:cursor-grabbing">
            <GripVertical className="w-3 h-3" />
          </button>
          <span className={`text-[10px] px-1.5 py-0.5 rounded ${priorityCfg.color}`}>{priorityCfg.label}</span>
        </div>
        <span className="text-xs">{typeCfg.icon}</span>
      </div>

      {/* 标题 */}
      <p className="text-xs text-ink-100 font-medium line-clamp-2 mb-2">{requirement.title}</p>

      {/* 编号 */}
      <p className="text-[10px] text-ink-500 mb-2">{requirement.requirementCode}</p>

      {/* 负责人 + 截止日期 */}
      <div className="flex items-center justify-between text-[10px] text-ink-500">
        {requirement.assigneeName && (
          <span className="flex items-center gap-1">
            <User className="w-3 h-3" />
            {requirement.assigneeName}
          </span>
        )}
        {requirement.dueDate && (
          <span className="flex items-center gap-1">
            <Calendar className="w-3 h-3" />
            {new Date(requirement.dueDate).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })}
          </span>
        )}
      </div>
    </div>
  );
}

// ==================== 看板列组件 ====================

function BoardColumn({
  column,
  requirements,
  onRequirementClick,
  onDrop,
}: {
  column: typeof boardColumns[0];
  requirements: RequirementDTO[];
  onRequirementClick: (id: string) => void;
  onDrop: (requirementId: string, newStatus: string) => void;
}) {
  const [draggingId, setDraggingId] = useState<string | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragStart = (event: DragStartEvent) => {
    setDraggingId(event.active.id as string);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    setDraggingId(null);
    const { active, over } = event;
    if (over && active.id !== over.id) {
      // 如果拖拽到不同的列，更新状态
      const targetColumnId = (over.data.current?.sortable?.containerId as string) || column.id;
      if (targetColumnId !== column.id) {
        onDrop(active.id as string, targetColumnId);
      }
    }
  };

  return (
    <div className="flex-1 min-w-[280px]">
      {/* 列标题 */}
      <div className={`flex items-center justify-between mb-3 p-2 rounded-lg border-t-2 ${column.color} bg-ink-800/30`}>
        <h3 className="text-xs font-medium text-ink-200">{column.label}</h3>
        <span className="text-[10px] text-ink-500 bg-ink-700/50 px-1.5 py-0.5 rounded">
          {requirements.length}
        </span>
      </div>

      {/* 任务列表 */}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragStart={handleDragStart} onDragEnd={handleDragEnd}>
        <SortableContext items={requirements.map(r => r.id)} strategy={verticalListSortingStrategy}>
          <div className="space-y-2 min-h-[200px] p-2 rounded-lg bg-ink-900/30" data-column-id={column.id}>
            {requirements.map(req => (
              <div key={req.id} onClick={() => onRequirementClick(req.id)}>
                <SortableRequirementCard requirement={req} isDragging={draggingId === req.id} />
              </div>
            ))}
          </div>
        </SortableContext>
        <DragOverlay>
          {draggingId ? (
            <div className="glass-dark rounded-lg p-3 border border-tech-500/20 shadow-xl scale-105">
              <p className="text-xs text-ink-100">拖拽中...</p>
            </div>
          ) : null}
        </DragOverlay>
      </DndContext>
    </div>
  );
}

// ==================== 主组件 ====================

export default function RequirementBoard({ requirements, onStatusChange, onRequirementClick }: RequirementBoardProps) {
  // 按状态分组
  const groupedRequirements = requirements.reduce((acc, req) => {
    const status = req.status || 'DRAFT';
    if (!acc[status]) acc[status] = [];
    acc[status].push(req);
    return acc;
  }, {} as Record<string, RequirementDTO[]>);

  const handleDrop = (requirementId: string, newStatus: string) => {
    onStatusChange(requirementId, newStatus);
  };

  return (
    <div className="flex gap-4 overflow-x-auto pb-4">
      {boardColumns.map(column => (
        <BoardColumn
          key={column.id}
          column={column}
          requirements={groupedRequirements[column.id] || []}
          onRequirementClick={onRequirementClick}
          onDrop={handleDrop}
        />
      ))}
    </div>
  );
}
