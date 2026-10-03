# 打包分发批完成报告：gewu-desktop（Electron 44 + 安装包首产出）

> **文档编号**：DONE-DESKTOP-P3-B2 | **日期**：2026-10-03 | **状态**：✅ 批次完成（自动化验证通过，待真机安装验证）
> **基线**：实施计划 D3-10（打包配置完善）前置批 | **提交**：`ddfd4df`（Electron 44）+ `9c88694`（打包批）

---

## 一、交付内容

| 项 | 结果 |
|----|------|
| **Electron 31.7.7 → 44.5.1** | Chromium 126→**152.0.7977.130**，Node 20→24.21；typecheck/vite 全绿 + 视觉回归 9 捕获点零差异 |
| **数据目录显式化** | 全模式传 `--gewu.desktop.data-dir=userData/engine-data`（CLI），消除 env/yml 链路依赖，打包态任意 cwd 恒定 |
| **node-pty 原生构建** | 1.0.0→1.1.0（旧版 C++ 与新 V8 头不兼容）+ @electron/rebuild 对 Electron ABI 编译，Electron 主进程 PTYOK 实测；终端后端自动升级原生 PTY（消除 script 回退首包 ~3s 延迟） |
| **安装包首产出** | AppImage 222MB / tar.gz 56MB（Linux x64）；resources 含 `runtime/desktop-runtime.jar`（52MB）+ **jlink JRE（61MB，14 模块集）** + asar + node-pty unpacked |
| **捆绑 JRE 验证** | jlink 裁剪（含 java.desktop——缺它 NoClassDefFoundError PropertyEditorSupport）；打包产物启动 → **引擎 ready（捆绑 JRE）**+ 9 捕获点全过 |

## 二、过程取证与排障记录（沉淀给后续平台化打包）

1. **npmmirror 包可达但 Electron 二进制下载挂起** → curl 直取 CDN 解压 + `path.txt`（无换行）；
2. **pnpm 11 `allowBuilds` 占位符**（`set this to true or false` 字面量为交互模板）→ 需填 true/false，否则构建被静默跳过且 path.txt 现象诡异；
3. **node-pty 1.0.0 对 Electron 44 编译失败**（V8 头 API 变更）→ 升 1.1.0 解决；
4. **builder 自动 npm rebuild 失败**（node-pty 的 `tsc -b` 构建）→ `--config.npmRebuild=false` 绕过（已由 electron-rebuild 编好）；正规 rebuild 移交 Jenkins 批 D3-13；
5. **CLI target 参数带空格引号**被识别为单个 target 名 → 分参数传递；
6. **jlink 模块集**：Spring Boot 最小集需含 java.desktop（java.beans）。

## 三、验证记录

| 验证项 | 结果 |
|--------|------|
| Electron 44 视觉回归（9 捕获点，Chromium 152） | ✅ |
| node-pty 在 Electron 主进程 spawn/read | ✅ PTYOK |
| jlink JRE 启动引擎（health + Started 22.6s / 0 ERROR） | ✅ |
| **打包产物启动 → 引擎 ready（捆绑 JRE + resources JAR）** | ✅ |
| 打包产物视觉捕获 | ✅ |

## 四、遗留与移交

| 项 | 去向 |
|----|------|
| 真机安装验证（AppImage 双击/deb 安装、快捷方式、卸载残留） | 用户验证 |
| chrome-sandbox 权限（打包产物需 --no-sandbox 或安装态 SUID；deb postinst 处理） | D3-10 收尾 |
| 正规 native rebuild 流水线化（替代手工 electron-rebuild） | D3-13 Jenkins |
| 自动更新（D3-12）、签名（D3-11）、arm64 信创（D3-15）、私有化包（D3-16） | 计划内后续批次 |
| 应用图标（当前默认图标） | D3-10 收尾 |
| 协同域 D2-11+ | 待平台 N1/N2 排期 |

## 五、结论

**D3-10 打包配置核心达成**：安装包从零到可运行（引擎捆绑 JRE 自包含、数据目录恒定、原生 PTY 就位），
桌面端首次具备可分发的实物形态。剩余 D3-10 收尾项（图标/sandbox 权限/多平台 target）与 D3-11~18
按计划推进；**Electron 44.5.1 为当前安全补丁支持线**，后续跟随升级建议纳入 Jenkins 批例行化。
