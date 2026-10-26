<!--
  STD-HUB-001 统一调度体系说明（V1.7 · 2026-09-29 接入）：
  本文件是 UCSS 规则体系定本（原包 01_规范文档/行业代码扫描规范_v1.0.md，零改写收编）。
  技能面主文件：.standards/ucss/SKILL.md（五步法作业流）· 入口：.standards/UCSS.md
  门禁：.standards/ucss/scripts/ucss_gate.py（SARIF CI 门禁，exit 0/1/2 机制层强制）
  判型：混合形态（规范面+技能面）· 双层 · 质量门型·扫描域——.standards/meta/records/spec-ucss.yaml
-->
# 行业代码扫描规范 v1.0

**Unified Code Scanning Specification（UCSS）— 统一代码扫描规范**

| 元数据 | 内容 |
|---|---|
| 版本 | v1.0 |
| 状态 | 正式发布 |
| 适用对象 | 智能体（ZCode 等 ADE）、研发团队、安全团队、QA 团队 |
| 语言范围 | Java / Kotlin / Python / JavaScript / TypeScript / Go / C / C++ / C# / PHP / Ruby / Rust / Swift / Objective-C / Scala / SQL + 前端框架（React/Vue/Angular）+ 后端框架（Spring/Express/Django/FastAPI/Gin 等） |
| 对齐标准 | CWE 4.15+ / CWE Top 25 2024 / OWASP Top 10:2021 / OWASP ASVS 4.0.3 / ISO/IEC 5055:2021（CISQ）/ SEI CERT / MISRA / GB/T 38674-2020 / GB/T 43698-2024 / CVSS v3.1 / OASIS SARIF 2.1 |

---

## 1. 总则

### 1.1 目的

本规范整合国际（OWASP、CWE、ISO/IEC、SEI CERT）、国内（GB/T、等保 2.0）及行业领先实践（奇安信代码卫士、SonarQube、Semgrep、CodeQL 等工具规则体系），定义一套**可被智能体直接执行、可被 CI/CD 直接集成**的统一代码扫描规范，目标是：

1. 统一"安全漏洞、软件缺陷、编码规范、逻辑推理、供应链与配置"五大扫描域的判定标准；
2. 统一严重等级、门禁阈值、修复 SLA 与报告格式；
3. 让智能体（ZCode 等）以 Skill 形式内化本规范，在代码生成与评审时同步执行扫描逻辑，实现"安全左移到生成时"。

### 1.2 适用范围

- 全栈研发：前端（Web/小程序/移动端）、后端服务、微服务、SDK、脚本工具；
- 全生命周期：编码 → 提交 → 合并 → 构建 → 发布 → 运行时反馈；
- 全形态代码：人类编写的代码与 AI 生成的代码（后者执行本规范第 11 章 AI 专项增强检查）。

### 1.3 术语定义

| 术语 | 定义 |
|---|---|
| SAST | 静态应用安全测试，不运行程序、基于源码/字节码的数据流与控制流分析 |
| SCA | 软件成分分析，识别第三方依赖及其已知漏洞（CVE/CNVD）与许可证风险 |
| DAST | 动态应用安全测试，运行时黑盒测试 |
| IAST | 交互式应用安全测试，测试运行时插桩检测 |
| 弱点（Weakness） | 代码中可被利用或引发故障的缺陷模式，以 CWE 编号标识 |
| 污点（Taint） | 从不可信来源（Source）流向敏感操作（Sink）且未经净化（Sanitizer）的数据 |
| 门禁（Gate） | 阻断流水线的质量/安全阈值条件 |
| 置信度 | 扫描结论为真实问题的概率分级（见第 8 章） |

### 1.4 引用的标准与规范

**国际标准：**

| 标准 | 用途 |
|---|---|
| CWE（MITRE） | 弱点分类统一编号，本规范所有问题必须映射 CWE 编号 |
| CWE Top 25 2024 | 最高频危险弱点清单，扫描必检集 |
| OWASP Top 10:2021 | Web 应用关键风险分类（A01~A10） |
| OWASP ASVS 4.0.3 | 应用安全需求验证等级（L1/L2/L3），本文默认 L2 |
| OWASP Cheat Sheet 系列 | 各类漏洞的判定与修复依据 |
| ISO/IEC 5055:2021（CISQ） | 基于源码弱点的软件质量度量（安全/可靠/性能/可维护） |
| SEI CERT C / C++ / Java / Perl | 各语言安全编码规则 |
| MISRA C:2012 / MISRA C++:2008 / AUTOSAR C++14 | 嵌入式/车载 C/C++ 补充规则 |
| CVSS v3.1（FIRST） | 漏洞严重程度评分 |
| OASIS SARIF 2.1.0 | 扫描结果标准交换格式 |
| SPDX 2.3 / CycloneDX 1.5 | SBOM（软件物料清单）标准 |

**国家标准与行业规范：**

