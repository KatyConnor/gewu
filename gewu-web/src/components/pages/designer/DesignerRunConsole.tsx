'use client';
// 编排设计器 - 运行控制台（FR-09）：输入、SSE 事件日志、审批锚点提示与最终输出。
// 事件按 nodeId 归因到画布节点高亮，本组件只负责展示与启停控制。
import { Play, Square, Terminal, X, UserCheck } from 'lucide-react';
import type { OrchestrationRunEvent } from '@/lib/orchestrationDesigner';

interface Props {
  running: boolean;
  input: string;
  events: OrchestrationRunEvent[];
  result: string | null;
  approvalNodeId: string | null;
  editable: boolean;
  onInputChange: (value: string) => void;
  onStart: () => void;
  onStop: () => void;
  onClose: () => void;
}

export default function DesignerRunConsole({
  running, input, events, result, approvalNodeId, editable,
  onInputChange, onStart, onStop, onClose,
}: Props) {
  return (
    <div className="flex h-64 shrink-0 flex-col border-t border-tech-500/10 bg-ink-900/60">
      <div className="flex items-center gap-2 px-3 py-2">
        <Terminal className="h-4 w-4 text-tech-400" />
        <p className="text-xs font-semibold text-ink-200">运行预览</p>
        {running && <span className="text-[10px] text-tech-400">执行中…</span>}
        {!editable && <span className="text-[10px] text-ink-500">已激活编排图：运行已保存版本</span>}
        <div className="ml-auto flex items-center gap-2">
          {running ? (
            <button onClick={onStop}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-cinnabar-400 border border-cinnabar-500/20 rounded-lg hover:bg-cinnabar-500/10 transition-colors">
              <Square className="h-3.5 w-3.5" />停止
            </button>
          ) : (
            <button onClick={onStart}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 transition-colors">
              <Play className="h-3.5 w-3.5" />{editable ? '保存并执行' : '执行'}
            </button>
          )}
          <button onClick={onClose} className="px-2 text-ink-500 hover:text-ink-300" aria-label="关闭运行预览">✕</button>
        </div>
      </div>
      {approvalNodeId && (
        <p className="mx-3 mb-2 flex items-center gap-1.5 rounded-lg bg-gold-500/10 px-2.5 py-1.5 text-[11px] text-gold-400">
          <UserCheck className="h-3.5 w-3.5 shrink-0" />
          节点 {approvalNodeId} 等待人工审批，请在审批中心处理后继续（超时前未处理将按超时结束）。
        </p>
      )}
      <div className="flex items-center gap-2 px-3 pb-2">
        <input type="text" value={input} onChange={e => onInputChange(e.target.value)}
          placeholder="执行输入（可选）…"
          className="flex-1 px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" />
      </div>
      {result != null && (
        <div className="mx-3 mb-2 max-h-20 overflow-y-auto scrollbar-thin rounded-lg bg-ink-800/60 p-2.5 text-xs text-ink-200">
          <p className="mb-1 text-[10px] text-ink-500">最终输出</p>
          <p className="whitespace-pre-wrap break-words">{result}</p>
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-y-auto scrollbar-thin px-3 pb-3 font-mono text-[11px]">
        {events.map((event, index) => (
          <div key={index} className="flex gap-2">
            <span className="shrink-0 text-ink-600">[{event.type}]</span>
            {event.nodeId && <span className="shrink-0 text-cyan-400">{event.nodeId}</span>}
            <span className={event.type === 'error' ? 'text-cinnabar-400' : 'text-ink-300'}>
              {event.content || event.errorMessage || (event.metadata?.output != null ? String(event.metadata.output) : '')}
            </span>
          </div>
        ))}
        {events.length === 0 && <p className="text-ink-500">等待事件…</p>}
      </div>
    </div>
  );
}
