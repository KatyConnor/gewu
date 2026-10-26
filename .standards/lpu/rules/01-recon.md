# LPU 规则 · 01 侦察阶段（S0 · RCN）

> 目标：30 分钟内建立项目画像，确定技术栈、规模、入口、数据存储，产出 RECON 报告。
> 侦察阶段产出的是"地图"而非"领土"——粗粒度、可纠错。

---

### LPU-RCN-001【M】技术栈指纹识别
按优先级依次探测并记录到 `config.json` 的 `stack` 字段：

| 探测对象 | 依据文件 |
|---|---|
| 语言与框架 | pom.xml / build.gradle / package.json / requirements.txt / go.mod / *.csproj / Gemfile / composer.json |
| Web 框架与版本 | 依赖清单 + 入口类（如 SpringBootApplication、app.listen） |
| 数据库类型 | JDBC URL / 连接字符串 / *.sql / ORM 配置 |
| 中间件 | redis/mq/缓存配置出现处 |
| 部署形态 | Dockerfile / k8s yaml / war 包结构 / cron 配置 |

结论必须附依赖文件证据。探测不到的项目写入"未识别"并列入待确认，禁止跳过不记。

### LPU-RCN-002【M】规模度量
至少记录：源码文件数、代码总行数（排除 vendor/node_modules/target 等构建产物）、
最大文件 TOP10、SQL/DDL 文件数。用于 S2 的模块拆分粒度决策（见 RCN-005）。

### LPU-RCN-003【M】入口点全景扫描
扫描并登记全部入口类型：
- Web 路由注解（@RequestMapping / app.get / router.get 等）
- RPC/Service 暴露（dubbo/spring remoting）
- 定时任务（@Scheduled / cron / quartz 配置）
- 消息消费者（listener / consumer）
- CLI main 方法 / 启动脚本
- 前端页面路由（routes.js 等）

入口点是 F 维与 P 维分析的锚点。入口清单输出到 `F-function.md` 初始段。

### LPU-RCN-004【M】数据库连接方式确认
确认以下之一并记录到 config.json：直连（提供只读连接方式）/ 仅有 DDL 文件 / 完全不可达。
**不可达时 D 维分析降级为"DDL + 代码中的 SQL 推断"模式，且 database.md 必须标注推断来源。**

### LPU-RCN-005【M】模块拆分预案
依据目录结构 + RCN-002 规模，将项目拆成 3-10 个分析模块（业务域优先，技术分层其次），
写入 PROGRESS 的"模块清单"。拆分原则：单个模块单次会话可完成五维深潜（经验值 ≤ 50 个源文件）。

### LPU-RCN-006【R】历史线索挖掘
git log（提交频率热区、最后一次活跃时间）、注释中的 TODO/FIXME/HACK 密度、
遗留的 README 碎片。这些是 K 维和 debt-risks 的免费线索。无 git 历史时记录"无版本历史"。

### LPU-RCN-007【M】RECON 报告落盘
侦察完成时向 `.lpu/findings/` 写入 RECON 报告（技术栈/规模/入口清单/模块拆分/风险初判），
并更新 PROGRESS 至 S1。**侦察结论允许粗粒度，但必须存在**——没有 RECON 报告不得进入 S1。

### LPU-RCN-008【R】危险信号预登记
侦察中发现的危险信号（加密硬编码、生产连接串、明显的死代码区、超大文件）预先登记到
`X-conflicts.md` 备用段落，避免后续深潜时遗忘。
