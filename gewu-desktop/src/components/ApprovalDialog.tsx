import React, { useState } from 'react';
import type { ApprovalRequestPayload } from '@gewu/agent-ui/protocol';

/**
 * 写授权弹窗（D1-21）：模型写文件前挂起，等待用户 diff 确认。
 * 三按钮：允许一次 / 本会话始终允许（白名单跳过后续同类写）/ 拒绝。
 */
export default function ApprovalDialog({ req, onDecide }: {
  req: ApprovalRequestPayload;
  onDecide: (approve: boolean, alwaysAllow: boolean) => void;
}) {
  const [busy, setBusy] = useState(false);
  const beforeLines = (req.before ?? '').split('\n');
  const afterLines = (req.after ?? '').split('\n');
  const changeType = req.before == null ? '新建文件' : '修改文件';

  const decide = (approve: boolean, alwaysAllow: boolean) => {
    setBusy(true);
    onDecide(approve, alwaysAllow);
  };

  return (
    <div className="overlay">
      <div className="dialog">
        <div className="dialog-head">
          <span className="dialog-title">⚠ 写入需要授权</span>
          <span className="dialog-badge">{changeType}</span>
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
          <button className="btn primary" disabled={busy} onClick={() => decide(true, false)}>允许一次</button>
        </div>
        <p className="hint">决策后对话将继续；拒绝时模型会收到拒绝原因。5 分钟未决策视为拒绝。</p>
      </div>
    </div>
  );
}
