'use client';
import { useState } from 'react';
import {
  Archive, ArchiveRestore, ArrowLeft, ChevronDown, ChevronRight,
  Folder, FolderOpen, MessageSquare, Pin, PinOff, Plus,
} from 'lucide-react';
import type { SessionDTO } from '@/lib/session';
import type { ProjectDTO } from '@/lib/project';

/**
 * 会话侧栏（S9 F1）——项目树形分组导航。
 * 每个项目（含「默认空间」组）下挂该项目/空间的会话；项目名右侧为
 * 该项目新建会话按钮；会话行悬浮提供置顶与归档操作；支持归档视图
 * （已归档会话取消归档后回到所属分组）。
 */
export default function SessionSidebar({
  sessions, archivedSessions, projects, activeSessionId, currentProjectId,
  showArchived, onToggleArchived, onSelectSession, onCreateSession,
  onArchive, onUnarchive, onPin, loading, user, onBackHome,
}: {
  sessions: SessionDTO[];
  archivedSessions: SessionDTO[];
  projects: ProjectDTO[];
  activeSessionId: string | null;
  currentProjectId: string | null;
  showArchived: boolean;
  onToggleArchived: () => void;
  onSelectSession: (session: SessionDTO) => void;
  onCreateSession: (projectId: string | null) => void;
  onArchive: (sessionId: string) => void;
  onUnarchive: (sessionId: string) => void;
  onPin: (sessionId: string, pinned: boolean) => void;
  loading: boolean;
  user: { name?: string } | null;
  onBackHome: () => void;
}) {
  // 折叠的项目分组（默认全展开；当前会话所在分组不可折叠隐藏）
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const toggleGroup = (key: string) => setCollapsed(prev => {
    const next = new Set(prev);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });

  const defaultSessions = sessions.filter(s => !s.projectId);
  const projectGroups = projects.map(p => ({
    project: p,
    sessions: sessions.filter(s => s.projectId === p.projectId),
  }));
  // 会话引用了不在项目列表中的项目（项目被删/无权限）：归入未知分组避免丢失
  const knownIds = new Set(projects.map(p => p.projectId));
  const orphanSessions = sessions.filter(s => s.projectId && !knownIds.has(s.projectId));

  const renderSessionRow = (session: SessionDTO, archived = false) => {
    const active = activeSessionId === session.sessionId;
    return (
      <div
        key={session.sessionId}
        onClick={() => onSelectSession(session)}
        className={`group/item pl-8 pr-2 py-2 mx-2 rounded-lg cursor-pointer transition-all border ${
          active ? 'bg-tech-500/10 border-tech-500/20' : 'border-transparent hover:bg-tech-500/5 hover:border-tech-500/10'
        }`}
      >
        <div className="flex items-center gap-1.5">
          {session.pinned === 1 && !archived && (
            <Pin className="w-3 h-3 text-tech-400 flex-shrink-0" aria-label="已置顶" />
          )}
          {archived && <Archive className="w-3 h-3 text-ink-500 flex-shrink-0" aria-label="已归档" />}
          <p className={`text-sm truncate flex-1 ${archived ? 'text-ink-500' : 'text-ink-200'}`}>{session.title}</p>
          {archived ? (
            <button
              onClick={(e) => { e.stopPropagation(); onUnarchive(session.sessionId); }}
              className="p-1 text-ink-500 hover:text-tech-400 rounded transition-all opacity-0 group-hover/item:opacity-100"
              aria-label="取消归档"
              title="取消归档，恢复到所属分组"
            ><ArchiveRestore className="w-3.5 h-3.5" /></button>
          ) : (
            <>
              <button
                onClick={(e) => { e.stopPropagation(); onPin(session.sessionId, session.pinned !== 1); }}
                className="p-1 text-ink-500 hover:text-tech-400 rounded transition-all opacity-0 group-hover/item:opacity-100"
                aria-label={session.pinned === 1 ? '取消置顶' : '置顶'}
                title={session.pinned === 1 ? '取消置顶' : '置顶'}
              >{session.pinned === 1 ? <PinOff className="w-3.5 h-3.5" /> : <Pin className="w-3.5 h-3.5" />}</button>
              <button
                onClick={(e) => { e.stopPropagation(); onArchive(session.sessionId); }}
                className="p-1 text-ink-500 hover:text-amber-400 rounded transition-all opacity-0 group-hover/item:opacity-100"
                aria-label="归档会话"
                title="归档会话（归档后可在「已归档」视图中找回）"
              ><Archive className="w-3.5 h-3.5" /></button>
            </>
          )}
        </div>
        <p className="text-xs text-ink-500 mt-1">
          {session.lastMessageAt ? new Date(session.lastMessageAt).toLocaleDateString('zh-CN') : '刚刚'}
          {session.messageCount > 0 && ` · ${session.messageCount}条消息`}
        </p>
      </div>
    );
  };

  const renderProjectGroup = (project: ProjectDTO, groupSessions: SessionDTO[]) => {
    const key = project.projectId;
    const isCollapsed = collapsed.has(key) && currentProjectId !== key;
    const Icon = isCollapsed ? Folder : FolderOpen;
    return (
      <div key={key}>
        <div className="group/proj flex items-center gap-1.5 pl-2 pr-2 py-1.5 cursor-pointer rounded-md hover:bg-tech-500/5"
          onClick={() => toggleGroup(key)}>
          <button className="p-0.5 text-ink-500 hover:text-ink-300" aria-label={isCollapsed ? '展开' : '折叠'} tabIndex={-1}>
            {isCollapsed ? <ChevronRight className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
          </button>
          <Icon className="w-3.5 h-3.5 text-tech-400 flex-shrink-0" />
          <span className="text-xs font-medium text-ink-300 truncate flex-1">{project.projectName}</span>
          <span className="text-[10px] text-ink-600 flex-shrink-0">{groupSessions.length}</span>
          <button
            onClick={(e) => { e.stopPropagation(); onCreateSession(project.projectId); }}
            className="p-1 text-ink-500 hover:text-tech-400 rounded transition-all opacity-0 group/proj:opacity-100"
            aria-label={`在 ${project.projectName} 新建会话`}
            title={`在 ${project.projectName} 新建会话`}
          ><Plus className="w-3.5 h-3.5" /></button>
        </div>
        {!isCollapsed && (
          <div className="space-y-0.5 mt-0.5">
            {groupSessions.length === 0 ? (
              <p className="pl-8 pr-3 py-1.5 text-[11px] text-ink-600">暂无会话</p>
            ) : groupSessions.map(s => renderSessionRow(s))}
          </div>
        )}
      </div>
    );
  };

  const renderDefaultGroup = () => {
    const isCollapsed = collapsed.has('__default__') && currentProjectId !== null;
    return (
      <div>
        <div className="group/space flex items-center gap-1.5 pl-2 pr-2 py-1.5 cursor-pointer rounded-md hover:bg-tech-500/5"
          onClick={() => toggleGroup('__default__')}>
          <button className="p-0.5 text-ink-500 hover:text-ink-300" aria-label={isCollapsed ? '展开' : '折叠'} tabIndex={-1}>
            {isCollapsed ? <ChevronRight className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
          </button>
          <MessageSquare className="w-3.5 h-3.5 text-ink-400 flex-shrink-0" />
          <span className="text-xs font-medium text-ink-300 truncate flex-1">默认空间</span>
          <span className="text-[10px] text-ink-600 flex-shrink-0">{defaultSessions.length}</span>
          <button
            onClick={(e) => { e.stopPropagation(); onCreateSession(null); }}
            className="p-1 text-ink-500 hover:text-tech-400 rounded transition-all opacity-0 group/space:opacity-100"
            aria-label="新建会话"
            title="在默认空间新建会话"
          ><Plus className="w-3.5 h-3.5" /></button>
        </div>
        {!isCollapsed && (
          <div className="space-y-0.5 mt-0.5">
            {defaultSessions.length === 0 ? (
              <p className="pl-8 pr-3 py-1.5 text-[11px] text-ink-600">暂无会话</p>
            ) : defaultSessions.map(s => renderSessionRow(s))}
          </div>
        )}
      </div>
    );
  };

  return (
    <aside className="w-72 border-r flex flex-col h-full flex-shrink-0 sidebar-bg">
      {/* 头部：标题 + 归档视图切换 */}
      <div className="px-3 pt-3 pb-2 flex items-center justify-between">
        <span className="text-xs font-semibold text-ink-200 tracking-wide">会话</span>
        <button
          onClick={onToggleArchived}
          className={`flex items-center gap-1 px-2 py-1 rounded-md text-[11px] transition-all border ${
            showArchived
              ? 'text-amber-300 bg-amber-500/10 border-amber-500/30'
              : 'text-ink-400 hover:text-ink-200 border-transparent hover:border-tech-500/20'
          }`}
          aria-label="已归档会话"
          title={showArchived ? '返回活跃会话' : '查看已归档会话'}
        >
          <Archive className="w-3 h-3" />
          {showArchived ? '返回' : '已归档'}
        </button>
      </div>

      <div className="flex-1 overflow-y-auto scrollbar-thin pb-2">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <div className="w-4 h-4 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : showArchived ? (
          archivedSessions.length === 0 ? (
            <div className="px-3 py-8 text-center"><p className="text-xs text-ink-500">暂无已归档会话</p></div>
          ) : (
            <div className="space-y-0.5 pt-1">{archivedSessions.map(s => renderSessionRow(s, true))}</div>
          )
        ) : (
          <>
            {renderDefaultGroup()}
            {projectGroups.map(g => renderProjectGroup(g.project, g.sessions))}
            {orphanSessions.length > 0 && (
              <div>
                <div className="flex items-center gap-1.5 pl-2 pr-2 py-1.5">
                  <Folder className="w-3.5 h-3.5 text-ink-500 flex-shrink-0" />
                  <span className="text-xs font-medium text-ink-400 truncate flex-1">其他项目</span>
                </div>
                <div className="space-y-0.5">{orphanSessions.map(s => renderSessionRow(s))}</div>
              </div>
            )}
          </>
        )}
      </div>

      {/* 底部用户卡片 */}
      <div className="p-3 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
        <div className="flex items-center gap-3 px-2 py-1.5">
          <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center text-white font-semibold text-xs">
            {(user?.name || '用').charAt(0)}
          </div>
          <div className="flex-1 min-w-0"><p className="text-xs text-ink-100 truncate font-medium">{user?.name || '当前用户'}</p></div>
          <button onClick={onBackHome} className="p-1.5 text-ink-500 hover:text-tech-400 rounded-md transition-all" title="返回主页">
            <ArrowLeft className="w-4 h-4" />
          </button>
        </div>
      </div>
    </aside>
  );
}
