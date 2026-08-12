# SP-02: 国密算法性能基准报告

## 文档信息

| 项目 | 内容 |
|------|------|
| Spike 编号 | SP-02 |
| 验证目标 | Bouncy Castle SM2/SM3/SM4-GCM 在国产 CPU 上的加解密性能 |
| 负责人 | 架构师 |
| 计划工期 | 2 天 |
| 关联设计文档 | 24-unified-security.md |

---

## 1. 验证目标

| 编号 | 验证项 | 通过标准 | 失败回退 |
|------|--------|---------|---------|
| SP-02-01 | SM4-GCM 加密吞吐 | > 100 MB/s | 使用硬件加速卡 |
| SP-02-02 | SM4-GCM vs SM4-CBC 性能对比 | GCM 性能不低于 CBC 的 80% | 回退 CBC（需额外 MAC） |
| SP-02-03 | SM3 哈希吞吐 | > 200 MB/s | 使用原生实现替代 |
| SP-02-04 | SM2 签名速度 | > 1000 次/秒 | 缓存签名结果 |
| SP-02-05 | SM2 验签速度 | > 500 次/秒 | 批量验签 |
| SP-02-06 | 国密 TLS 握手延迟 | < 50ms | 复用 TLS 会话 |

---

## 2. 测试环境

### 2.1 目标环境（信创）

| 资源 | 规格 |
|------|------|
| CPU | 鲲鹏 920 (ARM64) / 飞腾 S2500 (ARM64) |
| OS | 麒麟 V10 SP1 |
| JDK | 毕昇 JDK 21 / 龙芯 JDK 21 |
| Bouncy Castle | 1.77 |

### 2.2 开发环境（x86 替代）

| 资源 | 规格 |
|------|------|
| CPU | Intel i7 / AMD Ryzen 7 |
| OS | Ubuntu 22.04 |
| JDK | Eclipse Temurin 21 |
| Bouncy Castle | 1.77 |

---

## 3. 测试代码

### 3.1 Maven 依赖

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>1.77</version>
</dependency>
```

### 3.2 SM4-GCM 性能测试

```java
package com.gewu.spike;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.security.Security;

public class SM4GCMBenchmark {

    @BeforeAll
    static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void benchmarkSM4GCMEncrypt() throws Exception {
        // 生成 SM4 密钥
        KeyGenerator kg = KeyGenerator.getInstance("SM4", "BC");
        kg.init(128, new SecureRandom());
        SecretKey key = kg.generateKey();

        // 生成 IV (12 bytes for GCM)
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);

        // 测试数据 (1MB)
        byte[] data = new byte[1024 * 1024];
        new SecureRandom().nextBytes(data);

        Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC");

        // 预热
        for (int i = 0; i < 10; i++) {
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            cipher.doFinal(data);
        }

        // 基准测试 (100 次)
        int iterations = 100;
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            cipher.doFinal(data);
        }
        long endTime = System.nanoTime();

        double durationMs = (endTime - startTime) / 1_000_000.0;
        double throughputMBps = (iterations * 1.0) / (durationMs / 1000.0);

        System.out.println("=== SM4-GCM Encrypt Benchmark ===");
        System.out.println("Data size: 1 MB");
        System.out.println("Iterations: " + iterations);
        System.out.println("Total time: " + String.format("%.2f", durationMs) + " ms");
        System.out.println("Throughput: " + String.format("%.2f", throughputMBps) + " MB/s");
        System.out.println("Pass: " + (throughputMBps > 100 ? "YES" : "NO"));
    }

    @Test
    void benchmarkSM4GCMDecrypt() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("SM4", "BC");
        kg.init(128, new SecureRandom());
        SecretKey key = kg.generateKey();

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);

        byte[] data = new byte[1024 * 1024];
        new SecureRandom().nextBytes(data);

        Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        byte[] encrypted = cipher.doFinal(data);

        // 预热
        for (int i = 0; i < 10; i++) {
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            cipher.doFinal(encrypted);
        }

        // 基准测试
        int iterations = 100;
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            cipher.doFinal(encrypted);
        }
        long endTime = System.nanoTime();

        double durationMs = (endTime - startTime) / 1_000_000.0;
        double throughputMBps = (iterations * 1.0) / (durationMs / 1000.0);

        System.out.println("=== SM4-GCM Decrypt Benchmark ===");
        System.out.println("Throughput: " + String.format("%.2f", throughputMBps) + " MB/s");
        System.out.println("Pass: " + (throughputMBps > 100 ? "YES" : "NO"));
    }

    @Test
    void compareSM4GCMvsCBC() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("SM4", "BC");
        kg.init(128, new SecureRandom());
        SecretKey key = kg.generateKey();

        byte[] data = new byte[1024 * 1024]; // 1MB
        new SecureRandom().nextBytes(data);

        // GCM
        byte[] ivGCM = new byte[12];
        new SecureRandom().nextBytes(ivGCM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, ivGCM);
        Cipher cipherGCM = Cipher.getInstance("SM4/GCM/NoPadding", "BC");

        int iterations = 100;
        long startGCM = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cipherGCM.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            cipherGCM.doFinal(data);
        }
        long endGCM = System.nanoTime();
        double gcmMs = (endGCM - startGCM) / 1_000_000.0;

        // CBC
        byte[] ivCBC = new byte[16];
        new SecureRandom().nextBytes(ivCBC);
        javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(ivCBC);
        Cipher cipherCBC = Cipher.getInstance("SM4/CBC/PKCS7Padding", "BC");

        long startCBC = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cipherCBC.init(Cipher.ENCRYPT_MODE, key, ivSpec);
            cipherCBC.doFinal(data);
        }
        long endCBC = System.nanoTime();
        double cbcMs = (endCBC - startCBC) / 1_000_000.0;

        System.out.println("=== SM4 GCM vs CBC Comparison ===");
        System.out.println("GCM: " + String.format("%.2f", gcmMs) + " ms (" + String.format("%.2f", iterations / (gcmMs / 1000.0)) + " MB/s)");
        System.out.println("CBC: " + String.format("%.2f", cbcMs) + " ms (" + String.format("%.2f", iterations / (cbcMs / 1000.0)) + " MB/s)");
        System.out.println("GCM/CBC ratio: " + String.format("%.2f", (double) gcmMs / cbcMs));
        System.out.println("Pass (GCM >= 80% CBC): " + (gcmMs <= cbcMs * 1.25 ? "YES" : "NO"));
    }
}
```

### 3.3 SM3 哈希性能测试

```java
package com.gewu.spike;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;

