# 格物平台 统一部署运维指南 V1.0

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 统一部署运维指南 |
| 版本 | V1.2 |
| 创建日期 | 2026-07-08 |
| 最后更新 | 2026-07-08 |
| 文档状态 | 初稿 |
| 更新说明 | V1.1: 新增关键业务表备份优先级（含 sandbox_audit_log/workflow_* 表）；新增参考文档 27-31 V1.2: 新增 §3.7 容量规划（含用户规模/吞吐量/存储/连接数/带宽 5 个维度预估） |
| 项目根目录 | /home/wnn/devcode/ai-code/gewu-platform |
| 参考项目 | OpenCode v1.17.14 — GitHub Actions CI/CD、Docker 容器化、SST Serverless 部署 |

---

## 0. 参考对照：OpenCode 部署借鉴

在深入格物平台部署细节之前，先梳理 OpenCode 的部署模式作为参照基准。OpenCode 是单用户/单机部署的 AI 编程助手，格物是面向企业的多租户集群平台。虽然规模不同，但 OpenCode 的 CI/CD 工程化实践和容器化方案值得借鉴。

### 0.1 OpenCode 部署形态对比

| 维度 | OpenCode | 格物平台 | 借鉴价值 |
|------|----------|----------|----------|
| 部署规模 | 单机单用户 | 多节点集群 | 开发环境可复用 OpenCode 模式 |
| 数据库 | SQLite 单文件（无独立 DB 服务） | OceanBase 分布式集群 | DB-less 思路适用于本地开发沙箱 |
| 容器化 | Docker 多阶段构建（Alpine + musl） | 各模块独立 Dockerfile | Dockerfile 分层、多架构构建策略 |
| 编排 | K8s 基础 | K8s 完整集群 | OpenCode 的 K8s manifest 模式可参考 |
| CI/CD | GitHub Actions（SST Serverless + AWS） | Jenkins / GitLab CI | Pipeline 阶段划分模式（build→test→deploy） |
| 监控 | Sentry + OpenTelemetry | Nightingale + Prometheus + Grafana | Sentry 错误追踪思路可补充到格物 |
| 前端部署 | Vite 构建静态资源 → CDN | Nginx + 后端打包 | 静态资源缓存策略可复用 |
| 多架构 | linux/amd64 + arm64 | 鲲鹏/飞腾/龙芯全 CPU 架构 | OpenCode 的 `TARGETARCH` 多阶段构建模式 |
| 构建缓存 | Turborepo 缓存 + GitHub Actions cache | Maven 依赖缓存 | CI 构建缓存策略可复用 |

### 0.2 从 OpenCode 到格物的演进路径

对于本地开发环境和单机测试场景，格物可以直接借鉴 OpenCode 的简化部署模式：

```
第一阶段（单机开发） →  第二阶段（集群测试）  →  第三阶段（信创生产）
  借鉴 OpenCode          完整 K8s 部署          全信创栈
  Docker Compose         Helm Charts           国产 OS + CPU
  SQLite / 嵌入式DB       OceanBase             国产 JDK
  简化监控               Nightingale            国密 TLS
```

---

## 1. 部署架构总览

### 1.1 整体架构

```
                              ┌──────────────────────────────────┐
                              │          负载均衡层               │
                              │  ┌────────────┐ ┌────────────┐  │
                              │  │  Nginx     │ │  HAProxy   │  │
                              │  │  (L7)      │ │  (L4)      │  │
                              │  └────────────┘ └────────────┘  │
                              └──────────────┬───────────────────┘
                                             │
                              ┌──────────────▼───────────────────┐
                              │          API 网关层               │
                              │  自研 Java/Netty 网关 (3+ 节点)   │
                              │  国密 TLS / 限流 / 熔断 / 路由     │
                              └──────────────┬───────────────────┘
                                             │
              ┌──────────────────────────────┼──────────────────────────────┐
              │               ┌──────────────▼──────────────┐               │
              │               │      Kubernetes 集群          │               │
              │               │  ┌──── Master 节点 (3) ────┐ │               │
              │               │  │ API Server / Controller  │ │               │
              │               │  │ Scheduler / etcd         │ │               │
              │               │  └─────────────────────────┘ │               │
              │               │  ┌──── Worker 节点 (5+) ────┐ │               │
              │               │  │ gewu-interface  (3 副本)  │ │               │
              │               │  │ gewu-application (3 副本)  │ │               │
              │               │  │ gewu-gateway     (3 副本)  │ │               │
              │               │  │ gewu-sandbox     (5 副本)  │ │               │
              │               │  │ gewu-web         (2 副本)  │ │               │
              │               │  └─────────────────────────┘ │               │
              │               └──────────────────────────────┘               │
              │                            │                                  │
              └──────────────┬─────────────┼──────────────┬──────────────────┘
                             │             │              │
                ┌────────────▼──┐ ┌────────▼──────┐ ┌────▼────────────┐
                │  OceanBase    │ │ DragonflyDB   │ │  RocketMQ      │
                │  集群 (3 节点)  │ │ 缓存集群 (3 节点)│ │  集群 (3 节点)  │
                │  OBProxy 分发  │ │ 主从 + 哨兵    │ │  NameServer    │
                └───────────────┘ └───────────────┘ └────────────────┘
                             │             │              │
                ┌────────────▼────────────▼──────────────▼────────────┐
                │                 监控与基础设施                        │
                │  Nightingale + Prometheus + Grafana + VictoriaMetrics │
                │  ELK 日志 / Nacos 配置中心 / Jenkins CI/CD           │
                └──────────────────────────────────────────────────────┘
```

### 1.2 模块与服务映射

| 模块 | 服务名 | 部署副本 | 端口 | 说明 |
|------|--------|---------|------|------|
| **API 网关** | gewu-gateway | 3+ | 8443 (HTTPS), 8080 (HTTP) | 自研 Netty 网关，国密 TLS |
| **接口层** | gewu-interface | 3+ | 8081 | Spring Boot REST API |
| **应用层** | gewu-application | 3+ | 8082 | CQRS 命令/查询处理 |
| **沙箱** | gewu-sandbox | 5+ | 9090 | Firecracker/gVisor 沙箱 |
| **前端** | gewu-web | 2+ | 80 | React SPA，Nginx 托管 |
| **Nacos** | nacos | 3 | 8848 | 配置中心 HA 集群 |
| **OceanBase** | obcluster | 3 | 2881 | 分布式数据库集群 |
| **DragonflyDB** | dragonfly | 3 | 6379 | 内存缓存集群 |
| **RocketMQ** | rocketmq | 3+ | 9876, 10911 | 消息队列集群 |
| **Nightingale** | n9e | 2 | 17000 | 监控平台 |
| **Prometheus** | prometheus | 1 | 9090 | 时序数据采集 |
| **Grafana** | grafana | 1 | 3000 | 可视化仪表盘 |
| **VictoriaMetrics** | victoria-metrics | 2 | 8428 | 时序数据库 |

### 1.3 网络拓扑

```
用户 → CDN → Nginx/HAProxy (L4+L7) → 自研网关 (负载均衡) → Service Mesh
                                                                  │
                    ┌─────────────────────────────────────────────┤
                    │              │               │              │
              gewu-interface  gewu-application  gewu-sandbox  gewu-web
                    │              │               │
               OceanBase      DragonflyDB      RocketMQ
```

---

## 2. 环境要求

### 2.1 开发环境

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| **JDK** | 21+（推荐 Eclipse Temurin / 龙芯 JDK） | 必须 JDK 21 LTS |
| **Maven** | 3.8+ | 多模块构建 |
| **Node.js** | 18+ | 前端构建 |
| **Docker** | 24+ | 容器化运行 |
| **Docker Compose** | 2.20+ | 本地编排 |
| **IntelliJ IDEA** | 2023.2+ | 推荐 IDE |
| **Git** | 2.40+ | 版本控制 |
| **操作系统** | macOS 14+ / Ubuntu 22.04+ | 开发机 |

### 2.2 生产环境

