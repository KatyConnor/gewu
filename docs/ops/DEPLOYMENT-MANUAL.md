# 格物平台完整部署手册

> **文档版本**：V2.0 | **更新日期**：2026-09-03 | **适用代码基线**：迁移 V37（commit `adfd828` 之后）
> **取代**：deploy/DEPLOYMENT.md 旧版（V1.x，含已移除的 RocketMQ 内容）
> **配套文档**：docs/ops/OPERATIONS-MANUAL.md（日常运维）、docs/ops/DISASTER-RECOVERY.md（容灾）、deploy/PERFORMANCE-GUIDE.md（性能）

---

## 1. 部署拓扑与端口总表

```
                        ┌────────────────────────────┐
   浏览器 ────────────► │ Ingress / Nginx（可选）     │
                        └──────┬─────────────────────┘
                               │
                        ┌──────▼──────────┐
                        │ gateway :8080   │ JWT 鉴权 / 限流(100次/60s) / 熔断
                        └──────┬──────────┘
               /api/**         │         │ /api/v1/sandboxes/**
                 ┌─────────────▼──┐   ┌──▼──────────────┐
                 │ interface      │   │ sandbox :8082   │ Docker 代码执行隔离
                 │ :8081(dev)     │   │ (独立进程/容器)  │ internal-api-key 鉴权
                 │ :8080(prod)    │   └─────────────────┘
                 │ actuator :9081 │
                 └──┬─────┬─────┬─┘
        ┌───────────┘     │     └───────────┬──────────────┬────────────┐
  ┌─────▼─────┐  ┌────────▼───────┐  ┌──────▼─────┐  ┌─────▼─────┐  ┌───▼────────┐
  │ MySQL 8.0 │  │ DragonflyDB    │  │ pgvector   │  │ MinIO     │  │ SearXNG    │
  │ :3306     │  │ (Redis协议:6379)│  │ PG16 :5432 │  │ :9000/9001│  │ :8888→8080 │
  │ gewu_dev  │  │ 缓存/限流/SSE   │  │ wenshi 向量 │  │ 文档存储   │  │ 联合搜索    │
  └───────────┘  └────────────────┘  └────────────┘  └───────────┘  └────────────┘

  前端 Next.js :5001(dev)/:3000(prod) —— 独立进程/容器
  可观测栈（可选）：Prometheus:9090 / Grafana:3001→3000 / Loki:3100 / Jaeger:16686(OTLP 4317/4318)
```

| 组件 | 监听端口 | 说明 |
|------|---------|------|
| gewu-gateway | 8080 | 统一入口（可选部署；直连 interface 亦可） |
| gewu-interface（主服务） | dev 8081 / **prod 8080** / actuator 9081 | 全部业务 Controller + agent-engine 装配；端口由 profile 决定 |
| gewu-sandbox | 8082 | 独立进程，需访问 Docker daemon（unix socket） |
| gewu-web（前端） | dev 5001 / prod 3000 | Next.js 14；`/api` 走 rewrites 代理或直连 |
| MySQL | 3306 | 库 gewu_dev/gewu_prod；Flyway 自动迁移 V1~V37 |
| DragonflyDB | 6379 | Redis 协议兼容（缓存/限流/分布式 SSE 频道） |
| pgvector (PG16) | 5432 | 独立 wenshi 库（向量检索/语义缓存） |
| MinIO | 9000 API / 9001 控制台 | bucket `gewu-documents` |
| SearXNG | 宿主 8888 → 容器 8080 | Wenshi 联网检索（JSON API） |
| Jaeger（可选） | 16686 UI / 4317 gRPC / 4318 HTTP | OTLP 链路追踪接收 |

> **已移除**：RocketMQ（S6 决策，docs/design/44 门 2）。升级旧部署时请 `docker-compose down` 移除旧 namesrv/broker/dashboard 容器。

---

## 2. 环境要求

| 项 | 最低 | 推荐 | 说明 |
|----|------|------|------|
| CPU / 内存 | 2C / 4G | 4C / 8G | 主服务 JVM -Xmx2g |
| 操作系统 | Linux（内核 ≥5.x） | Linux | 沙箱依赖 Docker |
| JDK | 21（Temurin） | 21 | 仅 jar 方式需要；容器镜像内置 |
| Docker + Compose | 24+ / v2 | 最新 | 中间件与沙箱 |
| Node.js / pnpm | 18+ / 8+ | 20 / 8+ | 仅前端构建机需要 |
| kubectl + Helm | 1.26+ / 3.9+ | 最新 | 仅 K8s 方式需要 |
| 磁盘 | 40G | 100G+ | MySQL 数据 + MinIO 对象 + 日志 |

