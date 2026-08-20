# 开发工作空间技术设计

> **文档编号**：TDD-gewu-dev-workspace-V1.0
> **版本**：V1.0
> **编制日期**：2026-08-03
> **状态**：草稿
> **关联文档**：`39-user-workspace-design.md`（通用工作空间）、`27-agent-sandbox-design.md`（沙箱设计）

---

## 1. 背景与问题分析

### 1.1 现有工作空间的局限

`39-user-workspace-design.md` 实现了基于 MinIO 对象存储的通用工作空间，适用于文档管理、文件上传/下载等非开发场景。但对于开发人员（BACKEND_DEV/FRONTEND_DEV/TESTER），存在根本性缺陷：

| 开发需求 | MinIO 方案 | 问题 |
|----------|-----------|------|
| git clone 到工作空间 | ❌ | Git 需要真实文件系统（.git 目录、文件锁、inotify） |
| 编译（javac/maven/npm） | ❌ | 编译器需要文件系统读写 |
| 运行程序（java/node/python） | ❌ | 需要 shell 环境和进程管理 |
| 文件监听（npm run dev） | ❌ | 需要内核文件系统事件 |
| Git 提交推送 | ❌ | 需要文件系统 + 网络 + 凭证 |
| 环境变量配置 | ❌ | 无 .env 文件概念 |

### 1.2 沙箱现有能力与差距

| 能力 | 现状 | 差距 |
|------|------|------|
| Docker Volume 挂载 | ✅ workspaceId -> `/workspace` rw | 可用作工作存储 |
| 命令执行 (exec) | ✅ `/{id}/exec` API | CommandValidator 禁止 `|` `&&` `>` `>>` `$` |
| 镜像含 git | ❌ 4 种模板均不含 git | 需构建开发专用镜像 |
| 网络访问 | ❌ 默认 `network-enabled: false` | git clone/pull/push 需要网络 |
| 文件上传到 Volume | ❌ 仅 MinIO 上传 | 需 Docker cp API 直传容器 |
| 文件从 Volume 下载 | ❌ 仅 MinIO 预签名 URL | 需 Docker cp API 直取容器 |
| Git 凭证管理 | ❌ 无 | 需安全存储 SSH key / Token |
| 退出码 | ❌ 硬编码 0 | 无法判断命令成败 |
| 沙箱清理 | ⚠️ project 永不过期 | 开发沙箱需长生命周期 + 可控续期 |

---

## 2. 总体架构

### 2.1 双轨存储模型

```
┌─────────────────────────────────────────────────────────────┐
│                    用户工作空间存储                          │
│                                                             │
│  ┌─────────────────────┐    ┌──────────────────────────┐   │
│  │  MinIO (对象存储)    │    │  Docker Volume (文件系统) │   │
│  │  mode=storage        │    │  mode=dev                │   │
│  │                     │    │                          │   │
│  │  • 文档上传/下载     │    │  • git clone/pull/push   │   │
│  │  • 非 DEV 角色       │    │  • 源码 / 编译产物       │   │
│  │  • workspaces/{uid}/│    │  • .env / .git/          │   │
│  └─────────────────────┘    │  • 挂载: /workspace       │   │
│                             │  • 卷名: gewu-ws-{uid}   │   │
│                             └──────────────────────────┘   │
│                                    ↕                       │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  DevWorkspaceService (mode=dev)                      │  │
│  │  • Docker cp API 文件读写 (绕过 CommandValidator)     │  │
│  │  • exec 执行 git/build/run (DevCommandValidator)     │  │
│  │  • Git 凭证注入 (SM4 加解密)                         │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 开发沙箱容器

```
┌──────────────────────────────────────────────────────────┐
│            开发沙箱 (gewu/dev-base:latest)               │
│            source=dev, network=bridge                    │
│                                                          │
│  ┌─ /workspace (Docker Volume, rw) ─────────────────┐   │
│  │  projects/                                        │   │
│  │  ├── my-service/  (.git/ src/ pom.xml .env)     │   │
│  │  └── frontend/    (.git/ src/ package.json)     │   │
│  │  .ssh/  (id_rsa, known_hosts)                    │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  预装: git, openssh, jdk-21, maven, node-20, python-3   │
│  网络: bridge (git clone/push -> github/gitea/...)      │
│  生命周期: idle 2h 停止, TTL 30天, Volume 持久化         │
└──────────────────────────────────────────────────────────┘
```

---

## 3. 数据库设计（V20 迁移）

### 3.1 workspace 表扩展

```sql
ALTER TABLE workspace ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'storage'
    COMMENT '存储模式: storage=MinIO, dev=Docker卷';
