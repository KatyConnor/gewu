'use client';
// 比较对象结果弹窗（对齐版本历史布局）：
// 文件比较 → 直接左右内容对照；目录比较 → 左侧分类文件清单（有差异/仅左/仅右/无差异），
// 右侧左右对照选中文件的差异内容（MonacoDiff，左=左目录版本，右=右目录版本）。
import { useCallback, useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import Modal from '@/components/ui/Modal';
import MonacoDiff from '@/components/pages/MonacoDiff';
import { readFile } from '@/lib/dev-workspace';
import { monacoLangOf } from '@/lib/fileLanguage';

export type CompareItemKind = 'diff' | 'onlyA' | 'onlyB' | 'same';
export interface CompareItem { rel: string; kind: CompareItemKind }

export interface DevCompareResult {
  kind: 'file' | 'dir';
  original: string;
  modified: string;
  /** 目录比较：两侧根路径（内容按需加载用） */
  dirA?: string;
  dirB?: string;
  items?: CompareItem[];
  leftTitle: string;
  rightTitle: string;
}

const GROUPS: Array<{ key: CompareItemKind; label: string }> = [
  { key: 'diff', label: '有差异' },
  { key: 'onlyA', label: '仅左侧存在' },
  { key: 'onlyB', label: '仅右侧存在' },
  { key: 'same', label: '无差异' },
];

export default function DevCompareModal({ open, result, theme, onClose }: {
  open: boolean;
  result: DevCompareResult | null;
  theme?: string;
  onClose: () => void;
}) {
  const [sel, setSel] = useState<CompareItem | null>(null);
  const [loading, setLoading] = useState(false);
  const [content, setContent] = useState<{ original: string; modified: string } | null>(null);
  const [missingSide, setMissingSide] = useState<'' | 'left' | 'right'>('');

  useEffect(() => {
    if (open) { setSel(null); setContent(null); setMissingSide(''); }
  }, [open, result]);

  const safeRead = useCallback(async (p: string) => {
    try { return await readFile(p); } catch { return ''; }
  }, []);

  const loadItem = useCallback(async (item: CompareItem) => {
    if (!result?.dirA || !result?.dirB) return;
    setSel(item); setLoading(true); setMissingSide('');
    try {
      if (item.kind === 'onlyA') {
        const a = await safeRead(`${result.dirA}/${item.rel}`);
        setMissingSide('right');
        setContent({ original: a, modified: '' });
      } else if (item.kind === 'onlyB') {
        const b = await safeRead(`${result.dirB}/${item.rel}`);
        setMissingSide('left');
        setContent({ original: '', modified: b });
      } else {
        const [a, b] = await Promise.all([safeRead(`${result.dirA}/${item.rel}`), safeRead(`${result.dirB}/${item.rel}`)]);
        setContent({ original: a, modified: b });
      }
    } finally {
      setLoading(false);
    }
  }, [result, safeRead]);

  const items = result?.items || [];
  const groups = GROUPS.map(g => ({ ...g, list: items.filter(i => i.kind === g.key) })).filter(g => g.list.length > 0);
  const diffLang = sel ? monacoLangOf(sel.rel) : 'plaintext';

  return (
    <Modal open={open} onClose={onClose} zIndex="z-[75]" ariaLabel="比较对象"
      panelClassName="glass-dark rounded-xl w-full max-w-[1573px] h-[92vh] p-4 border border-tech-500/15 shadow-2xl flex flex-col">
      <div className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="text-sm font-semibold text-ink-50 min-w-0 truncate">
          比较对象<span className="text-xs text-ink-500 font-normal ml-2">{result?.leftTitle} vs {result?.rightTitle}</span>
        </div>
        <button onClick={onClose} className="text-ink-500 hover:text-ink-200 text-xs flex-shrink-0">关闭</button>
      </div>
      <div className="flex-1 min-h-0 flex gap-3">
        {result?.kind === 'dir' && (
          <div className="w-72 flex-shrink-0 overflow-y-auto scrollbar-thin border-r border-tech-500/10 pr-2">
            {groups.length === 0 ? (
              <div className="text-center py-8 text-ink-500 text-xs">目录清单加载中…</div>
            ) : (
              groups.map(g => (
                <div key={g.key} className="mb-2">
                  <div className="px-2 py-1 text-[10px] font-semibold text-ink-500 tracking-wider">{g.label}（{g.list.length}）</div>
                  {g.list.map(item => (
                    <button key={`${item.kind}:${item.rel}`} onClick={() => loadItem(item)}
                      className={`w-full text-left px-2 py-1 rounded-md text-[11px] transition-all truncate ${
                        sel?.rel === item.rel && sel?.kind === item.kind ? 'bg-tech-500/15 text-tech-400' : 'text-ink-300 hover:text-ink-100 hover:bg-tech-500/5'
                      }`} title={item.rel}>
                      {item.rel}
                    </button>
                  ))}
                </div>
              ))
            )}
          </div>
        )}
        <div className="flex-1 min-w-0 flex flex-col">
          {result?.kind === 'file' ? (
            <>
              <div className="text-[10px] text-ink-500 mb-1.5 flex-shrink-0">左：{result.leftTitle} ｜ 右：{result.rightTitle}</div>
              <div className="flex-1 min-h-0 border border-tech-500/10 rounded-lg overflow-hidden">
                <MonacoDiff original={result.original} modified={result.modified} language={monacoLangOf(result.rightTitle)} path={result.rightTitle} theme={theme} />
              </div>
            </>
          ) : sel ? (
            <>
              <div className="text-[10px] text-ink-500 mb-1.5 flex-shrink-0 truncate">
                {sel.rel}
                {missingSide === 'right' && ' ｜ 仅左侧存在（右侧为空）'}
                {missingSide === 'left' && ' ｜ 仅右侧存在（左侧为空）'}
              </div>
              <div className="flex-1 min-h-0 border border-tech-500/10 rounded-lg overflow-hidden">
                {loading ? (
                  <div className="flex items-center justify-center h-full"><Loader2 className="w-4 h-4 text-tech-400 animate-spin" /></div>
                ) : content ? (
                  <MonacoDiff original={content.original} modified={content.modified} language={diffLang} path={sel.rel} theme={theme} />
                ) : null}
              </div>
            </>
          ) : (
            <div className="flex items-center justify-center h-full text-ink-500 text-xs">
              {result?.kind === 'dir' ? '选择左侧清单中的文件查看左右差异' : '选择左侧版本查看差异'}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
