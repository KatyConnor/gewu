// AI 处理过程实时展示 - 将 chatStream 的流式事件累积为按时间交错的"过程时间线"。
// 供 ChatPage / ProjectDetailPage 的 AIProcessTimeline 组件消费，效果对标 zcode：
// 思考片段、工具调用、网络搜索按真实发生顺序交错展示，实时更新。
import type { SearchItem, WebSearchInfo, VerifyInfo } from './chat';

/** 思考片段：一轮推理产生一个片段，下一个事件（工具/正文/搜索）开始时自动闭合 */
export interface ThinkingProcessItem {
  kind: 'thinking';
  id: string;
  text: string;
  status: 'active' | 'done';
  startedAt: number;
  endedAt?: number;
}

/** 工具调用条目 */
export interface ToolProcessItem {
  kind: 'tool';
  id: string;
  name: string;
  args?: string;
  status: 'pending' | 'executing' | 'done' | 'error';
  result?: string;
  startedAt?: number;
  endedAt?: number;
}

/** 网络搜索条目（含正确性验证结论） */
export interface SearchProcessItem {
  kind: 'search';
  id: string;
  status: 'executing' | 'verifying' | 'adopted' | 'discarded';
  query?: string;
  results?: SearchItem[];
  verifyMethod?: string;
  startedAt: number;
  endedAt?: number;
}

export type ProcessItem = ThinkingProcessItem | ToolProcessItem | SearchProcessItem;

/** 一次 AI 回复处理过程的可渲染快照 */
export interface ProcessSnapshot {
  items: ProcessItem[];
  /** 当前阶段提示（如"正在思考..."、"正在继续推理..."） */
  status: string;
  startedAt: number;
  endedAt?: number;
  /** 快照时刻的耗时（毫秒），流式期间为实时值，finish 后固定 */
  elapsedMs: number;
}

/** 人类可读的工具调用摘要，如 read_file(file_path="ChatPage.tsx")、web_search(query="...") */
export function toolHeadline(name: string, args?: string): string {
  const preferred = [
    'file_path', 'path', 'file', 'filename', 'file_name', 'directory', 'dir',
    'command', 'cmd', 'script', 'code', 'sql',
    'query', 'q', 'keyword', 'keywords', 'search',
    'url', 'uri', 'link',
    'pattern', 'regex', 'name', 'id', 'content', 'text', 'input',
  ];
  let summary = '';
  if (args) {
    try {
      const parsed: unknown = JSON.parse(args);
      if (parsed && typeof parsed === 'object') {
        const entries = Object.entries(parsed as Record<string, unknown>)
          .filter(([, v]) => v !== null && v !== undefined && v !== '');
        const hit = entries.find(([k]) => preferred.includes(k)) ?? entries[0];
        if (hit) {
          const value = typeof hit[1] === 'string' ? hit[1] : JSON.stringify(hit[1]);
          summary = `${hit[0]}="${value.length > 48 ? value.slice(0, 48) + '…' : value}"`;
        }
      } else if (typeof parsed === 'string' && parsed) {
        summary = `"${parsed.length > 48 ? parsed.slice(0, 48) + '…' : parsed}"`;
      }
    } catch {
      summary = `"${args.length > 48 ? args.slice(0, 48) + '…' : args}"`;
    }
  }
  return summary ? `${name}(${summary})` : name;
}

/**
 * 过程累积器：消费 chatStream 回调语义，维护交错时间线。
 * 事件到来时更新内部条目，由调用方决定何时快照渲染。
 */
export class ProcessTracker {
  private items: ProcessItem[] = [];
  private status = '';
  private readonly startedAt = Date.now();
  private endedAt?: number;
  private thinkSeq = 0;
  private searchSeq = 0;

  /** 当前活跃的搜索条目（验证结论作用于最后一个搜索条目） */
  private get lastSearch(): SearchProcessItem | undefined {
    for (let i = this.items.length - 1; i >= 0; i--) {
      const item = this.items[i];
      if (item.kind === 'search') return item;
    }
    return undefined;
  }

  private closeActiveThinking() {
    for (let i = this.items.length - 1; i >= 0; i--) {
      const item = this.items[i];
      if (item.kind !== 'thinking') break;
      if (item.status === 'active') {
        item.status = 'done';
        item.endedAt = Date.now();
      }
      break;
    }
  }

  addThinking(text: string) {
    const last = this.items[this.items.length - 1];
    if (last && last.kind === 'thinking' && last.status === 'active') {
      last.text += text;
      return;
    }
    this.items.push({
      kind: 'thinking',
      id: `think-${++this.thinkSeq}`,
      text,
      status: 'active',
      startedAt: Date.now(),
    });
  }

