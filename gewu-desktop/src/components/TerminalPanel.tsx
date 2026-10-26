import { useCallback, useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';

/**
 * 终端面板（D1-27 多 Tab 完整化）：xterm.js ↔ 主进程 PTY 会话池。
 * - 多标签：每 Tab 独立 xterm 实例 + 独立 PTY（TerminalManager 池本就按 tabId 隔离）；
 *   切换仅显隐 host（PTY 与缓冲保持存活），关闭 Tab 杀对应 PTY；
 * - 全局 onData/onExit 订阅按 ptyId 分发；create 返回前到达的早期输出进
 *   earlyBuffer，拿到 ptyId 后回放（单 Tab 版实测教训：提示符在 spawn 瞬间发出）；
 * - cwd 快照：每个 Tab 创建时取当下会话工作目录；关闭全部 Tab 时通知父级收起抽屉。
 */
interface TabMeta {
  key: number;
  label: string;
  exited: boolean;
}
interface TermRec {
  term: Terminal;
  fit: FitAddon;
  ptyId: string | null;
  ro?: ResizeObserver;
}

export default function TerminalPanel({ cwd, height = 220, visible = true, onAllClosed }: {
  cwd?: string;
  height?: number;
  /** 收起=CSS 隐藏（PTY 与 xterm 缓冲保持存活，重开即恢复——用户实测收起重开丢内容） */
  visible?: boolean;
  onAllClosed?: () => void;
}) {
  const [tabs, setTabs] = useState<TabMeta[]>([]);
  const [active, setActive] = useState<number | null>(null);
  const seqRef = useRef(0);
  const autoCreatedRef = useRef(false);
  const hostsRef = useRef(new Map<number, HTMLDivElement>());
  const termsRef = useRef(new Map<number, TermRec>());
  const earlyRef = useRef<Array<{ tabId: string; data: string }>>([]);
  const cwdRef = useRef(cwd);
  cwdRef.current = cwd;
  const activeRef = useRef<number | null>(null);
  activeRef.current = active;
  const tabsRef = useRef<TabMeta[]>([]);
  tabsRef.current = tabs;

  // ===== 全局数据分发（挂载一次）：按 ptyId 路由；无主事件进早期缓冲 =====
  useEffect(() => {
    const offData = window.gewu.terminal.onData(({ tabId, data }) => {
      for (const rec of termsRef.current.values()) {
        if (rec.ptyId === tabId) {
          rec.term.write(data);
          return;
        }
      }
      earlyRef.current.push({ tabId, data });
      if (earlyRef.current.length > 300) earlyRef.current.splice(0, earlyRef.current.length - 300);
    });
    const offExit = window.gewu.terminal.onExit(({ tabId }) => {
      for (const [key, rec] of termsRef.current) {
        if (rec.ptyId === tabId) {
          rec.term.writeln('\r\n[会话已退出]');
          setTabs((prev) => prev.map((x) => (x.key === key ? { ...x, exited: true } : x)));
          return;
        }
      }
    });
    const onWinResize = () => {
      const key = activeRef.current;
      if (key == null) return;
      const rec = termsRef.current.get(key);
      if (rec) { try { rec.fit.fit(); } catch { /* 隐藏态 */ } }
    };
    window.addEventListener('resize', onWinResize);
    return () => {
      offData(); offExit();
      window.removeEventListener('resize', onWinResize);
    };
  }, []);

  // ===== 卸载清理：杀全部 PTY + 释放 xterm =====
  useEffect(() => () => {
    for (const rec of termsRef.current.values()) {
      rec.ro?.disconnect();
      if (rec.ptyId) window.gewu.terminal.kill({ tabId: rec.ptyId });
      rec.term.dispose();
    }
    termsRef.current.clear();
  }, []);

  const createTab = useCallback(() => {
    const key = ++seqRef.current;
    const dir = cwdRef.current;
    const label = dir ? `${dir.replace(/[\\/]+$/, '').split(/[\\/]/).filter(Boolean).pop() || 'workspace'}` : `终端 ${key}`;
    setTabs((prev) => [...prev, { key, label, exited: false }]);
    setActive(key);
  }, []);

  const closeTab = useCallback((key: number) => {
    const rec = termsRef.current.get(key);
    if (rec) {
      rec.ro?.disconnect();
      if (rec.ptyId) window.gewu.terminal.kill({ tabId: rec.ptyId });
      rec.term.dispose();
      termsRef.current.delete(key);
    }
    hostsRef.current.delete(key);
    const remaining = tabsRef.current.filter((x) => x.key !== key);
    setTabs(remaining);
    if (activeRef.current === key) {
      setActive(remaining.length ? remaining[remaining.length - 1].key : null);
      if (!remaining.length) onAllClosed?.();
    }
  }, [onAllClosed]);

  // ===== 首次展开自动建一个 Tab（点「终端」即得可用终端；常驻挂载后首开前不建） =====
  useEffect(() => {
    if (visible && !autoCreatedRef.current) {
      autoCreatedRef.current = true;
      createTab();
    }
  }, [visible, createTab]);

  // ===== 重新显示：等布局恢复后重算尺寸并聚焦（隐藏期间窗口可能已变化） =====
  useEffect(() => {
    if (!visible) return;
    const key = activeRef.current;
    if (key == null) return;
    const rec = termsRef.current.get(key);
    if (!rec) return;
    requestAnimationFrame(() => {
      try { rec.fit.fit(); } catch { /* 忽略 */ }
      rec.term.focus();
    });
  }, [visible]);

  // ===== 确保 Tab 的 xterm/PTY 就绪（host 挂载后创建；新增 Tab 触发） =====
  useEffect(() => {
    for (const t of tabsRef.current) {
      if (termsRef.current.has(t.key)) continue;
      const host = hostsRef.current.get(t.key);
      if (!host) continue;
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
      term.open(host);
      // 键盘输入 → PTY（ptyId 就绪前按键丢弃，创建在百毫秒内完成）
      term.onData((d) => {
        const rec = termsRef.current.get(t.key);
        if (rec?.ptyId) window.gewu.terminal.write({ tabId: rec.ptyId, data: d });
      });
      term.onResize(({ cols, rows }) => {
        const rec = termsRef.current.get(t.key);
        if (rec?.ptyId) window.gewu.terminal.resize({ tabId: rec.ptyId, cols, rows });
      });
      const ro = new ResizeObserver(() => {
        const rec = termsRef.current.get(t.key);
        if (rec && activeRef.current === t.key) { try { rec.fit.fit(); } catch { /* 隐藏态 */ } }
      });
      ro.observe(host);
      termsRef.current.set(t.key, { term, fit, ptyId: null, ro });

      // 容器尺寸就绪后创建 PTY（fit 计算需要实际像素）
      setTimeout(() => {
        const rec = termsRef.current.get(t.key);
        if (!rec) return; // 期间已被关闭
        const cols = Math.max(20, Math.floor(host.clientWidth / 9));
        const rows = Math.max(8, Math.floor((host.clientHeight - 8) / 18));
        window.gewu.terminal.create({ cwd: cwdRef.current, cols, rows }).then(({ tabId: id }) => {
          const cur = termsRef.current.get(t.key);
          if (!cur) { window.gewu.terminal.kill({ tabId: id }); return; }
          cur.ptyId = id;
          // 回放创建完成前到达的早期输出（含完整提示符）
          const mine = earlyRef.current.filter((e) => e.tabId === id);
          earlyRef.current = earlyRef.current.filter((e) => e.tabId !== id);
          mine.forEach((e) => cur.term.write(e.data));
          if (activeRef.current === t.key) {
            try { cur.fit.fit(); } catch { /* 忽略 */ }
            cur.term.focus();
          }
        }).catch((e: unknown) => {
          term.writeln(`\r\n[终端创建失败] ${e instanceof Error ? e.message : String(e)}`);
        });
      }, 80);
    }
    // 激活 Tab 适配 + 聚焦
    const key = activeRef.current;
    if (key != null) {
      const rec = termsRef.current.get(key);
      if (rec) {
        try { rec.fit.fit(); } catch { /* 忽略 */ }
        rec.term.focus();
      }
    }
  }, [tabs, active]);

  return (
    <div style={{ flexShrink: 0, display: visible ? undefined : 'none' }}>
      <div className="term-tabs">
        {tabs.map((t) => (
          <div key={t.key}
            className={`term-tab ${active === t.key ? 'active' : ''} ${t.exited ? 'exited' : ''}`}
            onClick={() => setActive(t.key)}>
            <span className="term-tab-label">{t.label}{t.exited ? ' · 已退出' : ''}</span>
            <button className="term-tab-close" title="关闭此终端"
              onClick={(e) => { e.stopPropagation(); closeTab(t.key); }}>×</button>
          </div>
        ))}
        <button className="term-tab-add" title="新建终端标签" onClick={createTab}>＋</button>
      </div>
      {tabs.map((t) => (
        <div key={t.key}
          ref={(el) => { if (el) hostsRef.current.set(t.key, el); else hostsRef.current.delete(t.key); }}
          className="terminal-host border-t"
          style={{ height, width: '100%', display: active === t.key ? 'block' : 'none' }} />
      ))}
    </div>
  );
}