ALTER TABLE workspace ADD COLUMN dev_sandbox_id VARCHAR(26) DEFAULT NULL
    COMMENT '开发沙箱ID';
```

### 3.2 workspace_project 表

```sql
CREATE TABLE IF NOT EXISTS workspace_project (
    id VARCHAR(26) NOT NULL,
    workspace_id VARCHAR(26) NOT NULL,
    project_name VARCHAR(128) NOT NULL,
    repo_url VARCHAR(512) NOT NULL COMMENT 'Git仓库地址',
    repo_branch VARCHAR(128) DEFAULT 'main',
    local_path VARCHAR(256) NOT NULL COMMENT '容器内相对路径',
    clone_status VARCHAR(32) DEFAULT 'pending' COMMENT 'pending/cloning/ready/failed',
    last_sync_at BIGINT DEFAULT NULL,
    head_commit VARCHAR(64) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_wp_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间Git项目';
```

### 3.3 git_credential 表

```sql
CREATE TABLE IF NOT EXISTS git_credential (
    id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    cred_name VARCHAR(64) NOT NULL,
    cred_type VARCHAR(16) NOT NULL COMMENT 'ssh_key / token',
    cred_value TEXT NOT NULL COMMENT 'SM4加密后的凭证',
    ssh_public_key TEXT DEFAULT NULL,
    git_host VARCHAR(256) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_git_cred_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Git凭证';
```

---

## 4. 开发镜像

### 4.1 Dockerfile.dev

```dockerfile
FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
    git openssh-client vim curl wget unzip tar \
    maven gradle python3 python3-pip nodejs npm \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime
WORKDIR /workspace
RUN mkdir -p /root/.ssh && chmod 700 /root/.ssh
RUN git config --global user.email "dev@gewu.local" \
    && git config --global user.name "Gewu Dev" \
    && git config --global init.defaultBranch main \
    && git config --global safe.directory '*'
CMD ["sleep", "infinity"]
```

### 4.2 application.yml 配置

```yaml
gewu:
  sandbox:
    dev:
      image: gewu/dev-base:latest
      cpu: 2
      memory-mb: 4096
      disk-mb: 20480
      network-enabled: true
      idle-timeout-minutes: 120
      manual-ttl-days: 30
```

---

## 5. 核心服务设计

### 5.1 DevWorkspaceService

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class DevWorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceProjectMapper projectMapper;
    private final GitCredentialMapper credMapper;
    private final SandboxService sandboxService;
    private final DockerClient dockerClient;
    private final ApiKeyCryptoService cryptoService;

    // === 沙箱管理 ===
    SandboxDTO startDevSandbox();       // 创建/恢复开发沙箱
    void stopDevSandbox();              // 停止（保留 Volume）
    SandboxDTO getDevSandboxStatus();   // 状态查询

    // === 文件操作（Docker cp） ===
    List<FileNodeDTO> listFiles(path);  // exec ls
    String readFile(filePath);          // exec cat
    void writeFile(filePath, content);  // Docker cp tar 写入
    void uploadFile(path, MultipartFile); // Docker cp tar 上传
    byte[] downloadFile(path);          // Docker cp tar 读取
    void deleteFile(path);              // exec rm

    // === Git ===
    WorkspaceProject cloneRepo(url, branch, name);
    WorkspaceProject gitPull(projectId);
    String gitCommitPush(projectId, message);
    List<WorkspaceProject> listProjects();

    // === 构建/运行 ===
    ExecCommandResponse build(projectId, cmd);
    ExecCommandResponse run(projectId, cmd);
    ExecCommandResponse execCommand(cmd);  // 通用 exec

    // === Git 凭证 ===
    GitCredential addGitCredential(name, type, value, host);
    List<GitCredential> listGitCredentials();
    void deleteGitCredential(id);
    void injectGitCredentials(userId, sandbox); // 注入到容器
}
```

### 5.2 Docker cp 文件传输

```java
/** 写文件到容器（tar 流，绕过 CommandValidator） */
private void writeFileToContainer(String containerId, String remotePath, String content) {
    String fileName = Paths.get(remotePath).getFileName().toString();
    String parentDir = Paths.get(remotePath).getParent() != null
        ? Paths.get(remotePath).getParent().toString() : "/";
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(fileName);
        entry.setSize(data.length);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
        tar.finish();
        dockerClient.copyArchiveToContainerCmd(containerId)
            .withTarInputStream(new ByteArrayInputStream(baos.toByteArray()))
            .withRemotePath("/workspace/" + parentDir)
            .exec();
    }
}

/** 从容器读取文件（tar 流解包） */
private byte[] readFileFromContainer(String containerId, String remotePath) {
    try (InputStream is = dockerClient
            .copyArchiveFromContainerCmd(containerId, "/workspace/" + remotePath)
            .exec();
         TarArchiveInputStream tar = new TarArchiveInputStream(is)) {
        TarArchiveEntry entry = tar.getNextTarEntry();
        return entry != null ? tar.readAllBytes() : new byte[0];
    }
}
```

### 5.3 DevCommandValidator

```java
@Component
public class DevCommandValidator {
    private static final Set<String> BLOCKED = Set.of(
        "rm -rf /", "mkfs", "dd if=", "shutdown", "reboot",
        "halt", "poweroff", "init 0", "init 6"
    );
    public void validate(String command) {
        String lower = command.toLowerCase();
        for (String b : BLOCKED) {
            if (lower.contains(b))
                throw BusinessException.of(ResultCode.PARAM_INVALID, "禁止: " + b);
        }
    }
}
```

SandboxService.execCommand 按 `sandbox.source` 路由：
- `source=dev` -> DevCommandValidator（宽松：允许管道/重定向/变量）
- 其他 -> CommandValidator（严格）

### 5.4 exec exitCode 修复

DockerSandboxProvider.exec() 当前硬编码 `exitCode(0)`，修复为从 docker exec 获取真实退出码。

---

## 6. API 清单

| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 1 | POST | `/api/v1/dev-workspaces/sandbox/start` | 启动开发沙箱 |
| 2 | POST | `/api/v1/dev-workspaces/sandbox/stop` | 停止沙箱 |
| 3 | GET | `/api/v1/dev-workspaces/sandbox` | 沙箱状态 |
| 4 | GET | `/api/v1/dev-workspaces/files?path=` | 列出目录 |
| 5 | GET | `/api/v1/dev-workspaces/files/content?path=` | 读取文件 |
| 6 | PUT | `/api/v1/dev-workspaces/files/content` | 写入文件 |
| 7 | POST | `/api/v1/dev-workspaces/files/upload` | 上传文件 |
| 8 | GET | `/api/v1/dev-workspaces/files/download?path=` | 下载文件 |
| 9 | DELETE | `/api/v1/dev-workspaces/files?path=` | 删除文件 |
| 10 | POST | `/api/v1/dev-workspaces/exec` | 执行命令 |
| 11 | POST | `/api/v1/dev-workspaces/projects/clone` | 克隆仓库 |
| 12 | GET | `/api/v1/dev-workspaces/projects` | 项目列表 |
| 13 | POST | `/api/v1/dev-workspaces/projects/{id}/pull` | Git pull |
| 14 | POST | `/api/v1/dev-workspaces/projects/{id}/push` | Git commit+push |
| 15 | POST | `/api/v1/dev-workspaces/projects/{id}/build` | 构建 |
| 16 | POST | `/api/v1/dev-workspaces/projects/{id}/run` | 运行 |
| 17 | POST | `/api/v1/dev-workspaces/git-credentials` | 添加凭证 |
| 18 | GET | `/api/v1/dev-workspaces/git-credentials` | 凭证列表 |
| 19 | DELETE | `/api/v1/dev-workspaces/git-credentials/{id}` | 删除凭证 |

---

## 7. 安全设计

| 层级 | 机制 |
|------|------|
| 命令分级 | dev 沙箱用 DevCommandValidator，Agent 沙箱不变 |
| 文件传输 | Docker cp API（不经 shell，无注入风险） |
| Git 凭证 | SM4 加密存储，注入后仅在容器内存活 |
| 路径校验 | 禁止 `..` 和绝对路径 |
| 网络 | bridge 模式，可限制仅 22/443/80 出站（可选） |
| 审计 | 所有 exec 记录 SandboxAuditLog |

---

## 8. ADR

### ADR-003: 开发工作空间使用 Docker Volume 而非 MinIO

**决策**：双轨存储。`mode=storage` -> MinIO（非开发角色），`mode=dev` -> Docker Volume（DEV/TEST 角色）。

**理由**：Docker Volume 是真实文件系统，支持 git/编译/文件监听；MinIO 适合文档管理。两者面向不同用户群体。

### ADR-004: 文件传输使用 Docker cp 而非 exec 重定向

**决策**：文件读写使用 Docker Java API（copyArchiveToContainerCmd / copyArchiveFromContainerCmd），通过 tar 流传输。

**理由**：Docker cp 是 API 级操作，不经过 shell，无注入风险；保持 Agent 沙箱严格校验不变。
