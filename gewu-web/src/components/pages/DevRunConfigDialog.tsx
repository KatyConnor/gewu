'use client';
// 运行/调试配置对话框（IDEA Run/Debug Configurations 风格）：
// 按运行类型分区（Maven 命令 / Java 主类 / 自定义命令），运行与调试共用同一份配置；
// 另含构建命令配置。持久化 localStorage 按项目隔离（dw-runconf-{projectId}）。
import { useEffect, useState } from 'react';
import Modal from '@/components/ui/Modal';

export interface DevRunConf {
  type: 'maven' | 'java' | 'custom';
  /** maven：goals（如 spring-boot:run） */
  goals?: string;
  /** java：主类全限定名 */
  mainClass?: string;
  /** java：JVM 参数 */
  jvmOpts?: string;
  /** java：程序参数 */
  programArgs?: string;
  /** custom：完整命令 */
  cmd?: string;
  /** 构建命令（构建按钮使用） */
  buildCmd?: string;
}

export const DEFAULT_BUILD_CMD = 'mvn compile';

const inputCls = 'w-full px-2.5 py-1.5 text-xs bg-ink-900/60 border border-tech-500/10 rounded-lg text-ink-100 placeholder:text-ink-600 outline-none focus:border-tech-500/40';
const labelCls = 'block text-[11px] text-ink-500 mb-1';

export default function DevRunConfigDialog({ open, conf, onClose, onSave }: {
  open: boolean;
  conf: DevRunConf | null;
  onClose: () => void;
  onSave: (conf: DevRunConf) => void;
}) {
  const [draft, setDraft] = useState<DevRunConf>(conf || { type: 'maven', goals: 'spring-boot:run', buildCmd: DEFAULT_BUILD_CMD });
  const [error, setError] = useState('');

  useEffect(() => {
    if (open) {
      setDraft(conf || { type: 'maven', goals: 'spring-boot:run', buildCmd: DEFAULT_BUILD_CMD });
      setError('');
    }
  }, [open, conf]);

  const save = () => {
    if (draft.type === 'java' && !(draft.mainClass || '').trim()) { setError('Java 主类不能为空'); return; }
    if (draft.type === 'custom' && !(draft.cmd || '').trim()) { setError('自定义命令不能为空'); return; }
    onSave(draft);
  };

  return (
    <Modal open={open} onClose={onClose} zIndex="z-[75]" ariaLabel="运行/调试配置"
      panelClassName="glass-dark rounded-xl w-full max-w-[520px] p-5 border border-tech-500/15 shadow-2xl">
      <div className="text-sm font-semibold text-ink-50 mb-4">运行/调试配置</div>

      <div className="space-y-3">
        <div>
          <label className={labelCls}>运行类型</label>
          <select value={draft.type} onChange={e => setDraft({ ...draft, type: e.target.value as DevRunConf['type'] })} className={inputCls}>
            <option value="maven">Maven 命令</option>
            <option value="java">Java 主类</option>
            <option value="custom">自定义命令</option>
          </select>
        </div>

        {draft.type === 'maven' && (
          <div>
            <label className={labelCls}>命令行（goals，可含 -P/-D 等参数）</label>
            <input value={draft.goals || ''} onChange={e => setDraft({ ...draft, goals: e.target.value })}
              placeholder="如：spring-boot:run" className={inputCls} />
          </div>
        )}

        {draft.type === 'java' && (
          <>
            <div>
              <label className={labelCls}>主类（全限定名）</label>
              <input value={draft.mainClass || ''} onChange={e => setDraft({ ...draft, mainClass: e.target.value })}
                placeholder="如：com.example.demo.DemoApplication" className={inputCls} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls}>JVM 参数（可选）</label>
                <input value={draft.jvmOpts || ''} onChange={e => setDraft({ ...draft, jvmOpts: e.target.value })}
                  placeholder="如：-Xms256m -Dspring.profiles.active=dev" className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>程序参数（可选）</label>
                <input value={draft.programArgs || ''} onChange={e => setDraft({ ...draft, programArgs: e.target.value })}
                  placeholder="如：--server.port=8081" className={inputCls} />
              </div>
            </div>
            <div className="text-[10px] text-ink-600 leading-4">
              启动时先编译并生成运行时类路径（target/classes + Maven 依赖），调试模式同样适用；首次可能较慢。
            </div>
          </>
        )}

        {draft.type === 'custom' && (
          <div>
            <label className={labelCls}>自定义命令（在项目根目录执行）</label>
            <input value={draft.cmd || ''} onChange={e => setDraft({ ...draft, cmd: e.target.value })}
              placeholder="如：npm run dev" className={inputCls} />
          </div>
        )}

        <div className="border-t border-tech-500/10 pt-3">
          <label className={labelCls}>构建命令（「构建」按钮执行）</label>
          <input value={draft.buildCmd || ''} onChange={e => setDraft({ ...draft, buildCmd: e.target.value })}
            placeholder={DEFAULT_BUILD_CMD} className={inputCls} />
        </div>

        {error && <div className="text-[11px] text-cinnabar-400">{error}</div>}
      </div>

      <div className="flex justify-end gap-2 mt-5">
        <button onClick={onClose} className="px-4 py-1.5 text-xs text-ink-300 hover:text-ink-100 transition-colors">取消</button>
        <button onClick={save} className="px-4 py-1.5 text-xs font-medium text-white bg-tech-500 hover:bg-tech-600 rounded-lg transition-colors">
          保存并应用
        </button>
      </div>
    </Modal>
  );
}
