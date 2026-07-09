# 格物平台 统一安全合规文档 V1.0

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 格物平台 统一安全合规文档 |
| 版本 | V1.1 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 适用范围 | 格物平台全系统 |
| 合并来源 | gewu-platform 07-security-compliance.md + opencode-1.17.14 安全架构 |

---

## 目录

1. [安全设计总纲](#1-安全设计总纲)
2. [密码安全](#2-密码安全)
3. [传输安全](#3-传输安全)
4. [认证与授权](#4-认证与授权)
5. [数据安全](#5-数据安全)
6. [沙箱隔离](#6-沙箱隔离)
7. [审计日志](#7-审计日志)
8. [限流与熔断](#8-限流与熔断)
9. [等保 2.0 三级合规](#9-等保-20-三级合规)
10. [信创合规](#10-信创合规)
11. [OpenCode → 格物 安全机制映射表](#11-opencode--格物-安全机制映射表)
12. [MCP 安全替换方案](#12-mcp-安全替换方案)
13. [安全配置基线](#13-安全配置基线)
14. [附录](#14-附录)

---

## 1. 安全设计总纲

### 1.1 核心安全原则

| 原则 | 说明 | 格物实现 | OpenCode 参考 |
|------|------|---------|--------------|
| **纵深防御** | 多层次安全防护 | 网络层→应用层→数据层→国密加密 | 文件系统沙箱→Shell 沙箱→网络沙箱 |
| **最小权限** | 只授予完成任务所需的最小权限 | RBAC + 工具级 allow/deny/ask | 3 模式权限（允许/拒绝/询问） |
| **默认安全** | 默认配置为最安全配置 | 禁用不必要功能，最小化攻击面 | 权限默认拒绝，显式允许 |
| **安全左移** | 安全要求在开发阶段纳入 | 安全设计评审、SAST/DAST | Schema 验证、参数化查询 |
| **数据保护** | 全生命周期数据保护 | 国密 TLS + SM4 存储加密 + SM3 完整性 | 输入过滤、输出限制 |

### 1.2 安全分层架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    格物统一安全架构（信创适配版）                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    应用层安全                             │   │
│  │  认证 (JWT/SM2) | 授权 (RBAC+Allow/Deny/Ask) | 审计     │   │
│  │  ┌──────────────────────────────────────────────────┐   │   │
│  │  │ OpenCode 交互式权限: 文件访问/Shell执行/网络请求 │   │   │
│  │  │ Allow → 自动允许 | Deny → 自动拒绝 | Ask → 询问 │   │   │
│  │  └──────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    数据层安全                             │   │
│  │  密码 (SM3+salt) | 存储 (SM4/GCM/PKCS7) | 脱敏 (Mask)   │   │
│  │  Schema 验证 | 参数化查询 | 敏感字段加密                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    网络层安全                             │   │
│  │  国密 TLS (SM2/SM4-GCM/SM3) | WAF | DDoS | OAuth 2.0   │   │
│  │  限流 (动态令牌桶) | 熔断 (断路器) | MCP 替换为国密认证   │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    基础设施层安全                         │   │
│  │  Firecracker MicroVM | gVisor | Docker/iSulad          │   │
│  │  seccomp | AppArmor | 文件系统 ACL | 命令白名单          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 信创合规约束

| 约束 | 要求 | 实现方案 | OpenCode 参考 |
|------|------|---------|--------------|
| 国密算法支持 | 必须支持 SM2/SM3/SM4 | Bouncy Castle 1.77 | —（仅 SHA-256/ECDSA） |
| 国产密码模块 | 密码算法通过国密局认证 | Bouncy Castle 国密实现 | — |
| 自主可控 | 核心组件自研 | 自研网关、自研工作流引擎、自研工具协议 | MCP → 自研替换 |
| 数据本地化 | 数据存储在中国境内 | OceanBase 数据库 | SQLite（本地） |
| 安全合规认证 | 通过等保 2.0 三级 | 按等保要求设计安全机制 | — |

---

## 2. 密码安全

### 2.1 密码存储策略

| 算法 | 用途 | 参数 | OpenCode 参考 |
|------|------|------|--------------|
| SM3 + 盐值 | 用户密码哈希 | 迭代 1024 次，盐值 32 字节 | bcrypt (迁移中) |
| SM3 HMAC | API Key 签名 | 密钥长度 ≥ 32 字节 | 基础哈希 |
| SM3 消息摘要 | 数据完整性校验 | 输出 256 bit | — |

### 2.2 密码强度策略

| 规则 | 要求 |
|------|------|
| 最小长度 | 8 位 |
| 复杂度 | 大写字母 + 小写字母 + 数字 + 特殊字符 |
| 历史密码 | 不可使用最近 5 次密码 |
| 有效期 | 90 天强制更换 |
| 登录锁定 | 连续 5 次失败锁定 30 分钟 |

### 2.3 密码哈希实现（SM3 + 盐值）

```java
/**
 * 密码安全工具类
 * SM3 哈希 + 盐值 + 迭代
 */
public class PasswordUtils {

    private static final int ITERATIONS = 1024;
    private static final int SALT_LENGTH = 32;

    // 生成密码哈希
    public static String hashPassword(String password) {
        byte[] salt = generateSalt();
        byte[] hash = sm3WithSalt(password, salt);
        // 存储格式: salt_hex:hash_hex
        return Hex.toHexString(salt) + ":" + Hex.toHexString(hash);
    }

    // 验证密码
    public static boolean verifyPassword(String password, String storedHash) {
        String[] parts = storedHash.split(":");
        byte[] salt = Hex.decode(parts[0]);
        byte[] expected = Hex.decode(parts[1]);
        byte[] actual = sm3WithSalt(password, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static byte[] sm3WithSalt(String password, byte[] salt) {
        byte[] data = (password + Hex.toHexString(salt)).getBytes(StandardCharsets.UTF_8);
        byte[] hash = data;
        for (int i = 0; i < ITERATIONS; i++) {
            SM3Digest digest = new SM3Digest();
            digest.update(hash, 0, hash.length);
            hash = new byte[digest.getDigestSize()];
            digest.doFinal(hash, 0);
        }
        return hash;
    }
}
```

> **迁移说明**: OpenCode 使用 bcrypt（SHA-256 派生），格物统一为 SM3 + 盐值。迁移时需保留旧哈希验证能力——登录时先尝试验证 SM3，失败后回退到 bcrypt 验证并自动升级到 SM3。

### 2.4 API Key 管理

| 字段 | 说明 |
|------|------|
| 存储方式 | SM3 HMAC 哈希，不存储明文 |
| 前缀 | gew_（可在配置中自定义） |
| 有效期 | 支持 expiration（默认 365 天） |
| 匹配方式 | 前缀匹配 + 精确哈希验证 |
| 轮换策略 | 每季度自动轮换，旧 key 保留 30 天验证窗口 |

```sql
CREATE TABLE api_key (
    id VARCHAR(26) PRIMARY KEY,
    key_prefix VARCHAR(8) NOT NULL,   -- gew_**** 用于展示
    key_hash VARCHAR(64) NOT NULL,     -- SM3 HMAC 哈希
    user_id VARCHAR(26) NOT NULL,
    permissions JSONB,                 -- 绑定的权限列表
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    revoked_at BIGINT,
    FOREIGN KEY (user_id) REFERENCES user(id)
);
```

---

## 3. 传输安全

### 3.1 国密 TLS 配置

| 配置项 | 国密 TLS | 标准 TLS（兼容） | OpenCode 参考 |
|--------|---------|-----------------|--------------|
| 协议版本 | 国密 TLS 1.1+ | TLS 1.2 / 1.3 | HTTPS |
| 证书类型 | SM2 证书 | RSA/ECDSA | — |
| 加密套件 | ECDHE-SM2-SM4-GCM | ECDHE-RSA-AES256-GCM | 默认 HTTPS |
| 证书链 | 国密 CA (GMT 0006) | 国际 CA | — |

```yaml
# 国密 TLS 配置（主）
server:
  port: 8443
  ssl:
    enabled: true
    key-store: classpath:ssl/gm-server.p12
    key-store-password: ${SSL_KEY_PASSWORD}
    key-store-type: PKCS12
    ciphers:
      - TLS_SM4_GCM_SM3
      - TLS_SM4_CCM_SM3
      - TLS_ECDHE_SM2_WITH_SM4_GCM_SM3

# 标准 TLS 配置（兼容 fallback）
server:
  ssl:
    ciphers:
      - TLS_AES_256_GCM_SHA384
      - TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
```

### 3.2 OAuth 2.0 国密适配

OpenCode 使用标准 OAuth 2.0（JWT RS256）进行 MCP 服务器认证。格物将其适配为**国密 OAuth 2.0**：

| 组件 | OpenCode | 格物（国密适配） |
|------|---------|-----------------|
| 授权码 | 标准 authorization_code | authorization_code（国家秘密传输加强） |
| Token 签名 | RS256 (RSA) | SM3withSM2 (256 bit) |
| Token 加密 | — | SM4 加密 JWT payload（可选） |
| Client Auth | Basic Auth | SM2 签名 + SM3 HMAC |
| Refresh Token | 标准刷新 | 刷新令牌由 SM4 加密存储 |

```
┌─────────┐      ┌─────────┐      ┌─────────┐      ┌─────────┐
│ 客户端   │─────>│ API网关  │─────>│ 认证服务  │─────>│ 用户系统  │
│         │      │         │      │         │      │         │
│ SM2签名  │      │ 验证JWT  │      │ 颁发JWT  │      │ 验证密码  │
│ 请求     │      │ SM2验签 │      │ SM2签名 │      │ SM3哈希  │
└─────────┘      └─────────┘      └─────────┘      └─────────┘
     │               │               │               │
     │ 国密 TLS      │ 内部 gRPC     │ SM4 加密 DB   │ SM3 链审计
     ▼               ▼               ▼               ▼
  加密传输        加密通信          加密存储        防篡改日志
```

### 3.3 网络安全架构

```
┌────────────────────────────────────────────────────────┐
│                     公网                                │
└─────────────────────┬──────────────────────────────────┘
                      │ 国密 TLS / HTTPS
┌─────────────────────▼──────────────────────────────────┐
│                  负载均衡 / 反向代理                       │
│           SSL 卸载 (国密)、DDoS 防护、WAF                 │
└─────────────────────┬──────────────────────────────────┘
                      │ 内部 HTTP (可选 mTLS)
┌─────────────────────▼──────────────────────────────────┐
│                 API 网关 (自研)                          │
│  JWT(SM2)认证 / RBAC授权 / 令牌桶限流 / 断路器 / 审计    │
│  OpenCode 3 模式权限映射: Allow→放行 / Deny→拒绝 / Ask→RBAC审批 │
└─────────┬─────────┬──────────┬──────────┬──────────────┘
          │         │          │          │
          ▼         ▼          ▼          ▼
    ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
    │应用服务  │ │数据库   │ │缓存    │ │消息队列 │
    │ SM4加密 │ │ TLS    │ │ 认证   │ │ TLS    │
    └────────┘ └────────┘ └────────┘ └────────┘
          │         │          │          │
          └─────────┴──────────┴──────────┘
                    │ 内部网络隔离
             ┌──────▼──────┐
             │  沙箱网络隔离  │
             │  Firewall    │
             └─────────────┘
```

---

## 4. 认证与授权

### 4.1 统一认证模型

| 层 | 方案 | OpenCode 参考 |
|----|------|--------------|
| 用户认证 | JWT (SM3withSM2 签名) | OAuth 2.0 + API Key |
| 服务认证 | mTLS + API Key (SM3 HMAC) | API Key |
| 工具协议认证 | 国密 SM2 签名挑战-响应 | OAuth 2.0 (MCP) |
| 会话认证 | 权限继承（子会话继承父会话） | 权限继承 |

### 4.2 JWT 令牌规范

| 字段 | 格物规范 | OpenCode 参考 |
|------|---------|--------------|
| 签名算法 | SM3withSM2 | RS256 / HS256 |
| 令牌格式 | JWT (JSON Web Token) | JWT |
| 访问令牌有效期 | 24 小时 | 15 分钟 |
| 刷新令牌有效期 | 7 天 | 7 天 |
| 令牌内容 | userId, roles, permissions, exp, iat | session_id, user_id |

```json
{
  "alg": "SM3withSM2",
  "typ": "JWT"
}
{
  "sub": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "username": "zhangsan",
  "roles": ["ARCHITECT"],
  "permissions": [
    "project:read",
    "project:write",
    "session:read",
    "session:write",
    "tool:file_read:allow",
    "tool:shell_exec:ask"
  ],
  "session_permissions": {
    "inherit_from": "session_parent_id",
    "overrides": ["tool:file_write:deny"]
  },
  "exp": 1762560000000,
  "iat": 1762473600000
}
```

### 4.3 RBAC + 交互式权限模型

格物的 RBAC 融合 OpenCode 的 allow/deny/ask 三种交互模式：

| 权限类型 | RBAC 映射 | 交互模式 | OpenCode 对应 |
|---------|----------|---------|--------------|
| 资源访问（读） | 角色→权限→资源 | Allow（自动） | file_read（项目目录内） |
| 资源修改（写） | 角色→权限→资源 | Ask（询问用户） | file_write（需授权） |
| Shell 执行 | 角色→权限→命令白名单 | Ask（询问用户） | shell_exec（需授权） |
| 网络请求 | 角色→权限→域名白名单 | Ask/Deny | network_request |
| 工具调用 | 角色→权限→工具 | Allow/Deny/Ask | tool_exec（按工具类型） |
| Agent 配置 | 架构师/管理员 | Allow（角色内） | admin |

```
权限决策流程（融合 OpenCode 交互式模式）:
  1. 检查 RBAC 角色权限 → 有则 Allow
  2. 检查权限持久化缓存 → 命中则应用历史决策
  3. 检查自动响应规则 → 匹配则应用规则
  4. 检查权限继承（父子会话）→ 继承父会话决策
  5. 以上均无 → Ask（询问用户）
```

### 4.4 权限持久化

```java
/**
 * 权限决策持久化 — 避免重复询问
 * 从 OpenCode permission.ts 吸收
 */
@Entity
@Table(name = "permission_decision")
public class PermissionDecision {

    @Id
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String toolId;       // 工具 ID

    @Column
    private String resourcePath; // 资源路径（可选）

    @Enumerated(EnumType.STRING)
    private Decision decision;   // ALLOW / DENY

    @Column(nullable = false)
    private Long createdAt;

    @Column
    private Long expiresAt;      // 过期时间（默认 24h）
}

public enum Decision {
    ALLOW, DENY
}
```

### 4.5 自动响应规则

从 OpenCode 的 auto-response rules 吸收，配置方式：

```yaml
permission:
  auto-respond:
    rules:
      - id: allow-file-read-in-project
        tool: file_read
        path: "/workspace/**"
        decision: ALLOW
        priority: 10
      - id: deny-rm-rf
        tool: shell_exec
        command: "rm -rf *"
        decision: DENY
        priority: 100
      - id: ask-network-external
        tool: network_request
        domain: "*"
        decision: ASK
        priority: 1
```

---

## 5. 数据安全

### 5.1 数据分类分级

| 级别 | 定义 | 示例 | 保护要求 | OpenCode 参考 |
|------|------|------|---------|--------------|
| L1 公开 | 对外公开的信息 | 产品名称、版本号 | 完整性保护 | 公开日志 |
| L2 内部 | 内部使用 | 项目名、成员列表 | 访问控制 | 会话元数据 |
| L3 敏感 | 敏感信息 | 用户手机、邮箱、角色权限 | SM4 加密 + ACL | Session 内容 |
| L4 机密 | 高度机密 | 密码、令牌密钥、数据库密码 | SM4 加密 + 最小权限 + 审计 | API Key, Token |

### 5.2 SM4 数据加密策略

| 数据类别 | 存储加密 | 传输加密 | 脱敏 |
|---------|---------|---------|------|
| 用户密码 | SM3 哈希 + 盐值 | 国密 TLS | 不展示 |
| 用户手机号 | SM4/GCM/NoPadding | 国密 TLS | 138****1234 |
| 用户邮箱 | SM4/GCM/NoPadding | 国密 TLS | u***@example.com |
| JWT 令牌 | — | 国密 TLS | 不展示 |
| API 密钥 | SM3 HMAC | 国密 TLS | gew_****abcd |
| 会话消息 | 可选 SM4 | 国密 TLS | 按权限展示 |
| 文件存储 | SM4/GCM/NoPadding | 国密 TLS | 按权限展示 |
| 配置密钥 | SM4 加密 | TLS | 不展示 |

### 5.3 Schema 验证（从 OpenCode 吸收）

OpenCode 使用 Effect Schema 进行工具参数验证。格物统一为注解式验证 + JSON Schema：

```java
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SchemaValidation {
    String jsonSchema() default "";      // JSON Schema 路径
    boolean required() default true;
    String pattern() default "";         // 正则验证
    long maxLength() default -1;
    long minLength() default -1;
}

// 工具参数验证
public class ToolParameterValidator {

    public static ValidationResult validate(Object params, String schemaPath) {
        // 1. 加载 JSON Schema
        // 2. 验证参数类型、范围、必填项
        // 3. 过滤危险输入（SQL 注入、路径遍历）
        // 4. 返回验证结果
        JsonSchema schema = JsonSchemaFactory.getInstance().getSchema(schemaPath);
        Set<ValidationMessage> errors = schema.validate(params);
        return errors.isEmpty()
            ? ValidationResult.passed()
            : ValidationResult.failed(errors);
    }
}
```

### 5.4 SM4 工具类

```java
/**
 * SM4 对称加密工具类
 * 用途: 数据加密存储、通信加密
 * Bouncy Castle 1.77
 */
public class SM4Utils {

    private static final String ALGORITHM = "SM4";
    private static final String CIPHER = "SM4/GCM/NoPadding";

    // 生成 SM4 密钥
    public static byte[] generateKey() {
        KeyGenerator kg = KeyGenerator.getInstance(ALGORITHM, "BC");
        kg.init(128, new SecureRandom());
        return kg.generateKey().getEncoded();
    }

    // SM4-GCM 加密 (AEAD 认证加密)
    public static byte[] encrypt(byte[] data, byte[] key, byte[] iv) {
        Cipher cipher = Cipher.getInstance(CIPHER, "BC");
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        return cipher.doFinal(data);
    }

    // SM4-GCM 解密 (AEAD 认证加密)
    public static byte[] decrypt(byte[] cipherData, byte[] key, byte[] iv) {
        Cipher cipher = Cipher.getInstance(CIPHER, "BC");
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        return cipher.doFinal(cipherData);
    }
}
```

### 5.5 SM2 工具类

```java
/**
 * SM2 非对称加密工具类
 * 用途: JWT 签名、密钥交换、数字签名
 * Bouncy Castle 1.77
 */
public class SM2Utils {

    // 生成 SM2 密钥对
    public static KeyPair generateKeyPair() {
        ECGenParameterSpec sm2Spec = new ECGenParameterSpec("sm2p256v1");
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC");
        keyPairGenerator.initialize(sm2Spec, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
    }

    // SM2 签名 (SM3withSM2)
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

    // SM2 加密 (C1C3C2)
    public static byte[] encrypt(byte[] data, PublicKey publicKey) {
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

### 5.6 SM3 工具类

```java
/**
 * SM3 密码杂凑工具类
 * 用途: 密码存储、数据完整性、消息摘要
 * Bouncy Castle 1.77
 */
public class SM3Utils {

    public static byte[] hash(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

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
}
```

### 5.7 密钥管理方案

```
┌─────────────────────────────────────────────────────────┐
│                    密钥管理系统                           │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ 主密钥 (MK)   │  │ 数据密钥 (DK) │  │ 会话密钥 (SK) │   │
│  │ HSM/KMS      │  │ 由 MK 加密    │  │ 由 DK 派生    │   │
│  │ 硬件加密保存   │  │ 定期轮换      │  │ 一次性使用     │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
│                                                         │
│  密钥层级: MK → KEK (密钥加密密钥) → DK (数据加密密钥)     │
└─────────────────────────────────────────────────────────┘
```

| 密钥类型 | 轮换周期 | 方式 |
|---------|---------|------|
| JWT SM2 签名密钥 | 每月 | 自动轮换，旧密钥保留验证窗口 |
| SM4 数据密钥 | 每季度 | 自动轮换，数据逐步重加密 |
| SM4 主密钥 | 每年 | 手动轮换（HSM） |
| API Key | 每季度 | 自动轮换，旧 key 保留 30 天 |

---

## 6. 沙箱隔离

### 6.1 沙箱类型对比

| 特性 | Firecracker (L1) | gVisor (L2) | Docker/iSulad (L3) | OpenCode 沙箱 |
|------|-----------------|-------------|-------------------|--------------|
| 隔离级别 | 硬件级 MicroVM | 用户态内核 | 容器级 | 文件系统 ACL |
| 安全等级 | 最高 | 中 | 基础 | 轻量（单进程） |
| 启动速度 | 慢（~2s） | 中（~1s） | 快（~200ms） | 极快（即时） |
| 资源开销 | 高 | 中 | 低 | 极低 |
| 适用场景 | 不可信代码 | 内部 Agent | 可信快速执行 | 本地开发调试 |
| 信创支持 | ✅ | ✅ | iSulad (华为) | — |

### 6.2 沙箱安全策略

格物沙箱融合 OpenCode 的轻量级文件系统 ACL 理念作为"预检层"：

```
沙箱安全策略链:
  1. OpenCode 项目目录 ACL（快速预检）→ 文件路径白名单
  2. Shell 命令白名单/黑名单 → 命令过滤
  3. 网络域名+端口白名单 → 网络沙箱
  4. Docker/gVisor/Firecracker 隔离 → 运行时隔离
  5. seccomp/AppArmor → 系统调用过滤
```

```java
@Data
@Builder
public class UnifiedSecurityPolicy {
    // OpenCode 层（轻量预检）
    private List<String> allowedReadPaths;     // 文件读白名单
    private List<String> allowedWritePaths;    // 文件写白名单
    private List<String> allowedCommands;      // 命令白名单
    private List<String> blockedCommands;      // 命令黑名单
    private List<String> allowedDomains;        // 域名白名单
    private List<Integer> allowedPorts;         // 端口白名单

    // Gewu 层（运行时隔离）
    private SecurityLevel level;               // L1/L2/L3
    private int cpuLimit;                      // CPU 限制 (millicores)
    private long memoryLimit;                  // 内存限制 (bytes)
    private long diskLimit;                    // 磁盘限制 (bytes)
    private int networkLimit;                  // 网络限制 (Mbps)
    private Duration timeout;                  // 执行超时
}
```

### 6.3 文件系统沙箱（从 OpenCode 吸收）

OpenCode 的轻量级 3 沙箱模型互补格物企业级容器沙箱：

| OpenCode 沙箱 | 格物映射 | 说明 |
|--------------|---------|------|
| 文件系统（项目目录 ACL） | 统一安全策略 → allowedPaths | 快速预检，避免容器启动 |
| Shell（命令权限控制） | ProcessPolicy → allowedCommands | 命令白名单 + 自动响应 |
| 网络（MCP 连接限制） | NetworkPolicy → allowedDomains | 域名白名单 + 端口气氛 |

---

## 7. 审计日志

### 7.1 审计策略

| 日志类别 | 记录内容 | 存储周期 | OpenCode 参考 |
|---------|---------|---------|--------------|
| 登录日志 | 登录/登出/失败/锁定 | 1 年 | — |
| 操作日志 | CRUD/执行/配置变更 | 1 年 | Event 系统 |
| 权限变更 | 角色分配/权限修改 | 3 年 | Permission 决策记录 |
| 安全事件 | 异常访问/攻击检测 | 3 年 | Sentry 错误追踪 |
| 工具执行 | 工具调用/参数/结果/超时 | 1 年 | Tool execution log |
| 权限决策 | allow/deny/ask 记录 | 1 年 | Permission persistence |
| 沙箱操作 | 沙箱创建/销毁/命令 | 1 年 | — |

### 7.2 审计日志防篡改链

```
每条审计日志包含前一条日志的哈希链：
  Log[0]: hash = SM3(content[0])
  Log[1]: hash = SM3(content[1] + hash[0])
  Log[2]: hash = SM3(content[2] + hash[1])
  ...

验证: 遍历日志链，验证每条的 hash 是否等于 SM3(content[n] + hash[n-1])
```

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000,
  "user": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "username": "zhangsan",
    "ip": "192.168.1.100"
  },
  "action": {
    "type": "PERMISSION_DECISION",
    "tool": "file_write",
    "resource": "/workspace/src/main.ts",
    "decision": "ASK",
    "result": "GRANTED",
    "duration": 2300
  },
  "chain": {
    "prev_hash": "abc123...",
    "hash": "def456...",
    "signature": "SM3withSM2签名值"
  }
}
```

### 7.3 OpenCode Event 系统 → 审计日志映射

| OpenCode Event | 格物审计事件 | 说明 |
|---------------|------------|------|
| session.created | AUDIT_SESSION_CREATE | 会话创建 |
| message.created | AUDIT_MESSAGE_SEND | 消息发送 |
| tool.executed | AUDIT_TOOL_EXECUTE | 工具执行 |
| permission.requested | AUDIT_PERMISSION_DECISION | 权限请求/决策 |
| session.updated | AUDIT_SESSION_UPDATE | 会话更新 |

---

## 8. 限流与熔断

### 8.1 统一限流策略

融合 OpenCode 的 API 级别限流与格物的网关动态令牌桶：

| 限流层级 | OpenCode | 格物 | 统一方案 |
|---------|---------|------|---------|
| 读取接口 | 100 次/分钟 | 网关默认 1000 QPS | 100/分钟（用户级）+ 1000 QPS（全局） |
| 写入接口 | 20 次/分钟 | 网关默认 1000 QPS | 20/分钟（用户级）+ 200 QPS（全局） |
| 工具执行 | 10 次/分钟 | 网关 default-limit | 10/分钟（用户级）+ 50 QPS（全局） |
| MCP/工具协议连接 | 5 次/分钟 | — | 5/分钟（用户级，自研协议同） |
| 登录 | — | 单 IP 100 QPS | 5 次/分钟（单用户），10 次/分钟（单 IP） |

### 8.2 动态令牌桶实现

```java
/**
 * 动态令牌桶限流器
 * 融合 OpenCode 按接口组限流 + 格物按用户/IP 限流
 */
@Component
public class DynamicTokenBucketRateLimiter {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        TokenBucket bucket = buckets.computeIfAbsent(
            key, k -> new TokenBucket(rule.getCapacity(), rule.getRefillRate()));
        return bucket.tryConsume();
    }

    // 限流规则（来自配置）
    public static class RateLimitRule {
        private String apiGroup;          // Read/Write/Tool/MCP
        private int capacity;              // 令牌桶容量
        private int refillRate;            // 填充速率 (tokens/sec)
        private int userLimit;             // 单用户限制
        private int ipLimit;               // 单 IP 限制
    }
}

// 网关限流 YAML 配置
gateway:
  rate-limit:
    enabled: true
    algorithm: dynamic-token-bucket
    rules:
      - api-group: read
        capacity: 100
        refill-rate: 2
        user-limit: 100    # 合并 OpenCode 100/min
        ip-limit: 500
      - api-group: write
        capacity: 20
        refill-rate: 1
        user-limit: 20     # 合并 OpenCode 20/min
        ip-limit: 100
      - api-group: tool
        capacity: 10
        refill-rate: 1
        user-limit: 10     # 合并 OpenCode 10/min
        ip-limit: 50
      - api-group: tool-protocol
        capacity: 5
        refill-rate: 1
        user-limit: 5      # 合并 OpenCode MCP 5/min
        ip-limit: 20
```

### 8.3 断路器配置

| 参数 | 值 | 说明 |
|------|-----|------|
| failure-threshold | 50% | 失败率触发熔断 |
| timeout-ms | 30000 | 请求超时（与工具超时一致） |
| half-open-delay-ms | 60000 | 半开延迟 |
| min-calls | 10 | 触发计算的最小请求数 |

---

## 9. 等保 2.0 三级合规

### 9.1 等保 2.0 三级要求清单

| 安全类别 | 控制点 | 等保要求 | 格物实现 | 状态 |
|---------|--------|---------|---------|------|
| **物理安全** | 物理访问控制 | 机房访问控制 | 云平台物理安全（阿里云/华为云） | ✅ |
| | 防盗窃和防破坏 | 设备固定、视频监控 | 云平台保障 | ✅ |
| | 温湿度控制 | 机房温湿度监控 | 云平台保障 | ✅ |
| **网络安全** | 网络隔离 | 划分不同安全区域 | VPC + 安全组 + ACL | ✅ |
| | 访问控制 | 网络边界访问控制 | API 网关 + 国密 TLS | ✅ |
| | 入侵防范 | 网络入侵检测 | WAF + IDS/IPS | ⚠️ 需部署 |
| | 安全审计 | 网络日志审计 | 审计日志（SM3 链防篡改） | ✅ |
| | 边界防护 | 网络边界防护 | 负载均衡 + WAF | ✅ |
| **主机安全** | 身份鉴别 | 主机登录认证 | JWT + SM2 证书 | ✅ |
| | 访问控制 | 主机资源访问控制 | RBAC + 最小权限 | ✅ |
| | 入侵防范 | 主机漏洞管理 | Trivy 镜像扫描 + 系统补丁 | ⚠️ 需实施 |
| | 恶意代码防范 | 主机防病毒 | 镜像安全扫描 | ⚠️ 需实施 |
| **应用安全** | 身份鉴别 | 用户身份认证 | 密码/验证码/JWT/SM2 | ✅ |
| | 访问控制 | 细粒度权限控制 | RBAC + Allow/Deny/Ask | ✅ |
| | 安全审计 | 应用操作审计 | 审计日志 + Event 系统 | ✅ |
| | 通信完整性 | 数据传输完整性 | 国密 TLS + SM3 HMAC | ✅ |
| | 通信保密性 | 数据传输加密 | 国密 TLS + SM4 加密 | ✅ |
| | 抗抵赖 | 操作不可否认 | SM2 数字签名 + 日志链 | ✅ |
| | 软件容错 | 异常处理 | 断路器 + 超时熔断 | ✅ |
| | 资源控制 | 会话/连接/资源限制 | 令牌桶限流 + 沙箱资源限制 | ✅ |
| **数据安全** | 数据完整性 | 存储数据完整性 | SM3 哈希链验证 | ✅ |
| | 数据保密性 | 存储数据加密 | SM4/GCM/PKCS7 | ✅ |
| | 备份恢复 | 数据备份恢复 | OceanBase 备份 + 异地容灾 | ✅ |
| | 剩余信息保护 | 数据清除 | 账号注销 + 数据清除策略 | ⚠️ 需实施 |
| | 个人信息保护 | 个人隐私保护 | 数据脱敏 + SM4 加密 | ✅ |
| **安全管理** | 安全管理制度 | 安全策略、制度 | 安全管理制度文档 | ⚠️ 需建立 |
| | 安全管理机构 | 安全岗位设置 | 安全工程师角色 | ✅ |
| | 人员安全管理 | 人员安全培训 | 安全培训计划 | ⚠️ 需制定 |
| | 系统建设管理 | 定级备案 | 系统定级报告 | ⚠️ 需提交 |
| | 系统运维管理 | 日常运维安全 | Nightingale 监控 + 告警 | ✅ |

### 9.2 等保三级差距分析

| 未覆盖项 | 风险级别 | 整改计划 | 负责人 |
|---------|---------|---------|--------|
| 入侵检测系统 | 高 | Phase 2 部署 IDS | 运维 |
| 主机防病毒 | 中 | Phase 2 集成 ClamAV | 运维 |
| 安全管理制度 | 高 | Phase 1 完成制度编写 | 安全工程师 |
| 系统定级备案 | 高 | Phase 1 提交定级报告 | 安全工程师 |
| 数据清除策略 | 中 | Phase 2 实现安全删除 | 开发 |
| 人员安全培训 | 中 | Phase 2 制定培训计划 | 安全工程师 |

---

## 10. 信创合规

### 10.1 NF-X 信创需求清单

| 编号 | 要求 | 格物实现 | OpenCode 替换说明 | 状态 |
|------|------|---------|-----------------|------|
| NF-X01 | 信创目录符合 | 所有选型组件在信创目录 | MCP → 自研工具协议 | ✅ |
| NF-X02 | 自主可控 | API 网关自研、工作流引擎自研、工具协议自研 | OAuth 2.0 → SM2 认证 | ✅ |
| NF-X03 | 国密算法 | SM2/SM3/SM4 Bouncy Castle 1.77 | ECDSA/ECDH → SM2, SHA-256 → SM3, AES → SM4 | ✅ |
| NF-X04 | 数据本地化 | OceanBase 数据库 | SQLite（本地）→ OceanBase | ✅ |
| NF-X05 | 安全审查 | 通过等保 2.0 三级测评 | 无等保要求 | ⚠️ Phase 1 |
| NF-X06 | 开源合规 | 使用的开源组件符合信创许可 | 需审计所有依赖 | ⚠️ 持续 |

### 10.2 信创组件清单

| 组件类型 | 信创组件 | 替代组件 | 版本 | 说明 |
|---------|---------|---------|------|------|
| JDK | 毕昇 JDK / 龙芯 JDK | OpenJDK | JDK 21+ | 适配国产 CPU |
| 数据库 | OceanBase | — | 4.2+ | 原生信创 |
| 缓存 | DragonflyDB | Redis | 1.27+ | 信创兼容 |
| 消息队列 | RocketMQ | — | 5.1+ | 国产自研 |
| 容器运行时 | iSulad | Docker | — | 华为开源 |
| 监控 | Nightingale | Prometheus | — | 国产开源 |
| CI/CD | Gitea + Drone | GitHub Actions | — | 国产自托管 |
| 国密库 | Bouncy Castle | — | 1.77 | FIPS 140-2 认证 |

### 10.3 密码算法替换对照

| 场景 | 国际算法 | 国密算法 | 替换影响 |
|------|---------|---------|---------|
| JWT 签名 | RS256 / ES256 | SM3withSM2 | 密钥格式、签名长度变化 |
| 密码哈希 | bcrypt / SHA-256 | SM3 + 盐值 + 迭代 | 兼容迁移需保留旧验证 |
| 数据加密 | AES-128/GCM | SM4/GCM/PKCS7 | 密钥长度、算法标识变化 |
| TLS 加密套件 | ECDHE-RSA-AES256-GCM | ECDHE-SM2-SM4-GCM | 证书格式变化（SM2 证书） |
| 哈希完整性 | SHA-256 | SM3 | 哈希值长度相同，替换透明 |

---

## 11. OpenCode → 格物 安全机制映射表

| OpenCode 安全机制 | 格物等价实现 | 映射方式 | 优先级 |
|-----------------|------------|---------|--------|
| 文件系统沙箱（项目目录 ACL） | 统一安全策略 → allowedPaths | 吸收为轻量预检层 | P0 |
| Shell 沙箱（命令权限控制） | ProcessPolicy → allowedCommands | 吸收为命令白名单 | P0 |
| 网络沙箱（MCP 连接限制） | NetworkPolicy → allowedDomains | 吸收为域名白名单 | P0 |
| Allow（自动允许） | RBAC 角色有权限 → Allow | 映射为角色权限判断 | P0 |
| Deny（自动拒绝） | RBAC 角色无权限 → Deny | 映射为角色权限判断 | P0 |
| Ask（询问用户） | 权限持久化未命中 → 询问 | 吸收为交互式审批 | P0 |
| 权限持久化 | permission_decision 表 | 直接吸收数据结构 | P1 |
| 自动响应规则 | auto_respond_rules 配置 | 直接吸收设计 | P1 |
| 权限继承（父子会话） | session.parent_id 继承 | 直接吸收逻辑 | P1 |
| OAuth 2.0（MCP） | 国密 OAuth 2.0（SM2 签名） | 替换算法，保留流程 | P0 |
| API Key | api_key 表 + SM3 HMAC | 增强实现 | P0 |
| Schema 验证 | JSON Schema 验证 | 替换技术，保留理念 | P0 |
| 参数化查询 | MyBatis + 参数绑定 | 技术栈替换 | P0 |
| 输入过滤 | 统一过滤器 + WAF | 扩展实现 | P0 |
| Sentry 错误追踪 | Nightingale 告警 | 替换为国产监控 | P1 |
| 限流: 读 100/min | 令牌桶 read 规则 | 吸收为 API 组规则 | P0 |
| 限流: 写 20/min | 令牌桶 write 规则 | 吸收为 API 组规则 | P0 |
| 限流: 工具 10/min | 令牌桶 tool 规则 | 吸收为 API 组规则 | P0 |
| 限流: MCP 5/min | 令牌桶 tool-protocol 规则 | 吸收为 API 组规则 | P0 |
| 事件系统 (Event) | 审计日志 + 消息队列 | 映射为审计日志源 | P1 |

---

## 12. MCP 安全替换方案

### 12.1 替换总览

OpenCode 使用 MCP (Model Context Protocol) 进行 AI 工具集成，其安全架构基于 OAuth 2.0。格物因信创合规替换为**自研工具协议**，安全架构全面升级为国密：

| 安全特性 | MCP (OpenCode) | 自研工具协议 (格物) |
|---------|---------------|-------------------|
| 认证方式 | OAuth 2.0 (RS256) | 国密 SM2 签名挑战-响应 |
| 传输加密 | TLS 1.2+ | 国密 TLS (SM2/SM4) |
| 数据完整性 | — | SM3 HMAC 消息认证码 |
| 工具发现 | 自动发现 | 自动发现 + 权限绑定 |
| 会话管理 | OAuth Session | SM4 加密会话令牌 |
| 令牌格式 | JWT (RS256) | JWT (SM3withSM2) |
| 连接管理 | stdio/SSE/HTTP | stdio/SSE/HTTP (加密增强) |

### 12.2 自研工具协议认证流程

```
工具服务器认证（替换 MCP OAuth 2.0）:

  1. 客户端 → 工具服务器: 连接请求 + 客户端 SM2 公钥
  2. 工具服务器 → 客户端: 随机挑战 nonce（SM4 加密传输）
  3. 客户端 → 工具服务器: SM2 签名(nonce) + 客户端证书
  4. 工具服务器: 验证 SM2 签名 → 颁发会话令牌 (JWT SM3withSM2)
  5. 后续请求: 携带 JWT 令牌进行认证
```

### 12.3 连接状态机

```
[*] → 断开
断开 → 连接中: 发起连接（携带 SM2 公钥）
连接中 → 已连接: SM2 挑战-响应验证通过
连接中 → 失败: 连接/超时错误
连接中 → 需要认证: 证书无效 / SM2 验签失败
已连接 → 断开: 连接断开 / 超时
已连接 → 需要认证: JWT 过期（SM3withSM2 重签）
需要认证 → 连接中: 重新认证（SM2 重签名挑战）
需要认证 → 失败: 认证失败超过阈值
失败 → 断开: 手动重置
```

### 12.4 MCP → 自研协议安全配置映射

```yaml
# OpenCode MCP 配置
mcp_servers:
  my-server:
    transport: sse
    url: https://tools.example.com/mcp
    auth:
      type: oauth2
      client_id: ${CLIENT_ID}
      client_secret: ${CLIENT_SECRET}

# 格物 自研工具协议配置
tool-protocol:
  servers:
    my-server:
      transport: sse
      url: https://tools.example.com/gwtp
      auth:
        type: gm-sign-challenge     # 国密 SM2 挑战-响应
        sm2-cert: classpath:certs/client-sm2.pem
        sm2-private-key: classpath:certs/client-sm2-key.pem
        ca-cert: classpath:certs/gm-ca.pem   # 国密 CA
      encryption:
        algorithm: SM4/GCM/NoPadding
        key-exchange: SM2           # 密钥交换
      rate-limit: 5/min             # 映射 MCP 5/min 限流
```

---

## 12. 工作流安全控制

### 12.1 工作流权限模型

工作流引擎支持节点级权限控制，确保每个流程节点只能由授权角色执行：

| 节点类型 | 执行角色 | 审核角色 | 说明 |
|----------|----------|----------|------|
| requirement_collect | 产品经理 | — | 需求收集 |
| requirement_review | — | 技术负责人 | 需求评审 |
| design | 架构师 | — | 系统设计 |
| design_review | — | 技术负责人 | 设计评审 |
| development | 开发工程师 | — | 编码开发 |
| code_review | — | 架构师 | 代码审查 |
| testing | 测试工程师 | — | 测试执行 |
| test_review | — | QA 经理 | 测试评审 |
| security_scan | 安全工程师 | — | 安全扫描 |
| deployment_approval | — | 技术负责人 | 部署审批 |

### 12.2 工作流权限表

```sql
-- 工作流权限表
CREATE TABLE workflow_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    permission_type VARCHAR(20) NOT NULL,  -- START, EXECUTE, REVIEW, MANAGE
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow_role_permission (workflow_id, role_code, permission_type),
    INDEX idx_workflow_id (workflow_id),
    INDEX idx_role_code (role_code)
);

-- 工作流权限矩阵
CREATE TABLE workflow_permission_matrix (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    node_type VARCHAR(50) NOT NULL,
    required_role VARCHAR(50) NOT NULL,
    permission_level VARCHAR(20) NOT NULL, -- EXECUTE, REVIEW, APPROVE
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow_node_role (workflow_id, node_type, required_role)
);
```

### 12.3 工作流审计日志

```sql
-- 工作流审计日志表
CREATE TABLE workflow_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    instance_id BIGINT,
    node_id VARCHAR(50),
    operation VARCHAR(50) NOT NULL,  -- START, COMPLETE, REVIEW, REJECT, CANCEL, TIMEOUT
    operator_id BIGINT NOT NULL,
    operator_name VARCHAR(50),
    before_state VARCHAR(20),
    after_state VARCHAR(20),
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    request_body TEXT,
    response_code INT,
    response_time BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_workflow_id (workflow_id),
    INDEX idx_instance_id (instance_id),
    INDEX idx_operator_id (operator_id),
    INDEX idx_operation (operation),
    INDEX idx_created_at (created_at)
);
```

### 12.4 工作流数据保留策略

数据保留 180 天，与平台统一策略一致：

```sql
-- 清理过期的工作流审计日志（保留 180 天）
DELETE FROM workflow_audit_log WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY);

-- 清理过期的工作流实例（保留 180 天）
DELETE FROM workflow_instance WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY) AND status IN ('COMPLETED', 'CANCELLED');

-- 归档历史数据（归档后保留 365 天）
INSERT INTO workflow_audit_log_archive SELECT * FROM workflow_audit_log WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY);
DELETE FROM workflow_audit_log WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY);
```

---

## 13. 镜像签名与密钥管理

### 13.1 镜像签名

使用 Cosign 对容器镜像进行签名，确保镜像完整性和来源可信：

```yaml
# .github/workflows/image-signing.yml
jobs:
  sign-image:
    runs-on: ubuntu-latest
    steps:
      - name: Install Cosign
        uses: sigstore/cosign-installer@main
        
      - name: Sign Image
        run: |
          cosign sign --key env://COSIGN_KEY ${{ github.repository }}:${{ github.ref_name }}
        env:
          COSIGN_KEY: ${{ secrets.COSIGN_PRIVATE_KEY }}
          
      - name: Verify Image
        run: |
          cosign verify --key env://COSIGN_KEY ${{ github.repository }}:${{ github.ref_name }}
        env:
          COSIGN_KEY: ${{ secrets.COSIGN_PUBLIC_KEY }}
```

### 13.2 密钥管理策略

采用"密钥生成 → 安全存储 → 定期轮换 → 销毁"全生命周期管理：

| 密钥类型 | 轮换周期 | 存储方式 | 说明 |
|----------|----------|----------|------|
| Cosign 签名密钥 | 90 天 | GitHub Secrets / Vault | 镜像签名 |
| JWT 签名密钥 | 90 天 | Vault | Token 签发 |
| 数据库连接密码 | 90 天 | SM4 加密 + Vault | JDBC 连接 |
| KMS 加密密钥 | 365 天 | 国产 KMS | 数据加密 |
| SSL/TLS 证书 | 365 天 | PKCS12 + 国密 | HTTPS |

### 13.3 密钥销毁流程

```bash
# 1. 生成新密钥对
cosign generate-key-pair

# 2. 更新 CI/CD 配置使用新密钥
# 3. 验证新密钥签名能力
cosign verify --key cosign.pub ${{ github.repository }}:latest

# 4. 归档旧密钥（保留 30 天回滚期）
# 5. 安全删除旧私钥
shred -u cosign.key

# 6. 更新密钥管理记录
```

### 13.4 镜像安全基线

| 检查项 | 要求 | 说明 |
|--------|------|------|
| **基础镜像** | 使用官方镜像或可信镜像 | 避免使用未知来源镜像 |
| **最小化** | 使用 Alpine 或 Distroless | 减少攻击面 |
| **非 root** | 容器以非 root 用户运行 | 降低权限提升风险 |
| **只读文件系统** | 文件系统设为只读 | 防止恶意写入 |
| **无敏感信息** | 镜像中不含密钥、密码 | 使用 Secret 管理 |
| **漏洞扫描** | 无 CRITICAL/HIGH 漏洞 | 定期扫描更新 |

### 13.5 密钥存储方案

| 存储方式 | 安全性 | 适用场景 | 说明 |
|----------|--------|----------|------|
| **GitHub Secrets** | ⭐⭐⭐ | 开源项目 | 免费，但仅限 GitHub Actions |
| **HashiCorp Vault** | ⭐⭐⭐⭐⭐ | 企业环境 | 集中式密钥管理，支持审计 |
| **KMS (国产)** | ⭐⭐⭐⭐ | 信创环境 | 国密支持，合规要求 |
| **HSM** | ⭐⭐⭐⭐⭐ | 高安全要求 | 硬件级安全 |

---

## 14. 安全配置基线

### 13.1 Spring Boot 安全配置

```yaml
# 格物安全基线 — 融合 OpenCode 安全实践
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

spring:
  session:
    timeout: 30m
    cookie:
      http-only: true
      secure: true
      same-site: strict

# 沙箱安全配置
sandbox:
  security:
    blocked-commands:
      - "rm -rf /"
      - "mkfs"
      - "dd"
      - "format"
      - "shutdown"
      - "reboot"
    blocked-paths:
      - "/etc"
      - "/var"
      - "/proc"
      - "/sys"
    auto-respond:
      enabled: true
      default-mode: ask        # 默认询问模式
      rules:
        - tool: file_read
          path: "/workspace/**"
          decision: allow
        - tool: shell_exec
          command: "ls,cat,pwd,echo"
          decision: allow

# 工具执行安全
tool:
  execution:
    default-timeout-ms: 30000   # 映射 OpenCode 30s 超时
    max-result-size: 10485760   # 10MB 结果限制
    parameter-validation:
      enabled: true
      schema-path: classpath:schema/
```

### 13.2 登录安全策略

```yaml
security:
  login:
    max-attempts: 5
    lockout-duration: 30m
    password-complexity:
      min-length: 8
      require-uppercase: true
      require-lowercase: true
      require-digit: true
      require-special: true
    session-timeout: 30m
    geo-detection:
      enabled: true
      allowed-regions:
        - CN
      action: warn
```

---

## 15. 附录

### 14.1 Bouncy Castle 依赖

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

### 14.2 安全测试清单

| 测试类型 | 工具 | 频率 | 说明 |
|---------|------|------|------|
| SAST | SonarQube | 每次提交 | 静态代码分析 |
| DAST | OWASP ZAP | 每周 | 动态应用安全测试 |
| 依赖扫描 | OWASP Dependency-Check | 每次构建 | CVE 检查 |
| 容器扫描 | Trivy | 每次构建 | 镜像漏洞扫描 |
| 密钥扫描 | GitLeaks | 每次提交 | 敏感信息泄露检测 |
| 渗透测试 | 手动 | 每季度 | 深度安全评估 |
| 国密合规测试 | 国密局标准 | 每次发布 | SM2/SM3/SM4 合规验证 |

### 14.3 应急响应

| 级别 | 定义 | 响应时间 | 示例 |
|------|------|---------|------|
| P0 紧急 | 系统严重受损，数据泄露 | < 15 分钟 | SQL 注入、国密密钥泄露 |
| P1 高危 | 高危漏洞，影响部分用户 | < 1 小时 | XSS、CSRF、权限绕过 |
| P2 中危 | 中危漏洞，影响有限 | < 4 小时 | 配置不当、弱密码 |
| P3 低危 | 低风险问题 | < 24 小时 | 信息泄露、文档错误 |

### 14.4 修订历史

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------|---------|--------|
| V1.0 | 2026-07-08 | 初稿 | — |
| V1.1 | 2026-07-08 | 增加 V5.0 工作流安全控制（§12）、镜像签名与密钥管理（§13） | 统一文档合并 |

---

**文档结束 — 全文共 14 章、涵盖 8 个安全领域、25 项等保 2.0 三级控制点、11 项 OpenCode 安全机制映射**
