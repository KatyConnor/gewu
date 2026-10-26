# UCSS 完整检查清单（references/checklists.md）

> 本文件是 UCSS v1.0 的规则明细库，供扫描执行时按需查阅。结构：§1 D1 安全漏洞完整清单 → §2 D2 缺陷清单 → §3 D3 规范清单 → §4 D5 供应链清单 → §5 D4 推理协议 → §6 场景专项 → §7 密钥扫描模式 → §8 CWE/OWASP 完整映射。

## §1 D1 安全漏洞完整清单

### 1.1 输入与注入（OWASP A03）

| UCSS 编号 | 名称 | CWE | 等级 | 判定条件 | 常见 Sink |
|---|---|---|---|---|---|
| SEC-INJ-01 | SQL 注入 | CWE-89 | Critical | 用户输入进入 SQL 且非参数化 | `Statement.execute`、`cursor.execute(f"...")`、MyBatis `${}`、`fmt.Sprintf`+`Query`、`mysqli_query` 拼接、knex `.raw()` 拼接 |
| SEC-INJ-02 | 命令注入 | CWE-78 | Critical | 用户输入进入 shell 命令且未白名单化 | `Runtime.exec`、`subprocess(shell=True)`、`os.system`、`child_process.exec`、`exec.Command("sh","-c",拼接)`、反引号 |
| SEC-INJ-03 | XSS（反射/存储/DOM） | CWE-79 | High/Critical | 用户数据未编码进入 HTML/属性/JS 上下文 | `dangerouslySetInnerHTML`、`v-html`、`innerHTML=`、`document.write`、`th:utext`、`<%=`、jQuery `.html()` |
| SEC-INJ-04 | 路径穿越 | CWE-22 | High | 用户输入拼路径且未归一化白名单校验 | `new File(base+user)`、`open(user)`、`fs.readFile(user)`；ZipSlip：解压 entry 名含 `../`（CWE-409） |
| SEC-INJ-05 | XXE | CWE-611 | High | XML 解析未禁外部实体/DOCTYPE | `DocumentBuilderFactory` 默认、`SAXReader`、libxml2、`lxml.etree`、`xml.etree`(旧)、PHP `simplexml_load_file` 外部加载 |
| SEC-INJ-06 | SSRF | CWE-918 | High | 用户可控 URL 服务端请求且未校验目标协议/网段 | `requests.get(user_url)`、`HttpClient.send(user)`、`curl_exec`、`http.Get(user)`；注意 DNS 重绑定与重定向 |
| SEC-INJ-07 | 模板注入 SSTI | CWE-1336 | Critical | 用户输入作为模板内容渲染 | `render_template_string(u)`、`Template(u).render`、Velocity/Freemarker 用户输入模板、Jinja `from_string` |
| SEC-INJ-08 | 不安全反序列化 | CWE-502 | Critical | 反序列化不可信数据 | `ObjectInputStream.readObject`、`pickle.loads`、`yaml.load`(非 safe)、`BinaryFormatter`、Fastjson autoType、`marshal.loads` |
| SEC-INJ-09 | 代码/表达式注入 | CWE-94/95 | Critical | 用户输入进入动态执行 | `eval`、`exec`、`Function()`、`setTimeout(string)`、Groovy `evaluate`、`ELProcessor.eval`、`new Function` |
| SEC-INJ-10 | LDAP/XPath/NoSQL/日志注入 | CWE-90/643/1236/117 | Medium~High | 拼接查询表达式或日志 | `ldap_search(filter+u)`、`log.info(u)`（CRLF）、MongoDB `$where` 拼接 |

### 1.2 认证与会话（OWASP A07）

