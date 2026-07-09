# 格物平台 - 部署架构设计

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 部署架构设计 |
| 版本 | V5.0 (更新) |
| 创建日期 | 2026-07-07 |
| 最后更新 | 2026-07-07 |
| 文档状态 | 已批准 |
| 项目根目录 | /home/wnn/devcode/ai-code/gewu-platform |

---

## 1. 部署概述

格物平台支持多种部署方式，包括开发环境、测试环境、生产环境，以及云原生部署和信创环境部署。

### 1.1 部署方式

| 部署方式 | 适用场景 | 特点 |
|----------|----------|------|
| **Docker Compose** | 开发环境、测试环境 | 快速启动、便于调试 |
| **Kubernetes** | 生产环境 | 高可用、弹性伸缩 |
| **KubeEdge** | 边缘计算 | 轻量级、离线部署 |
| **iSulad** | 信创环境 | 信创合规、自主可控 |

### 1.2 部署架构

```
┌─────────────────────────────────────────────────────────┐
│                    生产环境架构                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  API 网关     │  │  应用服务     │  │  沙箱服务     │  │
│  │  (3 节点)     │  │  (10 节点)    │  │  (5 节点)     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  数据库       │  │  缓存         │  │  消息队列     │  │
│  │  (OceanBase) │  │  (Dragonfly)  │  │  (RocketMQ)   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  监控         │  │  日志         │  │  CI/CD        │  │
│  │  (Nightingale)│  │  (ELK)        │  │  (Gitea)      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 开发环境部署

### 2.1 环境要求

| 组件 | 配置 | 数量 |
|------|------|------|
| **操作系统** | Ubuntu 22.04 / CentOS 8+ | 1 台 |
| **CPU** | 8 核 | - |
| **内存** | 16GB | - |
| **磁盘** | 100GB SSD | - |
| **网络** | 千兆网卡 | - |

### 2.2 Docker Compose 部署

#### 2.2.1 启动命令

```bash
cd /home/wnn/devcode/ai-code/gewu-platform
docker compose up -d
```

#### 2.2.2 服务清单

| 服务 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| **MySQL** | mysql:8.0 | 3306 | 主数据库 |
| **DragonflyDB** | docker.dragonflydb.io/dragonflydb/dragonfly:v1.27.1 | 6379 | 缓存 |
| **Nightingale** | flashcatcloud/nightingale:latest | 17000, 20090 | 监控平台 |
| **VictoriaMetrics** | victoriametrics/victoria-metrics:latest | 8428 | 时序数据库 |
| **Categraf** | flashcatcloud/categraf:latest | - | 采集器 |
| **Grafana** | grafana/grafana:latest | 3000 | 可视化 |
| **RocketMQ NameServer** | apache/rocketmq:5.1.4 | 9876 | 消息队列 |
| **RocketMQ Broker** | apache/rocketmq:5.1.4 | 10911 | 消息队列 |
| **RocketMQ Console** | apacherocketmq/rocketmq-dashboard:latest | 9877 | 消息队列管理 |
| **Gitea** | gitea/gitea:latest | 3001, 2222 | 代码仓库 |

#### 2.2.3 docker-compose.yml

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: gewu-mysql
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=root123456
      - MYSQL_DATABASE=gewu
      - MYSQL_USER=gewu
      - MYSQL_PASSWORD=gewu123456
    volumes:
      - mysql-data:/var/lib/mysql
    networks:
      - gewu-network

  dragonfly:
    image: docker.dragonflydb.io/dragonflydb/dragonfly:v1.27.1
    container_name: gewu-dragonfly
    ports:
      - "6379:6379"
    ulimits:
      memlock:
        soft: -1
        hard: -1
    volumes:
      - dragonfly-data:/data
    networks:
      - gewu-network

  nightingale:
    image: flashcatcloud/nightingale:latest
    container_name: gewu-nightingale
    ports:
      - "17000:17000"
      - "20090:20090"
    volumes:
      - ./deploy/docker/nightingale:/app/etc
    depends_on:
      - mysql
      - dragonfly
    networks:
      - gewu-network

  victoriametrics:
    image: victoriametrics/victoria-metrics:latest
    container_name: gewu-victoriametrics
    ports:
      - "8428:8428"
    volumes:
      - vm-data:/victoria-metrics-data
    networks:
      - gewu-network

  categraf:
    image: flashcatcloud/categraf:latest
    container_name: gewu-categraf
    volumes:
      - ./deploy/docker/categraf:/etc/categraf
    depends_on:
      - nightingale
    networks:
      - gewu-network

  grafana:
    image: grafana/grafana:latest
    container_name: gewu-grafana
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin123456
    volumes:
      - grafana-data:/var/lib/grafana
    depends_on:
      - victoriametrics
    networks:
      - gewu-network

  rocketmq-namesrv:
    image: apache/rocketmq:5.1.4
    container_name: gewu-rocketmq-namesrv
    ports:
      - "9876:9876"
    command: sh mqnamesrv
    environment:
      - JAVA_OPT_EXT=-server -Xms256m -Xmx256m
    networks:
      - gewu-network

  rocketmq-broker:
    image: apache/rocketmq:5.1.4
    container_name: gewu-rocketmq-broker
    ports:
      - "10911:10911"
    command: sh mqbroker -n gewu-rocketmq-namesrv:9876
    environment:
      - JAVA_OPT_EXT=-server -Xms256m -Xmx256m
    depends_on:
      - rocketmq-namesrv
    networks:
      - gewu-network

  rocketmq-console:
    image: apacherocketmq/rocketmq-dashboard:latest
    container_name: gewu-rocketmq-console
    ports:
      - "9877:8080"
    environment:
      - JAVA_OPT_EXT=-Drocketmq.namesrv.addr=gewu-rocketmq-namesrv:9876
    depends_on:
      - rocketmq-namesrv
    networks:
      - gewu-network

  gitea:
    image: gitea/gitea:latest
    container_name: gewu-gitea
    ports:
      - "3001:3000"
      - "2222:22"
    volumes:
      - gitea-data:/data
    environment:
      - GITEA__database__DB_TYPE=sqlite3
    networks:
      - gewu-network

volumes:
  mysql-data:
  dragonfly-data:
  vm-data:
  grafana-data:
  gitea-data:

networks:
  gewu-network:
    driver: bridge
```