**代码内模型文件**：`models/bge-small-zh-v1.5.onnx`（Wenshi 本地嵌入，384 维）——随仓库分发，jar 方式部署需确保工作目录可寻址。

---

## 3. 配置项总表（环境变量）

> Spring 环境变量与 yaml 键遵循松散绑定（`GEWU_CRYPTO_API_KEY_SECRET` ↔ `gewu.crypto.api-key-secret`）。下表为**生产必须复核**的清单。

### 3.1 数据与缓存

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `SPRING_DATASOURCE_URL` | jdbc:mysql://localhost:3306/gewu_dev | 主库连接（prod compose 指向 gewu_prod） |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | gewu / gewu123456 | **生产必须改密** |
| `SPRING_DATA_REDIS_HOST` / `PORT` | localhost / 6379 | DragonflyDB（Redis 协议） |
| `WENSHI_DB_URL` | jdbc:postgresql://localhost:5432/wenshi | 向量库（独立 PG） |
| `WENSHI_DB_USERNAME` / `PASSWORD` | wenshi / wenshi123456 | **生产必须改密** |

### 3.2 安全密钥（生产必须全部替换）

| 环境变量 | 要求 | 说明 |
|----------|------|------|
| `GEWU_SECURITY_JWT_SECRET` | ≥256 bit 随机串 | JWT HS256 签名密钥 |
| `GEWU_CRYPTO_API_KEY_SECRET` | 32 位 hex（SM4 密钥） | 模型供应商 API Key 加密存储；**更换后已录入的模型 Key 需在 `/models` 页面重录** |
| `GEWU_SECURITY_*`（密码策略等） | 见 application.yml `gewu.security` | 登录失败锁定等 |
| `JWT_SECRET`（gateway） | 与主服务一致 | 网关 JwtAuthFilter 使用 |

### 3.3 LLM 与 AI

| 环境变量 | 说明 |
|----------|------|
| `AI_API_KEY` / `gewu.ai.qwen.api-key` 等 | 兜底静态 Key；**推荐方式**：部署后在「模型配置」页面录入供应商 Key（SM4 加密入库，动态加载） |
| `GEWU_SEMANTIC_CACHE_ENABLED` | 语义缓存总开关（默认 true；代码生成场景建议关闭） |
| `GEWU_SEMANTIC_CACHE_THRESHOLD` | 相似度阈值（默认 0.95） |
| `GEWU_SEMANTIC_CACHE_DISABLED_AGENTS` | 按 agentId 关闭缓存的逗号列表 |
| `GEWU_WENSHI_LLM_DEFAULT-PROVIDER/MODEL` | Wenshi 链路兜底模型（默认 qwen / qwen-plus） |

### 3.4 行为开关与可观测

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `GEWU_SSE_DISTRIBUTED` | false | **多副本部署必须置 true**：SSE 跨实例广播 + HITL 决策回传（Redis Pub/Sub） |
| `OTEL_TRACING_ENABLED` | false | 链路追踪导出（K8s configmap 已置 true） |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | http://localhost:4318 | Jaeger OTLP 接收端 |
| `OTEL_TRACING_SAMPLING_PROBABILITY` | 1.0 | 采样率 |
| `SPRING_PROFILES_ACTIVE` | dev | **生产必须为 prod**（端口 8080 / 数据源等差异） |
| `MACHINE_ID` | -1 | 多实例雪花类序号（容器注入） |
| `GEWU_SEMANTIC_CACHE_*` / `agent.engine.*` | 见 §8 | 引擎预算/生命周期等已全部配置化 |

---

## 4. 部署方式 A：单机 docker-compose 生产部署（推荐起步）

### 4.1 准备

```bash
git clone <仓库地址> && cd gewu-platform
# 生产敏感参数（.env 或导出环境变量）
export MYSQL_ROOT_PASSWORD='<强随机>'
export MYSQL_PASSWORD='<强随机>'
export POSTGRES_PASSWORD='<强随机>'
export MINIO_ROOT_PASSWORD='<强随机>'
export GEWU_CRYPTO_API_KEY_SECRET='<32位hex>'   # SM4 密钥
export JWT_SECRET='<≥256bit 随机串>'
```

### 4.2 构建与启动

