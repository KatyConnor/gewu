# UCSS 行业代码扫描报告 — gewu-platform 框架代码全面审计

| 项 | 值 |
|---|---|
| 报告编号 | UCSS-SCAN-20261012-framework |
| 扫描范围 | gewu-platform 全部框架代码：Java 后端 11 模块（~100k 行）、Electron 桌面端、Web 前端×2、部署/CI/供应链 |
| 模式 mode | ONLINE（api.osv.dev 可达；本机无 SAST 工具，降级纯规则库审查 + OSV 在线查询，见 limitations） |
| 规则集 | UCSS v1.1（D1 安全漏洞 / D2 软件缺陷 / D3 编码规范 / D4 逻辑推理 / D5 供应链配置） |
| 判定 | **门禁 FAIL —— 存在 Critical 未修复项，阻断提交/合并/发布三档门** |
| 统计 | Critical 3 · High 8 · Medium 14 · Low 3（C1×21 / C2×7 / C3 项不计门禁） |

---

## 一、分域统计与覆盖

| 域 | 覆盖 | 结论摘要 |
|---|---|---|
| D1 安全漏洞 | Java 核心四模块源码级深审（348 文件 + 28 关键文件）；Electron 主进程逐行审；desktop-runtime 安全组件源码级复核；Web 前端 XSS/凭据面全扫 | 命中 3 Critical / 8 High；注入五大家族（SQL `${}`/命令/反序列化/XXE/SSTI-SpEL）经全量模式扫描**阴性** |
| D2 软件缺陷 | Java 核心 + Electron + desktop-runtime | 无资源泄漏主路径/无静态 SimpleDateFormat/无空 catch 系统性缺陷；SpEL 表达式均来自注解常量（非用户输入） |
| D3 编码规范 | 抽样 | 未发现系统性高危规范违反（日志经 LogMasking 统一脱敏，正面结论） |
| D4 逻辑推理 | D4-P1 逐写接口授权推理（Share/Orchestration/Sandbox/Message/Skill/ModelConfig/MCP/Admin/DevWorkspace）+ P3 数据流 + P5 fail-open 设计 | 命中 4 条 High 越权 + 2 条 Medium 设计缺陷（fail-open×2、锁定永不解除） |
| D5 供应链配置 | compose×2 / Dockerfile×2 / Jenkinsfile / gitlab-ci / k8s / helm / .env.example / 全库密钥模式扫描 / OSV 在线核查（Maven 15 包 + npm 8 包） | 命中密钥族 Critical + SCA High（Next.js RCE 公告、Tomcat 29 条）+ CI 门架空 |

---

## 二、Findings 明细（按等级排序）

### Critical（3）

**C-1 [C1] SEC-AUTH-01/SEC-CRY-02 JWT 签名密钥公开入库：网关硬编码 + 环境变量死信 + 角色 claims 信任** (CWE-798/321, OWASP A07/A02)
- 位置：`gewu-gateway/src/main/resources/application.yml:43`（无 `${JWT_SECRET:}` 占位符，值直接写死）；同值另见 interface `application.yml:235`、admin-server `application.yml:59`、interface-prod `application.yml:97`
- 证据链：仓库公开值 `gewu-platform-jwt-secret-...-for-hs256` → `JwtAuthFilter.java:30-32`（用此值验签）→ `AdminJwtAuthenticationFilter.java:84-93`（`roleCodes.contains("ADMIN")` 即授全量权限，角色完全取自 token claims）→ 伪造 `{"roles":["ADMIN"]}` 令牌通过网关与管理后端。**旁证**：`docker-compose.prod.yml:175` 与 `deploy/helm/.../config.yaml:32` 注入的 `GEWU_SECURITY_JWT_SECRET` 全库无任何代码消费（死信变量），生产覆盖链路断裂。
- 危害：任何读过仓库的人可离线伪造任意用户/ADMIN JWT，完整接管主应用、网关、管理后端。
- 修复：删除全部默认值 → `secret: ${JWT_SECRET}` 无默认 + @PostConstruct 非空校验（fail-closed）；修正死信变量名或补齐消费链；**必须立即轮换现网密钥**。

