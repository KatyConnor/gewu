import React, { useState, useRef, useEffect, useCallback } from 'react'
import { Input, Modal } from 'antd'
import { useApp } from '../../stores/appStore'

interface MenuState {
  visible: boolean
  session: { id: string; title?: string; share?: boolean } | null
  style: React.CSSProperties
}

export default function SessionPanel() {
  const app = useApp()
  const [menu, setMenu] = useState<MenuState>({ visible: false, session: null, style: {} })
  const [renameVisible, setRenameVisible] = useState(false)
  const [renameTitle, setRenameTitle] = useState('')
  const renameInputRef = useRef<HTMLInputElement>(null)

  const handleSelect = useCallback((id: string) => {
    app.selectSession(id)
    app.closeAllPanels()
  }, [app])

  const handleCreate = useCallback(async () => {
    const session = await app.createSession()
    if (session?.id) {
      app.closeAllPanels()
    }
  }, [app])

  const showMenu = useCallback((session: { id: string; title?: string; share?: boolean }, e: React.MouseEvent) => {
    e.preventDefault()
    const menuW = 170
    const menuH = 180
    const vw = window.innerWidth
    const vh = window.innerHeight
    let x = e.clientX
    let y = e.clientY
    if (x + menuW > vw) x = vw - menuW - 8
    if (y + menuH > vh) y = vh - menuH - 8
    if (x < 8) x = 8
    if (y < 8) y = 8
    setMenu({ visible: true, session, style: { left: x, top: y } })
  }, [])

  const closeMenu = useCallback(() => {
    setMenu({ visible: false, session: null, style: {} })
  }, [])

  const handleRename = useCallback(() => {
    if (!menu.session) return
    setRenameTitle(menu.session.title || '')
    closeMenu()
    setRenameVisible(true)
    setTimeout(() => renameInputRef.current?.focus(), 50)
  }, [menu.session, closeMenu])

  const confirmRename = useCallback(async () => {
    if (!menu.session || !renameTitle.trim()) return
    await app.updateSessionTitle(menu.session.id, renameTitle.trim())
    setRenameVisible(false)
  }, [menu.session, renameTitle, app])

  const handleDelete = useCallback(async () => {
    if (!menu.session) return
    const id = menu.session.id
    const title = menu.session.title || '此会话'
    closeMenu()
    Modal.confirm({
      title: `确定要删除"${title}"吗？`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        await app.deleteSession(id)
        app.addNotification('会话已删除', 'success')
      },
    })
  }, [menu.session, closeMenu, app])

  const formatTime = (ts?: number) => {
    if (!ts) return ''
    const d = new Date(ts)
    const now = new Date()
    const diff = now.getTime() - d.getTime()
    if (diff < 60000) return '刚刚'
    if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`
    if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`
    return `${d.getMonth() + 1}/${d.getDate()}`
  }

  const renderGroup = (label: string, sessions: typeof app.groupedSessions.today) => {
    if (!sessions.length) return null
    return (
      <div style={{ marginBottom: 8 }}>
        <div style={{
          padding: '8px 16px 4px',
          fontSize: 11,
          color: 'var(--text-muted)',
          textTransform: 'uppercase',
          letterSpacing: 0.5,
        }}>{label}</div>
        {sessions.map(s => (
          <div
            key={s.id}
            onClick={() => handleSelect(s.id)}
            onContextMenu={(e) => showMenu(s, e)}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '8px 12px',
              margin: '1px 8px',
              borderRadius: 6,
              cursor: 'pointer',
              transition: 'background 0.15s',
              background: app.currentSessionId === s.id ? 'var(--bg-active)' : 'transparent',
            }}
            onMouseEnter={(e) => {
              if (app.currentSessionId !== s.id) (e.currentTarget as HTMLElement).style.background = 'var(--bg-tertiary)'
            }}
            onMouseLeave={(e) => {
              if (app.currentSessionId !== s.id) (e.currentTarget as HTMLElement).style.background = 'transparent'
            }}
          >
            <span style={{
              flex: 1,
              fontSize: 13,
              color: 'var(--text-primary)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}>{s.title || '新会话'}</span>
            <button
              onClick={(e) => { e.stopPropagation(); showMenu(s, e) }}
              style={{
                background: 'none', border: 'none', color: 'var(--text-muted)',
                cursor: 'pointer', padding: '2px 6px', borderRadius: 4,
                fontSize: 14, opacity: 0, transition: 'opacity 0.15s',
              }}
              className="session-menu-btn"
            >⋯</button>
          </div>
        ))}
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        padding: '12px 16px', borderBottom: '1px solid var(--border)',
      }}>
        <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--text-primary)' }}>会话</h3>
        <button
          onClick={() => app.closeAllPanels()}
          style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 16, padding: '4px 8px', borderRadius: 4 }}
        >✕</button>
      </div>

      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
        <Input
          value={app.searchQuery}
          onChange={(e) => app.set('searchQuery', e.target.value)}
          placeholder="搜索会话..."
          style={{
            borderRadius: 6,
            background: 'var(--bg-primary)',
            color: 'var(--text-primary)',
            fontSize: 13,
          }}
        />
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
        {app.loading ? (
          <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>加载中...</div>
        ) : (
          <>
            {renderGroup('今天', app.groupedSessions.today)}
            {renderGroup('昨天', app.groupedSessions.yesterday)}
            {renderGroup('更早', app.groupedSessions.older)}
            {app.filteredSessions.length === 0 && (
              <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>暂无会话</div>
            )}
          </>
        )}
      </div>

      <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)' }}>
        <button
          onClick={handleCreate}
          style={{
            width: '100%', padding: 10,
            background: 'var(--accent, #6366f1)', color: 'white',
            border: 'none', borderRadius: 6, fontSize: 13, fontWeight: 500,
            cursor: 'pointer', transition: 'opacity 0.2s',
          }}
        >+ 新建会话</button>
      </div>

      {/* Context Menu */}
      {menu.visible && (
        <>
          <div onClick={closeMenu} onContextMenu={(e) => { e.preventDefault(); closeMenu() }} style={{ position: 'fixed', inset: 0, zIndex: 999 }} />
          <div style={{
            position: 'fixed', ...menu.style, zIndex: 1000,
            background: 'var(--bg-secondary)', border: '1px solid var(--border)',
            borderRadius: 8, padding: 4, minWidth: 160,
            boxShadow: '0 8px 24px rgba(0,0,0,0.2)',
          }} onClick={e => e.stopPropagation()}>
            <button style={menuItemStyle} onClick={handleRename}>
              <span style={menuIconStyle}>✏️</span> 重命名
            </button>
            <button style={menuItemStyle} onClick={closeMenu}>
              <span style={menuIconStyle}>🔀</span> Fork 会话
            </button>
            <button style={menuItemStyle} onClick={closeMenu}>
              <span style={menuIconStyle}>🔗</span> {menu.session?.share ? '取消分享' : '分享'}
            </button>
            <div style={{ height: 1, background: 'var(--border)', margin: '4px 0' }} />
            <button style={{ ...menuItemStyle, color: '#ef4444' }} onClick={handleDelete}>
              <span style={menuIconStyle}>🗑️</span> 删除
            </button>
          </div>
        </>
      )}

      {/* Rename Dialog */}
      <Modal
        title="重命名会话"
        open={renameVisible}
        onCancel={() => setRenameVisible(false)}
        onOk={confirmRename}
        okText="确定"
        cancelText="取消"
        destroyOnClose
      >
        <Input
          ref={renameInputRef as never}
          value={renameTitle}
          onChange={(e) => setRenameTitle(e.target.value)}
          placeholder="输入新标题"
          onPressEnter={confirmRename}
          style={{ marginTop: 12 }}
        />
      </Modal>
    </div>
  )
}

const menuItemStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8, width: '100%',
  padding: '7px 10px', background: 'none', border: 'none', borderRadius: 5,
  color: 'var(--text-secondary)', fontSize: 13, cursor: 'pointer', textAlign: 'left',
}

const menuIconStyle: React.CSSProperties = { fontSize: 14, width: 18, textAlign: 'center' }
