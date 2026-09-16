'use client';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Brain, Search, Loader2, ChevronDown, ChevronRight,
  XCircle, ShieldCheck, Sparkles, Terminal, FileText, Pencil, Wrench,
} from 'lucide-react';
import type { ProcessItem } from '@/lib/agentProcess';
import { toolHeadline, humanizeDuration, hasProcessActivity } from '@/lib/agentProcess';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';

/**
 * AI 处理过程时间线（zcode 风格）。
 * 正文内容段与 思考 / 工具调用 / 网络搜索 按真实发生顺序交错展示：
 * - 无卡片容器，扁平暗色操作行直接落在消息列里，AI 正文以正常排版穿插其间；
 * - 头部为"已工作 X 分 X 秒"，点击可折叠/展开整个过程；
 * - 操作行动词化：思考（持续时长）/ 终端 / 读取 / 编辑 / 查阅，长内容截断，点击展开详情。
 */

/** 工具调用 -> 动词行信息：根据工具名与参数推断 中文动词 + 展示对象 */
function toolRowInfo(name: string, args?: string): { icon: 'terminal' | 'read' | 'edit' | 'search' | 'tool'; verb: string; detail: string; mono: boolean } {
  let parsed: Record<string, unknown> = {};
  if (args) {
    try {
      const p: unknown = JSON.parse(args);
      if (p && typeof p === 'object') parsed = p as Record<string, unknown>;
    } catch { /* 保留空对象 */ }
  }
  const pick = (...keys: string[]): string => {
    for (const k of keys) {
      const v = parsed[k];
      if (typeof v === 'string' && v.trim()) return v;
    }
    return '';
  };
  const command = pick('command', 'cmd', 'script');
  const file = pick('file_path', 'path', 'file', 'filename', 'file_name');
  // 文件路径 -> "文件名 目录/" 两段式（zcode 样式）
  const fileLabel = (p: string): string => {
    const idx = p.lastIndexOf('/');
    return idx >= 0 ? `${p.slice(idx + 1)} ${p.slice(0, idx + 1)}` : p;
  };

  if (command || /bash|shell|exec|terminal/i.test(name)) {
    return { icon: 'terminal', verb: '终端', detail: command || toolHeadline(name, args), mono: true };
  }
  if (/write|edit|create|save|patch|replace/i.test(name)) {
    return { icon: 'edit', verb: '编辑', detail: file ? fileLabel(file) : toolHeadline(name, args), mono: false };
  }
  if (/read|cat|view|open/i.test(name)) {
    return { icon: 'read', verb: '读取', detail: file ? fileLabel(file) : toolHeadline(name, args), mono: false };
  }
  if (/glob|grep|find|search|list|ls/i.test(name)) {
    return { icon: 'search', verb: '查阅', detail: pick('pattern', 'query', 'keyword') || file || toolHeadline(name, args), mono: true };
  }
  return { icon: 'tool', verb: name, detail: toolHeadline(name, args), mono: true };
}

