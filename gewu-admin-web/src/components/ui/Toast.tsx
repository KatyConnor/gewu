'use client';
import { createContext, useContext, useState, useCallback, ReactNode } from 'react';
import { X, CheckCircle, AlertCircle, Info } from 'lucide-react';

interface ToastItem { id: string; message: string; type: 'success' | 'error' | 'info'; }

const ToastContext = createContext<(message: string, type?: 'success' | 'error' | 'info') => void>(() => {});

export function useToast() { return useContext(ToastContext); }

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const showToast = useCallback((message: string, type: 'success' | 'error' | 'info' = 'info') => {
    const id = Date.now().toString();
    setToasts(prev => {
      const next = [...prev, { id, message, type }];
      return next.length > 3 ? next.slice(-3) : next;
    });
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 3000);
  }, []);

  const icons = { success: CheckCircle, error: AlertCircle, info: Info };
  const colors = { success: 'border-tech-500/20 bg-tech-500/10 text-tech-400', error: 'border-danger-500/20 bg-danger-500/10 text-danger-400', info: 'border-tech-500/15 bg-tech-500/8 text-tech-400' };

  return (
    <ToastContext.Provider value={showToast}>
      {children}
      <div className="fixed top-4 right-4 z-[99999] space-y-2">
        {toasts.map(toast => {
          const Icon = icons[toast.type];
          return (
            <div key={toast.id} className={`animate-fade-up px-4 py-3 rounded-xl border ${colors[toast.type]} text-sm flex items-center gap-2 min-w-[200px] shadow-xl backdrop-blur-xl`}>
              <Icon className="w-4 h-4 flex-shrink-0" />
              <span className="flex-1">{toast.message}</span>
              <button onClick={() => setToasts(prev => prev.filter(t => t.id !== toast.id))} className="text-ink-500 hover:text-ink-300"><X className="w-3.5 h-3.5" /></button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}
