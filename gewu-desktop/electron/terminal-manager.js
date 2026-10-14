/**
 * TerminalManager（D1-25）：交互式终端会话池。
 * 双层实现：优先 node-pty（原生 PTY）；不可用时 Linux 回退
 * `script -qec <shell> /dev/null`（util-linux 提供 PTY，零原生编译——Phase 2 稳妥路线）。
 * 会话按 tabId 隔离；数据/退出经事件上抛主进程转发渲染层。
 */
const { spawn } = require('child_process');
const { EventEmitter } = require('events');
const fs = require('fs');

class TerminalManager extends EventEmitter {
  constructor() {
    super();
    this.sessions = new Map(); // tabId → proc/pty
    this.seq = 0;
  }

  /** 创建会话；返回 {tabId, backend} */
  create(opts = {}) {
    const tabId = 'term-' + (++this.seq);
    const cwd = opts.cwd && fs.existsSync(opts.cwd) ? opts.cwd : undefined;
    const cols = opts.cols || 80;
    const rows = opts.rows || 24;
    // M-10（UCSS SEC-INJ-10）：shell 白名单——渲染层可控参数不得指向任意可执行文件
    const ALLOWED_SHELLS = new Set(['bash', 'sh', 'zsh', 'fish', 'dash', 'ksh']);
    let shell = opts.shell || process.env.SHELL || 'bash';
    const shellBase = String(shell).split('/').pop();
    if (!ALLOWED_SHELLS.has(shellBase)) shell = process.env.SHELL || 'bash';

    let proc = null;
    let backend = 'script';
    try {
      // 优先 node-pty（若宿主可用）
      // eslint-disable-next-line global-require
      const pty = require('node-pty');
      proc = pty.spawn(shell, [], {
        name: 'xterm-256color',
        cols, rows,
        cwd,
        env: { ...process.env, TERM: 'xterm-256color' },
      });
      backend = 'node-pty';
      proc.onData((d) => this.emit('data', tabId, d));
      proc.onExit(({ exitCode }) => this.emit('exit', tabId, exitCode));
      this.sessions.set(tabId, { proc, backend, kind: 'pty' });
    } catch (noPty) {
      // 回退：script 提供 PTY（Linux）
      proc = spawn('script', ['-qec', shell, '/dev/null'], {
        cwd,
        env: { ...process.env, TERM: 'xterm-256color' },
        stdio: ['pipe', 'pipe', 'pipe'],
      });
      backend = 'script';
      proc.stdout.on('data', (d) => this.emit('data', tabId, d.toString()));
      proc.stderr.on('data', (d) => this.emit('data', tabId, d.toString()));
      proc.on('exit', (code) => this.emit('exit', tabId, code));
      this.sessions.set(tabId, { proc, backend, kind: 'child' });
    }
    this.emit('created', { tabId, backend });
    return { tabId, backend };
  }

  write(tabId, data) {
    const s = this.sessions.get(tabId);
    if (!s) return;
    if (s.kind === 'pty') s.proc.write(data);
    else s.proc.stdin.write(data);
  }

  /** script 回退不支持热 resize——仅 node-pty 生效；回退终端可忽略 */
  resize(tabId, cols, rows) {
    const s = this.sessions.get(tabId);
    if (s && s.kind === 'pty' && s.proc.resize) {
      try { s.proc.resize(cols, rows); } catch { /* 忽略 */ }
    }
  }

  kill(tabId) {
    const s = this.sessions.get(tabId);
    if (!s) return;
    try {
      if (s.kind === 'pty') s.proc.kill();
      else {
        s.proc.kill('SIGTERM');
        setTimeout(() => { try { s.proc.kill('SIGKILL'); } catch { /* 已退出 */ } }, 1500);
      }
    } catch { /* 已退出 */ }
    this.sessions.delete(tabId);
    this.emit('exit', tabId, 0);
  }

  killAll() {
    for (const tabId of [...this.sessions.keys()]) this.kill(tabId);
  }
}

module.exports = { TerminalManager };
