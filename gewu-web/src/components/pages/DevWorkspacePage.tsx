'use client';
import { useState, useEffect, useCallback } from 'react';
import { Loader2, Play, Square, GitBranch, Terminal, Save, Upload, Plus, RefreshCw, Key } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import {
  getDevWorkspace, startSandbox, stopSandbox, listFiles, readFile, writeFile,
  execCommand, cloneRepo, listProjects, gitPull, gitPush, buildProject, runProject,
  listGitCredentials, addGitCredential, deleteGitCredential,
  type DevWorkspaceInfo, type GitProject, type GitCredential, type ExecResult,
} from '@/lib/dev-workspace';

export default function DevWorkspacePage() {
  const toast = useToast();
  const [wsInfo, setWsInfo] = useState<DevWorkspaceInfo | null>(null);
  const [projects, setProjects] = useState<GitProject[]>([]);
  const [credentials, setCredentials] = useState<GitCredential[]>([]);
  const [loading, setLoading] = useState(true);
  const [sandboxLoading, setSandboxLoading] = useState(false);
  const [selectedProject, setSelectedProject] = useState<GitProject | null>(null);
  const [fileContent, setFileContent] = useState('');
  const [currentPath, setCurrentPath] = useState('');
  const [contentLoading, setContentLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [terminalOutput, setTerminalOutput] = useState('');
  const [execLoading, setExecLoading] = useState(false);
  const [execInput, setExecInput] = useState('');
  const [showClone, setShowClone] = useState(false);
  const [showCred, setShowCred] = useState(false);
  const [cloneUrl, setCloneUrl] = useState('');
  const [cloneName, setCloneName] = useState('');
  const [cloneBranch, setCloneBranch] = useState('main');
  const [credName, setCredName] = useState('');
  const [credType, setCredType] = useState('token');
  const [credValue, setCredValue] = useState('');
  const [credHost, setCredHost] = useState('github.com');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ws, projs, creds] = await Promise.all([
        getDevWorkspace(), listProjects(), listGitCredentials(),
      ]);
      setWsInfo(ws);
      setProjects(projs || []);
      setCredentials(creds || []);
    } catch (e) {
      toast('加载失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const handleStartSandbox = async () => {
    setSandboxLoading(true);
    try {
      await startSandbox();
      toast('开发沙箱已启动', 'success');
      load();
    } catch (e) {
      toast('启动失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSandboxLoading(false);
    }
  };

  const handleStopSandbox = async () => {
    try {
      await stopSandbox();
      toast('沙箱已停止', 'success');
      load();
    } catch (e) {
      toast('停止失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleReadFile = async (path: string) => {
    setCurrentPath(path);
    setContentLoading(true);
    try {
      const content = await readFile(path);
      setFileContent(content);
    } catch (e) {
      toast('读取失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setFileContent('');
    } finally {
      setContentLoading(false);
    }
  };

  const handleSaveFile = async () => {
    if (!currentPath) return;
    setSaving(true);
    try {
      await writeFile(currentPath, fileContent);
      toast('已保存', 'success');
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleExec = async () => {
    if (!execInput.trim()) return;
    setExecLoading(true);
    setTerminalOutput('$ ' + execInput + '\n');
    try {
      const result = await execCommand(execInput, 60);
      setTerminalOutput(prev => prev + (result.stdout || '') + (result.stderr ? '\n[stderr]\n' + result.stderr : '') + `\n[exit: ${result.exitCode}]\n`);
    } catch (e) {
      setTerminalOutput(prev => prev + 'Error: ' + (e instanceof Error ? e.message : String(e)) + '\n');
    } finally {
      setExecLoading(false);
      setExecInput('');
    }
  };

  const handleClone = async () => {
    if (!cloneUrl.trim() || !cloneName.trim()) return;
    setExecLoading(true);
    setTerminalOutput('Cloning ' + cloneUrl + '...\n');
    try {
      const project = await cloneRepo(cloneUrl, cloneName, cloneBranch);
      toast(`项目 ${cloneName} 克隆成功`, 'success');
      setShowClone(false);
      setCloneUrl(''); setCloneName(''); setCloneBranch('main');
      load();
      setSelectedProject(project);
    } catch (e) {
      toast('克隆失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      setTerminalOutput(prev => prev + 'Error: ' + (e instanceof Error ? e.message : String(e)) + '\n');
    } finally {
      setExecLoading(false);
    }
  };

  const handleGitPull = async () => {
    if (!selectedProject) return;
    try {
      await gitPull(selectedProject.projectId);
      toast('Pull 成功', 'success');
      load();
    } catch (e) {
      toast('Pull 失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleGitPush = async () => {
    if (!selectedProject) return;
    const message = prompt('Commit message:');
    if (!message) return;
    try {
      const result = await gitPush(selectedProject.projectId, message);
      toast('Push 完成', 'success');
      setTerminalOutput(result || 'Push completed');
    } catch (e) {
      toast('Push 失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleBuild = async (cmd: string) => {
    if (!selectedProject) return;
    setExecLoading(true);
    setTerminalOutput('$ ' + cmd + '\n');
    try {
      const result = await buildProject(selectedProject.projectId, cmd);
      setTerminalOutput(prev => prev + (result.stdout || '') + (result.stderr ? '\n[stderr]\n' + result.stderr : '') + `\n[exit: ${result.exitCode}]\n`);
    } catch (e) {
      setTerminalOutput(prev => prev + 'Error: ' + (e instanceof Error ? e.message : String(e)) + '\n');
    } finally {
      setExecLoading(false);
    }
  };

  const handleAddCred = async () => {
    if (!credName.trim() || !credValue.trim()) return;
    try {
      await addGitCredential(credName, credType, credValue, credHost);
      toast('凭证已添加', 'success');
      setShowCred(false);
      setCredName(''); setCredValue('');
      load();
    } catch (e) {
      toast('添加失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  if (loading) {
    return <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>;
  }

  const isRunning = wsInfo?.sandboxStatus === 'running';

  return (
    <div>
      <header className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2">
            <GitBranch className="w-6 h-6 text-tech-400" />开发工作空间
          </h1>
          <p className="text-ink-400 text-sm mt-1">Git 项目管理 · 代码编辑 · 编译运行</p>
        </div>
        <div className="flex items-center gap-3">
          <div className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs ${isRunning ? 'bg-green-500/10 text-green-400' : 'bg-ink-800 text-ink-500'}`}>
            <span className={`w-2 h-2 rounded-full ${isRunning ? 'bg-green-400 animate-pulse' : 'bg-ink-600'}`} />
            {wsInfo?.sandboxStatusDesc || '未创建'}
          </div>
          {isRunning ? (
            <button onClick={handleStopSandbox} className="flex items-center gap-2 px-4 py-2 border border-cinnabar-500/20 text-cinnabar-400 text-sm rounded-lg hover:bg-cinnabar-500/10">
              <Square className="w-4 h-4" />停止
            </button>
          ) : (
            <button onClick={handleStartSandbox} disabled={sandboxLoading} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50">
              {sandboxLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}启动沙箱
            </button>
          )}
        </div>
      </header>

      <div className="flex gap-4" style={{ minHeight: 'calc(100vh - 200px)' }}>
        {/* 左侧：项目列表 + 凭证 */}
        <div className="w-64 flex-shrink-0 space-y-4">
          <div className="glass-dark rounded-xl p-3">
            <div className="flex items-center justify-between mb-2 px-1">
              <span className="text-xs text-ink-400 font-medium">Git 项目</span>
              <button onClick={() => setShowClone(true)} className="p-1 text-ink-400 hover:text-tech-400" title="克隆仓库">
                <Plus className="w-4 h-4" />
              </button>
            </div>
            <div className="space-y-1">
              {projects.length === 0 ? (
                <div className="text-center py-4 text-ink-500 text-xs">无项目</div>
              ) : projects.map(p => (
                <button key={p.projectId} onClick={() => setSelectedProject(p)}
                  className={`w-full text-left px-2 py-2 rounded text-sm ${selectedProject?.projectId === p.projectId ? 'bg-tech-500/15 text-tech-400' : 'text-ink-300 hover:bg-tech-500/5'}`}>
                  <div className="flex items-center gap-1.5">
                    <GitBranch className="w-3.5 h-3.5 flex-shrink-0" />
                    <span className="truncate">{p.projectName}</span>
                  </div>
                  <div className="text-[10px] text-ink-600 ml-5">{p.repoBranch} · {p.cloneStatus}</div>
                </button>
              ))}
            </div>
          </div>

          <div className="glass-dark rounded-xl p-3">
            <div className="flex items-center justify-between mb-2 px-1">
              <span className="text-xs text-ink-400 font-medium">Git 凭证</span>
              <button onClick={() => setShowCred(true)} className="p-1 text-ink-400 hover:text-tech-400" title="添加凭证">
                <Key className="w-4 h-4" />
              </button>
            </div>
            <div className="space-y-1">
              {credentials.length === 0 ? (
                <div className="text-center py-4 text-ink-500 text-xs">无凭证</div>
              ) : credentials.map(c => (
                <div key={c.credentialId} className="flex items-center justify-between px-2 py-1.5 text-xs text-ink-300">
                  <div className="truncate">
                    <span className="text-ink-200">{c.credName}</span>
                    <span className="text-ink-600 ml-1">{c.credType}</span>
                  </div>
                  <button onClick={async () => { await deleteGitCredential(c.credentialId); load(); }} className="text-ink-600 hover:text-cinnabar-400">✕</button>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* 中间：代码编辑器 */}
        <div className="flex-1 glass-dark rounded-xl p-4 flex flex-col">
          <div className="flex items-center justify-between mb-3 pb-3 border-b border-tech-500/10">
            <div className="flex items-center gap-2">
              <input
                value={currentPath}
                onChange={e => setCurrentPath(e.target.value)}
                placeholder="文件路径 (如 projects/my-app/src/Main.java)"
                className="px-2 py-1 bg-ink-800/50 border border-tech-500/10 rounded text-xs text-ink-100 outline-none focus:border-tech-500/30 w-80"
              />
              <button onClick={() => handleReadFile(currentPath)} className="px-2 py-1 text-xs text-tech-400 hover:bg-tech-500/10 rounded">读取</button>
            </div>
            <div className="flex items-center gap-2">
              {selectedProject && (
                <>
                  <button onClick={handleGitPull} className="flex items-center gap-1 px-2 py-1 text-xs text-ink-300 hover:text-tech-400"><RefreshCw className="w-3 h-3" />Pull</button>
                  <button onClick={handleGitPush} className="flex items-center gap-1 px-2 py-1 text-xs text-ink-300 hover:text-tech-400"><Upload className="w-3 h-3" />Push</button>
                  <button onClick={() => handleBuild('mvn compile')} className="px-2 py-1 text-xs text-ink-300 hover:text-tech-400">构建</button>
                  <button onClick={() => { const cmd = prompt('运行命令:', 'mvn spring-boot:run'); if (cmd) { runProject(selectedProject.projectId, cmd).then(r => setTerminalOutput(r.stdout || r.stderr)); } }} className="px-2 py-1 text-xs text-ink-300 hover:text-tech-400">运行</button>
                </>
              )}
              <button onClick={handleSaveFile} disabled={saving} className="flex items-center gap-1 px-2 py-1 text-xs btn-primary text-white rounded disabled:opacity-50">
                {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}保存
              </button>
            </div>
          </div>
          {contentLoading ? (
            <div className="flex items-center justify-center py-20"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>
          ) : (
            <textarea
              value={fileContent}
              onChange={e => setFileContent(e.target.value)}
              placeholder="// 选择文件路径并读取，或直接编辑后保存"
              className="flex-1 w-full bg-ink-900/50 border border-tech-500/10 rounded-lg p-3 text-sm text-ink-100 font-mono outline-none focus:border-tech-500/30 resize-none"
              style={{ minHeight: '300px' }}
              spellCheck={false}
            />
          )}
        </div>

        {/* 右侧：终端 */}
        <div className="w-80 flex-shrink-0 glass-dark rounded-xl p-3 flex flex-col">
          <div className="flex items-center gap-1.5 mb-2 text-xs text-ink-400 font-medium">
            <Terminal className="w-3.5 h-3.5" />终端
          </div>
          <div className="flex-1 bg-ink-900/70 rounded-lg p-2 text-xs text-green-400 font-mono overflow-auto" style={{ minHeight: '200px', maxHeight: '400px' }}>
            <pre className="whitespace-pre-wrap">{terminalOutput || '$ '}</pre>
            {execLoading && <Loader2 className="w-3 h-3 animate-spin inline" />}
          </div>
          <div className="flex items-center gap-1 mt-2">
            <span className="text-xs text-ink-500">$</span>
            <input
              value={execInput}
              onChange={e => setExecInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') handleExec(); }}
              placeholder="输入命令..."
              className="flex-1 px-2 py-1 bg-ink-800/50 border border-tech-500/10 rounded text-xs text-ink-100 font-mono outline-none focus:border-tech-500/30"
              disabled={!isRunning}
            />
            <button onClick={handleExec} disabled={!isRunning || execLoading} className="px-2 py-1 text-xs text-tech-400 hover:bg-tech-500/10 rounded disabled:opacity-30">↵</button>
          </div>
        </div>
      </div>

      {/* 克隆仓库弹窗 */}
      {showClone && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowClone(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-semibold text-ink-50 mb-4">克隆 Git 仓库</h3>
            <div className="space-y-3">
              <div><label className="block text-xs text-ink-400 mb-1">仓库地址 *</label><input value={cloneUrl} onChange={e => setCloneUrl(e.target.value)} placeholder="https://github.com/user/repo.git" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">项目名称 *</label><input value={cloneName} onChange={e => setCloneName(e.target.value)} placeholder="my-project" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">分支</label><input value={cloneBranch} onChange={e => setCloneBranch(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setShowClone(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleClone} disabled={!cloneUrl.trim() || !cloneName.trim() || execLoading} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{execLoading && <Loader2 className="w-4 h-4 animate-spin" />}克隆</button>
            </div>
          </div>
        </div>
      )}

      {/* 添加凭证弹窗 */}
      {showCred && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowCred(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-semibold text-ink-50 mb-4">添加 Git 凭证</h3>
            <div className="space-y-3">
              <div><label className="block text-xs text-ink-400 mb-1">名称 *</label><input value={credName} onChange={e => setCredName(e.target.value)} placeholder="GitHub Token" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">类型</label>
                <select value={credType} onChange={e => setCredType(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none">
                  <option value="token">Personal Access Token</option>
                  <option value="ssh_key">SSH Key</option>
                </select>
              </div>
              <div><label className="block text-xs text-ink-400 mb-1">{credType === 'ssh_key' ? '私钥内容 *' : 'Token *'}</label><textarea value={credValue} onChange={e => setCredValue(e.target.value)} rows={4} placeholder={credType === 'ssh_key' ? '-----BEGIN OPENSSH PRIVATE KEY-----\n...' : 'ghp_xxxxxxxxxxxx'} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 font-mono outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">Git 服务地址</label><input value={credHost} onChange={e => setCredHost(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setShowCred(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleAddCred} disabled={!credName.trim() || !credValue.trim()} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50">添加</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