public class SM3Benchmark {

    @BeforeAll
    static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void benchmarkSM3() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SM3", "BC");

        byte[] data = new byte[1024 * 1024]; // 1MB
        new SecureRandom().nextBytes(data);

        // 预热
        for (int i = 0; i < 10; i++) {
            digest.digest(data);
        }

        // 基准测试
        int iterations = 200;
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            digest.digest(data);
        }
        long endTime = System.nanoTime();

        double durationMs = (endTime - startTime) / 1_000_000.0;
        double throughputMBps = (iterations * 1.0) / (durationMs / 1000.0);

        System.out.println("=== SM3 Hash Benchmark ===");
        System.out.println("Data size: 1 MB");
        System.out.println("Iterations: " + iterations);
        System.out.println("Total time: " + String.format("%.2f", durationMs) + " ms");
        System.out.println("Throughput: " + String.format("%.2f", throughputMBps) + " MB/s");
        System.out.println("Pass: " + (throughputMBps > 200 ? "YES" : "NO"));
    }
}
```

### 3.4 SM2 签名/验签性能测试

```java
package com.gewu.spike;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.security.*;

public class SM2Benchmark {

    @BeforeAll
    static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void benchmarkSM2SignVerify() throws Exception {
        // 生成 SM2 密钥对
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(256); // SM2 uses 256-bit curve
        KeyPair keyPair = kpg.generateKeyPair();

        Signature signer = Signature.getInstance("SM3withSM2", "BC");
        Signature verifier = Signature.getInstance("SM3withSM2", "BC");

        byte[] data = "Hello, 格物平台!".getBytes();

        // 签名性能
        int iterations = 1000;
        long startSign = System.nanoTime();
        byte[] signature = null;
        for (int i = 0; i < iterations; i++) {
            signer.initSign(keyPair.getPrivate());
            signer.update(data);
            signature = signer.sign();
        }
        long endSign = System.nanoTime();
        double signMs = (endSign - startSign) / 1_000_000.0;
        double signPerSec = iterations / (signMs / 1000.0);

        // 验签性能
        long startVerify = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            verifier.initVerify(keyPair.getPublic());
            verifier.update(data);
            verifier.verify(signature);
        }
        long endVerify = System.nanoTime();
        double verifyMs = (endVerify - startVerify) / 1_000_000.0;
        double verifyPerSec = iterations / (verifyMs / 1000.0);

        System.out.println("=== SM2 Sign/Verify Benchmark ===");
        System.out.println("Sign: " + String.format("%.2f", signMs) + " ms for " + iterations + " ops");
        System.out.println("Sign throughput: " + String.format("%.0f", signPerSec) + " ops/sec");
        System.out.println("Pass (Sign > 1000/s): " + (signPerSec > 1000 ? "YES" : "NO"));
        System.out.println();
        System.out.println("Verify: " + String.format("%.2f", verifyMs) + " ms for " + iterations + " ops");
        System.out.println("Verify throughput: " + String.format("%.0f", verifyPerSec) + " ops/sec");
        System.out.println("Pass (Verify > 500/s): " + (verifyPerSec > 500 ? "YES" : "NO"));
    }
}
```

### 3.5 国密 TLS 握手延迟测试

```java
package com.gewu.spike;

