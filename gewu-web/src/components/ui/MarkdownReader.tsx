'use client';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';

/**
 * Markdown 阅读模式：渲染后的富文本视图。
 * 复用会话消息的 MarkdownRenderer（GFM/表格/代码块高亮/复制），
 * 仅提供适度行宽的阅读版式；滚动容器由调用方控制。
 */
export default function MarkdownReader({ content, className = '' }: {
  content: string;
  /** 附加到根容器的类（如限定高度） */
  className?: string;
}) {
  return (
    <div className={`min-w-0 ${className}`}>
      <div className="max-w-3xl mx-auto px-6 py-4">
        <MarkdownRenderer content={content} />
      </div>
    </div>
  );
}
