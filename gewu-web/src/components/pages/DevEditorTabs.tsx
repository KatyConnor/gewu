'use client';
// IDEA 式编辑器标签栏（多 Tab 编辑 · 结构改造 Phase 1）。
// 行为对齐 IDEA：未保存显示圆点（hover 才露出关闭 ×）、预览 Tab 斜体、
// 双击=固定/取消固定、中键=关闭、右键菜单（关闭/关闭其他/关闭全部/固定/打开为普通 Tab/分屏）。
// 固定（pinned）Tab 不受「关闭其他/全部」影响、不显示关闭按钮，与 IDEA 一致。
import { useEffect, useRef, useState } from 'react';
import { Pin, X } from 'lucide-react';

export default function DevEditorTabs({
  tabs,
  activeKey,
  isSecondary,
  hasSplit,
  onActivate,
  onActivatePreview,
  onClose,
  onCloseOthers,
  onCloseAll,
  onTogglePin,
  onOpenNormal,
  onSplit,
  onMoveBack,
}: {
  /** Tab 元数据（path/title/dirty/pinned/preview 由上层 EditorTab 投影） */
  tabs: Array<{ path: string; title: string; dirty: boolean; pinned: boolean; preview: boolean }>;
  activeKey: string;
  /** 副编辑组（标题左侧标注，区分主/副组） */
  isSecondary?: boolean;
  /** 当前是否处于分屏态（控制「移到右侧分屏」菜单项显隐） */
  hasSplit: boolean;
  onActivate: (path: string) => void;
  /** 激活预览 Tab 并转正（IDEA：再点文件树同一文件转正） */
  onActivatePreview: (path: string) => void;
  onClose: (path: string) => void;
  onCloseOthers: (path: string) => void;
  onCloseAll: () => void;
  onTogglePin: (path: string) => void;
  /** 预览 Tab 转正（右键菜单项） */
  onOpenNormal: (path: string) => void;
  /** 移到右侧/下方分屏（仅主组菜单出现） */
  onSplit: (path: string, dir: 'right' | 'down') => void;
  /** 副组 Tab 移回主编辑区 */
  onMoveBack: (path: string) => void;
}) {
  const [menu, setMenu] = useState<{ x: number; y: number; path: string } | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // 激活 Tab 滚动到可视区（切换/新开时 IDEA 会把目标 Tab 带入视野）
  useEffect(() => {
    if (!activeKey || !scrollRef.current) return;
    const el = scrollRef.current.querySelector<HTMLElement>(`[data-tab-path="${CSS.escape(activeKey)}"]`);
    el?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [activeKey, tabs.length]);

  const menuTab = menu ? tabs.find(t => t.path === menu.path) : null;

  return (
    <div className="relative flex-shrink-0">
      <div ref={scrollRef}
        className="flex items-stretch overflow-x-auto scrollbar-thin border-b border-tech-500/10 bg-ink-900/30"
        style={{ height: 34 }}>
        {isSecondary && (
          <span className="flex items-center px-2 text-[10px] text-gold-400/80 border-r border-tech-500/10 whitespace-nowrap">分屏</span>
        )}
        {tabs.length === 0 && (
          <div className="flex items-center px-3 text-[11px] text-ink-600 select-none">{isSecondary ? '（空）' : '无打开文件'}</div>
        )}
        {tabs.map(t => {
          const active = t.path === activeKey;
          return (
            <div key={t.path}
              data-tab-path={t.path}
              onClick={() => (active && t.preview ? onActivatePreview(t.path) : onActivate(t.path))}
              onAuxClick={(e) => { if (e.button === 1 && !t.pinned) { e.preventDefault(); onClose(t.path); } }}
              onDoubleClick={() => onTogglePin(t.path)}
              onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); setMenu({ x: e.clientX, y: e.clientY, path: t.path }); }}
              title={t.preview ? `${t.title}（预览 Tab：编辑时自动转正）` : t.path}
              className={`group flex items-center gap-1.5 pl-3 pr-1 text-xs whitespace-nowrap border-r border-t-2 border-r-tech-500/10 cursor-pointer select-none max-w-[240px] transition-colors ${
                active
                  ? 'bg-tech-500/10 text-tech-300 border-t-tech-400'
                  : 'text-ink-400 hover:bg-tech-500/5 hover:text-ink-200 border-t-transparent'
              }`}>
              {t.pinned && <Pin className="w-3 h-3 flex-shrink-0 text-gold-400" />}
              <span className={`truncate ${t.preview ? 'italic text-ink-400' : ''}`}>{t.title}</span>
              {t.pinned ? (
                <span className="w-5 flex-shrink-0" />
              ) : (
                <button onClick={(e) => { e.stopPropagation(); onClose(t.path); }}
                  className="w-5 h-5 flex-shrink-0 flex items-center justify-center rounded hover:bg-ink-700/60"
                  title={t.dirty ? '关闭（有未保存修改将确认）' : '关闭（Ctrl+F4）'}>
                  {t.dirty
                    ? <span className="w-1.5 h-1.5 rounded-full bg-amber-400 group-hover:hidden" />
                    : <X className="w-3 h-3 opacity-60 group-hover:opacity-100" />}
                  {t.dirty && <X className="w-3 h-3 hidden group-hover:block opacity-80" />}
                </button>
              )}
            </div>
          );
        })}
      </div>

      {/* Tab 右键菜单（IDEA 语义） */}
      {menu && menuTab && (
        <>
          <div className="fixed inset-0 z-[85]" onClick={() => setMenu(null)} onContextMenu={e => { e.preventDefault(); setMenu(null); }} />
          <div className="fixed z-[86] min-w-[190px] glass-dark rounded-lg border border-tech-500/15 shadow-2xl py-1 text-xs"
            style={{ left: Math.min(menu.x, window.innerWidth - 210), top: Math.min(menu.y, window.innerHeight - 260) }}>
            {menuTab.preview && (
              <button onClick={() => { onOpenNormal(menu.path); setMenu(null); }}
                className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">打开为普通 Tab</button>
            )}
            <button onClick={() => { onTogglePin(menu.path); setMenu(null); }}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">
              {menuTab.pinned ? '取消固定' : '固定 Tab'}
            </button>
            <div className="h-px bg-tech-500/10 my-1" />
            <button onClick={() => { onClose(menu.path); setMenu(null); }} disabled={menuTab.pinned}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all disabled:opacity-40 disabled:pointer-events-none">关闭</button>
            <button onClick={() => { onCloseOthers(menu.path); setMenu(null); }}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">关闭其他</button>
            <button onClick={() => { onCloseAll(); setMenu(null); }}
              className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">关闭全部</button>
            <div className="h-px bg-tech-500/10 my-1" />
            {!isSecondary && !hasSplit && (
              <>
                <button onClick={() => { onSplit(menu.path, 'right'); setMenu(null); }}
                  className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">移到右侧分屏</button>
                <button onClick={() => { onSplit(menu.path, 'down'); setMenu(null); }}
                  className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">移到下方分屏</button>
              </>
            )}
            {isSecondary && (
              <button onClick={() => { onMoveBack(menu.path); setMenu(null); }}
                className="w-full text-left px-3 py-1.5 text-ink-300 hover:text-ink-100 hover:bg-tech-500/10 transition-all">移回主编辑区</button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
