# 各语言危险模式库（references/language-patterns.md）

> 按 Project 语言只读对应章节。每节给出：高危 Source（污点源）、危险 Sink（危险操作）、净化参考（Sanitizer）、语言专属规则。

## 1. Java（Spring/Spring Boot/MyBatis）

**Source**：`request.getParameter/getHeader/getCookies`、`@RequestParam/@PathVariable/@RequestBody`、`MultipartFile`、`System.getenv`、MQ 消息体
**Sink**：
- SQL：`Statement.execute/executeQuery`、`EntityManager.createNativeQuery(拼接)`、MyBatis `${}`（含 `orderBy${}`）、JPA `@Query(nativeQuery=true, 拼接)`
- 命令：`Runtime.getRuntime().exec(拼接)`、`ProcessBuilder(shell 拼接)`
- 反序列化：`ObjectInputStream.readObject`、`XStream` 无白名单、Fastjson `autoType`、Jackson `enableDefaultTyping`
- XXE：`DocumentBuilderFactory` 默认（需 `setFeature(DISABLE_DOCTYPE_DECL, true)` + 禁外部实体）
- XSS：`th:utext`、JSP `<%=`、`response.getWriter().print(user)`
- SSRF：`RestTemplate.getForObject(userUrl)`、`HttpURLConnection(userUrl)`
- 路径：`new File(base, user)`、`Files.newInputStream(Paths.get(base+user))`

**Sanitizer 参考**：`PreparedStatement` 参数绑定、MyBatis `#{}`、`ESAPI.encoder()`、OWASP Java HTML Sanitizer、`XMLInputFactory` 安全配置
**专属规则**：
- Spring Actuator/Swagger/Druid 未鉴权暴露（SUP-CFG-01）
- `@CrossOrigin(origins="*")`（SEC-FILE-04）
- `@RequestMapping` 接口无 `@PreAuthorize`/Security 规则且路径敏感（SEC-AUTH-03）
- `SecretKeySpec(硬编码字节)`（SEC-CRY-02）

## 2. Python（Django/Flask/FastAPI）

**Source**：`request.args/form/json/cookies/files`、Django `request.GET/POST`、FastAPI 请求参数、`os.environ`（外部配置）
**Sink**：
- SQL：`cursor.execute(f"...{x}")`、`"%s" % x` 拼 SQL、`.raw()`、`.extra()`
- 命令：`os.system/popen`、`subprocess.call(shell=True)`、`os.execv`
- 反序列化：`pickle.loads`、`yaml.load`(无 Loader=SafeLoader)、`marshal.loads`
- SSTI：`render_template_string(u)`、`Environment().from_string(u)`
- SSRF：`requests.get(u)`、`urllib.request.urlopen(u)`
- 路径：`open(user)`、`shutil.rmtree(user)`、`send_file(user)`
- 代码执行：`eval/exec/compile(u)`

**Sanitizer 参考**：参数化查询（execute(sql, params)）、`bleach`/`nh3`、`yaml.safe_load`、`jinja2` 自动转义开启、Django ORM
**专属规则**：
- `DEBUG = True` / `app.run(debug=True)`（SUP-CFG-01）
- `SECRET_KEY` 硬编码（SEC-CRY-02）
- Flask `send_file/send_from_directory` 路径未校验（SEC-INJ-04）
- FastAPI/DRF 无权限类（`permission_classes` 缺失）写接口（SEC-AUTH-03）
- `verify=False`（requests）（CWE-295 High）

## 3. JavaScript / TypeScript（Node/React/Vue/Angular/Express/Nest）

