import { useEffect, useRef } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';

/**
 * 终端面板（D1-25）：xterm.js ↔ 主进程 PTY（node-pty / script 回退）。
 * cwd 为引擎工作目录；会话退出后可重建。
 */
export default function TerminalPanel({ cwd, height = 220 }: {
  cwd?: string;
  height?: number;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const tabIdRef = useRef<string | null>(null);

  useEffect(() => {
    const term = new Terminal({
      fontSize: 12,
      fontFamily: '"Cascadia Code", Consolas, monospace',
      theme: {
        background: '#0a0f0d',
        foreground: '#c9d1d9',
        cursor: '#00d4aa',
        selectionBackground: 'rgba(0,212,170,0.3)',
      },
      cursorBlink: true,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(hostRef.current!);
    termRef.current = term;

    let disposed = false;
    let tabId: string | null = null;

    (async () => {
      // 容器尺寸就绪后再创建（fit 计算需要实际像素）
      await new Promise((r) => setTimeout(r, 100));
      if (disposed) return;
      const cols = Math.max(20, Math.floor(hostRef.current!.clientWidth / 9));
      const rows = Math.max(8, Math.floor((hostRef.current!.clientHeight - 8) / 18));
      const { tabId: id } = await window.gewu.terminal.create({ cwd, cols, rows });
      if (disposed) { window.gewu.terminal.kill({ tabId: id }); return; }
      tabIdRef.current = id;
      tabId = id;
      const offData = window.gewu.terminal.onData(({ tabId: tid, data }) => {
        if (tid === id) term.write(data);
      });
      const offExit = window.gewu.terminal.onExit(({ tabId: tid }) => {
        if (tid === id) term.writeln('\r\n[会话已退出]');
      });
      const onResize = () => {
        try { fit.fit(); } catch { /* 隐藏态 */ }
      };
      window.addEventListener('resize', onResize);
      const ro = new ResizeObserver(onResize);
      ro.observe(hostRef.current!);
      // 清理链
      (term as unknown as { _gewuCleanup?: () => void })._gewuCleanup = () => {
        offData(); offExit();
        window.removeEventListener('resize', onResize);
        ro.disconnect();
        window.gewu.terminal.kill({ tabId: id });
      };
      term.focus();
    })();

    return () => {
      disposed = true;
      const t = termRef.current;
      if (t) {
        const cleanup = (t as unknown as { _gewuCleanup?: () => void })._gewuCleanup;
        if (cleanup) cleanup();
        t.dispose();
      }
      termRef.current = null;
    };
  }, [cwd]);

  return (
    <div ref={hostRef} style={{ height, width: '100%' }}
      className="terminal-host border-t" />
  );
}