```bash
# 方式一：源码构建镜像（多阶段 Maven，产物为主服务 jar）
docker compose -f docker-compose.prod.yml build

# 启动全部（中间件 healthcheck 通过后主服务才启动，Flyway 自动迁移 V1→V37）
docker compose -f docker-compose.prod.yml up -d

# 观察迁移与启动日志
docker compose -f docker-compose.prod.yml logs -f gewu-platform
```

**首次启动验收**：
1. `docker compose -f docker-compose.prod.yml ps` — 全部 healthy；
2. `curl http://localhost:8080/actuator/health/readiness` — `{"status":"UP"}`；
3. MySQL 中确认 Flyway 表：`show tables like 'flyway_schema_history'` 且版本到 V37；
4. MinIO 控制台（:9001）创建 bucket `gewu-documents`（或用 mc：`mc mb local/gewu-documents`）。

### 4.3 沙箱服务（prod compose 未包含，单独部署）

```bash
# 构建沙箱 jar（或复用 CI 镜像）
mvn -pl gewu-sandbox -am package -DskipTests
# 启动（需本机 Docker daemon；internal key 与主服务一致）
java -jar gewu-sandbox/target/gewu-sandbox-1.0.0-SNAPSHOT.jar \
  --server.port=8082 \
  --gewu.sandbox.internal-key=<与主服务 gewu.sandbox.internal-key 相同> \
  > logs/gewu-sandbox.log 2>&1 &
```

### 4.4 前端部署

```bash
cd gewu-web
pnpm install && pnpm build
# 生产启动（3000 端口）；NEXT_PUBLIC_API_BASE 留空走同源 /api 反代
pnpm start -- -p 3000        # 或 pm2/systemd 托管
```

> 若前端与后端不同源部署，设置 `NEXT_PUBLIC_API_BASE=<后端地址>/api` 构建期注入，并在后端放行 CORS。

### 4.5 网关（可选）

多服务/多实例拓扑建议启用 gateway（JWT 校验前置 + 限流）：

```bash
mvn -pl gewu-gateway -am package -DskipTests
java -jar gewu-gateway/target/gewu-gateway-1.0.0-SNAPSHOT.jar --server.port=8080 \
  # 下游主服务地址按网关 application.yml 的路由配置调整
```

---

## 5. 部署方式 B：Kubernetes Helm 部署

```bash
# 0. 前置：集群内已有 MySQL/DragonflyDB/pgvector（或修改 values 指向外部托管）；
#    首次部署前 helm lint 校验
helm lint deploy/helm/gewu-platform

# 1. 部署（namespace gewu）
helm upgrade --install gewu deploy/helm/gewu-platform \
  --namespace gewu --create-namespace \
  --set global.imageRegistry=ghcr.io/<org> \
  --set interface.image.tag=<tag> \
  --set secret.mysqlPassword='<强随机>' \
  --set secret.jwtSecret='<≥256bit>' \
  --set secret.sm4Key='<32位hex>' \
  --wait --timeout 8m

# 2. 验收
kubectl -n gewu rollout status deployment/gewu-gewu-interface --timeout=300s
kubectl -n gewu get pods,svc,ingress

# 3. 回滚
helm rollback gewu --namespace gewu
```

**多副本要点**（values.interface.replicas > 1 时）：
- `config.sseDistributed` 必须为 `"true"`（默认已置）——否则 HITL 审批与协作广播会投递到错误实例；
- 沙箱 Deployment 挂载了宿主机 `docker.sock`，建议使用独立节点池 + `nodeSelector`；
- Ingress 已内置 SSE 注解（`proxy-buffering: off`、read/send-timeout 3600s）。

**镜像构建**：CI（`.github/workflows/ci-cd.yml`）在 main push 时构建 `gewu/gewu-platform` 镜像推送 ghcr.io；**gateway/sandbox/frontend 镜像需按同模式扩展 CI**（当前 Dockerfile 仅打包主服务 jar，见 §12 已知边界）。

---

## 6. 部署方式 C：本地开发环境

```bash
# 1. 中间件（MySQL/pgvector/MinIO/Dragonfly/SearXNG）
docker compose up -d

# 2. 主服务（dev profile，端口 8081，Flyway 自动迁移）
mvn package -DskipTests
./start-app.sh            # 或 java -jar gewu-interface/target/*.jar

# 3. 沙箱 + 网关（可选）
./start-sandbox.sh && ./start-gateway.sh

# 4. 前端（5001，/api 经 rewrites 代理到 8081）
cd gewu-web && pnpm install && pnpm dev

# 5. 默认账号：首次通过 POST /api/v1/auth/register 注册（首个注册用户建议授予 ADMIN 角色种子）
```