**Source**：`req.query/params/body/headers/cookies`、`location.search/hash`、`postMessage` 数据、`localStorage`、`URLSearchParams`
**Sink（Node）**：
- SQL：knex `.raw(拼接)`、mongoose `$where`、`sequelize.query(拼接)`
- 命令：`child_process.exec(拼接)`（用 `execFile`+数组替代）、`eval/Function`
- 路径：`fs.readFile(user)`、`res.sendFile(user)`、`path.join(base, user)` 后未 startsWith 校验
- SSRF：`axios.get(u)`、`fetch(u)`（服务端）
- 原型污染：`merge/Object.assign` 深合并用户对象到含 `__proto__`（CWE-1321）
**Sink（浏览器）**：
- `innerHTML/outerHTML/insertAdjacentHTML/document.write`、React `dangerouslySetInnerHTML`、Vue `v-html`、Angular `bypassSecurityTrust*`
- `href/src` 动态绑定含 `javascript:`；`setTimeout/setInterval` 字符串
- `postMessage(msg, "*")`、`addEventListener("message")` 不校验 `event.origin`

**Sanitizer 参考**：DOMPurify、React/Vue 默认转义（不用 raw API）、`encodeURIComponent`（URL 上下文）、Nest `ValidationPipe` 白名单、参数化 ORM
**专属规则**：
- JWT 存 localStorage（Medium，建议 HttpOnly Cookie）
- 生产 sourcemap 上传 CDN（信息泄露 Low~Medium）
- `VITE_*`/`NEXT_PUBLIC_*` 中放密钥（Critical，前端密钥必暴露）

## 4. Go

**Source**：`r.URL.Query()`、`r.FormValue/PostFormValue`、`r.Header/Body`、`http.Request` 泛型体
**Sink**：
- SQL：`fmt.Sprintf` 拼 SQL + `db.Query/exec`
- 命令：`exec.Command("sh","-c", 拼接)`
- SSRF：`http.Get(userURL)`
- 路径：`os.ReadFile(user)`、`http.ServeFile(w, r, user)`、`filepath.Join` 后未校验
- 模板：`template.HTML(user)`（绕过自动转义）
**Sanitizer 参考**：`db.Query(sql, args...)`、`html/template`（默认转义）、`path.Clean`+`strings.HasPrefix` 白名单、`url.Parse` 后校验 Host 白名单
**专属规则**：
- 整数溢出：`int` 转 `uint`、`unsafe` 使用（DEF-NUM-01）
- goroutine 闭包捕获循环变量（Go<1.22 版本语义，DEF-CON-01）
- `defer` 在循环内导致资源延迟释放（DEF-RES-01）
- `tls.Config{InsecureSkipVerify: true}`（CWE-295 High）

## 5. C / C++

**Source**：`argv`、`read/fgets`、网络 `recv`、`getenv`、文件内容
**Sink（按 CWE Top25）**：
- 缓冲区溢出：`strcpy/strcat/sprintf/gets/scanf(%s)`（CWE-120/787）→ 用 `strncpy/s snprintf`
- 整数溢出：`malloc(size * count)` 无溢出检查（CWE-190/680）
- UAF/双释放：`free` 后继续使用/再次 free（CWE-416/415）
- 格式化字符串：`printf(user)`（CWE-134）→ `printf("%s", user)`
- 命令注入：`system(user)`、`popen(user)`
- 未初始化：`malloc` 后未 memset 直接读取（CWE-457/908）
- 越界读：数组索引无边界检查（CWE-125）
**Sanitizer 参考**：`fgets`+长度、`snprintf`、智能指针(C++)、`-D_FORTIFY_SOURCE=2`、ASan/UBSan 构建验证
**专属规则**：MISRA C/CERT-C 对齐：禁 `goto` 跨作用域、宏副作用、隐式类型转换告警；嵌入式额外看看门狗/中断安全。

## 6. C#（.NET）

**Sink**：
- SQL：`SqlCommand(cmdText + user)`、`EF FromSqlRaw(拼接)`
- 命令：`Process.Start(user)`、`cmd /c 拼接`
- 反序列化：`BinaryFormatter.Deserialize`（禁用）、`JavaScriptSerializer`、`NetDataContractSerializer`
- XXE：`XmlDocument` 默认（需 `XmlResolver=null` 或 safe settings）
- XSS：`@Html.Raw(user)`
- 路径：`File.ReadAllText(user)`
**Sanitizer**：参数化 `SqlParameter`、Razor 自动转义、`AntiXssEncoder`、`System.Text.Json` 替代 BinaryFormatter
**专属规则**：`ValidateInput=false`（旧 WebForms）、`csrf` 标签缺失、`TrustServerCertificate=True` 连接串。

