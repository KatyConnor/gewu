'use client';
import { useDispatch } from 'react-redux';
import { setAuth } from '@/store';
import { useState, useRef, useEffect, FormEvent } from 'react';
import { useToast } from '@/components/ui/Toast';
import { Eye, EyeOff } from 'lucide-react';
import { login, register } from '@/lib/auth';
import { saveTokens, saveUser } from '@/lib/token';

export default function LoginPage() {
  const dispatch = useDispatch();
  const toast = useToast();
  const [loginType, setLoginType] = useState<'password' | 'sso'>('password');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [showForgotModal, setShowForgotModal] = useState(false);
  const [resetEmail, setResetEmail] = useState('');

  // 使用 ref 直接操作 DOM，解决浏览器 autofill 导致 React state 不同步的问题
  const formRef = useRef<HTMLFormElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  // 页面加载时检查是否有记住的邮箱
  useEffect(() => {
    const remembered = localStorage.getItem('gewu-remember');
    const savedEmail = localStorage.getItem('gewu-email');
    if (remembered === 'true' && savedEmail && emailRef.current) {
      emailRef.current.value = savedEmail;
    }
  }, []);

  // 监听浏览器 autofill，同步到 React state（通过 animationstart 事件检测 autofill）
  useEffect(() => {
    const inputs = formRef.current?.querySelectorAll('input');
    inputs?.forEach(input => {
      input.addEventListener('animationstart', (e) => {
        if (e.animationName === 'onAutoFillStart') {
          // 浏览器 autofill 触发，从 DOM 读取值
          if (input === emailRef.current && input.value) {
            // 值已通过 ref 获取，无需额外处理
          }
        }
      });
    });
  }, []);

  const [loading, setLoading] = useState(false);

  const handleLogin = async (e: FormEvent) => {
    e.preventDefault();
    e.stopPropagation();

    // 直接从 DOM 读取值
    const username = emailRef.current?.value || '';
    const password = passwordRef.current?.value || '';

    if (loginType === 'password') {
      if (!username || !password) {
        toast('请输入账号和密码', 'error');
        return;
      }
    }

    if (loginType === 'sso') {
      toast('SSO 登录功能开发中', 'info');
      return;
    }

    setLoading(true);
    try {
      const res = await login({ username, password });

      // 保存 token
      saveTokens(res.accessToken, res.refreshToken);

      // 保存用户信息
      saveUser({
        userId: res.userId,
        username: res.username,
        displayName: res.displayName,
        roles: res.roles,
        permissions: res.permissions || [],
      });

      // 更新 Redux 状态
      dispatch(setAuth({
        user: {
          userId: res.userId,
          username: res.username,
          displayName: res.displayName,
          roles: res.roles,
          name: res.displayName,
          role: res.roles?.[0] || '',
          department: '',
          avatar: res.displayName?.charAt(0) || '',
        },
        isAuthenticated: true,
      }));

      if (rememberMe) {
        localStorage.setItem('gewu-email', username);
        localStorage.setItem('gewu-remember', 'true');
      }

      toast('登录成功，正在跳转...', 'success');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : '登录失败';
      toast(message, 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async () => {
    const username = emailRef.current?.value || '';
    const password = passwordRef.current?.value || '';

    if (!username || !password) {
      toast('请先填写账号和密码', 'error');
      return;
    }

    setLoading(true);
    try {
      const res = await register({
        username,
        email: `${username}@placeholder.com`, // 如果没有邮箱字段，使用占位
        password,
        displayName: username,
      });

      saveTokens(res.accessToken, res.refreshToken);
      saveUser({
        userId: res.userId,
        username: res.username,
        displayName: res.displayName,
        roles: res.roles,
        permissions: res.permissions || [],
      });

      dispatch(setAuth({
        user: {
          userId: res.userId,
          username: res.username,
          displayName: res.displayName,
          roles: res.roles,
          name: res.displayName,
          role: res.roles?.[0] || '',
          department: '',
          avatar: res.displayName?.charAt(0) || '',
        },
        isAuthenticated: true,
      }));

      toast('注册成功，正在跳转...', 'success');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : '注册失败';
      toast(message, 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleSendReset = () => {
    if (!resetEmail) { toast('请输入邮箱地址', 'error'); return; }
    setShowForgotModal(false);
    toast(`重置链接已发送至 ${resetEmail}`, 'success');
  };

  return (
    <div className="min-h-screen flex">
      {/* 左侧品牌区 — 墨韵绿背景 + SVG山水层 */}
      <div className="hidden lg:flex lg:w-1/2 relative overflow-hidden ink-mountain items-center justify-center">
        <div className="absolute inset-0 pointer-events-none bg-gradient-to-b from-transparent via-ink-950/50 to-ink-950/90" />
        <div className="absolute top-20 left-20 w-72 h-72 rounded-full bg-tech-500/8 blur-3xl animate-ink-flow" />
        <div className="absolute bottom-40 right-20 w-56 h-56 rounded-full bg-cyber-500/5 blur-3xl animate-ink-flow" style={{ animationDelay: '3s' }} />
        <div className="absolute top-1/2 left-1/3 w-40 h-40 rounded-full bg-gold-500/4 blur-2xl animate-ink-flow" style={{ animationDelay: '5s' }} />
        <div className="absolute inset-0 pointer-events-none opacity-[0.03]" style={{ backgroundImage: 'linear-gradient(rgba(0,184,148,0.3) 1px, transparent 1px), linear-gradient(90deg, rgba(0,184,148,0.3) 1px, transparent 1px)', backgroundSize: '60px 60px' }} />

        <div className="relative z-10 text-center px-12">
          <div className="w-24 h-24 rounded-lg mx-auto mb-8 flex items-center justify-center animate-float-slow overflow-hidden">
            <img src="/logo/logo1.png" alt="格物致虚" className="w-full h-full object-cover rounded-lg" />
          </div>
          <h1 className="calligraphy-text text-5xl text-gradient-animate mb-4">格物<span className="mx-1.5 icon-glow-animate inline-block text-tech-400">·</span>致虚</h1>
          <p className="font-serif text-xl text-ink-300 mb-2 tracking-widest icon-glow-animate" style={{ animationDelay: '1s' }}>AI 智能协作平台</p>
          <div className="w-16 h-px mx-auto my-6 border-glow-animate rounded-full" style={{ background: 'linear-gradient(90deg, transparent, #00d4aa, #06b6d4, transparent)' }} />
          <p className="text-ink-400 text-sm leading-relaxed max-w-sm mx-auto tech-glow">致知在格物，物格而后知至。<br />以 AI 之力，穷万物之理；以协作之道，达无界之境。</p>
          <div className="mt-10 flex flex-wrap justify-center gap-3">
            <span className="px-3 py-1 text-xs border border-gold-500/20 rounded-full text-gold-400/80 border-glow-animate" style={{ animationDelay: '0s' }}>流式对话</span>
            <span className="px-3 py-1 text-xs border border-jade-500/20 rounded-full text-jade-400/80 border-glow-animate" style={{ animationDelay: '1s' }}>智能体协作</span>
            <span className="px-3 py-1 text-xs border border-cinnabar-500/20 rounded-full text-cinnabar-400/80 border-glow-animate" style={{ animationDelay: '2s' }}>项目管理</span>
            <span className="px-3 py-1 text-xs border border-ink-600/40 rounded-full text-ink-300/80 border-glow-animate" style={{ animationDelay: '3s' }}>企业级安全</span>
          </div>
        </div>
      </div>

      {/* 右侧登录表单 — 墨韵绿偏青绿背景 */}
      <div className="w-full lg:w-1/2 flex items-center justify-center p-8 relative" style={{ background: '#0a1a18' }}>
        <div className="absolute inset-0 opacity-[0.03] pointer-events-none" style={{ backgroundImage: 'radial-gradient(circle at 1px 1px, rgba(0,184,148,0.8) 1px, transparent 0)', backgroundSize: '24px 24px' }} />

        <div className="w-full max-w-md relative z-10">
          <div className="lg:hidden text-center mb-10">
            <div className="w-16 h-16 rounded-lg mx-auto mb-4 flex items-center justify-center overflow-hidden">
              <img src="/logo/logo1.png" alt="格物致虚" className="w-full h-full object-cover rounded-lg" />
            </div>
            <h1 className="calligraphy-text text-3xl font-bold text-gradient-animate">格物<span className="text-tech-400 mx-0.5">·</span>致虚</h1>
          </div>

          <div className="mb-8">
            <h2 className="text-2xl font-semibold text-ink-50 mb-2">欢迎回来</h2>
            <p className="text-ink-400 text-sm">登录您的账户，开启智能协作之旅</p>
          </div>

          <div className="flex gap-1 p-1 rounded-lg mb-6" style={{ background: 'rgba(21,40,38,0.5)' }}>
            <button onClick={() => setLoginType('password')} className={`flex-1 py-2 text-sm rounded-md transition-all ${loginType === 'password' ? 'text-gold-400 font-medium' : 'text-ink-400 hover:text-ink-200'}`} style={loginType === 'password' ? { background: 'rgba(31,56,51,0.7)' } : {}}>密码登录</button>
            <button onClick={() => setLoginType('sso')} className={`flex-1 py-2 text-sm rounded-md transition-all ${loginType === 'sso' ? 'text-gold-400 font-medium' : 'text-ink-400 hover:text-ink-200'}`} style={loginType === 'sso' ? { background: 'rgba(31,56,51,0.7)' } : {}}>企业 SSO</button>
          </div>

          {/* 密码登录 — 使用 ref 而非受控组件，解决 autofill 问题 */}
          {loginType === 'password' ? (
            <form ref={formRef} className="space-y-4" onSubmit={handleLogin}>
              <div>
                <label className="block text-sm text-ink-300 mb-1.5">企业邮箱 / 账号</label>
                <input ref={emailRef} name="email" type="text" autoComplete="username" placeholder="name@company.com"
                  className="w-full px-4 py-3 border border-tech-500/10 rounded-lg text-ink-100 placeholder-ink-500 focus:outline-none input-ink transition-all" style={{ background: 'rgba(16,32,30,0.6)' }} />
              </div>
              <div>
                <label className="block text-sm text-ink-300 mb-1.5">密码</label>
                <div className="relative">
                  <input ref={passwordRef} name="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" placeholder="请输入密码"
                    className="w-full px-4 py-3 border border-tech-500/10 rounded-lg text-ink-100 placeholder-ink-500 focus:outline-none input-ink transition-all pr-12" style={{ background: 'rgba(16,32,30,0.6)' }} />
                  <button type="button" onClick={() => setShowPassword(!showPassword)} className="absolute right-3 top-1/2 -translate-y-1/2 text-ink-500 hover:text-ink-300">
                    {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                  </button>
                </div>
              </div>
              <div className="flex items-center justify-between text-sm">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={rememberMe} onChange={e => setRememberMe(e.target.checked)} className="w-4 h-4 rounded accent-tech-500" style={{ borderColor: 'rgba(0,184,148,0.3)', background: 'rgba(21,40,38,0.5)' }} />
                  <span className="text-ink-400">记住我</span>
                </label>
                <button type="button" onClick={() => setShowForgotModal(true)} className="text-tech-400/80 hover:text-tech-400 transition-colors text-xs">忘记密码？</button>
              </div>
               <button type="submit" disabled={loading} className="w-full py-3 btn-primary text-white font-medium rounded-lg mt-6 relative overflow-hidden disabled:opacity-60 disabled:cursor-not-allowed">
                  <span className="relative z-10">{loading ? '登录中...' : '登 录'}</span>
                </button>
                <button type="button" onClick={handleRegister} disabled={loading} className="w-full py-3 border border-tech-500/20 text-tech-400 font-medium rounded-lg mt-3 hover:bg-tech-500/5 transition-all disabled:opacity-60 disabled:cursor-not-allowed">
                  <span className="relative z-10">注册新账户</span>
                </button>
            </form>
          ) : (
            <form className="space-y-4" onSubmit={handleLogin}>
              <div>
                <label className="block text-sm text-ink-300 mb-1.5">企业域名</label>
                <div className="flex">
                  <input name="domain" type="text" autoComplete="organization" placeholder="yourcompany" className="flex-1 px-4 py-3 border border-tech-500/10 rounded-l-lg text-ink-100 placeholder-ink-500 focus:outline-none input-ink transition-all" style={{ background: 'rgba(21,40,38,0.5)' }} />
                  <span className="px-4 py-3 border border-l-0 border-tech-500/10 rounded-r-lg text-tech-400/60 text-sm flex items-center" style={{ background: 'rgba(31,56,51,0.5)' }}>.sso.com</span>
                </div>
              </div>
              <button type="submit" className="w-full py-3 btn-primary text-white font-medium rounded-lg mt-6">通过 SSO 登录</button>
            </form>
          )}

          <div className="my-6 flex items-center gap-3">
            <div className="flex-1 h-px bg-gradient-to-r from-transparent via-tech-400/30 to-transparent" />
            <span className="text-xs text-ink-500">或</span>
            <div className="flex-1 h-px bg-gradient-to-r from-transparent via-tech-400/30 to-transparent" />
          </div>

          <div className="grid grid-cols-3 gap-3">
            {['钉钉', '企微', '飞书'].map(name => (
              <button key={name} onClick={() => toast(`${name} 登录功能开发中`, 'info')} className="flex items-center justify-center py-2.5 border border-tech-500/10 rounded-lg hover:border-tech-500/25 hover:bg-tech-500/5 transition-all">
                <span className="text-ink-300 text-sm hover:text-tech-400 transition-colors">{name}</span>
              </button>
            ))}
          </div>

          <p className="text-center text-sm text-ink-500 mt-6">
            还没有账户？ <a href="#" className="text-gold-400 hover:text-gold-300 transition-colors">联系管理员开通</a>
          </p>
        </div>
      </div>

      {/* 忘记密码弹窗 */}
      {showForgotModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowForgotModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-sm p-6 shadow-2xl animate-fade-up border border-glass-border" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="text-base font-semibold text-ink-500">重置密码</h3>
              <button onClick={() => setShowForgotModal(false)} className="text-ink-500 hover:text-ink-300 text-lg">✕</button>
            </div>
            <p className="text-xs text-ink-500 mb-4">请输入您的注册邮箱，我们将向您发送密码重置链接。</p>
            <input type="email" value={resetEmail} onChange={e => setResetEmail(e.target.value)} placeholder="name@company.com" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 mb-4" />
            <button onClick={handleSendReset} className="w-full py-2.5 btn-primary text-white text-sm rounded-lg">发送重置链接</button>
            <button onClick={() => setShowForgotModal(false)} className="w-full py-2 text-xs text-ink-400 hover:text-ink-200 transition-colors mt-2">返回登录</button>
          </div>
        </div>
      )}
    </div>
  );
}