| UCSS 编号 | 名称 | CWE | 等级 | 判定条件 |
|---|---|---|---|---|
| SEC-AUTH-01 | 硬编码凭证 | CWE-798 | Critical | 密码/AK/SK/token/API key 出现在源码或配置（联动 §7 密钥模式） |
| SEC-AUTH-02 | 弱密码存储 | CWE-916/756 | High | 密码用 MD5/SHA1/无 salt 存储；应使用 bcrypt/scrypt/Argon2/PBKDF2(≥10万轮) |
| SEC-AUTH-03 | 缺失认证 | CWE-306 | High | 敏感接口无鉴权中间件/注解（Spring 无 Security 配置、Express 无 auth 中间件、FastAPI 无 Depends） |
| SEC-AUTH-04 | 越权访问 | CWE-639/862/863 | High | 以 ID 查改数据无属主校验；角色校验仅在前端；管理接口未校验角色 |
| SEC-AUTH-05 | 会话管理缺陷 | CWE-613/384/1004 | Medium~High | 无过期、登出未失效、JWT `alg:none`/不校验签名/HS-RS 混淆、Cookie 缺 HttpOnly/Secure/SameSite |
| SEC-AUTH-06 | 无防爆破 | CWE-307 | Medium | 登录/验证码/找回密码接口无限流/锁定/验证码 |
| SEC-AUTH-07 | 不安全随机数 | CWE-338/330 | High | `random`/`Math.random`/`rand()`/`Math.floor(Math.random)` 用于 token、验证码、密钥、会话 ID；应使用 `secrets`/`SecureRandom`/`crypto.getRandomValues`/`crypto/rand` |
| SEC-AUTH-08 | JWT 误用 | CWE-347 | High | 不验证签名、接受 `none` 算法、payload 存敏感数据明文、不校验 exp/aud/iss |

### 1.3 加密与数据保护（OWASP A02）

| UCSS 编号 | 名称 | CWE | 等级 | 判定条件 |
|---|---|---|---|---|
| SEC-CRY-01 | 弱加密算法 | CWE-327/326 | High | DES/3DES/RC4/Blowfish、MD5/SHA1（安全用途）、RSA<2048、ECC<256、TLS<1.2 |
| SEC-CRY-02 | 硬编码密钥/IV | CWE-321/259/798 | Critical | AES key/IV 写死源码、数据库密码写入配置未走密管 |
| SEC-CRY-03 | 敏感数据暴露 | CWE-311/312/532/359 | High | PII/银行卡明文入库、HTTP 明文传输、日志打印密码/身份证/token、错误信息回显内部细节 |
| SEC-CRY-04 | 密码学误用 | CWE-327/330/329 | High | ECB 模式、GCM nonce 复用、静态 IV、无 AEAD 的 CBC 无 HMAC（padding oracle 面） |
| SEC-CRY-05 | CSRF 缺失 | CWE-352 | Medium | 状态变更接口无 CSRF Token/SameSite/同源校验之一 |

### 1.4 文件、资源与配置（OWASP A05）

| UCSS 编号 | 名称 | CWE | 等级 | 判定条件 |
|---|---|---|---|---|
| SEC-FILE-01 | 文件上传不安全 | CWE-434 | Critical | 无类型/魔数/大小校验、文件名未净化、上传目录可执行、返回可预测 WebShell 路径 |
| SEC-FILE-02 | 目录遍历暴露 | CWE-548/200 | Medium | 生产目录列表开启、静态资源映射泄露 |
| SEC-FILE-03 | 信息泄露 | CWE-200/209/538 | Medium~High | .git/.env/备份可访问、堆栈直接返回、Swagger/Actuator/Druid 生产暴露、注释泄露内网拓扑 |
| SEC-FILE-04 | CORS 误配 | CWE-942 | Medium | `*` + credentials、Origin 反射无白名单、`Access-Control-Allow-Origin` 从请求头直取 |
| SEC-FILE-05 | 安全头缺失 | CWE-693 | Low~Medium | 缺 CSP/X-Content-Type-Options/X-Frame-Options/HSTS/Referrer-Policy |
| SEC-FILE-06 | 开放重定向 | CWE-601 | Medium | `redirect(request.args["url"])` 未校验目标域名 |
| SEC-FILE-07 | 竞态条件 | CWE-367/362 | Medium~High | 检查与使用不一致（TOCTOU）、非原子读改写共享资源 |

## §2 D2 软件缺陷清单