| 组件 | 版本要求 | 数量 | 规格 |
|------|---------|------|------|
| **K8s 集群** | 1.24+ | 8+ 节点 | Master: 4C8G × 3, Worker: 16C32G × 5+ |
| **OceanBase** | 4.2+ | 3 节点 | 32C64G × 3, SSD 1TB+ |
| **DragonflyDB** | 1.27+ | 3 节点 | 8C16G × 3 |
| **RocketMQ** | 5.1+ | 3 节点 | 8C16G × 3 |
| **Nginx** | 1.24+ | 2 节点 | 4C8G × 2 |
| **Nightingale** | 7.x | 2 节点 | 8C16G × 2 |
| **VictoriaMetrics** | 1.93+ | 2 节点 | 8C16G × 2 |
| **Grafana** | 10.x | 1 节点 | 4C8G × 1 |
| **Nacos** | 2.3+ | 3 节点 | 4C8G × 3 |
| **ELK** | 8.x | 3 节点 | 8C16G × 3 |
| **Jenkins** | 2.440+ | 1 节点 | 8C16G × 1 |
| **容器镜像仓库** | Harbor 2.8+ | 2 节点 | 8C16G × 2, 存储 5TB+ |
| **负载均衡器** | HAProxy 2.8+ | 2 节点 | 4C8G × 2 |

### 2.3 信创环境要求

#### 硬件要求

| 类别 | 支持型号 | 架构 |
|------|---------|------|
| **CPU** | 鲲鹏 920 / 飞腾 S2500 / 龙芯 3A6000+ / 海光 3号 | ARM64 / LoongArch / x86-64 |
| **服务器** | 华为 Taishan / 浪潮 KOS / 联想 ThinkSystem 信创系列 | 适配中 |
| **网络** | 千兆/万兆网卡（国产芯片优先） | - |

#### 操作系统要求

| 操作系统 | 版本 | 架构 |
|----------|------|------|
| **麒麟 V10** | SP1+ | ARM64, x86-64 |
| **统信 UOS** | V20 1060+ | ARM64, LoongArch |
| **华为 EulerOS** | 2.10+ | ARM64 |

#### JDK 要求

| JDK 发行版 | 版本 | 架构 |
|-----------|------|------|
| **龙芯 JDK** | 21+ | LoongArch64 |
| **鲲鹏 JDK** (毕昇) | 21+ | ARM64 |
| **飞腾 JDK** | 21+ | ARM64 |
| **海光 JDK** | 21+ | x86-64 |

#### 容器运行时（信创）

| 组件 | 版本 | 说明 |
|------|------|------|
| **iSulad** | 2.1+ | 华为轻量容器引擎，替代 Docker |
| **KubeEdge** | 1.16+ | 边缘计算扩展 |
| **containerd** | 1.7+ | 通用容器运行时（可选） |

#### 数据库与中间件（信创）

| 组件 | 信创替代 | 说明 |
|------|---------|------|
| **数据库** | OceanBase 4.2+（必选）| 国产分布式数据库 |
| **备选数据库** | 达梦 DM8 / 人大金仓 KingbaseES V8 | 信创目录产品 |
| **缓存** | DragonflyDB（推荐）/ 自有 Redis | Dragonfly 采用 BSD 3-Clause 许可 |
| **消息队列** | RocketMQ 5.1+（必选）| Apache 顶级项目，国产主导 |
| **国密算法** | Bouncy Castle + 国密 SM2/SM3/SM4 | 通过国密局认证 |
| **监控** | Nightingale（推荐，CCF ODC 托管）| 国产开源监控平台 |

---

## 3. 容器化部署

### 3.1 模块镜像结构

每个 Spring Boot 模块独立构建镜像，采用多阶段构建模式（参考 OpenCode 的 alpine + targetarch 策略）：

```
gewu-gateway/           ← 自研 API 网关
gewu-interface/         ← REST API 接口层
gewu-application/       ← 应用层服务
gewu-common/            ← 公共库（作为依赖，不单独部署）
gewu-domain/            ← 领域层（作为依赖，不单独部署）
gewu-infrastructure/    ← 基础设施层（作为依赖，不单独部署）
gewu-sandbox/           ← 沙箱运行时
gewu-web/               ← 前端 SPA（Nginx 托管）
gewu-desktop/           ← 桌面端（Electron，独立分发）
```

### 3.2 Dockerfile 示例

#### gewu-interface/Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY gewu-common/pom.xml gewu-common/
COPY gewu-domain/pom.xml gewu-domain/
COPY gewu-infrastructure/pom.xml gewu-infrastructure/
COPY gewu-interface/pom.xml gewu-interface/
RUN mvn dependency:go-offline -pl gewu-interface -am -B