---

## 7. 数据库迁移（Flyway）

- 迁移脚本位于 `gewu-interface/src/main/resources/db/migration/`（**V1 ~ V37**），主服务启动时自动执行；
- **铁律**：已发布的迁移文件禁止修改（V37 曾 drop 三张空壳表，结构可从 git 历史找回）；新增变更一律追加 `V<n+1>__<desc>.sql`；
- 失败处理：`flyway repair` 后修复脚本再重启；测试环境（H2 schema.sql）与生产 MySQL 的 schema 需同步维护（历史上出现过 V33/V34 漂移，新增列时**两处都要改**）；
- 回滚：Flyway 社区版不支持 down——回滚=部署上一版本代码 + 手写逆向迁移。

---

## 8. 关键行为配置（agent.engine.* / gewu.*）

| 配置组 | 关键项 | 默认 | 说明 |
|--------|--------|------|------|
| `agent.engine.llm` | request-timeout / connect-timeout | 120s / 30s | LLM 同步超时 |
| `agent.engine.budget` | time-budget-ms、L1/L3 倍率 | 300s；÷5、×3 等 | 四维预算（token/时间/成本/轮次） |
| `agent.engine.lifecycle` | heartbeat / global-timeout | 60s / 300s | 编排实例守护 |
| `agent.engine.engine` | max-tool-rounds / default-history-limit | 10 / 50 | ReAct 循环上限 / 历史条数 |
| `agent.engine.tool` | allowed-hosts | 空=拒绝全部 | HTTP 工具 SSRF 白名单（**生产按需配置**） |
| `gewu.wenshi.routing` | chat / stream | legacy / wenshi | 双引擎路由开关（收敛决策见 docs/design/44） |
| `gewu.sandbox` | api url / internal-key | :8082 | 沙箱内部通信密钥 |
| `gewu.security.jwt` | 过期 30min/7d | - | access/refresh |
| `gewu.session.context-compress-mode` | llm / truncate | llm | 上下文压缩策略 |

完整清单见 `gewu-interface/src/main/resources/application.yml`（带注释）与 `AgentEngineProperties.java`。

---

## 9. 可观测栈部署（可选但强烈建议）

```bash
cd deploy/monitoring && docker compose up -d
# Prometheus:9090  Grafana:3001(admin/admin123)  Loki:3100  Jaeger UI:16686
# Alertmanager：Slack/PagerDuty 渠道需在 alertmanager/config.yml 填入真实 webhook
```

- 应用侧配合：`OTEL_TRACING_ENABLED=true`（链路）+ `/actuator/prometheus`（指标抓取）+ Promtail 采集 `/var/log/gewu/*.log`；
- 告警规则 9 条（Pod 重启/P95 延迟/限流/DB 健康/JVM 堆/磁盘/预算熔断/慢任务/低缓存命中）见 `deploy/monitoring/prometheus/rules/`；
- Grafana 预置看板：`gewu-platform.json`（技术指标）+ `agent-dashboard.json`（Agent 业务指标，含缓存命中率/模型路由/预算熔断）。

---

## 10. CI/CD

| 流水线 | 状态 | 阶段 |
|--------|------|------|
| GitHub Actions（**主推**） | 构建可用+部署真实化 | mvn verify（含覆盖率门禁）→ Trivy 扫描 → 镜像推 ghcr → helm 部署 staging/production（需 repo secrets：`STAGING_KUBECONFIG` / `PRODUCTION_KUBECONFIG`，base64 编码） |
| GitLab CI / Jenkins | 备选 | 语句完整，依赖集群 context |

---

## 11. 发布 / 回滚 / 备份

```bash
# 发布（脚本封装）           # 或手动
./deploy/scripts/deploy.sh   docker compose -f docker-compose.prod.yml up -d --build

# 回滚
./deploy/scripts/rollback.sh              # helm rollback gewu（K8s）
                                          # compose：切回上一 tag 重新 up

# 备份（建议 cron 化）
./deploy/scripts/backup.sh                # mysqldump + MinIO 数据
```

---

## 12. 健康检查与上线验收清单

```bash
# 进程健康
curl -f http://<host>:<port>/actuator/health/liveness    # UP
curl -f http://<host>:<port>/actuator/health/readiness   # UP
./deploy/scripts/health-check.sh                          # 封装脚本
```

