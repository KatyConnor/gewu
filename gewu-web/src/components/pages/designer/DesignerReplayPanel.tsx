'use client';
// 编排设计器 - 执行回放面板（docs/design/46 FR-14）：
// 选择一次历史执行，按 orchestration_node_execution 记录为画布节点着色并展示耗时/错误。
import { History, X } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import type { OrchestrationExecutionEntity, OrchestrationNodeExecution } from '@/lib/orchestration';

interface Props {
  executions: OrchestrationExecutionEntity[];
  selectedExecutionId: string;
  nodes: OrchestrationNodeExecution[];
  active: boolean;
  onSelectExecution: (executionId: string) => void;
  onLoad: () => void;
  onClear: () => void;
  onClose: () => void;
}

function replayState(status?: string): string {
  if (status === 'SUCCEEDED') return '完成';
  if (status === 'FAILED') return '失败';
  if (status === 'RUNNING') return '运行中（未收尾）';
  return status || '-';
}

export default function DesignerReplayPanel({
  executions, selectedExecutionId, nodes, active,
  onSelectExecution, onLoad, onClear, onClose,
}: Props) {
  return (
    <div className="flex h-64 shrink-0 flex-col border-t border-tech-500/10 bg-ink-900/60">
      <div className="flex items-center gap-2 px-3 py-2">
        <History className="h-4 w-4 text-tech-400" />
        <p className="text-xs font-semibold text-ink-200">执行回放</p>
        {active && <span className="text-[10px] text-tech-400">画布已按历史执行着色</span>}
        <div className="ml-auto flex items-center gap-2">
          <button onClick={onLoad} disabled={!selectedExecutionId}
            className="px-3 py-1.5 text-xs text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 transition-colors disabled:opacity-40">
            加载到画布
          </button>
          {active && (
            <button onClick={onClear}
              className="px-3 py-1.5 text-xs text-ink-300 border border-tech-500/20 rounded-lg hover:bg-ink-800 transition-colors">
              清除着色
            </button>
          )}
          <button onClick={onClose} className="px-2 text-ink-500 hover:text-ink-300" aria-label="关闭回放">✕</button>
        </div>
      </div>
      <div className="px-3 pb-2">
        <CustomSelect value={selectedExecutionId} onChange={onSelectExecution}
          options={executions.length > 0
            ? executions.map(e => ({
                value: e.id,
                label: `${e.id.slice(0, 12)}… · ${e.status} · ${e.createdAt ? new Date(e.createdAt > 1e12 ? e.createdAt : e.createdAt * 1000).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''}`,
              }))
            : [{ value: '', label: '暂无执行记录' }]} />
      </div>
      {nodes.length > 0 && (
        <div className="min-h-0 flex-1 overflow-y-auto scrollbar-thin px-3 pb-3 text-xs">
          <div className="grid grid-cols-2 gap-2 md:grid-cols-3">
            {nodes.map(node => (
              <div key={node.id ?? node.nodeId}
                className={`rounded-lg border p-2 ${
                  node.status === 'SUCCEEDED' ? 'border-green-500/40' :
                  node.status === 'FAILED' ? 'border-cinnabar-500/40' : 'border-tech-500/20'}`}>
                <p className="font-mono text-[11px] text-ink-100">{node.nodeId}</p>
                <p className="text-[10px] text-ink-400">{replayState(node.status)}
                  {node.durationMs != null ? ` · ${(node.durationMs / 1000).toFixed(1)}s` : ''}</p>
                {node.errorMessage && (
                  <p className="mt-1 text-[10px] text-cinnabar-400">{node.errorMessage}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
      {nodes.length === 0 && selectedExecutionId && (
        <p className="px-3 pb-3 text-xs text-ink-500">该执行没有节点级记录（早于落库功能的历史执行）。</p>
      )}
    </div>
  );
}
