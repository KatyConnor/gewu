# SP-01: OceanBase K8s 部署验证报告

## 文档信息

| 项目 | 内容 |
|------|------|
| Spike 编号 | SP-01 |
| 验证目标 | OceanBase 4.2+ 在 K8s 集群上的部署、性能基准、MySQL 兼容性 |
| 负责人 | DBA |
| 计划工期 | 3 天 |
| 关联设计文档 | 22-unified-db-schema.md, 25-unified-deployment.md |

---

## 1. 验证目标

| 编号 | 验证项 | 通过标准 | 失败回退 |
|------|--------|---------|---------|
| SP-01-01 | OB Operator 部署 3 节点集群 | 3 节点全部 READY，PVC 绑定成功 | 降级为 Docker Compose 单节点 |
| SP-01-02 | OBProxy 负载均衡 | 读写分离生效，连接池正常 | 使用直连模式 |
| SP-01-03 | MySQL 兼容模式 DDL/DML | 32 张表 DDL 全部执行成功 | 逐表排查兼容性问题 |
| SP-01-04 | 单节点 TPS 基准 | TPS > 2000（简单 INSERT） | 调整资源配额或降级 MySQL 8.0 |
| SP-01-05 | 3 节点集群 RPO | RPO = 0（同步复制无数据丢失） | 改为异步复制，RPO < 5min |
| SP-01-06 | 故障自动切换 | 主节点宕机后 30s 内自动切换 | 手动切换方案 |

---

## 2. 测试环境

### 2.1 最低配置

| 资源 | 规格 | 数量 |
|------|------|------|
| K8s 节点 | 8C16G, SSD 100GB | 3 台（Worker） |
| K8s Master | 4C8G | 1 台（可用 Minikube 替代） |
| 存储 | SSD, IOPS > 3000 | 每节点 50GB PVC |
| 网络 | 千兆内网 | — |

### 2.2 开发环境替代

如果无 K8s 集群，使用 Docker Compose 单节点替代：

```bash
# 启动 OceanBase 单节点
cd /home/wnn/devcode/ai-code/gewu-platform
docker compose up -d ob

# 等待启动完成（约 60-120 秒）
docker compose logs -f ob
```

---

## 3. 执行步骤

### 3.1 K8s 部署（OB Operator）

```bash
# Step 1: 安装 OB Operator
kubectl apply -f https://raw.githubusercontent.com/oceanbase/ob-operator/master/deploy/ob-operator.yaml

# Step 2: 验证 Operator 运行
kubectl get pods -n oceanbase-system
# 预期: ob-operator-xxx   1/1   Running

# Step 3: 创建 OceanBase 集群（3 节点）
cat <<EOF | kubectl apply -f -
apiVersion: oceanbase.oceanbase.com/v1alpha1
kind: OceanBaseCluster
metadata:
  name: gewu-ob-cluster
  namespace: oceanbase
spec:
  replica: 3
  observer:
    image: oceanbase/oceanbase-ce:4.2.0.0
    resource:
      cpu: 4
      memory: 8Gi
    storage:
      dataStorage:
        storageClass: standard
        size: 50Gi
      logStorage:
        storageClass: standard
        size: 20Gi
  obProxy:
    image: oceanbase/obproxy:4.2.0
    replica: 2
    resource:
      cpu: 2
      memory: 4Gi
EOF

# Step 4: 等待集群就绪（约 5-10 分钟）
kubectl get pods -n oceanbase -w
# 预期: 3 个 observer pod + 2 个 obproxy pod 全部 Running

# Step 5: 初始化数据库
kubectl exec -it gewu-ob-cluster-observer-0 -n oceanbase -- obclient -h localhost -P 2881 -u root -p '' -e "
  CREATE DATABASE gewu_dev;
  CREATE USER 'gewu'@'%' IDENTIFIED BY 'gewu123456';
  GRANT ALL ON gewu_dev.* TO 'gewu'@'%';
"
```

### 3.2 DDL 兼容性验证

```bash
# 连接数据库
obclient -h <OB_HOST> -P 2881 -u gewu -p gewu123456 -D gewu_dev

# 执行 32 张表 DDL（从 22-unified-db-schema.md 提取）
# 逐表执行并记录结果
source /home/wnn/devcode/ai-code/gewu-platform/deploy/scripts/V1__init_schema.sql

# 验证表数量
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'gewu_dev';
# 预期: 32

# 验证索引数量
SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = 'gewu_dev';
# 预期: >= 21

# 验证 JSON 字段
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'gewu_dev' AND data_type = 'json';
# 预期: event.details, session.metadata 等 JSON 字段正常创建

# 验证 ULID 主键
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema = 'gewu_dev' AND column_key = 'PRI'
ORDER BY table_name;
# 预期: 所有主键为 VARCHAR(26)
```