#### 2.2.4 访问地址

| 服务 | 访问地址 | 默认账号 |
|------|----------|----------|
| **MySQL** | localhost:3306 | root/root123456 |
| **DragonflyDB** | localhost:6379 | - |
| **Nightingale** | http://localhost:17000 | root/root.2020 |
| **VictoriaMetrics** | http://localhost:8428 | - |
| **Grafana** | http://localhost:3000 | admin/admin123456 |
| **RocketMQ Console** | http://localhost:9877 | - |
| **Gitea** | http://localhost:3001 | admin/admin123456 |

### 2.3 停止与清理

```bash
# 停止服务
docker compose down

# 停止并删除数据卷
docker compose down -v

# 查看服务状态
docker compose ps

# 查看服务日志
docker compose logs -f [service-name]
```

---

## 3. 生产环境部署

### 3.1 环境要求

| 组件 | 配置 | 数量 | 用途 |
|------|------|------|------|
| **应用服务器** | 16 核 32GB | 10 台 | 应用服务 |
| **数据库服务器** | 32 核 64GB | 3 台 | OceanBase 集群 |
| **缓存服务器** | 8 核 16GB | 3 台 | DragonflyDB 集群 |
| **消息队列服务器** | 8 核 16GB | 3 台 | RocketMQ 集群 |
| **监控服务器** | 8 核 16GB | 2 台 | Nightingale + Grafana |
| **负载均衡** | 4 核 8GB | 3 台 | Nginx/LVS |

### 3.2 Kubernetes 部署

#### 3.2.1 部署架构

