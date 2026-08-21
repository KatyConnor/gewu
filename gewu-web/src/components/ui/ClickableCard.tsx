'use client';
import { KeyboardEvent, ReactNode } from 'react';

interface ClickableCardProps {
  children: ReactNode;
  onClick?: () => void;
  className?: string;
}

export function ClickableCard({ children, onClick, className = '' }: ClickableCardProps) {
  const handleKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick?.(); }
  };
  return (
    <div role="button" tabIndex={0} onClick={onClick} onKeyDown={handleKeyDown} className={className}>
      {children}
    </div>
  );
}
