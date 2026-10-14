/**
 * 格物桌面端主进程（D1-1/D1-2）：
 *   窗口/单实例锁/生命周期 + EngineSupervisor（JVM 引擎守护）+ SecretVault（密钥托管）+ IPC 注册。
 * 渲染层：dev 加载 Vite dev server；prod 加载 dist/index.html（file://）并注入 CSP。
 */
const { app, BrowserWindow, ipcMain, shell, dialog, Menu, Tray, nativeImage } = require('electron');
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
let supervisorStarted = false;
let vault = null;
let terminals = null;
let tray = null;

// ============ 使用模式配置（首次启动引导 + 本地/平台切换） ============
// mode: 'unconfigured'(首次,未选择) | 'local'(本地离线,现状实现) | 'cloud'(格物平台,加载线上界面)
// 持久化于 userData/gewu-launch.json;云模式不启动本地引擎,窗口用系统标题栏直接加载平台地址
function launchConfigPath() {
  // 使用模式配置收纳到 ~/.gewu（数据根统一）；旧 userData/gewu-launch.json 启动时自动迁移
  return path.join(app.getPath('home'), '.gewu', 'launch.json');
}

function migrateLaunchConfig() {
  try {
    const legacy = path.join(app.getPath('userData'), 'gewu-launch.json');
    if (fs.existsSync(legacy) && !fs.existsSync(launchConfigPath())) {
      fs.copyFileSync(legacy, launchConfigPath());
      console.log('[main] 使用模式配置已迁移:', legacy, '->', launchConfigPath());
    }
  } catch { /* 迁移失败按无配置处理 */ }
}

function readLaunchConfig() {
  try {
    return JSON.parse(fs.readFileSync(launchConfigPath(), 'utf8'));
  } catch {
    return null;
  }
}

function writeLaunchConfig(cfg) {
  fs.writeFileSync(launchConfigPath(), JSON.stringify(cfg, null, 2));
}

/** 生效配置:视觉回归模式强制 local(隔离 userData 下不落引导配置,不干扰既有捕获流) */
function effectiveLaunchConfig() {
  if (process.env.GEWU_FORCE_ONBOARDING) return { mode: 'unconfigured' };
  if (process.env.GEWU_VISUAL_CRED_TEST) {
    // 凭据管理器端到端验证:云模式 + 静态假登录页(隔离 userData,不污染真实配置)
    return { mode: 'cloud', cloudUrl: 'http://127.0.0.1:5002/' };
  }
  if (process.env.GEWU_VISUAL_TEST) return { mode: 'local' };
  const cfg = readLaunchConfig();
  if (!cfg || !cfg.mode) return { mode: 'unconfigured' };
  return cfg;
}

let trayMenuRebuild = null;

let modeSwitching = false; // 模式切换销毁窗口期间防 window-all-closed 误退出(顶层:applyLaunchMode 与回调同源可见)

/** 应用使用模式(无重启):窗口重建 + 引擎生命周期切换。
 *  此前用 relaunch+exit——旧实例锁释放与新实例启动竞态,新实例拿不到单实例锁即退,
 *  用户实测点切换按钮后应用退出不重启。窗口内重建无此竞态。 */
function applyLaunchMode(mode) {
  modeSwitching = true;
  try {
    writeLaunchConfig({ ...readLaunchConfig(), mode });
    trayMenuRebuild?.();
    if (mode === 'local') {
      if (!supervisorStarted) {
        supervisor = supervisor ?? createSupervisor();
        supervisorStarted = true;
        supervisor.start().catch((e) => console.error('[main] 引擎启动失败:', e));
      }
    } else if (supervisorStarted && supervisor) {
      supervisor.stop();
      supervisorStarted = false;
    }
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.destroy();
    mainWindow = null;
    createWindow();
    if (mode === 'cloud') { injectCloudChrome(); injectCredentialManager(); }
  } finally {
    modeSwitching = false;
  }
}

function switchLaunchMode(mode) {
  const cfg = readLaunchConfig() ?? {};
  if (mode === 'cloud' && !cfg.cloudUrl) return; // 未配置平台地址:先在设置中配置
  applyLaunchMode(mode);
}

function createTray() {
  const iconPath = path.join(__dirname, '..', 'build', 'icon.png');
  if (!fs.existsSync(iconPath)) return;
  tray = new Tray(nativeImage.createFromPath(iconPath));
  tray.setToolTip('格物桌面');
  trayMenuRebuild = () => {
    const cfg = effectiveLaunchConfig();
    const menu = Menu.buildFromTemplate([
      { label: '本地离线使用', enabled: cfg.mode !== 'local',
        click: () => switchLaunchMode('local') },
      { label: '格物平台登录使用', enabled: cfg.mode !== 'cloud' && !!cfg.cloudUrl,
        click: () => switchLaunchMode('cloud') },
      { type: 'separator' },
      { label: '退出格物桌面', click: () => app.quit() },
    ]);
    tray.setContextMenu(menu);
  };
  trayMenuRebuild();
}

