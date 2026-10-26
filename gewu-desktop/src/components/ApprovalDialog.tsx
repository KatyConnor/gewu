import React, { useEffect, useState } from 'react';
import type { ApprovalRequestPayload } from '@gewu/agent-ui/protocol';

/**
 * 写授权弹窗（D1-21）：模型写文件前挂起，等待用户 diff 确认。
 * 三按钮：允许一次 / 本会话始终允许（白名单跳过后续同类写）/ 拒绝。
 *
 * 挂起语义（用户实测：离开超时回来授权，前端假死）：
 * - 30 分钟内决策：引擎侧仍在等待（35 分钟超时），决策命中即无缝恢复执行；
 * - 倒计时到点或流中断线 → 转挂起态（suspended）：授权后基于会话已完成进度自动续跑，无需重新发起。
 */
export default function ApprovalDialog({ req, suspended, onExpired, onDecide }: {
  req: ApprovalRequestPayload;
  /** 挂起态：等待流已终止/到点——决策后自动续跑 */
  suspended?: boolean;
  /** 前端等待到点（30 分钟）回调 */
  onExpired?: () => void;
  onDecide: (approve: boolean, alwaysAllow: boolean) => void;
}) {
  const WAIT_SECONDS = 30 * 60;
  const [left, setLeft] = useState(WAIT_SECONDS);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (suspended) return; // 挂起后停止倒计时
    const t = setInterval(() => setLeft((l) => {
      if (l <= 1) { clearInterval(t); onExpired?.(); return 0; }
      return l - 1;
    }), 1000);
    return () => clearInterval(t);
  }, [suspended, onExpired]);

  const beforeLines = (req.before ?? '').split('\n');
  const afterLines = (req.after ?? '').split('\n');
  const changeType = req.before == null ? '新建文件' : '修改文件';
  const mm = String(Math.floor(left / 60)).padStart(2, '0');
  const ss = String(left % 60).padStart(2, '0');

  const decide = (approve: boolean, alwaysAllow: boolean) => {
    setBusy(true);
    onDecide(approve, alwaysAllow);
  };

  return (
    <div className="overlay">
      <div className="dialog">
        <div className="dialog-head">
          <span className="dialog-title">⚠ 写入需要授权</span>
          <span className="dialog-badge">{suspended ? '已暂停' : changeType}</span>
        </div>
        <p className="dialog-sub">
          模型请求<b>{req.before == null ? '创建' : '写入'}</b>文件：
          <code className="dialog-path">{req.path}</code>
          <span className="hint">（工具：{req.tool}）</span>
        </p>
        <div className="diff-box">
          <div className="diff-col">
            <p className="diff-label before-label">修改前</p>
            <pre className="diff-pre">
              {req.before == null ? '（新文件）' : beforeLines.slice(0, 200).map((l) => `- ${l}`).join('\n')}
            </pre>
          </div>
          <div className="diff-col">
            <p className="diff-label after-label">写入后</p>
            <pre className="diff-pre">
              {afterLines.slice(0, 200).map((l) => `+ ${l}`).join('\n')}
            </pre>
          </div>
        </div>
        <div className="dialog-actions">
          <button className="btn" disabled={busy} onClick={() => decide(false, false)}>拒绝</button>
          <button className="btn" disabled={busy} onClick={() => decide(true, true)}>本会话始终允许</button>
          <button className="btn primary" disabled={busy} onClick={() => decide(true, false)}>
            {suspended ? '授权并继续任务' : '允许一次'}
          </button>
        </div>
        <p className="hint">
          {suspended
            ? '任务已暂停：等待期间执行流已结束。点击授权后，将基于已完成的进度自动继续执行，无需重新发起任务。'
            : <>任务挂起等待授权，剩余 <b>{mm}:{ss}</b>；到点后任务自动暂停，回来授权即可继续。你也可以随时先发送新的指令（将在本轮完成后自动执行）。</>}
        </p>
      </div>
    </div>
  );
}
