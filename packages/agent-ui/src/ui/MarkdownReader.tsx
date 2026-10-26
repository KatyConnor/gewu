'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import MarkdownRenderer from './MarkdownRenderer';

interface TocItem {
  id: string;
  text: string;
  level: number;
}

/**
 * 从渲染后的 DOM 提取 h1-h4 并回写锚点 id。
 * 以渲染结果为准（而非再解析一遍 Markdown 源码），目录与正文天然一致，
 * 免去维护第二套 slug/去重规则；每次全量重建（重扫 DOM 幂等）。
 */
function collectHeadings(root: HTMLElement): TocItem[] {
  const els = root.querySelectorAll<HTMLElement>('h1, h2, h3, h4');
  const used = new Map<string, number>();
  const items: TocItem[] = [];
  els.forEach((el) => {
    const text = (el.textContent || '').trim();
    if (!text) return;
    const base = text
      .toLowerCase()
      // 保留 CJK 汉字与常规词字符作锚点（ES5 目标不能用 u 标志的 \p 转义）
      .replace(/[^\w\u4e00-\u9fa5\s-]/g, '')
      .replace(/\s+/g, '-') || 'heading';
    const n = used.get(base) ?? 0;
    used.set(base, n + 1);
    const id = n === 0 ? base : `${base}-${n}`;
    el.id = id;
    items.push({ id, text, level: Number(el.tagName.slice(1)) });
  });
  return items;
}

/**
 * Markdown 阅读模式：渲染后的富文本视图。
 * 复用会话消息的 MarkdownRenderer（GFM/表格/代码块高亮/复制），
 * 仅提供适度行宽的阅读版式；滚动容器由调用方控制。
 *
 * showToc：左右分栏——左侧标题导航（点击平滑跳转 + 滚动高亮当前章节），
 * 右侧正文内部滚动。此模式要求调用方给出确定高度（如 flex-1 min-h-0 容器）。
 */
export default function MarkdownReader({ content, className = '', showToc = false }: {
  content: string;
  /** 附加到根容器的类（如限定高度） */
  className?: string;
  /** 左右分栏阅读版式：左侧目录 / 右侧正文 */
  showToc?: boolean;
}) {
  const contentRef = useRef<HTMLDivElement>(null);
  const [toc, setToc] = useState<TocItem[]>([]);
  const [activeId, setActiveId] = useState('');

  // 目录与锚点：内容渲染完成后从 DOM 重建（content 变化即重扫）
  useEffect(() => {
    if (!showToc) return;
    const root = contentRef.current;
    if (!root) return;
    setToc(collectHeadings(root));
  }, [showToc, content]);

  // 滚动高亮：取视口顶部（含 56px 余量）之上最后一个标题（rAF 节流）
  useEffect(() => {
    if (!showToc) return;
    const root = contentRef.current;
    if (!root) return;
    let raf = 0;
    const onScroll = () => {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        const headings = Array.from(root.querySelectorAll<HTMLElement>('h1, h2, h3, h4')).filter((h) => h.id);
        let current = '';
        for (const h of headings) {
          if (h.getBoundingClientRect().top - root.getBoundingClientRect().top <= 56) current = h.id;
          else break;
        }
        setActiveId(current);
      });
    };
    onScroll();
    root.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      root.removeEventListener('scroll', onScroll);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [showToc, content, toc]);

  // 点击跳转：scrollTo 而非 scrollIntoView，避免连带滚动页面级祖先
  const jumpTo = useCallback((id: string) => {
    const root = contentRef.current;
    const el = root?.querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    if (!root || !el) return;
    root.scrollTo({ top: el.offsetTop - root.offsetTop - 12, behavior: 'smooth' });
  }, []);

  if (!showToc) {
    return (
      <div className={`min-w-0 ${className}`}>
        <div className="max-w-3xl mx-auto px-6 py-4">
          <MarkdownRenderer content={content} />
        </div>
      </div>
    );
  }

  return (
    <div className={`h-full flex min-w-0 ${className}`}>
      {toc.length > 0 && (
        <nav aria-label="文档目录" className="w-56 flex-shrink-0 overflow-y-auto scrollbar-thin border-r border-tech-500/10 px-2 py-4">
          <div className="px-2 pb-2 text-[11px] font-semibold text-ink-500 tracking-wider">目录</div>
          {toc.map((h) => (
            <button
              key={h.id}
              type="button"
              onClick={() => jumpTo(h.id)}
              className={`block w-full text-left text-xs rounded-md py-1 pr-2 truncate transition-colors ${
                activeId === h.id ? 'text-tech-300 bg-tech-500/10' : 'text-ink-400 hover:text-ink-200 hover:bg-ink-800/40'
              }`}
              style={{ paddingLeft: (Math.min(h.level, 4) - 1) * 12 + 8 }}
              title={h.text}
            >
              {h.text}
            </button>
          ))}
        </nav>
      )}
      <div ref={contentRef} className="relative flex-1 min-w-0 overflow-y-auto scrollbar-thin">
        <div className="max-w-3xl mx-auto px-6 py-4">
          <MarkdownRenderer content={content} />
        </div>
      </div>
    </div>
  );
}
