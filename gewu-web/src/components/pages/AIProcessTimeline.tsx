'use client';
import { useEffect, useRef, useState } from 'react';
import {
  Brain, Search, Loader2, ChevronDown, ChevronRight,
  CheckCircle2, XCircle, ShieldCheck, ExternalLink, Sparkles,
} from 'lucide-react';
import type { ProcessItem } from '@/lib/agentProcess';
import { toolHeadline, formatDuration } from '@/lib/agentProcess';

/**
 * AI 处理过程时间线（zcode 风格）。
 * 思考片段 / 工具调用 / 网络搜索按真实发生顺序交错实时展示：
 * - 流式期间整体展开，最新条目自动展开、历史条目收起为摘要行；
 * - 完成后折叠为"已完成思考"摘要，点击可回看全过程。
 */
export default function AIProcessTimeline({
  items, status, streaming, totalMs, startAt, expanded, onToggle,
}: {
  items: ProcessItem[];
  /** 流式期间的后端阶段提示（如"正在继续推理..."） */
  status?: string;
  streaming: boolean;
  /** 完成时的总耗时（毫秒） */
  totalMs?: number;
  /** 过程开始时间戳（毫秒），流式期间用于实时计时 */
  startAt?: number;
  expanded: boolean;
  onToggle: () => void;
}) {
  const [now, setNow] = useState(Date.now());
  const bodyRef = useRef<HTMLDivElement>(null);
  // 用户手动展开/收起的条目（记录用户偏好，覆盖自动行为）
  const [manual, setManual] = useState<Record<string, boolean>>({});

  // 流式期间刷新计时，并让时间线自动滚动到最新条目
  useEffect(() => {
    if (!streaming) return;
    const timer = setInterval(() => setNow(Date.now()), 200);
    return () => clearInterval(timer);
  }, [streaming]);

  useEffect(() => {
    if (streaming && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [items, status, streaming]);

  const toolCount = items.filter(i => i.kind === 'tool').length;
  const searchCount = items.filter(i => i.kind === 'search').length;
  const thinkCount = items.filter(i => i.kind === 'thinking').length;
  const elapsed = streaming
    ? (startAt ? Math.max(0, now - startAt) : 0)
    : (totalMs ?? 0);

  const toggleItem = (id: string, current: boolean) => {
    setManual(prev => ({ ...prev, [id]: !current }));
  };

  // 条目是否展开：用户手动操作优先；否则流式中的"进行中"条目自动展开，已完成的自动收起
  const isItemExpanded = (item: ProcessItem): boolean => {
    if (item.id in manual) return manual[item.id];
    if (item.kind === 'thinking') return item.status === 'active';
    if (item.kind === 'tool') return item.status === 'executing' || item.status === 'pending';
    return item.status === 'executing' || item.status === 'verifying';
  };

  if (items.length === 0) return null;

  return (
    <div className="mb-3 rounded-lg border border-tech-500/10 overflow-hidden" style={{ background: 'rgba(14,28,27,0.6)' }}>
      {/* 摘要头：流式期间显示实时计时，完成后显示总耗时 */}
      <button onClick={onToggle} className="w-full flex items-center gap-2 px-3 py-2 text-xs text-ink-400 hover:text-tech-400 transition-colors">
        {streaming
          ? <Loader2 className="w-3.5 h-3.5 text-tech-400 animate-spin" />
          : <Brain className="w-3.5 h-3.5 text-tech-400" />}
        <span className={streaming ? 'text-tech-400' : 'text-ink-300'}>
          {streaming ? 'AI 正在处理' : '已完成思考'}
        </span>
        <span className="text-[10px] text-ink-600">{formatDuration(elapsed)}</span>
        {toolCount > 0 && <span className="px-1.5 py-0.5 text-[10px] bg-tech-500/10 text-tech-400 rounded">{toolCount} 次工具调用</span>}
        {searchCount > 0 && <span className="px-1.5 py-0.5 text-[10px] bg-tech-500/10 text-tech-400 rounded">{searchCount} 次搜索</span>}
        {thinkCount > 1 && <span className="px-1.5 py-0.5 text-[10px] bg-tech-500/10 text-tech-400 rounded">{thinkCount} 轮推理</span>}
        <span className="ml-auto text-ink-600">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>

      <div className={`thinking-collapse ${expanded ? 'expanded' : 'collapsed'}`}>
        <div ref={bodyRef} className="px-3 pb-3 max-h-80 overflow-y-auto scrollbar-thin">
          <div className="relative pl-4 space-y-1 before:absolute before:left-[7px] before:top-1 before:bottom-1 before:w-px before:bg-tech-500/15">
            {items.map(item => {
              if (item.kind === 'thinking') {
                return <ThinkingItem key={item.id} item={item} expanded={isItemExpanded(item)} onToggle={() => toggleItem(item.id, isItemExpanded(item))} />;
              }
              if (item.kind === 'tool') {
                return <ToolItem key={item.id} item={item} expanded={isItemExpanded(item)} onToggle={() => toggleItem(item.id, isItemExpanded(item))} />;
              }
              return <SearchItemView key={item.id} item={item} expanded={isItemExpanded(item)} onToggle={() => toggleItem(item.id, isItemExpanded(item))} />;
            })}
          </div>

          {/* 实时阶段提示 */}
          {streaming && status && (
            <div className="mt-2 pt-2 border-t border-tech-500/10 text-[11px] text-tech-400/80 flex items-center gap-1.5">
              <Sparkles className="w-3 h-3 animate-pulse" />
              <span>{status}</span>
              <span className="inline-block w-1 h-3 bg-tech-400/70 animate-pulse ml-0.5" />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/** 思考片段：流式文本实时输出，完成后折叠为摘要行 */
function ThinkingItem({ item, expanded, onToggle }: { item: Extract<ProcessItem, { kind: 'thinking' }>; expanded: boolean; onToggle: () => void }) {
  const active = item.status === 'active';
  const duration = item.endedAt ? formatDuration(item.endedAt - item.startedAt) : '';
  return (
    <div className="relative">
      <span className={`absolute -left-4 top-1.5 w-[7px] h-[7px] rounded-full ${active ? 'bg-tech-400 thinking-dot' : 'bg-tech-500/40'}`} />
      <button onClick={onToggle} className="w-full flex items-center gap-1.5 text-[11px] py-0.5 text-ink-400 hover:text-tech-400 transition-colors">
        <Brain className={`w-3 h-3 ${active ? 'text-tech-400' : 'text-ink-500'}`} />
        <span className={active ? 'text-tech-400' : ''}>思考中</span>
        {!active && duration && <span className="text-[10px] text-ink-600">{duration} · {item.text.length} 字</span>}
        <span className="text-ink-600">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>
      <div className={`thinking-collapse ${expanded ? 'expanded' : 'collapsed'}`}>
        <div className="mt-1 mb-1.5 bg-ink-900/50 rounded-lg p-2.5">
          <pre className="text-[11px] text-ink-300 whitespace-pre-wrap break-words font-mono leading-relaxed">
            {item.text}
            {active && <span className="inline-block w-1.5 h-3 bg-tech-400 animate-pulse ml-0.5 align-middle" />}
          </pre>
        </div>
      </div>
    </div>
  );
}

/** 工具调用条目：名称+参数摘要，执行中转圈，完成后显示耗时，展开可看参数与结果 */
function ToolItem({ item, expanded, onToggle }: { item: Extract<ProcessItem, { kind: 'tool' }>; expanded: boolean; onToggle: () => void }) {
  const duration = item.endedAt ? formatDuration(item.endedAt - (item.startedAt ?? item.endedAt)) : '';
  const running = item.status === 'executing' || item.status === 'pending';
  return (
    <div className="relative">
      <span className={`absolute -left-4 top-1.5 w-[7px] h-[7px] rounded-full ${running ? 'bg-tech-400 animate-pulse' : 'bg-green-500/50'}`} />
      <button onClick={onToggle} className="w-full flex items-start gap-1.5 text-[11px] py-0.5 text-left text-ink-300 hover:text-tech-400 transition-colors">
        {running
          ? <Loader2 className="w-3 h-3 mt-0.5 text-tech-400 animate-spin flex-shrink-0" />
          : item.status === 'error'
            ? <XCircle className="w-3 h-3 mt-0.5 text-red-400 flex-shrink-0" />
            : <CheckCircle2 className="w-3 h-3 mt-0.5 text-green-500/80 flex-shrink-0" />}
        <span className="font-mono break-all min-w-0 flex-1">{toolHeadline(item.name, item.args)}</span>
        {running && <span className="text-[10px] text-tech-400/70 flex-shrink-0">执行中</span>}
        {!running && duration && <span className="text-[10px] text-ink-600 flex-shrink-0">{duration}</span>}
        <span className="text-ink-600 flex-shrink-0">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>
      <div className={`thinking-collapse ${expanded ? 'expanded' : 'collapsed'}`}>
        <div className="mt-1 mb-1.5 bg-ink-900/50 rounded-lg p-2.5 space-y-1.5">
          {item.args && (
            <div>
              <div className="text-[10px] text-ink-500 mb-0.5">调用参数</div>
              <pre className="text-[10px] text-ink-400 whitespace-pre-wrap break-words font-mono leading-relaxed">{prettyJson(item.args)}</pre>
            </div>
          )}
          {item.result && (
            <div>
              <div className="text-[10px] text-ink-500 mb-0.5">执行结果</div>
              <pre className="text-[10px] text-ink-400 whitespace-pre-wrap break-words font-mono leading-relaxed max-h-40 overflow-y-auto scrollbar-thin">{item.result}</pre>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/** 网络搜索条目：执行 -> 验证 -> 采纳/丢弃，展开可看结果来源列表 */
function SearchItemView({ item, expanded, onToggle }: { item: Extract<ProcessItem, { kind: 'search' }>; expanded: boolean; onToggle: () => void }) {
  const duration = item.endedAt ? formatDuration(item.endedAt - item.startedAt) : '';
  const running = item.status === 'executing' || item.status === 'verifying';
  const adopted = item.results?.filter(r => r.adopted).length ?? 0;
  const discarded = item.results?.filter(r => r.discarded).length ?? 0;
  return (
    <div className="relative">
      <span className={`absolute -left-4 top-1.5 w-[7px] h-[7px] rounded-full ${running ? 'bg-tech-400 animate-pulse' : item.status === 'adopted' ? 'bg-green-500/60' : 'bg-red-500/50'}`} />
      <button onClick={onToggle} className="w-full flex items-center gap-1.5 text-[11px] py-0.5 text-left text-ink-300 hover:text-tech-400 transition-colors">
        {running
          ? <Loader2 className="w-3 h-3 text-tech-400 animate-spin flex-shrink-0" />
          : item.status === 'adopted'
            ? <ShieldCheck className="w-3 h-3 text-green-400 flex-shrink-0" />
            : <XCircle className="w-3 h-3 text-red-400/80 flex-shrink-0" />}
        <span className="min-w-0 flex-1 truncate">
          网络搜索{item.query ? `: ${item.query}` : ''}
        </span>
        {item.status === 'verifying' && <span className="text-[10px] text-tech-400/70 flex-shrink-0">验证中</span>}
        {item.status === 'adopted' && <span className="text-[10px] text-green-400/80 flex-shrink-0">采纳 {adopted}</span>}
        {item.status === 'discarded' && <span className="text-[10px] text-red-400/70 flex-shrink-0">丢弃 {discarded}</span>}
        {!running && duration && <span className="text-[10px] text-ink-600 flex-shrink-0">{duration}</span>}
        <span className="text-ink-600 flex-shrink-0">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>
      <div className={`thinking-collapse ${expanded ? 'expanded' : 'collapsed'}`}>
        {item.results && item.results.length > 0 && (
          <div className="mt-1 mb-1.5 ml-1 space-y-1">
            {item.results.map((r, idx) => (
              <div key={idx} className={`flex items-start gap-1.5 p-1.5 rounded text-[10px] ${
                r.adopted ? 'bg-green-500/5 border border-green-500/10' :
                r.discarded ? 'bg-red-500/5 border border-red-500/10 opacity-60' :
                'bg-ink-900/30'
              }`}>
                <div className="flex-shrink-0 mt-0.5">
                  {r.adopted ? <ShieldCheck className="w-2.5 h-2.5 text-green-400" /> :
                   r.discarded ? <XCircle className="w-2.5 h-2.5 text-red-400" /> :
                   <Search className="w-2.5 h-2.5 text-ink-500" />}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1">
                    <span className={`font-medium ${r.discarded ? 'text-ink-600 line-through' : 'text-ink-400'}`}>{r.title || r.source}</span>
                    <span className="text-[9px] text-ink-600">[{r.source}]</span>
                    {r.adopted && <span className="text-[9px] text-green-400/70">{Math.round(r.confidence * 100)}%</span>}
                    {r.discarded && r.discardReason && <span className="text-[9px] text-red-400/60">丢弃: {r.discardReason}</span>}
                  </div>
                  {r.snippet && <p className={`mt-0.5 ${r.discarded ? 'text-ink-700' : 'text-ink-500'} line-clamp-2`}>{r.snippet}</p>}
                  {r.url && !r.discarded && (
                    <a href={r.url} target="_blank" rel="noopener noreferrer"
                       className="inline-flex items-center gap-0.5 mt-0.5 text-tech-400/70 hover:text-tech-400 transition-colors">
                      <ExternalLink className="w-2.5 h-2.5" />
                      <span className="truncate max-w-[200px]">{r.url}</span>
                    </a>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/** 参数 JSON 美化：解析失败时原样返回 */
function prettyJson(raw: string): string {
  try {
    const parsed: unknown = JSON.parse(raw);
    return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
  } catch {
    return raw;
  }
}
