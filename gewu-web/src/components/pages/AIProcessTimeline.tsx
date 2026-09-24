'use client';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Brain, Search, Loader2, ChevronDown, ChevronRight,
  XCircle, ShieldCheck, Sparkles, Terminal, FileText, Pencil, Wrench, Bot,
} from 'lucide-react';
import type { ProcessItem } from '@/lib/agentProcess';
import { toolHeadline, humanizeDuration, hasProcessActivity } from '@/lib/agentProcess';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import ProcessEditDiff from './ProcessEditDiff';
import { lineDiff } from '@/lib/lineDiff';

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
  items, status, streaming, totalMs, startAt, expanded, onToggle, onItemClick,
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
  /** 条目点击（二期：子智能体行 → 右侧面板展示该智能体流式过程/聚合结果） */
  onItemClick?: (item: ProcessItem) => void;
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
              // 正文段保留在时间线内（用户实报问题3）：过程中的叙述按原位置交错展示，
              // 仅最终正文由 msg.content 独立渲染在时间线之后
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
            if (item.kind === 'subagent') {
              return <SubagentRow key={item.id} item={item} streaming={streaming} now={now}
                onClick={onItemClick ? () => onItemClick(item) : undefined} />;
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

/** 已知文件工具失败前缀（ReactAgentExecutor 错误文案契约，同仓同步）：命中则该行按失败态展示 */
const FILE_TOOL_ERROR_PREFIXES = [
  '文件不存在:', '未找到要替换的文本', 'old_text 在文件中多处匹配',
  '缺少 ', '文件操作失败:', '文件工具未配置', '未知内置工具:',
];

function fileToolError(result?: string): string | undefined {
  if (!result) return undefined;
  for (const p of FILE_TOOL_ERROR_PREFIXES) {
    if (result.startsWith(p)) return result;
  }
  return undefined;
}

/** 编辑行 diff 入参：edit_file 取 old_text/new_text；write_file 取 content（视作全新增，无 -N 口径） */
interface EditDiffInfo {
  oldText: string;
  newText: string;
  additions: number;
  deletions: number;
}

/** args 可解析且包含差异源时计算 diff；流式期间参数随 delta 累积，半截 JSON 返回 null 不渲染 */
function editDiffInfo(name: string, args?: string): EditDiffInfo | null {
  if (!args) return null;
  let parsed: Record<string, unknown>;
  try {
    const p: unknown = JSON.parse(args);
    if (!p || typeof p !== 'object') return null;
    parsed = p as Record<string, unknown>;
  } catch {
    return null;
  }
  const str = (v: unknown): string => (typeof v === 'string' ? v : '');
  const oldText = str(parsed.old_text);
  const newText = str(parsed.new_text);
  if (oldText && newText) {
    const d = lineDiff(oldText, newText);
    return { oldText, newText, additions: d.additions, deletions: d.deletions };
  }
  if (/write|create/i.test(name) && parsed.content != null) {
    const content = str(parsed.content);
    const d = lineDiff('', content);
    return { oldText: '', newText: content, additions: d.additions, deletions: 0 };
  }
  return null;
}

/** 后端 edit_file 结果文案解析：「已编辑 path（第 N 行起，+A -D）」→ 起始行/增删统计；旧文案返回 undefined */
function parseEditStartLine(result?: string): number | undefined {
  const m = result?.match(/（第 (\d+) 行起/);
  return m ? Number(m[1]) : undefined;
}
function parseEditCounts(result?: string): { additions: number; deletions: number } | undefined {
  const m = result?.match(/（(?:第 \d+ 行起，)?\+(\d+) -(\d+)）$/);
  return m ? { additions: Number(m[1]), deletions: Number(m[2]) } : undefined;
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
        {/* 单行尾部预览（用户实报问题3）：流式时滚动最新思考内容，完成态保留结尾
            预览——此前完成态只剩时长，点开"已工作"后流式时见过的文字不可见 */}
        {item.text ? (
          <>
            <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-ink-400">
              {tailPreview(item.text)}
              {active && streaming && <span className="inline-block w-1 h-3 bg-tech-400/80 animate-pulse ml-0.5 align-middle" />}
            </span>
            <span className="flex-shrink-0 text-ink-600">· 持续了{duration}{active && '…'}</span>
          </>
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

/** 工具行：动词化展示（终端/读取/编辑/查阅/工具名），点击展开参数与结果。memo：完成后不再重渲染。
 * 编辑行（zcode 风格增强）：折叠行右侧 +N -N 增删统计，展开渲染带行号的红绿差异块 */
/** 子智能体行（二期）：显示名称与执行状态，点击在右侧面板展示该智能体的流式过程/聚合结果 */
const SubagentRow = React.memo(function SubagentRow({ item, streaming, now, onClick }: {
  item: Extract<ProcessItem, { kind: 'subagent' }>;
  streaming: boolean;
  now?: number;
  onClick?: () => void;
}) {
  const running = item.status === 'running';
  const elapsed = running
    ? (now ? Math.max(0, now - item.startedAt) : 0)
    : (item.endedAt ? item.endedAt - item.startedAt : 0);
  const body = (
    <>
      <Bot className={`w-3.5 h-3.5 flex-shrink-0 ${running ? 'text-tech-400 animate-pulse' : 'text-cyber-400'}`} />
      <span className="text-tech-300">子智能体</span>
      <span className="text-ink-200">{item.name}</span>
      <span className="text-ink-600">·</span>
      {running ? (
        <span className="flex items-center gap-1 text-ink-400">
          <Loader2 className="w-3 h-3 animate-spin" /> 执行中
          {streaming && now && <span className="text-ink-600">{humanizeDuration(elapsed)}</span>}
        </span>
      ) : (
        <span className={item.status === 'success' ? 'text-green-400/90' : 'text-red-400/90'}>
          {item.status === 'success' ? '已完成' : '失败'} · {humanizeDuration(elapsed)}
        </span>
      )}
      {onClick && <span className="ml-auto text-[10px] text-ink-600 group-hover/sub:text-tech-400 transition-colors">查看 →</span>}
    </>
  );
  if (!onClick) {
    return (
      <div className="py-1.5 flex items-center gap-2 text-xs">
        {body}
      </div>
    );
  }
  return (
    <button
      onClick={onClick}
      className="w-full group/sub py-1.5 px-1 -mx-1 rounded-md flex items-center gap-2 text-xs text-left hover:bg-tech-500/8 transition-colors"
      title="在右侧面板查看该子智能体的执行过程"
    >
      {body}
    </button>
  );
});

const ToolRow = React.memo(function ToolRow({ item, expanded, onToggle }: {
  item: Extract<ProcessItem, { kind: 'tool' }>;
  expanded: boolean;
  onToggle: (id: string) => void;
}) {
  const running = item.status === 'executing' || item.status === 'pending';
  const info = toolRowInfo(item.name, item.args);
  const isEditRow = info.icon === 'edit';
  // 编辑行扩展：diff 入参（args 半截 JSON 时为 null）+ 失败态 + 后端文案中的真实起始行
  const diffInfo = useMemo(() => (isEditRow ? editDiffInfo(item.name, item.args) : null), [isEditRow, item.name, item.args]);
  const editError = isEditRow && !running ? fileToolError(item.result) : undefined;
  const startLine = isEditRow ? parseEditStartLine(item.result) : undefined;
  // 折叠行增删统计：后端结果文案优先（落库截断后仍可用），args 现算次之；失败行不显示
  const rowCounts = isEditRow && !running && !editError
    ? parseEditCounts(item.result) ?? (diffInfo ? { additions: diffInfo.additions, deletions: diffInfo.deletions } : undefined)
    : undefined;
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
        {/* 编辑行：+N -N 增删统计（zcode 风格，删 0 时只显示 +N）；失败行：红色错误文本 */}
        {rowCounts && !expanded && (
          <span className="flex-shrink-0 font-mono text-[10px]">
            <span className="text-green-400/80">+{rowCounts.additions}</span>
            {rowCounts.deletions > 0 && <span className="ml-1 text-red-400/70">-{rowCounts.deletions}</span>}
          </span>
        )}
        {editError && !expanded && (
          <span className="hidden sm:inline-block flex-shrink-0 max-w-[160px] truncate font-mono text-[10px] text-red-400/70" title={editError}>
            {tailPreview(editError, 40)}
          </span>
        )}
        {/* 其余行（含无法算出统计的旧编辑行）：维持结果尾部预览 */}
        {!expanded && !running && item.result && !rowCounts && !editError && (
          <span className="hidden sm:inline-block flex-shrink-0 max-w-[160px] truncate font-mono text-[10px] text-ink-600" title={item.result}>
            {tailPreview(item.result, 40)}
          </span>
        )}
        <span className="ml-auto flex-shrink-0 text-ink-600">{expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}</span>
      </button>
      {expanded && (
        isEditRow && diffInfo && !editError ? (
          <ProcessEditDiff oldText={diffInfo.oldText} newText={diffInfo.newText} startLine={startLine} />
        ) : (
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
        )
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
