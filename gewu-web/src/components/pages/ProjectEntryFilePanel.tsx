'use client';
import { useCallback, useEffect, useState } from 'react';
import type { ChangeEvent } from 'react';
import { FileText, RefreshCw, AlertTriangle } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import {
  resolveProjectEntry, readProjectEntryFile, readGlobalEntryFile,
  initProjectEntryFile, importEntryPackage, type EntryFileName, type EntryView,
} from '@/lib/entry-file';

/**
 * 项目调度文件面板（设计文档 56 §6.2 项目页签 + §6.4 升级通知）：
 * 跨层裁决视图（含升级提示横幅）、GEWU.md/AGENTS.md 项目文件查看与编辑、
 * 一键同步（限同名，评审 F-302）、模板初始化。
 */
type Names = 'GEWU.md' | 'AGENTS.md';
const NAMES: Names[] = ['GEWU.md', 'AGENTS.md'];

export default function ProjectEntryFilePanel({ projectId }: { projectId: string }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<EntryView | null>(null);
  const [projectFiles, setProjectFiles] = useState<Record<string, { exists: boolean; content: string }>>({});
  const [globalFiles, setGlobalFiles] = useState<Record<string, { exists: boolean; content: string }>>({});
  const [editing, setEditing] = useState<{ name: Names; content: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [v, pg, pa, gg, ga] = await Promise.all([
        resolveProjectEntry(projectId),
        readProjectEntryFile(projectId, 'GEWU.md').catch(() => ({ exists: false, content: '', path: '' })),
        readProjectEntryFile(projectId, 'AGENTS.md').catch(() => ({ exists: false, content: '', path: '' })),
        readGlobalEntryFile('GEWU.md').catch(() => ({ exists: false, content: '' })),
        readGlobalEntryFile('AGENTS.md').catch(() => ({ exists: false, content: '' })),
      ]);
      setView(v);
      setProjectFiles({ 'GEWU.md': pg, 'AGENTS.md': pa });
      setGlobalFiles({ 'GEWU.md': gg, 'AGENTS.md': ga });
    } catch {
      // 页签为辅助信息，加载失败静默
    }
  }, [projectId]);

  useEffect(() => { if (open) load(); }, [open, load]);

  const globalNameRaw = view?.source?.startsWith('user-global:')
    ? view.source.split(':')[1] : null;
  // 白名单校验（CR F#11）：非 GEWU/AGENTS 名一律不启用同步
  const globalName: Names | null =
    globalNameRaw === 'GEWU.md' || globalNameRaw === 'AGENTS.md' ? globalNameRaw : null;
  // 一键同步限同名（评审 F-302）：全局生效文件在项目层存在同名文件才可同步
  const canSync = !!view?.upgradeHint && !!globalName && !!projectFiles[globalName]?.exists;

  const run = async (fn: () => Promise<void>, okMsg: string) => {
    setBusy(true);
    try {
      await fn();
      toast(okMsg, 'success');
      setEditing(null);
      await load();
    } catch (err) {
      toast(err instanceof Error ? err.message : '操作失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  const handleSync = () => {
    if (!globalName) return;
    run(async () => {
      const g = await readGlobalEntryFile(globalName);
      await initProjectEntryFile(projectId, globalName, g.content);
    }, `已同步全局 ${globalName} 至项目（覆盖前服务端快照，可恢复）`);
  };

  const handleSave = () => {
    if (!editing) return;
    run(() => initProjectEntryFile(projectId, editing.name, editing.content),
      `已保存项目 ${editing.name}`);
  };

  const handleInitTemplate = (name: Names) => {
    run(() => initProjectEntryFile(projectId, name), `已按平台模板初始化项目 ${name}`);
  };

  const handleImportZip = async (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = ''; // 允许重复选择同一文件
    if (!f) return;
    setBusy(true);
    try {
      const r = await importEntryPackage(f, 'project', projectId);
      toast(`入口包已导入项目（${r.fileCount} 个文件）`, 'success');
      await load();
    } catch (err) {
      toast(err instanceof Error ? err.message : '导入失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="p-3 border-t border-tech-500/10">
      <button onClick={() => setOpen(o => !o)}
        className="flex items-center justify-between w-full text-xs text-ink-300 hover:text-ink-100">
        <span className="flex items-center gap-1.5"><FileText className="w-3.5 h-3.5 text-tech-400" />调度文件</span>
        <span className="text-ink-500">{open ? '收起' : '展开'}</span>
      </button>

      {open && (
        <div className="mt-2 space-y-2">
          {view?.present && (
            <p className="text-[10px] text-ink-500">当前生效：{view.source}</p>
          )}

          {view?.upgradeHint && (
            <div className="rounded-lg border border-gold-500/30 bg-gold-500/5 p-2">
              <p className="flex items-start gap-1.5 text-[10px] text-gold-300">
                <AlertTriangle className="w-3 h-3 mt-0.5 flex-shrink-0" />
                {view.upgradeHint}
              </p>
              {canSync && (
                <button onClick={handleSync} disabled={busy}
                  className="mt-1.5 flex items-center gap-1 px-2 py-1 text-[10px] bg-gold-500/20 text-gold-300 rounded hover:bg-gold-500/30 disabled:opacity-50">
                  <RefreshCw className="w-3 h-3" />一键同步（同名 {globalName}，覆盖前自动快照）
                </button>
              )}
            </div>
          )}

          {NAMES.map(name => {
            const pf = projectFiles[name] || { exists: false, content: '' };
            const gf = globalFiles[name] || { exists: false, content: '' };
            return (
              <div key={name} className="flex items-center justify-between text-[11px]">
                <span className={pf.exists ? 'text-ink-200' : 'text-ink-500'}>
                  {name}{gf.exists && !pf.exists ? '（全局已有，项目未创建）' : pf.exists ? '' : '（不存在）'}
                </span>
                <div className="flex items-center gap-1">
                  {pf.exists && (
                    <button onClick={() => setEditing({ name, content: pf.content })}
                      className="px-1.5 py-0.5 text-[10px] text-tech-300 hover:bg-tech-500/10 rounded">编辑</button>
                  )}
                  <label className="px-1.5 py-0.5 text-[10px] text-ink-300 hover:bg-ink-800/50 rounded cursor-pointer">
                    导入 zip
                    <input type="file" accept=".zip" className="hidden"
                      onChange={handleImportZip} disabled={busy} />
                  </label>
                  {!pf.exists && (
                    <button onClick={() => handleInitTemplate(name)} disabled={busy}
                      className="px-1.5 py-0.5 text-[10px] text-ink-300 hover:bg-ink-800/50 rounded disabled:opacity-50">模板初始化</button>
                  )}
                </div>
              </div>
            );
          })}
          <p className="text-[10px] text-ink-600">版本头 version: x.y.z 参与跨层裁决；保存建议提交进仓库供团队共享。</p>
        </div>
      )}

      {/* 编辑弹层 */}
      {editing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={() => setEditing(null)}>
          <div className="glass-dark rounded-xl p-5 w-full max-w-2xl border border-tech-500/20 max-h-[85vh] overflow-y-auto"
            onClick={e => e.stopPropagation()}>
            <h3 className="text-sm font-semibold text-ink-50 mb-3">编辑项目 {editing.name}</h3>
            <textarea
              value={editing.content}
              onChange={e => setEditing({ ...editing, content: e.target.value })}
              rows={18}
              className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none font-mono resize-none"
            />
            <div className="flex gap-2 justify-end mt-3">
              <button onClick={() => setEditing(null)} className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200">取消</button>
              <button onClick={handleSave} disabled={busy}
                className="px-4 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50">保存</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