COPY . .
RUN mvn package -pl gewu-interface -am -DskipTests -B

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S gewu && adduser -S gewu -G gewu
WORKDIR /app
COPY --from=build /app/gewu-interface/target/*.jar app.jar
USER gewu
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### gewu-gateway/Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY gewu-common/pom.xml gewu-common/
COPY gewu-gateway/pom.xml gewu-gateway/
RUN mvn dependency:go-offline -pl gewu-gateway -am -B

COPY . .
RUN mvn package -pl gewu-gateway -am -DskipTests -B

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S gewu && adduser -S gewu -G gewu
WORKDIR /app
COPY --from=build /app/gewu-gateway/target/*.jar app.jar
# 国密证书
COPY --from=build /app/gewu-gateway/src/main/resources/certs /app/certs
USER gewu
EXPOSE 8443 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=${SPRING_PROFILE}"]
```

#### gewu-sandbox/Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY gewu-sandbox/pom.xml gewu-sandbox/
RUN mvn dependency:go-offline -pl gewu-sandbox -am -B

COPY . .
RUN mvn package -pl gewu-sandbox -am -DskipTests -B

FROM alpine:3.19 AS runtime
RUN apk add --no-cache libgcc libstdc++ iptables ip6tables
COPY --from=build /app/gewu-sandbox/target/*.jar /app/app.jar
# Firecracker 二进制
COPY --from=firecracker-builder /firecracker /usr/local/bin/firecracker
COPY --from=firecracker-builder /jailer /usr/local/bin/jailer
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

#### gewu-web/Dockerfile（前端）

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY gewu-web/package.json gewu-web/pnpm-lock.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile
COPY gewu-web/ .
RUN pnpm build

FROM nginx:alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY gewu-web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

### 3.3 多架构构建

参考 OpenCode 的 `TARGETARCH` 模式，在信创环境需要同时支持 ARM64（鲲鹏/飞腾）和 LoongArch（龙芯）：

```bash
# 构建并推送多架构镜像
docker buildx build \
  --platform linux/amd64,linux/arm64,linux/loongarch64 \
  -t harbor.gewu.com/gewu/gewu-interface:1.0.0 \
  -f gewu-interface/Dockerfile \
  --push .
```

### 3.4 K8s 清单

#### Deployment — gewu-interface

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gewu-interface
  namespace: gewu
  labels:
    app: gewu-interface
    tier: backend
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  selector:
    matchLabels:
      app: gewu-interface
  template:
    metadata:
      labels:
        app: gewu-interface
        tier: backend
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8081"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: gewu-interface
                topologyKey: kubernetes.io/hostname
      containers:
        - name: gewu-interface
          image: harbor.gewu.com/gewu/gewu-interface:1.0.0
          imagePullPolicy: Always
          ports:
            - containerPort: 8081
              protocol: TCP
          env:
            - name: SPRING_PROFILE
              value: "production"
            - name: DB_HOST
              valueFrom:
                configMapKeyRef:
                  name: gewu-config
                  key: db.host
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: gewu-secret
                  key: db.password
          resources:
            requests:
              cpu: 1000m
              memory: 2Gi
            limits:
              cpu: 2000m
              memory: 4Gi
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 60
            periodSeconds: 15
            timeoutSeconds: 5
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 3
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 15
            periodSeconds: 5
            failureThreshold: 30
          volumeMounts:
            - name: config
              mountPath: /app/config
              readOnly: true
            - name: logs
              mountPath: /var/log/gewu
      volumes:
        - name: config
          configMap:
            name: gewu-config
        - name: logs
          emptyDir: {}
```

#### Service — gewu-interface

```yaml
apiVersion: v1
kind: Service
metadata:
  name: gewu-interface
  namespace: gewu
  labels:
    app: gewu-interface
spec:
  selector:
    app: gewu-interface
  ports:
    - name: http
      port: 8081
      targetPort: 8081
      protocol: TCP
  type: ClusterIP
```

#### ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: gewu-config
  namespace: gewu
data:
  application.yml: |
    spring:
      datasource:
        url: jdbc:oceanbase://${DB_HOST}:2881/gewu
        driver-class-name: com.oceanbase.jdbc.Driver
        hikari:
          maximum-pool-size: 50
          minimum-idle: 10
          idle-timeout: 300000
      data:
        redis:
          host: dragonfly
          port: 6379
          cluster:
            nodes:
              - dragonfly-0.dragonfly:6379
              - dragonfly-1.dragonfly:6379
              - dragonfly-2.dragonfly:6379
    rocketmq:
      name-server: rocketmq-namesrv:9876
      producer:
        group: gewu-producer
    app:
      gateway:
        url: http://gewu-gateway:8080
```

#### Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: gewu-secret
  namespace: gewu
type: Opaque
stringData:
  db.password: "${DB_PASSWORD}"
  redis.password: "${REDIS_PASSWORD}"
  jwt.secret: "${JWT_SECRET}"
  gm.sm2.private-key: "${SM2_PRIVATE_KEY}"
  gm.sm4.key: "${SM4_KEY}"
```

### 3.5 Helm Chart 结构

```
charts/gewu/
├── Chart.yaml                  # 版本信息
├── values.yaml                 # 默认值
├── values-production.yaml      # 生产环境覆盖
├── values-staging.yaml         # 预发环境覆盖
├── values-xinchuang.yaml       # 信创环境覆盖
├── templates/
│   ├── _helpers.tpl            # 模板辅助函数
│   ├── deployment-interface.yaml
│   ├── deployment-application.yaml
│   ├── deployment-gateway.yaml
│   ├── deployment-sandbox.yaml
│   ├── deployment-web.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── hpa.yaml                # 水平自动伸缩
│   ├── pdb.yaml                # Pod 中断预算
│   ├── network-policy.yaml     # 网络策略
│   └── ingress.yaml
├── charts/
│   ├── oceanbase/
│   ├── dragonflydb/
│   └── rocketmq/
└── ci/
    └── values-ci.yaml
```

#### values.yaml 核心配置

```yaml
global:
  namespace: gewu
  registry: harbor.gewu.com
  imagePullPolicy: Always

interface:
  replicaCount: 3
  image:
    repository: gewu/gewu-interface
    tag: 1.0.0
  resources:
    requests:
      cpu: 1
      memory: 2Gi
    limits:
      cpu: 2
      memory: 4Gi
  service:
    port: 8081

gateway:
  replicaCount: 3
  image:
    repository: gewu/gewu-gateway
    tag: 1.0.0
  resources:
    requests:
      cpu: 2
      memory: 4Gi
    limits:
      cpu: 4
      memory: 8Gi
  service:
    port: 8080
    sslPort: 8443
  tls:
    enabled: true
    certManager: true

sandbox:
  replicaCount: 5
  image:
    repository: gewu/gewu-sandbox
    tag: 1.0.0
  resources:
    requests:
      cpu: 2
      memory: 4Gi
    limits:
      cpu: 4
      memory: 8Gi

web:
  replicaCount: 2
  image:
    repository: gewu/gewu-web
    tag: 1.0.0
  resources:
    requests:
      cpu: 500m
      memory: 512Mi
    limits:
      cpu: 1
      memory: 1Gi

autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
  targetMemoryUtilizationPercentage: 80

ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
  hosts:
    - host: api.gewu.com
      paths:
        - /api
        - /ws
```

### 3.6 资源配额规划

| 服务 | request CPU | request Mem | limit CPU | limit Mem | 推荐副本 |
|------|-----------|-----------|---------|---------|---------|
| gewu-gateway | 2 core | 4 GiB | 4 core | 8 GiB | 3 |
| gewu-interface | 1 core | 2 GiB | 2 core | 4 GiB | 3 |
| gewu-application | 1 core | 2 GiB | 2 core | 4 GiB | 3 |
| gewu-sandbox | 2 core | 4 GiB | 4 core | 8 GiB | 5 |
| gewu-web | 0.5 core | 512 MiB | 1 core | 1 GiB | 2 |
| nacos | 1 core | 2 GiB | 2 core | 4 GiB | 3 |

### 3.7 容量规划

#### 用户规模与资源预估

| 用户规模 | API QPS | 活跃会话 | 沙箱并发 | DB 存储/天 | 推荐集群规模 |
|---------|---------|---------|---------|-----------|------------|
| **开发阶段** (< 10 人) | 50 | 10 | 5 | 100 MB | 2 节点 (4C8G × 2) |
| **MVP 阶段** (< 100 用户) | 500 | 100 | 50 | 1 GB | 5 节点 (8C16G × 5) |
| **成长阶段** (< 500 用户) | 2000 | 500 | 200 | 5 GB | 8 节点 (16C32G × 8) |
| **成熟阶段** (< 2000 用户) | 5000 | 2000 | 500 | 20 GB | 15 节点 (32C64G × 15) |

#### 吞吐量预估

| 服务 | 单副本 QPS | 副本数 | 总容量 | 扩容阈值 | 扩容策略 |
|------|-----------|--------|--------|---------|---------|
| gewu-gateway | 5000 req/s | 3 | 15000 req/s | CPU > 70% | HPA max 10 |
| gewu-interface | 2000 req/s | 3 | 6000 req/s | CPU > 70% | HPA max 8 |
| gewu-application | 1000 req/s | 3 | 3000 req/s | CPU > 70% | HPA max 8 |
| gewu-sandbox | 100 沙箱/节点 | 5 | 500 沙箱 | 池利用率 > 80% | 手动扩容 |
| gewu-web | 500 concurrent | 2 | 1000 concurrent | CPU > 70% | HPA max 5 |

#### 存储增长预估

| 数据类别 | 月增长 (100 用户) | 月增长 (500 用户) | 月增长 (2000 用户) | 保留期 |
|---------|-----------------|-----------------|------------------|--------|
| 会话消息 | 5 GB | 25 GB | 100 GB | 90 天 |
| 会话事件 (Event Sourcing) | 10 GB | 50 GB | 200 GB | 90 天 |
| 沙箱审计日志 | 2 GB | 10 GB | 40 GB | 180 天 |
| 工作流审计日志 | 1 GB | 5 GB | 20 GB | 180 天 |
| 工具执行日志 | 3 GB | 15 GB | 60 GB | 30 天 |
| 监控指标 | 2 GB | 10 GB | 40 GB | 30 天 |
| **合计** | **23 GB/月** | **115 GB/月** | **460 GB/月** | — |

#### 数据库连接数预估

| 服务 | 最小连接数/副本 | 最大连接数/副本 | 副本数 | 峰值总连接数 |
|------|---------------|---------------|--------|------------|
| gewu-gateway | 20 | 50 | 3 | 150 |
| gewu-interface | 30 | 100 | 3 | 300 |
| gewu-application | 20 | 50 | 3 | 150 |
| gewu-sandbox | 10 | 20 | 5 | 100 |
| Nacos | 10 | 20 | 3 | 60 |
| **合计** | — | — | — | **760** |

> OBProxy 默认连接数上限 2000，3 节点 OB 集群可支撑 6000 并发连接，760 峰值连接在安全范围内。

#### 带宽预估

| 场景 | 带宽需求 | 说明 |
|------|---------|------|
| API 请求 (正常负载) | 100 Mbps | HTTP JSON 请求/响应 |
| SSE 流式推送 | 500 Mbps | LLM 流式响应峰值带宽 |
| 镜像拉取 (部署时) | 1 Gbps | 首次部署/滚动更新时 |
| 沙箱镜像 | 100 Mbps (持续) | Firecracker rootfs 拉取 |
| 数据库复制 | 1 Gbps | OB 节点间日志同步 |
| **合计 (峰值)** | **2.7 Gbps** | 建议万兆网络 |

## 4. API 网关部署

### 4.1 自研 Java/Netty 网关架构

格物 API 网关基于 Spring WebFlux + Reactor Netty 自研，满足信创自主可控要求。参考 OpenCode 使用 Hono + Effect 构建轻量 HTTP 服务的思路，但格物采用更成熟的 Netty 生态以满足企业级 QPS 需求。

```
┌──────────────────────────────────────────────────────────┐
│                  自研 API 网关架构                        │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │  路由层    │  │  过滤层   │  │  限流层   │  │  熔断层   │ │
│  │  Route    │  │  Filter  │  │  Rate    │  │  Circuit │ │
│  │  Matcher  │  │  Chain   │  │  Limiter │  │  Breaker │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │  认证层    │  │  国密TLS  │  │  负载均衡 │  │  协议转换 │ │
│  │  Auth    │  │  GM SSL  │  │  Load    │  │  Protocol│ │
│  │          │  │          │  │  Balance │  │  Trans   │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │
└──────────────────────────────────────────────────────────┘
```

### 4.2 国密 TLS 配置

```yaml
# gateway 国密 TLS 配置
server:
  port: 8080

gewu:
  gateway:
    ssl:
      enabled: true
      port: 8443
      # 国密双证书：签名证书 + 加密证书
      gm:
        sign-cert: classpath:certs/gm/sign.cert.pem
        sign-key: classpath:certs/gm/sign.key.pem
        enc-cert: classpath:certs/gm/enc.cert.pem
        enc-key: classpath:certs/gm/enc.key.pem
        # SM2 密码套件
        ciphers:
          - TLCP_SM_SM2DHE_SM4_SM3
          - TLCP_SM_SM2SIGN_SM4_SM3
      # 兼容 TLS 1.3（非信创客户端降级）
      fallback:
        enabled: true
        cert: classpath:certs/tls/server.crt
        key: classpath:certs/tls/server.key
```

### 4.3 限流配置

```yaml
gewu:
  gateway:
    rate-limiter:
      enabled: true
      # 令牌桶算法
      strategy: token-bucket
      default:
        capacity: 10000       # 桶容量
        rate: 5000            # 每秒填充速率
        refill-interval: 1s   # 填充间隔
      per-route:
        - route: /api/session
          capacity: 2000
          rate: 1000
        - route: /api/llm
          capacity: 500
          rate: 100
        - route: /api/agent
          capacity: 3000
          rate: 1500
      # 分布式限流（基于 DragonflyDB）
      distributed:
        enabled: true
        storage: dragonfly
        key-prefix: gewu:ratelimit:
```

### 4.4 熔断配置

```yaml
gewu:
  gateway:
    circuit-breaker:
      enabled: true
      defaults:
        sliding-window-size: 100
        minimum-calls: 10
        failure-threshold: 0.5       # 50% 失败率触发
        slow-call-threshold: 0.3     # 30% 慢调用触发
        slow-call-duration: 5s       # 超过 5s 视为慢调用
        wait-duration: 30s           # 半开等待时间
      per-service:
        - service: gewu-interface
          failure-threshold: 0.6
          wait-duration: 15s
        - service: gewu-sandbox
          failure-threshold: 0.3
          wait-duration: 60s
```

### 4.5 网关部署 K8s 清单

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gewu-gateway
  namespace: gewu
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  selector:
    matchLabels:
      app: gewu-gateway
  template:
    metadata:
      labels:
        app: gewu-gateway
    spec:
      containers:
        - name: gewu-gateway
          image: harbor.gewu.com/gewu/gewu-gateway:1.0.0
          ports:
            - containerPort: 8080
              name: http
            - containerPort: 8443
              name: https-gm
          env:
            - name: SPRING_PROFILE
              value: "production"
          resources:
            requests:
              cpu: 2000m
              memory: 4Gi
            limits:
              cpu: 4000m
              memory: 8Gi
          volumeMounts:
            - name: gm-certs
              mountPath: /app/certs/gm
              readOnly: true
      volumes:
        - name: gm-certs
          secret:
            secretName: gewu-gm-certs
---
apiVersion: v1
kind: Service
metadata:
  name: gewu-gateway
  namespace: gewu
spec:
  type: ClusterIP
  selector:
    app: gewu-gateway
  ports:
    - name: http
      port: 8080
      targetPort: 8080
    - name: https-gm
      port: 8443
      targetPort: 8443
```

---

## 5. 数据库部署

### 5.1 OceanBase 集群部署

#### 最小生产集群（3 节点）

```yaml
# OceanBase 集群配置
oceanbase:
  cluster: gewu-cluster
  root_password: ${OB_ROOT_PASSWORD}
  servers:
    - host: ob-server-01
      zone: zone1
      ip: 10.0.1.10
      resources:
        cpu: 32
        memory: 64Gi
        disk: 1Ti
    - host: ob-server-02
      zone: zone2
      ip: 10.0.1.11
      resources:
        cpu: 32
        memory: 64Gi
        disk: 1Ti
    - host: ob-server-03
      zone: zone3
      ip: 10.0.1.12
      resources:
        cpu: 32
        memory: 64Gi
        disk: 1Ti
  obproxy:
    replicas: 2
    ports:
      sql: 2883
      prometheus: 2884
```

#### K8s 上使用 OCP 部署

```bash
# 使用 OceanBase  Operator 部署
kubectl create namespace ob

# 创建 OBCluster CR
cat <<EOF | kubectl apply -f -
apiVersion: cloud.oceanbase.com/v1
kind: OBCluster
metadata:
  name: gewu-cluster
  namespace: ob
spec:
  clusterName: gewu-cluster
  clusterId: 1
  topologies:
    - zone: zone1
      replica: 1
    - zone: zone2
      replica: 1
    - zone: zone3
      replica: 1
  image: oceanbase/oceanbase-cloud-native:4.2.1.0-10000012024092617
  resources:
    cpu: 32
    memory: 64Gi
    storage:
      - name: data
        size: 500Gi
      - name: redo
        size: 200Gi
      - name: log
        size: 100Gi
---
apiVersion: cloud.oceanbase.com/v1
kind: OBProxy
metadata:
  name: obproxy
  namespace: ob
spec:
  replicas: 2
  image: oceanbase/obproxy:4.2.1.0
  resources:
    cpu: 4
    memory: 8Gi
EOF
```

### 5.2 初始 Schema 迁移

```bash
# 使用 Flyway 执行 schema 迁移
mvn flyway:migrate -Dflyway.url=jdbc:oceanbase://obproxy:2883/gewu \
  -Dflyway.user=root \
  -Dflyway.password=${DB_PASSWORD}

# 验证迁移
mvn flyway:info -Dflyway.url=jdbc:oceanbase://obproxy:2883/gewu \
  -Dflyway.user=root \
  -Dflyway.password=${DB_PASSWORD}
```

### 5.3 备份与恢复策略

```bash
# OceanBase 全量备份
obclient -h obproxy -P 2883 -u root@gewu#gewu-cluster -p \
  -e "ALTER SYSTEM BACKUP DATABASE TO 'oss://backup-bucket/gewu/full/?host=oss-cn-hangzhou.aliyuncs.com&access_id=${OSS_ACCESS_ID}&access_key=${OSS_ACCESS_KEY}'"

# 增量备份
obclient -h obproxy -P 2883 -u root@gewu#gewu-cluster -p \
  -e "ALTER SYSTEM BACKUP INCREMENTAL DATABASE TO 'oss://backup-bucket/gewu/incr/?host=oss-cn-hangzhou.aliyuncs.com&access_id=${OSS_ACCESS_ID}&access_key=${OSS_ACCESS_KEY}'"
```

#### 备份策略

| 类型 | 频率 | 保留期 | 存储 |
|------|------|--------|------|
| 全量备份 | 每日 02:00 | 30 天 | OSS / 对象存储 |
| 增量备份 | 每 6 小时 | 7 天 | OSS / 对象存储 |
| 日志归档 | 实时 | 14 天 | OSS / 对象存储 |
| 配置文件 | 每周 | 90 天 | Git + 对象存储 |

#### 关键业务表备份优先级

| 表名 | 备份优先级 | 保留期 | 说明 |
|------|-----------|--------|------|
| `session` | P0 | 90 天 | 会话核心数据 |
| `message` | P0 | 90 天 | 消息核心数据 |
| `user_account` | P0 | 90 天 | 用户账户数据 |
| `project` | P0 | 90 天 | 项目核心数据 |
| `agent_config` | P1 | 60 天 | Agent 配置 |
| `agent_execution` | P1 | 30 天 | 执行历史 |
| `sandbox_audit_log` | P1 | 180 天 | 沙箱审计日志（合规要求） |
| `workflow_notification` | P1 | 90 天 | 工作流通知 |
| `workflow_permission` | P1 | 90 天 | 工作流权限 |
| `workflow_permission_matrix` | P1 | 90 天 | 权限矩阵 |
| `workflow_audit_log` | P1 | 180 天 | 工作流审计日志（合规要求） |
| `tool_config` | P2 | 30 天 | 工具配置 |

### 5.4 读写分离

```yaml
# application.yml 读写分离配置
spring:
  datasource:
    primary:
      jdbc-url: jdbc:oceanbase://obproxy:2883/gewu?useUnicode=true&characterEncoding=utf-8
      username: gewu_rw
      password: ${DB_PASSWORD}
      hikari:
        maximum-pool-size: 30
    replica:
      jdbc-url: jdbc:oceanbase://obproxy-replica:2883/gewu?useUnicode=true&characterEncoding=utf-8
      username: gewu_ro
      password: ${DB_PASSWORD_REPLICA}
      hikari:
        maximum-pool-size: 50

# MyBatis-Plus 读写分离
mybatis-plus:
  dynamic-datasource:
    enabled: true
    primary: primary
    strict: true
    datasource:
      primary:
        type: com.zaxxer.hikari.HikariDataSource
      replica:
        type: com.zaxxer.hikari.HikariDataSource
```

---

## 6. 中间件部署

### 6.1 DragonflyDB 缓存集群

参考 OpenCode 单节点 SQLite 的模式不适用于企业场景。格物采用 DragonflyDB 集群模式提供高性能缓存。

#### K8s 部署 DragonflyDB 集群

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: dragonfly
  namespace: gewu
spec:
  serviceName: dragonfly
  replicas: 3
  selector:
    matchLabels:
      app: dragonfly
  template:
    metadata:
      labels:
        app: dragonfly
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchLabels:
                  app: dragonfly
              topologyKey: kubernetes.io/hostname
      containers:
        - name: dragonfly
          image: docker.dragonflydb.io/dragonflydb/dragonfly:v1.27.1
          args:
            - "--requirepass=$(REDIS_PASSWORD)"
            - "--replicaof=dragonfly-0.dragonfly:6379"
            - "--cache_mode=true"
            - "--maxmemory=8gb"
            - "--hz=100"
          ports:
            - containerPort: 6379
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: gewu-secret
                  key: redis.password
          resources:
            requests:
              cpu: 2
              memory: 8Gi
            limits:
              cpu: 4
              memory: 16Gi
          volumeMounts:
            - name: data
              mountPath: /data
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: fast-ssd
        resources:
          requests:
            storage: 100Gi
---
apiVersion: v1
kind: Service
metadata:
  name: dragonfly
  namespace: gewu
spec:
  selector:
    app: dragonfly
  ports:
    - port: 6379
      targetPort: 6379
  clusterIP: None
```

#### Redisson 客户端配置

```yaml
spring:
  data:
    redis:
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 50
          max-idle: 20
          min-idle: 10

redisson:
  config: |
    clusterServersConfig:
      nodeAddresses:
        - "redis://dragonfly-0.dragonfly:6379"
        - "redis://dragonfly-1.dragonfly:6379"
        - "redis://dragonfly-2.dragonfly:6379"
      password: ${REDIS_PASSWORD}
      masterConnectionPoolSize: 50
      slaveConnectionPoolSize: 50
      timeout: 3000
      retryAttempts: 3
      retryInterval: 1500
    codec: org.redisson.codec.Kryo5Codec
```

### 6.2 RocketMQ 集群部署

#### K8s 部署 RocketMQ

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: rocketmq-namesrv
  namespace: gewu
spec:
  serviceName: rocketmq-namesrv
  replicas: 2
  selector:
    matchLabels:
      app: rocketmq-namesrv
  template:
    metadata:
      labels:
        app: rocketmq-namesrv
    spec:
      containers:
        - name: namesrv
          image: apache/rocketmq:5.1.4
          command: ["sh", "mqnamesrv"]
          ports:
            - containerPort: 9876
          env:
            - name: JAVA_OPT_EXT
              value: "-server -Xms512m -Xmx512m"
          resources:
            requests:
              cpu: 1
              memory: 1Gi
            limits:
              cpu: 2
              memory: 2Gi
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: rocketmq-broker
  namespace: gewu
spec:
  serviceName: rocketmq-broker
  replicas: 3
  selector:
    matchLabels:
      app: rocketmq-broker
  template:
    metadata:
      labels:
        app: rocketmq-broker
    spec:
      containers:
        - name: broker
          image: apache/rocketmq:5.1.4
          command: ["sh", "mqbroker"]
          args:
            - "-n"
            - "rocketmq-namesrv:9876"
          ports:
            - containerPort: 10911
            - containerPort: 10909
          env:
            - name: JAVA_OPT_EXT
              value: "-server -Xms4g -Xmx4g -XX:+UseG1GC"
          volumeMounts:
            - name: config
              mountPath: /home/rocketmq/rocketmq-5.1.4/conf/broker.conf
              subPath: broker.conf
            - name: store
              mountPath: /home/rocketmq/store
          resources:
            requests:
              cpu: 2
              memory: 4Gi
            limits:
              cpu: 4
              memory: 8Gi
      volumes:
        - name: config
          configMap:
            name: rocketmq-config
  volumeClaimTemplates:
    - metadata:
        name: store
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: fast-ssd
        resources:
          requests:
            storage: 200Gi
```

#### RocketMQ 死信队列（DLQ）配置

```yaml
rocketmq:
  producer:
    group: gewu-producer
    send-message-timeout: 3000
    retry-times-when-send-failed: 2
  consumer:
    # 主消费组
    - group: gewu-main-consumer
      topic: gewu-events
      tag: "*"
      consume-thread-min: 10
      consume-thread-max: 40
      max-reconsume-times: 3         # 重试 3 次进入 DLQ
      dlq:
        topic: "%DLQ%gewu-main-consumer"
        enabled: true
    # 死信消费组（独立处理）
    - group: gewu-dlq-consumer
      topic: "%DLQ%gewu-main-consumer"
      tag: "*"
      consume-thread-min: 2
      consume-thread-max: 5
```

#### Nacos 配置中心 HA

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: nacos
  namespace: gewu
spec:
  serviceName: nacos
  replicas: 3
  selector:
    matchLabels:
      app: nacos
  template:
    metadata:
      labels:
        app: nacos
    spec:
      containers:
        - name: nacos
          image: nacos/nacos-server:v2.3.0
          ports:
            - containerPort: 8848
            - containerPort: 9848
          env:
            - name: MODE
              value: "cluster"
            - name: NACOS_SERVERS
              value: "nacos-0.nacos:8848 nacos-1.nacos:8848 nacos-2.nacos:8848"
            - name: MYSQL_SERVICE_HOST
              value: "obproxy"
            - name: MYSQL_SERVICE_DB_NAME
              value: "nacos"
            - name: MYSQL_SERVICE_USER
              value: "nacos"
            - name: MYSQL_SERVICE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: gewu-secret
                  key: nacos.password
          resources:
            requests:
              cpu: 1
              memory: 2Gi
            limits:
              cpu: 2
              memory: 4Gi
```

---

## 7. CI/CD 流水线

### 7.1 构建流水线架构

参考 OpenCode 的 GitHub Actions pipeline（test → build → deploy），格物采用 Jenkins / GitLab CI 实现 Maven 多模块构建。

```
代码提交 → 代码扫描 → 单元测试 → Maven构建 → 镜像构建 → 镜像扫描 → 部署

  Stage 1    Stage 2    Stage 3    Stage 4     Stage 5    Stage 6    Stage 7
  checkout   sonar     mvn test   mvn         docker     trivy     helm
             qube       + jacoco  package     build+pull  scan      upgrade
```

### 7.2 Jenkins Pipeline

```groovy
pipeline {
    agent {
        kubernetes {
            label 'gewu-builder'
            yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: maven
      image: maven:3.8-eclipse-temurin-21
      command: ["cat"]
      tty: true
      resources:
        requests:
          cpu: 4
          memory: 8Gi
    - name: docker
      image: docker:24
      command: ["dockerd-entrypoint.sh"]
      securityContext:
        privileged: true
    - name: trivy
      image: aquasec/trivy:latest
      command: ["cat"]
      tty: true
  volumes:
    - name: maven-cache
      persistentVolumeClaim:
        claimName: maven-cache
'''
        }
    }

    environment {
        REGISTRY = 'harbor.gewu.com'
        PROJECT = 'gewu'
        VERSION = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Code Analysis') {
            steps {
                container('maven') {
                    withSonarQubeEnv('sonarqube') {
                        sh 'mvn sonar:sonar -Dsonar.projectKey=gewu-platform'
                    }
                }
            }
        }

        stage('Unit Test') {
            steps {
                container('maven') {
                    sh 'mvn test -B'
                }
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                    jacoco classPattern: '**/target/classes'
                }
            }
        }

        stage('Build') {
            steps {
                container('maven') {
                    sh 'mvn package -DskipTests -B -T 4'
                }
            }
        }

        stage('Docker Build & Push') {
            parallel {
                stage('gewu-interface') {
                    steps {
                        container('docker') {
                            script {
                                docker.build("${REGISTRY}/${PROJECT}/gewu-interface:${VERSION}",
                                    "-f gewu-interface/Dockerfile .")
                                docker.image("${REGISTRY}/${PROJECT}/gewu-interface:${VERSION}").push()
                            }
                        }
                    }
                }
                stage('gewu-gateway') {
                    steps {
                        container('docker') {
                            script {
                                docker.build("${REGISTRY}/${PROJECT}/gewu-gateway:${VERSION}",
                                    "-f gewu-gateway/Dockerfile .")
                                docker.image("${REGISTRY}/${PROJECT}/gewu-gateway:${VERSION}").push()
                            }
                        }
                    }
                }
                stage('gewu-sandbox') {
                    steps {
                        container('docker') {
                            script {
                                docker.build("${REGISTRY}/${PROJECT}/gewu-sandbox:${VERSION}",
                                    "-f gewu-sandbox/Dockerfile .")
                                docker.image("${REGISTRY}/${PROJECT}/gewu-sandbox:${VERSION}").push()
                            }
                        }
                    }
                }
                stage('gewu-web') {
                    steps {
                        container('docker') {
                            script {
                                docker.build("${REGISTRY}/${PROJECT}/gewu-web:${VERSION}",
                                    "-f gewu-web/Dockerfile .")
                                docker.image("${REGISTRY}/${PROJECT}/gewu-web:${VERSION}").push()
                            }
                        }
                    }
                }
            }
        }

        stage('Image Scan') {
            steps {
                container('trivy') {
                    sh "trivy image --severity CRITICAL,HIGH --exit-code 1 " +
                       "${REGISTRY}/${PROJECT}/gewu-interface:${VERSION}"
                }
            }
        }

        stage('Deploy to Staging') {
            when {
                branch 'develop'
            }
            steps {
                container('docker') {
                    sh """
                        helm upgrade --install gewu ./charts/gewu \
                            --namespace gewu-staging \
                            --create-namespace \
                            -f charts/gewu/values-staging.yaml \
                            --set global.image.tag=${VERSION} \
                            --wait --timeout 10m
                    """
                }
            }
        }

        stage('Deploy to Production (Canary)') {
            when {
                branch 'main'
            }
            steps {
                container('docker') {
                    // 金丝雀发布：先部署 10% 流量
                    sh """
                        helm upgrade --install gewu-canary ./charts/gewu \
                            --namespace gewu-production \
                            --create-namespace \
                            -f charts/gewu/values-production.yaml \
                            --set global.image.tag=${VERSION} \
                            --set interface.replicaCount=1 \
                            --set gateway.replicaCount=1 \
                            --set canary.weight=10 \
                            --wait --timeout 10m
                    """
                }
            }
        }

        stage('Full Rollout') {
            when {
                branch 'main'
            }
            input {
                message "确认金丝雀验证通过，全量发布？"
                ok "发布"
            }
            steps {
                container('docker') {
                    sh """
                        helm upgrade --install gewu ./charts/gewu \
                            --namespace gewu-production \
                            --create-namespace \
                            -f charts/gewu/values-production.yaml \
                            --set global.image.tag=${VERSION} \
                            --wait --timeout 15m
                    """
                }
            }
        }
    }

    post {
        failure {
            // 发布失败自动回滚
            sh "helm rollback gewu -n gewu-production"
        }
        always {
            cleanWs()
        }
    }
}
```

### 7.3 镜像构建缓存优化

参考 OpenCode 的 Turborepo + actions/cache 策略，Maven 项目利用 PV 持久化依赖缓存：

```yaml
# Jenkins PV 挂载 Maven 缓存
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: maven-cache
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 50Gi
  storageClassName: nfs-client
