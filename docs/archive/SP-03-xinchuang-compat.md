# SP-03: 信创环境兼容性验证报告

## 文档信息

| 项目 | 内容 |
|------|------|
| Spike 编号 | SP-03 |
| 验证目标 | iSulad/Firecracker/gVisor 在国产 CPU/OS 上的兼容性 |
| 负责人 | DevOps |
| 计划工期 | 3 天 |
| 关联设计文档 | 27-agent-sandbox-design.md, 29-xinchuang-compliance.md |

---

## 1. 验证目标

| 编号 | 验证项 | 通过标准 | 失败回退 |
|------|--------|---------|---------|
| SP-03-01 | iSulad 在麒麟 V10 上运行 | 容器启动 < 500ms | 降级 Docker |
| SP-03-02 | Firecracker ARM64 启动 | MicroVM 启动 < 5s | 降级 Kata Containers |
| SP-03-03 | gVisor ARM64 兼容 | runsc 正常运行 | 降级 iSulad (L3) |
| SP-03-04 | 国产 JDK 兼容 | 毕昇 JDK 21 运行 Spring Boot 3.2 | 使用 Temurin JDK |
| SP-03-05 | 沙箱三级降级路径 | L1→L2→L3 自动降级 | 固定使用 L3 |

---

## 2. 测试环境矩阵

| CPU | OS | 容器引擎 | 沙箱 | JDK |
|-----|-----|---------|------|-----|
| 鲲鹏 920 (ARM64) | 麒麟 V10 SP1 | iSulad 2.11 | Firecracker + gVisor | 毕昇 JDK 21 |
| 飞腾 S2500 (ARM64) | 麒麟 V10 SP1 | iSulad 2.11 | gVisor | 毕昇 JDK 21 |
| 海光 3号 (x86) | 统信 UOS V20 | iSulad 2.11 | Firecracker + gVisor | 毕昇 JDK 21 |
| 龙芯 3A6000 (LoongArch) | 麒麟 V10 SP1 | iSulad 2.11 | — | 龙芯 JDK 21 |

---

## 3. 执行步骤

### 3.1 iSulad 安装与验证

```bash
# Step 1: 在麒麟 V10 上安装 iSulad
# 参考: https://gitee.com/openeuler/iSulad
sudo yum install -y iSulad

# Step 2: 启动 iSulad 服务
sudo systemctl start isulad
sudo systemctl enable isulad

# Step 3: 验证 iSulad 版本
isula version
# 预期: Version 2.11.x

# Step 4: 运行测试容器
isula run --rm hello-world
# 预期: Hello from iSulad!

# Step 5: 测量容器启动时间
time isula run --rm alpine echo "test"
# 通过标准: < 500ms
# 记录: _____ms

# Step 6: 验证资源限制
isula run --rm --cpu-quota 50000 --memory 256m alpine sh -c "cat /sys/fs/cgroup/memory/memory.limit_in_bytes"
# 预期: 268435456 (256MB)
```

### 3.2 Firecracker ARM64 验证

```bash
# Step 1: 检查 KVM 支持
ls -la /dev/kvm
# 预期: crw-rw-rw- 1 root kvm ...

# Step 2: 下载 Firecracker ARM64 二进制
wget https://github.com/firecracker-microvm/firecracker/releases/download/v1.6.0/firecracker-v1.6.0-aarch64.tgz
tar xzf firecracker-v1.6.0-aarch64.tgz
sudo cp firecracker-v1.6.0-aarch64 /usr/local/bin/firecracker

# Step 3: 下载 ARM64 rootfs
wget https://s3.amazonaws.com/spec.ccftp.minimal/rootfs/rootfs.ext4
# 或使用自定义 rootfs（包含 JDK 21）

# Step 4: 启动 MicroVM
firecracker --api-sock /tmp/firecracker.sock &

# 通过 API 配置并启动
curl --unix-socket /tmp/firecracker.sock -X PUT "http://localhost/boot-source" \
  -H "Accept: application/json" \
  -d '{
    "kernel_image_path": "vmlinux.bin",
    "boot_args": "console=ttyS0 reboot=k panic=1 pci=off"
  }'

curl --unix-socket /tmp/firecracker.sock -X PUT "http://localhost/drives/rootfs" \
  -H "Accept: application/json" \
  -d '{
    "drive_id": "rootfs",
    "path_on_host": "rootfs.ext4",
    "is_root_device": true,
    "is_read_only": false
  }'

curl --unix-socket /tmp/firecracker.sock -X PUT "http://localhost/actions" \
  -H "Accept: application/json" \
  -d '{
    "action_type": "InstanceStart"
  }'

# Step 5: 测量 MicroVM 启动时间
time curl --unix-socket /tmp/firecracker.sock -X PUT "http://localhost/actions" \
  -H "Accept: application/json" \
  -d '{"action_type": "InstanceStart"}'
# 通过标准: < 5s
# 记录: _____s
```

### 3.3 gVisor ARM64 验证

