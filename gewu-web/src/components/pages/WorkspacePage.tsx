'use client';
import { useState, useEffect, useCallback, useRef } from 'react';
import { Loader2, FolderPlus, Upload, Trash2, ChevronRight, ChevronDown, Folder, FileText, Save, Play, HardDrive } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import {
  getMyWorkspace, listFiles, createDirectory, uploadFile,
  getFileContent, saveFileContent, deleteFile, createWorkspaceSandbox,
  type WorkspaceDTO, type FileNodeDTO,
} from '@/lib/workspace';

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
  const [selectedFile, setSelectedFile] = useState<FileNodeDTO | null>(null);
  const [fileContent, setFileContent] = useState('');
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
      return await listFiles(dirId);
    } catch {
      return [];
    }
  };

  const toggleDir = async (dir: FileNodeDTO) => {
    const next = new Set(expandedDirs);
    if (next.has(dir.fileId)) {
      next.delete(dir.fileId);
    } else {
      next.add(dir.fileId);
    }
    setExpandedDirs(next);
  };

  const handleSelectFile = async (file: FileNodeDTO) => {
    if (file.fileType === 1) {
      toggleDir(file);
      return;
    }
    setSelectedFile(file);
    setContentLoading(true);
    try {
      const content = await getFileContent(file.fileId);
      setFileContent(content);
    } catch (e) {
      toast('读取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setFileContent('');
    } finally {
      setContentLoading(false);
    }
  };

  const handleSave = async () => {
    if (!selectedFile) return;
    setSaving(true);
    try {
      const updated = await saveFileContent(selectedFile.fileId, fileContent);
      setSelectedFile(updated);
      toast('已保存 (v' + updated.version + ')', 'success');
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleCreateDir = async () => {
    if (!newDirName.trim()) return;
    try {
      await createDirectory(currentParentId, newDirName.trim());
      toast('目录已创建', 'success');
      setShowNewDir(false);
      setNewDirName('');
      const tree = await listFiles(currentParentId ?? undefined);
      setFiles(tree);
      if (currentParentId && expandedDirs.has(currentParentId)) {
        await loadChildren(currentParentId);
        setFiles(prev => [...prev]); // refresh handled by parent
      }
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
      const tree = await listFiles(currentParentId ?? undefined);
      setFiles(tree);
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
      }
      const tree = await listFiles(currentParentId ?? undefined);
      setFiles(tree);
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
              files.map(f => (
                <div key={f.fileId} className="group">
                  <div
                    onClick={() => handleSelectFile(f)}
                    className={`flex items-center gap-1.5 px-2 py-1.5 rounded cursor-pointer text-sm ${
                      selectedFile?.fileId === f.fileId
                        ? 'bg-tech-500/15 text-tech-400'
                        : 'text-ink-300 hover:bg-tech-500/5'
                    }`}
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
                  {/* Expanded children would be loaded on demand */}
                </div>
              ))
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
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="flex items-center gap-1.5 px-3 py-1.5 btn-primary text-white text-xs rounded-lg disabled:opacity-50"
                >
                  {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}
                  保存 Ctrl+S
                </button>
              </div>
              {contentLoading ? (
                <div className="flex items-center justify-center py-20">
                  <Loader2 className="w-5 h-5 text-tech-400 animate-spin" />
                </div>
              ) : (
                <textarea
                  value={fileContent}
                  onChange={e => setFileContent(e.target.value)}
                  onKeyDown={e => {
                    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
                      e.preventDefault();
                      handleSave();
                    }
                  }}
                  className="flex-1 w-full bg-ink-900/50 border border-tech-500/10 rounded-lg p-3 text-sm text-ink-100 font-mono outline-none focus:border-tech-500/30 resize-none"
                  style={{ minHeight: '400px' }}
                  spellCheck={false}
                />
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
