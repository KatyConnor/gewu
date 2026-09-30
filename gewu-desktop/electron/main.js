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

const isDev = process.argv.includes('--dev');
const DEV_URL = process.env.GEWU_DEV_URL || 'http://127.0.0.1:5173';

let mainWindow = null;
let supervisor = null;
let vault = null;

// ============ 单实例锁 ============
const gotLock = app.requestSingleInstanceLock();
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
    supervisor = createSupervisor();

    createWindow();
    supervisor.start().catch((e) => console.error('[main] 引擎启动失败:', e));

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  app.on('before-quit', () => {
    if (supervisor) supervisor.stop();
  });
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
