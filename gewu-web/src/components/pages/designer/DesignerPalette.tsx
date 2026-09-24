'use client';
// 编排设计器 - 左侧节点库：按类型拖入画布，未实现类型禁用。
import { Boxes, Bot, Wrench, UserCheck, Split, Shuffle, GitMerge, ListTree } from 'lucide-react';
import { NODE_CATALOG, type OrchNodeType } from '@/lib/orchestrationDesigner';
import type { ModeOption } from '@/lib/orchestrationDesigner';

const PALETTE_ICONS: Record<OrchNodeType, typeof Bot> = {
  AGENT: Bot,
  TOOL: Wrench,
  HUMAN: UserCheck,
  ROUTER: Split,
  PARALLEL: Shuffle,
  MERGE: GitMerge,
  PLAN: ListTree,
  SUBGRAPH: Boxes,
};

const NODE_TYPE_ORDER: OrchNodeType[] = [
  'AGENT', 'TOOL', 'HUMAN', 'ROUTER', 'PARALLEL', 'MERGE', 'PLAN', 'SUBGRAPH',
];

export const NODE_DND_MIME = 'application/x-orchestration-node';

export default function DesignerPalette({ mode }: { mode: ModeOption }) {
  const onDragStart = (event: React.DragEvent, nodeType: OrchNodeType) => {
    event.dataTransfer.setData(NODE_DND_MIME, nodeType);
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <aside className="flex w-48 shrink-0 flex-col gap-2 overflow-y-auto scrollbar-thin border-r border-tech-500/10 bg-ink-900/40 p-3">
      <p className="text-xs font-semibold text-ink-200">节点库</p>
      <p className="rounded-lg bg-ink-800/60 p-2 text-[10px] leading-relaxed text-ink-400">
        拖拽节点到画布；连线从节点底部端口拖到目标节点顶部端口。
        Shift + 左键拖拽可框选节点；Delete / Backspace 删除选中；滚轮缩放，拖拽空白平移。
      </p>
      {!mode.edgesMatter && (
        <p className="rounded-lg bg-gold-500/10 p-2 text-[10px] leading-relaxed text-gold-400">
          {mode.hint}
        </p>
      )}
      {NODE_TYPE_ORDER.map(nodeType => {
        const item = NODE_CATALOG[nodeType];
        const Icon = PALETTE_ICONS[nodeType];
        return (
          <div
            key={nodeType}
            draggable={item.implemented}
            onDragStart={e => onDragStart(e, nodeType)}
            className={`rounded-lg border border-tech-500/10 bg-ink-800/50 p-2.5 ${
              item.implemented
                ? 'cursor-grab hover:border-tech-500/40 active:cursor-grabbing'
                : 'cursor-not-allowed opacity-50'
            }`}
            title={item.description}
          >
            <div className="flex items-center gap-2">
              <Icon className="h-3.5 w-3.5 text-tech-400" />
              <span className="text-xs font-medium text-ink-100">{item.label}</span>
              {!item.implemented && <span className="ml-auto text-[10px] text-ink-500">未实现</span>}
            </div>
          </div>
        );
      })}
    </aside>
  );
}