**C-2 [C1] SEC-CRY-02 敏感数据加密密钥（SM4）真实值入库且作为生产默认** (CWE-798, OWASP A02)
- 位置：`.env.example:29`（git 跟踪，值与本地 `.env` 真实值相同 `13b4a0ea…f1f`）；`docker-compose.prod.yml:170`（同值作为 compose 默认回退）；另 interface `application.yml:230` 默认 `0123456789abcdef…` 与 admin `application.yml:54` 默认 `13b4a0ea…` **两套服务默认密钥不一致**
- 证据：`ApiKeyCryptoService.java:25-27` 自证这两个值是"仓库中出现过的公开默认密钥"；未设 `GEWU_CRYPTO_API_KEY_SECRET` 时全部 LLM Provider API Key 以公开密钥 SM4-GCM 加密落库（`ModelConfigService.java:130,173`）；prod 下弱值仅 `log.warn` 不阻断（`ApiKeyCryptoService.java:51-53`）。
- 危害：拖库后攻击者可用仓库公开密钥解密全部 LLM API Key。
- 修复：**立即轮换该 SM4 密钥**（视为已泄露）；yml 默认清空 + 启动强校验失败；两服务统一密钥来源。

**C-3 [C1] SEC-AUTH-01 生产编排文件硬编码弱口令族 + 数据库端口对外** (CWE-798/1188, OWASP A07)
- 位置：`docker-compose.prod.yml`（MYSQL_ROOT_PASSWORD 回退 `root123456`：L32/L66；MYSQL_PASSWORD `gewu123456`：L34/L176；POSTGRES_PASSWORD `wenshi123456`：L46；MINIO `minioadmin/minioadmin123`：L93-94/L155-156）+ prod 数据库/缓存/对象存储端口全部 `ports:` 绑定宿主（L31-32 等）；`.env.example` 同值
- 证据：fallback 值即最终值（.env 缺失时）；MySQL 3306/pgvector 5432/Dragonfly 6379/MinIO 9000-9001 在 prod 文件中绑定 `0.0.0.0`；Dragonfly **无任何密码配置**
- 危害：一次配置疏漏 = 弱口令数据库直接暴露网络；未鉴权 Redis 类服务可被任意来源访问。
- 修复：prod compose 移除全部默认回退（缺 .env 即启动失败）；生产端口移除 `ports:` 仅走内部网络；Dragonfly 配置 requirepass。

### High（8）

**H-1 [C1] SEC-AUTH-04 编排图全生命周期横向越权 + Webhook 劫持** (CWE-639/862, OWASP A01, D4-P1)
- `gewu-application/.../orchestration/OrchestrationService.java:721-725,110-133,163,287`：`selectById` 仅判空、无 createdBy 比对、无 @PreAuthorize → 任意认证用户可改/激活/执行/回滚**他人**编排图，`upsertWebhook` 可替换他人 Webhook 凭证后匿名触发。修复：服务层统一 `requireGraphOwner`。

**H-2 [C1] SEC-AUTH-04 沙箱代理接口无属主校验** (CWE-639, OWASP A01)
- `gewu-interface/.../SandboxProxyController.java:41-94`：`listSandboxes` 返回**全平台**沙箱元数据；start/stop/delete 按 ID 无属主比对 → 可枚举并销毁任意用户沙箱。唯一防线是默认值公开的沙箱内部 key（见 C-2 族）。修复：代理层按当前用户过滤/校验。

**H-3 [C1] SEC-AUTH-04 会话消息读/发/搜无成员校验** (CWE-639/862, OWASP A01)
- `gewu-application/.../session/MessageService.java:45-77,80-111,113-119,158-171`：仅判认证不判成员关系；`getMessage(messageId)` 按主键直查 → 任意认证用户可读/搜任意会话完整 AI 对话记录并可注入消息。同文件 `SessionService.checkMembership:389-397` 即正确范式。修复：五方法入口统一接成员校验。