| 标准 | 用途 |
|---|---|
| GB/T 38674-2020《信息安全技术 应用软件安全编程指南》 | 安全编程要求（中文场景对齐） |
| GB/T 43698-2024《网络安全技术 软件供应链安全要求》 | 供应链/SCA/SBOM 要求 |
| GB/T 25000.51（SQuaRE） | 系统与软件质量要求 |
| 等级保护 2.0（GB/T 22239-2019） | 安全建设要求中的开发安全条款 |
| GB/T 30276-2020 | 漏洞管理流程要求 |

**行业工具规则体系（对齐源）：**

| 工具 | 类型 | 对齐内容 |
|---|---|---|
| 奇安信代码卫士（V9） | SAST | 1300+ 源代码缺陷规则、20+ 语言；本规范安全/缺陷规则与之对齐 |
| 奇安信开源卫士（V3） | SCA | 开源组件资产识别、漏洞告警 |
| SonarQube（RSPEC 规则） | SAST/质量 | 可靠性/安全性/可维护性规则编号 |
| Semgrep | 轻量 SAST | 规则语法与 CI 集成模式 |
| CodeQL / Checkmarx / Fortify | 企业 SAST | 污点分析模型（Source-Sink-Sanitizer） |
| ESLint(+security)、Bandit、GoSec、SpotBugs+FindSecBugs、cppcheck、Brakeman、gitleaks 等 | 语言级工具 | 各语言落地执行器（见第 9 章映射） |

---

## 2. 规范体系架构

```
┌─────────────────────────────────────────────────────────────┐
│                     统一代码扫描规范 UCSS                      │
├──────────────┬──────────────┬──────────────┬────────────────┤
│   扫描域      │   等级体系    │   流程门禁    │   输出闭环      │
│  (第4~6章)   │   (第7章)     │   (第10章)   │  (第8/9/12章)  │
├──────────────┼──────────────┼──────────────┼────────────────┤
│ D1 安全漏洞   │ Critical     │ 开发态门禁    │ SARIF 报告      │
│ D2 软件缺陷   │ High         │ 提交态门禁    │ 修复建议        │
│ D3 编码规范   │ Medium       │ 构建态门禁    │ SLA 追踪       │
│ D4 逻辑推理   │ Low + Info   │ 发布态门禁    │ 度量看板        │
│ D5 供应链配置 │              │              │                │
└──────────────┴──────────────┴──────────────┴────────────────┘
      每个 Scan 域问题均携带：CWE 编号 / OWASP 映射 / CVSS 评分 / 置信度
```

**五大扫描域（Scan Domains）：**

| 域 | 名称 | 目标 | 对应质量特性 |
|---|---|---|---|
| D1 | 安全漏洞 Security | 发现可被利用的安全弱点 | ISO 5055 Security |
| D2 | 软件缺陷 Defect | 发现导致运行故障的可靠性缺陷 | ISO 5055 Reliability |
| D3 | 编码规范 Style | 统一风格、控制复杂度 | ISO 5055 Maintainability |
| D4 | 逻辑与推理 Logic | 业务逻辑漏洞、状态/并发/时序缺陷，需多步推理判定 | Security + Reliability |
| D5 | 供应链与配置 Supply | 依赖、密钥、配置、IaC | GB/T 43698 |

---

## 3. 语言与场景支持矩阵

### 3.1 语言覆盖（16 语言 + 主流框架）

| 语言 | 后端服务 | 前端/客户端 | 必检规则源（完整映射见附录/Skill references） |
|---|---|---|---|
| Java | ✔（Spring/Spring Boot/MyBatis） | ✔（Android） | CERT-J、SpotBugs、FindSecBugs、代码卫士 Java 集 |
| Kotlin | ✔（Ktor） | ✔（Android） | detekt、Android Lint |
| Python | ✔（Django/FastAPI/Flask） | ✔ | bandit、ruff、Pylint、CERT-Python |
| JavaScript | ✔（Node.js/Express/Nest） | ✔（React/Vue/Angular） | ESLint+security、Semgrep JS 集 |
| TypeScript | ✔（NestJS） | ✔（React/Vue/Angular） | ESLint+typescript-eslint+security |
| Go | ✔（Gin/Beego/grpc） | — | gosec、golangci-lint |
| C | 嵌入式/系统 | — | MISRA C、CERT-C、flawfinder、cppcheck |
| C++ | 系统/客户端 | ✔（Qt/游戏） | MISRA/CERT-C++、AUTOSAR、cppcheck |
| C# | ✔（.NET） | ✔（Unity/WPF） | Roslyn analyzers、Security Code Scan |
| PHP | ✔（Laravel/ThinkPHP） | ✔ | PHPStan/Psalm、Semgrep PHP |
| Ruby | ✔（Rails） | — | Brakeman、RuboCop |
| Rust | ✔（系统/高性能） | ✔（WASM） | clippy、cargo-audit |
| Swift | — | ✔（iOS） | SwiftLint 安全集 |
| Objective-C | — | ✔（iOS 遗留） | 代码卫士 OC 集、Clang analyzer |
| Scala | ✔（Akka/Spark） | — | Scalastyle、WartRemover |
| SQL | 存储过程/脚本 | — | SQL 注入模式、sqlfluff |

### 3.2 场景专项矩阵

