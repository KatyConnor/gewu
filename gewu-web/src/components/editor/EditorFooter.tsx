import { useMemo } from 'react'

interface Props {
  cursorLine: number
  cursorColumn: number
  language: string
  isModified: boolean
  encoding: string
}

export default function EditorFooter({ cursorLine, cursorColumn, language, isModified, encoding }: Props) {
  const languageLabel = useMemo(() => {
    const labels: Record<string, string> = {
      javascript: 'JavaScript', typescript: 'TypeScript', vue: 'Vue',
      html: 'HTML', css: 'CSS', scss: 'SCSS', json: 'JSON',
      markdown: 'Markdown', python: 'Python', java: 'Java',
      go: 'Go', rust: 'Rust', sql: 'SQL', shell: 'Shell', yaml: 'YAML',
      plaintext: '纯文本',
    }
    return labels[language] || language
  }, [language])

  return (
    <div style={styles.footer}>
      <div style={styles.left}>
        <span style={styles.info}>
          <svg width={14} height={14} viewBox="0 0 14 14" fill="none">
            <rect x={2} y={2} width={10} height={10} rx={2} stroke="currentColor" strokeWidth={1.2} />
            <path d="M5 5H9M5 7H7" stroke="currentColor" strokeWidth={1.2} strokeLinecap="round" />
          </svg>
          行 {cursorLine}, 列 {cursorColumn}
        </span>
        <span style={styles.separator}>|</span>
        <span style={styles.info}>
          <svg width={14} height={14} viewBox="0 0 14 14" fill="none">
            <path d="M3 3L7 7L3 11M7 7H11" stroke="currentColor" strokeWidth={1.2} strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          {languageLabel}
        </span>
        <span style={styles.separator}>|</span>
        <span style={styles.info}>
          <svg width={14} height={14} viewBox="0 0 14 14" fill="none">
            <path d="M2 5H12M2 7H10M2 9H8" stroke="currentColor" strokeWidth={1.2} strokeLinecap="round" />
          </svg>
          {encoding}
        </span>
      </div>
      <div style={styles.right}>
        {isModified && (
          <span style={styles.modified}>
            <svg width={14} height={14} viewBox="0 0 14 14" fill="none">
              <circle cx={7} cy={7} r={3} fill="currentColor" />
            </svg>
            已修改
          </span>
        )}
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  footer: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', height: 24, padding: '0 12px', background: 'var(--accent, #007acc)', color: 'rgba(255, 255, 255, 0.9)', fontSize: 12, userSelect: 'none' },
  left: { display: 'flex', alignItems: 'center', gap: 8 },
  right: { display: 'flex', alignItems: 'center', gap: 8 },
  info: { display: 'flex', alignItems: 'center', gap: 4 },
  separator: { color: 'rgba(255, 255, 255, 0.5)' },
  modified: { display: 'flex', alignItems: 'center', gap: 4, color: '#fff' },
}
