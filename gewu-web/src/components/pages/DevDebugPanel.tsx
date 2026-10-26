'use client';
import { useEffect, useRef, useState } from 'react';
import { Play, StepForward, CornerDownRight, CornerUpRight, Square, Bug, Loader2, Variable, GitCommitHorizontal, Terminal, Crosshair, X } from 'lucide-react';
import type { DebugState } from '@/lib/dev-workspace';

/**
 * 开发工作空间调试面板（IDEA Debug 面板复刻）：
 * 顶部工具条（继续 F9 / 步过 F8 / 步入 F7 / 步出 Shift+F8 / 运行到光标 Alt+F9 / 停止）+
 * 变量（局部变量 + Watches + 表达式求值）/ 调用栈 / 控制台 三个页签。
 */
interface Props {
  state: DebugState;
  /** 累积的应用控制台文本（增量由页面合并） */
  consoleText: string;
  /** 正在执行的调试动作标签 */
  busy: string | null;
  onAction: (op: 'cont' | 'next' | 'step' | 'stepUp') => void;
  /** 运行到光标（IDEA Alt+F9） */
  onRunTo: () => void;
  /** 线程切换（IDEA Threads 视图选中） */
  onThread: (threadId: string) => void;
  onStop: () => void;
  onEval: (expr: string) => void;
  /** Watch 登记/移除 */
  onWatch: (expr: string, remove?: boolean) => void;
  /** 定位栈帧源文件 */
  onGoto: (className: string, fileName?: string, line?: number) => void;
}

