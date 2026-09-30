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
    const data = safeStorage.isEncryptionAvailable()
      ? safeStorage.encryptString(json)
      : Buffer.from(json, 'utf8');
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
