import React from 'react'
import { useApp } from '../../stores/appStore'
import SessionPanel from './SessionPanel'
import ContextPanel from './ContextPanel'
import TodoPanel from './TodoPanel'
import ReviewSidePanel from './ReviewSidePanel'

export default function SidebarPanel() {
  const app = useApp()
  const showSession = app.showSessionPanel
  const showReview = app.showReviewPanel
  const showTest = app.showTestPanel
  const showTabs = !showSession && !showReview && !showTest

  const width = showReview || showTest ? 360 : 280

  return (
    <aside style={{
      width,
      background: 'var(--bg-secondary)',
      borderRight: '1px solid var(--border)',
      display: 'flex',
      flexDirection: 'column',
      flexShrink: 0,
      overflow: 'hidden',
      transition: 'width 0.2s ease',
    }}>
      {showTabs && (
        <div style={{ display: 'flex', borderBottom: '1px solid var(--border)', padding: '0 8px' }}>
          {(['project', 'context', 'todo'] as const).map(tab => {
            const labels = { project: '项目', context: '上下文', todo: '待办' }
            return (
              <button
                key={tab}
                onClick={() => app.set('sidePanelTab', tab)}
                style={{
                  background: 'none', border: 'none',
                  color: app.sidePanelTab === tab ? 'var(--text-primary)' : 'var(--text-secondary)',
                  cursor: 'pointer', padding: '10px 12px', fontSize: 12,
                  borderBottom: app.sidePanelTab === tab ? '2px solid var(--accent, #6366f1)' : '2px solid transparent',
                  transition: 'all 0.15s',
                }}
              >
                {labels[tab]}
              </button>
            )
          })}
        </div>
      )}

      <div style={{ flex: 1, overflowY: 'auto' }}>
        {showSession && <SessionPanel />}
        {showReview && <ReviewSidePanel />}
        {showTest && (
          <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
            测试面板（待实现）
          </div>
        )}
        {!showSession && !showReview && !showTest && app.sidePanelTab === 'project' && (
          <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
            {app.currentProject ? (
              <div>
                <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 8 }}>
                  {app.currentProject.name}
                </div>
                <div style={{ fontSize: 12 }}>{app.currentProject.path}</div>
              </div>
            ) : '请先打开项目'}
          </div>
        )}
        {!showSession && !showReview && !showTest && app.sidePanelTab === 'context' && <ContextPanel />}
        {!showSession && !showReview && !showTest && app.sidePanelTab === 'todo' && <TodoPanel />}
      </div>
    </aside>
  )
}