export default function DevDebugPanel({ state, consoleText, busy, onAction, onRunTo, onThread, onStop, onEval, onWatch, onGoto }: Props) {
  const [tab, setTab] = useState<'vars' | 'frames' | 'console'>('vars');
  const [expr, setExpr] = useState('');
  const [watchInput, setWatchInput] = useState('');
  const consoleRef = useRef<HTMLPreElement>(null);

  // 控制台自动滚底
  useEffect(() => {
    if (consoleRef.current) consoleRef.current.scrollTop = consoleRef.current.scrollHeight;
  }, [consoleText]);

  const stopped = state.status === 'stopped';
  const terminated = state.status === 'terminated';
  const btn = (label: string, icon: React.ReactNode, onClick: () => void, kbd: string, disabled: boolean, tag: string) => (
    <button onClick={onClick} disabled={disabled || !!busy} title={`${label}（${kbd}）`}
      className="flex items-center gap-1 px-2 py-1 text-xs rounded border border-ink-700/60 text-ink-200 hover:text-tech-400 hover:border-tech-500/30 transition-all disabled:opacity-40">
      {busy === tag ? <Loader2 className="w-3 h-3 animate-spin" /> : icon}{label}
      <kbd className="text-[9px] text-ink-600 ml-0.5">{kbd}</kbd>
    </button>
  );

  return (
    <div className="flex-shrink-0 h-48 mt-2 rounded-lg border border-tech-500/10 bg-ink-900/40 flex flex-col overflow-hidden">
      {/* 工具条 + 状态 + 线程选择 */}
      <div className="flex items-center justify-between gap-2 px-2.5 py-1.5 border-b border-tech-500/10 flex-shrink-0">
        <div className="flex items-center gap-1.5 min-w-0">
          <Bug className="w-3.5 h-3.5 text-tech-400 flex-shrink-0" />
          <span className="text-[10px] text-ink-500 flex-shrink-0">
            {state.kind === 'python' ? 'Python · pdb'
              : state.kind === 'node' ? 'Node · inspect'
              : state.kind === 'go' ? 'Go · dlv'
              : state.kind === 'c' ? 'C/C++ · gdb'
              : 'Java · jdb'}
          </span>
          <span className={`text-xs flex-shrink-0 ${stopped ? 'text-gold-400' : terminated ? 'text-ink-500' : 'text-green-400'}`}>
            {stopped ? '已挂起' : terminated ? '已结束' : '运行中'}
          </span>
          {stopped && state.currentThread && (
            <span className="text-[11px] font-mono text-ink-400 flex-shrink-0">线程 {state.currentThread}</span>
          )}
          {stopped && (state.threads?.length || 0) > 1 && (
            <select
              value={state.currentThread || state.threads.find(t => t.current)?.id || ''}
              onChange={(e) => onThread(e.target.value)}
              className="bg-ink-900/60 border border-tech-500/15 rounded text-[11px] text-ink-200 outline-none cursor-pointer max-w-40"
              title="切换调试上下文线程（IDEA Threads 视图）">
              {state.threads.map(t => (
                <option key={t.id} value={t.id}>{t.name}{t.current ? '（当前）' : ''}</option>
              ))}
            </select>
          )}
          {stopped && state.currentFile && (
            <span className="text-[11px] font-mono text-ink-300 truncate">
              {state.currentClass}.{state.currentMethod} · {state.currentFile}:{state.currentLine}
            </span>
          )}
          {stopped && state.currentThread && (
            <span className="text-[11px] font-mono text-ink-400 flex-shrink-0">线程 {state.currentThread}</span>
          )}
          {stopped && (state.threads?.length || 0) > 1 && (
            <select
              value={state.currentThread || state.threads.find(t => t.current)?.id || ''}
              onChange={(e) => onThread(e.target.value)}
              className="bg-ink-900/60 border border-tech-500/15 rounded text-[11px] text-ink-200 outline-none cursor-pointer max-w-40"
              title="切换调试上下文线程（IDEA Threads 视图）">
              {state.threads.map(t => (
                <option key={t.id} value={t.id}>{t.name}{t.current ? '（当前）' : ''}</option>
              ))}
            </select>
          )}
        </div>
        <div className="flex items-center gap-1.5 flex-shrink-0">
          {btn('继续', <Play className="w-3 h-3" />, () => onAction('cont'), 'F9', !stopped, 'cont')}
          {btn('步过', <StepForward className="w-3 h-3" />, () => onAction('next'), 'F8', !stopped, 'next')}
          {btn('步入', <CornerDownRight className="w-3 h-3" />, () => onAction('step'), 'F7', !stopped, 'step')}
          {btn('步出', <CornerUpRight className="w-3 h-3" />, () => onAction('stepUp'), '⇧F8', !stopped, 'stepUp')}
          {btn('到光标', <Crosshair className="w-3 h-3" />, onRunTo, '⌥F9', !stopped, 'runTo')}
          <button onClick={onStop} disabled={!!busy} title="停止调试"
            className="flex items-center gap-1 px-2 py-1 text-xs rounded border border-cinnabar-500/20 text-cinnabar-400 hover:bg-cinnabar-500/10 transition-all disabled:opacity-40">
            {busy === 'stop' ? <Loader2 className="w-3 h-3 animate-spin" /> : <Square className="w-3 h-3" />}停止
          </button>
        </div>
      </div>

      {/* 页签 */}
      <div className="flex items-center gap-1 px-2 pt-1 flex-shrink-0">
        {([['vars', '变量', <Variable key="v" className="w-3 h-3" />], ['frames', '调用栈', <GitCommitHorizontal key="f" className="w-3 h-3" />], ['console', '控制台', <Terminal key="c" className="w-3 h-3" />]] as const).map(([id, label, icon]) => (
          <button key={id} onClick={() => setTab(id as typeof tab)}
            className={`flex items-center gap-1 px-2 py-1 text-[11px] rounded-t transition-all ${tab === id ? 'text-tech-400 border-b border-tech-400' : 'text-ink-500 hover:text-ink-200'}`}>
            {icon}{label}
          </button>
        ))}
        {state.message && tab === 'vars' && (
          <span className="ml-auto text-[10px] text-ink-500 truncate max-w-[45%]" title={state.message}>{state.message}</span>
        )}
      </div>

      {/* 内容 */}
      <div className="flex-1 min-h-0 overflow-y-auto px-2 py-1.5 text-xs">
        {tab === 'vars' && (
          <>
            <table className="w-full">
              <tbody>
                {state.locals.length === 0 && (
                  <tr><td className="py-3 text-center text-ink-600">
                    {state.kind === 'node'
                      ? 'Node 暂不支持局部变量列表，请在下方输入表达式求值'
                      : stopped ? '无可显示的局部变量' : '挂起后展示局部变量'}
                  </td></tr>
                )}
                {state.locals.map(l => (
                  <tr key={l.name} className="align-top">
                    <td className="py-0.5 pr-2 font-mono text-tech-400 whitespace-nowrap">{l.name}</td>
                    <td className="py-0.5 font-mono text-ink-300 break-all">{l.value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex items-center gap-1.5 mt-2 pt-1.5 border-t border-tech-500/10">
              <input value={expr} onChange={e => setExpr(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && expr.trim()) { onEval(expr.trim()); } }}
                placeholder="求值表达式（IDEA Alt+F8）"
                className="flex-1 px-2 py-1 bg-ink-800/50 border border-tech-500/10 rounded text-[11px] text-ink-100 outline-none focus:border-tech-500/30" />
              <button onClick={() => { if (expr.trim()) onEval(expr.trim()); }} disabled={!!busy || !stopped}
                className="px-2 py-1 text-[11px] border border-ink-700/60 rounded text-ink-200 hover:text-tech-400 disabled:opacity-40">求值</button>
            </div>
            {/* Watches（IDEA Watches 面板） */}
            <div className="mt-2 pt-1.5 border-t border-tech-500/10">
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-ink-500 flex-shrink-0">Watches</span>
                <input value={watchInput} onChange={e => setWatchInput(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter' && watchInput.trim()) { onWatch(watchInput.trim()); setWatchInput(''); } }}
                  placeholder="添加监视表达式"
                  className="flex-1 px-2 py-1 bg-ink-800/50 border border-tech-500/10 rounded text-[11px] text-ink-100 outline-none focus:border-tech-500/30" />
              </div>
              <div className="mt-1">
                {state.watches.length === 0 && <p className="text-[10px] text-ink-600 py-1">（无监视表达式）</p>}
                {state.watches.map(w => (
                  <div key={w.expr} className="group flex items-start gap-2 px-1 py-0.5 rounded hover:bg-tech-500/5">
                    <span className="font-mono text-[11px] text-gold-400/90 break-all flex-1">
                      {w.expr}<span className="text-ink-500"> = </span><span className="text-ink-300">{w.value ?? '（挂起后取值）'}</span>
                    </span>
                    <button onClick={() => onWatch(w.expr, true)} disabled={!!busy}
                      className="opacity-0 group-hover:opacity-100 text-ink-600 hover:text-cinnabar-400 flex-shrink-0" title="移除">
                      <X className="w-3 h-3" />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          </>
        )}
        {tab === 'frames' && (
          <>
            {state.frames.length === 0 && <div className="py-3 text-center text-ink-600">{stopped ? '无栈帧' : '挂起后展示调用栈'}</div>}
            {state.frames.map((f, i) => (
              <button key={i} onClick={() => onGoto(f.className, f.fileName, f.line)}
                className={`w-full flex items-center gap-2 px-1.5 py-1 rounded text-left ${i === 0 ? 'bg-tech-500/10' : 'hover:bg-tech-500/5'}`}>
                <span className="text-[10px] text-ink-600 font-mono flex-shrink-0">[{i + 1}]</span>
                <span className="font-mono text-ink-200 truncate">{f.className}.{f.methodName}</span>
                <span className="ml-auto text-[10px] font-mono text-ink-500 flex-shrink-0">{f.fileName}{f.line ? `:${f.line}` : ''}</span>
              </button>
            ))}
          </>
        )}
        {tab === 'console' && (
          <pre ref={consoleRef} className="h-full font-mono text-[11px] text-ink-300 whitespace-pre-wrap break-all overflow-y-auto">{consoleText || '（暂无输出）'}</pre>
        )}
      </div>
    </div>
  );
}
