import { useCallback, useEffect, useState } from 'react';
import type { EngineConnection } from '../engine';
import TerminalPanel from './TerminalPanel';

/**
 * 开发空间视图（桌面本地模式，对齐 web DevWorkspacePage）：
 * Git 项目克隆（本地 git，非交互 ssh）/ 项目文件树浏览与预览 / 项目终端。
 * 克隆落地为本地项目（local_project 绑定目录），与会话/文件工具/终端的工作区机制互通。
 */
interface ProjectItem { projectId: string; projectName: string; directory?: string; repoUrl?: string; repoBranch?: string }
interface TreeNode { name: string; path: string; dir: boolean }

function TreeLevel({ nodes, depth, expanded, onToggle }: {
  nodes: TreeNode[]; depth: number;
  expanded: Set<string>; onToggle: (n: TreeNode) => void;
}) {
  return (
    <>
      {nodes.map((n) => (
        <div key={n.path}>
          <div className={`devws-node ${n.dir ? 'dir' : 'file'}`}
            style={{ paddingLeft: 8 + depth * 14 }} onClick={() => onToggle(n)}>
            <span className="devws-node-icon">{n.dir ? (expanded.has(n.path) ? '▾' : '▸') : '·'}</span>
            <span>{n.name}</span>
          </div>
          {n.dir && expanded.has(n.path) && <TreeLevel nodes={[]} depth={depth + 1} expanded={expanded} onToggle={onToggle} />}
        </div>
      ))}
    </>
  );
}

