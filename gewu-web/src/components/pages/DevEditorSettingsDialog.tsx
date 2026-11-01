'use client';
// 编辑器设置面板（IDEA Settings/Preferences 对齐 · Ctrl+Alt+S）。
// 偏好全局生效并持久化 localStorage；字段与 agent-ui EditorSettings 一一对应。
import Modal from '@/components/ui/Modal';
import type { EditorSettings } from '@/lib/editorSettings';

function Row({ label, title, children }: { label: string; title?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5" title={title}>
      <span className="text-xs text-ink-300">{label}</span>
      {children}
    </div>
  );
}

function Toggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <button onClick={() => onChange(!value)}
      className={`relative w-9 h-5 rounded-full transition-colors flex-shrink-0 ${value ? 'bg-tech-500' : 'bg-ink-700'}`}
      role="switch" aria-checked={value}>
      <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${value ? 'left-[18px]' : 'left-0.5'}`} />
    </button>
  );
}

const inputCls = 'w-16 px-2 py-1 text-xs text-center bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 outline-none focus:border-tech-500/40';

export default function DevEditorSettingsDialog({
  open, settings, onChange, onClose,
}: {
  open: boolean;
  settings: EditorSettings;
  onChange: (s: EditorSettings) => void;
  onClose: () => void;
}) {
  const set = (patch: Partial<EditorSettings>) => onChange({ ...settings, ...patch });
  if (!open) return null;
  return (
    <Modal open onClose={onClose} zIndex="z-[88]" ariaLabel="编辑器设置"
      panelClassName="glass-dark rounded-xl w-full max-w-md p-4 border border-tech-500/15 shadow-2xl">
      <div className="flex items-center justify-between mb-2">
        <div className="text-sm font-semibold text-ink-50">编辑器设置</div>
        <div className="text-[10px] text-ink-600">Ctrl+Alt+S · 全局生效</div>
      </div>
      <div className="divide-y divide-tech-500/10 max-h-[62vh] overflow-y-auto scrollbar-thin pr-1">
        <div className="py-1">
          <Row label="字号" title="编辑器字体大小（Ctrl+滚轮临时缩放）">
            <div className="flex items-center gap-1">
              <button onClick={() => set({ fontSize: Math.max(10, settings.fontSize - 1) })}
                className="w-6 h-6 rounded border border-tech-500/20 text-ink-400 hover:text-tech-400">−</button>
              <input type="number" min={10} max={24} value={settings.fontSize}
                onChange={(e) => set({ fontSize: Math.min(24, Math.max(10, Number(e.target.value) || 12)) })}
                className={inputCls} />
              <button onClick={() => set({ fontSize: Math.min(24, settings.fontSize + 1) })}
                className="w-6 h-6 rounded border border-tech-500/20 text-ink-400 hover:text-tech-400">+</button>
            </div>
          </Row>
          <Row label="缩进宽度（空格数）" title="Tab/缩进使用的空格数">
            <select value={settings.tabSize} onChange={(e) => set({ tabSize: Number(e.target.value) })}
              className="px-2 py-1 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 outline-none">
              <option value={2}>2</option>
              <option value={4}>4</option>
              <option value={8}>8</option>
            </select>
          </Row>
          <Row label="使用空格缩进" title="关闭后使用 Tab 字符缩进">
            <Toggle value={settings.insertSpaces} onChange={(v) => set({ insertSpaces: v })} />
          </Row>
          <Row label="软换行" title="长行自动折行显示（IDEA View → Active Editor → Soft-Wrap）">
            <Toggle value={settings.wordWrap} onChange={(v) => set({ wordWrap: v })} />
          </Row>
        </div>
        <div className="py-1">
          <Row label="显示空白符" title="IDEA Settings → Editor → Appearance → Show whitespaces">
            <select value={settings.renderWhitespace} onChange={(e) => set({ renderWhitespace: e.target.value as EditorSettings['renderWhitespace'] })}
              className="px-2 py-1 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 outline-none">
              <option value="selection">仅选区</option>
              <option value="all">全部显示</option>
              <option value="none">关闭</option>
            </select>
          </Row>
          <Row label="相对行号" title="行号显示为相对当前行的距离（Vim 风格）">
            <Toggle value={settings.relativeLineNumbers} onChange={(v) => set({ relativeLineNumbers: v })} />
          </Row>
          <Row label="粘性滚动" title="作用域签名钉在编辑器顶部（IDEA Sticky Lines）">
            <Toggle value={settings.stickyScroll} onChange={(v) => set({ stickyScroll: v })} />
          </Row>
          <Row label="右侧缩略图" title="代码结构缩略图（IDEA 默认关闭）">
            <Toggle value={settings.minimap} onChange={(v) => set({ minimap: v })} />
          </Row>
          <Row label="括号配对着色" title="嵌套括号按层级着色">
            <Toggle value={settings.bracketPairs} onChange={(v) => set({ bracketPairs: v })} />
          </Row>
        </div>
        <div className="py-1">
          <Row label="自动保存" title="修改后 0.8 秒自动写盘（IDEA 默认自动保存；关闭后需 Ctrl+S 手动保存）">
            <Toggle value={settings.autoSave} onChange={(v) => set({ autoSave: v })} />
          </Row>
          <Row label="保存时格式化" title="每次保存（含自动保存）前对整个文件格式化">
            <Toggle value={settings.formatOnSave} onChange={(v) => set({ formatOnSave: v })} />
          </Row>
        </div>
      </div>
      <div className="flex justify-end mt-3">
        <button onClick={onClose} className="px-3 py-1.5 text-xs font-medium text-white bg-tech-500 hover:bg-tech-600 rounded-lg transition-colors">完成</button>
      </div>
    </Modal>
  );
}