| 场景 | 附加必检项 |
|---|---|
| 前端 Web（React/Vue/Angular） | XSS（dangerouslySetInnerHTML/v-html/innerHTML）、DOM 型注入、postMessage 校验、JWT 本地存储、源映射泄露、依赖混淆 |
| 小程序 | API 域名白名单、本地缓存敏感数据、appSecret 硬编码 |
| Android | 组件导出、Intent 注入、WebView（JS 桥/文件访问）、日志泄露、允许备份 |
| iOS | Keychain 使用、ATS 配置、剪贴板敏感信息、URL Scheme 劫持 |
| 后端 API | OWASP API Top 10（BOLA/BFLA/越权/资源耗尽）、批量赋值、限流缺失 |
| 微服务/云原生 | 容器逃逸配置、K8s YAML 特权容器、IaC（Terraform）、Secret 挂载 |
| AI 应用代码 | Prompt 注入面、模型输出未净化直接渲染、RAG 数据越权 |

---

## 4. 扫描域 D1：安全漏洞（必检规则集）

> 判定模型统一采用污点分析四元组：**Source（污点源）→ Propagator（传播）→ Sanitizer（净化）→ Sink（危险操作）**。命中 = 存在一条 Source→Sink 路径且无有效净化。

### 4.1 输入与注入类（OWASP A03）

| 编号 | 规则 | 典型 Sink（各语言） | 严重等级基线 |
|---|---|---|---|
| SEC-INJ-01 | SQL 注入（CWE-89） | 字符串拼接 SQL：Java `Statement.execute`、Python `cursor.execute(f"...")`、PHP `mysqli_query($sql.$x)`、Go `fmt.Sprintf` 拼 SQL、MyBatis `${}` | Critical |
| SEC-INJ-02 | 命令注入（CWE-78） | `Runtime.exec`、`os.system/subprocess(shell=True)`、`child_process.exec`、`os/exec.Command`（拼接） | Critical |
| SEC-INJ-03 | XSS 反射/存储（CWE-79） | 未编码输出：React `dangerouslySetInnerHTML`、Vue `v-html`、Thymeleaf `th:utext`、JSP `<%=` | High（存储型 Critical） |
| SEC-INJ-04 | 路径穿越/任意文件读写（CWE-22） | `new File(userInput)`、`open(../)`、`fs.readFile(userPath)`、Zip 解压未校验（ZipSlip CWE-409） | High |
| SEC-INJ-05 | XXE（CWE-611） | XML 解析器未禁外部实体：`DocumentBuilderFactory`、libxml、lxml 默认配置 | High |
| SEC-INJ-06 | SSRF（CWE-918） | 服务端以用户输入发起 URL 请求且未校验目标（含 DNS 重绑定） | High |
| SEC-INJ-07 | 模板注入 SSTI（CWE-1336） | Jinja2 `render_template_string(user)`、Velocity/Freemarker 用户输入模板 | Critical |
| SEC-INJ-08 | 反序列化不可信数据（CWE-502） | Java `ObjectInputStream`、Python `pickle.loads`、.NET `BinaryFormatter`、Fastjson autoType | Critical |
| SEC-INJ-09 | 代码/表达式注入（CWE-94/95） | `eval`/`exec`、`Function()`、`ELProcessor.eval`、Groovy shell | Critical |
| SEC-INJ-10 | LDAP/XPath/ORM/日志注入（CWE-90/643/1236） | 拼接查询过滤器、`log.info(userInput)` 无换行转义 | Medium~High |

### 4.2 认证与会话（OWASP A07）

| 编号 | 规则 | 判定要点 | 等级 |
|---|---|---|---|
| SEC-AUTH-01 | 硬编码凭证（CWE-798） | 源码/配置中出现密码、AK/SK、token、API key（正则+熵检测；联动 SUP-SEC-01） | Critical |
| SEC-AUTH-02 | 弱口令策略/弱哈希（CWE-916/327） | MD5/SHA1 存密码、无 salt、bcrypt/PBKDF2/Argon2 缺失；IDEA、ECB 模式 | High |
| SEC-AUTH-03 | 未认证访问（CWE-306） | 敏感接口缺少鉴权注解/中间件（Spring Security、FastAPI Depends） | High |
| SEC-AUTH-04 | 越权访问（CWE-284/639） | 水平越权：以 ID 直接查询无属主校验；垂直越权：仅前端隐藏入口 | High |
| SEC-AUTH-05 | 会话管理缺陷（CWE-613/384） | 会话不过期、登出未失效、JWT 不校验签名/算法混淆（none/HS-RS）、Cookie 缺 HttpOnly/Secure/SameSite | Medium~High |
| SEC-AUTH-06 | 无防爆破（CWE-307） | 登录、找回密码、验证码接口无频率限制 | Medium |
| SEC-AUTH-07 | 随机数不安全（CWE-338） | `random`、`Math.random`、`rand()` 用于 token/验证码/密钥 | High |

### 4.3 加密与数据保护（OWASP A02）