**H-4 [C2] SEC-INJ-04 DevWorkspace 写/传/下载绕过路径净化 → 容器任意路径** (CWE-22, D4-P3)
- `gewu-application/.../workspace/DevWorkspaceService.java:266-281`：write/upload/download 未过 `sanitizeWorkspaceRel`（read 路径已净化）→ `/etc/cron.d/x` 类值原样下发沙箱 Docker cp 通道。是否容器逃逸取决于沙箱服务挂载（待验证）。修复：三路径同 read 强制净化 + 拼 `/workspace/`。

**H-5 [C1] SEC-AUTH-04/SEC-FILE-05 Electron 云模式将全量特权桥暴露给远程页面** (CWE-284, OWASP A01)
- `gewu-desktop/electron/main.js:640-647`（同一窗口挂 preload 加载远程 cloudUrl `main.js:702`）→ `preload.js:8-64` 向远程页暴露：`getEngineInfo`（含引擎 port+token）、`terminal.create/write`（`terminal-manager.js:24,32` 渲染层可控 shell 经 `pty.spawn` 任意进程）、`saveLaunchConfig` 等 → 恶意/被劫持的云端页面可拿引擎令牌直调 `http://127.0.0.1:<port>/api/v1/*`（CORS 全开放见 M-8，`POST /projects` 可绑定任意绝对路径目录为工作区）→ 本地任意文件写 + shell 工具执行。
- 修复：远程内容使用独立 session/无特权 preload 的窗口；特权 IPC 仅保留窗口控制；引擎令牌不再经 preload 交渲染层（改主进程代理数据面）。

**H-6 [C1] SEC-AUTH-04 Electron `creds:get` 无源绑定，远程页可读任意站点保存的凭据** (CWE-522, OWASP A07)
- `main.js:891-896`（`creds:get` 接受任意 siteKey 字符串）+ `secret-vault.js:66-78`（解密返回明文）→ 恶意云页面 `window.gewu.getWebCredential('https://真实平台')` 即得明文账密；`creds:save` 亦可向任意 origin 植入凭据（自动填充钓鱼）。修复：主进程按 `webContents` 实际 origin 强制比对 siteKey。

**H-7 [C2] D5/SCA Next.js 14.2.35 —— 23 条已知公告含未认证 RCE，且 14.x 已 EOL** (CWE-1104)
- `gewu-web/package.json`（`next: 14.2.35` 钉死）：GHSA-2xp9-vwfh-vxw4（图像优化 API 未认证 RCE，AVIF 场景，修复于 16.3.3）；CVE-2026-44573（Middleware/Proxy 绕过 C:H，修复 16.2.5）；CVE-2026-64646~49 等。无 14.x 修复版本。
- 修复：迁移 Next 15/16（含代码适配）；短期在边缘禁用 `/_next/image` AVIF 入口。

**H-8 [C2] SEC-AUTH-01 沙箱内部 API key 默认值公开** (CWE-798)
- interface `application.yml:281` 默认 `gewu-dev-internal-key-change-me`：泄露即可绕过 interface 直连沙箱服务 8082 执行容器命令。修复：默认清空 + fail-closed（与 C-2 同批治理）。

### Medium（14）