### 3.3 性能基准测试

```bash
# 安装 sysbench
apt-get install -y sysbench  # 或 yum install sysbench

# 测试 1: 单表 INSERT TPS
sysbench oltp_insert --table-name=user_account \
  --mysql-host=<OB_HOST> --mysql-port=2881 \
  --mysql-user=gewu --mysql-password=gewu123456 \
  --mysql-db=gewu_dev \
  --threads=16 --time=60 --report-interval=10 \
  run

# 通过标准: TPS > 2000
# 记录结果: TPS = _____

# 测试 2: 复杂查询（JOIN）
sysbench oltp_read_only --table-name=session_message \
  --mysql-host=<OB_HOST> --mysql-port=2881 \
  --mysql-user=gewu --mysql-password=gewu123456 \
  --mysql-db=gewu_dev \
  --threads=16 --time=60 \
  run

# 通过标准: QPS > 5000, P95 < 100ms
# 记录结果: QPS = _____, P95 = _____ms

# 测试 3: 混合读写（70% 读 + 30% 写）
sysbench oltp_read_write --table-name=session \
  --mysql-host=<OB_HOST> --mysql-port=2881 \
  --mysql-user=gewu --mysql-password=gewu123456 \
  --mysql-db=gewu_dev \
  --threads=32 --time=120 \
  run

# 通过标准: TPS > 1000, P95 < 200ms
# 记录结果: TPS = _____, P95 = _____ms
```

### 3.4 高可用验证

```bash
# 测试 1: 主节点故障切换
# 1. 找到当前主节点
kubectl get pods -n oceanbase -l role=leader
# 记录: leader pod = _____

# 2. 模拟主节点宕机
kubectl delete pod <leader-pod> -n oceanbase

# 3. 观察切换时间
kubectl get pods -n oceanbase -w
# 通过标准: 30 秒内新主节点选举完成
# 记录: 切换时间 = _____秒

# 测试 2: 数据一致性（RPO）
# 1. 在故障前持续写入
sysbench oltp_insert --table-name=user_account \
  --mysql-host=<OB_HOST> --mysql-port=2881 \
  --mysql-user=gewu --mysql-password=gewu123456 \
  --mysql-db=gewu_dev \
  --threads=4 --time=30 \
  run &

# 2. 同时删除主节点
kubectl delete pod <leader-pod> -n oceanbase

# 3. 切换完成后检查数据
kubectl exec -it <new-leader-pod> -n oceanbase -- obclient -h localhost -P 2881 -u root -e "
  SELECT COUNT(*) FROM gewu_dev.user_account;
"
# 通过标准: RPO = 0（无数据丢失）
# 记录: 写入行数 vs 实际行数 = _____ vs _____
```

---

## 4. 测试结果记录

| 测试项 | 通过标准 | 实际结果 | 状态 |
|--------|---------|---------|------|
| OB Operator 部署 | 3 节点 READY | | ⬜ |
| OBProxy 负载均衡 | 读写分离生效 | | ⬜ |
| 32 张表 DDL | 全部执行成功 | | ⬜ |
| JSON 字段 | 正常创建 | | ⬜ |
| ULID 主键 | VARCHAR(26) | | ⬜ |
| INSERT TPS | > 2000 | | ⬜ |
| 复杂查询 QPS | > 5000 | | ⬜ |
| 混合读写 TPS | > 1000 | | ⬜ |
| 故障切换时间 | < 30s | | ⬜ |
| RPO | = 0 | | ⬜ |

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| K8s 集群资源不足 | 中 | 高 | 使用 Docker Compose 单节点替代 |
| OB 4.2.0 镜像拉取失败 | 低 | 高 | 预拉取镜像到私有仓库 |
| DDL 兼容性问题 | 中 | 中 | 逐表排查，记录不兼容语法 |
| 性能不达标 | 中 | 高 | 调整 OB 参数（内存/日志大小）；降级 MySQL 8.0 |
| 故障切换超时 | 低 | 高 | 调整选举超时参数；手动切换方案 |

---

## 6. Go/No-Go 决策

| 决策 | 条件 |
|------|------|
| **Go** | 所有测试项通过，TPS > 2000，RPO = 0 |
| **Conditional Go** | 性能略低（TPS 1500-2000），但功能正常，可优化 |
| **No-Go** | DDL 执行失败 > 3 张表，或 RPO > 0，降级 MySQL 8.0 |

---

**报告填写人**: _______________  **日期**: _______________