| 编号 | 规则 | 判定要点 | 等级 |
|---|---|---|---|
| SEC-CRY-01 | 已弃用/弱加密算法（CWE-327） | DES、RC4、MD5/SHA1（安全用途）、RSA<2048、TLS<1.2 | High |
| SEC-CRY-02 | 硬编码加密密钥/IV（CWE-321/259） | AES key 写死源码、IV 固定、密码写入配置 | Critical |
| SEC-CRY-03 | 敏感数据明文存储/传输（CWE-311/532） | 身份证/手机号/银行卡明文入库、http 明文传输、日志打印敏感字段 | High |
| SEC-CRY-04 | 密码学误用（CWE-326/330） | ECB 模式、GCM nonce 复用、密钥长度不足 | High |

### 4.4 文件、资源与配置（OWASP A05）

| 编号 | 规则 | 判定要点 | 等级 |
|---|---|---|---|
| SEC-FILE-01 | 文件上传未校验（CWE-434） | 类型/大小/内容（魔数）校验缺失、上传目录可执行 | Critical |
| SEC-FILE-02 | 目录遍历/列目录（CWE-548） | 生产开启目录列表、静态映射未限制 | Medium |
| SEC-FILE-03 | 敏感信息泄露（CWE-200/538/209） | .git/.env/备份文件可访问、错误堆栈直接返回、Swagger 生产暴露 | Medium~High |
| SEC-FILE-04 | CORS 配置错误（CWE-942） | `Access-Control-Allow-Origin: *` 携带凭证、Origin 反射 | Medium |
| SEC-FILE-05 | 安全响应头缺失（CWE-693） | CSP、X-Content-Type-Options、HSTS 缺失 | Low~Medium |

### 4.5 D1 必检映射：CWE Top 25（2024）

以下弱点全部纳入必检集（未在上文列出的补充于此）：
CWE-79、CWE-787（越界写）、CWE-89、CWE-352（CSRF）、CWE-22、CWE-125（越界读）、CWE-78、CWE-416（UAF，C/C++ 必检）、CWE-862（缺授权）、CWE-476、CWE-20（输入校验缺失）、CWE-88（参数注入）、CWE-190（整数溢出，C/C++/Go 必检）、CWE-502、CWE-287、CWE-798、CWE-863、CWE-77、CWE-306、CWE-119、CWE-276（权限默认错误）、CWE-918、CWE-362（竞态）。

### 4.6 CSRF 与业务防护（CWE-352）

- 状态变更接口必须具备：CSRF Token / SameSite=Strict|Lax / 同源校验三者之一；
- JSON API 且不接受跨域时可豁免，但须在扫描报告中标注豁免依据。

---

## 5. 扫描域 D2：软件缺陷

> 对齐 ISO/IEC 5055 可靠性度量、CERT 规则与 SonarQube Bug 集。

| 编号 | 规则 | 说明 | 等级基线 |
|---|---|---|---|
| DEF-NPE-01 | 空指针/空引用解引用（CWE-476） | 可能为 null 的返回值/入参未判空即使用 | High |
| DEF-RES-01 | 资源泄漏（CWE-404/772） | 文件/连接/流未在 finally/with/defer/try-with-resources 关闭 | High |
| DEF-CON-01 | 并发缺陷（CWE-362/366/567） | 共享可变状态无锁保护、double-checked locking 错误、死锁环（锁序不一致）、并发修改异常风险 | High |
| DEF-NUM-01 | 数值缺陷（CWE-190/191/192） | 整数溢出（C/C++/Go 必检）、除零、浮点等值比较、隐式窄化转换丢精度 | Medium~High |
| DEF-MEM-01 | 内存缺陷（C/C++：CWE-415/416/476/787/125/401/590） | UAF、双重释放、缓冲区溢出、内存泄漏、栈超限、未初始化读取（CWE-457） | High~Critical |
| DEF-ERR-01 | 异常处理缺陷（CWE-703） | 吞异常（空 catch）、捕获过宽、finally 中 return 吞异常、错误信息泄露内部路径 | Medium |
| DEF-LOG-01 | 边界与时序（CWE-367） | TOCTOU（检查与使用不一致）、日期时区处理错误、条件恒真/恒假 | Medium |
| DEF-UI-01 | 状态机缺陷 | 组件卸载后 setState、Promise 未捕获 rejection、async 竞态 | Medium |

---

## 6. 扫描域 D3、D4、D5

### 6.1 D3 编码规范（Maintainability）

| 编号 | 规则 | 阈值基线（可项目级调整） |
|---|---|---|
| STY-CPX-01 | 圈复杂度 | 函数 ≤ 15 |
| STY-CPX-02 | 认知复杂度 | 函数 ≤ 15 |
| STY-CPX-03 | 函数长度 | ≤ 80 行（软限）/ 200 行（硬限） |
| STY-CPX-04 | 文件长度 | ≤ 1000 行 |
| STY-CPX-05 | 参数个数 | ≤ 5 |
| STY-CPX-06 | 嵌套深度 | ≤ 4 层 |
| STY-DUP-01 | 重复代码 | 块 ≥ 100 token 重复率 ≤ 3% |
| STY-NAM-01 | 命名规范 | 遵循各语言社区标准；禁用单字母命名（循环变量除外） |
| STY-DOC-01 | 公共 API 文档 | 公共 API 必须有文档注释 |
| STY-SEC-01 | 死代码 | 未使用变量/导入/函数、被注释掉的代码块 |
| STY-FMT-01 | 格式 | 以各语言官方 Formatter 为准（gofmt、black、prettier 等） |

