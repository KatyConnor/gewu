'use client';
import { useState, useRef, useEffect, useCallback } from 'react';
import { ChevronDown } from 'lucide-react';

interface SelectOption { value: string; label: string }

interface CustomSelectProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  className?: string;
}

export default function CustomSelect({ value, onChange, options, className = '' }: CustomSelectProps) {
  const [open, setOpen] = useState(false);
  const [dropUp, setDropUp] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const currentLabel = options.find(o => o.value === value)?.label || options[0]?.label || '';

  // 判断展开方向：如果按钮距离视口底部 < 200px，则向上展开
  const checkDirection = useCallback(() => {
    if (!ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    setDropUp(spaceBelow < 200);
  }, []);

  const toggleOpen = useCallback(() => {
    if (!open) checkDirection();
    setOpen(prev => !prev);
  }, [open, checkDirection]);

  const handleClickOutside = useCallback((e: MouseEvent) => {
    if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
  }, []);

  useEffect(() => {
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [handleClickOutside]);

  return (
    <div ref={ref} className={`relative ${className}`}>
      <button type="button" onClick={toggleOpen}
        className="w-full flex items-center justify-between gap-2 px-3 py-1.5 text-sm rounded-lg border transition-colors text-ink-300"
        style={{ background: 'rgba(14,28,27,0.6)', borderColor: 'rgba(0,184,148,0.1)' }}>
        <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '6em' }}>{currentLabel}</span>
        <ChevronDown className={`w-3.5 h-3.5 text-ink-500 transition-transform flex-shrink-0 ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <div
          className={`absolute rounded-lg border shadow-xl z-50 overflow-hidden ${dropUp ? 'bottom-full mb-1 right-0' : 'top-full mt-1 right-0'}`}
          style={{ background: '#0e1c1b', borderColor: 'rgba(0,184,148,0.15)', boxShadow: '0 8px 32px rgba(0,0,0,0.4), 0 0 0 1px rgba(0,184,148,0.1)', maxHeight: '200px', overflowY: 'auto', minWidth: '100%', width: 'max-content', maxWidth: '12em' }}>
          {options.map(opt => (
            <button key={opt.value} type="button" onClick={() => { onChange(opt.value); setOpen(false); }}
              className={`w-full text-left px-3 py-2 text-sm transition-colors ${value === opt.value ? 'text-tech-400 bg-tech-500/10' : 'text-ink-300 hover:bg-tech-500/5 hover:text-ink-100'}`}
              style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {opt.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
