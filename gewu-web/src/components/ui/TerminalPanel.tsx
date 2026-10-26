'use client';
// 网页终端面板（设计文档 44 §F6）：xterm.js 封装 + 连接管理 + 尺寸自适应
import { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
// xterm 基础样式必须引入：viewport 绝对定位/滚动、测量与 helper 元素的隐藏全靠它；
// 缺失时滚动容器高度塌缩为 0（无法滚动），字符宽度测量元素裸露成乱码行（用户实报）
import '@xterm/xterm/css/xterm.css';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import { Minimize2, Maximize2, X, RotateCw, PanelBottom, PanelRight } from 'lucide-react';
import type { CSSProperties } from 'react';
import { TerminalClient, TerminalStatus } from '@/lib/terminal';

const termTheme = {
  background: 'rgba(8, 18, 17, 0.96)',
  foreground: '#d7e2e0',
  cursor: '#00b894',
  selectionBackground: 'rgba(0, 184, 148, 0.3)',
};

interface TerminalPanelProps {
  visible: boolean;
  onClose(): void;
  fullscreen: boolean;
  onToggleFullscreen(): void;
}

export default function TerminalPanel({ visible, onClose, fullscreen, onToggleFullscreen }: TerminalPanelProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const xtermRef = useRef<Terminal | null>(null);
  const clientRef = useRef<TerminalClient | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const [status, setStatus] = useState<TerminalStatus>('connecting');
  const [statusDetail, setStatusDetail] = useState<string>('');
  const [restartTick, setRestartTick] = useState(0);
  /** 抽屉停靠位:bottom=底部抽屉 / right=右侧抽屉(fixed 定位受视口约束,不占文档流) */
  const [position, setPosition] = useState<'bottom' | 'right'>('bottom');

  useEffect(() => {
    if (!visible || !containerRef.current) return;
    const term = new Terminal({
      fontFamily: "'JetBrains Mono', 'Fira Code', Menlo, monospace",
      fontSize: 13,
      cursorBlink: true,
      scrollback: 5000,
      theme: termTheme,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.loadAddon(new WebLinksAddon());
    term.open(containerRef.current);
    xtermRef.current = term;
    fitRef.current = fit;

    let cols = 120;
    let rows = 32;
    try {
      fit.fit();
      cols = term.cols;
      rows = term.rows;
    } catch { /* 容器尚未布局时使用默认尺寸 */ }

    term.onData((data) => {
      clientRef.current?.sendInput(new TextEncoder().encode(data));
    });
    term.writeln('\x1b[36m正在连接工作区终端…\x1b[0m');

    const client = new TerminalClient({
      cols, rows,
      onOutput: (data) => term.write(new Uint8Array(data)),
      onStatus: (s, detail) => {
        setStatus(s);
        setStatusDetail(detail || '');
        if (s === 'error' && detail) {
          term.writeln(`\r\n\x1b[31m${detail}\x1b[0m`);
        }
        if (s === 'closed' && detail) {
          term.writeln(`\r\n\x1b[33m${detail}\x1b[0m`);
        }
      },
    });
    clientRef.current = client;
    client.connect();

    const ro = new ResizeObserver(() => {
      try {
        fit.fit();
        clientRef.current?.resize(term.cols, term.rows);
      } catch { /* 面板不可见时跳过 */ }
    });
    ro.observe(containerRef.current);

    // 双 RAF + 字体就绪后强制重算:open 时容器字体/布局未稳定会导致 fit 的行数
    // 与实际渲染错位——光标落在可视区之外(用户实测输入命令后看不到光标位置)
    const refit = () => {
      try {
        fit.fit();
        clientRef.current?.resize(term.cols, term.rows);
      } catch { /* 忽略 */ }
    };
    const raf1 = requestAnimationFrame(() => { refit(); });
    const raf2 = requestAnimationFrame(() => requestAnimationFrame(refit));
    document.fonts?.ready?.then(() => refit()).catch(() => {});

    return () => {
      cancelAnimationFrame(raf1);
      cancelAnimationFrame(raf2);
      ro.disconnect();
      client.dispose();
      term.dispose();
      xtermRef.current = null;
      clientRef.current = null;
      fitRef.current = null;
    };
    // 不依赖 position：停靠位切换仅改容器布局，由 ResizeObserver 触发重排；
    // 若将其列入依赖会销毁重建 xterm，缓冲区（历史命令与输出）全部丢失（用户实报）
  }, [visible, restartTick]);

  // 手动重启：销毁当前连接重建
  const restart = () => {
    setRestartTick((n) => n + 1);
  };

  if (!visible) return null;

  const drawerStyle: CSSProperties = fullscreen
    ? { background: 'rgba(8,18,17,0.96)' }
    : position === 'right'
      ? { position: 'fixed', top: 40, right: 0, bottom: 0, width: 'min(560px, 60vw)',
          background: 'rgba(8,18,17,0.96)', boxShadow: '-8px 0 24px rgba(0,0,0,0.4)' }
      : { position: 'fixed', left: 0, right: 0, bottom: 0, height: '45vh', maxHeight: 480,
          background: 'rgba(8,18,17,0.96)', boxShadow: '0 -8px 24px rgba(0,0,0,0.4)' };

  return (
    <div data-testid="terminal-panel"
      className={`border-tech-500/10 flex flex-col ${fullscreen ? 'fixed left-0 right-0 bottom-0 top-10 z-50' : 'fixed z-40'}`}
      style={drawerStyle}>
      <div className="flex items-center justify-between px-4 py-1.5 flex-shrink-0" style={{ borderBottom: '1px solid rgba(0,184,148,0.08)' }}>
        <div className="flex items-center gap-2 text-xs text-ink-400">
          <span className={`inline-block w-1.5 h-1.5 rounded-full ${status === 'ready' ? 'bg-tech-400' : status === 'connecting' ? 'bg-gold-400 animate-pulse' : 'bg-cinnabar-400'}`} />
          <span>工作区终端</span>
          {status === 'connecting' && <span className="text-ink-500">{statusDetail || '连接中…'}</span>}
          {(status === 'closed' || status === 'error') && (
            <span className="text-ink-500">{statusDetail || '已断开'}</span>
          )}
        </div>
        <div className="flex items-center gap-1">
          {(status === 'closed' || status === 'error') && (
            <button onClick={restart} className="p-1.5 text-ink-400 hover:text-tech-400 rounded transition-all" title="重新连接">
              <RotateCw className="w-3.5 h-3.5" />
            </button>
          )}
          {!fullscreen && (
            <button onClick={() => setPosition((p) => (p === 'bottom' ? 'right' : 'bottom'))}
              className="p-1.5 text-ink-400 hover:text-tech-400 rounded transition-all"
              title={position === 'bottom' ? '切换为右侧抽屉' : '切换为底部抽屉'}>
              {position === 'bottom' ? <PanelRight className="w-3.5 h-3.5" /> : <PanelBottom className="w-3.5 h-3.5" />}
            </button>
          )}
          <button onClick={onToggleFullscreen} className="p-1.5 text-ink-400 hover:text-tech-400 rounded transition-all"
            title={fullscreen ? '退出全屏' : '全屏'}>
            {fullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
          </button>
          <button onClick={onClose} className="p-1.5 text-ink-400 hover:text-cinnabar-400 rounded transition-all" title="关闭终端">
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
      <div ref={containerRef} className="flex-1 min-h-0 px-2 pb-1" />
    </div>
  );
}
