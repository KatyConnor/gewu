'use client';
import { useState, useEffect } from 'react';
import { X, Search, Plus, Check } from 'lucide-react';
import { listMyProjects, type ProjectDTO } from '@/lib/project';

// ==================== 组件 Props ====================

interface RelatedSelectorProps {
  label: string;
  type: 'project' | 'session' | 'document';
  value: string; // multiple=true 时为 JSON 数组字符串，multiple=false 时为单个 ID
  onChange: (value: string) => void;
  projectId?: string; // 用于过滤会话/文档
  multiple?: boolean; // 是否多选，默认 true
}

// ==================== 主组件 ====================

export default function RelatedSelector({ label, type, value, onChange, projectId, multiple = true }: RelatedSelectorProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [options, setOptions] = useState<Array<{ id: string; label: string }>>([]);
  const [loading, setLoading] = useState(false);

  // 解析当前值
  useEffect(() => {
    try {
      if (value) {
        if (multiple) {
          const parsed = JSON.parse(value);
          setSelectedIds(Array.isArray(parsed) ? parsed : []);
        } else {
          setSelectedIds([value]);
        }
      } else {
        setSelectedIds([]);
      }
    } catch {
      setSelectedIds([]);
    }
  }, [value, multiple]);

  // 加载选项数据
  useEffect(() => {
    if (!isOpen) return;

    const loadOptions = async () => {
      setLoading(true);
      try {
        if (type === 'project') {
          const result = await listMyProjects();
          setOptions((result.records || []).map(p => ({
            id: p.projectId,
            label: p.projectName,
          })));
        } else if (type === 'session') {
          // TODO: 加载会话列表
          setOptions([]);
        } else if (type === 'document') {
          // TODO: 加载文档列表
          setOptions([]);
        }
      } catch {
        setOptions([]);
      } finally {
        setLoading(false);
      }
    };

    loadOptions();
  }, [isOpen, type]);

  // 过滤选项
  const filteredOptions = options.filter(opt =>
    opt.label.toLowerCase().includes(searchKeyword.toLowerCase())
  );

  // 切换选择
  const toggleOption = (id: string) => {
    if (multiple) {
      const newIds = selectedIds.includes(id)
        ? selectedIds.filter(i => i !== id)
        : [...selectedIds, id];
      setSelectedIds(newIds);
      onChange(JSON.stringify(newIds));
    } else {
      // 单选模式：点击已选项取消选中，点击新项替换选中
      const newIds = selectedIds.includes(id) ? [] : [id];
      setSelectedIds(newIds);
      onChange(newIds.length > 0 ? newIds[0] : '');
    }
  };

  // 移除已选项
  const removeSelected = (id: string) => {
    if (multiple) {
      const newIds = selectedIds.filter(i => i !== id);
      setSelectedIds(newIds);
      onChange(JSON.stringify(newIds));
    } else {
      setSelectedIds([]);
      onChange('');
    }
  };

  return (
    <div className="space-y-2">
      <label className="text-xs text-ink-400 mb-1 block">{label}</label>

      {/* 已选标签 */}
      {selectedIds.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selectedIds.map(id => {
            const option = options.find(o => o.id === id);
            return (
              <span
                key={id}
                className="inline-flex items-center gap-1 px-2 py-1 rounded bg-tech-500/10 text-tech-400 text-xs"
              >
                {option?.label || id}
                <button
                  onClick={() => removeSelected(id)}
                  className="hover:text-tech-300"
                >
                  <X className="w-3 h-3" />
                </button>
              </span>
            );
          })}
        </div>
      )}

      {/* 选择按钮 */}
      <button
        onClick={() => setIsOpen(true)}
        className="flex items-center gap-1.5 px-3 py-2 text-xs text-ink-400 border border-tech-500/10 rounded-lg hover:border-tech-500/20 hover:bg-ink-800/30 transition-all"
      >
        <Plus className="w-3 h-3" />
        选择{label}
      </button>

      {/* 选择弹框 */}
      {isOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setIsOpen(false)}>
          <div className="glass-dark rounded-xl w-full max-w-md border border-tech-500/20 max-h-[70vh] flex flex-col" onClick={e => e.stopPropagation()}>
            {/* 弹框头部 */}
            <div className="p-4 border-b border-tech-500/10">
              <h3 className="text-sm font-semibold text-ink-50 mb-3">选择{label}</h3>
              <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg">
                <Search className="w-4 h-4 text-ink-500" />
                <input
                  value={searchKeyword}
                  onChange={e => setSearchKeyword(e.target.value)}
                  placeholder="搜索..."
                  className="flex-1 bg-transparent text-sm text-ink-100 placeholder-ink-500 outline-none"
                />
              </div>
            </div>

            {/* 选项列表 */}
            <div className="flex-1 overflow-y-auto p-2">
              {loading ? (
                <div className="flex items-center justify-center py-8">
                  <div className="w-5 h-5 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
                </div>
              ) : filteredOptions.length === 0 ? (
                <p className="text-sm text-ink-500 text-center py-8">暂无数据</p>
              ) : (
                <div className="space-y-1">
                  {filteredOptions.map(option => {
                    const isSelected = selectedIds.includes(option.id);
                    return (
                      <button
                        key={option.id}
                        onClick={() => toggleOption(option.id)}
                        className={`w-full flex items-center justify-between p-3 rounded-lg transition-all ${
                          isSelected
                            ? 'bg-tech-500/10 border border-tech-500/20'
                            : 'hover:bg-ink-800/30 border border-transparent'
                        }`}
                      >
                        <span className="text-sm text-ink-200">{option.label}</span>
                        {isSelected && (
                          <Check className="w-4 h-4 text-tech-400" />
                        )}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            {/* 弹框底部 */}
            <div className="p-4 border-t border-tech-500/10 flex items-center justify-between">
              <span className="text-xs text-ink-500">已选择 {selectedIds.length} 项</span>
              <div className="flex gap-2">
                <button
                  onClick={() => setIsOpen(false)}
                  className="px-4 py-2 text-xs text-ink-400 hover:text-ink-200"
                >
                  取消
                </button>
                <button
                  onClick={() => setIsOpen(false)}
                  className="px-4 py-2 text-xs btn-primary text-white rounded-lg"
                >
                  确认
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
