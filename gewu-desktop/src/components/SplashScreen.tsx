/**
 * 启动画面（用户指定）：替换原引擎状态卡片——格物商标 + 动态加载（不确定进度条 + 标志轻呼吸）。
 * 仅保留一个动效焦点（进度条）；错误/崩溃态转为文字说明 + 重启入口。
 * prefers-reduced-motion 下全部动画降级为静态。
 */
import GewuLogo from './GewuLogo';

const STATE_TEXT: Record<string, string> = {
  booting: '正在启动',
  crashed: '引擎已崩溃，正在自动重启…',
  degraded: '引擎降级运行中',
  stopped: '引擎已停止',
  ready: '就绪',
};

export default function SplashScreen({ state, error, onRestart }: {
  state: string; error: string | null; onRestart: () => void;
}) {
  const failed = Boolean(error) || state === 'crashed' || state === 'stopped';
  return (
    <div className="splash">
      <div className="splash-logo"><GewuLogo size={136} /></div>
      <div className="splash-state" role="status">{STATE_TEXT[state] ?? `本地引擎状态：${state}`}</div>
      <div className="splash-bar" aria-hidden="true" />
      {error && <p className="error splash-error">{error}</p>}
      <p className="hint">首次启动需初始化本地运行时（约 3~10 秒）；崩溃会自动重启（≤3 次）</p>
      {failed && <button className="btn" onClick={onRestart}>重启引擎</button>}
    </div>
  );
}
