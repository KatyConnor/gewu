# 格物平台 - 安全合规设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 安全合规设计文档 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 适用范围 | 格物平台全系统 |

---

## 目录

1. [安全设计原则](#1-安全设计原则)
2. [国密算法实现](#2-国密算法实现)
3. [认证与授权](#3-认证与授权)
4. [数据安全](#4-数据安全)
5. [通信安全](#5-通信安全)
6. [审计日志](#6-审计日志)
7. [安全合规标准](#7-安全合规标准)
8. [安全开发生命周期](#8-安全开发生命周期)
9. [应急响应](#9-应急响应)

---

## 1. 安全设计原则

### 1.1 核心安全原则

| 原则 | 说明 | 实现方式 |
|------|------|----------|
| **纵深防御** | 多层次安全防护，单层失效不影响整体 | 网络层→应用层→数据层→数据加密 |
| **最小权限** | 用户和进程只获得完成任务所需的最小权限 | RBAC + 细粒度权限控制 |
| **默认安全** | 默认配置为最安全配置 | 禁用不必要功能，最小化攻击面 |
| **安全左移** | 安全要求在开发阶段即纳入考虑 | 安全设计评审、代码安全审查、SAST/DAST |
| **数据保护** | 全生命周期数据保护 | 传输加密(TLS/国密)、存储加密(SM4)、脱敏处理 |
| **可审计性** | 所有操作可追溯、可审计 | 完整审计日志、操作留痕 |

### 1.2 信创合规约束

| 约束 | 要求 | 实现方案 |
|------|------|----------|
| 国密算法支持 | 必须支持 SM2/SM3/SM4 | Bouncy Castle 集成 |
| 国产密码模块 | 密码算法通过国密局认证 | 使用 Bouncy Castle 国密实现 |
| 自主可控 | 核心组件自研，不依赖国外商用产品 | 自研网关、自研工作流引擎 |
| 数据本地化 | 用户数据存储在中国境内 | OceanBase 数据库 |
| 安全合规认证 | 通过等保 2.0 三级 | 按等保要求设计安全机制 |

---

## 2. 国密算法实现

### 2.1 国密算法概览

| 算法 | 类型 | 密钥长度 | 用途 | 对标国际算法 |
|------|------|---------|------|-------------|
| **SM2** | 非对称加密 | 256 bit | 数字签名、密钥交换、加密 | ECDSA/ECDH |
| **SM3** | 密码杂凑 | 256 bit | 数据完整性校验、密码存储 | SHA-256 |
| **SM4** | 对称加密 | 128 bit | 数据加密、通信加密 | AES-128 |
| **SM9** | 标识密码 | 256 bit | 标识认证 (保留) | - |
| **SM2-SM3-SM4** | 国密套件 | - | TLS 国密套件 | - |

### 2.2 国密算法应用场景

```
┌────────────────────────────────────────────────────────────┐
│                    国密算法应用场景                          │
│                                                            │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐   │
│  │ 用户认证      │    │ 数据加密      │    │ 通信安全      │   │
│  │──────────────│    │──────────────│    │──────────────│   │
│  │ 密码存储: SM3│    │ 存储加密: SM4│    │ API加密: SM2 │   │
│  │ 数字签名: SM2│    │ 字段加密: SM4│    │ TLS国密套件  │   │
│  │ JWT签名: SM2 │    │ 文件加密: SM4│    │ 网关加密: SM2│   │
│  └──────────────┘    └──────────────┘    └──────────────┘   │
│                                                            │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐   │
│  │ 完整性校验    │    │ 密钥管理      │    │ 审计溯源      │   │
│  │──────────────│    │──────────────│    │──────────────│   │
│  │ 数据完整性:   │    │ 密钥生成: SM2│    │ 日志签名: SM2│   │
│  │   SM3 HMAC   │    │ 密钥交换: SM2│    │ 操作链: SM3  │   │
│  │ 消息摘要: SM3 │    │ 密钥派生: SM3│    │ 防篡改: SM3  │   │
│  └──────────────┘    └──────────────┘    └──────────────┘   │
└────────────────────────────────────────────────────────────┘
```

### 2.3 依赖配置

#### Maven 依赖

```xml
<!-- Bouncy Castle 国密算法库 -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>1.77</version>
</dependency>
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpkix-jdk18on</artifactId>
    <version>1.77</version>
</dependency>
```

### 2.4 SM2 工具类设计

```java
/**
 * SM2 非对称加密工具类
 * 用途: 数字签名、密钥交换、数据加密
 */
public class SM2Utils {
    
    // 生成 SM2 密钥对
    public static KeyPair generateKeyPair() {
        // 使用 Bouncy Castle 的 SM2 实现
        ECGenParameterSpec sm2Spec = new ECGenParameterSpec("sm2p256v1");
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC");
        keyPairGenerator.initialize(sm2Spec, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
    }
    
    // SM2 签名 (返回 DER 编码)
    public static byte[] sign(byte[] data, PrivateKey privateKey) {
        Signature signature = Signature.getInstance("SM3withSM2", "BC");
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }
    
    // SM2 验签
    public static boolean verify(byte[] data, byte[] sign, PublicKey publicKey) {
        Signature signature = Signature.getInstance("SM3withSM2", "BC");
        signature.initVerify(publicKey);
        signature.update(data);
        return signature.verify(sign);
    }
    
    // SM2 加密
    public static byte[] encrypt(byte[] data, PublicKey publicKey) {
        // 使用 SM2 集成加密方案
        SM2Engine engine = new SM2Engine("C1C3C2");
        engine.init(true, new ParametersWithRandom(publicKey, new SecureRandom()));
        return engine.processBlock(data, 0, data.length);
    }
    
    // SM2 解密
    public static byte[] decrypt(byte[] cipherData, PrivateKey privateKey) {
        SM2Engine engine = new SM2Engine("C1C3C2");
        engine.init(false, privateKey);
        return engine.processBlock(cipherData, 0, cipherData.length);
    }
}
```

### 2.5 SM3 工具类设计

```java
/**
 * SM3 密码杂凑工具类
 * 用途: 数据完整性校验、密码存储、消息摘要
 */
public class SM3Utils {
    
    // SM3 哈希
    public static byte[] hash(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }
    
    // SM3 哈希 (Hex 编码)
    public static String hashHex(String data) {
        return Hex.toHexString(hash(data.getBytes(StandardCharsets.UTF_8)));
    }
    
    // SM3 HMAC
    public static byte[] hmac(byte[] data, byte[] key) {
        SM3Digest digest = new SM3Digest();
        HMac hmac = new HMac(digest);
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] result = new byte[hmac.getMacSize()];
        hmac.doFinal(result, 0);
        return result;
    }
    
    // SM3 带盐值的密码哈希
    public static String passwordHash(String password, String salt) {
        byte[] data = (password + salt).getBytes(StandardCharsets.UTF_8);
        // 多次迭代增加破解难度
        byte[] hash = data;
        for (int i = 0; i < 1024; i++) {
            hash = hash(hash);
        }
        return Hex.toHexString(hash);
    }
}
```

### 2.6 SM4 工具类设计

```java
/**
 * SM4 对称加密工具类
 * 用途: 数据加密存储、通信加密
 */
public class SM4Utils {
    
    private static final String ALGORITHM = "SM4";
    private static final String CIPHER = "SM4/CBC/PKCS7Padding";
    
    // 生成 SM4 密钥
    public static byte[] generateKey() {
        KeyGenerator kg = KeyGenerator.getInstance(ALGORITHM, "BC");
        kg.init(128, new SecureRandom());
        return kg.generateKey().getEncoded();
    }
    
    // SM4 CBC 加密
    public static byte[] encrypt(byte[] data, byte[] key, byte[] iv) {
        Cipher cipher = Cipher.getInstance(CIPHER, "BC");
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(data);
    }
    
    // SM4 CBC 解密
    public static byte[] decrypt(byte[] cipherData, byte[] key, byte[] iv) {
        Cipher cipher = Cipher.getInstance(CIPHER, "BC");
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(cipherData);
    }
}
```

### 2.7 国密 TLS 配置

```yaml
# application.yml 国密 TLS 配置
server:
  port: 8443
  ssl:
    enabled: true
    # 国密证书 (SM2证书链)
    key-store: classpath:ssl/gm-server.p12
    key-store-password: ${SSL_KEY_PASSWORD}
    key-store-type: PKCS12
    # 国密加密套件
    ciphers:
      - TLS_SM4_GCM_SM3
      - TLS_SM4_CCM_SM3
      - TLS_ECDHE_SM2_WITH_SM4_GCM_SM3
```

---

## 3. 认证与授权

### 3.1 认证架构

```
┌─────────┐      ┌─────────┐      ┌─────────┐      ┌─────────┐
│ 客户端   │─────>│ API网关  │─────>│ 认证服务  │─────>│ 用户系统  │
│         │      │         │      │         │      │         │
│ 携带JWT │      │ 验证JWT │      │ 颁发JWT  │      │ 验证密码 │
│         │      │ 国密验签 │      │ SM2签名  │      │ SM3哈希 │
└─────────┘      └─────────┘      └─────────┘      └─────────┘
```

### 3.2 JWT 令牌规范

| 字段 | 说明 | 加密方式 |
|------|------|----------|
| 令牌格式 | JWT (JSON Web Token) | 标准 JWT 格式 |
| 签名算法 | SM3withSM2 | 国密非对称签名 |
| 令牌内容 | userId, roles, permissions, exp, iat | JSON 负载 |
| 访问令牌有效期 | 24 小时 | 配置可调 |
| 刷新令牌有效期 | 7 天 | 配置可调 |
| 令牌存储 | 客户端 (localStorage/内存) | - |

### 3.3 JWT 令牌结构

```json
{
  "alg": "SM3withSM2",
  "typ": "JWT"
}
{
  "sub": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "username": "zhangsan",
  "roles": ["ARCHITECT", "BACKEND_DEV"],
  "permissions": [
    "project:read",
    "project:write",
    "session:read",
    "session:write"
  ],
  "exp": 1762560000000,
  "iat": 1762473600000
}
```

### 3.4 RBAC 权限模型

```
┌──────────┐    ┌──────────┐    ┌──────────────┐
│  用户     │───>│  角色     │───>│  权限         │
│  User    │    │  Role    │    │  Permission  │
└──────────┘    └──────────┘    └──────────────┘
     │               │                │
     │               │                │
     ▼               ▼                ▼
┌───────────┐  ┌───────────┐  ┌──────────────┐
│ 用户角色   │  │ 角色权限   │  │ 资源+操作     │
│ user_role │  │role_perm  │  │resource+action│
└───────────┘  └───────────┘  └──────────────┘
```

#### 系统预置角色

| 角色编码 | 角色名称 | 权限范围 |
|----------|---------|---------|
| SUPER_ADMIN | 系统管理员 | 全部权限 (系统配置、用户管理、审计) |
| ARCHITECT | 架构师 | 技术架构、工作流设计、沙箱配置 |
| PRODUCT_MANAGER | 产品经理 | 项目管理、需求管理、版本规划 |
| BACKEND_DEV | 后端开发 | 编码、代码审查、沙箱执行 |
| FRONTEND_DEV | 前端开发 | 前端编码、UI设计、代码审查 |
| TESTER | 测试工程师 | 测试管理、CI集成、缺陷管理 |
| DEVOPS | 运维工程师 | 监控管理、部署管理、日志查询 |
| SECURITY | 安全工程师 | 安全策略、审计日志、合规检查 |
| MEMBER | 普通成员 | 基础功能只读权限 |

### 3.5 授权拦截器

```java
/**
 * 权限注解
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    String value();           // 权限编码
    String action() default ""; // 操作
}

/**
 * 权限拦截器 (API 网关层)
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 从 JWT 中提取用户权限列表
        // 2. 判断请求的资源+操作是否在权限列表中
        // 3. 通过/拒绝
        // 4. 记录审计日志
    }
}
```

---

## 4. 数据安全

### 4.1 数据分类分级

| 级别 | 定义 | 示例 | 保护要求 |
|------|------|------|----------|
| L1 公开 | 对外公开的信息 | 产品名称、版本号 | 完整性保护 |
| L2 内部 | 内部使用，不对外公开 | 项目名称、成员列表 | 访问控制 |
| L3 敏感 | 敏感信息，限制范围 | 用户邮箱、手机号、角色权限 | 加密存储 + 访问控制 |
| L4 机密 | 高度机密信息 | 密码、令牌密钥、数据库密码 | 加密存储 + 最小权限 + 审计 |

### 4.2 数据加密策略

| 数据类别 | 存储加密 | 传输加密 | 脱敏展示 | 密钥管理 |
|----------|---------|---------|---------|---------|
| 用户密码 | SM3 哈希 + 盐值 | HTTPS/TLS | 不展示 | - |
| 用户手机号 | SM4 加密 | HTTPS/TLS | 掩码显示 (138****1234) | 主密钥 |
| 用户邮箱 | SM4 加密 | HTTPS/TLS | 掩码显示 (u***@example.com) | 主密钥 |
| JWT 令牌 | - | HTTPS/TLS | 不展示 | SM2 私钥 |
| API 密钥 | SM3 哈希 | HTTPS/TLS | 前缀展示 (gew_****abcd) | - |
| 会话消息 | 可选 SM4 加密 | HTTPS/TLS | 按权限展示 | 会话密钥 |
| 文件存储 | SM4 加密 | HTTPS/TLS | 按权限展示 | 文件密钥 |
| 数据库连接 | - | TLS/SSL | 不展示 | 密钥管理服务 |
| 配置密钥 | SM4 加密 | TLS | 不展示 | 密钥管理服务 |

### 4.3 密钥管理方案

```
┌─────────────────────────────────────────────────────────┐
│                    密钥管理系统                           │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ 主密钥 (MK)   │  │ 数据密钥 (DK) │  │ 会话密钥 (SK) │   │
│  │ 硬件加密保存   │  │ 由 MK 加密     │  │ 由 DK 派生    │   │
│  │ HSM/KMS      │  │ 定期轮换      │  │ 一次性使用     │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
│                                                         │
│  密钥层级: MK → KEK (密钥加密密钥) → DK (数据加密密钥)    │
└─────────────────────────────────────────────────────────┘
```

### 4.4 密钥轮换策略

| 密钥类型 | 轮换周期 | 轮换方式 |
|----------|---------|---------|
| 主密钥 (MK) | 每年 | 手动轮换，需要解密后重新加密 |
| 数据密钥 (DK) | 每季度 | 自动轮换，数据逐步重加密 |
| JWT 签名密钥 | 每月 | 自动轮换，旧密钥保留验证窗口 |
| 会话密钥 | 每次会话 | 自动生成，会话结束即销毁 |
| 数据库密码 | 每季度 | 自动轮换，零停机切换 |

---

## 5. 通信安全

### 5.1 网络安全架构

```
┌────────────────────────────────────────────────────────┐
│                     公网                                │
└─────────────────────┬──────────────────────────────────┘
                      │ HTTPS / 国密 TLS
┌─────────────────────▼──────────────────────────────────┐
│                  负载均衡 / 反向代理                       │
│              SSL 卸载、DDoS 防护、WAF                    │
└─────────────────────┬──────────────────────────────────┘
                      │ 内部 HTTP
┌─────────────────────▼──────────────────────────────────┐
│                 API 网关 (自研)                          │
│       认证 / 授权 / 限流 / 国密 / 审计                  │
└─────────┬─────────┬──────────┬──────────┬──────────────┘
          │         │          │          │
          ▼         ▼          ▼          ▼
    ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
    │应用服务  │ │数据库   │ │缓存    │ │消息队列 │
    │ 8080    │ │ 2881   │ │ 6379   │ │ 9876   │
    └────────┘ └────────┘ └────────┘ └────────┘
          │         │          │          │
          └─────────┴──────────┴──────────┘
                      │ 内部网络隔离
               ┌──────▼──────┐
               │  沙箱网络隔离  │
               │   Firewall   │
               └─────────────┘
```

### 5.2 TLS/SSL 配置

| 配置项 | 标准 TLS | 国密 TLS |
|--------|---------|----------|
| 协议版本 | TLS 1.2 / 1.3 | 国密 TLS 1.1+ |
| 证书类型 | RSA/ECDSA | SM2 证书 |
| 加密套件 | ECDHE-RSA-AES256-GCM | ECDHE-SM2-SM4-GCM |
| 证书链 | 国际 CA | 国密 CA (GMT 0006) |

### 5.3 API 网关安全配置

```yaml
# 网关安全配置示例
gateway:
  security:
    # 认证配置
    jwt:
      algorithm: SM3withSM2
      public-key: classpath:keys/gateway-public-key.pem
      issuer: gewu-platform
    
    # 限流配置
    rate-limit:
      enabled: true
      algorithm: token-bucket
      default-limit: 1000  # 默认 QPS
      ip-limit: 100        # 单 IP QPS
      user-limit: 50       # 单用户 QPS
    
    # 熔断配置
    circuit-breaker:
      enabled: true
      failure-threshold: 50   # 50% 失败率触发
      timeout-ms: 30000       # 请求超时
      half-open-delay-ms: 60000  # 半开延迟
    
    # 可信 IP 白名单
    trusted-ips:
      - 10.0.0.0/8
      - 172.16.0.0/12
      - 192.168.0.0/16
```

---

## 6. 审计日志

### 6.1 审计日志策略

| 日志类别 | 记录内容 | 存储周期 | 保护级别 |
|----------|---------|---------|---------|
| 登录日志 | 登录/登出/失败/锁定 | 1年 | L3 |
| 操作日志 | CRUD/执行/配置变更 | 1年 | L3 |
| 权限变更 | 角色分配/权限修改 | 3年 | L4 |
| 安全事件 | 异常访问/攻击检测 | 3年 | L4 |
| 数据变更 | 敏感数据修改 | 1年 | L3 |
| 系统日志 | 服务启停/配置变更 | 6月 | L2 |

### 6.2 审计日志格式

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000,
  "user": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "username": "zhangsan",
    "ip": "192.168.1.100",
    "userAgent": "Mozilla/5.0 ..."
  },
  "action": {
    "type": "UPDATE",
    "resource": "PROJECT",
    "resourceId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "detail": {
      "before": { "name": "旧名称", "status": 1 },
      "after": { "name": "新名称", "status": 2 }
    },
    "result": "SUCCESS",
    "duration": 156
  },
  "signature": "SM3withSM2签名值(用于防篡改)"
}
```

### 6.3 审计日志防篡改方案

```
每条审计日志包含前一条日志的哈希链：

Log[0]: hash = SM3(content[0])
Log[1]: hash = SM3(content[1] + hash[0])
Log[2]: hash = SM3(content[2] + hash[1])
...

验证: 遍历日志链，验证每条的 hash 是否等于
SM3(content[n] + hash[n-1])
```

---

## 7. 安全合规标准

### 7.1 等保 2.0 三级要求

| 安全类别 | 等保要求 | 实现方案 | 状态 |
|----------|---------|---------|------|
| **物理安全** | 机房安全 | 云平台物理安全 | ✅ 由云平台保障 |
| **网络安全** | 网络隔离、访问控制 | VPC + 安全组 + ACL | ✅ 已设计 |
| **主机安全** | 系统加固、漏洞管理 | 镜像扫描 + 系统补丁 | ⚠️ 需实施 |
| **应用安全** | 身份鉴别、访问控制、安全审计 | JWT/RBAC/审计日志 | ✅ 已设计 |
| **数据安全** | 数据加密、备份恢复 | SM4加密 + 定期备份 | ✅ 已设计 |
| **安全管理** | 安全制度、应急响应 | 安全管理制度 | ⚠️ 需建立 |

### 7.2 法律法规合规

| 法律法规 | 要求 | 实现方案 |
|----------|------|----------|
| 《网络安全法》 | 网络安全等级保护、用户信息保护 | 等保2.0三级、数据加密、隐私保护 |
| 《数据安全法》 | 数据分类分级、数据安全审查 | L1-L4分级、审计日志 |
| 《个人信息保护法》 | 用户同意、最小必要、数据删除 | 用户授权、数据最小化、账号注销 |
| 《商用密码管理条例》 | 使用国密算法、通过国密认证 | SM2/SM3/SM4 完整实现 |
| 《关键信息基础设施安全保护条例》 | CII 识别、安全保护、应急演练 | 系统认定为 CII 后补充 |

### 7.3 信创合规清单

| 合规项 | 要求 | 当前状态 | 验证方法 |
|--------|------|---------|---------|
| 国产 JDK | JDK 21+ (鲲鹏/飞腾/龙芯) | ✅ 已配置 | 编译验证 |
| 国产数据库 | OceanBase 4.2+ | ✅ 已集成 | 连接测试 |
| 国产缓存 | DragonflyDB 1.27+ | ✅ 已配置 | 兼容性测试 |
| 国产消息队列 | RocketMQ 5.1+ | ✅ 已配置 | 发送/消费测试 |
| 国产监控 | Nightingale | ✅ 已配置 | 数据采集验证 |
| 国产 CI/CD | Gitea + Drone | ✅ 已配置 | 流水线测试 |
| 国产容器运行时 | iSulad | ⚠️ 文档提及 | Phase 0 验证 |
| 国密算法 | SM2/SM3/SM4/Bouncy Castle 1.77 | ✅ 已配置依赖 | 单元测试 |
| 自研网关 | Java/Netty | ⚠️ P0 验证 | 性能压测 |
| 自研工作流 | 状态机引擎 | ⚠️ 待实现 | 功能测试 |

---

## 8. 安全开发生命周期

### 8.1 SDL 流程

```
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│ 需求阶段  │──>│ 设计阶段  │──>│ 开发阶段  │──>│ 测试阶段  │──>│ 运维阶段  │
│─────────│   │─────────│   │─────────│   │─────────│   │─────────│
│ 安全需求  │   │ 安全设计  │   │ 安全编码  │   │ 安全测试  │   │ 安全监控  │
│ 威胁建模  │   │ 攻击面    │   │ 代码审查  │   │ SAST     │   │ 漏洞管理  │
│          │   │ 分析     │   │ 依赖检查  │   │ DAST     │   │ 应急响应  │
└─────────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘
```

### 8.2 安全工具集成

| 阶段 | 工具 | 集成方式 | 频率 |
|------|------|---------|------|
| 代码开发 | SonarQube | IDE 插件 + Maven 插件 | 每次提交 |
| 代码审查 | SonarQube | PR 门禁 | 每次 PR |
| 依赖检查 | OWASP Dependency-Check | Maven 插件 | 每次构建 |
| SAST | SpotBugs | Maven 插件 | 每次构建 |
| DAST | OWASP ZAP | CI/CD 流水线 | 每日运行 |
| 容器扫描 | Trivy | CI/CD 流水线 | 每次构建 |
| 密钥扫描 | GitLeaks | Git Hook | 每次提交 |

### 8.3 安全依赖配置

```xml
<!-- OWASP Dependency Check -->
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>9.0.9</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS>
        <formats>
            <format>HTML</format>
            <format>JSON</format>
        </formats>
    </configuration>
</plugin>

<!-- SpotBugs -->
<plugin>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-maven-plugin</artifactId>
    <version>4.8.3</version>
    <configuration>
        <effort>Max</effort>
        <threshold>Low</threshold>
        <failOnError>true</failOnError>
    </configuration>
</plugin>
```

### 8.4 .drone.yml 安全测试流水线

```yaml
kind: pipeline
name: security-test

steps:
  - name: dependency-check
    image: maven:3.8-openjdk-21
    commands:
      - mvn dependency-check:check

  - name: spotbugs
    image: maven:3.8-openjdk-21
    commands:
      - mvn spotbugs:check

  - name: trivy-scan
    image: aquasec/trivy:latest
    commands:
      - trivy image gewu/gewu-application:$DRONE_COMMIT_SHA
```

---

## 9. 应急响应

### 9.1 安全事件分级

| 级别 | 定义 | 响应时间 | 示例 |
|------|------|---------|------|
| P0 紧急 | 系统严重受损，数据泄露 | < 15分钟 | SQL注入、数据批量泄露 |
| P1 高危 | 高危漏洞，影响部分用户 | < 1小时 | XSS漏洞、CSRF漏洞 |
| P2 中危 | 中危漏洞，影响有限 | < 4小时 | 配置不当、弱密码 |
| P3 低危 | 低风险问题 | < 24小时 | 信息泄露、文档错误 |

### 9.2 应急响应流程

```
1. 发现/报告
   │
2. 评估定级 (确定 P0-P3)
   │
3. 应急处置
   ├── 阻断攻击 (封IP/下线服务)
   ├── 保护数据 (加密/备份)
   └── 取证分析
   │
4. 漏洞修复
   │
5. 复测验证
   │
6. 复盘总结 ──→ 更新安全策略
```

### 9.3 安全配置基线

```yaml
# Spring Boot 安全配置
management:
  endpoints:
    web:
      exposure:
        exclude: "*"  # 生产环境关闭 Actuator
  endpoint:
    health:
      show-details: never  # 不显示健康详情

server:
  # 安全响应头
  headers:
    x-frame-options: DENY
    x-content-type-options: nosniff
    x-xss-protection: 1; mode=block
    strict-transport-security: max-age=31536000; includeSubDomains
    content-security-policy: default-src 'self'
    referrer-policy: strict-origin-when-cross-origin
  
  # 请求限制
  max-http-request-header-size: 8192
  max-http-request-size: 10MB

# 会话安全
spring:
  session:
    timeout: 30m
    cookie:
      http-only: true
      secure: true
      same-site: strict
```

---

## 附录A：修订历史

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------|---------|--------|
| V1.0 | 2026-07-08 | 初稿：国密算法、认证授权、数据安全、合规要求 | - |

---

**文档结束**
