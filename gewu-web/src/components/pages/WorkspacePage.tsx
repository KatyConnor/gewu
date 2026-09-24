'use client';
import { useState, useEffect, useCallback, useRef } from 'react';
import dynamic from 'next/dynamic';
import { Loader2, FolderPlus, Upload, Trash2, ChevronRight, ChevronDown, Folder, FileText, Save, Play, HardDrive, Pencil, Undo2 } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import MarkdownReader from '@/components/ui/MarkdownReader';
import { isMarkdownPath, isProbablyBinary, monacoLangOf } from '@/lib/fileLanguage';
import {
  getMyWorkspace, listFiles, createDirectory, uploadFile,
  getFileContent, saveFileContent, deleteFile, createWorkspaceSandbox,
  type WorkspaceDTO, type FileNodeDTO,
} from '@/lib/workspace';

// Monaco 懒加载（自托管 public/monaco，免 CDN）
const MonacoFileEditor = dynamic(() => import('./MonacoEditor'), { ssr: false });

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

export default function WorkspacePage() {
  const toast = useToast();
  const [workspace, setWorkspace] = useState<WorkspaceDTO | null>(null);
  const [files, setFiles] = useState<FileNodeDTO[]>([]);
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set());
  const [dirChildren, setDirChildren] = useState<Record<string, FileNodeDTO[] | undefined>>({});
  const [selectedFile, setSelectedFile] = useState<FileNodeDTO | null>(null);
  const [fileContent, setFileContent] = useState('');
  const [savedContent, setSavedContent] = useState('');
  const [viewMode, setViewMode] = useState<'preview' | 'edit'>('preview');
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(true);
  const [contentLoading, setContentLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [showNewDir, setShowNewDir] = useState(false);
  const [newDirName, setNewDirName] = useState('');
  const [currentParentId, setCurrentParentId] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const ws = await getMyWorkspace();
      setWorkspace(ws);
      const tree = await listFiles(undefined);
      setFiles(tree);
    } catch (e) {
      toast('加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const loadChildren = async (dirId: string) => {
    try {
      const children = await listFiles(dirId);
      setDirChildren(prev => ({ ...prev, [dirId]: children }));
    } catch {
      setDirChildren(prev => ({ ...prev, [dirId]: [] }));
    }
  };

  const toggleDir = async (dir: FileNodeDTO) => {
    const next = new Set(expandedDirs);
    if (next.has(dir.fileId)) {
      next.delete(dir.fileId);
    } else {
      next.add(dir.fileId);
      await loadChildren(dir.fileId);
    }
    setExpandedDirs(next);
  };

  /** 刷新根列表与所有已展开目录的子列表（增删/上传后保持树视图一致） */
  const refreshVisible = async () => {
    try {
      setFiles(await listFiles(undefined));
    } catch {
      // 根列表保持旧值，已展开目录的刷新继续执行
    }
    await Promise.all(Array.from(expandedDirs).map(id => loadChildren(id)));
  };

  const handleSelectFile = async (file: FileNodeDTO) => {
    if (file.fileType === 1) {
      toggleDir(file);
      return;
    }
    setSelectedFile(file);
    setContentLoading(true);
    setViewMode('preview');
    setDirty(false);
    try {
      const content = await getFileContent(file.fileId);
      setFileContent(content);
      setSavedContent(content);
    } catch (e) {
      toast('读取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setFileContent('');
      setSavedContent('');
    } finally {
      setContentLoading(false);
    }
  };

  const handleSave = async () => {
    if (!selectedFile || viewMode !== 'edit') return;
    setSaving(true);
    try {
      const updated = await saveFileContent(selectedFile.fileId, fileContent);
      setSelectedFile(updated);
      setSavedContent(fileContent);
      setDirty(false);
      toast('已保存 (v' + updated.version + ')', 'success');
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  /** 退出编辑：有未保存修改先确认，放弃则还原到最近保存内容 */
  const exitEdit = () => {
    if (dirty && !confirm('有未保存的修改，确定放弃并退出编辑？')) return;
    setFileContent(savedContent);
    setDirty(false);
    setViewMode('preview');
  };

  // Ctrl+S 保存（仅编辑态生效）
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        if (viewMode === 'edit') handleSave();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewMode, selectedFile, fileContent, saving]);

  const handleCreateDir = async () => {
    if (!newDirName.trim()) return;
    try {
      await createDirectory(currentParentId, newDirName.trim());
      toast('目录已创建', 'success');
      setShowNewDir(false);
      setNewDirName('');
      await refreshVisible();
    } catch (e) {
      toast('创建失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await uploadFile(file, currentParentId ?? undefined);
      toast('上传成功', 'success');
      await refreshVisible();
    } catch (err) {
      toast('上传失败: ' + (err instanceof Error ? err.message : String(err)), 'error');
    }
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleDelete = async (file: FileNodeDTO) => {
    if (!confirm(`确认删除 ${file.fileName}？`)) return;
    try {
      await deleteFile(file.fileId);
      toast('已删除', 'success');
      if (selectedFile?.fileId === file.fileId) {
        setSelectedFile(null);
        setFileContent('');
        setSavedContent('');
        setDirty(false);
        setViewMode('preview');
      }
      await refreshVisible();
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleCreateSandbox = async () => {
    try {
      const sb = await createWorkspaceSandbox('shell', '工作空间沙箱');
      toast('沙箱已创建: ' + sb.sandboxId.substring(0, 8), 'success');
    } catch (e) {
      toast('沙箱创建失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  /** 递归渲染树节点：目录展开时懒加载并渲染其子节点 */
  const renderNode = (f: FileNodeDTO, depth: number) => (
    <div key={f.fileId} className="group">
      <div
        onClick={() => handleSelectFile(f)}
        className={`flex items-center gap-1.5 px-2 py-1.5 rounded cursor-pointer text-sm ${
          selectedFile?.fileId === f.fileId
            ? 'bg-tech-500/15 text-tech-400'
            : 'text-ink-300 hover:bg-tech-500/5'
        }`}
        style={{ paddingLeft: 8 + depth * 16 }}
      >
        {f.fileType === 1 ? (
          <>
            {expandedDirs.has(f.fileId) ? (
              <ChevronDown className="w-3.5 h-3.5 flex-shrink-0" />
            ) : (
              <ChevronRight className="w-3.5 h-3.5 flex-shrink-0" />
            )}
            <Folder className="w-4 h-4 text-amber-400/70 flex-shrink-0" />
          </>
        ) : (
          <>
            <span className="w-3.5 flex-shrink-0" />
            <FileText className="w-4 h-4 text-tech-400/70 flex-shrink-0" />
          </>
        )}
        <span className="truncate flex-1">{f.fileName}</span>
        {f.fileType === 2 && f.fileSize !== undefined && (
          <span className="text-[10px] text-ink-600">{formatSize(f.fileSize)}</span>
        )}
        <button
          onClick={(e) => { e.stopPropagation(); handleDelete(f); }}
          className="opacity-0 group-hover:opacity-100 p-0.5 text-ink-500 hover:text-cinnabar-400"
        >
          <Trash2 className="w-3 h-3" />
        </button>
      </div>
      {f.fileType === 1 && expandedDirs.has(f.fileId) && renderChildren(f, depth)}
    </div>
  );

  const renderChildren = (dir: FileNodeDTO, depth: number) => {
    const children = dirChildren[dir.fileId];
    if (children === undefined) {
      return <Loader2 className="w-3 h-3 text-ink-600 animate-spin my-1" style={{ marginLeft: 24 + depth * 16 }} />;
    }
    if (children.length === 0) {
      return <div className="text-ink-600 text-xs py-1" style={{ paddingLeft: 24 + depth * 16 }}>空目录</div>;
    }
    return <div className="space-y-0.5">{children.map(c => renderNode(c, depth + 1))}</div>;
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Loader2 className="w-6 h-6 text-tech-400 animate-spin" />
      </div>
    );
  }

  return (
    <div>
      {/* Header */}
      <header className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2">
            <HardDrive className="w-6 h-6 text-tech-400" />
            {workspace?.workspaceName || '我的工作空间'}
          </h1>
          <p className="text-ink-400 text-sm mt-1">文件管理与开发环境</p>
        </div>
        <div className="flex items-center gap-3">
          {workspace && (
            <div className="text-right">
              <div className="text-xs text-ink-400">
                {formatSize(workspace.usedBytes)} / {formatSize(workspace.quotaBytes)}
              </div>
              <div className="w-32 h-1.5 bg-ink-800 rounded-full mt-1 overflow-hidden">
                <div
                  className={`h-full rounded-full ${workspace.usagePercent > 90 ? 'bg-cinnabar-500' : 'bg-tech-500'}`}
                  style={{ width: `${Math.min(workspace.usagePercent, 100)}%` }}
                />
              </div>
            </div>
          )}
          <button
            onClick={handleCreateSandbox}
            className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"
          >
            <Play className="w-4 h-4" /> 启动沙箱
          </button>
        </div>
      </header>

      <div className="flex gap-4" style={{ minHeight: 'calc(100vh - 200px)' }}>
        {/* File Tree */}
        <div className="w-72 flex-shrink-0 glass-dark rounded-xl p-3 overflow-y-auto">
          <div className="flex items-center justify-between mb-3 px-1">
            <span className="text-xs text-ink-400 font-medium">文件</span>
            <div className="flex items-center gap-1">
              <button
                onClick={() => { setCurrentParentId(null); setShowNewDir(true); }}
                className="p-1 text-ink-400 hover:text-tech-400 rounded"
                title="新建目录"
              >
                <FolderPlus className="w-4 h-4" />
              </button>
              <button
                onClick={() => fileInputRef.current?.click()}
                className="p-1 text-ink-400 hover:text-tech-400 rounded"
                title="上传文件"
              >
                <Upload className="w-4 h-4" />
              </button>
              <input
                ref={fileInputRef}
                type="file"
                className="hidden"
                onChange={handleUpload}
              />
            </div>
          </div>

          {showNewDir && (
            <div className="mb-2 px-2">
              <input
                value={newDirName}
                onChange={e => setNewDirName(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') handleCreateDir(); if (e.key === 'Escape') setShowNewDir(false); }}
                placeholder="目录名"
                className="w-full px-2 py-1 bg-ink-800/50 border border-tech-500/20 rounded text-xs text-ink-100 outline-none focus:border-tech-500/40"
                autoFocus
              />
            </div>
          )}

          <div className="space-y-0.5">
            {files.length === 0 ? (
              <div className="text-center py-8 text-ink-500 text-xs">空目录</div>
            ) : (
              files.map(f => renderNode(f, 0))
            )}
          </div>
        </div>

        {/* Content Editor */}
        <div className="flex-1 glass-dark rounded-xl p-4 flex flex-col">
          {selectedFile ? (
            <>
              <div className="flex items-center justify-between mb-3 pb-3 border-b border-tech-500/10">
                <div className="flex items-center gap-2">
                  <FileText className="w-4 h-4 text-tech-400" />
                  <span className="text-sm text-ink-100 font-medium">{selectedFile.fileName}</span>
                  <span className="text-[10px] text-ink-500">
                    v{selectedFile.version || 1} · {selectedFile.filePath}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  {viewMode === 'edit' && dirty && <span className="text-[10px] text-amber-400">● 未保存</span>}
                  {viewMode === 'edit' ? (
                    <>
                      <button
                        onClick={handleSave}
                        disabled={saving || !dirty}
                        className="flex items-center gap-1.5 px-3 py-1.5 btn-primary text-white text-xs rounded-lg disabled:opacity-50"
                      >
                        {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}
                        保存 Ctrl+S
                      </button>
                      <button
                        onClick={exitEdit}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-ink-300 hover:text-ink-100 border border-ink-700/60 text-xs rounded-lg transition-all"
                        title="退出编辑（未保存修改将还原）"
                      >
                        <Undo2 className="w-3 h-3" />退出
                      </button>
                    </>
                  ) : (
                    <button
                      onClick={() => setViewMode('edit')}
                      className="flex items-center gap-1.5 px-3 py-1.5 text-ink-300 hover:text-ink-100 border border-ink-700/60 text-xs rounded-lg transition-all"
                      title="编辑此文件（Ctrl+S 保存）"
                    >
                      <Pencil className="w-3 h-3" />编辑
                    </button>
                  )}
                </div>
              </div>
              {contentLoading ? (
                <div className="flex items-center justify-center py-20">
                  <Loader2 className="w-5 h-5 text-tech-400 animate-spin" />
                </div>
              ) : viewMode === 'edit' ? (
                <div className="flex-1 min-h-0" style={{ minHeight: '400px' }}>
                  <MonacoFileEditor
                    value={fileContent}
                    language={monacoLangOf(selectedFile.fileName)}
                    path={`ws-${selectedFile.fileId}`}
                    wordWrap="on"
                    onChange={(v) => { setFileContent(v ?? ''); setDirty(true); }}
                  />
                </div>
              ) : isMarkdownPath(selectedFile.fileName) ? (
                <MarkdownReader content={fileContent} />
              ) : isProbablyBinary(fileContent) ? (
                <div className="flex-1 flex flex-col items-center justify-center py-20 text-ink-500">
                  <FileText className="w-10 h-10 mb-3 opacity-30" />
                  <p className="text-sm">二进制文件不支持预览，请下载后查看</p>
                </div>
              ) : (
                <div className="flex-1 min-h-0" style={{ minHeight: '400px' }}>
                  <MonacoFileEditor
                    value={fileContent}
                    language={monacoLangOf(selectedFile.fileName)}
                    path={`ws-${selectedFile.fileId}`}
                    readOnly
                    wordWrap="off"
                  />
                </div>
              )}
            </>
          ) : (
            <div className="flex flex-col items-center justify-center py-20 text-ink-500">
              <FileText className="w-12 h-12 mb-3 opacity-30" />
              <p className="text-sm">选择左侧文件查看内容</p>
              <p className="text-xs mt-1">或上传文件 / 创建目录开始使用</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
