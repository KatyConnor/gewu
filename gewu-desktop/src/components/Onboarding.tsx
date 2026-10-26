/**
 * 首次启动引导（用户指定）：未配置使用模式时展示——
 * 选择「本地离线使用」或「格物平台登录使用」;平台模式需配置线上访问地址。
 * 选择本地后此刻才启动本地引擎;选择平台由主进程重建窗口直接加载线上界面。
 */
import { useState } from 'react';
import GewuLogo from './GewuLogo';

export default function Onboarding({ busy, error, onLocal, onCloud }: {
  busy?: boolean;
  error?: string | null;
  onLocal: () => void;
  onCloud: (url: string) => void;
}) {
  const [choice, setChoice] = useState<'local' | 'cloud' | null>(null);
  const [url, setUrl] = useState('https://');
  const urlOk = /^https?:\/\/.+/.test(url.trim());

  return (
    <div className="splash onboarding">
      <div className="splash-logo"><GewuLogo size={104} /></div>
      <h1 className="onboarding-title">欢迎使用格物桌面</h1>
      <p className="onboarding-sub">选择使用方式——之后可随时在设置或系统托盘中更改</p>
      <div className="onboarding-cards">
        <button type="button" className={`onboard-card ${choice === 'local' ? 'active' : ''}`}
          onClick={() => setChoice('local')}>
          <span className="oc-icon">⌂</span>
          <span className="oc-name">本地离线使用</span>
          <span className="oc-desc">本地引擎 + 本地工作区，对话与文件全部在本机完成</span>
        </button>
        <button type="button" className={`onboard-card ${choice === 'cloud' ? 'active' : ''}`}
          onClick={() => setChoice('cloud')}>
          <span className="oc-icon">☁</span>
          <span className="oc-name">格物平台登录使用</span>
          <span className="oc-desc">登录格物平台，使用云端智能体与协同能力</span>
        </button>
      </div>
      {choice === 'cloud' && (
        <div className="onboarding-url">
          <input placeholder="格物平台访问地址（如 https://gewu.example.com）" value={url}
            onChange={(e) => setUrl(e.target.value)} />
        </div>
      )}
      {error && <p className="error onboarding-error">{error}</p>}
      <button className="btn primary onboard-go"
        disabled={!choice || busy || (choice === 'cloud' && !urlOk)}
        onClick={() => (choice === 'local' ? onLocal() : onCloud(url.trim()))}>
        {busy ? '正在切换…' : choice === 'cloud' ? '进入格物平台' : choice === 'local' ? '开始本地使用' : '请先选择使用方式'}
      </button>
    </div>
  );
}