export default function DevWorkspaceView({ conn, projects, refreshProjects }: {
  conn: EngineConnection;
  projects: ProjectItem[];
  refreshProjects: () => void;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [cloneOpen, setCloneOpen] = useState(false);
  const [cloneForm, setCloneForm] = useState({ repoUrl: '', branch: '', projectName: '' });
  const [cloneErr, setCloneErr] = useState<string | null>(null);
  const [cloning, setCloning] = useState(false);
  const [tree, setTree] = useState<Record<string, TreeNode[]>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set(['']));
  const [preview, setPreview] = useState<{ path: string; content: string } | null>(null);
  const [termOpen, setTermOpen] = useState(false);

  const loadDir = useCallback(async (projectId: string, dir: string) => {
    try {
      const nodes = (await conn.data(
        `/v1/dev-workspace/projects/${projectId}/tree?path=${encodeURIComponent(dir)}`)) as TreeNode[];
      setTree((prev) => ({ ...prev, [dir]: nodes }));
    } catch { /* 目录读取失败按空处理 */ }
  }, [conn]);

  useEffect(() => {
    if (selected) {
      setTree({}); setExpanded(new Set([''])); setPreview(null);
      loadDir(selected, '');
    }
  }, [selected, loadDir]);

  const openNode = (node: TreeNode) => {
    if (!selected) return;
    if (node.dir) {
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(node.path)) next.delete(node.path); else next.add(node.path);
        return next;
      });
      if (!tree[node.path]) loadDir(selected, node.path);
    } else {
      conn.data(`/v1/dev-workspace/projects/${selected}/file?path=${encodeURIComponent(node.path)}`)
        .then((content) => setPreview({ path: node.path, content: String(content ?? '') }))
        .catch(() => setPreview({ path: node.path, content: '（读取失败）' }));
    }
  };

  const runClone = async () => {
    setCloning(true); setCloneErr(null);
    try {
      const body: Record<string, string> = { repoUrl: cloneForm.repoUrl.trim() };
      if (cloneForm.branch.trim()) body.branch = cloneForm.branch.trim();
      if (cloneForm.projectName.trim()) body.projectName = cloneForm.projectName.trim();
      const env = await conn.fetch('/v1/dev-workspace/clone', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      const json = (await env.json()) as { message?: string };
      if (!env.ok) throw new Error(json.message || `HTTP ${env.status}`);
      setCloneOpen(false);
      setCloneForm({ repoUrl: '', branch: '', projectName: '' });
      refreshProjects();
    } catch (e) {
      setCloneErr(e instanceof Error ? e.message : String(e));
    } finally {
      setCloning(false);
    }
  };

  const sel = projects.find((p) => p.projectId === selected) ?? null;
  const rootNodes = tree[''] ?? [];

  const renderLevel = (nodes: TreeNode[], depth: number): React.ReactNode =>
    nodes.map((n) => (
      <div key={n.path}>
        <div className={`devws-node ${n.dir ? 'dir' : 'file'}`}
          style={{ paddingLeft: 8 + depth * 14 }} onClick={() => openNode(n)}>
          <span className="devws-node-icon">{n.dir ? (expanded.has(n.path) ? '▾' : '▸') : '·'}</span>
          <span>{n.name}</span>
        </div>
        {n.dir && expanded.has(n.path) && renderLevel(tree[n.path] ?? [], depth + 1)}
      </div>
    ));

  return (
    <div className="devws">
      {/* ===== 左栏：克隆表单 + 项目列表 ===== */}
      <div className="devws-left">
        <div className="devws-clone-head">
          <span className="devws-label">Git 项目</span>
          <button className="btn small" onClick={() => { setCloneOpen((v) => !v); setCloneErr(null); }}>＋ 克隆仓库</button>
        </div>
        {cloneOpen && (
          <div className="devws-clone-form">
            <input placeholder="仓库地址（https:// 或 git@host:owner/repo）" value={cloneForm.repoUrl}
              onChange={(e) => setCloneForm((f) => ({ ...f, repoUrl: e.target.value }))} />
            <div className="devws-clone-row">
              <input placeholder="分支（可选）" value={cloneForm.branch}
                onChange={(e) => setCloneForm((f) => ({ ...f, branch: e.target.value }))} />
              <input placeholder="项目名（默认仓库名）" value={cloneForm.projectName}
                onChange={(e) => setCloneForm((f) => ({ ...f, projectName: e.target.value }))} />
            </div>
            {cloneErr && <p className="error">{cloneErr}</p>}
            <button className="btn primary devws-clone-go" disabled={cloning || !cloneForm.repoUrl.trim()} onClick={runClone}>
              {cloning ? '克隆中…（大仓库可能数分钟）' : '开始克隆'}
            </button>
          </div>
        )}
        <div className="devws-projects">
          {projects.length === 0 && <p className="hint">暂无项目——克隆仓库或在会话中创建</p>}
          {projects.map((p) => (
            <div key={p.projectId} className={`devws-project ${selected === p.projectId ? 'active' : ''}`}
              onClick={() => setSelected(p.projectId)}>
              <span className="devws-project-name">
                {p.projectName}{p.repoUrl && <span className="devws-git-badge" title={p.repoUrl}> git</span>}
              </span>
              <span className="devws-project-dir">{p.directory ?? ''}</span>
            </div>
          ))}
        </div>
      </div>

      {/* ===== 主区：文件树 + 预览 ===== */}
      <div className="devws-main">
        {!sel ? (
          <div className="devws-empty">选择左侧项目查看文件目录</div>
        ) : (
          <>
            <div className="devws-tree">{renderLevel(rootNodes, 0)}</div>
            <div className="devws-preview">
              {preview ? (
                <>
                  <div className="devws-preview-head">{preview.path}</div>
                  <pre className="devws-preview-body">{preview.content}</pre>
                </>
              ) : (
                <div className="devws-empty">点击左侧文件预览内容</div>
              )}
            </div>
          </>
        )}
      </div>

      {/* ===== 底部：项目终端（cwd=项目目录，复用多 Tab 终端） ===== */}
      <div className="devws-term">
        <div className="devws-term-head">
          <span className="devws-label">{sel ? `终端 · ${sel.projectName}` : '终端（选择项目后以其目录为工作区）'}</span>
          <button className="btn small" onClick={() => setTermOpen((v) => !v)}>
            {termOpen ? '收起终端' : '打开终端'}
          </button>
        </div>
        {termOpen && sel && (
          <TerminalPanel cwd={sel.directory || undefined} visible={termOpen} height={240}
            onAllClosed={() => setTermOpen(false)} />
        )}
      </div>
    </div>
  );
}