import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.net.ssl.*;
import java.security.Security;

public class GMTLSBenchmark {

    @BeforeAll
    static void setup() {
        Security.addProvider(new BouncyCastleProvider());
        Security.addProvider(new BouncyCastleJsseProvider());
    }

    @Test
    void benchmarkGMTLSHandshake() throws Exception {
        // 注意: 需要配置 SM2 证书
        // 此处为框架代码，实际执行需要：
        // 1. 生成 SM2 证书（使用 Bouncy Castle）
        // 2. 配置 SSLContext 使用国密 TLS
        // 3. 启动本地 HTTPS 服务器
        // 4. 测量握手延迟

        System.out.println("=== 国密 TLS 握手延迟测试 ===");
        System.out.println("前置条件: SM2 证书已生成，国密 TLS 服务器已启动");
        System.out.println("测试方法: 使用 SSLContext 建立 100 次连接，测量握手时间");
        System.out.println("通过标准: P95 < 50ms");
        System.out.println();
        System.out.println("参考实现:");
        System.out.println("  SSLContext ctx = SSLContext.getInstance(\"TLS\", \"BCJSSE\");");
        System.out.println("  ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());");
        System.out.println("  SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);");
        System.out.println("  socket.startHandshake();");
    }
}
```

---

## 4. 执行步骤

```bash
# Step 1: 创建测试项目
cd /home/wnn/devcode/ai-code/gewu-platform
mkdir -p gewu-common/src/test/java/com/gewu/spike

# Step 2: 添加 Bouncy Castle 依赖到 gewu-common/pom.xml
# (见 3.1 Maven 依赖)

# Step 3: 复制测试代码到 gewu-common/src/test/java/com/gewu/spike/

# Step 4: 在 x86 开发环境执行
mvn test -pl gewu-common -Dtest=SM4GCMBenchmark
mvn test -pl gewu-common -Dtest=SM3Benchmark
mvn test -pl gewu-common -Dtest=SM2Benchmark

# Step 5: 在信创环境（鲲鹏 920 + 麒麟 V10）执行
# 将编译好的 jar 包传输到信创机器
scp gewu-common/target/gewu-common-1.0.0-SNAPSHOT.jar kunpeng-host:/tmp/
ssh kunpeng-host "java -jar /tmp/gewu-common-1.0.0-SNAPSHOT.jar --test"

# Step 6: 记录结果到测试结果表
```

---

## 5. 测试结果记录

### 5.1 x86 开发环境

| 测试项 | 通过标准 | 实际结果 | 状态 |
|--------|---------|---------|------|
| SM4-GCM 加密 | > 100 MB/s | | ⬜ |
| SM4-GCM 解密 | > 100 MB/s | | ⬜ |
| SM4-GCM vs CBC | GCM >= 80% CBC | | ⬜ |
| SM3 哈希 | > 200 MB/s | | ⬜ |
| SM2 签名 | > 1000 ops/s | | ⬜ |
| SM2 验签 | > 500 ops/s | | ⬜ |
| 国密 TLS 握手 | < 50ms | | ⬜ |

### 5.2 信创环境（鲲鹏 920）

| 测试项 | 通过标准 | 实际结果 | 状态 |
|--------|---------|---------|------|
| SM4-GCM 加密 | > 100 MB/s | | ⬜ |
| SM4-GCM 解密 | > 100 MB/s | | ⬜ |
| SM3 哈希 | > 200 MB/s | | ⬜ |
| SM2 签名 | > 1000 ops/s | | ⬜ |
| SM2 验签 | > 500 ops/s | | ⬜ |

---

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| Bouncy Castle 在 ARM64 性能低 | 中 | 高 | 使用硬件加速（鲲鹏 920 内置加密引擎） |
| SM4-GCM 性能低于 CBC | 低 | 中 | 回退 CBC + HMAC-SM3（需额外代码） |
| 国密 TLS 握手延迟高 | 中 | 中 | 复用 TLS 会话；使用 TLS 1.3 |
| 毕昇 JDK 兼容性问题 | 低 | 高 | 使用 Temurin JDK 21 替代 |

---

## 7. Go/No-Go 决策

| 决策 | 条件 |
|------|------|
| **Go** | SM4-GCM > 100 MB/s，SM2 签名 > 1000/s |
| **Conditional Go** | SM4-GCM 50-100 MB/s，可接受但需优化 |
| **No-Go** | SM4-GCM < 50 MB/s，回退 SM4-CBC + HMAC |

---

**报告填写人**: _______________  **日期**: _______________
