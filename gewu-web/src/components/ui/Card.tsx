'use client';
import { ReactNode } from 'react';

interface CardProps {
  children: ReactNode;
  className?: string;
  hover?: boolean;
  onClick?: () => void;
}

export function Card({ children, className = '', hover = false, onClick }: CardProps) {
  const baseClass = 'glass-dark rounded-xl p-6';
  const hoverClass = hover ? ' card-hover cursor-pointer' : '';
  const clickableClass = onClick ? ' cursor-pointer' : '';

  if (onClick) {
    return (
      <div
        role="button"
        tabIndex={0}
        className={`${baseClass}${hoverClass}${clickableClass} ${className}`}
        onClick={onClick}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
      >
        {children}
      </div>
    );
  }

  return <div className={`${baseClass}${hoverClass}${clickableClass} ${className}`}>{children}</div>;
}

export function CardHeader({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`flex items-center justify-between mb-4 ${className}`}>{children}</div>;
}

export function CardTitle({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <h3 className={`text-base font-semibold text-ink-50 ${className}`}>{children}</h3>;
}

export function CardDescription({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <p className={`text-ink-400 text-sm mt-1 ${className}`}>{children}</p>;
}

export function CardContent({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={className}>{children}</div>;
}