// ============ 单实例锁（视觉回归隔离 userData 后锁互不影响） ============
const gotLock = process.env.GEWU_VISUAL_TEST || process.env.GEWU_VISUAL_CRED_TEST || process.env.GEWU_TEST_NO_LOCK || app.requestSingleInstanceLock();
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
    // 无边框自绘标题栏（D3-11 前置）：移除系统菜单栏（File/Edit/View/Window 白条），
    // 窗口控制（最小化/最大化/关闭）由渲染层 TitleBar 经 IPC 驱动
    Menu.setApplicationMenu(null);
    vault = new SecretVault(app.getPath('userData'));
    terminals = new TerminalManager();
    terminals.on('data', (tabId, data) => {
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('terminal:data', { tabId, data });
    });
    terminals.on('exit', (tabId, code) => {
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('terminal:exit', { tabId, code });
    });

    migrateLaunchConfig();
    const launchCfg = effectiveLaunchConfig();
    createTray();

    if (launchCfg.mode === 'cloud' && launchCfg.cloudUrl) {
      // 格物平台模式:直接加载线上界面(系统标题栏),本地引擎不启动
      createWindow();
    } else {
      if (launchCfg.mode === 'local') {
        supervisor = createSupervisor();
      }
      createWindow();
      if (supervisor) {
        supervisorStarted = true;
        supervisor.start().catch((e) => console.error('[main] 引擎启动失败:', e));
      }
    }

    if (process.env.GEWU_VISUAL_TEST || process.env.GEWU_VISUAL_CRED_TEST) {
      runVisualTest(process.env.GEWU_VISUAL_CRED_TEST || process.env.GEWU_VISUAL_TEST).catch((e) => {
        console.error('[visual] 失败:', e);
        setTimeout(() => app.quit(), 500);
      });
    }

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    // 模式切换(本地↔平台)会销毁旧窗口重建——此间窗口数瞬时为 0,不退出
    if (modeSwitching) return;
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

  // 凭据管理器端到端(GEWU_VISUAL_CRED_TEST):假登录页保存→重载→自动填充
  if (process.env.GEWU_VISUAL_CRED_TEST) {
    let formProbe = false;
    for (let i = 0; i < 20; i++) {
      formProbe = await mainWindow.webContents.executeJavaScript(
        '!!document.querySelector("input[type=password]")').catch(() => false);
      if (formProbe) { console.log('[cred] form ready at', (i + 1) * 1000, 'ms'); break; }
      await sleep(1000);
    }
    console.log('[cred] form:', formProbe);
    await mainWindow.webContents.executeJavaScript(
      '(function(){ var set=function(el,v){var p=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set;p.call(el,v);el.dispatchEvent(new Event("input",{bubbles:true}));};' +
      ' var inputs=document.querySelectorAll("input"); var user=inputs[0]; var pwd=document.querySelector("input[type=password]");' +
      ' set(user,"001001"); set(pwd,"test123456"); var cb=document.querySelector("input[type=checkbox]"); if(cb) cb.checked=true; })()');
    await capture('c1-filled.png');
    await mainWindow.webContents.executeJavaScript(
      '[...document.querySelectorAll("button")].find(function (b) { return (b.textContent || "").replace(/\\s/g, "").indexOf("登录") >= 0; })?.click()');
    await sleep(1200);
    const vaultFile = path.join(app.getPath('userData'), 'secrets.bin');
    console.log('[cred] secrets.bin:', fs.existsSync(vaultFile));
    await mainWindow.webContents.reload();
    await sleep(4000);
    const fillResult = await mainWindow.webContents.executeJavaScript(
      '(function(){ var user=document.querySelector("input:not([type=password])");' +
      ' var pwd=document.querySelector("input[type=password]");' +
      ' return JSON.stringify({ user: user ? user.value : null, pwdLen: pwd ? pwd.value.length : 0 }); })()');
    console.log('[cred] autofill:', fillResult);
    await capture('c2-refilled.png');
    setTimeout(() => app.quit(), 500);
    return;
  }

  // 首次启动引导页捕获(GEWU_FORCE_ONBOARDING:未配置态)——复现用户点击路径取证
  if (effectiveLaunchConfig().mode === 'unconfigured') {
    await sleep(3000);
    await capture('00c-onboarding.png');
    const cardProbe = await mainWindow.webContents.executeJavaScript(
      "(() => { const cards = [...document.querySelectorAll('.onboard-card')];"
      + " const local = cards.find((c) => c.textContent.includes('本地离线使用'));"
      + " if (!local) return 'no-card'; local.click(); return 'card-clicked'; })()");
    console.log('[visual] onboarding card:', cardProbe);
    await sleep(300);
    const btnProbe = await mainWindow.webContents.executeJavaScript(
      "(() => { const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('开始本地使用'));"
      + " if (!btn) return 'no-btn'; btn.click(); return 'start-clicked'; })()");
    console.log('[visual] onboarding start:', btnProbe);
    await sleep(2500);
    await capture('00d-after-local.png');
    console.log('[visual] launch-config:', JSON.stringify(readLaunchConfig()));
    console.log('[visual] onboarding captured');
    setTimeout(() => app.quit(), 500);
    return;
  }

  // 启动画面（品牌 splash：格物商标 + 动态加载）——引擎仍在启动时捕获；
  // headless 早期合成层可能未出帧（截图空白 ≠ 渲染缺陷），先 DOM 探针取证再截图
  await sleep(5000);
  try {
    const splashProbe = await mainWindow.webContents.executeJavaScript(
      "JSON.stringify({ titlebar: !!document.querySelector('.titlebar'), splash: !!document.querySelector('.splash'),"
      + " logo: !!document.querySelector('.splash-logo svg'),"
      + " stateText: (document.querySelector('.splash-state') || {}).textContent ?? null })");
    console.log('[visual] splash-probe', splashProbe);
  } catch (e) {
    console.log('[visual] splash-probe 失败:', e.message);
  }
  await capture('00-splash.png');

  // 等引擎 ready（上限 120s，与 Supervisor 硬超时对齐）
  for (let i = 0; i < 240; i++) {
    if (supervisor && supervisor.getInfo().state === 'ready') break;
    await sleep(500);
  }
  await sleep(3000); // 渲染层 waitEngineReady + 主页首屏数据
  await capture('01-home.png');

  // 新建会话对话框（工作区选择器）：主页卡片驱动
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelector('.home-card'); el && el.click(); })(); 'ok'");
  await sleep(800);
  await capture('01b-wsdialog.png');
  // 关闭 ws 对话框（点「取消」——勿点「选择目录」：原生 dialog 在 headless 下挂起）
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.ws-dialog .dialog-actions .btn')].find((b) => b.textContent.trim() === '取消'); el && el.click(); })(); 'ok'");
  await sleep(400);
  // 统一设置面板（用户行齿轮 → 左导航 + 模型两栏编辑器）
  // 先种一个含扩展字段的供应商（模型行/高级配置回归捕获——上下文窗口/最大输出/推理配置）
  try {
    const info = supervisor.getInfo();
    const base = `http://127.0.0.1:${info.port}/api/v1`;
    const provRes = await fetch(`${base}/engine/providers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Engine-Token': info.token },
      body: JSON.stringify({
        code: 'visual-stub', displayName: '视觉回归供应商',
        baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
        modelsJson: JSON.stringify([{
          name: 'glm-visual-flash', displayName: '视觉模型',
          contextWindow: 131072, maxOutputTokens: 8192,
          inputTypes: ['text', 'image'], capabilities: ['structured_output'],
          reasoningLevels: ['low', 'high'], reasoningParams: { thinking: { type: 'enabled' } },
        }]),
      }),
    });
    if (!provRes.ok) console.log('[visual] provider 种子失败: HTTP', provRes.status, await provRes.text());
  } catch (e) {
    console.log('[visual] provider 种子失败:', e.message);
  }
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelector('.user-gear'); el && el.click(); })(); 'ok'");
  await sleep(1200);
  await capture('01c-settings.png');
  // 选中种子供应商 → 扩展模型行 → 展开高级配置（折叠区回归捕获）
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.prov-item')].find((n) => n.textContent.includes('视觉回归供应商')); el && el.click(); })(); 'ok'");
  await sleep(600);
  await capture('01c2-model-row.png');
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelector('.adv-toggle'); el && el.click(); })(); 'ok'");
  await sleep(400);
  await capture('01c3-model-adv.png');
  // 切到「子智能体」验证导航切换
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.settings-nav-item')].find((n) => n.textContent.includes('子智能体')); el && el.click(); })(); 'ok'");
  await sleep(600);
  await capture('01d-settings-agents.png');
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelector('.settings-nav-head .btn'); el && el.click(); })(); 'ok'");
  await sleep(400);

  // 进工作台（测试钩子驱动，确定性高于 DOM click）
  await mainWindow.webContents.executeJavaScript("window.__gewu_test.newSession(); 'ok'");
  await sleep(1500);
  await capture('02-workbench.png');

  // 终端抽屉（对话视图顶栏「终端」按钮驱动）——用户实测：曾仅切状态未挂载组件
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.chat-head .btn')].find((b) => b.textContent.includes('终端')); el && el.click(); })(); 'ok'");
  await sleep(2500);
  await capture('02a-terminal.png');
  // 多 Tab（D1-27）：「＋」新建第二标签后捕获
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelector('.term-tab-add'); el && el.click(); })(); 'ok'");
  await sleep(2500);
  await capture('02a2-two-tabs.png');
  // 收起→重开（用户实测：收起重开丢内容/重建新 PTY）——PTY 池大小前后对比证明会话保持
  const ptyBefore = terminals.sessions.size;
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.chat-head .btn')].find((b) => b.textContent.includes('收起终端')); el && el.click(); })(); 'ok'");
  await sleep(800);
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.chat-head .btn')].find((b) => b.textContent.trim() === '终端'); el && el.click(); })(); 'ok'");
  await sleep(1200);
  console.log('[visual] terminal-reopen pty-pool', ptyBefore, '→', terminals.sessions.size);
  await capture('02a3-terminal-reopen.png');
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.chat-head .btn')].find((b) => b.textContent.includes('收起终端')); el && el.click(); })(); 'ok'");
  await sleep(800);

  // 编排工作台（侧栏常驻导航驱动：主页/对话/编排第三颗按钮；IIFE 防全局 const 污染后续脚本）
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelectorAll('.side-nav .btn')[2]; el && el.click(); })(); 'ok'");
  await sleep(2000);
  await capture('02b-orchestration.png');
  // 回主页验证独立侧栏形态（无会话列表，管理面板/设置可见）
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelectorAll('.side-nav .btn')[0]; el && el.click(); })(); 'ok'");
  await sleep(1200);
  await capture('02c-home.png');
  await mainWindow.webContents.executeJavaScript("window.__gewu_test.newSession(); 'ok'");
  await sleep(1200);

  // 种一个文件变更（引擎 REST：建会话 + 写文件），驱动 chip 与面板
  try {
    const info = supervisor.getInfo();
    const base = `http://127.0.0.1:${info.port}/api/v1`;
    const headers = { 'Content-Type': 'application/json', 'X-Engine-Token': info.token };
    const sessRes = await fetch(`${base}/ai/sessions`, {
      method: 'POST', headers,
      body: JSON.stringify({ title: '视觉回归长标题会话——用于验证顶部信息带十八字符截断与省略号显示效果' }),
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

  // 种项目（D1-25）：绑定目录 + 项目内会话——侧栏项目分组与「项目下新建」回归捕获
  try {
    const info = supervisor.getInfo();
    const base = `http://127.0.0.1:${info.port}/api/v1`;
    const headers = { 'Content-Type': 'application/json', 'X-Engine-Token': info.token };
    const projDir = path.join(process.env.GEWU_VISUAL_TEST, 'visual-project');
    fs.mkdirSync(projDir, { recursive: true });
    const projRes = await fetch(`${base}/projects`, {
      method: 'POST', headers, body: JSON.stringify({ name: '视觉项目', directory: projDir }),
    });
    if (!projRes.ok) {
      console.log('[visual] project 种子失败: HTTP', projRes.status, await projRes.text());
    } else {
      const projEnv = await projRes.json();
      const projId = (projEnv.data || projEnv).projectId;
      await fetch(`${base}/ai/sessions`, {
        method: 'POST', headers, body: JSON.stringify({ title: '项目内会话', projectId: projId }),
      });
    }
  } catch (e) {
    console.log('[visual] project 种子失败:', e.message);
  }

  // 打开种子会话（测试钩子）→ 渲染层 refreshFileChanges → chip 出现
  const lastSessionId = global.__visLastSessionId;
  if (lastSessionId) await mainWindow.webContents.executeJavaScript(
    `window.__gewu_test.openSession(${JSON.stringify(lastSessionId)}); 'ok'`);
  await sleep(2500);
  await capture('03-session-chip.png');

  // 项目分组探针:渲染层 projects 拉取实证(D1-25 排障)
  try {
    const projProbe = await mainWindow.webContents.executeJavaScript(
      "JSON.stringify({ projectsCount: (window.__gewu_test || {}).projectsCount ?? null })");
    console.log('[visual] projects-probe', projProbe);
  } catch (e) { console.log('[visual] projects-probe 失败:', e.message); }

  // 会话尾部变更条（用户指定）：展开逐文件行回归捕获
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = document.querySelector('.turn-changes-head'); el && el.click(); })(); 'ok'");
  await sleep(400);
  await capture('03b-turn-changes.png');

  // 项目行「＋」→ 项目上下文新建会话对话框（D1-25 回归捕获）
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('button')].find((b) => (b.getAttribute('aria-label') || '').includes('视觉项目')); el && el.click(); })(); 'ok'");
  await sleep(500);
  await capture('03c-project-session-dialog.png');
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('.ws-actions .btn')].find((b) => b.textContent.trim() === '取消'); el && el.click(); })(); 'ok'");
  await sleep(300);

  // 打开文件面板
  await mainWindow.webContents.executeJavaScript(
    "const c = document.querySelector('.chat-head .chip'); c && c.click(); 'ok'");
  await sleep(2500);
  await capture('04-filepanel.png');

  // 审查 → 分栏（用户实测：分栏布局打乱整页且无法关闭——回归捕获）
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '审查'); el && el.click(); })(); 'ok'");
  await sleep(1500);
  await capture('04a-review.png');
  await mainWindow.webContents.executeJavaScript(
    "(() => { const el = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '分栏'); el && el.click(); })(); 'ok'");
  await sleep(1500);
  await capture('04b-split.png');
  // 分栏态页面滚动约束验证：html 不得可滚（滚动即布局打乱）
  const splitScroll = await mainWindow.webContents.executeJavaScript(
    "JSON.stringify({ scrollY: window.scrollY, docH: document.documentElement.scrollHeight, winH: window.innerHeight, headerVisible: !!document.querySelector('.border-l .border-b') })");
  console.log('[visual] split-scroll-check', splitScroll);

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
  // 打包态捆绑 JRE 优先（resources/jre 由 builder extraResources 打入）；
  // 开发态不存在 → resolveJava 回退 JAVA_HOME/PATH
  const bundledJava = path.join(process.resourcesPath || '', 'jre', 'bin', 'java');
  const visual = process.env.GEWU_VISUAL_TEST;
  const gewuRoot = path.join(app.getPath('home'), '.gewu');
  // 引擎数据：~/.gewu/data（旧 userData/engine-data 一次性迁移，会话/消息/项目数据无感延续）
  const dataDir = visual
    ? path.join(visual, 'engine-data')
    : path.join(gewuRoot, 'data');
  const legacyDataDir = path.join(app.getPath('userData'), 'engine-data');
  if (!visual && fs.existsSync(legacyDataDir) && !fs.existsSync(dataDir)) {
    fs.cpSync(legacyDataDir, dataDir, { recursive: true });
    console.log('[main] 引擎数据已迁移:', legacyDataDir, '->', dataDir);
  }
    // H2 库文件名统一 gewu 前缀（旧 desktop-runtime.* → gewu.*；数据库内容不变）
    for (const ext of ['mv.db', 'trace.db']) {
      const oldDb = path.join(dataDir, `desktop-runtime.${ext}`);
      const newDb = path.join(dataDir, `gewu.${ext}`);
      if (fs.existsSync(oldDb) && !fs.existsSync(newDb)) {
        fs.renameSync(oldDb, newDb);
        console.log('[main] 数据库已更名:', path.basename(oldDb), '->', path.basename(newDb));
      }
    }
    // 旧执行日志改名
    const oldLog = path.join(gewuRoot, 'logs', 'engine.log');
    const newLog = path.join(gewuRoot, 'logs', 'gewu.log');
    if (fs.existsSync(oldLog) && !fs.existsSync(newLog)) {
      fs.renameSync(oldLog, newLog);
    }
  // 默认工作区：~/.gewu/workspace（旧 启动目录/workspace 一次性迁移；修 ./workspace 随启动目录漂移）
  const workspaceRoot = visual
    ? path.join(visual, 'workspace')
    : path.join(gewuRoot, 'workspace');
  const legacyWs = path.join(app.getAppPath(), 'workspace');
  if (!visual && fs.existsSync(legacyWs) && !fs.existsSync(workspaceRoot)) {
    fs.cpSync(legacyWs, workspaceRoot, { recursive: true });
    console.log('[main] 默认工作区已迁移:', legacyWs, '->', workspaceRoot);
  }
  fs.mkdirSync(workspaceRoot, { recursive: true });
  // 技能目录扁平化:~/.gewu/skills(用户指定,不嵌套于工作区)——
  // 合并旧两处技能(启动目录/workspace/.gewu/skills 与 ~/.gewu/workspace/.gewu/skills),同名不覆盖
  const skillsDir = path.join(gewuRoot, 'skills');
  fs.mkdirSync(skillsDir, { recursive: true });
  for (const src of [
    path.join(legacyWs, '.gewu', 'skills'),
    path.join(workspaceRoot, '.gewu', 'skills'),
  ]) {
    if (!fs.existsSync(src)) continue;
    let moved = 0;
    for (const f of fs.readdirSync(src)) {
      if (!f.endsWith('.md')) continue;
      const dst = path.join(skillsDir, f);
      if (!fs.existsSync(dst)) {
        fs.copyFileSync(path.join(src, f), dst);
        moved += 1;
      }
    }
    if (moved > 0) console.log('[main] 技能已迁移:', src, '->', skillsDir, `(${moved} 个)`);
  }
  fs.mkdirSync(path.join(gewuRoot, 'logs'), { recursive: true });
  const supervisor = new EngineSupervisor({
    jarPath: engineJarPath(),
    javaPath: fs.existsSync(bundledJava) ? bundledJava : undefined,
    dataDir,
    workspaceRoot,
    // 视觉回归：引擎数据目录也隔离（userData 隔离只管 Chromium 侧，JVM 侧需显式 CLI 参数）
    isolatedDataDir: visual
      ? path.join(visual, 'engine-data')
      : undefined,
    modelKeys: vault.getModelKeys(),
    profiles: process.env.GEWU_ENGINE_PROFILE || undefined,
    logFile: path.join(app.getPath('home'), '.gewu', 'logs', 'gewu.log'),
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
  const cfg = effectiveLaunchConfig();
  const isCloud = cfg.mode === 'cloud' && !!cfg.cloudUrl;
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 680,
    title: '格物桌面',
    // 全模式无边框:本地/引导用渲染层 TitleBar,平台模式注入自绘标题栏(Linux 无 WCO)
    frame: false,
    icon: fs.existsSync(path.join(__dirname, '..', 'build', 'icon.png'))
      ? path.join(__dirname, '..', 'build', 'icon.png') : undefined,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
    },
  });

  // 最大化状态推送（TitleBar 按钮 □/❐ 图标切换）
  const sendWinState = () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('win:state', { maximized: mainWindow.isMaximized() });
    }
  };
  mainWindow.on('maximize', sendWinState);
  mainWindow.on('unmaximize', sendWinState);

  // dev 态 devtools 快捷键（应用菜单移除后默认快捷键失效）
  if (isDev) {
    mainWindow.webContents.on('before-input-event', (_e, input) => {
      if (input.type === 'keyDown' && input.key === 'F12') mainWindow.webContents.toggleDevTools();
    });
  }

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
    // 视觉回归转发全量（含 info 级插桩）；常态仅 warning+，避免日志噪音
    if (level >= 2 || process.env.GEWU_VISUAL_TEST) {
      console.log(`[renderer:${level}] ${message} @${source}:${line}`);
    }
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

  if (isCloud) {
    // 格物平台界面(用户在设置/引导中配置的线上访问地址)
    mainWindow.loadURL(cfg.cloudUrl);
    injectCloudChrome();
    injectCredentialManager();
  } else if (isDev) {
    mainWindow.loadURL(DEV_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// ============ IPC（§5.3 控制面通道） ============
// 来源分级（UCSS H-5/H-6）：云模式窗口加载远程页面，preload 桥对远程内容只保留
// 窗口控制等无害通道；引擎令牌/终端/密钥/凭据/启动配置仅限本地 UI（file:// 或本地 dev server）。
function isRemoteSender(e) {
  try {
    const u = new URL((e && e.sender && e.sender.getURL()) || 'file://');
    if (u.protocol === 'file:') return false;
    return !['127.0.0.1', 'localhost', '[::1]'].includes(u.hostname);
  } catch {
    return true;
  }
}
function assertLocalSender(e) {
  if (isRemoteSender(e)) throw new Error('远程页面无权调用本通道');
}
/** 凭据仅可读写调用方页面自身源（防跨站读取已存凭据） */
function senderOrigin(e) {
  try {
    return new URL((e && e.sender && e.sender.getURL()) || 'about:blank').origin;
  } catch {
    return 'about:blank';
  }
}
ipcMain.handle('engine:getInfo', (e) => {
  assertLocalSender(e);
  return supervisor ? supervisor.getInfo() : { state: 'stopped' };
});
// ============ 窗口控制（frameless 自绘标题栏） ============
ipcMain.handle('win:minimize', () => { if (mainWindow) mainWindow.minimize(); });
ipcMain.handle('win:maximizeToggle', () => {
  if (!mainWindow) return;
  if (mainWindow.isMaximized()) mainWindow.unmaximize(); else mainWindow.maximize();
});
ipcMain.handle('win:close', () => { if (mainWindow) mainWindow.close(); });
ipcMain.handle('win:isMaximized', () => (mainWindow ? mainWindow.isMaximized() : false));
// ============ 使用模式(首次引导 + 本地/平台切换) ============
ipcMain.handle('app:getLaunchConfig', () => effectiveLaunchConfig());
ipcMain.handle('app:switchLaunchMode', (_e, mode) => {
  if (mode !== 'local' && mode !== 'cloud') throw new Error('mode 必须为 local 或 cloud');
  switchLaunchMode(mode); // relaunch + exit:引擎生命周期/窗口差异全量重建
});
ipcMain.handle('app:saveLaunchConfig', (e, cfg) => {
  assertLocalSender(e);
  if (!cfg || (cfg.mode !== 'local' && cfg.mode !== 'cloud')) {
    throw new Error('mode 必须为 local 或 cloud');
  }
  if (cfg.mode === 'cloud') {
    // 地址校验用 URL 构造验证(仅前缀正则会放过 "https://" 这类空地址)
    let parsed = null;
    try { parsed = new URL(cfg.cloudUrl ?? ''); } catch { parsed = null; }
    if (!parsed || !/^https?:$/.test(parsed.protocol) || !parsed.hostname) {
      throw new Error('平台模式需要完整的线上访问地址(如 https://gewu.example.com)');
    }
  }
  if (parsed.protocol === 'http:') {
    // M-9（UCSS SEC-FILE-05）：明文 http 平台地址凭据可被中间人截获——留存告警日志
    console.warn('[security] 平台地址为明文 http，登录凭据存在被中间人截获风险:', parsed.hostname);
  }
  writeLaunchConfig({ ...readLaunchConfig(), ...cfg });
  trayMenuRebuild?.();
  applyLaunchMode(cfg.mode);
  return { mode: cfg.mode };
});

/** 平台模式:向线上页面注入自绘标题栏(品牌角标+页面标题+「⌂ 本地」切换+窗控三键,
 *  视觉与本地 TitleBar 一致;Linux 无 WCO,窗控经 preload 桥接 IPC) */
function injectCloudChrome() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const inject = () => {
    mainWindow?.webContents.executeJavaScript(`(function () {
      if (document.getElementById('gewu-titlebar')) return 'exists';
      var css = document.createElement('style');
      css.textContent = '#gewu-titlebar{position:fixed;top:0;left:0;right:0;height:40px;z-index:2147483647;display:flex;align-items:center;background:#0e1c1b;border-bottom:1px solid rgba(0,184,148,0.15);-webkit-app-region:drag;user-select:none;font-family:sans-serif;}' +
        '#gewu-titlebar .gtb-mark{padding-left:12px;display:flex;align-items:center;gap:8px;}' +
        '#gewu-titlebar .gtb-mark span{display:flex;width:22px;height:22px;border-radius:6px;background:#0a1413;border:1px solid rgba(0,184,148,0.4);align-items:center;justify-content:center;color:#00b894;font-size:11px;font-weight:700;}' +
          '#gewu-titlebar .gtb-act{margin-left:auto;height:100%;display:flex;align-items:center;-webkit-app-region:no-drag;padding-right:4px;}' +
        '#gewu-titlebar .gtb-act .gtb-switch{display:flex;align-items:center;gap:4px;padding:4px 12px;margin-right:4px;border-radius:14px;background:rgba(0,184,148,0.12);border:1px solid rgba(0,184,148,0.35);color:#00b894;font-size:12px;cursor:pointer;}' +
        '#gewu-titlebar .gtb-act .gtb-switch:hover{background:rgba(0,184,148,0.25);}' +
        '#gewu-titlebar .gtb-btn{width:44px;height:40px;display:flex;align-items:center;justify-content:center;background:transparent;border:none;color:#8ba89d;cursor:pointer;-webkit-app-region:no-drag;font-size:12px;}' +
        '#gewu-titlebar .gtb-btn:hover{background:rgba(255,255,255,0.08);color:#c9d1d9;}' +
        '#gewu-titlebar .gtb-btn.close:hover{background:#e81123;color:#fff;}' +
        'body{padding-top:40px !important;}';
      document.head.appendChild(css);
      var bar = document.createElement('div');
      bar.id = 'gewu-titlebar';
      bar.innerHTML = '<div class="gtb-mark"><span>格</span></div>' +
        '<div class="gtb-act"><button class="gtb-switch" id="gtb-switch" title="切换到本地离线使用">⌂ 本地</button></div>' +
        '<button class="gtb-btn" id="gtb-min" title="最小化">─</button>' +
        '<button class="gtb-btn" id="gtb-max" title="最大化">□</button>' +
        '<button class="gtb-btn close" id="gtb-close" title="关闭">✕</button>';
      document.body.appendChild(bar);
      document.getElementById('gtb-min').addEventListener('click', function () { window.gewu.win.minimize(); });
      var maxBtn = document.getElementById('gtb-max');
      maxBtn.addEventListener('click', function () { window.gewu.win.maximizeToggle(); });
      window.gewu.win.onState(function (st) { maxBtn.textContent = st.maximized ? '❐' : '□'; });
      document.getElementById('gtb-close').addEventListener('click', function () { window.gewu.win.close(); });
      document.getElementById('gtb-switch').addEventListener('click', function () { window.gewu.gewuDesktopSwitchLocal(); });
      document.getElementById('gewu-titlebar').addEventListener('dblclick', function () { window.gewu.win.maximizeToggle(); });
      return 'injected';
    })()`).catch(() => {});
  };
  mainWindow.webContents.on('did-finish-load', inject);
  inject();
}

/** 平台模式:登录凭据管理器(「记住我」浏览器级体验)——
 *  MutationObserver 检测登录表单:有保存凭据时自动填充(React native setter 触发识别);
 *  登录按钮点击且「记住我」勾选时保存(经 IPC safeStorage 加密落盘,按平台地址隔离) */
function injectCredentialManager() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const inject = () => {
    mainWindow?.webContents.executeJavaScript(`(function () {
      if (window.__gewuCredMgr) return 'exists';
      window.__gewuCredMgr = true;
      var setVal = function (el, v) {
        var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      };
      var findFields = function () {
        var pwd = document.querySelector('input[type="password"]:not([readonly])');
        if (!pwd) return null;
        var user = null;
        var all = Array.prototype.slice.call(document.querySelectorAll('input'));
        for (var i = 0; i < all.length; i++) {
          if (all[i] === pwd) break;
          var t = (all[i].type || 'text').toLowerCase();
          if (t === 'text' || t === 'email' || t === 'tel') { user = all[i]; }
        }
        if (!user) return null;
        return { user: user, pwd: pwd };
      };
      var rememberChecked = function () {
        var boxes = document.querySelectorAll('input[type="checkbox"]');
        for (var i = 0; i < boxes.length; i++) {
          var scope = boxes[i].closest('label') || boxes[i].parentElement;
          if ((scope.textContent || '').indexOf('记住') >= 0) return boxes[i].checked;
        }
        return true;
      };
      var bindSave = function (fields) {
        var btn = Array.prototype.slice.call(document.querySelectorAll('button')).find(function (b) {
          return (b.textContent || '').replace(/\s/g, '').indexOf('登录') >= 0;
        });
        if (!btn || btn.__gewuSave) return;
        btn.__gewuSave = true;
        btn.addEventListener('click', function () {
          var u = fields.user.value, p = fields.pwd.value;
          if (u && p && rememberChecked()) {
            window.gewu.saveWebCredential(location.origin, u, p);
          }
        }, true);
      };
      var scan = function () {
        var fields = findFields();
        if (!fields || fields.user.__gewuBound) return;
        fields.user.__gewuBound = true;
        bindSave(fields);
        window.gewu.getWebCredential(location.origin).then(function (cred) {
          if (cred && cred.username && !fields.user.value) {
            setVal(fields.user, cred.username);
            setVal(fields.pwd, cred.password);
          }
        }).catch(function () {});
      };
      var start = function () {
        scan();
        new MutationObserver(function () { scan(); })
          .observe(document.body, { childList: true, subtree: true });
      };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
      else start();
    })()`).catch(() => {});
  };
  mainWindow.webContents.on('did-finish-load', inject);
  inject();
}
/** 工作区选择器（会话绑定本地目录）：主进程原生目录对话框 */
ipcMain.handle('dir:choose', async (e) => {
  assertLocalSender(e);
  const result = await dialog.showOpenDialog(mainWindow, {
    title: '选择会话工作目录',
    properties: ['openDirectory', 'createDirectory'],
  });
  return result.canceled || !result.filePaths.length ? null : result.filePaths[0];
});
ipcMain.handle('engine:restart', async (e) => {
  assertLocalSender(e);
  if (!supervisor) return { state: 'stopped' };
  supervisor.restarts = 0;
  await supervisor.restart();
  return supervisor.getInfo();
});
ipcMain.handle('secret:saveModelKey', (e, payload) => {
  assertLocalSender(e);
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
ipcMain.handle('secret:listProviders', (e) => {
  assertLocalSender(e);
  return vault ? vault.listProviders() : [];
});
// ============ 平台登录凭据(云模式「记住我」自动填充) ============
ipcMain.handle('creds:save', (e, siteKey, username, password) => {
  if (!siteKey || !username || password == null) throw new Error('凭据参数不完整');
  // 源绑定（UCSS H-6）：只能写当前页面自身源
  if (String(siteKey) !== senderOrigin(e)) throw new Error('凭据只能保存到当前页面源');
  vault.saveWebCredential(String(siteKey), String(username), String(password));
  return { ok: true };
});
ipcMain.handle('creds:get', (e, siteKey) => {
  // 源绑定（UCSS H-6）：非本源的读取一律按不存在处理（不泄露存在性）
  if (!siteKey || String(siteKey) !== senderOrigin(e)) return null;
  return vault ? vault.getWebCredential(String(siteKey)) : null;
});

// ============ 终端（D1-25） ============
ipcMain.handle('terminal:create', (e, opts) => {
  assertLocalSender(e);
  const cwd = opts && opts.cwd ? opts.cwd : path.join(app.getPath('home'));
  return terminals.create({ cwd, cols: opts && opts.cols, rows: opts && opts.rows, shell: opts && opts.shell });
});
ipcMain.handle('terminal:write', (e, { tabId, data }) => { assertLocalSender(e); return terminals.write(tabId, data); });
ipcMain.handle('terminal:resize', (e, { tabId, cols, rows }) => { assertLocalSender(e); return terminals.resize(tabId, cols, rows); });
ipcMain.handle('terminal:kill', (e, { tabId }) => { assertLocalSender(e); return terminals.kill(tabId); });