### 6.2 D4 逻辑与推理审计（本规范特色域）

> D4 问题无法用单点规则覆盖，须通过**多步推理**判定，是智能体扫描相对传统工具的核心增量。执行时按以下五个推理协议逐项核查：

**P1 授权推理协议（BOLA/BFLA）**
对每个写操作/敏感读接口，沿调用链回答三问：① 对象属主是否与当前主体比对？② 角色权限是否在服务端强制？③ 未授权路径（未登录/低角色）可达吗？任一问"否/不确定"→ 报告 CWE-639/862/863。

**P2 状态与时序推理协议**
枚举接口并发/重放场景：同一请求并发两次会怎样？支付/退款/库存扣减是否幂等？非幂等且无锁/无唯一约束 → CWE-362/472。

**P3 数据流完整性推理**
追踪关键字段（金额、数量、角色、价格、状态）从输入到落库全程：是否有中间篡改点（前端传入、隐藏字段、可编辑 payload）→ CWE-472/348。

**P4 业务规则对抗推理**
站在攻击者视角推演：限流绕过（改 ID/分页/大小写）、校验绕过（构造边界值/类型混淆/超长输入）、优惠/积分逻辑套利。发现可组合利用链 → 至少 Medium。

**P5 设计缺陷推理（OWASP A04）**
评估：信任边界跨越（客户端校验当服务端校验）、默认开放（fail-open）、TOCTOU、缺少防滥用设计（无审计日志 CWE-778）。

> 智能体执行 D4 时必须输出**推理链**（证据行号 + 推导步骤），不得无证据断言。

### 6.3 D5 供应链与配置

| 编号 | 规则 | 判定要点 | 等级基线 |
|---|---|---|---|
| SUP-DEP-01 | 已知漏洞依赖（SCA） | 依赖（含传递依赖）命中 CVE/GHSA：CVSS≥9 Critical、7~8.9 High、4~6.9 Medium、<4 Low | Critical~Low |
| SUP-DEP-02 | 停更/幽灵依赖 | 依赖已归档、从仓库移除（依赖混淆/撤包攻击面） | Medium |
| SUP-DEP-03 | 许可证合规 | 引入 GPL/AGPL 等与项目商业策略冲突的许可证须登记 | Info~Medium |
| SUP-SEC-01 | 密钥/凭证扫描 | gitleaks 级规则：云 AK/SK、私钥文件、连接串、JWT secret、小程序 secret；含历史提交扫描 | Critical |
| SUP-CFG-01 | 配置安全 | debug 模式开启、默认口令、Druid/Swagger/Actuator 未鉴权暴露 | High |
| SUP-CFG-02 | 容器与 IaC | 以 root 运行、privileged、hostNetwork、明文 env Secret、Terraform 0.0.0.0/0 开放 | High |
| SUP-CFG-03 | 构建脚本安全 | 构建脚本中 curl|bash、不锁版本、postinstall 钩子风险 | Medium |
| SUP-SBOM-01 | SBOM 生成 | 发布产物必须生成 SPDX 或 CycloneDX 格式 SBOM（GB/T 43698-2024 对齐） | 门禁项 |

---

## 7. 严重等级体系

### 7.1 五级定义

| 等级 | CVSS v3.1 参考 | 定义 | 示例 |
|---|---|---|---|
| **Critical** | 9.0~10.0 | 可直接远程利用、造成核心数据泄露/系统失陷，无需交互 | SQL 注入、RCE、反序列化、硬编码生产密钥、上传 getshell |
| **High** | 7.0~8.9 | 可利用但需一定条件/权限，或造成重大可靠性故障 | 存储型 XSS、SSRF、越权、弱加密 |
| **Medium** | 4.0~6.9 | 利用条件受限或影响可控 | 反射 XSS（配合诱导）、CSRF、信息泄露、TOCTOU |
| **Low** | 0.1~3.9 | 纵深防御缺失、影响轻微 | 缺安全头、低风险信息暴露 |
| **Info** | 0 | 建议项/规范提示 | 命名、注释、风格 |

### 7.2 等级修正因子（动态调整）

按以下因子在基线上上浮/下调一级（最多一级，Critical 不下调至 Medium 以下）：

- **可达性**：Sink 所在接口未在路由/入口暴露 → 可降一级；
- **暴露面**：仅内网/管理后台可达 → 可降一级；
- **数据敏感度**：涉及个人敏感信息（PII）/金融数据 → 上浮一级；
- **利用链组合**：与其他问题可组合成利用链 → 上浮一级；
- **补偿控制**：WAF 规则/网关鉴权已明确覆盖 → 可降一级（须记录）。

### 7.3 缺陷密度度量（对齐 ISO/IEC 5055）