export default function AIProcessTimeline({
  items, status, streaming, totalMs, startAt, expanded, onToggle, hideContentSegments,
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
  /** 完成态模式：隐藏交错在时间线中的正文段（正文由 msg.content 独立渲染在时间线之后，zcode 形态） */
  hideContentSegments?: boolean;
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
    if (streaming && expanded && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [items, status, streaming, expanded]);

  const activeNow = (item: ProcessItem): number | undefined =>
    streaming && item.kind === 'thinking' && item.status === 'active' ? now : undefined;

  // 条目是否展开：用户手动操作优先；否则流式中的"进行中"条目自动展开，已完成的自动收起
  const isItemExpanded = (item: ProcessItem): boolean => expandedFor(item, manual);
  // 稳定回调：按 id 切换展开态（避免每行内联箭头破坏行级 memo）
  const toggleItemById = useCallback((id: string) => {
    const item = items.find(i => i.id === id);
    if (!item) return;
    setManual(prev => ({ ...prev, [id]: !expandedFor(item, prev) }));
  }, [items]);

  // 纯正文（无任何过程事件）不渲染时间线，交给常规气泡
  if (items.length === 0 || !hasProcessActivity(items)) return null;

  const elapsed = streaming
    ? (startAt ? Math.max(0, now - startAt) : 0)
    : (totalMs ?? 0);
  const lastContentIdx = items.map(i => i.kind).lastIndexOf('content');

  return (
    <div className="mb-1">
      {/* 头部：已工作时长，点击折叠/展开整个过程 */}
      <button onClick={onToggle} className="flex items-center gap-1.5 py-1 text-xs text-ink-400 hover:text-ink-200 transition-colors">
        {streaming && <Loader2 className="w-3.5 h-3.5 text-tech-400 animate-spin" />}
        <span>已工作 {humanizeDuration(elapsed)}</span>
        <ChevronDown className={`w-3.5 h-3.5 text-ink-600 transition-transform ${expanded ? '' : '-rotate-90'}`} />
      </button>

      {expanded && (
        <div ref={bodyRef} className="mt-0.5">
          {items.map((item, idx) => {
            if (item.kind === 'content') {
              // 完成态隐藏正文段：正文由 msg.content 完整渲染在时间线之后
              if (hideContentSegments) return null;
              return (
                <div key={item.id} className="my-2">
                  <MarkdownRenderer content={item.text} isStreaming={streaming && idx === lastContentIdx} />
                </div>
              );
            }
            if (item.kind === 'thinking') {
              return <ThinkingRow key={item.id} item={item} now={activeNow(item)} streaming={streaming} expanded={isItemExpanded(item)} onToggle={toggleItemById} />;
            }
            if (item.kind === 'tool') {
              return <ToolRow key={item.id} item={item} expanded={isItemExpanded(item)} onToggle={toggleItemById} />;
            }
            return <SearchRow key={item.id} item={item} expanded={isItemExpanded(item)} onToggle={toggleItemById} />;
          })}

          {/* 实时阶段提示 */}
          {streaming && status && (
            <div className="py-1 text-xs text-ink-500 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-tech-400/70 animate-pulse flex-shrink-0" />
              <span>{status}</span>
              <span className="inline-block w-1 h-3 bg-tech-400/70 animate-pulse ml-0.5" />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** 展开态判定（S9 反馈）：默认收起，用户手动点击优先——流式过程不再自动展开占屏 */
function expandedFor(item: ProcessItem, manual: Record<string, boolean>): boolean {
  if (item.id in manual) return manual[item.id];
  return false;
}

/** 单行尾部预览：截取流式内容最新一段（zcode 单行滚动效果，S9 F4） */
function tailPreview(text: string, max = 64): string {
  const clean = text.replace(/\s+/g, ' ').trimEnd();
  return clean.length > max ? `…${clean.slice(-max)}` : clean;
}

/** 思考行：🧠 思考 · 持续了N秒，点击展开原文；流式期间单行滚动显示最新思考内容。
 * React.memo：now 仅在活跃行传入——已完成行不随 200ms 计时 tick 重渲染（S9 性能修复） */
const ThinkingRow = React.memo(function ThinkingRow({ item, now, streaming, expanded, onToggle }: {
  item: Extract<ProcessItem, { kind: 'thinking' }>;
  now?: number;
  streaming: boolean;
  expanded: boolean;
  onToggle: (id: string) => void;
}) {
  const active = item.status === 'active';
  const duration = active && now !== undefined
    ? humanizeDuration(now - item.startedAt)
    : humanizeDuration((item.endedAt ?? item.startedAt) - item.startedAt);
  return (
    <div>
      <button onClick={() => onToggle(item.id)} className="w-full flex items-center gap-2 py-1 text-left text-xs text-ink-500 hover:text-ink-300 transition-colors">
        <Brain className={`w-3.5 h-3.5 flex-shrink-0 ${active && streaming ? 'text-tech-400/90' : ''}`} />
        <span className="flex-shrink-0">思考</span>
        {/* 流式期间：单行视图滚动显示最新思考内容（完成后回落为持续时长） */}
        {active && streaming && item.text ? (
          <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-ink-400">
            {tailPreview(item.text)}
            <span className="inline-block w-1 h-3 bg-tech-400/80 animate-pulse ml-0.5 align-middle" />
          </span>
        ) : (
          <span className="text-ink-600">· 持续了{duration}{active && '…'}</span>
        )}
        <span className="ml-auto flex-shrink-0 text-ink-600">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>
      {expanded && (
        <div className="mb-1.5 rounded-md bg-ink-900/40 px-3 py-2 max-h-40 overflow-y-auto scrollbar-thin">
          <pre className="text-[11px] text-ink-500 whitespace-pre-wrap break-words font-mono leading-relaxed">
            {item.text}
            {active && <span className="inline-block w-1.5 h-3 bg-tech-400 animate-pulse ml-0.5 align-middle" />}
          </pre>
        </div>
      )}
    </div>
  );
});

/** 工具行：动词化展示（终端/读取/编辑/查阅/工具名），点击展开参数与结果。memo：完成后不再重渲染 */
const ToolRow = React.memo(function ToolRow({ item, expanded, onToggle }: {
  item: Extract<ProcessItem, { kind: 'tool' }>;
  expanded: boolean;
  onToggle: (id: string) => void;
}) {
  const running = item.status === 'executing' || item.status === 'pending';
  const info = toolRowInfo(item.name, item.args);
  return (
    <div>
      <button onClick={() => onToggle(item.id)} className="w-full flex items-center gap-2 py-1 text-left text-xs text-ink-500 hover:text-ink-300 transition-colors">
        {running
          ? <Loader2 className="w-3.5 h-3.5 flex-shrink-0 text-tech-400 animate-spin" />
          : item.status === 'error'
            ? <XCircle className="w-3.5 h-3.5 flex-shrink-0 text-red-400/80" />
            : info.icon === 'terminal' ? <Terminal className="w-3.5 h-3.5 flex-shrink-0" />
              : info.icon === 'edit' ? <Pencil className="w-3.5 h-3.5 flex-shrink-0" />
                : info.icon === 'read' ? <FileText className="w-3.5 h-3.5 flex-shrink-0" />
                  : info.icon === 'search' ? <Search className="w-3.5 h-3.5 flex-shrink-0" />
                    : <Wrench className="w-3.5 h-3.5 flex-shrink-0" />}
        <span className="flex-shrink-0">{info.verb}</span>
        <span className={`min-w-0 flex-1 truncate ${info.mono ? 'font-mono text-[11px]' : ''}`}>{info.detail}</span>
        {/* 已完成且无展开：单行尾部预览最新执行结果（S9 F4 流式单行视图） */}
        {!expanded && !running && item.result && (
          <span className="hidden sm:inline-block flex-shrink-0 max-w-[160px] truncate font-mono text-[10px] text-ink-600" title={item.result}>
            {tailPreview(item.result, 40)}
          </span>
        )}
        <span className="ml-auto flex-shrink-0 text-ink-600">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>
      {expanded && (
        <div className="mb-1.5 rounded-md bg-ink-900/40 px-3 py-2 space-y-1.5">
          {item.args && (
            <div>
              <div className="text-[10px] text-ink-600 mb-0.5">调用参数</div>
              <pre className="text-[10px] text-ink-500 whitespace-pre-wrap break-words font-mono leading-relaxed">{prettyJson(item.args)}</pre>
            </div>
          )}
          {item.result && (
            <div>
              <div className="text-[10px] text-ink-600 mb-0.5">执行结果</div>
              <pre className="text-[10px] text-ink-500 whitespace-pre-wrap break-words font-mono leading-relaxed max-h-40 overflow-y-auto scrollbar-thin">{item.result}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
});

/** 网络搜索行：查阅 · N 条结果（含采纳/丢弃），点击展开来源列表。memo：静态行不重渲染 */
const SearchRow = React.memo(function SearchRow({ item, expanded, onToggle }: {
  item: Extract<ProcessItem, { kind: 'search' }>;
  expanded: boolean;
  onToggle: (id: string) => void;
}) {
  const running = item.status === 'executing' || item.status === 'verifying';
  const adopted = item.results?.filter(r => r.adopted).length ?? 0;
  const discarded = item.results?.filter(r => r.discarded).length ?? 0;
  return (
    <div>
      <button onClick={() => onToggle(item.id)} className="w-full flex items-center gap-2 py-1 text-left text-xs text-ink-500 hover:text-ink-300 transition-colors">
        {running
          ? <Loader2 className="w-3.5 h-3.5 flex-shrink-0 text-tech-400 animate-spin" />
          : <Search className="w-3.5 h-3.5 flex-shrink-0" />}
        <span className="flex-shrink-0">查阅</span>
        <span className="min-w-0 flex-1 truncate">
          {item.results?.length ? `· ${item.results.length} 条结果` : ''}
          {item.query && <span className="text-ink-600"> · {item.query}</span>}
        </span>
        {item.status === 'adopted' && adopted > 0 && <span className="flex-shrink-0 text-[10px] text-green-400/70">采纳 {adopted}</span>}
        {item.status === 'discarded' && discarded > 0 && <span className="flex-shrink-0 text-[10px] text-red-400/60">丢弃 {discarded}</span>}
        <span className="ml-auto flex-shrink-0 text-ink-600">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>
      {expanded && item.results && item.results.length > 0 && (
        <div className="mb-1.5 ml-1 space-y-1">
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
                    <span className="truncate max-w-[200px]">{r.url}</span>
                  </a>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
});

/** 参数 JSON 美化：解析失败时原样返回 */
function prettyJson(raw: string): string {
  try {
    const parsed: unknown = JSON.parse(raw);
    return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
  } catch {
    return raw;
  }
}
