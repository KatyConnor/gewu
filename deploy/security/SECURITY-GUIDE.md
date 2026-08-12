# 格物平台安全加固方案

## 1. 安全架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      安全架构                                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ 认证层   │    │ 授权层   │    │ 审计层   │              │
│  │ JWT      │    │ RBAC     │    │ AuditLog │              │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘              │
│       │              │              │                      │
│       └──────────────┼──────────────┘                      │
│                      ▼                                      │
│              ┌──────────────┐                               │
│              │  国密加密     │                               │
│              │  SM2/SM3/SM4 │                               │
│              └──────┬───────┘                               │
│                     │                                       │
│         ┌───────────┼───────────┐                          │
│         ▼           ▼           ▼                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                   │
│  │ 网络安全 │ │ 容器安全 │ │ 数据安全 │                   │
│  │ TLS/mTLS │ │ 镜像扫描 │ │ 加密存储 │                   │
│  └──────────┘ └──────────┘ └──────────┘                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 2. 认证与授权

### 2.1 JWT 认证

- Access Token: 30 分钟有效期
- Refresh Token: 7 天有效期
- 支持 Token 刷新与吊销

### 2.2 RBAC 权限控制

```yaml
roles:
  admin:
    - user:read,write,delete
    - project:read,write,delete
    - session:read,write,delete
    - agent:read,write,delete
  user:
    - user:read
    - project:read,write
    - session:read,write
    - agent:read
  guest:
    - user:read
    - project:read
```

### 2.3 密码策略

- 最小长度: 8 字符
- 必须包含: 大写字母、小写字母、数字、特殊字符
- 密码哈希: SM3 + Salt
- 密码历史: 记录最近 5 次密码

## 3. 国密加密

### 3.1 SM2 非对称加密

```java
// 密钥对生成
KeyPair keyPair = SmUtil.sm2GenerateKeyPair();

// 加密
byte[] ciphertext = SmUtil.sm2Encrypt(publicKey, plaintext);

// 签名
byte[] signature = SmUtil.sm2Sign(privateKey, data);
```

### 3.2 SM3 哈希算法

```java
// 密码哈希
String hashed = SmUtil.sm3Hex(password + salt);

// 数据完整性校验
String checksum = SmUtil.sm3Hex(data);
```

### 3.3 SM4 对称加密

```java
// 加密
byte[] ciphertext = SmUtil.sm4GcmEncrypt(key, iv, plaintext, aad);

// 解密
byte[] plaintext = SmUtil.sm4GcmDecrypt(key, iv, ciphertext, aad);
```

## 4. 网络安全

### 4.1 TLS 配置

```yaml
# Nginx Ingress TLS 配置
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/ssl-protocols: "TLSv1.2 TLSv1.3"
spec:
  tls:
    - hosts:
        - gewu.com
      secretName: gewu-tls
```

### 4.2 NetworkPolicy

```yaml
# 限制 Pod 间通信
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: gewu-network-policy
spec:
  podSelector:
    matchLabels:
      app: gewu-platform
  policyTypes:
    - Ingress
    - Egress
```

### 4.3 安全头配置

```java
@Component
public class SecurityHeadersFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("X-XSS-Protection", "1; mode=block");
        httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        httpResponse.setHeader("Content-Security-Policy", "default-src 'self'");
        chain.doFilter(request, response);
    }
}
```

## 5. 容器安全

### 5.1 镜像扫描

```bash
# Trivy 扫描
trivy image --severity HIGH,CRITICAL gewu/platform:latest

# Snyk 扫描
snyk container test gewu/platform:latest
```

### 5.2 镜像签名

```bash
# Cosign 签名
cosign sign --key cosign.key gewu/platform:latest

# 验证签名
cosign verify --key cosign.pub gewu/platform:latest
```

### 5.3 运行时安全

```yaml
# Pod 安全策略
apiVersion: policy/v1beta1
kind: PodSecurityPolicy
metadata:
  name: gewu-psp
spec:
  privileged: false
  allowPrivilegeEscalation: false
  requiredDropCapabilities:
    - ALL
  volumes:
    - 'configMap'
    - 'emptyDir'
    - 'projected'
    - 'secret'
    - 'downwardAPI'
    - 'persistentVolumeClaim'
  hostNetwork: false
  hostIPC: false
  hostPID: false
  runAsUser:
    rule: 'MustRunAsNonRoot'
  seLinux:
    rule: 'RunAsAny'
  fsGroup:
    rule: 'RunAsAny'
```

## 6. 数据安全

### 6.1 数据加密

```yaml
# 数据库加密配置
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/gewu?useSSL=true&requireSSL=true
```

### 6.2 密钥管理

```java
// 使用 Kubernetes Secret
String jwtSecret = kubernetesClient.secrets()
    .withName("gewu-secrets")
    .get()
    .getData()
    .get("jwt-secret");
```

### 6.3 数据脱敏

```java
// 日志脱敏
public String maskSensitiveData(String data) {
    return data.replaceAll("(?<=\\d{3})\\d{4}(?=\\d{4})", "****");
}
```

## 7. 安全扫描

### 7.1 SAST（静态应用安全测试）

```bash
# SpotBugs
mvn spotbugs:check

# SonarQube
mvn sonar:sonar
```

### 7.2 DAST（动态应用安全测试）

```bash
# OWASP ZAP
zap-cli quick-scan http://localhost:8080
```

### 7.3 依赖扫描

```bash
# Maven 依赖检查
mvn dependency-check:check

# npm 安全审计
cd gewu-web && npm audit
```

## 8. 安全配置检查清单

### 8.1 应用安全

- [ ] JWT Secret 使用强密码
- [ ] 密码策略已配置
- [ ] 国密加密已启用
- [ ] XSS 过滤已启用
- [ ] CSRF 防护已启用
- [ ] 安全头已配置

### 8.2 网络安全

- [ ] TLS 证书已配置
- [ ] NetworkPolicy 已配置
- [ ] 端口暴露最小化
- [ ] 防火墙规则已配置

### 8.3 容器安全

- [ ] 镜像使用非 root 用户
- [ ] 镜像漏洞扫描通过
- [ ] 镜像签名已配置
- [ ] 资源限制已配置

### 8.4 数据安全

- [ ] 敏感数据加密存储
- [ ] 密钥使用 Secret 管理
- [ ] 日志脱敏已配置
- [ ] 备份加密已配置

## 9. 安全事件响应

### 9.1 事件分类

| 级别 | 说明 | 响应时间 |
|------|------|----------|
| P0 | 数据泄露、服务不可用 | 15 分钟 |
| P1 | 权限提升、恶意攻击 | 1 小时 |
| P2 | 漏洞利用、异常访问 | 4 小时 |
| P3 | 配置错误、日志异常 | 24 小时 |

### 9.2 响应流程

1. 检测: 监控告警、日志分析
2. 達封: 隔离受影响系统
3. 修复: 应用补丁、恢复服务
4. 复盘: 分析原因、更新策略

## 10. 合规要求

### 10.1 等保 2.0 三级

- 安全通信网络: TLS 加密、网络隔离
- 安全区域边界: 防火墙、入侵检测
- 安全计算环境: 身份认证、访问控制
- 安全管理中心: 安全审计、监控告警

### 10.2 数据安全法

- 数据分类分级
- 数据加密存储
- 数据访问审计
- 数据备份恢复