按 CISQ 四特性统计"每千行代码弱点密度（weaknesses/KLOC）"并纳入质量报告：
安全密度 / 可靠性密度 / 性能效率密度 / 可维护性密度。

---

## 8. 置信度与误报治理

| 置信度 | 定义 | 处置 |
|---|---|---|
| C1 确定 | 完整污点链/明确证据 | 直接计入门禁，不可豁免流程 |
| C2 很可能 | 路径存在但个别传播点未验证 | 计入门禁；允许开发者提交反证申诉 |
| C3 可能 | 模式匹配、缺数据流佐证 | 不阻断；进入待确认清单，72h 内人工/AI 复核 |

**申诉与抑制**：误报归档原因与责任链，用于规则调优；抑制白名单必须带工单号与有效期，过期自动恢复计入门禁。

**AI 辅助复核（推荐）**：对 Critical/High 自动发起二次推理复核（结合调用图上下文），输出复核意见与置信度变化，目标：Critical/High 误报率 < 20%。

---

## 9. 工具链与落地执行矩阵

### 9.1 双轨制工具策略

| 轨道 | 场景 | 工具 | 说明 |
|---|---|---|---|
| **深度轨** | 合并前全量/发布前 | 奇安信代码卫士 / Fortify / Checkmarx / CodeQL / SonarQube | 污点分析、跨文件跨函数，耗时长 |
| **敏捷轨** | IDE 内/提交增量 | Semgrep / 各语言 Linter / gitleaks | 秒级反馈，规则与本规范映射 |

### 9.2 各语言执行器映射（敏捷轨默认命令）

| 语言 | 安全 | 缺陷/质量 | 规范/格式 |
|---|---|---|---|
| Java | SpotBugs+FindSecBugs、Semgrep | SpotBugs、PMD | google-java-format、Checkstyle |
| Python | bandit、Semgrep | ruff（F/E/B 规则）、mypy | black、isort |
| JS/TS | eslint-plugin-security、Semgrep | eslint（recommended） | prettier、eslint |
| Go | gosec | go vet、staticcheck、golangci-lint 聚合 | gofmt |
| C/C++ | flawfinder、Semgrep | cppcheck、clang-tidy | clang-format（MISRA 插件可选） |
| C# | Security Code Scan | Roslyn Analyzers | dotnet format |
| PHP | Semgrep、Psalm taint | PHPStan | php-cs-fixer |
| Ruby | Brakeman | rubocop | rubocop |
| Rust | cargo-audit / cargo-deny | clippy | rustfmt |
| Swift/OC | SwiftLint 安全集、Clang analyzer | SwiftLint | swift-format |
| 依赖（SCA） | trivy / dependency-check / 奇安信开源卫士 | — | — |
| 密钥 | gitleaks / trufflehog | — | — |
| IaC | trivy config / checkov | — | — |

### 9.3 结果归一化（SARIF 2.1.0）

所有工具结果统一转换为 SARIF 2.1.0；字段映射要求：

```
ruleId        ← 本规范编号（如 SEC-INJ-01）+ 原生规则 ID 双写
level         ← error 映射 Critical/High，warning 映射 Medium，note 映射 Low/Info
cwe           ← properties["cwe"] 必填
owasp         ← properties["owasp"]（如 A03:2021）
cvss          ← properties["cvss_score"] + severity
confidence    ← properties["confidence"]（C1/C2/C3）
fingerprint   ← 稳定指纹（文件+规则+关键行归一化哈希），用于跨版本去重
```

---

## 10. 扫描流程与门禁

### 10.1 五阶段流水线

```
①编码态     ②提交态        ③合并态          ④构建/发布态        ⑤运行态
IDE 实时    pre-commit     MR/PR 门禁        CI 全量 + SCA       DAST/IAST 反馈
敏捷轨      敏捷轨增量      敏捷轨+深度轨      深度轨+SBOM+容器     回流扫描库
```

### 10.2 门禁阈值（默认值，可项目级收紧）

| 门禁 | 默认阻断条件 |
|---|---|
| 提交门禁 | 新增 Critical/High（C1/C2）= 0；gitleaks 命中 = 0 |
| 合并门禁 | 新增 Critical/High/Medium（C1）= 0；圈复杂度超硬限 = 0；CWE Top25 命中 = 0 |
| 发布门禁 | 存量 Critical = 0 且 High ≤ 基线值且无逾期单；SBOM 已生成；SCA 无 Critical CVE |
| 抑制合规 | 所有豁免/抑制必须带工单号+有效期，过期自动恢复计入门禁 |

### 10.3 修复 SLA

| 等级 | 响应 | 修复时限 | 逾期动作 |
|---|---|---|---|
| Critical | 4h | 24~48h | 冻结发布 |
| High | 1d | 7d | 通知安全负责人 |
| Medium | 3d | 30d | 纳入版本计划 |
| Low | 7d | 90d | 季度清理 |

---

## 11. AI 生成代码专项增强规范（AI-Code Addendum）

AI 生成代码（Copilot/ZCode 等产出）在 D1~D5 之上叠加检查：

