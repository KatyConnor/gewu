/**
 * preload（D1-5）：contextBridge 类型化 API —— 渲染层唯一特权面。
 * 数据面（引擎 REST/SSE）由渲染层直连 127.0.0.1（携带 engine:getInfo 交付的临时令牌）；
 * 控制面（引擎生命周期/密钥/终端）经 IPC 由主进程代理。
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('gewu', {
  /** 引擎连接信息 {state, port, token, pid} */
  getEngineInfo: () => ipcRenderer.invoke('engine:getInfo'),
  /** 手动重启引擎（诊断态） */
  restartEngine: () => ipcRenderer.invoke('engine:restart'),
  /** 引擎状态推送订阅，返回取消订阅函数 */
  onEngineStatus: (cb) => {
    const handler = (_e, status) => cb(status);
    ipcRenderer.on('engine:status', handler);
    return () => ipcRenderer.removeListener('engine:status', handler);
  },
  /** 保存模型供应商密钥（密文落 SecretVault；明文不回渲染层） */
  saveModelKey: (payload) => ipcRenderer.invoke('secret:saveModelKey', payload),
  /** 已保存供应商列表（脱敏） */
  listSavedProviders: () => ipcRenderer.invoke('secret:listProviders'),
  /** 终端（D1-25）：xterm.js 会话经 node-pty/script PTY */
  terminal: {
    create: (opts) => ipcRenderer.invoke('terminal:create', opts),
    write: (p) => ipcRenderer.invoke('terminal:write', p),
    resize: (p) => ipcRenderer.invoke('terminal:resize', p),
    kill: (p) => ipcRenderer.invoke('terminal:kill', p),
    onData: (cb) => {
      const h = (_e, d) => cb(d);
      ipcRenderer.on('terminal:data', h);
      return () => ipcRenderer.removeListener('terminal:data', h);
    },
    onExit: (cb) => {
      const h = (_e, d) => cb(d);
      ipcRenderer.on('terminal:exit', h);
      return () => ipcRenderer.removeListener('terminal:exit', h);
    },
  },
});