| # | UCSS 编号 | 标题 | 位置 | 置信 |
|---|---|---|---|---|
| M-1 | SEC-FILE-03 | Swagger/OpenAPI 生产未鉴权暴露（permitAll + 未关闭） | interface `SecurityConfig.java:75-79` + `application.yml:341-346`；admin `AdminSecurityConfig.java:42-45` | C1 |
| M-2 | SEC-AUTH-05 | 账户 5 次失败**永久锁定**（lockedUntil 零读取，LoginSecurityService 整套死代码） | `AuthService.java:54-67` | C1 |
| M-3 | SEC-AUTH-06 | 登录用户名枚举（USER_NOT_FOUND vs PASSWORD_INCORRECT 可区分） | `AuthService.java:51-70` | C1 |
| M-4 | SEC-AUTH-05 | Access Token 实际 20h（yml 72000000 注释写 30min；expiresIn 用常量 30min）+ 角色 claims 不随库刷新 | interface `application.yml:236`、`JwtAuthenticationFilter.java:67-88` | C1 |
| M-5 | SEC-FILE-03 | 管理口 9081 actuator `show-details: always` 且整口无认证 | interface `application.yml:299-316`、`SecurityConfig.java:83-84` | C1 |
| M-6 | SEC-AUTH-02 | 密码哈希自实现 SM3 加盐 1 万轮（非 HMAC-PBKDF 构造），低于 KDF 基线 | `PasswordHasher.java:12,34-39` | C2 |
| M-7 | SEC-AUTH-03 | 数据权限拦截器 fail-open（解析失败放行原 SQL）且全库仅 1 处启用 | `DataPermissionInnerInterceptor.java:80-83,175-178` | C1 |
| M-8 | SEC-FILE-05 | 桌面引擎绑定 0.0.0.0（无 `server.address`，配置注释自认仅 127.0.0.1）+ CORS `allowedOriginPatterns("*")` | desktop-runtime `application.yml:1-2`、`EngineSecurityConfig.java:29` | C2 |
| M-9 | SEC-INJ-10 | http:// 云地址可保存 + 凭据管理器自动填充注入 → MITM 窃取登录凭据 | `main.js:740,794-863` | C2 |
| M-10 | SEC-AUTH-04 | terminal:create 渲染层可控 shell/cwd 无白名单 | `terminal-manager.js:21-24,32` | C1 |
| M-11 | D5/CI | 安全扫描门被架空：Jenkins `trivy ... \|\| true`；GitLab `allow_failure: true` | `Jenkinsfile` Security Scan、`.gitlab-ci.yml` security-scan | C1 |
| M-12 | D5/配置 | 生产镜像 root 运行：compose 构建 `Dockerfile`（无 USER），`Dockerfile.secure` 未被引用；k8s deployment 无 securityContext | `docker-compose.prod.yml:140`、`deploy/k8s/deployment.yaml` | C1 |
| M-13 | D5/SCA | Java 依赖族过期：Tomcat 10.1.36（29 条：会话固定 CVE-2025-55668 C:H、走私 CVE-2026-24880、约束失效 CVE-2026-43515）；jackson 2.15.4×11；spring-security 6.2.4×3；spring-web 6.1.6×3（RFD CVE-2025-41234）；netty 4.1.109×2；bc 1.77×9；poi 5.2.5；minio 8.5.10；Boot 3.2.5 已 EOL（CVE-2025-22235） | 根 `pom.xml:43-47` | C1 |
| M-14 | D5/SCA | vite 锁死 5.4.0（15 条 dev-server 公告族，dev-only）+ gewu-web/admin-web JWT 双 token 存 localStorage（XSS→会话劫持面） | `pnpm-lock.yaml`、`gewu-web/src/lib/token.ts:18-19`、`gewu-admin-web/src/lib/token.ts:18-19` | C1 |

### Low（3）

| # | 编号 | 标题 | 位置 |
|---|---|---|---|
| L-1 | SEC-FILE-01 | DevWorkspace 上传无类型/大小应用层校验（对比 WorkspaceService 有黑名单） | `DevWorkspaceService.java:272-275` |
| L-2 | SEC-CRY-04 | Electron safeStorage 不可用时密钥明文落盘（注释自认的降级路径，无告警） | `secret-vault.js:30-32,55-58` |
| L-3 | D5/配置 | prod JDBC `useSSL=false` + `latest` 镜像漂移（minio/searxng/dragonfly-prod） | `docker-compose.prod.yml:147,158` |