1. **幻觉 API 检查**：引用的库/方法是否存在（import 可解析性验证）；
2. **复制风险**：生成代码中的硬编码密钥/内网地址/示例凭证（训练数据泄露特征）；
3. **过度授权模式**：生成配置/代码是否默认过宽（`*` 通配、`sudo`、宽松 CORS）；
4. **净化缺失高发点**：AI 常省略输出编码与参数化查询 → 对 D1 规则全量执行，不适用"仅增量"豁免；
5. **单元测试佐证**：D4 推理出的逻辑风险，要求补充对应边界测试后才可关闭；
6. **溯源标记**：AI 生成比例纳入报告元数据，供审计。

---

## 12. 报告规范

### 12.1 扫描报告必备字段

```yaml
report_id: <uuid>
project: <名称/仓库>
commit: <sha> / branch
scan_profile: full | incremental | targeted
languages: [...]
tools: [{name, version, track: deep|agile}]
summary:
  by_severity: {critical: n, high: n, medium: n, low: n, info: n}
  by_domain: {D1: n, D2: n, D3: n, D4: n, D5: n}
  density_kloc: {security: x, reliability: x, performance: x, maintainability: x}
  ai_generated_ratio: <0~1>   # 可选
gate_result: PASS | FAIL | FAIL_WITH_WAIVER(waiver_ids)
findings:
  - id: <fingerprint>
    rule: SEC-INJ-01
    cwe: "CWE-89"
    owasp: "A03:2021"
    severity: Critical
    confidence: C1
    cvss: 9.8
    location: {file: "...", line: 42, snippet: "..."}
    taint_trace: [source@L12, propagator@L30, sink@L42]   # D1/D2 必填
    reasoning: "..."        # D4 必填：推理链
    fix_advice: "..."       # 必填：含安全示例代码
    references: [ASVS-5.3.4, CERT-J-00xx]
```

### 12.2 修复建议规范

每条问题必须给出：① 问题描述与危害；② 定位与污点/推理链；③ **安全修复示例代码（正/反对照）**；④ 参考标准条款。禁止只报问题不给修法。

---

## 13. 网络模式与离线部署

> 本章解决三类落地环境：**完全隔离内网**（涉密/金融核心）、**可出网办公网**（有代理/白名单）、**混合同步**（线上资源更新本地缓存后离线消费）。UCSS 的核心扫描能力（D1~D5 规则库 + 智能体推理）本身零外网依赖；本章定义外网依赖的边界、同步机制与降级纪律。

### 13.1 三种运行模式

| 模式 | 代号 | 定义 | 能力边界 |
|---|---|---|---|
| 离线模式 | `OFFLINE` | 扫描全程禁止任何网络访问 | 规则库扫描 ✔ · 本地已装工具 ✔ · 本地缓存漏洞库 ✔ · 在线规则/漏洞查询 ✘ |
| 在线模式 | `ONLINE` | 可出网，允许实时拉取规则与查询漏洞库 | 全能力 |
| 同步模式 | `SYNC` | 在有网窗口期执行资源同步，将最新规则/漏洞库/工具包落到本地缓存，供 `OFFLINE` 消费 | 消费端仍按 OFFLINE 纪律执行 |

**模式判定优先级**：显式配置（环境变量 `UCSS_NETWORK_MODE=OFFLINE|ONLINE`）> 缓存可用性探测 > 默认 `OFFLINE`（安全默认原则：不确定时绝不外联）。

### 13.2 资源分类与同步矩阵

| 资源 | 类型 | 更新源 | 同步频率 | 离线消费方式 |
|---|---|---|---|---|
| UCSS 规则库（Skill references） | 内置 | 本规范版本发布 | 随版本 | 天然离线 |
| Semgrep 规则集 | 可同步 | semgrep-rules 仓库 | 周 | `--config <cache>/semgrep-rules/` |
| Trivy 漏洞库 | 可同步 | ghcr.io trivy-db | 日 | `--cache-dir <cache>/trivy-db --skip-db-update` |
| OSV / GitHub Advisory 数据 | 可同步 | api.osv.dev | 日/周 | osv-scanner `--local` |
| 语言审计工具（bandit/gosec/eslint…） | 工具 | PyPI/npm | 月 | 预装 + wheel 离线仓 |
| 奇安信代码卫士/开源卫士规则库 | 商用 | 官方离线升级包 | 月 | 内网部署版 + 升级包导入 |
| CWE/OWASP/CERT 标准数据 | 内置 | 官方发布 | 季度 | references 已内置摘要 |

### 13.3 本地缓存目录规范

```
~/.ucss/cache/（可用 UCSS_CACHE_DIR 重定向）
├── semgrep-rules/      # 预下载的 Semgrep 规则
├── trivy-db/           # Trivy 漏洞库
├── advisories/         # OSV/Advisory JSON 导出
├── wheels/             # Python 工具离线 wheel 包
├── npm-tarballs/       # Node 工具离线包
├── packs/              # 打包导出的离线资源包（跨网导入用）
└── manifest.json       # 各资源版本号、更新时间、来源、校验和
```

