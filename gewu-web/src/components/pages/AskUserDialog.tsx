'use client';
/**
 * 用户问答确认弹框（ZCode 形态，图1）：AI 经 ask_user 工具挂起提问后，
 * 展示问题 + 编号候选项 + 自定义输入，用户选中或输入后提交恢复任务执行；
 * 「忽略」以跳过应答回灌（AI 基于已有信息自主决策继续）。
 */
import React, { useEffect, useRef, useState } from 'react';
import { HelpCircle } from 'lucide-react';

export interface PendingAsk {
  askId: string;
  sessionId: string;
  question: string;
  options: string[];
}

interface Props {
  ask: PendingAsk;
  /** 提交（选中项或自定义输入文本） */
  onSubmit: (askId: string, answer: string) => void;
  /** 忽略（跳过此问题，任务继续） */
  onSkip: (askId: string) => void;
}

export default function AskUserDialog({ ask, onSubmit, onSkip }: Props) {
  const [selected, setSelected] = useState<number | null>(null);
  const [custom, setCustom] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 键盘导航：↑/↓ 切换候选项，回车提交当前选中/输入
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (ask.options.length === 0) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelected(prev => (prev === null ? 0 : (prev + 1) % ask.options.length));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelected(prev => (prev === null ? ask.options.length - 1 : (prev - 1 + ask.options.length) % ask.options.length));
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [ask.options]);

  const submit = () => {
    if (custom.trim()) {
      onSubmit(ask.askId, custom.trim());
      return;
    }
    if (selected !== null && ask.options[selected]) {
      onSubmit(ask.askId, ask.options[selected]);
    }
  };

  const canSubmit = Boolean(custom.trim()) || (selected !== null && Boolean(ask.options[selected]));

  return (
    <div
      className="fixed inset-0 z-[80] flex items-center justify-center bg-black/50 backdrop-blur-sm"
      data-testid="ask-user-dialog"
    >
      <div
        className="glass-dark rounded-xl border border-tech-500/25 w-[560px] max-w-[92vw] max-h-[80vh] flex flex-col shadow-2xl"
        onClick={e => e.stopPropagation()}
      >
        <div className="px-5 py-3.5 border-b border-tech-500/10 flex items-center gap-2">
          <HelpCircle className="w-4 h-4 text-tech-400" />
          <span className="text-sm font-medium text-ink-100">需要你的确认</span>
          <span className="text-[11px] text-ink-500">任务已暂停，回答后继续执行</span>
        </div>
        <div className="px-5 py-4 flex-1 overflow-y-auto">
          <p className="text-sm text-ink-100 whitespace-pre-wrap leading-relaxed">{ask.question}</p>
          {ask.options.length > 0 && (
            <div className="mt-4 space-y-1.5">
              {ask.options.map((opt, i) => (
                <button
                  key={i}
                  onClick={() => { setSelected(i); setCustom(''); }}
                  className={`w-full text-left px-3.5 py-2.5 rounded-lg border transition-all text-sm flex gap-2.5 ${
                    selected === i
                      ? 'bg-tech-500/15 border-tech-500/40 text-ink-50'
                      : 'bg-ink-800/40 border-transparent text-ink-300 hover:border-tech-500/20'
                  }`}
                >
                  <span className={`flex-shrink-0 ${selected === i ? 'text-tech-400' : 'text-ink-500'}`}>{i + 1}.</span>
                  <span className="whitespace-pre-wrap">{opt}</span>
                </button>
              ))}
            </div>
          )}
          <textarea
            ref={inputRef}
            value={custom}
            onChange={e => { setCustom(e.target.value); setSelected(null); }}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (canSubmit) submit(); }
            }}
            rows={2}
            placeholder="或输入你的回答… (Enter 提交)"
            className="mt-4 w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none scrollbar-thin"
          />
          <p className="mt-1.5 text-[11px] text-ink-500">
            {ask.options.length > 0 ? '使用 ↑/↓ 键选择候选项，' : ''}回答后任务将继续执行
          </p>
        </div>
        <div className="px-5 py-3 border-t border-tech-500/10 flex items-center justify-end gap-2.5">
          <button
            onClick={() => onSkip(ask.askId)}
            className="px-4 py-1.5 text-sm text-ink-400 hover:text-ink-200 rounded-lg transition-all"
          >
            忽略
          </button>
          <button
            onClick={submit}
            disabled={!canSubmit}
            className="px-5 py-1.5 text-sm btn-primary text-white rounded-lg disabled:opacity-40"
          >
            提交
          </button>
        </div>
      </div>
    </div>
  );
}
