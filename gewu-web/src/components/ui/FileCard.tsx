'use client';
import { useState } from 'react';
import { FileCode, FileText, FileJson, FileSpreadsheet, FileType, Download, Eye, X, Loader2 } from 'lucide-react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { FileInfo } from '@/types';
import MarkdownReader from '@/components/ui/MarkdownReader';
import { prismLangOf } from '@/lib/prismHighlight';

/**
 * 文件卡片组件 - 展示 AI 生成的文件，支持预览和下载。
 */
export default function FileCard({ file }: { file: FileInfo }) {
  const [showPreview, setShowPreview] = useState(false);
  const [previewContent, setPreviewContent] = useState<string | null>(null);
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const icon = getFileIcon(file.fileType);
  const typeLabel = getFileTypeLabel(file.fileType);

  /** 用 fetch + blob 触发下载，避免跨域 download 属性失效 */
  const handleDownload = async (e?: React.MouseEvent) => {
    e?.preventDefault();
    e?.stopPropagation();
    setDownloading(true);
    try {
      const res = await fetch(file.downloadUrl);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = file.fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch {
      // CORS 或网络失败时降级为新标签页打开
      window.open(file.downloadUrl, '_blank');
    } finally {
      setDownloading(false);
    }
  };

  /** 预览：fetch 完整文件内容 */
  const handlePreview = async () => {
    setShowPreview(true);
    if (previewContent !== null) return; // 已加载过
    setLoadingPreview(true);
    try {
      const res = await fetch(file.downloadUrl);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const text = await res.text();
      setPreviewContent(text);
    } catch {
      // CORS 失败时降级为预览片段
      setPreviewContent(file.previewContent || '（无法加载完整内容，请下载查看）');
    } finally {
      setLoadingPreview(false);
    }
  };

  return (
    <>
      <div className="flex items-center gap-3 p-3 rounded-lg border border-tech-500/20 bg-ink-900/40 hover:bg-ink-800/40 transition-all">
        <div className="flex-shrink-0 text-tech-400">{icon}</div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-ink-100 truncate">{file.fileName}</span>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-tech-500/10 text-tech-400">{typeLabel}</span>
            <span className="text-[10px] text-ink-600">{formatSize(file.fileSize)}</span>
          </div>
          <p className="text-[11px] text-ink-500 mt-0.5 line-clamp-1">{file.previewContent}</p>
        </div>
        <div className="flex items-center gap-1 flex-shrink-0">
          <button
            onClick={handlePreview}
            className="p-1.5 rounded-md text-ink-400 hover:text-tech-400 hover:bg-ink-800/50 transition-all"
            title="预览"
          >
            <Eye className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={handleDownload}
            disabled={downloading}
            className="p-1.5 rounded-md text-ink-400 hover:text-tech-400 hover:bg-ink-800/50 transition-all disabled:opacity-50"
            title="下载"
          >
            {downloading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>

      {/* 预览模态框 */}
      {showPreview && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
          onClick={() => setShowPreview(false)}
        >
          <div
            className="relative w-full max-w-4xl max-h-[80vh] mx-4 rounded-xl border border-tech-500/20 overflow-hidden flex flex-col"
            style={{ background: 'rgba(14,28,27,0.95)' }}
            onClick={(e) => e.stopPropagation()}
          >
            {/* 头部 */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-tech-500/10 flex-shrink-0">
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-tech-400 flex-shrink-0">{icon}</span>
                <span className="text-sm font-medium text-ink-100 truncate">{file.fileName}</span>
                <span className="text-[10px] text-ink-600 flex-shrink-0">{formatSize(file.fileSize)}</span>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                <button
                  onClick={handleDownload}
                  disabled={downloading}
                  className="flex items-center gap-1 px-2 py-1 rounded-md text-xs text-tech-400 hover:bg-tech-500/10 transition-all disabled:opacity-50"
                >
                  {downloading ? <Loader2 className="w-3 h-3 animate-spin" /> : <Download className="w-3 h-3" />}
                  <span>下载</span>
                </button>
                <button
                  onClick={() => setShowPreview(false)}
                  className="p-1 rounded-md text-ink-400 hover:text-ink-100 hover:bg-ink-800/50"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>
            {/* 内容 - 可滚动（Markdown 走阅读模式渲染，代码走语法高亮，其余纯文本） */}
            <div className="flex-1 overflow-auto p-4 scrollbar-thin">
              {loadingPreview ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="w-5 h-5 text-tech-400 animate-spin" />
                  <span className="ml-2 text-sm text-ink-400">加载文件内容...</span>
                </div>
              ) : isMarkdownFile(file) ? (
                <MarkdownReader content={previewContent ?? ''} />
              ) : previewHighlightLang(file) ? (
                <SyntaxHighlighter
                  language={previewHighlightLang(file)!}
                  style={vscDarkPlus}
                  showLineNumbers={(previewContent ?? '').split('\n').length > 3}
                  customStyle={{
                    margin: 0,
                    padding: '12px 0',
                    background: 'transparent',
                    fontSize: '13px',
                    lineHeight: '1.6',
                  }}
                  codeTagProps={{
                    style: {
                      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace",
                    },
                  }}
                >
                  {previewContent ?? ''}
                </SyntaxHighlighter>
              ) : (
                <pre className="text-[13px] text-ink-200 whitespace-pre-wrap break-words font-mono leading-relaxed">
                  {previewContent}
                </pre>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/** Markdown 文件判定：类型标记优先，扩展名兜底 */
function isMarkdownFile(file: FileInfo): boolean {
  if (file.fileType === 'markdown') return true;
  const name = file.fileName.toLowerCase();
  return name.endsWith('.md') || name.endsWith('.markdown');
}

/** 代码类文件的 Prism 高亮语言（code/json/yaml 且扩展名可识别）；不命中返回 null 走纯文本 */
function previewHighlightLang(file: FileInfo): string | null {
  if (file.fileType !== 'code' && file.fileType !== 'json' && file.fileType !== 'yaml') return null;
  return prismLangOf(file.fileName);
}

function getFileIcon(fileType: string) {  switch (fileType) {
    case 'code':
      return <FileCode className="w-5 h-5" />;
    case 'markdown':
      return <FileText className="w-5 h-5" />;
    case 'json':
      return <FileJson className="w-5 h-5" />;
    case 'csv':
      return <FileSpreadsheet className="w-5 h-5" />;
    case 'docx':
      return <FileType className="w-5 h-5" />;
    default:
      return <FileText className="w-5 h-5" />;
  }
}

function getFileTypeLabel(fileType: string): string {
  switch (fileType) {
    case 'code':
      return '代码';
    case 'markdown':
      return '文档';
    case 'json':
      return 'JSON';
    case 'yaml':
      return 'YAML';
    case 'csv':
      return '表格';
    case 'docx':
      return 'Word';
    default:
      return '文件';
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}