强制要求：
1. 所有缓存资源必须登记 `manifest.json`（版本 + 时间 + sha256），无 manifest 的缓存视为不可信；
2. 离线扫描报告必须标注缓存时效：`漏洞库版本：{date}（落后 {n} 天）`，落后超过 30 天须在报告limitations中显著提示；
3. 资源包导入内网时校验 sha256，禁止导入无签名/无校验的第三方规则。

### 13.4 同步流程（SYNC 模式）

```
有网窗口（定时任务/人工触发）
  └─> sync_resources.py sync        # 按矩阵逐项拉取，单项失败不中断
        ├─ git clone --depth 1 semgrep-rules → cache/
        ├─ trivy --download-db-only --cache-dir cache/trivy-db
        ├─ OSV API 批量导出已知依赖 advisory
        ├─ pip download / npm pack 工具包 → wheels|npm-tarballs
        └─ 更新 manifest.json（版本+校验和）
  └─> sync_resources.py pack        # 可选：打 zip 供内网摆渡导入
内网消费端
  └─> sync_resources.py import pack # 导入+校验 → 本地缓存就绪
  └─> 扫描时按 OFFLINE 纪律使用缓存
```

企业拓扑：单台「同步机」（可出网、白名单域：pypi/npm/osv.dev/semgrep.dev/ghcr.io）→ 定期打包 → 内网镜像仓/文件摆渡 → 各扫描节点导入。

### 13.5 离线模式行为约束（智能体执行纪律）

1. **禁止外联**：`OFFLINE` 下禁用 `--config auto`、在线漏洞查询、在线规则拉取；工具不存在时降级为纯规则库审查，**不得因缺工具而放弃扫描**；
2. **降级显式化**：任何降级（无工具/缓存过期/资源缺失）必须写入报告 `limitations`，禁止静默降级；
3. **时效性义务**：使用缓存漏洞库时，SCA 结论必须附带库版本与更新时间；过期缓存得出的"无漏洞"结论不得表述为"安全"，只能表述为"截至 {库版本} 未发现已知漏洞"；
4. **模式自报**：每份报告头必须标注运行模式 `mode: OFFLINE | ONLINE | SYNC-consumed`；
5. **同步健康检查**：`SYNC` 消费端每日执行 `sync_resources.py status`，任一资源落后超阈值（漏洞库 7 天 / 规则 30 天）触发告警。

### 13.6 在线模式附加约束

1. 外联目标白名单制：仅允许访问 13.2 矩阵列出的官方源；
2. 禁止将项目源码、依赖清单以外的任何内容上传到第三方服务；
3. 在线拉取的规则在首次使用前应过一遍本地审查（规则内容即正则/查询，防止恶意规则注入执行面）；
4. 全部外联行为记录到扫描日志（目标 URL、时间、结果），供审计。

---

## 附录 A：本规范编号 → CWE/OWASP 速查（摘要）

| 本规范编号 | CWE | OWASP Top10:2021 | ASVS 4.0 参考 |
|---|---|---|---|
| SEC-INJ-01 | CWE-89 | A03 | V5.3 / V7 |
| SEC-INJ-02 | CWE-78 | A03 | V5 |
| SEC-INJ-03 | CWE-79 | A03 | V5.3 |
| SEC-INJ-04 | CWE-22 | A01 | V12 |
| SEC-INJ-05 | CWE-611 | A05 | V13 |
| SEC-INJ-06 | CWE-918 | A10 | V12 |
| SEC-INJ-07 | CWE-1336 | A03 | V5 |
| SEC-INJ-08 | CWE-502 | A08 | V5 |
| SEC-INJ-09 | CWE-94/95 | A03 | V5 |
| SEC-AUTH-01 | CWE-798 | A07 | V6 |
| SEC-AUTH-02 | CWE-916/327 | A02 | V6 |
| SEC-AUTH-03 | CWE-306 | A07 | V4 |
| SEC-AUTH-04 | CWE-639/862 | A01 | V4 |
| SEC-AUTH-05 | CWE-613/384 | A07 | V3 |
| SEC-CRY-01 | CWE-327 | A02 | V6 |
| SEC-CRY-02 | CWE-321/259 | A02 | V6 |
| SUP-DEP-01 | CWE-1104 | A06 | V14 |
| SUP-CFG-02 | CWE-250 | A05 | V14 |

（完整映射由 Skill 包 `references/checklists.md` 提供）

## 附录 B：各语言高危模式 Source/Sink 示例集

（完整内容由 Skill 包 `references/language-patterns.md` 提供，含 16 语言 Source/Sink/Sanitizer 模式清单）

## 附录 C：版本与治理

- 本规范由安全委员会/规范 Owner 维护，季度评审；
- 规则新增遵循流程：提案（引用 CWE/ASVS 依据）→ 试点项目验证 → 误报率评估（<30% 可转门禁）→ 全量生效；
- 版本兼容：Minor 升级（规则新增/收紧）不阻断存量项目；Major 升级给 90 天过渡期。

---

*UCSS v1.0 · 整合 CWE / OWASP / ISO 5055 / CERT / GB-T 38674 / GB-T 43698 · 适配智能体与 CI/CD 双形态执行*
