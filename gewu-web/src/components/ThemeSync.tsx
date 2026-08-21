'use client';
import { useEffect } from 'react';
import { useSelector } from 'react-redux';
import { RootState } from '@/store';

// 主题色映射 - 用于动态修改 Tailwind ink 颜色
const themeInkColors: Record<string, Record<string, string>> = {
  ink: {
    50: '#f5f7f6', 100: '#dde6e2', 200: '#b8ccc4', 300: '#8ba89d',
    400: '#5d8076', 500: '#3d6359', 600: '#2d4d45', 700: '#1f3833',
    800: '#152826', 900: '#0e1c1b', 950: '#081211',
  },
  deepsea: {
    50: '#f0f7ff', 100: '#d4e6ff', 200: '#a8c8e8', 300: '#7ba8cc',
    400: '#4d88b0', 500: '#2a6090', 600: '#1e4d70', 700: '#163d5b',
    800: '#0e2840', 900: '#0a1c30', 950: '#061018',
  },
  jade: {
    50: '#0f172a', 100: '#1e293b', 200: '#334155', 300: '#475569',
    400: '#64748b', 500: '#94a3b8', 600: '#cbd5e1', 700: '#e2e8f0',
    800: '#f1f5f9', 900: '#f8fafc', 950: '#ffffff',
  },
  celadon: {
    50: '#222b32', 100: '#36434e', 200: '#475c6b', 300: '#567081',
    400: '#6b8a9b', 500: '#8ea9b8', 600: '#bccdd8', 700: '#dce5eb',
    800: '#eef3f6', 900: '#f8fafb', 950: '#ffffff',
  },
};

export default function ThemeSync() {
  const theme = useSelector((s: RootState) => s.app.theme);

  useEffect(() => {
    const root = document.documentElement;
    if (theme) {
      root.setAttribute('data-theme', theme);
    } else {
      root.removeAttribute('data-theme');
    }

    // 动态修改 CSS 变量
    const colors = themeInkColors[theme] || themeInkColors.ink;
    Object.entries(colors).forEach(([key, value]) => {
      root.style.setProperty(`--tw-${key}`, value);
    });
  }, [theme]);

  return null;
}
