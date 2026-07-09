# 格物平台 — Agent 沙箱设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | Agent 沙箱设计文档 |
| 版本 | V1.1 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 关联统一文档 | 20-unified-prd.md, 21-unified-architecture.md, 24-unified-security.md |
| 源设计文档 | opencode-1.17.14/docs/design/04-sandbox-security.md (V4.0) |
| 设计原则 | 信创适配 + 国密合规 + 等保 2.0 三级 |
| 更新说明 | V1.1: 新增 §8.4 沙箱兼容性矩阵（含已知问题 KC-01~KC-05 和降级路径）；增强 §8.3 国产 OS 支持 |

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