### C3 待复核（不计门禁）

- gateway `X-User-Id` 转发采用 `mutate().header()` 追加语义，若下游存在直连面可头注入（需确认下游读取方式与生产拓扑）；
- 平台侧 `sanitizeRelative`（SessionFileWorkspaceService.java:641）无 realpath 符号链接复检，弱于桌面 PathGuard——当前读写均经容器侧 `sandboxClient`，逃逸收敛于容器内，需验证是否存在宿主侧直读路径；
- SsrfValidator 理论 TOCTOU DNS rebinding；
- 沙箱 `ln -s` 不在黑名单（容器内影响）。

---

## 三、门禁判定

| 门档 | 阈值 | 本次 | 判定 |
|---|---|---|---|
| 提交门 | Critical=0 且 High≤2 | Critical=3 | **FAIL** |
| 合并门 | Critical=0 且 High≤3 | Critical=3 | **FAIL** |
| 发布门 | Critical=0 且 High=0 | Critical=3 | **FAIL** |

**正面结论（同具证据效力）**：注入五大家族全量阴性（MyBatis 全 `#{}`、无动态执行引擎、无反序列化入口）；SSRF 白名单默认拒绝 + 重定向复检；SM4-GCM 随机 IV 无 ECB；桌面端 PathGuard/LocalFileService 全写路径沙箱校验扎实；Web 前端未发现可达 XSS（Prism 输出自带转义）；k8s Secret 为占位符未泄露；`.env` 从未进入 git 历史。

## 四、修复优先级 Top 10

1. **[P0 今日]** C-1 JWT：轮换现网密钥 + 删除三处默认值改 fail-closed + 修死信变量 `GEWU_SECURITY_JWT_SECRET`（compose/helm 注入名与代码消费名对齐）。
2. **[P0 今日]** C-2 SM4：视为已泄露并轮换；统一两服务密钥；prod 弱值从 warn 改启动失败。
3. **[P0 本周]** C-3 compose：移除全部弱口令 fallback；生产移除 DB/Redis/MinIO 端口映射；Dragonfly 加密码。
4. **[P1 本周]** H-1/2/3 越权三件套：OrchestrationService/SandboxProxyController/MessageService 补属主与成员校验（有同库正确范式可照抄）。
5. **[P1 本周]** H-4 DevWorkspace 三路径补净化；H-8 沙箱内部 key 同批 fail-closed。
6. **[P1 本周]** H-5/H-6 Electron：远程云页面改独立无特权 session；`creds:get/save` 按 webContents origin 绑定；引擎令牌不经 preload 下发。
7. **[P2]** M-1/5 Swagger 与 actuator 生产关闭/收紧；M-4 token 有效期修正 + 角色以 DB 为准；M-2/3 锁定自动解除 + 统一登录错误文案。
8. **[P2]** M-13/M-14 SCA：Spring Boot 3.2.5→3.4.x（顺带收敛 tomcat/jackson/security/netty 版本族）；Next.js 14→16 迁移立项；vite bump。
9. **[P2]** M-6 密码哈希迁 PBKDF2WithHmacSM3 ≥100k 轮（带存量哈希迁移策略）；M-7 DataPermission fail-closed + 批量启用。
10. **[P3]** M-11/12 CI 门去 `|| true`、生产镜像切 `Dockerfile.secure` + k8s securityContext；L 系列小项。

## 五、扫描限制（可信度自评）

- 未运行工具：gitleaks/semgrep/trivy 本机缺失，D1/D2 依赖规则库人工审查 + 40+ 组模式 grep（全量覆盖但非引擎级数据流分析）；
- 引擎类模块（gewu-agent-engine/veloflow-engine/gewu-infrastructure/gewu-sandbox/gewu-domain/gewu-common）为主线程模式级快扫（SpEL/MCP/反序列化/命令执行阴性），非源码级逐行审——建议下轮补深扫；
- SCA 基于 OSV 报告的版本区间命中，个别公告为条件触发（已标注 C2/C3 与条件）；
- 沙箱容器逃逸类结论（H-4）依赖沙箱服务侧挂载方式（范围外），已给出验证步骤；
- vendor 产物（gewu-desktop/public Monaco 24MB）与 lockfile 全量传递依赖未逐一人工审。

