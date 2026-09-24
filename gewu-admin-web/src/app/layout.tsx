import type { Metadata } from 'next';
import { Providers } from '@/store/Providers';
import { ToastProvider } from '@/components/ui/Toast';
import ThemeSync from '@/components/ThemeSync';
import ErrorBoundary from '@/components/ErrorBoundary';
import './globals.css';

export const metadata: Metadata = {
  title: '格物致虚 · AI 智能协作平台',
  description: 'AI 智能协作平台 - 流式对话、智能体协作、项目管理',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" className="dark">
      <head>
        <link href="https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@400;500;600;700;900&family=Noto+Sans+SC:wght@300;400;500;600;700&family=ZCOOL+XiaoWei&display=swap" rel="stylesheet" />
      </head>
      <body>
        <Providers>
          <ThemeSync />
          <ToastProvider>
            <ErrorBoundary>{children}</ErrorBoundary>
          </ToastProvider>
        </Providers>
      </body>
    </html>
  );
}
