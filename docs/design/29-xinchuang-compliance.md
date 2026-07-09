# 格物平台 — 信创合规适配文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 信创合规适配文档 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 关联统一文档 | 20-unified-prd.md, 21-unified-architecture.md, 24-unified-security.md, 25-unified-deployment.md |
| 源设计文档 | opencode-1.17.14/docs/design/12-xinchuang-compliance.md (V4.0), 13-xinchuang-selection-discussion.md |

---

## 目录

1. [信创合规总纲](#1-信创合规总纲)
2. [硬件适配](#2-硬件适配)
3. [操作系统适配](#3-操作系统适配)
4. [数据库适配](#4-数据库适配)
5. [中间件适配](#5-中间件适配)
6. [容器化适配](#6-容器化适配)
7. [国密算法适配](#7-国密算法适配)
8. [合规认证路径](#8-合规认证路径)
9. [技术选型决策记录](#9-技术选型决策记录)
10. [与统一文档的交叉引用](#10-与统一文档的交叉引用)

---

## 1. 信创合规总纲

### 1.1 合规要求

| 要求 | 说明 | 优先级 |
|------|------|--------|
| 信创目录符合 | 所有选型组件须在信创目录内 | P0 |
| 自主可控 | API 网关、工作流引擎、工具协议须自研 | P0 |
| 国密算法 | 完整支持 SM2/SM3/SM4，通过国密局认证 | P0 |
| 数据本地化 | 用户数据存储在国内服务器 | P0 |
| 安全审查 | 通过等保 2.0 三级测评 | P1 |
| 开源合规 | 使用的开源组件须符合信创开源许可 | P1 |

### 1.2 全栈信创适配矩阵

| 层级 | 组件 | 非信创选型 | 信创选型 | 信创目录 |
|------|------|-----------|----------|----------|
| **硬件** | CPU | x86_64 | 鲲鹏/飞腾/龙芯/海光/兆芯 | ✅ |
| **操作系统** | OS | Ubuntu/CentOS | 麒麟 V10/统信 UOS/欧拉 | ✅ |
| **数据库** | 关系型 | MySQL/PostgreSQL | 达梦 DM8/OceanBase/人大金仓 | ✅ |
| **中间件** | 消息队列 | RabbitMQ/Kafka | RocketMQ | ✅ |
| **中间件** | 缓存 | Redis | 龙缓 | ✅ |
| **中间件** | 应用服务器 | Tomcat | 东方通 TongWeb/宝兰德 BES | ✅ |
| **容器** | 容器引擎 | Docker CE | iSulad (华为) | ✅ |
| **容器** | 镜像仓库 | Docker Hub | Harbor (信创版) | ✅ |
| **安全** | 加密算法 | AES/RSA/ECDSA | SM2/SM3/SM4 | ✅ |
| **监控** | 监控系统 | Prometheus | Nightingale | ✅ |
| **CI/CD** | 代码托管 | GitHub/GitLab | Gitee/Gitea | ✅ |

---

## 2. 硬件适配

### 2.1 国产 CPU 支持矩阵

| CPU 型号 | 架构 | 信创目录 | JDK 21 支持 | 适配状态 |
|----------|------|----------|-------------|----------|
| **鲲鹏 920** | ARM64 | ✅ | ✅ | ✅ 已验证 |
| **飞腾 S2500** | ARM64 | ✅ | ✅ | ✅ 已验证 |
| **龙芯 3A6000** | LoongArch | ✅ | ⚠️ 需龙芯 JDK | ⚠️ 适配中 |
| **海光 C86** | x86_64 | ✅ | ✅ | ✅ 已验证 |
| **兆芯 KX-7000** | x86_64 | ✅ | ✅ | ✅ 已验证 |

### 2.2 CPU 适配测试结果

| 测试项 | 鲲鹏 | 飞腾 | 海光 | 兆芯 |
|--------|------|------|------|------|
| JDK 21 完整功能 | ✅ | ✅ | ✅ | ✅ |
| Spring Boot 3.2 | ✅ | ✅ | ✅ | ✅ |
| OceanBase 驱动 | ✅ | ✅ | ✅ | ✅ |
| RocketMQ 客户端 | ✅ | ✅ | ✅ | ✅ |
| 龙缓客户端 | ✅ | ✅ | ✅ | ✅ |
| Bouncy Castle 国密 | ✅ | ✅ | ✅ | ✅ |
| iSulad 容器引擎 | ✅ | ✅ | ✅ | ✅ |
| Firecracker MicroVM | ✅ | ✅ | ✅ | ✅ |

---

## 3. 操作系统适配

### 3.1 国产 OS 支持矩阵

| 操作系统 | 版本 | 架构支持 | 适配状态 | 说明 |
|----------|------|----------|----------|------|
| **麒麟 V10** | Server | ARM64/x86_64 | ✅ 已验证 | 信创首选，生态最成熟 |
| **统信 UOS V20** | Server | ARM64/x86_64 | ✅ 已验证 | 桌面端市场份额高 |
| **中科方德** | 4.0 | x86_64 | ⚠️ 兼容 | 部分组件需验证 |
| **欧拉 (openEuler)** | 22.03 LTS | ARM64/x86_64 | ✅ 已验证 | 社区活跃，华为支持 |

### 3.2 OS 适配验证清单

```
麒麟 V10 适配验证：
  ├── JDK 21 安装与运行 ──── ✅
  ├── iSulad 安装与运行 ──── ✅
  ├── RocketMQ 客户端 ────── ✅
  ├── OceanBase JDBC ─────── ✅
  ├── 龙缓客户端 ─────────── ✅
  └── 权限与安全策略 ──────── ✅

统信 UOS 适配验证：
  ├── JDK 21 安装与运行 ──── ✅
  ├── iSulad 安装与运行 ──── ✅
  ├── 桌面客户端 (Electron) ─ ✅
  └── 文件系统权限 ────────── ✅
```

---

## 4. 数据库适配

### 4.1 数据库选型对比

| 数据库 | 信创目录 | 性能评分 | 社区活跃度 | 学习成本 | 推荐指数 |
|--------|----------|----------|-----------|---------|---------|
| **达梦 DM8** | ✅ 领导品牌 | ⭐⭐⭐⭐⭐ | 中等 | 中 | ⭐⭐⭐⭐⭐ |
| **OceanBase** | ✅ | ⭐⭐⭐⭐⭐ | 高 | 低 (MySQL 兼容) | ⭐⭐⭐⭐⭐ |
| **人大金仓 KingbaseES** | ✅ | ⭐⭐⭐⭐ | 中等 | 中 | ⭐⭐⭐⭐ |
| **GaussDB** | ✅ | ⭐⭐⭐⭐ | 中等 | 中 | ⭐⭐⭐⭐ |

### 4.2 数据库适配策略

```java
/**
 * 多数据库方言适配接口
 * 通过 DatabaseDialect 抽象层支持 4 种数据库
 */
public interface DatabaseDialect {
    String getLimitSql(String sql, int offset, int limit);
    String getSequenceNextVal(String sequenceName);
    String getCurrentTimestamp();
    boolean supportsJson();
    String getJsonExtract(String column, String path);
}

@Component
@ConditionalOnProperty(name = "database.type", havingValue = "oceanbase")
public class OceanBaseDialect implements DatabaseDialect { ... }

@Component
@ConditionalOnProperty(name = "database.type", havingValue = "dameng")
public class DamengDialect implements DatabaseDialect { ... }

@Component
@ConditionalOnProperty(name = "database.type", havingValue = "kingbase")
public class KingbaseDialect implements DatabaseDialect { ... }
```

### 4.3 推荐选型

**主推方案**：达梦 DM8 或 OceanBase（MySQL 兼容模式）

选型理由：
- 达梦 DM8：信创目录领导品牌，生态成熟，与 Oracle 兼容性好，适合企业级 OLTP 场景
- OceanBase：分布式架构，水平扩展能力强，MySQL 兼容降低学习成本

**备选方案**：人大金仓 KingbaseES

### 4.4 数据库连接配置（SM4 加密）

```yaml
# 达梦数据库连接（信创推荐）
spring:
  datasource:
    url: jdbc:dm://localhost:5236/gewu?useSSL=true
    username: gewu_user
    password: ${DB_PASSWORD_ENCRYPTED}  # SM4 加密存储
    driver-class-name: dm.jdbc.driver.DmDriver

# OceanBase 数据库连接（备选）
  oceanbase:
    url: jdbc:oceanbase://localhost:2883/gewu?useSSL=true
    username: gewu_user
    password: ${DB_PASSWORD_ENCRYPTED}  # SM4 加密存储
    driver-class-name: com.oceanbase.jdbc.Driver
```

---

## 5. 中间件适配

### 5.1 消息队列

| 对比项 | RocketMQ | RabbitMQ | Kafka |
|--------|----------|----------|-------|
| 信创目录 | ✅ | ❌ | ❌ |
| 吞吐量 | 10万 TPS | 1万 TPS | 100万 TPS |
| 延迟 | 毫秒级 | 微秒级 | 毫秒级 |
| Java 支持 | 原生 | 好 | 好 |

**推荐**：RocketMQ — 信创合规 + 高吞吐 + Java 原生支持

### 5.2 缓存

| 对比项 | 龙缓 (Dragonfly) | Redis | Memcached |
|--------|------------------|-------|-----------|
| 信创目录 | ✅ | ❌ | ❌ |
| Redis 兼容 | ✅ 完全兼容 | — | — |
| 吞吐量 | 100万 QPS | 10万 QPS | 10万 QPS |
| 集群模式 | ✅ | ✅ | ❌ |

**推荐**：龙缓 — 信创目录 + Redis 完全兼容 + 更高性能

### 5.3 应用服务器

| 产品 | 信创目录 | 说明 |
|------|----------|------|
| **东方通 TongWeb** | ✅ | 国产中间件标杆，Java EE 兼容 |
| **宝兰德 BES** | ✅ | 性能优秀，电信行业验证 |
| Tomcat | ❌ | 开源，可作开发测试用 |

**推荐**：Spring Boot 内嵌模式（开发阶段），东方通 TongWeb（生产部署）

### 5.4 API 网关

| 方案 | 信创目录 | 说明 |
|------|----------|------|
| **自研网关** (Java/Netty) | ✅ 自主可控 | 信创合规首选 |
| Spring Cloud Gateway | ❌ | 开源，可用作参考 |
| Kong/Kong | ❌ | 非国产 |
| APISIX | ⚠️ | 需信创适配 |

**推荐**：自研 API 网关（Java/Netty）— 完全自主可控

---

## 6. 容器化适配

### 6.1 容器引擎选型

| 对比项 | iSulad | Docker CE | containerd |
|--------|--------|-----------|------------|
| 信创目录 | ✅ | ❌ | ❌ |
| 开源方 | 华为 | Docker Inc. | CNCF |
| 命令兼容 | Docker 兼容 | — | 不兼容 |
| 资源占用 | 轻量 (C 语言) | 较重 (Go) | 轻量 (Go) |
| 国产 CPU | 鲲鹏/飞腾原生 | 需适配 | 需适配 |

**推荐**：iSulad — 信创目录 + Docker 命令兼容 + 国产 CPU 原生支持

### 6.2 容器镜像仓库

| 产品 | 信创目录 | 说明 |
|------|----------|------|
| Harbor (信创版) | ✅ | 企业级镜像仓库 |
| Docker Registry | ❌ | 简单镜像存储 |

**推荐**：Harbor 信创版

### 6.3 容器编排

| 产品 | 信创目录 | 说明 |
|------|----------|------|
| KubeEdge | ✅ | 华为边缘计算，云边协同 |
| K8s 社区版 | ❌ | 需信创适配 |
| 自研调度器 | ✅ 自主可控 | 轻量级场景 |

**推荐**：KubeEdge（生产环境）+ 自研调度器（轻量场景）

---

## 7. 国密算法适配

### 7.1 国密算法标准

| 算法 | 类型 | 用途 | 对标国际算法 | 实现方式 |
|------|------|------|-------------|----------|
| **SM2** | 非对称加密 | 数字签名、密钥交换 | RSA/ECDSA | Bouncy Castle 1.77 |
| **SM3** | 哈希算法 | 完整性校验、密码哈希 | SHA-256 | Bouncy Castle 1.77 |
| **SM4** | 对称加密 | 数据加密 | AES | Bouncy Castle 1.77 |

### 7.2 国密应用场景

| 场景 | 算法 | 实现方式 |
|------|------|----------|
| 用户密码存储 | SM3 + 随机盐值 | PasswordEncoder |
| API 通信加密 | SM4-GCM | 自研网关 TLS |
| 数据存储加密 | SM4-CBC/PKCS7 | DataEncryption 工具类 |
| 数字签名 | SM2 | JWT 签名 |
| 密钥交换 | SM2 | TLS 握手 |
| 数据库连接加密 | SM4 | 连接密码解密 |

---

## 8. 合规认证路径

### 8.1 信创认证路线

```
Phase 1 (Week 1-8) —— 基础适配
  ├── 技术选型确认（信创目录验证）—— 所有组件须在目录内
  ├── 开发环境搭建（麒麟 V10 + iSulad）
  ├── 数据库适配（达梦 DM8 / OceanBase）
  └── 国密算法集成（Bouncy Castle SM2/SM3/SM4）

Phase 2 (Week 9-16) —— 深度适配
  ├── 全平台兼容性测试（5 种 CPU × 4 种 OS）
  ├── 性能基准测试
  ├── 安全扫描与修复
  └── 文档准备

Phase 3 (Week 17-24) —— 认证提交
  ├── 信创适配验证报告
  ├── 等保 2.0 三级测评
  ├── 国密算法认证
  └── 信创目录产品认证
```

### 8.2 等保 2.0 三级要求映射

| 等保要求 | 实现措施 | 状态 |
|----------|----------|------|
| 身份鉴别 | JWT + SM2 双因素 | ✅ 已实现 |
| 访问控制 | RBAC + 工具级权限 | ✅ 已实现 |
| 安全审计 | AOP 切面 + RocketMQ 审计事件 | ✅ 已实现 |
| 数据完整性 | SM3 哈希校验 | ✅ 已实现 |
| 数据保密性 | SM4-GCM 加密 + 脱敏 | ✅ 已实现 |
| 数据备份恢复 | OceanBase 主从 + 定时备份 | ✅ 已实现 |
| 剩余信息保护 | 逻辑删除 + 数据清理 | ✅ 已实现 |
| 入侵防范 | 沙箱隔离 + 命令白名单 | ✅ 已实现 |
| 安全加固 | 最小权限 + seccomp/AppArmor | ✅ 已实现 |

---

## 9. 技术选型决策记录

### ADR-01：数据库选型

**决策**：采用达梦 DM8 为主选，OceanBase 为备选

**背景**：
- 需要信创目录内数据库
- 需要支持分布式 OLTP 场景
- 需要与现有 MySQL 工具链兼容

**备选方案**：
1. 达梦 DM8 — 信创领导品牌，Oracle 兼容，生态成熟
2. OceanBase — 分布式架构，MySQL 兼容，社区活跃
3. 人大金仓 — PostgreSQL 兼容

**结论**：达梦 DM8 为推荐方案，OceanBase 为高可用方案

### ADR-02：容器引擎选型

**决策**：采用 iSulad（华为开源）替代 Docker

**背景**：
- Docker 不在信创目录内
- 需要支持国产 CPU（鲲鹏/飞腾）
- 需要 Docker 命令兼容以减少迁移成本

**备选方案**：
1. iSulad — 信创目录，华为开源，Docker 兼容
2. containerd — 信创目录问题
3. Docker CE — 不在信创目录内

**结论**：iSulad 为首选，Docker CE 仅用于开发测试环境

### ADR-03：API 网关选型

**决策**：自研 API 网关（Java/Netty）

**背景**：
- 信创合规要求核心组件自主可控
- 需要深度集成国密算法
- 需要定制化协议转换能力

**备选方案**：
1. Spring Cloud Gateway — 开源，非信创目录
2. APISIX — 需信创适配
3. Kong — 非国产

**结论**：自研网关，基于 Java 21 + Netty，满足信创合规

### ADR-04：消息队列选型

**决策**：采用 RocketMQ

**背景**：
- 信创目录内唯一的高吞吐消息队列
- Java 原生支持，易于集成
- 分布式事务消息支持

**结论**：RocketMQ 为唯一合规方案

---

## 10. 与统一文档的交叉引用

### 10.1 PRD 对应关系

| PRD 需求 | 信创文档对应章节 | 说明 |
|---------|-----------------|------|
| NF-X01 信创目录 | §1.2 全栈信创适配矩阵 | 所有组件信创目录验证 |
| NF-X02 自主可控 | §5.4 API 网关 + ADR-03 | 自研网关决策 |
| NF-X03 国密算法 | §7 国密算法适配 | SM2/SM3/SM4 实现 |
| NF-X04 数据本地化 | §4 数据库适配 | 国产数据库部署 |
| NF-X05 安全审查 | §8.2 等保 2.0三级映射 | 等保合规措施 |

### 10.2 架构对应关系

| 架构文档章节 | 信创文档对应章节 | 说明 |
|-------------|-----------------|------|
| 21-unified-architecture.md §9 技术栈 | §9 ADR 决策记录 | 技术选型决策依据 |
| 21-unified-architecture.md §5 技术栈对比 | §1.2 适配矩阵 | 信创×非信创对比 |

### 10.3 部署对应关系

| 部署文档章节 | 信创文档对应章节 | 说明 |
|-------------|-----------------|------|
| 25-unified-deployment.md §2 环境配置 | §2 硬件适配 + §3 OS 适配 | 国产 CPU/OS 部署 |
| 25-unified-deployment.md §3 容器化 | §6 容器化适配 | iSulad 替换 Docker |
| 25-unified-deployment.md §6 数据库 | §4 数据库适配 | 达梦/OceanBase 部署 |

### 10.4 安全对应关系

| 安全文档章节 | 信创文档对应章节 | 说明 |
|-------------|-----------------|------|
| 24-unified-security.md §10 信创合规 | §1 信创合规总纲 | 合规要求对齐 |
| 24-unified-security.md §2 密码安全 | §7 国密算法适配 | 国密算法实现 |
| 24-unified-security.md §9 等保 2.0 | §8.2 等保 2.0三级映射 | 等保措施映射 |
