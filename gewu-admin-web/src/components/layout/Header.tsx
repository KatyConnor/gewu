'use client';
import { memo, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { RootState } from '@/store';
import { setPage, setTheme, clearAuth } from '@/store';
import { ThemeType } from '@/types';
import { Bell, Palette, Check, LogOut } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { clearTokens } from '@/lib/token';
import { logout as apiLogout } from '@/lib/auth';

const themes: { id: ThemeType; label: string; color: string }[] = [
  { id: 'ink', label: '墨韵·深色东方', color: '#00b894' },
  { id: 'deepsea', label: '深海·Logo原色', color: '#0ea5e9' },
  { id: 'jade', label: '玉蕴·浅色专业', color: '#0d9488' },
  { id: 'celadon', label: '青瓷·科技融合', color: '#2ea087' },
];

function HeaderInner() {
  const dispatch = useDispatch();
  const theme = useSelector((s: RootState) => s.app.theme);
  const [showThemeDropdown, setShowThemeDropdown] = useState(false);
  const toast = useToast();

  return (
    <header className="fixed top-0 right-0 left-64 h-16 z-40 flex items-center justify-end px-8 border-b" style={{ background: 'rgba(8,18,17,0.85)', backdropFilter: 'blur(16px)', borderColor: 'rgba(0,184,148,0.08)' }}>
      <div className="flex items-center gap-2">
        {/* 消息提醒铃铛 */}
        <button className="relative p-2.5 text-ink-400 hover:text-tech-400 hover:bg-nav-hover rounded-lg transition-all" title="消息提醒">
          <Bell className="w-5 h-5" />
          <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-cinnabar-500 rounded-full shadow-sm shadow-cinnabar-500/60" />
        </button>
        {/* 主题切换下拉 */}
        <div className="relative">
          <button onClick={() => setShowThemeDropdown(!showThemeDropdown)} className="p-2.5 text-ink-400 hover:text-tech-400 hover:bg-nav-hover rounded-lg transition-all" title="切换主题">
            <Palette className="w-5 h-5" />
          </button>
          {showThemeDropdown && (
            <div className="absolute right-0 top-full mt-2 glass-dark rounded-xl p-3 shadow-2xl border min-w-[180px] z-50">
              <p className="text-[10px] font-medium text-ink-500 uppercase tracking-wider mb-2 px-1">主题色</p>
              {themes.map(t => ( // eslint-disable-line
                <button key={t.id} onClick={() => { dispatch(setTheme(t.id)); setShowThemeDropdown(false); toast('主题已切换', 'success'); }}
                  className="w-full flex items-center gap-3 px-2.5 py-2 rounded-lg hover:bg-nav-hover transition-all">
                  <div className="w-5 h-5 rounded-full flex-shrink-0" style={{ background: t.color }} />
                  <span className="text-sm text-ink-200">{t.label}</span>
                  {theme === t.id && <Check className="w-4 h-4 text-tech-400 ml-auto" />}
                </button>
              ))}
            </div>
          )}
        </div>
        {/* 设置 */}
        <button onClick={() => toast('设置功能开发中', 'info')} className="p-2.5 text-ink-400 hover:text-tech-400 hover:bg-nav-hover rounded-lg transition-all" title="设置">
          <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
        </button>
        {/* 注销退出 */}
        <button onClick={async () => { await apiLogout(); clearTokens(); localStorage.removeItem('gewu-remember'); localStorage.removeItem('gewu-email'); dispatch(clearAuth()); toast('已退出登录', 'info'); }} className="flex items-center gap-2 px-4 py-2 border border-cinnabar-500/20 text-cinnabar-400 text-sm rounded-lg hover:bg-cinnabar-500/10 transition-all ml-1">
          <LogOut className="w-4 h-4" /><span>退出</span>
        </button>
      </div>
    </header>
  );
}

export default memo(HeaderInner);
