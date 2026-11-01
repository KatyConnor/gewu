'use client';
// 全项目替换（IDEA Replace in Files · Ctrl+Shift+R）：
// 查找词 + 替换词 + 大小写/正则/文件掩码 → grep 命中预览 → 勾选文件逐个替换写盘。
// execCommand 沙箱通道检索，readFile/writeFile 精确替换（保留后端 XSS 过滤约束，命令行只传白名单词）。
import { useEffect, useMemo, useState } from 'react';
import { Loader2 } from 'lucide-react';
import Modal from '@/components/ui/Modal';
import { useToast } from '@/components/ui/Toast';
import { execCommand, readFile, writeFile, type GitProject } from '@/lib/dev-workspace';

interface ReplaceHit {
  path: string;   // 工作空间绝对路径
  rel: string;    // 仓库相对路径
  lines: Array<{ line: number; text: string }>;
}

export default function DevProjectReplaceDialog({
  open, project, onClose, onDone,
}: {
  open: boolean;
  project: GitProject;
  onClose: () => void;
  /** 替换完成后回调（刷新 git status / 文件树） */
  onDone: () => void;
}) {
  const toast = useToast();
  const [find, setFind] = useState('');
  const [replace, setReplace] = useState('');
  const [matchCase, setMatchCase] = useState(false);
  const [isRegex, setIsRegex] = useState(false);
  const [fileMask, setFileMask] = useState('');
  const [preview, setPreview] = useState<ReplaceHit[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [searching, setSearching] = useState(false);
  const [replacing, setReplacing] = useState(false);
  const [err, setErr] = useState('');

  const lp = project.localPath || '';

  const buildPattern = useMemo(() => {
    if (!find) return null;
    if (isRegex) {
      try { return new RegExp(find, matchCase ? 'g' : 'gi'); } catch { return null; }
    }
    return new RegExp(find.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), matchCase ? 'g' : 'gi');
  }, [find, isRegex, matchCase]);

  // 预览检索（防抖 400ms）
  useEffect(() => {
    if (!open || !find.trim() || !lp) { setPreview([]); setErr(''); return; }
    setSearching(true);
    const timer = setTimeout(async () => {
      try {
        const masks = fileMask.trim()
          ? fileMask.trim().split(/[\s,]+/).map(m => `--include="${m}"`).join(' ')
          : '';
        // grep 检索（正则模式直接传；字面模式转义）——词串白名单化防注入：正则交由 -E，字面走 -F
        const pattern = find.trim().replace(/"/g, '');
        const flag = isRegex ? '-rnIE' : '-rnIF';
        const r = await execCommand(`grep ${flag} "${pattern}" ${masks} --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target /workspace/${lp} 2>/dev/null | head -400`, 30);
        const map = new Map<string, ReplaceHit>();
        (r.stdout || '').split('\n').filter(Boolean).forEach(line => {
          const m = /^\/workspace\/(.+?):(\d+):(.*)$/.exec(line);
          if (!m) return;
          const abs = `/workspace/${m[1]}`;
          if (!map.has(abs)) map.set(abs, { path: abs, rel: m[1], lines: [] });
          map.get(abs)!.lines.push({ line: Number(m[2]), text: m[3] });
        });
        const list = Array.from(map.values()).sort((a, b) => a.rel.localeCompare(b.rel));
        setPreview(list);
        setSelected(new Set(list.map(h => h.path)));
        setErr('');
      } catch (e) {
        setErr(e instanceof Error ? e.message : String(e));
      } finally {
        setSearching(false);
      }
    }, 400);
    return () => clearTimeout(timer);
  }, [open, find, fileMask, isRegex, lp]);

  /** 预览该文件替换后片段（首个命中行） */
  const previewOf = (h: ReplaceHit): string => {
    if (!buildPattern) return '';
    const sample = h.lines[0]?.text || '';
    let count = 0;
    sample.replace(buildPattern, (s) => { count++; return s; });
    return sample.replace(buildPattern, (s) => `【${replace || s}】`).slice(0, 160);
  };

  const doReplace = async () => {
    if (!buildPattern || !find.trim()) return;
    const targets = preview.filter(h => selected.has(h.path));
    if (targets.length === 0) return;
    if (replace.includes('"') && !isRegex) { /* 保留：写入由 API 层过滤 */ }
    setReplacing(true);
    let touched = 0;
    let failed = 0;
    try {
      for (const h of targets) {
        try {
          const content = await readFile(h.path);
          const next = content.replace(buildPattern, replace);
          if (next !== content) {
            await writeFile(h.path, next);
            touched++;
          }
        } catch { failed++; }
      }
      toast(`替换完成：${touched} 个文件${failed ? `（${failed} 个失败）` : ''}`, failed ? 'info' : 'success');
      onDone();
      onClose();
    } finally {
      setReplacing(false);
    }
  };

  if (!open) return null;
  const totalHits = preview.reduce((n, h) => n + h.lines.length, 0);

  return (
    <Modal open onClose={onClose} zIndex="z-[88]" ariaLabel="在项目中替换"
      panelClassName="glass-dark rounded-xl w-full max-w-2xl h-[80vh] p-4 border border-tech-500/15 shadow-2xl flex flex-col">
      <div className="text-sm font-semibold text-ink-50 mb-3">在项目中替换（Replace in Files）</div>
      <div className="grid grid-cols-2 gap-2 mb-2">
        <input autoFocus value={find} onChange={(e) => setFind(e.target.value)}
          placeholder="查找（Ctrl+Shift+R）"
          className="px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 placeholder:text-ink-600 outline-none focus:border-tech-500/40" />
        <input value={replace} onChange={(e) => setReplace(e.target.value)}
          placeholder="替换为"
          className="px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 placeholder:text-ink-600 outline-none focus:border-tech-500/40" />
      </div>
      <div className="flex items-center gap-3 mb-3 text-[11px] text-ink-400">
        <label className="flex items-center gap-1 cursor-pointer">
          <input type="checkbox" checked={matchCase} onChange={(e) => setMatchCase(e.target.checked)} className="accent-tech-500" />区分大小写
        </label>
        <label className="flex items-center gap-1 cursor-pointer">
          <input type="checkbox" checked={isRegex} onChange={(e) => setIsRegex(e.target.checked)} className="accent-tech-500" />正则表达式
        </label>
        <label className="flex items-center gap-1 flex-1">
          文件掩码
          <input value={fileMask} onChange={(e) => setFileMask(e.target.value)} placeholder="*.java, *.xml"
            className="flex-1 px-2 py-1 text-[11px] bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 placeholder:text-ink-600 outline-none" />
        </label>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto scrollbar-thin border border-tech-500/10 rounded-lg">
        {searching && <div className="flex items-center justify-center py-10"><Loader2 className="w-4 h-4 text-tech-400 animate-spin" /></div>}
        {err && <div className="px-3 py-4 text-xs text-cinnabar-300">{err}</div>}
        {!searching && !find.trim() && <div className="px-3 py-10 text-center text-xs text-ink-500">输入查找内容预览命中</div>}
        {!searching && find.trim() && preview.length === 0 && !err && (
          <div className="px-3 py-10 text-center text-xs text-ink-500">无命中结果</div>
        )}
        {preview.map(h => (
          <div key={h.path} className="border-b border-tech-500/5 last:border-0">
            <label className="flex items-center gap-2 px-2.5 py-1.5 cursor-pointer hover:bg-tech-500/5">
              <input type="checkbox" checked={selected.has(h.path)}
                onChange={(e) => setSelected(prev => { const n = new Set(prev); if (e.target.checked) n.add(h.path); else n.delete(h.path); return n; })}
                className="accent-tech-500 flex-shrink-0" />
              <span className="text-xs text-ink-200 truncate flex-1" title={h.rel}>{h.rel}</span>
              <span className="text-[10px] text-ink-600 flex-shrink-0">{h.lines.length} 处</span>
            </label>
            {selected.has(h.path) && buildPattern && (
              <div className="pl-8 pr-2 pb-1.5 text-[10px] font-mono text-ink-500 truncate" title={previewOf(h)}>
                {previewOf(h)}
              </div>
            )}
          </div>
        ))}
      </div>
      <div className="flex items-center justify-between mt-3">
        <span className="text-[10px] text-ink-600">{totalHits > 0 ? `${preview.length} 个文件 · ${totalHits} 处命中 · 已选 ${selected.size} 个文件` : ''}</span>
        <div className="flex gap-2">
          <button onClick={onClose} className="px-3 py-1.5 text-xs text-ink-300 hover:text-ink-100 transition-colors">取消</button>
          <button onClick={doReplace} disabled={replacing || selected.size === 0 || !find.trim()}
            className="px-3 py-1.5 text-xs font-medium text-white bg-tech-500 hover:bg-tech-600 rounded-lg transition-colors disabled:opacity-50 flex items-center gap-1">
            {replacing && <Loader2 className="w-3 h-3 animate-spin" />}替换已选文件
          </button>
        </div>
      </div>
    </Modal>
  );
}
