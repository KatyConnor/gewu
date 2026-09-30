/**
 * EngineSupervisor（D1-3）—— 本地 Agent 引擎子进程守护。
 * 纯 Node 模块（零 Electron 依赖，可独立单测）：
 *   选空闲端口 → spawn JVM（env 注入令牌/Key/数据目录）→ 健康轮询 → 就绪通知；
 *   崩溃自动重启 ≤3 次（指数退避），应用退出时优雅停止。
 * 事件：'status' {state: booting|ready|crashed|stopped|degraded, detail?}
 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');

const MAX_RESTARTS = 3;
const HEALTH_SOFT_TIMEOUT_MS = 45_000;
const HEALTH_TIMEOUT_MS = 120_000;
const HEALTH_POLL_MS = 500;

function pickFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

function healthOnce(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/api/v1/engine/health', timeout: 1500 },
      (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => resolve(res.statusCode === 200 && body.includes('"UP"')));
      }
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

class EngineSupervisor extends EventEmitter {
  /**
   * @param {object} opts
   *   jarPath      引擎 jar 绝对路径
   *   javaPath     java 可执行（缺省依次找 bundled JRE → JAVA_HOME → PATH）
   *   dataDir      引擎数据目录（H2/日志）
   *   modelKeys    {providerCode: apiKey}（经 GEWU_MODEL_KEYS 注入 KeyVault，仅驻引擎内存）
   *   profiles     Spring profile（开发态 'stub'；生产缺省）
   *   logFile      引擎输出日志路径
   */
  constructor(opts) {
    super();
    this.opts = opts;
    this.child = null;
    this.port = null;
    this.token = null;
    this.restarts = 0;
    this.stoppedByUser = false;
    this.state = 'stopped';
  }

  emitState(state, detail) {
    this.state = state;
    this.emit('status', { state, detail });
  }

  resolveJava() {
    if (this.opts.javaPath && fs.existsSync(this.opts.javaPath)) return this.opts.javaPath;
    const candidates = [
      this.opts.javaPath,
      process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', 'java'),
      '/usr/bin/java',
    ].filter(Boolean);
    for (const c of candidates) {
      try {
        fs.accessSync(c, fs.constants.X_OK);
        return c;
      } catch {
        /* 尝试下一个 */
      }
    }
    return 'java';
  }

  async start() {
    if (this.child) return;
    this.stoppedByUser = false;
    this.emitState('booting');
    this.port = this.opts.port || (await pickFreePort());
    this.token = crypto.randomUUID().replace(/-/g, '');

    const java = this.resolveJava();
    const args = ['-XX:TieredStopAtLevel=1', '-Xmx256m', '-jar', this.opts.jarPath, `--server.port=${this.port}`];
    if (this.opts.profiles) {
      args.push(`--spring.profiles.active=${this.opts.profiles}`);
    }
    const env = {
      ...process.env,
      GEWU_ENGINE_TOKEN: this.token,
      GEWU_MODEL_KEYS: JSON.stringify(this.opts.modelKeys || {}),
      GEWU_DESKTOP_DATADIR: this.opts.dataDir,
    };

    const logStream = this.opts.logFile
      ? fs.createWriteStream(this.opts.logFile, { flags: 'a' })
      : null;
    this.child = spawn(java, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    this.child.stdout.pipe(logStream, { end: false });
    this.child.stderr.pipe(logStream, { end: false });
    this.emit('pid', this.child.pid);

    const handleExit = (code) => {
      this.child = null;
      if (logStream) logStream.end();
      if (this.stoppedByUser) {
        this.emitState('stopped');
        return;
      }
      if (this.restarts < MAX_RESTARTS) {
        this.restarts += 1;
        const backoff = 1000 * this.restarts;
        this.emitState('crashed', `退出码 ${code}，${backoff / 1000}s 后第 ${this.restarts}/${MAX_RESTARTS} 次重启`);
        setTimeout(() => this.start().catch(() => {}), backoff);
      } else {
        this.emitState('degraded', `连续崩溃 ${MAX_RESTARTS} 次，请查看引擎日志`);
      }
    };
    // exit：进程退出；error：spawn 失败（如找不到 java）——两者走同一恢复路径，防宿主崩溃
    this.child.on('exit', handleExit);
    this.child.on('error', (err) => {
      this.emitState('crashed', 'spawn 失败: ' + err.message);
      handleExit(-1);
    });

    // 健康轮询（令牌豁免端点）。分层超时（V1.2 实测教训）：
    //   软超时 45s：仅提示"仍在启动"（高负载机器实测 22~32s，不得误判 degraded）；
    //   硬超时 120s：杀进程 → exit 处理器走重启路径（≤3 次），而非静默挂起。
    const started = Date.now();
    let softWarned = false;
    while (this.child && Date.now() - started < HEALTH_TIMEOUT_MS) {
      if (await healthOnce(this.port)) {
        this.emitState('ready', { port: this.port, token: this.token });
        return true;
      }
      const elapsed = Date.now() - started;
      if (!softWarned && elapsed > HEALTH_SOFT_TIMEOUT_MS) {
        this.emitState('booting', `仍在启动（${Math.round(elapsed / 1000)}s，负载高时会到 30s+）`);
        softWarned = true;
      }
      await new Promise((r) => setTimeout(r, HEALTH_POLL_MS));
    }
    if (!this.child) {
      return false; // 进程已退出：exit 处理器负责重启计数
    }
    // 硬超时：杀进程触发重启路径
    this.emitState('crashed', `引擎启动超时（${HEALTH_TIMEOUT_MS / 1000}s），自动重启`);
    try {
      this.child.kill('SIGKILL');
    } catch {
      /* 已退出 */
    }
    return false;
  }

  /** 就绪后的连接信息（渲染层直连数据面） */
  getInfo() {
    return {
      state: this.state,
      port: this.port,
      token: this.token,
      pid: this.child ? this.child.pid : null,
    };
  }

  stop() {
    this.stoppedByUser = true;
    if (this.child) {
      this.child.kill('SIGTERM');
      const child = this.child;
      setTimeout(() => {
        try {
          if (child.exitCode === null) child.kill('SIGKILL');
        } catch {
          /* 已退出 */
        }
      }, 5_000);
    }
  }

  async restart() {
    this.restarts = 0;
    this.stop();
    await new Promise((r) => setTimeout(r, 800));
    return this.start();
  }
}

module.exports = { EngineSupervisor };
