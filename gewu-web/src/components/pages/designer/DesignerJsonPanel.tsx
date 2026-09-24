'use client';
// 编排设计器 - JSON 视图抽屉：Monaco 编辑图定义，应用后与画布双向同步。
import MonacoEditor from '@/components/pages/MonacoEditor';

interface Props {
  value: string;
  error: string;
  onChange: (next: string) => void;
  onApply: () => void;
  onClose: () => void;
}

export default function DesignerJsonPanel({ value, error, onChange, onApply, onClose }: Props) {
  return (
    <div className="flex h-72 flex-col border-t border-tech-500/10 bg-ink-900/60">
      <div className="flex items-center justify-between px-3 py-2">
        <p className="text-xs font-semibold text-ink-200">图定义 JSON</p>
        <div className="flex items-center gap-2">
          {error && <span className="text-[10px] text-cinnabar-400">{error}</span>}
          <button onClick={onApply} disabled={!!error}
            className="px-3 py-1.5 text-xs text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 transition-colors disabled:opacity-40">
            应用到画布
          </button>
          <button onClick={onClose} className="px-2 text-ink-500 hover:text-ink-300" aria-label="关闭 JSON 视图">✕</button>
        </div>
      </div>
      <div className="min-h-0 flex-1">
        <MonacoEditor value={value} language="json" path="orchestration-graph-definition.json"
          onChange={v => onChange(v ?? '')} />
      </div>
    </div>
  );
}