  addTool(toolCall: { id: string; name: string; arguments?: string }) {
    this.closeActiveThinking();
    this.items.push({
      kind: 'tool',
      id: toolCall.id || `tool-${Date.now()}`,
      name: toolCall.name,
      args: toolCall.arguments,
      status: 'pending',
      startedAt: Date.now(),
    });
  }

  toolExecuting(id: string) {
    const tool = this.items.find(i => i.kind === 'tool' && i.id === id);
    if (tool && tool.kind === 'tool' && tool.status === 'pending') tool.status = 'executing';
  }

  toolResult(id: string, output: string) {
    const tool = this.items.find(i => i.kind === 'tool' && i.id === id);
    if (tool && tool.kind === 'tool') {
      tool.status = 'done';
      tool.result = output;
      tool.endedAt = Date.now();
    }
  }

  searchStart(label: string) {
    this.closeActiveThinking();
    this.items.push({
      kind: 'search',
      id: `search-${++this.searchSeq}`,
      status: 'executing',
      query: label && label !== '正在搜索网络资源...' ? label : undefined,
      startedAt: Date.now(),
    });
  }

  searchResult(info: WebSearchInfo) {
    const search = this.lastSearch;
    if (!search) return;
    search.query = info.query;
    search.results = info.results;
    search.status = 'verifying';
  }

  searchVerifying(label: string) {
    const search = this.lastSearch;
    if (search && !search.query && label && label !== '正在验证搜索结果正确性...') {
      search.query = label;
    }
  }

  searchVerdict(verdict: VerifyInfo) {
    const search = this.lastSearch;
    if (!search) return;
    search.status = verdict.adoptedCount > 0 ? 'adopted' : 'discarded';
    search.verifyMethod = verdict.method;
    search.endedAt = Date.now();
  }

  /** 首段正文输出：思考阶段结束（后续 thinking 事件将开启新片段） */
  contentStarted() {
    this.closeActiveThinking();
  }

  setStatus(status: string) {
    this.status = status;
  }

  /** 正常完成：闭合所有未闭合条目 */
  finish() {
    this.closeActiveThinking();
    this.endedAt = this.endedAt ?? Date.now();
    this.status = '';
  }

  /** 快照：浅拷贝条目，保证 React 拿到新引用触发渲染 */
  snapshot(): ProcessSnapshot {
    return {
      items: this.items.map(i => ({ ...i })),
      status: this.status,
      startedAt: this.startedAt,
      endedAt: this.endedAt,
      elapsedMs: this.elapsedMs,
    };
  }

  get elapsedMs(): number {
    return (this.endedAt ?? Date.now()) - this.startedAt;
  }
}

/**
 * 创建 chatStream 回调与 ProcessTracker 的绑定。
 * 返回的 handlers 覆盖过程相关回调（thinking/tool/web），调用方在此基础上
 * 合并自己的 onContent/onError/onComplete 等业务回调。
 */
export function createProcessStreamHandler(onUpdate: (snapshot: ProcessSnapshot) => void) {
  const tracker = new ProcessTracker();
  const notify = () => onUpdate(tracker.snapshot());
  return {
    tracker,
    handlers: {
      onThinking: (text: string) => { tracker.addThinking(text); notify(); },
      onStatus: (status: string) => { tracker.setStatus(status); notify(); },
      onToolCall: (toolCall: { id: string; name: string; arguments: string }) => { tracker.addTool(toolCall); notify(); },
      onToolExecuting: (toolCall: { id: string; name: string; arguments: string }) => { tracker.toolExecuting(toolCall.id); notify(); },
      onToolResult: (result: { toolCallId: string; output: string }) => { tracker.toolResult(result.toolCallId, result.output); notify(); },
      onWebSearchStart: (status: string) => { tracker.searchStart(status); notify(); },
      onWebSearchResult: (info: WebSearchInfo) => { tracker.searchResult(info); notify(); },
      onWebVerifying: (status: string) => { tracker.searchVerifying(status); notify(); },
      onWebVerdict: (verdict: VerifyInfo) => { tracker.searchVerdict(verdict); notify(); },
      onContentStarted: () => { tracker.contentStarted(); notify(); },
      // T4.5 新事件：以阶段状态提示呈现（通知型，无需过程条目）
      onExperienceSaved: (note: string) => { tracker.setStatus(note); notify(); },
      onFailureRecorded: (reason: string) => { tracker.setStatus(reason); notify(); },
    },
  };
}

/** 毫秒 -> "12.3s" / "1m05s" 展示 */
export function formatDuration(ms: number): string {
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60);
  return `${m}m${s.toString().padStart(2, '0')}s`;
}