| UCSS 编号 | 名称 | CWE | 等级 | 要点 |
|---|---|---|---|---|
| DEF-NPE-01 | 空指针解引用 | CWE-476 | High | 可空返回值/入参未判空；链式调用中间可为 null |
| DEF-RES-01 | 资源泄漏 | CWE-404/772 | High | 流/连接/锁未用 try-with-resources/with/defer/finally 释放 |
| DEF-CON-01 | 并发缺陷 | CWE-362/366/567/820 | High | 无锁共享可变状态、检查后行动竞态、锁序不一致死锁、非线程安全类用于并发 |
| DEF-NUM-01 | 数值缺陷 | CWE-190/191/192/369 | Medium~High | 整数溢出、符号回绕、除零、浮点等值比较、隐式窄化 |
| DEF-MEM-01 | 内存缺陷(C/C++) | CWE-415/416/476/787/125/401/590/457 | High~Critical | UAF、双重释放、堆/栈溢出、内存泄漏、未初始化读取 |
| DEF-ARR-01 | 数组越界 | CWE-787/125/129 | High | 索引未校验、`length-1` 边界、负索引 |
| DEF-ERR-01 | 异常处理缺陷 | CWE-703/755 | Medium | 空 catch、catch 过宽、finally return 吞异常、递归无终止保护 |
| DEF-LOG-01 | 逻辑边界 | CWE-367/197/843 | Medium | TOCTOU、时区/夏令时错误、类型混淆、恒真恒假 |
| DEF-UI-01 | 前端状态缺陷 | — | Medium | 卸载后 setState、未清理定时器/监听器、Promise rejection 未捕获、竞态请求覆盖 |
| DEF-DB-01 | 数据一致性 | — | High | 多表写无事务、长事务、N+1 查询（性能，可降 Info） |

## §3 D3 编码规范清单

| UCSS 编号 | 规则 | 基线阈值 |
|---|---|---|
| STY-CPX-01/02 | 圈复杂度/认知复杂度 | ≤ 15 |
| STY-CPX-03 | 函数长度 | 软限 80 行 / 硬限 200 行 |
| STY-CPX-04 | 文件长度 | ≤ 1000 行 |
| STY-CPX-05 | 函数参数 | ≤ 5 |
| STY-CPX-06 | 嵌套深度 | ≤ 4 层 |
| STY-DUP-01 | 重复代码 | ≥100 token 重复块占比 ≤ 3% |
| STY-NAM-01 | 命名 | 语言社区标准；禁单字母（循环变量除外）；禁拼音混杂（中文团队可用 pinyin 约定需一致） |
| STY-DOC-01 | 文档 | 导出符号/公共 API 必须有文档注释 |
| STY-SEC-01 | 死代码 | 未用变量/导入/函数、注释掉的代码块 |
| STY-TOD-01 | TODO 管理 | TODO/FIXME 必须带负责人或工单号 |
| STY-FMT-01 | 格式 | 官方 Formatter：gofmt/black/prettier/clang-format/rustfmt |

## §4 D5 供应链与配置清单

| UCSS 编号 | 名称 | 等级 | 判定 |
|---|---|---|---|
| SUP-DEP-01 | 漏洞依赖 | Critical~Low | CVE/GHSA 命中：CVSS≥9→Critical；7~8.9→High；4~6.9→Medium；<4→Low。查直接+传递依赖 |
| SUP-DEP-02 | 停更/撤包 | Medium | 依赖已归档/unpublish/维护停滞>2 年且存在已知风险面 |
| SUP-DEP-03 | 许可证风险 | Info~Medium | GPL/AGPL/LGPL 与商业闭源冲突需登记审批 |
| SUP-DEP-04 | 锁文件缺失 | Medium | 应用项目无 lock 文件（package-lock/poetry.lock/go.sum 已内置等），不可复现构建 |
| SUP-SEC-01 | 密钥泄漏 | Critical | 命中 §7 任一模式；含 .env 入库、历史提交 |
| SUP-CFG-01 | 配置不安全 | High | DEBUG=True 生产、默认口令 admin/root、面板未鉴权、明文协议 |
| SUP-CFG-02 | 容器/IaC | High | root 运行、privileged、hostPID、明文 Secret env、0.0.0.0/0 高危端口 |
| SUP-CFG-03 | 构建脚本风险 | Medium | `curl|bash`、floating tag、postinstall 钩子、未校验的下载 |
| SUP-SBOM-01 | SBOM 缺失 | 门禁项 | 发布产物未生成 SPDX/CycloneDX SBOM |

## §5 D4 逻辑推理协议（详细版）

### P1 授权推理（→ CWE-639/862/863）
```
对每个 POST/PUT/DELETE 与敏感 GET：
Q1 资源属主：代码是否将当前用户 ID 与资源属主比对？（查 ORM 过滤条件）
Q2 服务端强制：权限校验在服务端还是仅前端路由守卫？
Q3 可达性：该接口在鉴权白名单/匿名列表中吗？
→ 任一为否/不确定：报告 High（写操作）/ Medium（读操作），附推理链
```

