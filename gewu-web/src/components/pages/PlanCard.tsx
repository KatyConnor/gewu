'use client';
import { useState } from 'react';
import {
  CheckCircle2, ChevronDown, Circle, CircleDot, ListChecks, Loader2,
} from 'lucide-react';
import type { PlanStepInfo } from '@/lib/chat';

/**
 * 任务流程卡片（S9 F5，zcode 风格）。
 * 会话区域右上角浮动展示：折叠态为「标题 · 进程 n/m」小卡，展开态为
 * 步骤清单（完成=绿色勾、进行中=旋转指示、待办=空心圆）。
 * 数据来自模型经内置 plan_task 工具提交的任务清单（plan_created/plan_updated 事件）。
 */
export default function PlanCard({ title, steps, streaming, planPath, onViewFullPlan }: {
  title: string;
  steps: PlanStepInfo[];
  streaming: boolean;
  /** 计划文件路径（plan_task markdown 参数落盘于工作空间 plan/ 目录） */
  planPath?: string;
  /** 「查看完整计划」→ 右侧面板 Markdown 预览 */
  onViewFullPlan?: (path: string) => void;
}) {
  const [expanded, setExpanded] = useState(true);
  if (steps.length === 0) return null;
  const doneCount = steps.filter(s => s.status === 'done').length;

  return (
    <div className="absolute right-4 top-16 z-20 w-72 rounded-lg border border-tech-500/20 bg-ink-900/95 backdrop-blur-sm shadow-lg shadow-black/30 overflow-hidden">
      {/* 头部：标题 + 进程 n/m + 折叠开关 */}
      <button
        onClick={() => setExpanded(prev => !prev)}
        className="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-tech-500/5 transition-colors"
        aria-expanded={expanded}
        aria-label={expanded ? '折叠任务清单' : '展开任务清单'}
      >
        <ListChecks className="w-3.5 h-3.5 text-tech-400 flex-shrink-0" />
        <span className="text-xs font-medium text-ink-200 truncate flex-1" title={title}>
          {title || '任务计划'}
        </span>
        <span className={`text-[10px] flex-shrink-0 ${doneCount === steps.length ? 'text-green-400' : 'text-tech-400'}`}>
          进程 {doneCount}/{steps.length}
        </span>
        <ChevronDown className={`w-3.5 h-3.5 text-ink-500 flex-shrink-0 transition-transform ${expanded ? '' : '-rotate-90'}`} />
      </button>

      {/* 展开态：步骤清单 */}
      {expanded && (
        <div className="px-3 pb-2 pt-0.5 max-h-64 overflow-y-auto scrollbar-thin border-t border-tech-500/10">
          {steps.map(step => (
            <div key={step.id} className="flex items-start gap-2 py-1.5">
              <span className="flex-shrink-0 mt-0.5">
                {step.status === 'done' ? (
                  <CheckCircle2 className="w-3.5 h-3.5 text-green-400/90" />
                ) : step.status === 'in_progress' ? (
                  streaming
                    ? <Loader2 className="w-3.5 h-3.5 text-tech-400 animate-spin" />
                    : <CircleDot className="w-3.5 h-3.5 text-tech-400" />
                ) : (
                  <Circle className="w-3.5 h-3.5 text-ink-600" />
                )}
              </span>
              <p className={`text-xs leading-relaxed ${
                step.status === 'done' ? 'text-ink-500 line-through' :
                step.status === 'in_progress' ? 'text-ink-200' : 'text-ink-400'
              }`}>{step.text}</p>
            </div>
          ))}
          {/* 查看完整计划：右侧面板以 Markdown 预览模式展示 plan/ 目录下的计划文件 */}
          {planPath && onViewFullPlan && (
            <button
              onClick={() => onViewFullPlan(planPath)}
              className="mt-1.5 mb-1 w-full flex items-center justify-center gap-1.5 px-2 py-1.5 text-[11px] rounded-md bg-tech-500/10 border border-tech-500/25 text-tech-300 hover:bg-tech-500/20 transition-all"
              title="在右侧面板预览计划文件（Markdown）"
            >
              查看完整计划 →
            </button>
          )}
        </div>
      )}
    </div>
  );
}
