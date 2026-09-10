/**
 * 格物平台桌面客户端 - Electron 主进程
 *
 * 渲染层直接加载格物平台 Web 端（Next.js）：
 *   - 生产模式加载 GEWU_WEB_URL（默认 http://127.0.0.1:5001）
 *   - 开发模式（electron . --dev）加载 GEWU_DEV_URL（默认 http://localhost:5001）
 * Web 端未启动时会展示带重试按钮的错误页。
 */
const { app, BrowserWindow, shell, Menu } = require('electron');
const path = require('path');

const isDev = process.argv.includes('--dev');
const WEB_URL = process.env.GEWU_WEB_URL || 'http://127.0.0.1:5001';
const DEV_URL = process.env.GEWU_DEV_URL || 'http://localhost:5001';
const TARGET_URL = isDev ? DEV_URL : WEB_URL;

let mainWindow = null;

const ERROR_PAGE_HTML = (target) =>
  'data:text/html;charset=utf-8,' +
  encodeURIComponent(`<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<style>
  body { font-family: system-ui, sans-serif; display: flex; align-items: center;
         justify-content: center; height: 100vh; margin: 0; background: #f5f6f8; }
  .box { text-align: center; color: #333; }
  h1 { font-size: 20px; margin-bottom: 8px; }
  p { color: #888; margin-bottom: 24px; }
  button { padding: 8px 32px; font-size: 14px; border: 1px solid #4a6cf7;
           background: #4a6cf7; color: #fff; border-radius: 6px; cursor: pointer; }
  button:hover { background: #3a5ce5; }
</style></head>
<body><div class="box">
  <h1>无法连接格物平台服务</h1>
  <p>目标地址 ${target} 不可达，请确认平台服务已启动后重试</p>
  <button onclick="location.replace('${target}')">重新连接</button>
</div></body></html>`);

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 680,
    title: '格物平台',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  // 非 macOS 下隐藏菜单栏（保留 macOS 系统菜单以维持快捷键惯例）
  if (process.platform !== 'darwin') {
    Menu.setApplicationMenu(null);
  }

  mainWindow.loadURL(TARGET_URL);

  mainWindow.webContents.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    // 仅拦截主框架加载失败；忽略用户主动取消（-3）
    if (isMainFrame && code !== -3) {
      mainWindow.loadURL(ERROR_PAGE_HTML(`${url} (${code}: ${desc})`));
    }
  });

  // 外部链接交给系统浏览器，避免客户端内跳转丢失会话
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.on('closed', () => { mainWindow = null; });
}

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

  app.whenReady().then(createWindow);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
