'use client';
import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Check, Copy } from 'lucide-react';

/** 代码块组件的 props */
interface CodeBlockProps {
  language: string;
  value: string;
}

/** 复制按钮状态 */
function CopyButton({ code }: { code: string }) {
  const [copied, setCopied] = React.useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // fallback
      const textarea = document.createElement('textarea');
      textarea.value = code;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <button
      onClick={handleCopy}
      className="absolute top-2 right-2 p-1.5 rounded-md text-ink-500 hover:text-ink-100 hover:bg-ink-800/50 transition-all"
      title={copied ? '已复制' : '复制代码'}
    >
      {copied ? <Check className="w-3.5 h-3.5 text-green-400" /> : <Copy className="w-3.5 h-3.5" />}
    </button>
  );
}

/** 内联代码渲染器 */
function InlineCode({ children }: { children: React.ReactNode }) {
  return (
    <code className="px-1.5 py-0.5 mx-0.5 rounded-md bg-ink-800/60 text-tech-300 text-[0.85em] font-mono border border-tech-500/10">
      {children}
    </code>
  );
}

/** 代码块渲染器（带语法高亮和复制按钮） */
function CodeBlock({ language, value }: CodeBlockProps) {
  const lineCount = value.split('\n').length;
  return (
    <div className="relative group my-3 rounded-xl overflow-hidden border border-tech-500/10">
      {/* 头部：语言标签 + 复制按钮 */}
      <div className="flex items-center justify-between px-4 py-2 bg-ink-900/80 border-b border-tech-500/10">
        <span className="text-[11px] font-mono text-ink-400 uppercase tracking-wider">
          {language || 'text'}
        </span>
        <CopyButton code={value} />
      </div>
      {/* 代码主体 - 固定最大高度，超出滚动 */}
      <div className="overflow-auto" style={{ maxHeight: '400px' }}>
        <SyntaxHighlighter
          language={language || 'text'}
          style={vscDarkPlus}
          customStyle={{
            margin: 0,
            padding: '16px',
            background: 'rgba(8,18,17,0.6)',
            fontSize: '13px',
            lineHeight: '1.6',
            borderRadius: 0,
          }}
          codeTagProps={{
            style: {
              fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace",
            },
          }}
          showLineNumbers={lineCount > 3}
          lineNumberStyle={{
            minWidth: '2.5em',
            paddingRight: '1em',
            color: 'rgba(255,255,255,0.15)',
            userSelect: 'none',
          }}
        >
          {value}
        </SyntaxHighlighter>
      </div>
    </div>
  );
}

/** MarkdownRenderer 的 props */
interface MarkdownRendererProps {
  content: string;
  /** 是否为流式渲染中（实时输出时不完全的 Markdown 也能正常渲染） */
  isStreaming?: boolean;
}

/**
 * Markdown 渲染组件。
 * 支持代码语法高亮、表格、列表、引用等 GFM 格式，代码块支持一键复制。
 * 同时适用于完整的 AI 回复和流式实时输出场景。
 */
export default function MarkdownRenderer({ content, isStreaming }: MarkdownRendererProps) {
  // 在流式输出时，确保内容不为空时也能渲染
  const safeContent = useMemo(() => {
    if (!content || content.trim().length === 0) return '';
    return content;
  }, [content]);

  if (!safeContent && !isStreaming) return null;
  if (!safeContent && isStreaming) {
    return (
      <span className="inline-block w-1.5 h-4 bg-tech-400 ml-0.5 animate-pulse align-middle" />
    );
  }

  return (
    <div className="prose prose-invert max-w-none text-sm text-ink-100 leading-relaxed">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // 代码块
          code({ className, children }) {
            const match = /language-(\w+)/.exec(className || '');
            const isInline = !match && !String(children).includes('\n');

            if (isInline) {
              return <InlineCode>{children}</InlineCode>;
            }

            return (
              <CodeBlock
                language={match ? match[1] : ''}
                value={String(children).replace(/\n$/, '')}
              />
            );
          },
          // 标题
          h1({ children }) {
            return <h1 className="text-xl font-bold text-ink-50 mt-6 mb-3 pb-2 border-b border-tech-500/10">{children}</h1>;
          },
          h2({ children }) {
            return <h2 className="text-lg font-semibold text-ink-100 mt-5 mb-2">{children}</h2>;
          },
          h3({ children }) {
            return <h3 className="text-base font-medium text-ink-200 mt-4 mb-1.5">{children}</h3>;
          },
          // 列表
          ul({ children }) {
            return <ul className="list-disc list-inside space-y-1 my-2 text-ink-200">{children}</ul>;
          },
          ol({ children }) {
            return <ol className="list-decimal list-inside space-y-1 my-2 text-ink-200">{children}</ol>;
          },
          // 表格
          table({ children }) {
            return (
              <div className="overflow-auto my-3 rounded-lg border border-tech-500/10" style={{ maxHeight: '400px' }}>
                <table className="min-w-full text-sm">{children}</table>
              </div>
            );
          },
          thead({ children }) {
            return <thead className="bg-ink-800/40">{children}</thead>;
          },
          th({ children }) {
            return <th className="px-4 py-2.5 text-left text-xs font-medium text-ink-300 uppercase tracking-wider">{children}</th>;
          },
          td({ children }) {
            return <td className="px-4 py-2 border-t border-tech-500/5 text-ink-200">{children}</td>;
          },
          // 引用块
          blockquote({ children }) {
            return (
              <blockquote className="border-l-2 border-tech-500/30 pl-4 my-3 italic text-ink-400 bg-tech-500/5 py-2 pr-3 rounded-r-lg">
                {children}
              </blockquote>
            );
          },
          // 链接
          a({ children, href }) {
            return (
              <a href={href} target="_blank" rel="noopener noreferrer" className="text-tech-400 hover:text-tech-300 underline underline-offset-2 transition-colors">
                {children}
              </a>
            );
          },
          // 段落
          p({ children }) {
            return <p className="my-2 leading-relaxed">{children}</p>;
          },
          // 水平线
          hr() {
            return <hr className="my-4 border-tech-500/10" />;
          },
          // 强调
          strong({ children }) {
            return <strong className="font-semibold text-ink-100">{children}</strong>;
          },
          em({ children }) {
            return <em className="italic text-ink-300">{children}</em>;
          },
          // 删除线
          del({ children }) {
            return <del className="line-through text-ink-500">{children}</del>;
          },
        }}
      >
        {safeContent}
      </ReactMarkdown>
      {/* 流式输出光标 */}
      {isStreaming && (
        <span className="inline-block w-1.5 h-4 bg-tech-400 ml-0.5 animate-pulse align-middle" />
      )}
    </div>
  );
}