---

## 六、修复状态回填（AEGIS 批 FIX-20261012，2026-10-12）

> 单据：.aegis/orders/（INTAKE + RC/FP/VR 合并批）；GATE：s-20261012-081241（用户授权"按计划优先级启动修复"）；
> 检查点：pre-aegis-fix-p0（protect，可整体回退）

| 项 | 状态 | 处置 |
|---|---|---|
| C-1 JWT 密钥公开入库+死信变量 | **已修复** | 4 yml 去 `${JWT_SECRET}` 默认（fail-closed）+ compose/helm 变量名对齐 + helm required 强制 |
| C-2 SM4 真实密钥入库 | **代码侧已修复**（轮换待运维） | .env.example/compose 真实值→占位/去回退；3 服务默认清空；ApiKeyCryptoService prod 命中公开默认 warn→throw；**现网密钥视为已泄露须运维轮换** |
| C-3 生产弱口令族+端口 | **已修复** | 12 处回退清零 + DB/Redis/MinIO prod 端口移除 + Dragonfly requirepass + Spring 侧 password 对齐 |
| H-1 编排越权 | **已修复** | requireGraphOwner 统一守卫 ×6 写入口（MANUAL/AGENT_TOOL 执行、update/activate/delete/upsertWebhook/getWebhook）；系统通道豁免 |
| H-2 沙箱代理越权 | **DEFER（知情披露）** | Sandbox 实体无归属字段，需跨服务改造（实体+迁移+创建链贯通），不做伪修复 |
| H-3 消息越权 | **已修复** | 4 方法成员校守卫（send/list/get/search） |
| H-4 DevWorkspace 路径绕过 | **已修复（纵深）** | 客户端三路径净化；服务端 validatePath 为既有第二道防线（`..`/绝对路径/强制 /workspace 前缀） |
| H-5/H-6 Electron 特权桥/凭据跨源 | **已修复** | IPC 通道按 sender 来源分级（远程页仅窗口控制）+ creds 源绑定（读不泄露存在性）+ 引擎令牌不再达远程页 |
| H-8 沙箱内部 key | **已修复（prod 已闭环）** | prod 双侧 fail-fast 既有；dev 双端对齐默认保留（去除破坏本地直启），登记残留 |
| M-1/M-5 Swagger+actuator | **已修复（prod）** | interface-prod swagger 关闭 + show-details never |
| M-2/M-3 锁定/枚举 | **已修复** | lockedUntil 到期自动复位 + 登录统一"用户名或密码错误" |
| M-4 token 20h | **已修复** | yml 修正 1800000 |
| M-8 引擎 0.0.0.0 | **已修复** | server.address: 127.0.0.1 |
| M-9/M-10 http 云地址/shell 白名单 | **已修复** | 主进程告警 + 终端 shell 白名单 |
| M-11/M-12 CI 门+镜像 root | **已修复** | `\|\| true` 移除 + allow_failure:false + Dockerfile.secure 切换 + k8s securityContext |
| M-6/M-7/M-13/M-14 | **DEFER** | 密码哈希迁移（需存量策略）/DataPermission fail-closed（需兼容审计）/Boot 3.4.x、Next 16、vite 升级立项 |

**门禁状态**：三档门仍 **FAIL→待复扫确认**——代码侧 Critical/High 已收敛，但 ①现网密钥轮换（运维）②H-2 跨服务改造 ③动态验证（本机无构建工具，静态等价验证通过）未完成；复扫入口：UCSS 报告 rescan 判据。**验证限制**：mvn/java/node 缺失，编译与运行时冒烟待具备构建环境后由角色 06 按 UTSS 独立准出。
