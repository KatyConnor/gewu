import React, { useState, useEffect, useMemo } from 'react'
import { useApp } from '../../stores/appStore'

interface Todo {
  content: string
  status: 'completed' | 'in_progress' | 'pending' | 'cancelled'
  priority: 'high' | 'medium' | 'low'
}

export default function TodoPanel() {
  const app = useApp()
  const [todos, setTodos] = useState<Todo[]>([])
  const [loading, setLoading] = useState(false)

  const statusIcon = (status: string) => {
    const map: Record<string, string> = { completed: '✅', in_progress: '🔄', pending: '⬜', cancelled: '❌' }
    return map[status] || '⬜'
  }

  const priorityLabel = (p: string) => {
    const map: Record<string, string> = { high: '高', medium: '中', low: '低' }
    return map[p] || p
  }

  const sortedTodos = useMemo(() => {
    const order: Record<string, number> = { in_progress: 0, pending: 1, completed: 2, cancelled: 3 }
    return [...todos].sort((a, b) => (order[a.status] ?? 4) - (order[b.status] ?? 4))
  }, [todos])

  const completedCount = useMemo(() => todos.filter(t => t.status === 'completed').length, [todos])

  useEffect(() => {
    if (!app.currentSessionId) return
    setLoading(true)
    fetch(`/api/session/${app.currentSessionId}/todo`)
      .then(r => r.json())
      .then(result => {
        const arr = Array.isArray(result) ? result : (result?.data && Array.isArray(result.data) ? result.data : [])
        setTodos(arr)
      })
      .catch(() => setTodos([]))
      .finally(() => setLoading(false))
  }, [app.currentSessionId])

  return (
    <div style={{ padding: 12 }}>
      <div style={{
        fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase',
        letterSpacing: 0.5, marginBottom: 8, fontWeight: 500,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      }}>
        待办事项
        {todos.length > 0 && (
          <span style={{ textTransform: 'none', letterSpacing: 0, fontWeight: 400, color: 'var(--accent)' }}>
            {completedCount}/{todos.length}
          </span>
        )}
      </div>

      {!todos.length && !loading && (
        <div style={{ fontSize: 12, color: 'var(--text-muted)', padding: '12px 0' }}>无待办事项</div>
      )}
      {loading && (
        <div style={{ fontSize: 12, color: 'var(--text-muted)', padding: '12px 0' }}>加载中...</div>
      )}

      {sortedTodos.map((todo, i) => (
        <div key={i} style={{
          display: 'flex', alignItems: 'flex-start', gap: 6,
          padding: '6px 0', borderBottom: '1px solid var(--border)', fontSize: 12,
        }}>
          <span style={{ flexShrink: 0, fontSize: 13 }}>{statusIcon(todo.status)}</span>
          <span style={{
            flex: 1, color: todo.status === 'completed' ? 'var(--text-muted)' : 'var(--text-secondary)',
            lineHeight: 1.4,
            textDecoration: todo.status === 'completed' ? 'line-through' : undefined,
          }}>{todo.content}</span>
          <span style={{
            padding: '1px 5px', borderRadius: 3, fontSize: 10, fontWeight: 600, flexShrink: 0,
            background: todo.priority === 'high' ? '#ef444422' : todo.priority === 'medium' ? '#f59e0b22' : '#22c55e22',
            color: todo.priority === 'high' ? '#ef4444' : todo.priority === 'medium' ? '#f59e0b' : '#22c55e',
          }}>{priorityLabel(todo.priority)}</span>
        </div>
      ))}
    </div>
  )
}
