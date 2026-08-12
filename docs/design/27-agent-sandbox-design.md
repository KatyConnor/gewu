# 格物平台 — Agent 沙箱设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | Agent 沙箱设计文档 |
| 版本 | V1.2 |
| 创建日期 | 2026-07-08 |
| 最后更新 | 2026-07-10 |
| 文档状态 | 已批准 |
| 关联统一文档 | 20-unified-prd.md, 21-unified-architecture.md, 24-unified-security.md |
| 源设计文档 | opencode-1.17.14/docs/design/04-sandbox-security.md (V4.0) |
| 设计原则 | 信创适配 + 国密合规 + 等保 2.0 三级 |
| 更新说明 | V1.1: 新增 §8.4 沙箱兼容性矩阵（含已知问题 KC-01~KC-05 和降级路径）；增强 §8.3 国产 OS 支持。 **V1.2**: 新增 §11 沙箱生命周期管理（三种创建场景、空闲管理、自动启停、过期策略）；新增 §12 沙箱模板与镜像管理（内网镜像仓库、模板匹配、自动拉取）；新增 §13 API 设计（execute API、项目绑定 API）；新增 §14 数据库 DDL 变更（V4 迁移脚本） |

---

## 目录

1. [沙箱架构概述](#1-沙箱架构概述)
2. [沙箱配置模型](#2-沙箱配置模型)
3. [沙箱管理器实现](#3-沙箱管理器实现)
4. [沙箱调度与资源池](#4-沙箱调度与资源池)
5. [安全加固](#5-安全加固)
6. [审计日志](#6-审计日志)
7. [监控告警](#7-监控告警)
8. [信创适配](#8-信创适配)
9. [与统一文档的交叉引用](#9-与统一文档的交叉引用)
10. [总结](#10-总结)

---

## 1. 沙箱架构概述

### 1.1 设计目标

| 目标 | 描述 |
|------|------|
| **隔离性** | 不同用户的任务相互隔离 |
| **安全性** | 防止恶意代码影响宿主系统 |
| **资源控制** | 限制 CPU、内存、磁盘、网络使用 |
| **可审计** | 所有操作可追溯、可审计 |
| **信创合规** | 支持国产 CPU 和操作系统，容器化从 Docker 替换为 iSulad |

### 1.2 沙箱类型（信创适配版）

```
┌─────────────────────────────────────────────────────────────────┐
│                沙箱架构（Firecracker + gVisor + iSulad）          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  本地沙箱 (开发者)                        │   │
│  │                                                         │   │
│  │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │   │
│  │   │  Firecracker│  │  gVisor     │  │  iSulad     │   │   │
│  │   │  (高安全)   │  │  (中安全)   │  │  (快速)     │   │   │
│  │   │  信创支持   │  │  信创支持   │  │  信创目录   │   │   │
│  │   └─────────────┘  └─────────────┘  └─────────────┘   │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  云端沙箱 (测试/QA)                       │   │
│  │                                                         │   │
│  │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │   │
│  │   │  Firecracker│  │  gVisor     │  │  Kata       │   │   │
│  │   │  MicroVM    │  │  用户态内核  │  │  Containers │   │   │
│  │   │  (L1-高安全)│  │  (L2-中安全)│  │  (L1-高安全)│   │   │
│  │   └─────────────┘  └─────────────┘  └─────────────┘   │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 安全等级定义

| 等级 | 技术实现 | 隔离级别 | 适用场景 |
|------|----------|----------|----------|
| **L1** | Firecracker MicroVM / Kata Containers | 硬件级虚拟化 | 不可信代码执行 |
| **L2** | gVisor 用户态内核 | 系统调用拦截 | 内部 Agent 执行 |
| **L3** | iSulad 容器（信创目录） | 操作系统级 | 可信环境快速启动 |

---

## 2. 沙箱配置模型

### 2.1 沙箱配置

```java
@Data
@Builder
public class SandboxConfig {
    private String sandboxId;
    private SandboxType type;  // FIRECRACKER, GVISOR, ISULAD, KATA
    private String image;
    private int cpuLimit;      // CPU 限制 (millicores)
    private long memoryLimit;  // 内存限制 (bytes)
    private long diskLimit;    // 磁盘限制 (bytes)
    private int networkLimit;  // 网络限制 (Mbps)
    private List<String> allowedPaths;
    private List<String> blockedCommands;
    private NetworkConfig network;
    private SecurityPolicy security;
    private Duration timeout;
    private SecurityLevel securityLevel;  // L1, L2, L3
}

public enum SandboxType {
    FIRECRACKER,    // Firecracker MicroVM (L1 - 高安全，硬件级隔离)
    GVISOR,         // gVisor 用户态内核 (L2 - 中安全，系统调用拦截)
    ISULAD,         // iSulad 容器 (L3 - 快速，信创目录)
    KATA            // Kata Containers (L1 - 高安全，K8s 原生)
}

public enum SecurityLevel {
    L1,  // 硬件级隔离 (Firecracker/Kata) - 不可信代码执行
    L2,  // 用户态内核 (gVisor) - 内部 Agent 执行
    L3   // 容器级 (iSulad) - 可信环境快速启动，信创目录
}
```

### 2.2 安全策略配置

```java
@Data
@Builder
public class SecurityPolicy {
    private FileSystemPolicy fileSystem;
    private NetworkPolicy network;
    private ProcessPolicy process;
    private ResourcePolicy resource;
    private AuditPolicy audit;
}
```

| 策略类型 | 配置项 | 说明 |
|----------|--------|------|
| **FileSystemPolicy** | readOnlyPaths, readWritePaths, blockedPaths, maxFileSize, maxDiskUsage | 文件系统访问控制 |
| **NetworkPolicy** | allowedDomains, blockedDomains, allowedPorts, maxBandwidth | 网络访问控制 |
| **ProcessPolicy** | allowedCommands, blockedCommands, maxProcesses, maxExecutionTime | 进程执行控制 |
| **ResourcePolicy** | cpuLimit, memoryLimit, diskLimit, networkLimit | 资源使用限制 |
| **AuditPolicy** | logCommands, logFileAccess, retentionDays | 审计策略 |

---

## 3. 沙箱管理器实现

### 3.1 沙箱管理器接口

```java
public interface SandboxManager {
    Sandbox createSandbox(SandboxConfig config);
    ProcessResult executeCommand(String sandboxId, String command);
    void destroySandbox(String sandboxId);
    SandboxSnapshot snapshotSandbox(String sandboxId);
    void restoreSandbox(String sandboxId, SandboxSnapshot snapshot);
}
```

### 3.2 Firecracker MicroVM 沙箱（L1 - 高安全）

**特性**：
- 硬件级虚拟化隔离
- 每个 MicroVM 有独立内核
- IP 地址池管理（172.16.0.x 段）
- 支持快照和恢复

**实现要点**：
```java
@Component
@ConditionalOnProperty(name = "sandbox.type", havingValue = "firecracker")
public class FirecrackerSandboxManager implements SandboxManager {
    // IP 地址池管理
    private final Queue<Integer> availableIps = new ConcurrentLinkedQueue<>();
    private final Set<Integer> usedIps = ConcurrentHashMap.newKeySet();
    private static final int IP_POOL_START = 10;
    private static final int IP_POOL_END = 254;

    // 创建 MicroVM → 配置网络（IP 池分配）→ 挂载文件系统 → 返回沙箱
    // 销毁时回收 IP 地址
}
```

### 3.3 gVisor 沙箱（L2 - 中安全）

**特性**：
- 用户态内核拦截系统调用
- 使用 runsc 运行时
- Docker API 兼容

**实现要点**：
```java
@Component
@ConditionalOnProperty(name = "sandbox.type", havingValue = "gvisor")
public class GVisorSandboxManager implements SandboxManager {
    // 使用 Docker 客户端创建容器，运行时设置为 runsc (gVisor)
    // CPU/memory 限制通过 HostConfig 设置
}
```

### 3.4 iSulad 沙箱（L3 - 快速，信创目录）

**特性**：
- 华为开源容器引擎，信创目录内组件
- Docker 命令兼容
- 支持国产 CPU（鲲鹏/飞腾）和 OS（麒麟/统信）

### 3.5 Kubernetes Pod 沙箱（云端）

**特性**：
- K8s Pod 级别的隔离
- 资源限制通过 ResourceRequirements 设置
- 命名空间隔离（gewu-sandbox）

**实现要点**：
```java
@Component
@ConditionalOnProperty(name = "sandbox.type", havingValue = "kubernetes")
public class KubernetesSandboxManager implements SandboxManager {
    // 创建 Pod → 配置资源限制 → 设置卷挂载 → 返回沙箱
    // 使用 K8s exec API 执行命令
}
```

---

## 4. 沙箱调度与资源池

### 4.1 沙箱调度器

```java
@Component
@RequiredArgsConstructor
public class SandboxScheduler {
    private final ResourcePoolManager resourcePool;
    private final QueueService queueService;
    private final NodeManager nodeManager;

    public SandboxAllocation schedule(SandboxRequest request) {
        // 1. 检查资源配额
        // 2. 选择最佳节点（负载最低）
        // 3. 分配资源
        // 4. 创建沙箱
    }
}
```

调度策略：
- **最小负载优先**：选择负载评分最低的节点
- **亲和性调度**：支持数据本地化调度
- **资源预检查**：分配前检查资源容量

### 4.2 资源池管理器

| 资源类型 | 池实现 | 说明 |
|----------|--------|------|
| CPU | CpuResourcePool | millicores 粒度分配 |
| 内存 | MemoryResourcePool | bytes 粒度分配 |
| 磁盘 | DiskResourcePool | bytes 粒度分配 |
| 网络 | NetworkResourcePool | Mbps 带宽分配 |

### 4.3 容器预热池

预热池配置（减少沙箱启动延迟）：

| 规格 | 预热数量 | 用途 |
|------|----------|------|
| Small | 100 | 轻量任务 |
| Medium | 50 | 标准任务 |
| Large | 20 | 计算密集型任务 |

```java
@Component
public class WarmPoolManager {
    @PostConstruct
    public void init() {
        initializeWarmPool("small", 100);
        initializeWarmPool("medium", 50);
        initializeWarmPool("large", 20);
    }

    // 虚拟线程自动补池，每 5 秒检查一次
    private void startRefillThread(String tier, int targetCount) {
        Thread.startVirtualThread(() -> {
            while (true) {
                // 检查池容量，不足则补充
                Thread.sleep(5000);
            }
        });
    }
}
```

### 4.4 本地沙箱实现（开发者模式）

```yaml
# application.yml (本地客户端)
sandbox:
  type: docker
  image: gewu/sandbox-base:latest
  resource-limits:
    cpu: 2000      # 2 cores
    memory: 4294967296  # 4GB
    disk: 10737418240   # 10GB
  security:
    blocked-commands:
      - "rm -rf /"
      - "mkfs"
      - "dd"
      - "format"
    blocked-paths:
      - "/etc"
      - "/var"
      - "/proc"
      - "/sys"
  timeout: 3600  # 1小时
```

---

## 5. 安全加固

### 5.1 容器安全配置

```yaml
# Docker/iSulad 安全配置
security_opt:
  - no-new-privileges:true
  - apparmor:docker-default

cap_drop:
  - ALL

cap_add:
  - NET_BIND_SERVICE

read_only: true

tmpfs:
  - /tmp
  - /var/run

volumes:
  - /workspace
```

### 5.2 网络隔离

```yaml
# 网络配置
networks:
  sandbox-network:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16
          gateway: 172.20.0.1
```

网络隔离策略：
- 每个沙箱分配独立 IP（172.16.0.x 段）
- VLAN 隔离不同用户
- eBPF 网络策略控制
- DNS 白名单（仅允许特定域名解析）
- 端口白名单（仅开放必要端口）

### 5.3 资源限制

```yaml
# 资源限制
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 4G
      pids: 100
    reservations:
      cpus: '0.5'
      memory: 1G
```

### 5.4 安全等级配置

| 配置项 | L1 (Firecracker) | L2 (gVisor) | L3 (iSulad) |
|--------|-----------------|-------------|-------------|
| seccomp | 默认 + 自定义 | 默认 | 默认 |
| AppArmor | 强制 | 强制 | 可选 |
| Capabilities | 全部删除 | 保留 NET_BIND_SERVICE | 保留常用 |
| 只读根文件系统 | 是 | 是 | 可选 |
| 用户命名空间 | 是 | 是 | 否 |

---

## 6. 审计日志

### 6.1 审计日志表

```sql
CREATE TABLE sandbox_audit_log (
    id VARCHAR(26) PRIMARY KEY,
    sandbox_id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource VARCHAR(64) NOT NULL,
    details JSON,
    ip_address VARCHAR(45),
    timestamp BIGINT NOT NULL
);

CREATE INDEX idx_audit_sandbox ON sandbox_audit_log(sandbox_id);
CREATE INDEX idx_audit_user ON sandbox_audit_log(user_id);
CREATE INDEX idx_audit_time ON sandbox_audit_log(timestamp DESC);
```

### 6.2 审计服务

审计记录范围：
- **命令执行**：记录所有在沙箱中执行的命令
- **文件访问**：记录文件的读写操作
- **网络请求**：记录网络连接请求
- **沙箱生命周期**：创建、销毁、快照等
- **权限变更**：安全策略更改

审计数据保留 1 年（安全事件日志保留 3 年），满足等保 2.0 三级要求（见 24-unified-security.md）。

---

## 7. 监控告警

### 7.1 监控指标

| 指标名称 | 类型 | 说明 |
|----------|------|------|
| `sandbox_count_total` | gauge | 总沙箱数量 |
| `sandbox_count_running` | gauge | 运行中沙箱数量 |
| `sandbox_count_pending` | gauge | 等待中沙箱数量 |
| `sandbox_cpu_usage` | gauge | 沙箱 CPU 使用率 |
| `sandbox_memory_usage` | gauge | 沙箱内存使用率 |
| `sandbox_creation_time` | histogram | 沙箱创建时间 |
| `sandbox_execution_time` | histogram | 沙箱执行时间 |
| `sandbox_creation_failed_total` | counter | 沙箱创建失败总数 |

### 7.2 告警规则

| 告警名称 | 规则 | 严重级别 |
|----------|------|----------|
| SandboxResourceExhausted | CPU > 90% 或 内存 > 90% 持续 5 分钟 | warning |
| SandboxCreationFailureRate | 创建失败率 > 10% (5 分钟窗口) | warning |
| SandboxHighLatency | P95 创建延迟 > 10 秒 (5 分钟窗口) | warning |

---

## 8. 信创适配

### 8.1 容器运行时替换

| 组件 | 非信创环境 | 信创环境 |
|------|-----------|----------|
| 容器引擎 | Docker CE | iSulad（华为开源，信创目录） |
| 轻量虚拟化 | Firecracker MicroVM | Firecracker（ARM64 支持已验证） |
| 沙箱运行时 | gVisor / runsc | gVisor（LoongArch 支持） |
| K8s 容器 | Docker + containerd | iSulad + KubeEdge |

### 8.2 国产 CPU 支持

| CPU 架构 | 沙箱支持状态 | 说明 |
|----------|-------------|------|
| 鲲鹏 ARM64 | ✅ 已验证 | Firecracker/gVisor/iSulad 均支持 |
| 飞腾 ARM64 | ✅ 已验证 | iSulad 原生支持 |
| 龙芯 LoongArch | ⚠️ 部分支持 | gVisor 需编译适配 |
| 海光 x86 | ✅ 已验证 | 全系列沙箱支持 |
| 兆芯 x86 | ✅ 已验证 | 全系列沙箱支持 |

### 8.3 国产 OS 支持

| 操作系统 | 兼容性 | L1 (Firecracker) | L2 (gVisor) | L3 (iSulad) | 说明 |
|----------|--------|-----------------|-------------|-------------|------|
| 麒麟 V10 SP1+ | ✅ 已验证 | ✅ | ✅ | ✅ | 全系列沙箱支持 |
| 统信 UOS V20 1060+ | ✅ 已验证 | ⚠️ 需 KVM 支持 | ✅ | ✅ | iSulad + gVisor 稳定运行 |
| 中科方德 4.0+ | ⚠️ 部分兼容 | ❌ 无 KVM | ⚠️ 测试中 | ✅ | L3 可用 |
| 欧拉 (openEuler) 22.03+ | ✅ 已验证 | ✅ | ✅ | ✅ | 全系列沙箱支持 |

### 8.4 沙箱兼容性矩阵与已知问题

#### 信创环境兼容性矩阵

| L# | 技术 | 鲲鹏 ARM64 | 飞腾 ARM64 | 龙芯 LoongArch | 海光 x86 | 麒麟 V10 | 统信 UOS |
|----|------|-----------|-----------|---------------|---------|---------|---------|
| L1 | Firecracker | ✅ | ⚠️ 测试中 | ❌ 不支持 | ✅ | ✅ | ⚠️ 需 KVM |
| L1 | Kata Containers | ✅ | ✅ | ⚠️ 测试中 | ✅ | ✅ | ✅ |
| L2 | gVisor | ✅ | ✅ | ⚠️ 需交叉编译 | ✅ | ✅ | ✅ |
| L3 | iSulad | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

> ❌ = 不支持 / ⚠️ = 有限支持 / ✅ = 已验证通过

#### 已知兼容性问题

| 编号 | 问题描述 | 影响范围 | 缓解措施 | 优先级 |
|------|---------|---------|---------|--------|
| KC-01 | 龙芯 LoongArch 不支持 Firecracker MicroVM（缺少 KVM） | L1 沙箱在龙芯不可用 | 降级为 L2 gVisor + 编译适配 | P1 |
| KC-02 | 统信 UOS 部分内核版本缺少 KVM 模块 | L1 沙箱在 UOS 受限 | 升级内核 / 使用 Kata Containers 替代 | P1 |
| KC-03 | 飞腾 S2500 + Firecracker 联合测试进行中 | 飞腾 L1 暂未完成验证 | 优先级较低，先使用 Kata Containers | P2 |
| KC-04 | gVisor LoongArch 需要交叉编译 runsc 二进制 | L2 在龙芯性能可能下降 | 使用 QEMU 用户态模拟编译 | P2 |
| KC-05 | 鲲鹏 920 上 Firecracker 的 ARM64 支持已验证，但 MicroVM 启动时间比 x86 长约 30% | L1 启动延迟增加 | 预热池增加 20% 余量 | P3 |

#### 降级路径定义

| 场景 | 首选方案 | 降级方案 (自动) | 降级方案 (手动) |
|------|---------|---------------|---------------|
| 龙芯部署 | L2 gVisor | L3 iSulad | 等待 Firecracker LoongArch 支持 |
| 无 KVM 环境 | L2 gVisor | L3 iSulad | 安装 Kata Containers |
| 鲲鹏/飞腾 | L1 Firecracker | L2 gVisor | L3 iSulad |
| 麒麟/欧拉 | L1 Firecracker | L2 gVisor | L3 iSulad |

---

## 9. 与统一文档的交叉引用

### 9.1 PRD 对应关系

| PRD 用户故事 | 沙箱设计对应章节 | 说明 |
|-------------|-----------------|------|
| US-SB-01（沙箱执行代码） | §3 沙箱管理器实现 | 4 种沙箱类型满足不同安全需求 |
| US-SB-02（运行时选择） | §1.3 安全等级定义 | L1/L2/L3 三级可选 |
| US-SB-03（资源限制） | §5.3 资源限制 | CPU/内存/磁盘/网络精细控制 |
| US-SB-04（安全策略） | §5 安全加固 | seccomp/AppArmor/capabilities |

### 9.2 架构文档对应关系

| 架构文档章节 | 沙箱设计对应章节 | 说明 |
|-------------|-----------------|------|
| 21-unified-architecture.md §6.4 沙箱模块 | §3 沙箱管理器 + §4 调度与资源池 | 完整架构实现 |
| 21-unified-architecture.md §9 技术选型 | §8 信创适配 | 信创组件替换方案 |

### 9.3 安全文档对应关系

| 安全文档章节 | 沙箱设计对应章节 | 说明 |
|-------------|-----------------|------|
| 24-unified-security.md §6 沙箱隔离 | §5 安全加固 | 沙箱安全配置对齐 |
| 24-unified-security.md §7 审计日志 | §6 审计日志 | 审计策略一致 |
| 24-unified-security.md §10 信创合规 | §8 信创适配 | 国密+信创组件|

---

## 10. 总结

### 10.1 架构优势

| 特性 | 说明 |
|------|------|
| **多层隔离** | 容器、进程、文件系统多层隔离 |
| **资源控制** | CPU、内存、磁盘、网络精细控制 |
| **安全审计** | 所有操作可追溯、可审计 |
| **弹性伸缩** | 根据负载自动扩缩容 |
| **成本优化** | 预热池、资源回收、冷热分层 |
| **信创合规** | 100% 支持国产 CPU/OS/容器引擎 |

### 10.2 与统一设计原则的一致性

| 设计原则 | 沙箱实现 | 一致性 |
|----------|----------|--------|
| 国密算法 | 沙箱通信加密（SM4-GCM） | ✅ |
| 等保三级 | 审计日志 + 访问控制 | ✅ |
| 信创适配 | iSulad + 国产 CPU/OS | ✅ |
| DDD 分层 | SandboxManager 接口抽象 | ✅ |
| 可扩展性 | 支持新增沙箱类型 | ✅ |

---

## 11. 沙箱生命周期管理（V1.2 新增）

### 11.1 三种创建场景

沙箱根据创建来源分为三种场景，每种场景有不同的生命周期管理策略：

```
┌─────────────────────────────────────────────────────────────────┐
│                     沙箱三种创建场景                              │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  手动创建     │  │  Agent 自动  │  │  项目绑定    │          │
│  │  source=manual│  │  source=agent│  │ source=project│         │
│  │  用户主动     │  │  任务驱动    │  │  环境一致    │          │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘          │
│         │                 │                  │                   │
│         ↓                 ↓                  ↓                   │
│  ┌─────────────────────────────────────────────────────┐        │
│  │            SandboxService (统一生命周期管理)          │        │
│  │                                                      │        │
│  │  create → start → [running] → stop → start → destroy│        │
│  │            ↑           ↓         ↑                  │        │
│  │            │      空闲超时?      │                   │        │
│  │            └── auto-stop ────────┘                   │        │
│  └─────────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────┘
```

#### 场景一：手动创建沙箱

| 属性 | 值 | 说明 |
|------|-----|------|
| `source` | `manual` | 标识为用户手动创建 |
| `autoDestroy` | `false` | 不自动销毁 |
| 生命周期 | 用户完全控制 | 用户手动启动/停止/销毁 |
| 空闲管理 | 超时自动停止 | 默认 30 分钟无操作自动 stop |
| 过期策略 | 默认 7 天 TTL | 过期提醒，可续期 |

**状态流转**：
```
创建中 → 运行中 ──[用户点停止]──→ 已停止 ──[用户点启动]──→ 运行中
   │         │                                              │
   │         └──[空闲超时 30min]──→ 已停止 ──[用户点启动]──→ 运行中
   │                                                              │
   └──[用户点销毁]────────────────────────────────→ 已销毁
```

#### 场景二：Agent 自动创建沙箱

| 属性 | 值 | 说明 |
|------|-----|------|
| `source` | `agent` | 标识为 Agent 自动创建 |
| `autoDestroy` | `true` | 执行完自动销毁 |
| `agentId` | 关联 Agent | 记录由哪个 Agent 创建 |
| 生命周期 | 短生命周期 | 执行完立即销毁 |
| 镜像选择 | 自动匹配 | 根据代码语言自动选择镜像 |

**状态流转**：
```
创建中 → 运行中 → 执行命令 → 返回结果 → 自动销毁
```

**触发流程**：
1. 用户发消息 "帮我执行这段 Python 代码"
2. Agent 生成代码
3. `ToolExecutionService` 判断需要沙箱执行
4. 自动选择模板（根据语言 → 镜像映射）
5. 调用 `SandboxService.executeCode()` → 创建 → 启动 → 执行 → 返回结果 → 销毁

#### 场景三：项目绑定沙箱

| 属性 | 值 | 说明 |
|------|-----|------|
| `source` | `project` | 标识为项目绑定 |
| `autoDestroy` | `false` | 项目存续期间保留 |
| `projectId` | 关联项目 | 绑定到指定项目 |
| 生命周期 | 跟随项目 | 项目归档时销毁 |
| 镜像选择 | 根据 techStack | 从项目技术栈自动匹配 |

**状态流转**：
```
项目创建 → 沙箱创建 → 运行中 ──[空闲超时]──→ 已停止
              │                           ↓
              │           项目成员使用 → 启动 → 运行中
              │                           ↓
              └──[项目归档]────────────→ 已销毁
```

### 11.2 空闲管理与自动启停

#### 设计原则

- **停止 ≠ 销毁**：停止只是 `docker stop`，容器仍在，秒级恢复
- **销毁 = 删除容器**：`docker rm`，需要重新创建
- 空闲检测通过定时任务实现（每 5 分钟检查一次）

#### 空闲自动停止策略

```
┌────────────────────────────────────────────────────┐
│  空闲自动停止策略                                    │
│                                                     │
│  沙箱运行中 ──→ 检测最后使用时间                      │
│       │         ↓                                   │
│       │    超过空闲阈值?                             │
│       │    ├─ 否 → 继续运行                         │
│       │    └─ 是 → 自动停止（保留容器）              │
│       ↓                                             │
│  用户/Agent 需要使用                                │
│       ↓                                             │
│  启动沙箱（秒级恢复，容器已存在）                     │
└────────────────────────────────────────────────────┘
```

#### 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `gewu.sandbox.idle-timeout-minutes` | 30 | 空闲超时自动停止（分钟） |
| `gewu.sandbox.auto-stop-enabled` | true | 是否启用空闲自动停止 |
| `gewu.sandbox.manual-ttl-days` | 7 | 手动创建沙箱默认过期天数 |
| `gewu.sandbox.project-ttl-days` | 0 | 项目沙箱过期天数（0=跟随项目） |
| `gewu.sandbox.agent-max-lifetime-seconds` | 300 | Agent 沙箱最大存活时间 |

#### 定时任务

```java
@Component
public class SandboxScheduler {

    @Scheduled(fixedRate = 300000) // 每 5 分钟
    public void autoStopIdleSandboxes() {
        // 1. 查询所有运行中的沙箱
        // 2. 检查 lastUsedAt 是否超过 idle-timeout
        // 3. 超过则自动 stop（仅 manual/project 类型）
        // 4. Agent 类型不停止（由 autoDestroy 处理）
    }

    @Scheduled(fixedRate = 3600000) // 每 1 小时
    public void checkExpiredSandboxes() {
        // 1. 查询所有未过期沙箱
        // 2. 检查 expireAt 是否已过
        // 3. 过期则标记 EXPIRED 并停止
    }
}
```

### 11.3 沙箱状态机

```
                    ┌─────────────────────────────────────────────┐
                    │                                             │
                    ↓                                             │
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐     │
│ CREATING │───→│ RUNNING  │───→│ STOPPED  │───→│ DESTROYED│     │
│ (创建中) │    │ (运行中) │    │ (已停止) │    │ (已销毁) │     │
└──────────┘    └────┬─────┘    └────┬─────┘    └──────────┘     │
                     │               │                            │
                     │               └──[启动]────────────────────→│
                     │                                            │
                     ├──[空闲超时]──→ STOPPED                     │
                     │                                            │
                     ├──[执行异常]──→ ERROR ──→ STOPPED           │
                     │                                            │
                     └──[过期/销毁]──→ DESTROYED                  │
                                                          ↑
                                                          │
                     ERROR ──[恢复]────────────────────→ RUNNING
```

| 状态 | 说明 | 可执行操作 |
|------|------|-----------|
| `CREATING` | 创建中 | 无（等待） |
| `RUNNING` | 运行中 | exec / stop / destroy |
| `STOPPED` | 已停止 | start / destroy |
| `DESTROYED` | 已销毁 | 无（终态） |
| `ERROR` | 异常 | stop / destroy / recover |
| `EXPIRED` | 已过期 | renew / destroy |

---

## 12. 沙箱模板与镜像管理（V1.2 新增）

### 12.1 内网镜像仓库

在企业内网环境中，无法访问 Docker Harbor 等外部镜像仓库，需要使用内部私有镜像仓库。

#### 架构设计

```
┌──────────────────────────────────────────────────┐
│  内部镜像仓库 (Harbor/Nexus)                       │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │ sandbox-   │ │ sandbox-   │ │ sandbox-   │    │
│  │ python:3.12│ │ node:20    │ │ openjdk:21 │    │
│  └────────────┘ └────────────┘ └────────────┘    │
└──────────────────────────────────────────────────┘
          ↑ 配置镜像仓库地址 + 认证
┌──────────────────────────────────────────────────┐
│  gewu-sandbox 服务                                │
│  sandbox.registry.url = https://harbor.internal   │
│  sandbox.registry.username = xxx                  │
│  sandbox.registry.password = xxx                  │
│  DockerClient 创建时配置 Registry 认证             │
└──────────────────────────────────────────────────┘
```

#### 配置

```yaml
gewu:
  sandbox:
    registry:
      url: https://harbor.internal:5000
      username: ${REGISTRY_USER}
      password: ${REGISTRY_PASS}
      repository: gewu/sandbox
      insecure: false  # 是否允许 HTTP
    defaults:
      image: harbor.internal:5000/gewu/sandbox/python:3.12
```

#### DockerClient 认证配置

```java
@Bean
public DockerClient dockerClient() {
    DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerHost)
            .withRegistryUrl(registryUrl)
            .withRegistryUsername(registryUsername)
            .withRegistryPassword(registryPassword)
            .build();
    // ...
}
```

### 12.2 沙箱模板管理

#### 模板定义

```java
public enum SandboxTemplate {
    PYTHON("python", "harbor.internal:5000/gewu/sandbox/python:3.12", "Python 数据科学", 1, 2048),
    NODEJS("node", "harbor.internal:5000/gewu/sandbox/node:20", "Node.js 全栈", 1, 2048),
    JAVA("java", "harbor.internal:5000/gewu/sandbox/openjdk:21", "Java 后端", 2, 4096),
    SHELL("shell", "harbor.internal:5000/gewu/sandbox/alpine:3.20", "通用命令行", 0.5, 512);

    private final String code;
    private final String image;
    private final String displayName;
    private final double defaultCpu;
    private final int defaultMemoryMb;
}
```

#### 模板匹配逻辑

```java
@Component
public class SandboxTemplateMatcher {

    // 根据代码语言匹配镜像
    public String resolveImage(String language) {
        return switch (language.toLowerCase()) {
            case "python", "py" -> SandboxTemplate.PYTHON.getImage();
            case "javascript", "js", "typescript", "ts" -> SandboxTemplate.NODEJS.getImage();
            case "java", "kotlin", "scala", "groovy" -> SandboxTemplate.JAVA.getImage();
            case "shell", "bash", "sh" -> SandboxTemplate.SHELL.getImage();
            default -> SandboxTemplate.SHELL.getImage();
        };
    }

    // 从项目 techStack JSON 解析语言匹配镜像
    public String resolveFromTechStack(String techStack) {
        // techStack 示例: {"language": "python", "framework": "django"}
        // 解析后返回对应镜像
    }

    // 根据 Agent 任务类型匹配
    public String resolveFromTaskType(String taskType) {
        return switch (taskType) {
            case "code_execute", "test_run" -> SandboxTemplate.PYTHON.getImage();
            case "build", "compile" -> SandboxTemplate.JAVA.getImage();
            case "data_analysis" -> SandboxTemplate.PYTHON.getImage();
            default -> SandboxTemplate.SHELL.getImage();
        };
    }
}
```

#### 预设模板与资源配比

| 模板名称 | 镜像 | 默认 CPU | 默认内存 | 适用场景 |
|----------|------|----------|----------|----------|
| Python 数据分析 | `harbor.internal/gewu/sandbox/python:3.12` | 1 核 | 2GB | Python 代码执行、数据处理、ML 推理 |
| Node.js 全栈 | `harbor.internal/gewu/sandbox/node:20` | 1 核 | 2GB | Node.js 代码执行、前端构建 |
| Java 后端 | `harbor.internal/gewu/sandbox/openjdk:21` | 2 核 | 4GB | Java 编译运行、Maven/Gradle 构建 |
| 通用命令行 | `harbor.internal/gewu/sandbox/alpine:3.20` | 0.5 核 | 512MB | 通用命令行操作、Shell 脚本 |

### 12.3 镜像自动拉取

创建容器前检查镜像是否存在，不存在则从内部仓库拉取：

```java
@Override
public Sandbox create(CreateSandboxCommand command) {
    String image = command.getImage() != null ? command.getImage() : defaultImage;

    // 检查镜像是否存在，不存在则拉取
    ensureImageExists(image);

    // ... 原有创建容器逻辑
}

private void ensureImageExists(String image) {
    try {
        dockerClient.inspectImageCmd(image).exec();
        log.debug("镜像 {} 已存在", image);
    } catch (NotFoundException e) {
        log.info("镜像 {} 不存在，开始从内部仓库拉取...", image);
        try {
            dockerClient.pullImageCmd(image)
                .exec(new PullImageResultCallback())
                .awaitCompletion(120, TimeUnit.SECONDS);
            log.info("镜像 {} 拉取完成", image);
        } catch (Exception pullException) {
            throw new SandboxException("镜像拉取失败: " + image, pullException);
        }
    }
}
```

### 12.4 镜像白名单

为安全性考虑，仅允许使用内部仓库预定义的镜像：

```java
public class SandboxSecurityPolicy {
    // 允许的镜像前缀（内部仓库）
    private static final List<String> ALLOWED_IMAGE_PREFIXES = List.of(
        "harbor.internal:5000/gewu/sandbox/"
    );

    public static boolean isImageAllowed(String image) {
        return ALLOWED_IMAGE_PREFIXES.stream().anyMatch(image::startsWith);
    }
}
```

---

## 13. API 设计（V1.2 新增）

### 13.1 API 总览

| API | 方法 | 说明 | 调用方 |
|-----|------|------|--------|
| `/api/v1/sandboxes` | POST | 手动创建沙箱 | 前端 |
| `/api/v1/sandboxes` | GET | 获取沙箱列表 | 前端 |
| `/api/v1/sandboxes/{id}` | GET | 获取沙箱详情 | 前端 |
| `/api/v1/sandboxes/{id}` | DELETE | 销毁沙箱 | 前端 |
| `/api/v1/sandboxes/{id}/start` | POST | 启动沙箱 | 前端 |
| `/api/v1/sandboxes/{id}/stop` | POST | 停止沙箱 | 前端 |
| `/api/v1/sandboxes/{id}/exec` | POST | 执行命令 | 前端/Agent |
| `/api/v1/sandboxes/{id}/logs` | GET | 获取操作日志 | 前端 |
| `/api/v1/sandboxes/execute` | POST | Agent 自动执行代码（短生命周期） | Agent |
| `/api/v1/sandboxes/project/{projectId}` | POST | 创建项目绑定沙箱 | 前端/项目服务 |
| `/api/v1/sandboxes/project/{projectId}` | DELETE | 销毁项目绑定沙箱 | 前端/项目服务 |
| `/api/v1/sandboxes/{id}/expire` | PUT | 更新过期时间/续期 | 前端 |

### 13.2 Agent 自动执行 API

```
POST /api/v1/sandboxes/execute
Content-Type: application/json

{
  "language": "python",
  "code": "print('hello world')",
  "timeout": 30
}

Response 200:
{
  "code": 10000,
  "message": "操作成功",
  "data": {
    "exitCode": 0,
    "stdout": "hello world\n",
    "stderr": "",
    "duration": 1234
  }
}
```

**内部流程**：
1. `SandboxTemplateMatcher.resolveImage(language)` → 匹配镜像
2. `SandboxService.createSandbox(command)` → 创建沙箱（source=agent, autoDestroy=true）
3. `SandboxService.startSandbox(id)` → 启动
4. `SandboxService.execCommand(id, code, timeout)` → 执行代码
5. 返回执行结果
6. `SandboxService.destroySandbox(id)` → 自动销毁

### 13.3 项目绑定 API

```
POST /api/v1/sandboxes/project/{projectId}
Content-Type: application/json

{
  "template": "python"  // 可选，不传则从项目 techStack 推断
}

Response 200:
{
  "code": 10000,
  "message": "操作成功",
  "data": {
    "sandboxId": "01KX...",
    "sandboxName": "项目XXX-开发环境",
    "image": "harbor.internal:5000/gewu/sandbox/python:3.12",
    "status": "running"
  }
}
```

### 13.4 沙箱列表增强

```
GET /api/v1/sandboxes?source=manual&status=running

Response 200:
{
  "code": 10000,
  "data": [
    {
      "sandboxId": "01KX...",
      "sandboxName": "我的Python环境",
      "image": "harbor.internal:5000/gewu/sandbox/python:3.12",
      "status": "running",
      "statusDesc": "运行中",
      "source": "manual",
      "projectId": null,
      "cpuCores": 1,
      "memoryMb": 2048,
      "createdAt": 1783660000000,
      "lastUsedAt": 1783665000000,
      "expireAt": 1784265000000
    }
  ]
}
```

---

## 14. 数据库 DDL 变更（V1.2 新增）

### 14.1 V4 迁移脚本

```sql
-- V4: 沙箱生命周期管理字段 + 项目绑定
-- 文档: docs/design/27-agent-sandbox-design.md §11-§14

-- 新增生命周期字段
ALTER TABLE sandbox ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'manual'
    COMMENT '创建来源: manual/agent/project';
ALTER TABLE sandbox ADD COLUMN project_id VARCHAR(26) DEFAULT NULL
    COMMENT '关联项目ID';
ALTER TABLE sandbox ADD COLUMN agent_id VARCHAR(26) DEFAULT NULL
    COMMENT '关联AgentID';
ALTER TABLE sandbox ADD COLUMN auto_destroy TINYINT DEFAULT 0
    COMMENT '是否自动销毁: 0=否 1=是';
ALTER TABLE sandbox ADD COLUMN last_used_at BIGINT DEFAULT NULL
    COMMENT '最后使用时间(毫秒时间戳)';
ALTER TABLE sandbox ADD COLUMN expire_at BIGINT DEFAULT NULL
    COMMENT '过期时间(毫秒时间戳)';

-- 新增索引
ALTER TABLE sandbox ADD KEY idx_sandbox_project (project_id);
ALTER TABLE sandbox ADD KEY idx_sandbox_agent (agent_id);
ALTER TABLE sandbox ADD KEY idx_sandbox_source (source);
ALTER TABLE sandbox ADD KEY idx_sandbox_expire (expire_at);
```

### 14.2 Sandbox 实体字段变更

| 字段 | 类型 | 新增/已有 | 说明 |
|------|------|----------|------|
| id | VARCHAR(26) | 已有 | ULID 主键 |
| sandboxName | VARCHAR(128) | 已有 | 沙箱名称 |
| containerId | VARCHAR(128) | 已有 | Docker 容器 ID |
| status | VARCHAR(32) | 已有 | 状态 |
| image | VARCHAR(256) | 已有 | 镜像地址 |
| cpuLimit | INT | 已有 | CPU 限制 |
| memoryLimitMb | INT | 已有 | 内存限制 |
| diskLimitMb | INT | 已有 | 磁盘限制 |
| networkEnabled | INT | 已有 | 网络启用 |
| runtime | VARCHAR(32) | 已有 | 运行时类型 |
| **source** | **VARCHAR(16)** | **新增** | **创建来源** |
| **projectId** | **VARCHAR(26)** | **新增** | **关联项目** |
| **agentId** | **VARCHAR(26)** | **新增** | **关联 Agent** |
| **autoDestroy** | **TINYINT** | **新增** | **自动销毁** |
| lastUsedAt | BIGINT | 已有 | 最后使用 |
| stoppedAt | BIGINT | 已有 | 停止时间 |
| **expireAt** | **BIGINT** | **新增** | **过期时间** |

---

## 15. 实施计划（V1.2 新增）

### 15.1 分阶段实施

| 阶段 | 内容 | 优先级 | 预估工时 |
|------|------|--------|----------|
| **Phase 1** | 数据模型 + DDL V4 + 模板匹配 + execute API | P0 | 3 天 |
| **Phase 2** | 空闲自动停止 + 定时任务 + 过期管理 + 镜像自动拉取 | P0 | 2 天 |
| **Phase 3** | Agent 集成（ToolExecutionService 调用沙箱） | P1 | 2 天 |
| **Phase 4** | 项目绑定 + 前端适配 | P1 | 2 天 |

### 15.2 Phase 1 详细任务

| 任务 | 文件 | 说明 |
|------|------|------|
| DDL 迁移 | `V4__sandbox_lifecycle.sql` | 新增字段 + 索引 |
| 实体更新 | `Sandbox.java` | 新增字段 |
| DTO 更新 | `SandboxDTO.java` | 新增字段 |
| Command 更新 | `CreateSandboxCommand.java` | 新增 source/projectId/autoDestroy/template |
| 模板匹配 | `SandboxTemplateMatcher.java` | **新增** 模板匹配服务 |
| Service 更新 | `SandboxService.java` | 新增 executeCode/createForProject |
| Controller 更新 | `SandboxController.java** | 新增 /execute, /project/{id}, /{id}/expire |
| 配置更新 | `application.yml` | 新增空闲超时/TTL/镜像仓库配置 |

---

## 16. 版本变更记录

| 版本 | 日期 | 变更内容 | 作者 |
|------|------|----------|------|
| V1.0 | 2026-07-08 | 初始版本：沙箱架构、配置模型、管理器实现、调度资源池、安全加固、审计日志、监控告警、信创适配 | 架构组 |
| V1.1 | 2026-07-08 | 新增 §8.4 沙箱兼容性矩阵；增强 §8.3 国产 OS 支持 | 架构组 |
| **V1.2** | **2026-07-10** | **新增 §11 沙箱生命周期管理；新增 §12 沙箱模板与镜像管理；新增 §13 API 设计；新增 §14 数据库 DDL 变更；新增 §15 实施计划** | **架构组** |