### P2 状态与时序（→ CWE-362/472）
```
枚举并发场景：同请求并发重放 / 不同账户同时操作同一资源
检查：幂等键？数据库唯一约束？分布式锁？乐观锁版本号？
金额扣减/库存/票券类业务缺任一防护 → High
```

### P3 数据流完整性（→ CWE-472/348）
```
追踪：金额/数量/单价/角色/折扣/状态字段
来源是客户端请求体/隐藏字段/可改参数？→ 服务端必须重算
中间人可改（非 TLS、内部信任）？→ 记录 Medium
```

### P4 业务规则对抗
```
攻击者视角推演清单：
- 限流绕过：改 userId/分页大小/大小写/Unicode 归一化/IP 伪造头
- 校验绕过：边界值 0/负数/超长/嵌套对象/原型污染(__proto__)
- 套利：优惠券叠加、并发提现、整数拆分
发现可行链 → Medium 起步，可组合到敏感操作 → High
```

### P5 设计缺陷（→ OWASP A04）
```
- 信任边界：客户端校验当服务端校验？内网服务默认可信？
- 默认策略：fail-open 还是 fail-closed？
- 审计缺失：敏感操作无日志（CWE-778）→ Low/Medium
- 密码恢复逻辑可预测？（安全问题答案可枚举）
```

**输出要求**：每条 D4 finding 附 `reasoning` 字段：`证据行号 → 推导步骤 1 → 步骤 2 → 结论`。

## §6 场景专项必检项

### 前端 Web（React/Vue/Angular）
- XSS 三大口：`dangerouslySetInnerHTML` / `v-html` / `bypassSecurityTrustHtml`（SEC-INJ-03）
- `href`/`src` 注入 `javascript:` 协议；`postMessage` 无 origin 校验
- Token 存 localStorage（XSS 可窃）→ 建议 HttpOnly Cookie
- 生产 sourcemap 泄露源码；`.env` 前缀 `VITE_`/`NEXT_PUBLIC_` 泄密
- 依赖混淆：内网包名未 scope 化、registry 混用
- Angular：`trustAsHtml`、模板注入；Vue：`:href` 动态绑定

### 小程序
- appSecret 硬编码、code2session 在前端调用
- 敏感数据明文存 storage；web-view 业务域名白名单过宽

### Android
- `android:exported="true"` 无权限保护、Intent 重定向
- WebView：`setJavaScriptEnabled(true)` + `addJavascriptInterface`、`setAllowFileAccess`、`loadUrl(user)`
- 日志打印敏感数据；`allowBackup="true"`；证书校验绕过（`checkServerTrusted` 空实现，CWE-295）

### iOS
- ATS 关闭（`NSAllowsArbitraryLoads`）；Keychain 未用（敏感信息存 UserDefaults）
- URL Scheme 无校验处理外部调用；剪贴板敏感数据

### 后端 API
- OWASP API Top10：BOLA(API1)、BFLA(API5)、批量赋值（mass assignment，API3）——`Model.objects.create(**request.data)` 类模式
- 分页无上限（资源耗尽）、无限流、错误响应泄露堆栈
- GraphQL：introspection 生产开放、无查询深度限制、resolver 级越权

### 微服务/云原生
- K8s：privileged、hostNetwork/hostPath、`capabilities: SYS_ADMIN`、ServiceAccount 挂载默认 token、明文 Secret
- Docker：以 root 运行、基础镜像 latest/未固定 digest、`ADD url` 下载
- Terraform：0.0.0.0/0 放行、明文 provider 密钥、RDS 公网

### AI 应用代码
- Prompt 注入：用户输入拼进 system prompt / 未隔离指令与数据
- 模型输出未净化直接 `eval`/渲染 HTML/执行 SQL
- RAG：检索权限未按用户隔离（数据越权）、向量库注入
- Function calling：工具参数未校验直接执行

## §7 密钥/凭证扫描模式（正则基线）