```
┌─────────────────────────────────────────────────────────┐
│                    Kubernetes 集群                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  API Server  │  │  Controller  │  │  Scheduler   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  etcd        │  │  Node 1      │  │  Node 2      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  Node 3      │  │  Node 4      │  │  Node 5      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

#### 3.2.2 Helm Charts

```bash
# 安装 helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# 添加仓库
helm repo add gewu https://gewu-repo.example.com/charts

# 更新仓库
helm repo update

# 安装应用
helm install gewu gewu/gewu -n gewu --create-namespace

# 查看部署状态
helm status gewu -n gewu

# 升级部署
helm upgrade gewu gewu/gewu -n gewu --set image.tag=v1.0.0
```

#### 3.2.3 资源配置

```yaml
# 应用服务资源配置
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gewu-application
  namespace: gewu
spec:
  replicas: 10
  selector:
    matchLabels:
      app: gewu-application
  template:
    metadata:
      labels:
        app: gewu-application
    spec:
      containers:
      - name: gewu-application
        image: gewu/gewu-application:v1.0.0
        ports:
        - containerPort: 8080
        resources:
          requests:
            cpu: 1000m
            memory: 2Gi
          limits:
            cpu: 2000m
            memory: 4Gi
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 15
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: gewu-application
  namespace: gewu
spec:
  selector:
    app: gewu-application
  ports:
  - port: 8080
    targetPort: 8080
  type: ClusterIP
```

---

## 4. 信创环境部署

### 4.1 环境要求

| 组件 | 配置 | 数量 |
|------|------|------|
| **操作系统** | 龙芯 LoongArch64 / 鲲鹏 ARM64 / 飞腾 ARM64 | - |
| **CPU** | 8 核+ | - |
| **内存** | 16GB+ | - |
| **磁盘** | 100GB SSD | - |
| **容器运行时** | iSulad | - |

### 4.2 iSulad 部署

#### 4.2.1 安装 iSulad

```bash
# 下载 iSulad
wget https://example.com/isulad-1.0.0-linux-amd64.tar.gz

# 解压
tar -xzf isulad-1.0.0-linux-amd64.tar.gz

# 安装
sudo ./isulad-installer.sh install

# 启动服务
sudo systemctl start isulad
sudo systemctl enable isulad
```

#### 4.2.2 Docker Compose 配置

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: gewu-mysql
    runtime: isulad  # 使用 iSulad 运行时
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=root123456
    volumes:
      - mysql-data:/var/lib/mysql
    networks:
      - gewu-network

  dragonfly:
    image: docker.dragonflydb.io/dragonflydb/dragonfly:v1.27.1
    container_name: gewu-dragonfly
    runtime: isulad
    ports:
      - "6379:6379"
    ulimits:
      memlock:
        soft: -1
        hard: -1
    volumes:
      - dragonfly-data:/data
    networks:
      - gewu-network
```

---

## 5. KubeEdge 部署

### 5.1 边缘计算架构

```
┌─────────────────────────────────────────────────────────┐
│                    云端 (Kubernetes)                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  API Server  │  │  Controller  │  │  Scheduler   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  EdgeHub     │  │  CloudHub    │  │  EventBus     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                            ↓ MQTT
┌─────────────────────────────────────────────────────────┐
│                    边缘节点 (KubeEdge)                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  EdgeCore     │  │  DeviceTwin  │  │  ServiceBus   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  应用服务     │  │  沙箱服务     │  │  数据采集     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 5.2 部署步骤

```bash
# 1. 安装 KubeEdge
wget https://github.com/kubeedge/kubeedge/releases/download/v1.13.0/kubeedge-v1.13.0-linux-amd64.tar.gz
tar -xzf kubeedge-v1.13.0-linux-amd64.tar.gz
cd kubeedge
sudo ./hack/local-up-kubeedge.sh

# 2. 配置边缘节点
cat > /etc/kubeedge/config.yaml << EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: edgecore-config
  namespace: kubeedge