```

### 7.4 代码分支与发布策略

```
main (生产)        ← 合并发布 tag，触发全量部署
  ↑
release/*          ← 预发布分支，触发 staging 部署
  ↑
develop (开发)      ← 每日构建，触发 dev 环境部署
  ↑
feature/*           ← 特性分支，仅运行测试
```

### 7.5 部署策略对比

| 策略 | 适用场景 | 说明 |
|------|---------|------|
| **滚动更新** | 常规发布 | K8s 默认，逐个替换 Pod |
| **金丝雀发布** | 重大变更 | 先 10% 流量验证，逐步扩至 100% |
| **蓝绿部署** | 关键发布 | 整套新环境就绪后一键切流，回滚迅速 |

#### 蓝绿部署脚本

```bash
# 蓝环境（当前生产）
BLUE_NS="gewu-prod-blue"
# 绿环境（新版本）
GREEN_NS="gewu-prod-green"

# 部署绿环境
helm upgrade --install gewu ./charts/gewu \
  --namespace ${GREEN_NS} \
  --create-namespace \
  -f charts/gewu/values-production.yaml \
  --set global.image.tag=${VERSION} \
  --wait --timeout 15m

# 验证绿环境
curl -s -f https://green.gewu.com/actuator/health || exit 1

# 切换流量（istio）
kubectl apply -f - <<EOF
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: gewu
  namespace: istio-system
spec:
  hosts:
    - api.gewu.com
  gateways:
    - gewu-gateway
  http:
    - route:
        - destination:
            host: gewu-interface.${GREEN_NS}.svc.cluster.local
          weight: 100
EOF

# 保留蓝环境 24h 用于回滚
```

### 7.6 回滚策略

```bash
# Helm 快速回滚
helm rollback gewu -n gewu-production --wait --timeout 10m

# 查看历史版本
helm history gewu -n gewu-production

# 指定版本回滚
helm rollback gewu -n gewu-production 3 --wait --timeout 10m

# 蓝绿部署回滚
kubectl apply -f - <<EOF
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: gewu
  namespace: istio-system
spec:
  hosts:
    - api.gewu.com
  gateways:
    - gewu-gateway
  http:
    - route:
        - destination:
            host: gewu-interface.gewu-prod-blue.svc.cluster.local
          weight: 100
EOF
```

---

## 8. 监控与告警

### 8.1 监控架构

```
                            ┌──────────────────┐
                            │    Grafana       │
                            │    可视化仪表盘    │
                            └────────┬─────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
    ┌─────────▼─────────┐  ┌─────────▼─────────┐  ┌─────────▼─────────┐
    │   Nightingale     │  │   Prometheus      │  │   VictoriaMetrics │
    │   告警引擎 + 通知   │  │   指标采集          │  │   时序数据库       │
    └─────────┬─────────┘  └─────────┬─────────┘  └─────────┬─────────┘
              │                      │                      │
    ┌─────────▼─────────┐  ┌─────────▼─────────┐  ┌─────────▼─────────┐
    │   Categraf        │  │   Exporters       │  │   Loki            │
    │   统一采集器        │  │   node/kube-state │  │   日志系统         │
    └───────────────────┘  └───────────────────┘  └───────────────────┘
```

### 8.2 Nightingale 告警规则

```yaml
# Nightingale 告警规则 — 应用服务
groups:
  - name: gewu-application
    rules:
      - alert: InterfaceHighErrorRate
        expr: rate(http_server_requests_seconds_count{application="gewu-interface",status=~"5.."}[5m]) / rate(http_server_requests_seconds_count{application="gewu-interface"}[5m]) > 0.01
        for: 3m
        labels:
          severity: critical
          team: backend
        annotations:
          summary: "接口层错误率过高"
          description: "gewu-interface 错误率 {{ $value | humanizePercentage }}，超过 1%"

      - alert: InterfaceP99Latency
        expr: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{application="gewu-interface"}[5m])) > 0.5
        for: 5m
        labels:
          severity: warning
          team: backend
        annotations:
          summary: "接口层 P99 延迟过高"
          description: "gewu-interface P99 延迟 {{ $value | humanizeDuration }}，超过 500ms"

      - alert: GatewayHighLoad
        expr: rate(gateway_requests_total[1m]) > 5000
        for: 1m
        labels:
          severity: warning
          team: backend
        annotations:
          summary: "网关 QPS 过高"
          description: "API 网关 QPS {{ $value }}, 超过 5000"

      - alert: SandboxHighFailureRate
        expr: rate(sandbox_execution_failed_total[5m]) > 0
        for: 2m
        labels:
          severity: critical
          team: sandbox
        annotations:
          summary: "沙箱执行失败"
          description: "沙箱最近 5 分钟有执行失败"

# 数据库告警
  - name: gewu-database
    rules:
      - alert: OBHighConnectionUsage
        expr: ob_connection_count / ob_connection_limit > 0.8
        for: 5m
        labels:
          severity: high
          team: dba
        annotations:
          summary: "OceanBase 连接数超 80%"
          description: "OB 集群连接使用率 {{ $value | humanizePercentage }}"

      - alert: OBSlowQueryExists
        expr: ob_slow_query_count > 0
        for: 5m
        labels:
          severity: warning
          team: dba
        annotations:
          summary: "OceanBase 存在慢查询"
          description: "OB 最近 5 分钟有 {{ $value }} 个慢查询"

# 缓存告警
  - name: gewu-cache
    rules:
      - alert: DragonflyLowHitRate
        expr: dragonfly_keyspace_hits_total / (dragonfly_keyspace_hits_total + dragonfly_keyspace_misses_total) < 0.9
        for: 5m
        labels:
          severity: warning
          team: backend
        annotations:
          summary: "缓存命中率低于 90%"
          description: "DragonflyDB 命中率 {{ $value | humanizePercentage }}"

      - alert: DragonflyMemoryUsage
        expr: dragonfly_used_memory / dragonfly_maxmemory > 0.8
        for: 5m
        labels:
          severity: high
          team: devops
        annotations:
          summary: "缓存内存使用率超 80%"
          description: "DragonflyDB 内存使用率 {{ $value | humanizePercentage }}"

# 消息队列告警
  - name: gewu-mq
    rules:
      - alert: RocketMQBacklog
        expr: rocketmq_message_backlog > 10000
        for: 3m
        labels:
          severity: high
          team: backend
        annotations:
          summary: "RocketMQ 消息堆积"
          description: "Topic {{ $labels.topic }} 堆积 {{ $value }} 条"

      - alert: RocketMQConsumerLag
        expr: rocketmq_consumer_lag > 5000
        for: 5m
        labels:
          severity: warning
          team: backend
        annotations:
          summary: "RocketMQ 消费延迟"
          description: "消费组 {{ $labels.consumer_group }} 落后 {{ $value }} 条"
```

### 8.3 Grafana 仪表盘清单

| 仪表盘名称 | 数据源 | 主要指标 |
|-----------|--------|---------|
| 格物应用总览 | VictoriaMetrics | QPS、延迟、错误率、资源 |
| 接口层详细 | VictoriaMetrics | 各接口 QPS、P50/P95/P99、HTTP 状态码分布 |
| 网关监控 | VictoriaMetrics | 请求量、限流次数、熔断状态 |
| OceanBase 监控 | VictoriaMetrics / OB Exporter | 连接数、TPS、慢查询、磁盘使用 |
| DragonflyDB 监控 | VictoriaMetrics | 命中率、内存、QPS、淘汰率 |
| RocketMQ 监控 | VictoriaMetrics | 生产/消费 TPS、堆积量、延迟 |
| JVM 监控 | VictoriaMetrics / JMX Exporter | 堆内存、GC 频率、线程数 |
| K8s 集群监控 | Prometheus | 节点资源、Pod 状态、网络流量 |

### 8.4 Spring Boot Actuator 指标暴露

```yaml
# application.yml — 生产环境监控配置
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info,env
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: gewu-interface
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
      group:
        readiness:
          include: db,redis,rocketmq
        liveness:
          include: ping
  info:
    env:
      enabled: true
    build:
      enabled: true
    git:
      enabled: true
      mode: full
```

---

## 9. 灾备方案

### 9.1 多 AZ 部署

```
                    ┌──────────────────────────────────────────────┐
                    │              全局负载均衡 (GSLB)              │
                    └──────┬──────────────────────┬────────────────┘
                           │                      │
              ┌────────────▼──────┐    ┌──────────▼──────────────┐
              │   AZ A (主)       │    │   AZ B (备)             │
              │  ┌──────────────┐ │    │  ┌──────────────────┐   │
              │  │ K8s 集群     │ │    │  │ K8s 集群         │   │
              │  │ OceanBase    │ │    │  │ OceanBase        │   │
              │  │  主副本       │ │    │  │  备副本          │   │
              │  │ DragonflyDB  │ │    │  │ DragonflyDB     │   │
              │  │ RocketMQ     │ │    │  │ RocketMQ        │   │
              │  └──────────────┘ │    │  └──────────────────┘   │
              └───────────────────┘    └─────────────────────────┘
```

#### K8s 多 AZ 调度

```yaml
# Pod 拓扑分布约束
spec:
  topologySpreadConstraints:
    - maxSkew: 1
      topologyKey: topology.kubernetes.io/zone
      whenUnsatisfiable: DoNotSchedule
      labelSelector:
        matchLabels:
          app: gewu-interface
```

### 9.2 数据库备份与恢复

```bash
# 每日全量备份脚本
#!/bin/bash
BACKUP_DATE=$(date +%Y%m%d)
BACKUP_PATH="/backup/oceanbase/${BACKUP_DATE}"

obclient -h obproxy -P 2883 -u root@gewu#gewu-cluster -p${OB_ROOT_PASSWORD} <<EOF
ALTER SYSTEM BACKUP DATABASE TO 'oss://gewu-backup/full/${BACKUP_DATE}/?host=oss-cn-hangzhou.aliyuncs.com';
EOF

# 验证备份
obclient -h obproxy -P 2883 -u root@gewu#gewu-cluster -p${OB_ROOT_PASSWORD} <<EOF
SELECT * FROM oceanbase.CDB_OB_BACKUP_JOBS ORDER BY CREATE_TIME DESC LIMIT 10;
EOF

# 清理 30 天前的备份
find /backup/oceanbase -maxdepth 1 -mtime +30 -exec rm -rf {} \;
```

```bash
# 恢复演练脚本
#!/bin/bash
RESTORE_DATE=$1

obclient -h obproxy -P 2883 -u root@gewu#gewu-cluster -p${OB_ROOT_PASSWORD} <<EOF
ALTER SYSTEM RESTORE gewu FROM 'oss://gewu-backup/full/${RESTORE_DATE}/'
UNTIL TIME='${RESTORE_DATE} 23:59:59';
EOF

# 验证恢复
obclient -h obproxy -P 2883 -u root@gewu#gewu-cluster -p${OB_ROOT_PASSWORD} \
  -e "SELECT COUNT(*) FROM gewu.session LIMIT 1;"
```

### 9.3 数据备份保留策略

| 数据类型 | 备份方式 | 频率 | 保留期 | 存储位置 |
|----------|---------|------|--------|---------|
| OceanBase 全量 | 物理备份 | 每日 02:00 | 30 天 | OSS + 异地 |
| OceanBase 增量 | 物理备份 | 每 6 小时 | 7 天 | OSS |
| OceanBase 日志 | 实时归档 | 实时 | 14 天 | OSS |
| DragonflyDB RDB | SAVE 命令 | 每 6 小时 | 3 天 | 本地 + NAS |
| RocketMQ 消息 | 消费确认后清理 | - | 72 小时 | 本地磁盘 |
| 配置文件 | git 版本管理 | 随变更 | 永久 | Git + 对象存储 |
| 容器镜像 | 镜像仓库 | 每次构建 | 90 天 | Harbor |

### 9.4 灾备演练计划

| 演练类型 | 频率 | 内容 | 验收标准 |
|----------|------|------|----------|
| 数据库恢复 | 每月 | 从备份恢复 OceanBase 到沙箱环境 | RTO < 4h, RPO < 24h |
| AZ 切换 | 每季度 | 主 AZ 整体切换到备 AZ | RTO < 30min |
| 服务熔断 | 每季度 | 模拟网关熔断后的降级表现 | 核心 API 降级可用 |
| 全链路压测 | 每半年 | 模拟生产流量压测 | QPS 达到设计容量的 120% |
| 演练复盘 | 每次演练后 | 输出 Postmortem | 改进项跟踪闭环 |

---

## 10. 从 OpenCode 部署到格物的演进

### 10.1 为什么对比

OpenCode 是单用户 AI 编程助手，格物是企业级 AI 协作平台。两者定位不同，但 OpenCode 在以下方面的实践可以直接迁移到格物的**开发环境和轻量测试环境**：

1. **开发体验**: Bun dev server + Vite HMR → Spring Boot DevTools + 前端热重载
2. **容器化**: Docker 多阶段构建 + 多架构 → 格物各模块 Dockerfile
3. **CI/CD 编排**: GitHub Actions build/test/deploy 阶段划分 → Jenkins Pipeline 阶段映射
4. **基础设施即代码**: SST 部署 → Helm + K8s manifests

### 10.2 格子本地开发环境（借鉴 OpenCode）

对于开发人员本地开发，格物可以借鉴 OpenCode 的"单文件数据库 + 单一进程"模式，降低开发环境复杂度：

```yaml
# docker-compose.dev.yml — 本地开发环境（轻量版）
services:
  # 应用服务（Maven 启动，不在 Docker 中运行，支持热加载）
  # 使用本地 mvn spring-boot:run，IDE 直接调试

  # 轻量数据库（开发用 H2 替代 OceanBase）
  h2:
    image: oscarfonts/h2:latest
    container_name: gewu-dev-db
    ports:
      - "1521:1521"
      - "8082:8082"
    environment:
      H2_OPTIONS: -ifNotExists
    volumes:
      - h2-data:/opt/h2-data

  # DragonflyDB（单节点，无需集群）
  dragonfly:
    image: docker.dragonflydb.io/dragonflydb/dragonfly:v1.27.1
    container_name: gewu-dev-cache
    ports:
      - "6379:6379"
    ulimits:
      memlock:
        soft: -1
        hard: -1

  # RocketMQ（单 NameServer + 单 Broker，无需集群）
  rocketmq-namesrv:
    image: apache/rocketmq:5.1.4
    container_name: gewu-dev-mq-namesrv
    ports:
      - "9876:9876"
    command: sh mqnamesrv

  rocketmq-broker:
    image: apache/rocketmq:5.1.4
    container_name: gewu-dev-mq-broker
    ports:
      - "10911:10911"
    command: sh mqbroker -n localhost:9876
    depends_on:
      - rocketmq-namesrv

  # 监控（按需启动）
  nightingale:
    image: flashcatcloud/nightingale:latest
    container_name: gewu-dev-monitor
    ports:
      - "17000:17000"
    profiles:
      - monitor

  grafana:
    image: grafana/grafana:latest
    container_name: gewu-dev-grafana
    ports:
      - "3000:3000"
    profiles:
      - monitor

volumes:
  h2-data:
```

### 10.3 CI/CD 演进路径

| 阶段 | 服务器 | 部署目标 | CI/CD 工具 | 参考来源 |
|------|--------|---------|-----------|---------|
| 阶段 0：本地开发 | 开发者笔记本 | localhost | IDE + Maven | OpenCode dev模式 |
| 阶段 1：团队开发 | 内网服务器 | Docker Compose | Jenkins Pipeline | OpenCode Docker 模式 |
| 阶段 2：测试环境 | 内网 K8s | Helm → test 命名空间 | Jenkins + Harbor | 本文第 7 节 |
| 阶段 3：预发布 | 内网 K8s | Helm → staging 命名空间 | Jenkins + GitLab CI | 本文第 7 节 |
| 阶段 4：生产 | 信创 K8s | Helm → production 命名空间 | Jenkins + GitLab CI | 本文完整方案 |

### 10.4 OpenCode 可借鉴的具体实践清单

| OpenCode 实践 | 格物借鉴方式 | 优先级 |
|-------------|------------|--------|
| GitHub Actions concurrency 控制 | Jenkins pipeline 并发限制 | P1 |
| Docker multi-stage builds | 各模块 Dockerfile 分层构建 | P1 |
| TARGETARCH 多架构构建 | 信创 CPU 多架构矩阵 | P1 |
| Cache turbo/hashFiles 加速 CI | Maven 依赖 + 镜像层缓存 | P1 |
| Playwright e2e 测试 | Selenium/Cypress 前端 e2e | P2 |
| Sentry 错误追踪 | 接入 Nightingale 异常事件 | P2 |
| SST Serverless 部署 | 仅供了解，格物需 K8s 集群 | P3 |

---

## 附录 A：环境变量清单

| 变量名 | 说明 | 来源 |
|--------|------|------|
| `DB_HOST` | OceanBase 连接地址 | ConfigMap |
| `DB_PASSWORD` | 数据库密码 | Secret |
| `REDIS_PASSWORD` | DragonflyDB 密码 | Secret |
| `JWT_SECRET` | JWT 签名密钥 | Secret |
| `SM2_PRIVATE_KEY` | 国密 SM2 私钥 | Secret |
| `SM4_KEY` | 国密 SM4 对称密钥 | Secret |
| `NACOS_PASSWORD` | Nacos 配置中心密码 | Secret |
| `ROCKETMQ_NAMESRV` | RocketMQ NameServer 地址 | ConfigMap |
| `OBS_ACCESS_ID` | 对象存储 AK | Secret |
| `OBS_ACCESS_KEY` | 对象存储 SK | Secret |
| `SPRING_PROFILE` | 激活 profile | ConfigMap |

## 附录 B：部署检查清单

- [ ] K8s 集群 1.24+ 就绪，至少 3 Master + 5 Worker
- [ ] Harbor 镜像仓库 2.8+ 就绪，存储配额充足
- [ ] OceanBase 4.2+ 集群 3 节点部署完成
- [ ] OBProxy 2 节点部署完成
- [ ] DragonflyDB 3 节点 StatefulSet 部署完成
- [ ] RocketMQ 集群（2 NameServer + 3 Broker）部署完成
- [ ] Nacos 3 节点集群部署完成（OceanBase 后端）
- [ ] 国密 SM2/SM3/SM4 证书生成并导入 Secret
- [ ] Nightingale + VictoriaMetrics + Grafana 部署完成
- [ ] Categraf 采集器部署至所有 Worker 节点
- [ ] Jenkins 流水线配置完成
- [ ] Helm Chart 各环境 values 文件准备完成
- [ ] 防火墙端口策略配置完毕
- [ ] 多 AZ 网络互通验证
- [ ] 备份策略配置并验证（OceanBase 全量 + 增量）
- [ ] 灾备演练脚本准备完成
- [ ] 告警规则导入 Nightingale
- [ ] Grafana 仪表盘模板导入

## 附录 C：相关文档

| 文档 | 版本 | 位置 |
|------|------|------|
| 技术架构总览 | V5.0 | design/01-technical-architecture.md |
| 部署架构设计 | V5.0 | design/02-deployment-architecture.md |
| 数据库设计 | V5.0 | design/03-database-design.md |
| 监控告警文档 | V5.0 | design/06-monitoring-alerting.md |
| 安全合规文档 | V5.0 | design/07-security-compliance.md |
| OpenCode 架构 | v1.17.14 | opencode-1.17.14/docs/architecture.md |

---

**文档结束**
