'use client';
import { useRef, useState } from 'react';
import { ArrowLeft, GitBranch, Lock, Users } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { createProject, type ProjectDTO } from '@/lib/project';

// ==================== 常量 ====================

/** 常用主流版本控制 */
const vcsOptions = [
  { value: 'git', label: 'Git' },
  { value: 'svn', label: 'SVN' },
  { value: 'mercurial', label: 'Mercurial' },
  { value: 'perforce', label: 'Perforce' },
];

/** 仓库认证方式：访问凭证（令牌/SSH 私钥）与用户名密码二选一 */
type AuthMode = 'credential' | 'password';

const inputCls = 'w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30';
const labelCls = 'text-xs text-ink-400 mb-1 block';
const cardCls = 'glass-dark rounded-xl p-5 border border-tech-500/10';

// ==================== 主组件 ====================

export default function ProjectCreatePage({ onBack, onCreated }: {
  onBack: () => void;
  onCreated: (project: ProjectDTO) => void;
}) {
  const toast = useToast();
  const [submitting, setSubmitting] = useState(false);

  // 基础信息
  const [name, setName] = useState('');
  const [shortName, setShortName] = useState('');
  const [systemName, setSystemName] = useState('');
  const [desc, setDesc] = useState('');
  const [visibility, setVisibility] = useState(0);
  const [iconColor, setIconColor] = useState('#00b894');

  // 团队与角色
  const [techLead, setTechLead] = useState('');
  const [teamMembers, setTeamMembers] = useState('');

  // 技术配置
  const [techStack, setTechStack] = useState('');
  const [vcs, setVcs] = useState('git');
  const [worktree, setWorktree] = useState('');
  /** 用户手动编辑过工作空间后，不再跟随项目简称自动填充 */
  const worktreeTouched = useRef(false);

  // 仓库配置
  const [repoUrl, setRepoUrl] = useState('');
  const [authMode, setAuthMode] = useState<AuthMode>('credential');
  const [credType, setCredType] = useState<'token' | 'ssh_key'>('token');
  const [credential, setCredential] = useState('');
  const [repoUsername, setRepoUsername] = useState('');
  const [repoPassword, setRepoPassword] = useState('');

  const handleShortNameChange = (value: string) => {
    setShortName(value);
    if (!worktreeTouched.current) {
      setWorktree(value.trim() ? `/workspace/${value.trim()}` : '');
    }
  };

  const handleWorktreeChange = (value: string) => {
    worktreeTouched.current = true;
    setWorktree(value);
  };

  const handleSubmit = async () => {
    if (!name.trim() || submitting) return;

    // 仓库凭证二选一：password 模式要求用户名与密码成对出现
    let payloadCredentialType: 'token' | 'ssh_key' | 'password' | undefined;
    let payloadUsername: string | undefined;
    let payloadCredential: string | undefined;
    if (authMode === 'credential') {
      if (credential.trim()) {
        payloadCredentialType = credType;
        payloadCredential = credential;
      }
    } else if (repoUsername.trim() || repoPassword) {
      if (!repoUsername.trim() || !repoPassword) {
        toast('用户名密码认证需同时填写用户名与密码', 'error');
        return;
      }
      payloadCredentialType = 'password';
      payloadUsername = repoUsername;
      payloadCredential = repoPassword;
    }

    setSubmitting(true);
    try {
      const project = await createProject({
        projectName: name,
        shortName: shortName || undefined,
        systemName: systemName || undefined,
        teamMembers: teamMembers || undefined,
        techLead: techLead || undefined,
        description: desc || undefined,
        visibility,
        techStack: techStack || undefined,
        vcs: vcs || undefined,
        worktree: worktree || undefined,
        iconColor,
        repoUrl: repoUrl || undefined,
        repoCredentialType: payloadCredentialType,
        repoUsername: payloadUsername,
        repoCredential: payloadCredential,
      });
      toast('项目创建成功', 'success');
      onCreated(project);
    } catch {
      toast('创建失败', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="max-w-3xl">
      {/* 页面标题 */}
      <header className="flex items-center gap-3 mb-6">
        <button
          onClick={onBack}
          className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-ink-400 hover:text-ink-200 bg-ink-800/40 border border-tech-500/10 rounded-lg"
        >
          <ArrowLeft className="w-4 h-4" />返回
        </button>
        <div>
          <h1 className="text-2xl font-semibold text-ink-50">新建项目</h1>
          <p className="text-ink-400 text-sm mt-0.5">填写项目基础信息与仓库配置</p>
        </div>
      </header>

      <div className="space-y-4">
        {/* 基础信息 */}
        <section className={cardCls}>
          <h3 className="text-sm font-semibold text-ink-100 mb-4">基础信息</h3>
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls}>项目名称 *</label>
                <input
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder="请输入项目名称"
                  className={inputCls}
                />
              </div>
              <div>
                <label className={labelCls}>项目简称</label>
                <input
                  value={shortName}
                  onChange={e => handleShortNameChange(e.target.value)}
                  placeholder="如：gewu-platform"
                  className={inputCls}
                />
              </div>
            </div>
            <div>
              <label className={labelCls}>所属系统</label>
              <input
                value={systemName}
                onChange={e => setSystemName(e.target.value)}
                placeholder="如：交易中台（可选）"
                className={inputCls}
              />
            </div>
            <div>
              <label className={labelCls}>项目编号</label>
              <input
                disabled
                placeholder="保存后自动生成"
                className="w-full px-3 py-2 bg-ink-800/30 border border-tech-500/10 rounded-lg text-sm text-ink-500 placeholder-ink-500 outline-none cursor-not-allowed"
              />
            </div>
            <div>
              <label className={labelCls}>项目描述</label>
              <textarea
                value={desc}
                onChange={e => setDesc(e.target.value)}
                placeholder="项目描述（可选）"
                rows={3}
                className={`${inputCls} resize-none`}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls}>可见性</label>
                <select
                  value={visibility}
                  onChange={e => setVisibility(Number(e.target.value))}
                  className={`${inputCls} outline-none`}
                >
                  <option value={0}>私有</option>
                  <option value={1}>团队可见</option>
                  <option value={2}>公开</option>
                </select>
              </div>
              <div>
                <label className={labelCls}>图标颜色</label>
                <div className="flex items-center gap-2">
                  <input
                    type="color"
                    value={iconColor}
                    onChange={e => setIconColor(e.target.value)}
                    className="w-10 h-9 rounded border border-tech-500/10 bg-ink-800/50 cursor-pointer"
                  />
                  <input
                    value={iconColor}
                    onChange={e => setIconColor(e.target.value)}
                    placeholder="#00b894"
                    className={`${inputCls} flex-1`}
                  />
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* 团队与角色 */}
        <section className={cardCls}>
          <h3 className="flex items-center gap-1.5 text-sm font-semibold text-ink-100 mb-4">
            <Users className="w-4 h-4 text-tech-400" />团队与角色
          </h3>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className={labelCls}>技术负责人</label>
              <input
                value={techLead}
                onChange={e => setTechLead(e.target.value)}
                placeholder="如：张三（可选）"
                className={inputCls}
              />
            </div>
            <div>
              <label className={labelCls}>团队成员</label>
              <input
                value={teamMembers}
                onChange={e => setTeamMembers(e.target.value)}
                placeholder="多人用逗号分隔，如：李四, 王五"
                className={inputCls}
              />
            </div>
          </div>
        </section>

        {/* 技术配置 */}
        <section className={cardCls}>
          <h3 className="text-sm font-semibold text-ink-100 mb-4">技术配置</h3>
          <div className="space-y-3">
            <div>
              <label className={labelCls}>技术栈</label>
              <input
                value={techStack}
                onChange={e => setTechStack(e.target.value)}
                placeholder="如：React, Node.js, PostgreSQL"
                className={inputCls}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls}>版本控制</label>
                <select
                  value={vcs}
                  onChange={e => setVcs(e.target.value)}
                  className={inputCls}
                >
                  {vcsOptions.map(opt => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className={labelCls}>工作空间</label>
                <input
                  value={worktree}
                  onChange={e => handleWorktreeChange(e.target.value)}
                  placeholder="填写项目简称后自动填充"
                  className={inputCls}
                />
              </div>
            </div>
          </div>
        </section>

        {/* 仓库配置 */}
        <section className={cardCls}>
          <h3 className="flex items-center gap-1.5 text-sm font-semibold text-ink-100 mb-4">
            <GitBranch className="w-4 h-4 text-tech-400" />仓库配置
          </h3>
          <div className="space-y-3">
            <div>
              <label className={labelCls}>仓库地址</label>
              <input
                value={repoUrl}
                onChange={e => setRepoUrl(e.target.value)}
                placeholder="如：https://github.com/org/repo.git"
                className={inputCls}
              />
            </div>
            <div>
              <label className={labelCls}>访问认证（二选一，可选）</label>
              <div className="grid grid-cols-2 gap-2 mb-3">
                <button
                  type="button"
                  onClick={() => setAuthMode('credential')}
                  className={`px-3 py-2 text-sm rounded-lg border transition-all ${
                    authMode === 'credential'
                      ? 'border-tech-500/40 bg-tech-500/10 text-tech-300'
                      : 'border-tech-500/10 text-ink-400 hover:text-ink-200'
                  }`}
                >
                  仓库访问凭证
                </button>
                <button
                  type="button"
                  onClick={() => setAuthMode('password')}
                  className={`px-3 py-2 text-sm rounded-lg border transition-all ${
                    authMode === 'password'
                      ? 'border-tech-500/40 bg-tech-500/10 text-tech-300'
                      : 'border-tech-500/10 text-ink-400 hover:text-ink-200'
                  }`}
                >
                  用户名密码
                </button>
              </div>
              {authMode === 'credential' ? (
                <div className="space-y-3">
                  <div>
                    <label className={labelCls}>凭证类型</label>
                    <select
                      value={credType}
                      onChange={e => setCredType(e.target.value as 'token' | 'ssh_key')}
                      className={inputCls}
                    >
                      <option value="token">访问令牌（Access Token）</option>
                      <option value="ssh_key">SSH 私钥（仓库公钥对应的私钥）</option>
                    </select>
                  </div>
                  <div>
                    <label className={labelCls}>凭证内容</label>
                    <textarea
                      value={credential}
                      onChange={e => setCredential(e.target.value)}
                      placeholder={credType === 'token' ? '粘贴访问令牌' : '粘贴 SSH 私钥（-----BEGIN OPENSSH PRIVATE KEY-----）'}
                      rows={credType === 'ssh_key' ? 4 : 2}
                      className={`${inputCls} resize-none font-mono text-xs`}
                    />
                  </div>
                </div>
              ) : (
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className={labelCls}>用户</label>
                    <input
                      value={repoUsername}
                      onChange={e => setRepoUsername(e.target.value)}
                      placeholder="仓库用户名"
                      autoComplete="off"
                      className={inputCls}
                    />
                  </div>
                  <div>
                    <label className={labelCls}>密码</label>
                    <input
                      type="password"
                      value={repoPassword}
                      onChange={e => setRepoPassword(e.target.value)}
                      placeholder="仓库密码"
                      autoComplete="new-password"
                      className={inputCls}
                    />
                  </div>
                </div>
              )}
              <p className="flex items-center gap-1 text-[11px] text-ink-500 mt-2">
                <Lock className="w-3 h-3" />凭证加密存储，创建后不再回显
              </p>
            </div>
          </div>
        </section>
      </div>

      {/* 底部操作 */}
      <div className="flex gap-2 justify-end mt-4 pt-4 border-t border-tech-500/10">
        <button onClick={onBack} className="px-4 py-2 text-sm text-ink-400 hover:text-ink-200">取消</button>
        <button
          onClick={handleSubmit}
          disabled={!name.trim() || submitting}
          className="px-5 py-2 text-sm btn-primary text-white rounded-lg disabled:opacity-50"
        >
          {submitting ? '创建中…' : '创建项目'}
        </button>
      </div>
    </div>
  );
}