data:
  edgehub:
    websocket:
      address: 192.168.1.100:10002
  tunnel:
    enabled: true
EOF

# 3. 启动边缘节点
sudo systemctl start edgecore
sudo systemctl enable edgecore
```

---

## 6. 沙箱部署

### 6.1 Firecracker 部署

#### 6.1.1 安装 Firecracker

```bash
# 下载 Firecracker
wget https://github.com/firecracker-microvm/firecracker/releases/download/v1.7.0/firecracker-v1.7.0-x86_64

# 设置执行权限
chmod +x firecracker-v1.7.0-x86_64

# 安装
sudo mv firecracker-v1.7.0-x86_64 /usr/local/bin/firecracker
```

#### 6.1.2 创建 MicroVM

```bash
# 创建 MicroVM 配置
cat > /tmp/microvm.json << EOF
{
  "boot_args": "console=ttyS0 reboot=k panic=1 pci=off",
  "cpu_template": "T2",
  "vcpu_count": 2,
  "mem_size_mib": 512,
  "mmio_size_mib": 64,
  "sandbox": {
    "allow_msr_access": false,
    "debug": false,
    "parent_path": "/tmp/firecracker-sandbox",
    "use_parent_namespaces": true
  },
  "machine_config": {
    "vcpu_count": 2,
    "mem_size_mib": 512,
    "ht_enabled": false
  },
  "network_interfaces": [
    {
      "iface_id": "eth0",
      "guest_mac": "02:FC:00:00:00:01",
      "host_dev_name": "tap0"
    }
  ],
  "drives": [
    {
      "drive_id": "rootfs",
      "path_on_host": "/tmp/rootfs.ext4",
      "is_root_device": true,
      "is_readonly": false,
      "rate_limit_iops": 0,
      "rate_limit_bytes": 0
    }
  ]
}
EOF

# 启动 MicroVM
firecracker --api-sock /tmp/firecracker.sock --config-file /tmp/microvm.json
```

### 6.2 gVisor 部署

#### 6.2.1 安装 gVisor

```bash
# 下载 gVisor
wget https://storage.googleapis.com/gvisor/releases/release/latest/release.tar.gz
tar -xzf release.tar.gz

# 安装
sudo ./release/runsc --install
```

#### 6.2.2 运行容器

```bash
docker run --runtime=gvisor --rm -it nginx:latest
```

---

## 7. 监控部署

### 7.1 Nightingale 部署

#### 7.1.1 安装 Nightingale

```bash
# 克隆仓库
git clone https://github.com/ccfos/nightingale.git
cd nightingale/docker/compose-bridge

# 启动
docker compose up -d

# 访问
# http://localhost:17000
# 默认账号: root / root.2020
```

#### 7.1.2 配置 Categraf

```yaml
# categraf 配置示例
[[inputs.cpu]]
  percpu = true
  totalcpu = true
  collect_cpu_time = false
  report_active_cores = true

[[inputs.mem]]
  [[inputs.mem.pass]]
    include = ["^/.*"]

[[inputs.disk]]
  mount_points = ["/"]
  ignore_fs = ["tmpfs", "devtmpfs", "fuse.lxcfs", "overlay"]
```

### 7.2 Grafana 部署

#### 7.2.1 安装 Grafana

```bash
# 启动 Grafana
docker compose up -d grafana

# 访问
# http://localhost:3000
# 默认账号: admin / admin
```

#### 7.2.2 配置数据源

1. 登录 Grafana
2. 导入 VictoriaMetrics 数据源
3. 配置 API 地址: http://victoriametrics:8428

---

## 8. CI/CD 部署

### 8.1 Gitea 部署

#### 8.1.1 安装 Gitea

```bash
# 启动 Gitea
docker compose up -d gitea

# 访问
# http://localhost:3001
# 默认账号: admin / admin123456
```

#### 8.1.2 创建仓库

```bash
# 在 Gitea 中创建 gewu-platform 仓库
# 配置 Webhook
# Webhook URL: http://drone.example.com/api/hook/xxxxx
```

### 8.2 Drone CI 部署

#### 8.2.1 安装 Drone

```bash
# 启动 Drone
docker compose up -d drone

