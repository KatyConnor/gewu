/**
 * 格物桌面端主进程（D1-1/D1-2）：
 *   窗口/单实例锁/生命周期 + EngineSupervisor（JVM 引擎守护）+ SecretVault（密钥托管）+ IPC 注册。
 * 渲染层：dev 加载 Vite dev server；prod 加载 dist/index.html（file://）并注入 CSP。
 */
const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const { EngineSupervisor } = require('./engine-supervisor');
const { SecretVault } = require('./secret-vault');
const { TerminalManager } = require('./terminal-manager');

const isDev = process.argv.includes('--dev');
const DEV_URL = process.env.GEWU_DEV_URL || 'http://127.0.0.1:5173';

// 视觉回归模式：软件渲染 + 隔离 userData（独立 H2/单实例锁，
  // 不干扰用户正在运行的桌面应用与引擎——V1.2 实测教训）
if (process.env.GEWU_VISUAL_TEST) {
  app.commandLine.appendSwitch('disable-gpu');
  app.commandLine.appendSwitch('disable-software-rasterizer-vsync');
  app.setPath('userData', '/tmp/gewu-visual-userdata');
}

let mainWindow = null;
let supervisor = null;
let vault = null;
let terminals = null;

// ============ 单实例锁（视觉回归隔离 userData 后锁互不影响） ============
const gotLock = process.env.GEWU_VISUAL_TEST || app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    vault = new SecretVault(app.getPath('userData'));
    terminals = new TerminalManager();
    terminals.on('data', (tabId, data) => {
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('terminal:data', { tabId, data });
    });
    terminals.on('exit', (tabId, code) => {
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('terminal:exit', { tabId, code });
    });
    supervisor = createSupervisor();

    createWindow();
    supervisor.start().catch((e) => console.error('[main] 引擎启动失败:', e));

    if (process.env.GEWU_VISUAL_TEST) {
      runVisualTest(process.env.GEWU_VISUAL_TEST).catch((e) => {
        console.error('[visual] 失败:', e);
        setTimeout(() => app.quit(), 500);
      });
    }

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  app.on('before-quit', () => {
    if (supervisor) supervisor.stop();
    if (terminals) terminals.killAll();
  });

