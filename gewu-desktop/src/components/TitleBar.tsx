/**
 * 自绘标题栏（无边框窗口）：左品牌角标（不显示文字标题——用户指定），右视图动作图标 + 窗口控制三键。
 * 整条为拖拽区（-webkit-app-region: drag），控件 no-drag；双击空白切换最大化。
 * actions：视图级动作图标槽（编排/设置/终端——hover 原生 title 中文提示）。
 */
import { useEffect, useState, type ReactNode } from 'react';
import GewuLogo from './GewuLogo';

export default function TitleBar({ actions }: { actions?: ReactNode }) {
  const [maximized, setMaximized] = useState(false);
  useEffect(() => {
    let alive = true;
    window.gewu.win.isMaximized().then((m) => { if (alive) setMaximized(m); }).catch(() => {});
    const off = window.gewu.win.onState((s) => setMaximized(s.maximized));
    return () => { alive = false; off(); };
  }, []);

  return (
    <div className="titlebar" onDoubleClick={() => window.gewu.win.maximizeToggle()}>
      <div className="titlebar-brand">
        <span className="titlebar-mark"><GewuLogo size={22} tagline={false} /></span>
      </div>
      {actions != null && <div className="titlebar-actions">{actions}</div>}
      <div className="titlebar-controls" onDoubleClick={(e) => e.stopPropagation()}>
        <button className="tbtn" title="最小化" aria-label="最小化"
          onClick={() => window.gewu.win.minimize()}>
          <svg width="10" height="10" viewBox="0 0 10 10"><rect x="0" y="4.5" width="10" height="1" fill="currentColor" /></svg>
        </button>
        <button className="tbtn" title={maximized ? '还原' : '最大化'} aria-label={maximized ? '还原' : '最大化'}
          onClick={() => window.gewu.win.maximizeToggle()}>
          {maximized ? (
            <svg width="10" height="10" viewBox="0 0 10 10">
              <rect x="0.5" y="2.5" width="7" height="7" fill="none" stroke="currentColor" />
              <path d="M2.5 2.5 V0.5 H9.5 V7.5 H7.5" fill="none" stroke="currentColor" />
            </svg>
          ) : (
            <svg width="10" height="10" viewBox="0 0 10 10">
              <rect x="0.5" y="0.5" width="9" height="9" fill="none" stroke="currentColor" />
            </svg>
          )}
        </button>
        <button className="tbtn close" title="关闭" aria-label="关闭"
          onClick={() => window.gewu.win.close()}>
          <svg width="10" height="10" viewBox="0 0 10 10">
            <path d="M0.5 0.5 L9.5 9.5 M9.5 0.5 L0.5 9.5" stroke="currentColor" />
          </svg>
        </button>
      </div>
    </div>
  );
}