| 类型 | 模式示例 |
|---|---|
| AWS AK/SK | `AKIA[0-9A-Z]{16}`、`(?i)aws.{0,20}secret.{0,20}['\"][A-Za-z0-9/+=]{40}` |
| 私钥文件 | `-----BEGIN (RSA\|EC\|DSA\|OPENSSH\|PGP) PRIVATE KEY-----` |
| 通用 API Key | `(?i)(api[_-]?key\|apikey\|secret[_-]?key\|access[_-]?token)['\"]?\s*[:=]\s*['\"][A-Za-z0-9_\-]{16,}` |
| 云厂商 | `LTAI[A-Za-z0-9]{12,}`(阿里)、`AIza[0-9A-Za-z\-_]{35}`(Google)、`ghp_[A-Za-z0-9]{36}`(GitHub)、`sk-[A-Za-z0-9]{20,}`(OpenAI 形态)、`Bearer [A-Za-z0-9\-_.]{20,}` |
| 数据库连接串 | `(?i)(mysql\|postgres\|mongodb(\+srv)?)://[^/\s:]+:[^@\s]+@` |
| JWT secret | `(?i)jwt[_-]?(secret\|key)['\"]?\s*[:=]\s*['\"][^'\"]{8,}` |
| 高熵候选 | 单行 ≥20 字符、熵 >3.5、且上下文含 key/secret/token/password 字样 |
| 文件级 | `.env*`、`*.pem`、`*.key`、`id_rsa*`、`credentials*.json`、`*.keystore` 入库检测 |

> 命中后显示打码值（前 4 后 4），提醒立即轮换 + 从历史清除（BFG/git filter-repo），并计入 Critical。

## §8 CWE/OWASP 完整映射表

| UCSS 编号 | CWE | OWASP Top10:2021 | ASVS 4.0 参考条目 |
|---|---|---|---|
| SEC-INJ-01 | CWE-89 | A03:2021 Injection | V5.3.4, V7.1 |
| SEC-INJ-02 | CWE-78 | A03 | V5.2 |
| SEC-INJ-03 | CWE-79 | A03 | V5.3 |
| SEC-INJ-04 | CWE-22/409 | A01:2021 Broken Access Control | V12.3 |
| SEC-INJ-05 | CWE-611 | A05:2021 Security Misconfiguration | V13.2 |
| SEC-INJ-06 | CWE-918 | A10:2021 SSRF | V12.6 |
| SEC-INJ-07 | CWE-1336 | A03 | V5.1 |
| SEC-INJ-08 | CWE-502 | A08:2021 Software/Data Integrity | V5.5 |
| SEC-INJ-09 | CWE-94/95 | A03 | V5.1 |
| SEC-INJ-10 | CWE-90/643/1236/117 | A03 | V5.4 |
| SEC-AUTH-01 | CWE-798 | A07:2021 Identification/Auth Failures | V6.4 |
| SEC-AUTH-02 | CWE-916 | A02:2021 Cryptographic Failures | V6.2 |
| SEC-AUTH-03 | CWE-306 | A07 | V4.1 |
| SEC-AUTH-04 | CWE-639/862/863 | A01 | V4.2/4.3 |
| SEC-AUTH-05 | CWE-613/384 | A07 | V3.3/3.5 |
| SEC-AUTH-06 | CWE-307 | A07 | V2.2 |
| SEC-AUTH-07 | CWE-338 | A02 | V6.3 |
| SEC-AUTH-08 | CWE-347 | A02/A07 | V3.5 |
| SEC-CRY-01 | CWE-327/326 | A02 | V6.2 |
| SEC-CRY-02 | CWE-321/259 | A02 | V6.4 |
| SEC-CRY-03 | CWE-311/532 | A02 | V6.1/7.1 |
| SEC-CRY-04 | CWE-329/330 | A02 | V6.3 |
| SEC-CRY-05 | CWE-352 | A01 | V5.1.3 |
| SEC-FILE-01 | CWE-434 | A05 | V12.1 |
| SEC-FILE-03 | CWE-200/209 | A05 | V7.4 |
| SEC-FILE-04 | CWE-942 | A05 | V14.4 |
| SEC-FILE-06 | CWE-601 | A01 | V12.6 |
| SEC-FILE-07 | CWE-367 | A01 | V1.5 |
| DEF-CON-01 | CWE-362 | A04:2021 Insecure Design | V1.5 |
| SUP-DEP-01 | CWE-1104 | A06:2021 Vulnerable Components | V14.2 |
| SUP-CFG-02 | CWE-250 | A05 | V14.1 |

---
*UCSS v1.0 · references/checklists.md*