## 7. PHP

**Sink**：
- SQL：`mysql_query/mysqli_query(拼接)`、`PDO::query(拼接)`（用 prepare+execute）
- 命令：`system/exec/shell_exec/passthru/反引号`、`proc_open`
- XSS：`echo $user`、`<?= $user ?>`（用 `htmlspecialchars` + 引号上下文）
- 文件：`include/require($user)`（RFI/LFI，CWE-98）、`file_get_contents(user)`
- SSRF：`curl_exec($ch)` URL 可控
- 反序列化：`unserialize($user)`（CWE-502；用 `allowed_classes: false`）
**Sanitizer**：`PDO prepared`、`htmlspecialchars/htmlentities`、`escapeshellarg`、`filter_var` 白名单
**专属规则**：`allow_url_include=On`、上传未用 `move_uploaded_file` 白名单校验。

## 8. Ruby（Rails）

**Sink**：
- SQL：`where("... #{params[:x]}")`、`.order(params)`（用 hash/白名单）
- 命令：`system("cmd #{x}")`、反引号、`open("|"+cmd)`（Kernel#open 命令注入！用 `File.open`）
- XSS：`html_safe`、`raw`、`<%= %>`（默认转义，这三个绕过）
- 反序列化：`Marshal.load`、`YAML.load`（用 `safe_load`）
- mass assignment：`Model.new(params)` 无 permit（用 Strong Parameters）
**专属规则**：`Brakeman` 必跑；`render inline:`/`render file:` 用户可控。

## 9. Rust

**Sink**：
- `unsafe` 块审计（裸指针/`transmute`/`from_raw`，CWE-787/416）
- `format!` 拼 SQL → `sqlx`/`diesel` 参数化
- `Command::new("sh").arg("-c").arg(user)`
- `unwrap()` 在可失败路径（panic 风险，Info~Low）
**供应链**：`cargo-audit`（RustSec）、`cargo-deny`（许可证/重复版本）；`std::process` 环境继承注意。

## 10. Swift / Objective-C（iOS）

**Sink**：
- `WKWebView`：`loadHTMLString` 注入、`evaluateJavaScript(user)`
- `URL(string: user)` 后请求（SSRF 面）、ATS 例外域过宽
- OC：`stringWithFormat` 拼 SQL（SQLite）、`WKScriptMessageHandler` 未校验来源
- Keychain 未使用（UserDefaults 存 token，SEC-CRY-03）
**专属规则**：剪贴板敏感数据、URL Scheme 处理无 host 校验、`NSAllowsArbitraryLoads`。

## 11. Kotlin（Android/后端）

- Android 同 Swift/OC 段 + `Intent.parseUri`、`Jsoup.parse` 未白名单
- 后端同 Java 章节（Ktor：`call.receive`、`Exposed` raw）
- `!!` 非空断言链（NPE 风险 DEF-NPE-01）

## 12. Scala

- Spark/Play：`sql"#$user"` 插值（用 `sql"..."` 参数化 + `on(...)`）、`eval`
- `collect` 用户正则（ReDoS，CWE-1333）

## 13. SQL / 存储过程

- 动态 SQL 拼接（`EXEC(@sql + @user)`、`sp_executesql` 未参数化）
- 权限：`GRANT ALL`、应用账号使用 SA/root

## 14. 配置/脚本类（YAML/Dockerfile/Terraform/CI）

- Dockerfile：`USER root`、`ADD url`、`latest` 标签、secret 进层（`ENV AK=...`）
- K8s：见 checklists §6；CI：明文 secret、`pull_request_target` 滥用、第三方 action 未 pin SHA（CWE-1357）

---
*UCSS v1.0 · references/language-patterns.md · Source/Sink 清单持续维护*
