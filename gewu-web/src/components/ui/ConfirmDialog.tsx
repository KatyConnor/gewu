'use client';
import { createContext, useCallback, useContext, useEffect, useRef, useState, ReactNode } from 'react';
import Modal from './Modal';

/**
 * 全站统一确认对话框（替代 window.confirm/浏览器原生提示框）.
 *
 * 用法：`const ok = await confirmDialog({ message: '确认删除？', danger: true });`
 * - promise 语义与 window.confirm 对齐（true=确认 / false=取消），调用点只需加 await；
 * - 危险操作（danger）确认按钮用 cinnabar 实心并默认聚焦「取消」，防误触；
 * - 基于 Modal：ESC=取消、焦点圈禁、关闭归还焦点、退场动画；
 * - 未挂 Provider 时安全回落 false（不抛错）。
 */
export interface ConfirmOptions {
  /** 标题（默认：危险=「危险操作」，普通=「操作确认」） */
  title?: string;
  /** 正文，支持 \n 换行 */
  message: ReactNode;
  /** 确认按钮文案（默认「确定」） */
  confirmText?: string;
  /** 取消按钮文案（默认「取消」） */
  cancelText?: string;
  /** 破坏性操作：确认按钮转 cinnabar 实心，默认聚焦取消按钮 */
  danger?: boolean;
}

interface PendingItem { id: number; opts: ConfirmOptions; resolve: (ok: boolean) => void; }

const ConfirmContext = createContext<(opts: ConfirmOptions) => Promise<boolean>>(async () => false);

/** 模块级桥：任意组件无需挂 hook 即可调用（ConfirmProvider 挂载时注册分发器） */
let dispatcher: ((opts: ConfirmOptions) => Promise<boolean>) | null = null;

export function confirmDialog(opts: ConfirmOptions): Promise<boolean> {
  return dispatcher ? dispatcher(opts) : Promise.resolve(false);
}

export function useConfirm() { return useContext(ConfirmContext); }

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [queue, setQueue] = useState<PendingItem[]>([]);
  const [display, setDisplay] = useState<PendingItem | null>(null);
  const idRef = useRef(0);
  const btnRef = useRef<HTMLButtonElement>(null);

  const ask = useCallback((opts: ConfirmOptions) => new Promise<boolean>(resolve => {
    const item: PendingItem = { id: ++idRef.current, opts, resolve };
    setQueue(prev => [...prev, item]);
  }), []);

  // 模块桥注册（effect 内赋值，规避渲染期副作用）
  useEffect(() => {
    dispatcher = ask;
    return () => { if (dispatcher === ask) dispatcher = null; };
  }, [ask]);

  // 展示项跟随队首；队列清空时保留旧值供 Modal 播退场动画
  useEffect(() => {
    if (queue[0]) setDisplay(queue[0]);
  }, [queue]);

  // Modal 打开后先聚焦面板，这里覆盖为聚焦默认按钮（危险→取消，普通→确定）
  useEffect(() => {
    if (queue.length === 0) return;
    const t = setTimeout(() => btnRef.current?.focus(), 0);
    return () => clearTimeout(t);
  }, [display, queue.length]);

  const settle = useCallback((item: PendingItem, ok: boolean) => {
    setQueue(prev => prev.filter(i => i !== item));
    item.resolve(ok);
  }, []);

  const open = queue.length > 0;
  const danger = !!display?.opts.danger;

  return (
    <ConfirmContext.Provider value={ask}>
      {children}
      <Modal
        open={open}
        onClose={() => display && settle(display, false)}
        zIndex="z-[80]"
        ariaLabel={display?.opts.title ?? (danger ? '危险操作确认' : '操作确认')}
        panelClassName="glass-dark rounded-xl w-full max-w-sm p-5 border border-tech-500/15 shadow-2xl"
      >
        {display && (
          <div key={display.id}>
            <h3 className="text-sm font-semibold text-ink-50 mb-2">
              {display.opts.title ?? (danger ? '危险操作' : '操作确认')}
            </h3>
            <div className="text-xs text-ink-400 leading-relaxed whitespace-pre-line mb-5">{display.opts.message}</div>
            <div className="flex justify-end gap-2">
              <button
                ref={danger ? btnRef : undefined}
                type="button"
                onClick={() => settle(display, false)}
                className="px-4 py-2 text-sm rounded-lg border border-tech-500/15 text-ink-300 hover:text-ink-100 hover:border-tech-500/30 transition-colors"
              >
                {display.opts.cancelText ?? '取消'}
              </button>
              <button
                ref={danger ? undefined : btnRef}
                type="button"
                onClick={() => settle(display, true)}
                className={`px-4 py-2 text-sm rounded-lg font-medium text-white transition-colors ${danger ? 'bg-cinnabar-500 hover:bg-cinnabar-600' : 'bg-tech-500 hover:bg-tech-600'}`}
              >
                {display.opts.confirmText ?? '确定'}
              </button>
            </div>
          </div>
        )}
      </Modal>
    </ConfirmContext.Provider>
  );
}
