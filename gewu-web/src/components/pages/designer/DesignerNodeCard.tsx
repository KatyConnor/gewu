'use client';
// 编排设计器 - 自定义画布节点卡片（React Flow 自定义节点）。
// 图标随节点类型区分，展示配置摘要与缺失警示角标；SUPERVISOR 模式下首个 AGENT 节点带"监督者"徽标。
import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import {
  Bot, Wrench, UserCheck, Split, GitMerge, ListTree, Shuffle, Boxes,
  AlertTriangle, Crown, Loader2, CheckCircle, XCircle,
} from 'lucide-react';
import { NODE_CATALOG, type DesignerNodeData, type NodeRunState, type OrchNodeType } from '@/lib/orchestrationDesigner';

type OrchestrationNode = Node<DesignerNodeData, 'orchestration'>;

const TYPE_ICONS: Record<OrchNodeType, typeof Bot> = {
  AGENT: Bot,
  TOOL: Wrench,
  HUMAN: UserCheck,
  ROUTER: Split,
  PARALLEL: Shuffle,
  MERGE: GitMerge,
  PLAN: ListTree,
  SUBGRAPH: Boxes,
};

/** 运行态着色（SSE 事件驱动） */
const RUN_STATE_STYLE: Record<NodeRunState, { border: string; icon: typeof Loader2; label: string; spin?: boolean }> = {
  RUNNING: { border: 'border-tech-400', icon: Loader2, label: '运行中', spin: true },
  SUCCEEDED: { border: 'border-green-500/70', icon: CheckCircle, label: '已完成' },
  FAILED: { border: 'border-cinnabar-400', icon: XCircle, label: '失败' },
  APPROVAL: { border: 'border-gold-400', icon: UserCheck, label: '待审批' },
};

function nodeSubtitle(def: DesignerNodeData['def']): string {
  if (def.type === 'AGENT') {
    const parts = [def.roleCode, def.refId].filter(Boolean);
    return parts.length > 0 ? parts.join(' · ') : '未指定 Agent';
  }
  if (def.type === 'TOOL') {
    const toolName = def.config?.toolName;
    return toolName ? String(toolName) : '未配置工具';
  }
  if (def.type === 'HUMAN') {
    const timeout = def.config?.timeoutSeconds;
    return timeout != null ? `审批超时 ${timeout}s` : '审批超时默认 1800s';
  }
  return NODE_CATALOG[def.type ?? 'AGENT']?.description ?? '';
}

function DesignerNodeCardInner({ data, selected }: NodeProps<OrchestrationNode>) {
  const nodeType: OrchNodeType = data.def.type ?? 'AGENT';
  const icon = TYPE_ICONS[nodeType] ?? Bot;
  const Icon = icon;
  const catalog = NODE_CATALOG[nodeType];
  const subtitle = nodeSubtitle(data.def);
  const missingConfig =
    (nodeType === 'AGENT' && !data.def.refId && !data.def.roleCode) ||
    (nodeType === 'TOOL' && !String(data.def.config?.toolName ?? '').trim());
  const stateStyle = data.state ? RUN_STATE_STYLE[data.state] : null;
  const StateIcon = stateStyle?.icon;

  return (
    <div
      className={`w-44 rounded-lg border bg-ink-900/95 px-3 py-2.5 shadow-md transition-colors ${
        selected
          ? 'border-tech-400 ring-1 ring-tech-400/40'
          : stateStyle ? stateStyle.border : 'border-tech-500/20'
      }`}
    >
      <Handle type="target" position={Position.Top} className="!h-2.5 !w-2.5 !border-2 !border-ink-900 !bg-tech-500" />
      <div className="flex items-center gap-2">
        <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded bg-tech-500/10">
          <Icon className="h-3.5 w-3.5 text-tech-400" />
        </span>
        <span className="truncate text-xs font-semibold text-ink-50">{catalog.label}</span>
        {StateIcon && (
          <span title={stateStyle.label} className="ml-auto flex shrink-0">
            <StateIcon className={`h-3.5 w-3.5 ${stateStyle.spin ? 'animate-spin text-tech-400' : 'text-ink-200'}`} />
          </span>
        )}
        {!StateIcon && data.isSupervisor && (
          <span className="ml-auto flex shrink-0 items-center gap-0.5 rounded-full bg-gold-500/15 px-1.5 py-0.5 text-[10px] text-gold-400">
            <Crown className="h-3 w-3" />监督者
          </span>
        )}
        {!StateIcon && missingConfig && (
          <span title="关键配置缺失" className="ml-auto flex shrink-0">
            <AlertTriangle className="h-3.5 w-3.5 text-gold-400" />
          </span>
        )}
      </div>
      <p className="mt-1 truncate font-mono text-[10px] text-ink-300">{data.def.nodeId}</p>
      <p className="truncate text-[10px] text-ink-500" title={subtitle}>{subtitle}</p>
      {!catalog.implemented && (
        <p className="mt-0.5 text-[10px] text-gold-400">引擎未实现，按 Agent 处理</p>
      )}
      <Handle type="source" position={Position.Bottom} className="!h-2.5 !w-2.5 !border-2 !border-ink-900 !bg-tech-500" />
    </div>
  );
}

const DesignerNodeCard = memo(DesignerNodeCardInner);
export default DesignerNodeCard;