```bash
# Step 1: 下载 gVisor ARM64 二进制
wget https://storage.googleapis.com/gvisor/releases/release/20240101/aarch64/runsc
wget https://storage.googleapis.com/gvisor/releases/release/20240101/aarch64/containerd-shim-runsc-v1
sudo chmod +x runsc containerd-shim-runsc-v1
sudo mv runsc /usr/local/bin/
sudo mv containerd-shim-runsc-v1 /usr/local/bin/

# Step 2: 验证 runsc 版本
runsc --version
# 预期: runsc version 20240101.0

# Step 3: 配置 containerd 使用 runsc
cat <<EOF | sudo tee /etc/containerd/config.toml
version = 2
[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runsc]
  runtime_type = "io.containerd.runsc.v1"
EOF

sudo systemctl restart containerd

# Step 4: 运行 gVisor 容器
isula run --runtime=runsc --rm hello-world
# 预期: Hello from gVisor!

# Step 5: 验证系统调用拦截
isula run --runtime=runsc --rm alpine strace -c ls /
# 预期: 系统调用被 runsc 拦截并模拟
```

### 3.4 国产 JDK 验证

```bash
# Step 1: 安装毕昇 JDK 21（鲲鹏）
# 下载: https://mirror.iscas.ac.cn/kunpeng/archive/compiler/bisheng_jdk/
tar xzf bisheng-jdk-21.0.2_linux-aarch64.tar.gz
export JAVA_HOME=/opt/bisheng-jdk-21.0.2
export PATH=$JAVA_HOME/bin:$PATH

# Step 2: 验证 JDK 版本
java -version
# 预期: openjdk version "21.0.2" 2024-xx-xx

# Step 3: 运行 Spring Boot 3.2 应用
cd /home/wnn/devcode/ai-code/gewu-platform
mvn clean package -DskipTests
java -jar gewu-interface/target/gewu-interface-1.0.0-SNAPSHOT.jar

# Step 4: 验证应用启动
curl http://localhost:8080/actuator/health
# 预期: {"status":"UP"}

# Step 5: 运行单元测试
mvn test -pl gewu-common
# 预期: 所有测试通过
```

### 3.5 沙箱三级降级路径验证

```bash
# 模拟降级场景

# 场景 1: L1 Firecracker 不可用（无 KVM）
# 预期: 自动降级到 L2 gVisor
# 验证: 检查沙箱管理器日志
grep "Falling back to L2" /var/log/gewu/sandbox.log

# 场景 2: L2 gVisor 不可用（runsc 启动失败）
# 预期: 自动降级到 L3 iSulad
# 验证: 检查沙箱管理器日志
grep "Falling back to L3" /var/log/gewu/sandbox.log

# 场景 3: L3 iSulad 正常
# 预期: 使用 iSulad 容器沙箱
# 验证: 检查容器运行状态
isula ps | grep gewu-sandbox
```

---

## 4. 测试结果记录

### 4.1 鲲鹏 920 + 麒麟 V10

| 测试项 | 通过标准 | 实际结果 | 状态 |
|--------|---------|---------|------|
| iSulad 安装 | 成功 | | ⬜ |
| iSulad 容器启动 | < 500ms | | ⬜ |
| iSulad 资源限制 | 生效 | | ⬜ |
| Firecracker KVM | /dev/kvm 可用 | | ⬜ |
| Firecracker MicroVM 启动 | < 5s | | ⬜ |
| gVisor runsc | 正常运行 | | ⬜ |
| gVisor 系统调用拦截 | 生效 | | ⬜ |
| 毕昇 JDK 21 | Spring Boot 启动成功 | | ⬜ |
| L1→L2 降级 | 自动降级 | | ⬜ |
| L2→L3 降级 | 自动降级 | | ⬜ |

### 4.2 飞腾 S2500 + 麒麟 V10

| 测试项 | 通过标准 | 实际结果 | 状态 |
|--------|---------|---------|------|
| iSulad 容器启动 | < 500ms | | ⬜ |
| gVisor 运行 | 正常 | | ⬜ |
| 毕昇 JDK 21 | 应用启动成功 | | ⬜ |

### 4.3 龙芯 3A6000 + 麒麟 V10

| 测试项 | 通过标准 | 实际结果 | 状态 |
|--------|---------|---------|------|
| iSulad 容器启动 | < 500ms | | ⬜ |
| 龙芯 JDK 21 | 应用启动成功 | | ⬜ |
| Firecracker | 不支持（无 KVM） | | ⬜ |
| gVisor | 需交叉编译 | | ⬜ |

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| iSulad 镜像拉取失败 | 中 | 高 | 预拉取到私有仓库 |
| Firecracker ARM64 启动慢 | 中 | 中 | 预热池 + 快照恢复 |
| gVisor LoongArch 不支持 | 高 | 中 | 龙芯仅使用 L3 iSulad |
| 毕昇 JDK 兼容性问题 | 低 | 高 | 使用 Temurin JDK 21 |
| KVM 模块缺失 | 中 | 高 | 升级内核或使用 Kata Containers |

---

## 6. Go/No-Go 决策

| 决策 | 条件 |
|------|------|
| **Go** | 鲲鹏/飞腾上 iSulad + gVisor 全部通过 |
| **Conditional Go** | iSulad 通过，gVisor 部分问题可接受 |
| **No-Go** | iSulad 无法运行，降级为 Docker 开发环境 |

---

**报告填写人**: _______________  **日期**: _______________
