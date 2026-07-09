interface Props {
  visible: boolean
  fileName: string
  onAction: (action: 'save' | 'discard' | 'cancel') => void
}

export default function SaveDialog({ visible, fileName, onAction }: Props) {
  if (!visible) return null

  return (
    <div style={styles.overlay} onClick={() => onAction('cancel')}>
      <div style={styles.dialog} onClick={e => e.stopPropagation()}>
        <h3 style={styles.title}>保存文件</h3>
        <p style={styles.message}>文件 "{fileName}" 已修改，是否保存更改？</p>
        <div style={styles.actions}>
          <button style={styles.btnSave} onClick={() => onAction('save')}>保存</button>
          <button style={styles.btnDiscard} onClick={() => onAction('discard')}>不保存</button>
          <button style={styles.btnCancel} onClick={() => onAction('cancel')}>取消</button>
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  overlay: { position: 'fixed', inset: 0, background: 'rgba(0, 0, 0, 0.5)', zIndex: 2000, display: 'flex', alignItems: 'center', justifyContent: 'center' },
  dialog: { background: 'var(--bg-secondary, #252526)', border: '1px solid var(--border, #333)', borderRadius: 8, boxShadow: '0 8px 32px rgba(0, 0, 0, 0.4)', width: 400, maxWidth: '90vw' },
  title: { margin: 0, padding: '16px 20px 0', fontSize: 15, fontWeight: 600, color: 'var(--text-primary, #fff)' },
  message: { padding: '12px 20px 16px', fontSize: 13, color: 'var(--text-primary, #ccc)', lineHeight: 1.5, margin: 0 },
  actions: { display: 'flex', justifyContent: 'flex-end', gap: 8, padding: '12px 20px', borderTop: '1px solid var(--border, #333)' },
  btnSave: { padding: '6px 16px', borderRadius: 4, fontSize: 13, cursor: 'pointer', border: 'none', background: 'var(--accent, #007acc)', color: '#fff' },
  btnDiscard: { padding: '6px 16px', borderRadius: 4, fontSize: 13, cursor: 'pointer', border: '1px solid var(--border, #555)', background: 'var(--bg-tertiary, #3c3c3c)', color: 'var(--text-primary, #ccc)' },
  btnCancel: { padding: '6px 16px', borderRadius: 4, fontSize: 13, cursor: 'pointer', border: '1px solid var(--border, #555)', background: 'var(--bg-tertiary, #3c3c3c)', color: 'var(--text-primary, #ccc)' },
}
