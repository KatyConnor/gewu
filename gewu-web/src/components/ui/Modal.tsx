'use client';
import { useEffect, useRef, useState } from 'react';

/**
 * 全站统一弹框组件（2026-10-25 升级：替代散落的 fixed inset-0 手写遮罩）.
 *
 * 行为约定：
 * - 点击遮罩不关闭——仅 ESC / 关闭图标 / 业务按钮调用 onClose 关闭；
 * - ESC 默认可关（closeOnEsc=false 用于强确认类，如 AskUserDialog）；
 * - 打开时焦点圈禁在面板内，关闭后归还焦点至触发元素；
 * - 进场 200ms / 退场 150ms 动画，仅用 opacity+transform，respect prefers-reduced-motion。
 */
export interface ModalProps {
  open: boolean;
  onClose: () => void;
  children: React.ReactNode;
  /** 面板类（宽度/内边距/圆角/滚动），默认对齐全站最常见形态 */
  panelClassName?: string;
  /** 遮罩类，默认半透明黑 */
  backdropClassName?: string;
  /** 层级类，默认 z-50（强确认框可用 z-[80]） */
  zIndex?: string;
  /** 布局形态：center=居中弹框，right=右侧抽屉（h-full 靠右、水平滑入） */
  variant?: 'center' | 'right';
  /** ESC 是否触发 onClose */
  closeOnEsc?: boolean;
  /** 无障碍名称（面板无可视标题时建议传入） */
  ariaLabel?: string;
}

const EXIT_MS = 150;

export default function Modal({
  open,
  onClose,
  children,
  panelClassName = 'glass-dark rounded-xl p-5 w-full max-w-xl border border-tech-500/20 max-h-[80vh] overflow-y-auto',
  backdropClassName = 'bg-black/50',
  zIndex = 'z-50',
  variant = 'center',
  closeOnEsc = true,
  ariaLabel,
}: ModalProps) {
  // rendered：面板是否挂载（含退场动画期）；closing：退场动画进行中
  const [rendered, setRendered] = useState(open);
  const [closing, setClosing] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const prevFocusRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  // 开→挂载+进场；关→先播退场动画再卸载（定时器仅依赖 rendered 变化重置，不会被 closing 触发清掉）
  useEffect(() => {
    if (open) {
      setClosing(false);
      setRendered(true);
      return;
    }
    if (!rendered) return;
    setClosing(true);
    const t = setTimeout(() => {
      setClosing(false);
      setRendered(false);
    }, EXIT_MS);
    return () => clearTimeout(t);
  }, [open, rendered]);

  // ESC 关闭 + Tab 焦点圈禁 + 打开即聚焦面板 + 关闭归还焦点
  useEffect(() => {
    if (!rendered || closing) return;
    const panel = panelRef.current;
    if (!panel) return;
    prevFocusRef.current = document.activeElement as HTMLElement | null;
    panel.focus();

    const focusables = (): HTMLElement[] =>
      Array.from(panel.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      )).filter(el => !el.hasAttribute('disabled') && el.offsetParent !== null);

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && closeOnEsc) {
        e.preventDefault();
        onCloseRef.current();
        return;
      }
      if (e.key !== 'Tab') return;
      const list = focusables();
      if (list.length === 0) { e.preventDefault(); return; }
      const first = list[0];
      const last = list[list.length - 1];
      const active = document.activeElement as HTMLElement;
      if (e.shiftKey && (active === first || !panel.contains(active))) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && (active === last || !panel.contains(active))) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      if (prevFocusRef.current && document.contains(prevFocusRef.current)) prevFocusRef.current.focus();
    };
  }, [rendered, closing, closeOnEsc]);

  if (!rendered) return null;

  const closingCls = closing ? ' modal-closing' : '';
  const containerCls = variant === 'right'
    ? `fixed inset-0 ${zIndex} flex justify-end`
    : `fixed inset-0 ${zIndex} flex items-center justify-center`;

  return (
    <div className={`${containerCls} ${backdropClassName} modal-backdrop${closingCls}`}>
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        tabIndex={-1}
        data-variant={variant}
        className={`modal-panel${closingCls} outline-none ${panelClassName}`}
      >
        {children}
      </div>
    </div>
  );
}
