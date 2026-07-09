import React, { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Input, Button } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useApp } from '../stores/appStore'

const TIPS = [
  { key: 'Enter', text: '快速创建' },
  { key: '@', text: '提及文件' },
  { key: '/', text: '命令面板' },
]

export default function HomeView() {
  const [sessionTitle, setSessionTitle] = useState('')
  const navigate = useNavigate()
  const clearCurrent = useApp((s) => s.clearCurrent)
  const createSession = useApp((s) => s.createSession)

  useEffect(() => {
    clearCurrent()
  }, [clearCurrent])

  const handleCreate = useCallback(async () => {
    const title = sessionTitle.trim() || undefined
    const session = await createSession(title)
    setSessionTitle('')
    if (session?.id) {
      navigate(`/session/${session.id}`)
    }
  }, [sessionTitle, createSession, navigate])

  return (
    <div style={styles.homeView}>
      {/* Background Particles */}
      <div style={styles.bgParticles}>
        {Array.from({ length: 6 }, (_, i) => (
          <div
            key={i}
            style={{
              ...styles.particle,
              left: `${[10, 80, 20, 70, 50, 90][i]}%`,
              top: `${[20, 30, 70, 80, 10, 60][i]}%`,
              animationDelay: `${i * 2}s`,
            }}
          />
        ))}
      </div>

      <div style={styles.homeCenter}>
        <div style={styles.logoArea}>
          <div style={styles.logoIcon}>
            <div style={styles.logoGlow} />
            <img src="/gewu-logo.jpg" alt="格物致虚" style={styles.homeLogoImg} />
          </div>
          <h1 style={styles.appTitle}>
            格物<span style={styles.titleDot}>·</span>致虚
          </h1>
          <p style={styles.appDesc}>格物求实，致虚观道，智能编程</p>
          <div style={styles.divider} />
        </div>

        <div style={styles.createArea}>
          <Input
            value={sessionTitle}
            onChange={(e) => setSessionTitle(e.target.value)}
            onPressEnter={handleCreate}
            placeholder="为会话起个名字（可选）"
            style={styles.titleInput}
            size="large"
          />
          <Button
            type="primary"
            size="large"
            icon={<PlusOutlined />}
            onClick={handleCreate}
            style={styles.createBtn}
          >
            开始新会话
          </Button>
        </div>

        <div style={styles.tips}>
          {TIPS.map((tip, i) => (
            <div
              key={i}
              style={{
                ...styles.tip,
                animationDelay: `${0.8 + i * 0.1}s`,
              }}
            >
              <span style={styles.tipKey}>{tip.key}</span>
              <span style={styles.tipText}>{tip.text}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  homeView: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: '100%',
    padding: 24,
    background: 'radial-gradient(ellipse at center, var(--bg-secondary) 0%, var(--bg-primary) 70%)',
    position: 'relative',
    overflow: 'hidden',
  },
  bgParticles: {
    position: 'absolute',
    inset: 0,
    pointerEvents: 'none',
    overflow: 'hidden',
  },
  particle: {
    position: 'absolute',
    width: 4,
    height: 4,
    background: 'var(--accent)',
    borderRadius: '50%',
    opacity: 0,
    animation: 'particleFloat 12s ease-in-out infinite',
  },
  homeCenter: {
    width: '100%',
    maxWidth: 480,
    textAlign: 'center',
    position: 'relative',
    zIndex: 1,
  },
  logoArea: {
    marginBottom: 48,
    animation: 'fadeInUp 0.8s ease-out',
  },
  logoIcon: {
    width: 88,
    height: 88,
    margin: '0 auto 24px',
    position: 'relative',
    animation: 'logoFloat 4s ease-in-out infinite',
  },
  logoGlow: {
    position: 'absolute',
    inset: -8,
    borderRadius: 20,
    background: 'linear-gradient(135deg, var(--accent) 0%, transparent 50%)',
    opacity: 0.2,
    filter: 'blur(12px)',
    animation: 'glowPulse 3s ease-in-out infinite',
  },
  homeLogoImg: {
    width: '100%',
    height: '100%',
    borderRadius: 16,
    objectFit: 'contain',
    boxShadow: 'var(--shadow-lg)',
  },
  appTitle: {
    fontSize: 44,
    fontWeight: 700,
    color: 'var(--text-primary)',
    margin: '0 0 16px',
    letterSpacing: 6,
    background: 'linear-gradient(135deg, var(--text-primary) 0%, var(--accent) 40%, var(--text-primary) 80%)',
    backgroundSize: '200% auto',
    WebkitBackgroundClip: 'text',
    WebkitTextFillColor: 'transparent',
    backgroundClip: 'text',
  },
  titleDot: {
    WebkitTextFillColor: 'var(--accent)',
    opacity: 0.9,
    margin: '0 4px',
    fontWeight: 400,
    filter: 'drop-shadow(0 0 6px var(--accent-glow))',
  },
  appDesc: {
    color: 'var(--text-secondary)',
    fontSize: 15,
    margin: 0,
    lineHeight: 1.7,
    letterSpacing: 3,
    fontFamily: "'Noto Serif SC', 'STKaiti', serif",
    animation: 'fadeInUp 0.8s ease-out 0.4s both',
  },
  divider: {
    width: 60,
    height: 2,
    background: 'linear-gradient(90deg, transparent, var(--accent), transparent)',
    margin: '24px auto 0',
    animation: 'fadeInUp 0.8s ease-out 0.5s both',
  },
  createArea: {
    display: 'flex',
    gap: 12,
    marginBottom: 36,
    animation: 'fadeInUp 0.8s ease-out 0.6s both',
  },
  titleInput: {
    flex: 1,
    borderRadius: 'var(--radius-lg)',
    background: 'var(--bg-tertiary)',
    border: '1px solid var(--border)',
  },
  createBtn: {
    borderRadius: 'var(--radius-lg)',
    background: 'linear-gradient(135deg, var(--accent) 0%, #2db87a 100%)',
    border: 'none',
    fontWeight: 600,
    boxShadow: 'var(--shadow-sm), 0 0 16px var(--accent-muted)',
    whiteSpace: 'nowrap',
  },
  tips: {
    display: 'flex',
    justifyContent: 'center',
    gap: 28,
  },
  tip: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    fontSize: 12,
    color: 'var(--text-muted)',
    animation: 'fadeInUp 0.6s ease-out both',
  },
  tipKey: {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    minWidth: 24,
    height: 22,
    padding: '0 6px',
    borderRadius: 'var(--radius-sm)',
    border: '1px solid var(--border-light)',
    background: 'var(--bg-tertiary)',
    fontSize: 11,
    fontFamily: "'JetBrains Mono', monospace",
    color: 'var(--text-secondary)',
  },
  tipText: {},
}