# 访问
# http://localhost:8080
# 配置 Drone 与 Gitea 集成
```

#### 8.2.2 配置 Pipeline

```yaml
# .drone.yml
kind: pipeline
name: gewu-platform

steps:
  - name: build
    image: maven:3.8-openjdk-21
    commands:
      - mvn clean package -DskipTests

  - name: test
    image: maven:3.8-openjdk-21
    commands:
      - mvn test

  - name: deploy
    image: docker:24
    commands:
      - docker login -u $DOCKER_USERNAME -p $DOCKER_PASSWORD
      - docker build -t gewu/gewu-platform:$DRONE_COMMIT_SHA .
      - docker push gewu/gewu-platform:$DRONE_COMMIT_SHA
```

---

## 9. 备份与恢复

### 9.1 数据库备份

```bash
# MySQL 备份
mysqldump -u root -p gewu > /backup/gewu_$(date +%Y%m%d).sql

# OceanBase 备份
obclient -u root -p -e "BACKUP DATABASE gewu TO 'backup_$(date +%Y%m%d)'"

# DragonflyDB 备份
redis-cli BGSAVE
```

### 9.2 备份策略

| 数据类型 | 备份频率 | 保留时间 | 备份方式 |
|----------|----------|----------|----------|
| **数据库** | 每日 | 30 天 | 全量备份 |
| **配置文件** | 每周 | 90 天 | 全量备份 |
| **日志文件** | 每日 | 7 天 | 增量备份 |

---

## 10. 安全加固

### 10.1 网络安全

- **防火墙**: 开放必要端口，关闭不必要端口
- **SSL/TLS**: 启用 HTTPS
- **访问控制**: 配置防火墙规则、ACL

### 10.2 容器安全

- **镜像扫描**: 使用 Trivy 扫描镜像漏洞
- **资源限制**: 限制容器资源使用
- **安全配置**: 最小化容器镜像

### 10.3 数据安全

- **加密**: 敏感数据加密存储
- **备份**: 定期备份，异地容灾
- **审计**: 记录所有操作日志

---

## 11. 故障恢复

### 11.1 高可用部署

- **应用服务**: 多副本部署
- **数据库**: 主从复制、故障转移
- **缓存**: 主从复制、故障转移
- **消息队列**: 集群模式、故障转移

### 11.2 故障恢复流程

1. **故障检测**: 监控告警
2. **故障定位**: 日志分析、性能分析
3. **故障恢复**: 自动恢复或手动恢复
4. **故障复盘**: 分析原因、改进措施

---

## 12. 扩展性设计

### 12.1 水平扩展

- **应用服务**: 无状态服务，支持水平扩展
- **数据库**: OceanBase 分布式架构
- **缓存**: DragonflyDB 主从架构
- **消息队列**: RocketMQ 集群模式

### 12.2 垂直扩展

- **数据库**: OceanBase 支持垂直扩展
- **缓存**: DragonflyDB 支持垂直扩展
- **消息队列**: RocketMQ 支持垂直扩展

---

## 13. 附录

### 13.1 部署检查清单

- [ ] 环境准备完成
- [ ] Docker/iSulad 安装完成
- [ ] Docker Compose 配置完成
- [ ] 数据库连接测试
- [ ] 缓存连接测试
- [ ] 消息队列连接测试
- [ ] 监控平台连接测试
- [ ] CI/CD 配置完成
- [ ] 备份策略配置完成
- [ ] 安全加固完成

### 13.2 相关文档

| 文档 | 版本 | 说明 |
|------|------|------|
| 01-technical-architecture.md | V5.0 | 技术架构总览 |
| 03-database-design.md | V5.0 | 数据库设计 |
| 06-monitoring-alerting.md | V5.0 | 监控告警文档 |
| 07-security-compliance.md | V5.0 | 安全合规文档 |

---

**文档结束**