// ============ 视觉回归自捕获（GEWU_VISUAL_TEST=<outdir>，Phase 1 验收） ============
// 无头渲染（--ozone-platform=headless --disable-gpu）下自动截取关键状态页：
// 主页 → 工作台 → 会话(变更 chip) → 文件面板。产出 PNG 供审查比对。
async function runVisualTest(outDir) {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  fs.mkdirSync(outDir, { recursive: true });
  const capture = async (name) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    const img = await mainWindow.webContents.capturePage();
    fs.writeFileSync(path.join(outDir, name), img.toPNG());
    console.log('[visual] captured', name);
  };

  // 等引擎 ready（上限 120s，与 Supervisor 硬超时对齐）
  for (let i = 0; i < 240; i++) {
    if (supervisor && supervisor.getInfo().state === 'ready') break;
    await sleep(500);
  }
  await sleep(3000); // 渲染层 waitEngineReady + 主页首屏数据
  await capture('01-home.png');

  // 进工作台（测试钩子驱动，确定性高于 DOM click）
  await mainWindow.webContents.executeJavaScript("window.__gewu_test.newSession(); 'ok'");
  await sleep(1500);
  await capture('02-workbench.png');

  // 种一个文件变更（引擎 REST：建会话 + 写文件），驱动 chip 与面板
  try {
    const info = supervisor.getInfo();
    const base = `http://127.0.0.1:${info.port}/api/v1`;
    const headers = { 'Content-Type': 'application/json', 'X-Engine-Token': info.token };
    const sessRes = await fetch(`${base}/ai/sessions`, {
      method: 'POST', headers,
      body: JSON.stringify({ title: '视觉回归会话' }),
    });
    const sessEnv = await sessRes.json();
    global.__visLastSessionId = (sessEnv.data || sessEnv).sessionId;
    await fetch(`${base}/sessions/${(sessEnv.data || sessEnv).sessionId}/file-changes/content?path=visual-test.md`, {
      method: 'PUT', headers,
      body: JSON.stringify({ content: '# 视觉回归\n第一行内容\n第二行内容' }),
    });
  } catch (e) {
    console.log('[visual] 种子失败:', e.message);
  }

  // 打开种子会话（测试钩子）→ 渲染层 refreshFileChanges → chip 出现
  const lastSessionId = global.__visLastSessionId;
  if (lastSessionId) await mainWindow.webContents.executeJavaScript(
    `window.__gewu_test.openSession(${JSON.stringify(lastSessionId)}); 'ok'`);
  await sleep(2500);
  await capture('03-session-chip.png');

  // 打开文件面板
  await mainWindow.webContents.executeJavaScript(
    "const c = document.querySelector('.chat-head .chip'); c && c.click(); 'ok'");
  await sleep(2500);
  await capture('04-filepanel.png');

  // 点『打开』→ Monaco 视图（取证核心：种子 .java + 无响应探针 + console 钩子）
  try {
    const info = supervisor.getInfo();
    const base = `http://127.0.0.1:${info.port}/api/v1`;
    const headers = { 'Content-Type': 'application/json', 'X-Engine-Token': info.token };
    const javaCode = fs.readFileSync(path.join(__dirname, 'fixtures/sm4.java'), 'utf8');
    const sessRes = await fetch(`${base}/ai/sessions`, {
      method: 'POST', headers,
      body: JSON.stringify({ title: '取证会话' }),
    });
    const sessEnv = await sessRes.json();
    const javaSessionId = (sessEnv.data || sessEnv).sessionId;
    await fetch(`${base}/sessions/${javaSessionId}/file-changes/content?path=Sm4Util.java`, {
      method: 'PUT', headers,
      body: JSON.stringify({ content: javaCode }),
    });
    await mainWindow.webContents.executeJavaScript(
      `window.__gewu_test.openSession(${JSON.stringify(javaSessionId)}); 'ok'`);
    await sleep(2000);
  } catch (e) {
    console.log('[visual] 取证种子失败:', e.message);
  }
  await mainWindow.webContents.executeJavaScript(
    "(async () => { const btns = []; "
    + "document.querySelectorAll('button').forEach(b => { if (b.textContent === '打开') btns.push(b); }); "
    + "if (btns.length) btns[btns.length - 1].click(); return 'clicked:' + btns.length; })(); 'ok'");
  // 无响应探针：主线程阻塞时 evaluate 会超时（取证核心）
  for (let t = 1; t <= 4; t++) {
    await sleep(3000);
    const probeStart = Date.now();
    try {
      await Promise.race([
        mainWindow.webContents.executeJavaScript('"probe:" + (1+1)').then((v) => v),
        new Promise((_, rej) => setTimeout(() => rej(new Error('EVAL_TIMEOUT')), 4000)),
      ]);
      console.log(`[probe] ${t * 3}s 主线程响应正常 (${Date.now() - probeStart}ms)`);
    } catch (e) {
      console.error(`[probe] ${t * 3}s 主线程阻塞！${e.message}`);
    }
    await capture(`07-freeze-${t * 3}s.png`);
  }

  // 发送消息驱动工具调用循环（时间线交错 + 授权[视觉模式自动] + 文件落地）
  await mainWindow.webContents.executeJavaScript(
    "window.__gewu_test.send('在当前工作区创建 visual-sort.txt，内容写：冒泡排序验证'); 'ok'");
  await sleep(2000);
  await capture('05-timeline-streaming.png'); // 流中：展开态实时交错行
  await sleep(9000); // 等待完成
  // 完成后默认折叠：点击时间线头部展开（验证交错条目与正文段）
  await mainWindow.webContents.executeJavaScript(
    "const b = document.querySelector('.msg.assistant .mb-1 > button'); b && b.click(); 'ok'");
  await sleep(1200);
  await capture('06-timeline-done.png');

  console.log('[visual] done');
  setTimeout(() => app.quit(), 800);
}
}

