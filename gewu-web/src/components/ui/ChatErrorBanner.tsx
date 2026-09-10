'use client';
import { useState } from 'react';
import { AlertTriangle, X } from 'lucide-react';
import type { ChatErrorInfo } from '@/lib/chatErrors';

/**
 * 对话错误提示框（替代一闪而过的 toast）。
 * 折叠态：只显示错误第一行摘要 + 查看详情 / 关闭；
 * 展开态：错误分类、可能原因、建议处理、发生时间与完整错误信息。
 * 常驻显示直到用户点击关闭，发送新消息时自动清除。
 */
export default function ChatErrorBanner({ info, onClose }: {
  info: ChatErrorInfo;
  onClose: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const timeText = new Date(info.time).toLocaleString('zh-CN', { hour12: false });

  return (
    <div className="my-2 rounded-lg border border-red-500/20 bg-red-500/5 overflow-hidden" role="alert">
      {/* 折叠态：第一行摘要 + 操作 */}
      <div className="flex items-center gap-2 px-3 py-2">
        <AlertTriangle className="w-4 h-4 text-red-400 flex-shrink-0" />
        <span className="text-xs text-red-300/90 min-w-0 flex-1 truncate" title={info.raw}>{info.title}</span>
        <button
          onClick={() => setExpanded(prev => !prev)}
          className="flex-shrink-0 px-2 py-0.5 text-[11px] rounded-md text-red-300/90 hover:text-red-200 hover:bg-red-500/10 border border-red-500/20 transition-all"
        >
          {expanded ? '收起详情' : '查看详情'}
        </button>
        <button
          onClick={onClose}
          className="flex-shrink-0 p-1 text-red-300/60 hover:text-red-200 rounded transition-all"
          aria-label="关闭错误提示"
          title="关闭"
        ><X className="w-3.5 h-3.5" /></button>
      </div>

      {/* 展开态：完整错误详情 */}
      {expanded && (
        <div className="px-3 pb-3 pt-1 space-y-2 border-t border-red-500/10">
          <div className="flex items-center gap-2 pt-1">
            <span className="text-[11px] text-ink-500 flex-shrink-0">错误分类</span>
            <span className="text-xs text-red-300/90 font-medium">{info.category}</span>
            <span className="ml-auto text-[10px] text-ink-600">{timeText}</span>
          </div>
          <div>
            <div className="text-[11px] text-ink-500 mb-0.5">可能原因</div>
            <p className="text-xs text-ink-300 leading-relaxed">{info.cause}</p>
          </div>
          <div>
            <div className="text-[11px] text-ink-500 mb-0.5">建议处理</div>
            <p className="text-xs text-ink-300 leading-relaxed">{info.suggestion}</p>
          </div>
          <div>
            <div className="text-[11px] text-ink-500 mb-0.5">完整错误信息</div>
            <pre className="text-[10px] text-ink-400 whitespace-pre-wrap break-words font-mono leading-relaxed bg-ink-900/50 rounded-md p-2 max-h-40 overflow-y-auto scrollbar-thin">{info.raw}</pre>
          </div>
        </div>
      )}
    </div>
  );
}
