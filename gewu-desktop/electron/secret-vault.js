/**
 * SecretVault（D1-4）—— 密钥加密存储（Electron safeStorage → OS keychain 体系）。
 * 存储内容：模型供应商 API Key（明文仅内存）；文件为密文（加密不可用时降级明文并保留升级路径）。
 * 渲染层永不接触明文（§11.1 威胁模型）。
 */
const { safeStorage } = require('electron');
const fs = require('fs');
const path = require('path');

class SecretVault {
  constructor(userDataPath) {
    this.file = path.join(userDataPath, 'secrets.bin');
  }

  load() {
    try {
      if (!fs.existsSync(this.file)) return {};
      const buf = fs.readFileSync(this.file);
      const json = safeStorage.isEncryptionAvailable()
        ? safeStorage.decryptString(buf)
        : buf.toString('utf8');
      return JSON.parse(json);
    } catch {
      return {};
    }
  }

  save(obj) {
    const json = JSON.stringify(obj);
    const encrypted = safeStorage.isEncryptionAvailable();
    if (!encrypted && !this._plaintextWarned) {
      // L-2（UCSS SEC-CRY-04）：加密不可用降级明文时显式告警，提示配置系统钥匙环
      console.warn('[secret-vault] safeStorage 不可用，密钥将以明文落盘（' + this.file + '）——请安装并解锁系统钥匙环');
      this._plaintextWarned = true;
    }
    const data = encrypted ? safeStorage.encryptString(json) : Buffer.from(json, 'utf8');
    fs.writeFileSync(this.file, data);
  }

  /** 保存模型 Key（providerCode → apiKey + 元数据） */
  saveModelKey(providerCode, apiKey, meta) {
    const all = this.load();
    all.modelKeys = all.modelKeys || {};
    all.providerMeta = all.providerMeta || {};
    all.modelKeys[providerCode] = apiKey;
    all.providerMeta[providerCode] = meta || {};
    this.save(all);
  }

  /** 引擎引导用：{providerCode: apiKey} */
  getModelKeys() {
    return this.load().modelKeys || {};
  }

  /** 平台登录凭据（云模式「记住我」）：safeStorage 加密落盘,按平台地址隔离 */
  saveWebCredential(siteKey, username, password) {
    const all = this.load();
    all.webCredentials = all.webCredentials || {};
    const enc = safeStorage.isEncryptionAvailable();
    all.webCredentials[siteKey] = {
      username,
      password: enc ? safeStorage.encryptString(password).toString('base64') : password,
      encrypted: enc,
      savedAt: Date.now(),
    };
    this.save(all);
  }

  /** 读取平台登录凭据;无记录返回 null */
  getWebCredential(siteKey) {
    const all = this.load();
    const c = all.webCredentials?.[siteKey];
    if (!c) return null;
    try {
      return {
        username: c.username,
        password: c.encrypted ? safeStorage.decryptString(Buffer.from(c.password, 'base64')) : c.password,
      };
    } catch {
      return null;
    }
  }

  /** 设置页列表（脱敏：不含明文） */
  listProviders() {
    const all = this.load();
    return Object.entries(all.modelKeys || {}).map(([code]) => ({
      code,
      hasKey: true,
      ...(all.providerMeta?.[code] || {}),
    }));
  }
}

module.exports = { SecretVault };