// ============ 引擎守护 ============
function engineJarPath() {
  // 打包态：resources/runtime/desktop-runtime.jar（build 流程拷入）；开发态：相邻模块 target
  const bundled = path.join(process.resourcesPath || '', 'runtime', 'desktop-runtime.jar');
  if (fs.existsSync(bundled)) return bundled;
  const devJar = path.join(app.getAppPath(), '..', 'gewu-desktop-runtime', 'target',
    'gewu-desktop-runtime-1.0.0-SNAPSHOT.jar');
  if (fs.existsSync(devJar)) return devJar;
  throw new Error('未找到引擎 jar（打包 resources/runtime 或开发态 gewu-desktop-runtime/target）');
}

function createSupervisor() {
  const supervisor = new EngineSupervisor({
    jarPath: engineJarPath(),
    dataDir: path.join(app.getPath('userData'), 'engine-data'),
    modelKeys: vault.getModelKeys(),
    profiles: process.env.GEWU_ENGINE_PROFILE || undefined,
    logFile: path.join(app.getPath('userData'), 'engine.log'),
  });
  supervisor.on('status', (s) => {
    console.log('[engine]', s.state, s.detail || '');
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('engine:status', { ...s, info: supervisor.getInfo() });
    }
  });
  return supervisor;
}

// ============ 窗口 ============
function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 680,
    title: '格物桌面',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
    },
  });

  // 生产态 CSP（file:// 无响应头，经 onHeadersReceived 注入；127.0.0.1 为本地引擎数据面）
  mainWindow.webContents.session.webRequest.onHeadersReceived((details, callback) => {
    if (details.url.startsWith('file://')) {
      callback({
        responseHeaders: {
          ...details.responseHeaders,
          'Content-Security-Policy': [
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "connect-src 'self' http://127.0.0.1:* http://localhost:*; img-src 'self' data:; font-src 'self' data:",
          ],
        },
      });
    } else {
      callback({ responseHeaders: details.responseHeaders });
    }
  });

  // 渲染层诊断（console/崩溃/无响应 → 主进程日志，视觉回归可捕获真实报错）
  mainWindow.webContents.on('console-message', (_e, level, message, line, source) => {
    if (level >= 2) console.log(`[renderer:${level}] ${message} @${source}:${line}`);
  });
  mainWindow.webContents.on('render-process-gone', (_e, details) => {
    console.error('[renderer] 进程崩溃:', details.reason, details.exitCode);
  });
  mainWindow.webContents.on('unresponsive', () => console.error('[renderer] 无响应！'));

  // 外链走系统浏览器
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http')) shell.openExternal(url);
    return { action: 'deny' };
  });

  if (isDev) {
    mainWindow.loadURL(DEV_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// ============ IPC（§5.3 控制面通道） ============
ipcMain.handle('engine:getInfo', () => (supervisor ? supervisor.getInfo() : { state: 'stopped' }));
ipcMain.handle('engine:restart', async () => {
  if (!supervisor) return { state: 'stopped' };
  supervisor.restarts = 0;
  await supervisor.restart();
  return supervisor.getInfo();
});
ipcMain.handle('secret:saveModelKey', (_e, payload) => {
  // payload: {providerCode, apiKey, displayName, baseUrl, modelsJson}
  if (!payload || !payload.providerCode) throw new Error('providerCode 必填');
  vault.saveModelKey(payload.providerCode, payload.apiKey || '', {
    displayName: payload.displayName,
    baseUrl: payload.baseUrl,
    modelsJson: payload.modelsJson,
  });
  // Key 随下次引擎启动注入 KeyVault；运行期立即生效由渲染层直调 /engine/providers
  return { ok: true };
});
ipcMain.handle('secret:listProviders', () => (vault ? vault.listProviders() : []));

// ============ 终端（D1-25） ============
ipcMain.handle('terminal:create', (_e, opts) => {
  const cwd = opts && opts.cwd ? opts.cwd : path.join(app.getPath('home'));
  return terminals.create({ cwd, cols: opts && opts.cols, rows: opts && opts.rows, shell: opts && opts.shell });
});
ipcMain.handle('terminal:write', (_e, { tabId, data }) => terminals.write(tabId, data));
ipcMain.handle('terminal:resize', (_e, { tabId, cols, rows }) => terminals.resize(tabId, cols, rows));
ipcMain.handle('terminal:kill', (_e, { tabId }) => terminals.kill(tabId));