| # | 验收项 | 通过标准 |
|---|--------|---------|
| 1 | 中间件全部 healthy | compose ps / kubectl get pods |
| 2 | Flyway 版本至 V37 | flyway_schema_history 最大 version |
| 3 | 登录 + 首页 | POST /api/v1/auth/login 返回 10000 |
| 4 | **AI 流式对话** | 会话发消息，SSE 逐字输出（首字节 <2s） |
| 5 | **文件卡片** | 触发带 file 事件的消息，前端卡片渲染（回归 chat.ts 修复） |
| 6 | 模型供应商连通 | /models 页面录入 Key 后测试对话 |
| 7 | 沙箱执行 | 开发工作台执行 ls 命令返回结果 |
| 8 | MinIO 上传 | 工作台上传文件成功且预签名下载可用 |
| 9 | 编排执行（含 HITL） | 提交含 HUMAN 节点的图，审批后恢复 |
| 10 | 可观测 | Grafana 看板有数据；Jaeger 可见 llm_call 链路 |

---

## 13. 常见问题排查（FAQ）

| 症状 | 原因 | 处置 |
|------|------|------|
| **SSE 流式不逐字输出（一次性吐完）** | 反向代理缓冲响应 | Nginx 加 `proxy_buffering off` + 长超时（K8s ingress 已内置注解）；确认前端走直连地址（NEXT_PUBLIC_API_BASE） |
| 沙箱创建失败 / exec 无响应 | docker.sock 权限或未挂载 | 容器挂载 `/var/run/docker.sock` 并 `groupadd` 对应 gid；确认 internal-key 两端一致 |
| 启动报 Flyway 校验失败 | 迁移文件被修改/校验和漂移 | `flyway repair`；严禁改已发布迁移 |
| 登录后所有请求 401 | JWT 密钥两端不一致或已轮换 | 统一 gateway 与 interface 的密钥；重新登录 |
| 模型对话报认证失败 | SM4 密钥更换后旧密文无法解密 | 用新密钥重启后在「模型配置」重录 API Key |
| 多副本下 HITL 审批不恢复 / 广播收不到 | `GEWU_SSE_DISTRIBUTED` 未开 | 置 true 并重启（Redis Pub/Sub 需 Dragonfly/Redis 可达） |
| 语义缓存永不命中 | 向量库不可达或 agent 在禁用列表 | 检查 pgvector 连通与 `GEWU_SEMANTIC_CACHE_*` 配置；看 agent-cache.miss 指标 |
| 工具 HTTP 调用全部被拒 | SSRF 白名单为空（默认拒绝全部） | `agent.engine.tool.allowed-hosts` 配置业务域名 |
| Wenshi 检索无结果 | SearXNG 不可达或 ONNX 模型缺失 | 检查 `SEARXNG_BASE_URL` 与 `models/bge-small-zh-v1.5.onnx` 路径 |
| 前端 401 死循环 | token 过期且 refresh 失败 | 清 localStorage 重登；检查 refresh 接口在网关 skip-paths |

---

## 14. 安全加固清单（上线前必查）

- [ ] 全部默认密码已替换（MySQL/PG/MinIO/JWT/SM4）——`docker-compose.prod.yml` 中的默认值仅用于首次拉起；
- [ ] MySQL/PG/MinIO 端口不对公网暴露（仅容器网络/内网）；
- [ ] 网关限流阈值按业务复核（默认 100 次/60s/用户）；
- [ ] `agent.engine.tool.allowed-hosts` 按需最小化配置；
- [ ] 审计链校验纳入例行巡检（`GET /api/v1/audit-chain/verify`）；
- [ ] 密码策略与账号锁定策略确认（`gewu.security.password`）；
- [ ] Trivy 扫描纳入 CI（已内置，CRITICAL/HIGH 阻断）。

---

## 15. 已知边界（部署视角，如实声明）

1. **镜像覆盖**：当前 Dockerfile 仅打包主服务 jar；gateway/sandbox/frontend 的镜像需按同模式扩展 CI（Helm values 已预留仓库位）；
2. **Helm 未经实机 lint**：本机无 helm，首次部署前先 `helm lint deploy/helm/gewu-platform`；
3. **压测基线未跑**：jmeter 脚本就绪（`deploy/jmeter/run-perf-test.sh`），首轮基线需部署后执行；
4. **prod compose 的 MinIO bucket 需手工创建**（首次），或后续在应用侧加启动建桶；
5. **OceanBase 兼容**：compose 注释提及生产切 OceanBase 4.4.2 的专项（docs/archive/SP-01）尚未执行，当前以 MySQL 8.0 为准。
