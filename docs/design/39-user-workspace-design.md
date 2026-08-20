# 用户工作空间技术设计

> **文档编号**：TDD-gewu-workspace-V1.0  
> **版本**：V1.0  
> **编制日期**：2026-08-03  
> **状态**：草稿

---

## 1. 引言

### 1.1 目的

为每个用户分配独立工作空间，实现用户级文件存储与源码管理。开发（BACKEND_DEV/FRONTEND_DEV）和测试（TESTER）角色获得持久化沙箱环境，在独立空间中存储源码和相关文件。

### 1.2 现状分析（基于 codebase-memory 源码分析）

| 维度 | 现状 | 差距 |
|------|------|------|
| **对象存储** | MinIO 单 bucket `gewu-documents`，路径 `{projectId}/{phaseCode}/{uuid}_{filename}`，仅用于项目阶段文档 | ❌ 无用户级存储隔离，无文件树元数据管理 |
| **沙箱** | `DockerSandboxProvider` 创建容器，`readonly-rootfs` + `tmpfs /tmp`（64MB），无持久化卷挂载 | ❌ 容器销毁后代码全部丢失，无跨会话持久化 |
| **项目** | `Project` 实体有 `worktree`/`sandboxes`/`commands` 字段，但 `worktree` 未使用 | ❌ 无工作树初始化逻辑 |
| **RBAC** | 9 角色含 BACKEND_DEV/FRONTEND_DEV/TESTER，`@DataPermission` 数据权限拦截器已就位 | ✅ 角色体系完备，可直接复用 |
| **基础设施** | MySQL + MinIO + DragonflyDB + Docker，沙箱容器直接跑在宿主机 Docker daemon | ✅ 无需新增基础设施组件 |

### 1.3 关联文档

| 文档 | 说明 |
|------|------|
| `docs/design/01-technical-architecture.md` | 整体技术架构 |
| `docs/design/27-agent-sandbox-design.md` | 沙箱设计 |
| `docs/design/37-five-dimension-rbac-design.md` | 五维 RBAC 权限设计 |
| `docs/design/22-unified-db-schema.md` | 统一数据库设计 |

---

## 2. 需求摘要

### 2.1 功能范围

| 编号 | 功能 | 优先级 | 说明 |
|------|------|--------|------|
| F-001 | 用户工作空间自动创建 | P0 | 用户注册/首次访问时自动初始化 |
| F-002 | 文件树管理 | P0 | 目录创建/删除/重命名/列表 |
| F-003 | 文件上传/下载 | P0 | MultipartFile 上传 + 预签名 URL 下载 |
| F-004 | 源码读写 | P0 | 文本内容读取/保存（代码编辑器） |
| F-005 | 沙箱持久化卷挂载 | P0 | 用户工作空间挂载到沙箱 `/workspace` |
| F-006 | 角色差异化能力 | P1 | DEV/TEST 角色获得增强功能 |
| F-007 | 存储配额管理 | P1 | 按角色分配存储上限 |
| F-008 | 文件搜索 | P2 | 按名称/内容搜索工作空间文件 |

### 2.2 非功能约束

| 类别 | 约束 |
|------|------|
| 性能 | 文件列表 <200ms，文件上传 <3s（10MB 内），沙箱卷挂载 <1s |
| 安全 | 用户间工作空间完全隔离，文件路径防穿越，@DataPermission 自动过滤 |
| 可用性 | MinIO 不可用时降级为只读模式，不阻塞登录 |
| 存储 | 默认配额 1GB/用户，DEV/TEST 角色 5GB，ADMIN 20GB |

---

## 3. 总体设计

### 3.1 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户工作空间                              │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              WorkspaceController (接口层)                │   │
│  │  GET /workspaces/me          POST /workspaces/files     │   │
│  │  GET /workspaces/files/tree  PUT /workspaces/files/{id} │   │
│  │  DELETE /workspaces/files/{id}                          │   │
│  └──────────────────────────┬──────────────────────────────┘   │
│                             │                                   │
│  ┌──────────────────────────▼──────────────────────────────┐   │
│  │              WorkspaceService (应用层)                    │   │
│  │  ┌──────────┐  ┌───────────┐  ┌──────────────────┐     │   │
│  │  │自动初始化 │  │文件树管理  │  │配额检查/统计      │     │   │
│  │  └──────────┘  └───────────┘  └──────────────────┘     │   │
│  └──────────┬───────────────────────┬──────────────────────┘   │
│             │                       │                           │
│  ┌──────────▼──────────┐  ┌────────▼─────────────────────┐     │
│  │  MinioStorageService │  │  SandboxService (扩展)        │     │
│  │  (复用，新增路径前缀) │  │  workspaceId → Docker Volume │     │
│  │  workspaces/{userId}/│  │  mount → /workspace           │     │
│  └─────────────────────┘  └──────────────────────────────┘     │
│             │                       │                           │
│  ┌──────────▼───────────────────────▼──────────────────────┐   │
│  │                    基础设施层                             │   │
│  │  ┌────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │   │
│  │  │ MySQL  │  │  MinIO   │  │ Dragonfly│  │  Docker  │  │   │
│  │  │(元数据) │  │(文件内容)│  │(配额缓存) │  │ (卷+容器) │  │   │
│  │  └────────┘  └──────────┘  └──────────┘  └──────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 技术选型

| 层级 | 技术 | 现有/新增 | 用途 |
|------|------|-----------|------|
| 对象存储 | MinIO | 现有 | 文件内容存储（复用 `MinioStorageService`） |
| 元数据 | MySQL/OceanBase | 现有 | 文件树元数据、配额记录 |
| 缓存 | DragonflyDB | 现有 | 配额使用量缓存、文件树缓存 |
| 沙箱 | Docker | 现有 | 持久化卷挂载到容器 |
| Docker Volume | local driver | 新增 | 用户工作空间持久化目录 |

### 3.3 DDD 包结构（遵循现有分层）

```
gewu-domain/src/main/java/com/gewu/domain/workspace/
├── Workspace.java              # 工作空间实体
└── WorkspaceFile.java          # 文件元数据实体

gewu-infrastructure/src/main/java/com/gewu/infrastructure/
├── mapper/
│   ├── WorkspaceMapper.java    # MyBatis-Plus Mapper
│   └── WorkspaceFileMapper.java
└── storage/
    └── MinioStorageService.java  # 扩展：新增 workspace 路径前缀方法

gewu-application/src/main/java/com/gewu/application/workspace/
├── WorkspaceService.java       # 工作空间服务
├── dto/
│   ├── WorkspaceDTO.java
│   ├── FileNodeDTO.java        # 文件树节点
│   ├── CreateDirCommand.java
│   ├── UpdateFileCommand.java
│   └── UploadFileCommand.java

gewu-interface/src/main/java/com/gewu/interfaceapi/controller/
└── WorkspaceController.java    # REST 接口

gewu-sandbox/src/main/java/com/gewu/sandbox/
├── dto/CreateSandboxCommand.java  # 扩展：新增 workspaceId 字段
└── provider/DockerSandboxProvider.java  # 扩展：卷挂载逻辑
```

---

## 4. 数据库设计

### 4.1 `workspace` 表（V19 迁移）

```sql
CREATE TABLE IF NOT EXISTS workspace (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    user_id VARCHAR(26) NOT NULL COMMENT '所属用户ID',
    workspace_name VARCHAR(128) NOT NULL DEFAULT '我的工作空间' COMMENT '工作空间名称',
    storage_path VARCHAR(256) NOT NULL COMMENT 'MinIO 存储路径前缀',
    quota_bytes BIGINT NOT NULL DEFAULT 1073741824 COMMENT '配额上限(字节)',
    used_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '已用空间(字节)',
    file_count INT NOT NULL DEFAULT 0 COMMENT '文件总数',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=正常 2=冻结',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_user (user_id),
    KEY idx_workspace_path (storage_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户工作空间';
```

**设计要点**：
- `user_id` 唯一约束：每用户仅一个工作空间
- `storage_path`：如 `workspaces/01ARZ3NDEKTSV4RRFFQ69G5FU0/`，与 MinIO 对象前缀对应
- `quota_bytes`：按角色差异化分配（注册时根据角色设定）
- `used_bytes`/`file_count`：实时统计，写入时更新

### 4.2 `workspace_file` 表

```sql
CREATE TABLE IF NOT EXISTS workspace_file (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键',
    workspace_id VARCHAR(26) NOT NULL COMMENT '工作空间ID',
    parent_id VARCHAR(26) DEFAULT NULL COMMENT '父目录ID(NULL=根目录)',
    file_name VARCHAR(255) NOT NULL COMMENT '文件/目录名',
    file_type TINYINT NOT NULL COMMENT '1=目录 2=文件',
    file_path VARCHAR(1024) NOT NULL COMMENT '完整相对路径(如 src/main/App.java)',
    object_key VARCHAR(512) DEFAULT NULL COMMENT 'MinIO对象Key(文件类型才有)',
    mime_type VARCHAR(128) DEFAULT NULL COMMENT 'MIME类型',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    checksum VARCHAR(64) DEFAULT NULL COMMENT 'SHA-256校验和',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=正常 2=回收站',
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_file_workspace (workspace_id),
    KEY idx_file_parent (parent_id),
    KEY idx_file_path (workspace_id, file_path(768))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间文件元数据';
```

**设计要点**：
- `parent_id` 自引用实现树形结构，NULL 表示根目录
- `file_path` 存储完整相对路径（冗余但便于查询/防穿越校验）
- `object_key` 仅文件类型有值，目录类型为 NULL
- `version` 每次内容更新 +1，支持简单版本追溯
- 软删除 + 回收站双机制：`deleted` 为 MyBatis-Plus 逻辑删除，`status=2` 为回收站

### 4.3 MinIO 存储布局

```
gewu-documents/                           (现有 bucket)
├── {projectId}/{phaseCode}/...           (现有：项目阶段文档)
└── workspaces/                           (新增：用户工作空间)
    └── {userId}/                         (用户级隔离)
        ├── src/                          (源码目录)
        │   ├── main/
        │   └── test/
        ├── docs/                         (文档目录)
        ├── uploads/                      (上传文件)
        └── .workspace.json               (工作空间元信息)
```

**对象 Key 规则**：`workspaces/{userId}/{relativePath}`
- 例如：`workspaces/01ARZ...FU0/src/main/App.java`

### 4.4 Docker Volume 布局

```
宿主机: /var/lib/docker/volumes/
└── gewu-ws-{userId前12位小写}/     (Docker named volume)
    └── _data/
        ├── src/
        ├── docs/
        └── uploads/
```

**卷生命周期**：
- 首次创建工作空间时 `docker volume create gewu-ws-{userId}`
- 沙箱启动时 `--mount source=gewu-ws-{userId},target=/workspace`
- 沙箱销毁时卷保留（不删除）
- 用户注销时 `docker volume rm`（可选）

---

## 5. 接口设计

### 5.1 接口清单

| 编号 | 接口 | 方法 | 路径 | 权限 |
|------|------|------|------|------|
| API-001 | 获取当前用户工作空间 | GET | `/api/v1/workspaces/me` | 已认证 |
| API-002 | 文件树列表 | GET | `/api/v1/workspaces/files?parentId={id}` | 已认证 |
| API-003 | 创建目录 | POST | `/api/v1/workspaces/files/dirs` | 已认证 |
| API-004 | 上传文件 | POST | `/api/v1/workspaces/files/upload` | 已认证 |
| API-005 | 读取文件内容 | GET | `/api/v1/workspaces/files/{fileId}/content` | 已认证 |
| API-006 | 保存文件内容 | PUT | `/api/v1/workspaces/files/{fileId}/content` | 已认证 |
| API-007 | 重命名/移动文件 | PUT | `/api/v1/workspaces/files/{fileId}` | 已认证 |
| API-008 | 删除文件 | DELETE | `/api/v1/workspaces/files/{fileId}` | 已认证 |
| API-009 | 下载文件 | GET | `/api/v1/workspaces/files/{fileId}/download` | 已认证 |
| API-010 | 创建工作空间沙箱 | POST | `/api/v1/workspaces/sandboxes` | 已认证 |

### 5.2 关键接口详细设计

#### API-002: 文件树列表

```
GET /api/v1/workspaces/files?parentId={parentId}

响应:
{
  "code": 10000,
  "data": [
    {
      "fileId": "01ARZ...",
      "fileName": "src",
      "fileType": 1,           // 1=目录
      "filePath": "src",
      "fileSize": 0,
      "childrenCount": 3,
      "createdAt": 1722652800000,
      "updatedAt": 1722652800000
    },
    {
      "fileId": "01ARZ...",
      "fileName": "README.md",
      "fileType": 2,           // 2=文件
      "filePath": "README.md",
      "fileSize": 1024,
      "mimeType": "text/markdown",
      "version": 3,
      "createdAt": 1722652800000,
      "updatedAt": 1722740000000
    }
  ]
}
```

#### API-005/006: 源码读写（代码编辑器）

```
GET /api/v1/workspaces/files/{fileId}/content
响应: text/plain (文件原始内容)

PUT /api/v1/workspaces/files/{fileId}/content
请求: text/plain (新内容)
响应:
{
  "code": 10000,
  "data": {
    "fileId": "01ARZ...",
    "version": 4,
    "fileSize": 2048,
    "checksum": "a1b2c3..."
  }
}
```

#### API-010: 创建工作空间沙箱

```
POST /api/v1/workspaces/sandboxes
请求:
{
  "template": "java",        // python/node/java/shell
  "sandboxName": "我的开发环境"
}

响应:
{
  "code": 10000,
  "data": {
    "sandboxId": "01ARZ...",
    "sandboxName": "我的开发环境",
    "status": "running",
    "mountPath": "/workspace",
    "image": "openjdk:21-slim"
  }
}
```

---

## 6. 核心类设计

### 6.1 Workspace 实体

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workspace")
public class Workspace extends BaseEntity {
    private String userId;
    private String workspaceName;
    private String storagePath;
    private Long quotaBytes;
    private Long usedBytes;
    private Integer fileCount;
    private Integer status;
}
```

### 6.2 WorkspaceFile 实体

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workspace_file")
public class WorkspaceFile extends BaseEntity {
    private String workspaceId;
    private String parentId;
    private String fileName;
    private Integer fileType;      // 1=目录 2=文件
    private String filePath;
    private String objectKey;
    private String mimeType;
    private Long fileSize;
    private String checksum;
    private Integer version;
    private Integer status;        // 1=正常 2=回收站
}
```

### 6.3 WorkspaceService 核心方法

```java
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceFileMapper fileMapper;
    private final MinioStorageService storageService;
    private final UserAccountMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    /**
     * 获取或自动创建当前用户的工作空间。
     * 首次访问时延迟初始化，按角色分配配额。
     */
    @DataPermission(orgField = "user_id")
    public WorkspaceDTO getOrCreateMyWorkspace() {
        String userId = UserContext.currentUserId();
        Workspace ws = workspaceMapper.selectOne(
            new LambdaQueryWrapper<Workspace>().eq(Workspace::getUserId, userId));
        if (ws == null) {
            ws = initWorkspace(userId);
        }
        return toDTO(ws);
    }

    /**
     * 初始化工作空间：创建 DB 记录 + MinIO 目录 + Docker Volume + 默认目录结构。
     */
    @Transactional
    public Workspace initWorkspace(String userId) {
        // 1. 按角色确定配额
        long quota = determineQuotaByRoles(userId);

        // 2. 创建 DB 记录
        Workspace ws = new Workspace();
        ws.setUserId(userId);
        ws.setWorkspaceName("我的工作空间");
        ws.setStoragePath("workspaces/" + userId + "/");
        ws.setQuotaBytes(quota);
        ws.setUsedBytes(0L);
        ws.setFileCount(0);
        ws.setStatus(1);
        workspaceMapper.insert(ws);

        // 3. 创建默认目录树 (src/, docs/, uploads/)
        createDefaultDirectories(ws.getId());

        // 4. 创建 Docker Volume (异步，失败不阻塞)
        createWorkspaceVolume(userId);

        return ws;
    }

    /**
     * 按角色确定存储配额。
     */
    private long determineQuotaByRoles(String userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
            new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) return 1L * 1024 * 1024 * 1024; // 1GB

        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        List<String> roleCodes = roles.stream().map(Role::getRoleCode).toList();

        if (roleCodes.contains("ADMIN")) return 20L * 1024 * 1024 * 1024;  // 20GB
        if (roleCodes.contains("BACKEND_DEV") || roleCodes.contains("FRONTEND_DEV"))
            return 5L * 1024 * 1024 * 1024;  // 5GB
        if (roleCodes.contains("TESTER")) return 5L * 1024 * 1024 * 1024;  // 5GB
        return 1L * 1024 * 1024 * 1024;  // 1GB
    }

    /**
     * 列出文件树（指定父目录下，null=根目录）。
     */
    public List<FileNodeDTO> listFiles(String parentId) {
        String workspaceId = getMyWorkspaceId();
        LambdaQueryWrapper<WorkspaceFile> wrapper = new LambdaQueryWrapper<WorkspaceFile>()
            .eq(WorkspaceFile::getWorkspaceId, workspaceId)
            .orderByAsc(WorkspaceFile::getFileType)  // 目录在前
            .orderByAsc(WorkspaceFile::getFileName);
        if (parentId == null) {
            wrapper.isNull(WorkspaceFile::getParentId);
        } else {
            wrapper.eq(WorkspaceFile::getParentId, parentId);
        }
        return fileMapper.selectList(wrapper).stream().map(this::toFileNodeDTO).toList();
    }

    /**
     * 上传文件（含配额检查）。
     */
    @Transactional
    public FileNodeDTO uploadFile(MultipartFile file, String parentId) {
        Workspace ws = getMyWorkspaceEntity();
        checkQuota(ws, file.getSize());

        String relativePath = buildFilePath(parentId, file.getOriginalFilename());
        String objectKey = ws.getStoragePath() + relativePath;

        // 上传到 MinIO
        storageService.uploadToWorkspace(objectKey, file);

        // 写入 DB
        WorkspaceFile wf = new WorkspaceFile();
        wf.setWorkspaceId(ws.getId());
        wf.setParentId(parentId);
        wf.setFileName(file.getOriginalFilename());
        wf.setFileType(2);
        wf.setFilePath(relativePath);
        wf.setObjectKey(objectKey);
        wf.setMimeType(file.getContentType());
        wf.setFileSize(file.getSize());
        wf.setVersion(1);
        wf.setStatus(1);
        fileMapper.insert(wf);

        // 更新配额
        ws.setUsedBytes(ws.getUsedBytes() + file.getSize());
        ws.setFileCount(ws.getFileCount() + 1);
        workspaceMapper.updateById(ws);

        return toFileNodeDTO(wf);
    }

    /**
     * 保存文件内容（代码编辑器场景）。
     */
    @Transactional
    public FileNodeDTO saveFileContent(String fileId, String content) {
        Workspace ws = getMyWorkspaceEntity();
        WorkspaceFile wf = getFileEntity(fileId, ws.getId());

        long oldSize = wf.getFileSize();
        long newSize = content.getBytes(StandardCharsets.UTF_8).length;
        checkQuota(ws, newSize - oldSize);

        // 覆盖写入 MinIO
        String objectKey = wf.getObjectKey();
        storageService.uploadWorkspaceContent(objectKey, content, wf.getMimeType());

        // 更新 DB
        wf.setFileSize(newSize);
        wf.setChecksum(sha256(content));
        wf.setVersion(wf.getVersion() + 1);
        fileMapper.updateById(wf);

        // 更新配额
        ws.setUsedBytes(ws.getUsedBytes() + (newSize - oldSize));
        workspaceMapper.updateById(ws);

        return toFileNodeDTO(wf);
    }
}
```

### 6.4 DockerSandboxProvider 扩展（卷挂载）

```java
// DockerSandboxProvider.create() 方法扩展
@Override
public Sandbox create(CreateSandboxCommand command) {
    // ... 现有镜像/资源配比逻辑 ...

    var hostConfigBuilder = com.github.dockerjava.api.model.HostConfig.newHostConfig()
        .withNanoCPUs(nanoCpus)
        .withMemory(memoryBytes)
        .withNetworkMode(networkEnabled ? "bridge" : "none")
        .withSecurityOpts(List.of("no-new-privileges:true"))
        .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
        .withReadonlyRootfs(true)
        .withTmpFs(java.util.Map.of("/tmp", "rw,noexec,nosuid,size=64m"));

    // ★ 新增：工作空间卷挂载
    if (command.getWorkspaceId() != null) {
        String volumeName = "gewu-ws-" + command.getWorkspaceId().substring(0, 12).toLowerCase();
        ensureVolumeExists(volumeName);
        hostConfigBuilder.withBinds(new com.github.dockerjava.api.model.Bind(
            volumeName,
            new com.github.dockerjava.api.model.Volume("/workspace"),
            com.github.dockerjava.api.model.AccessMode.rw
        ));
        // 有工作空间时放开 readonly rootfs 限制（/workspace 需可写）
        hostConfigBuilder.withReadonlyRootfs(false);
    }

    CreateContainerResponse container = dockerClient.createContainerCmd(image)
        .withName("gewu-sandbox-" + Ulid.next().substring(0, 8).toLowerCase())
        .withEnv(env)
        .withHostConfig(hostConfigBuilder.build())
        .exec();

    // ... 后续逻辑不变 ...
}

private void ensureVolumeExists(String volumeName) {
    try {
        dockerClient.listVolumesCmd()
            .withFilter("name", List.of(volumeName))
            .exec()
            .getVolumes();
    } catch (Exception e) {
        log.warn("检查 Docker volume 失败，尝试创建: {}", volumeName);
    }
    try {
        dockerClient.createVolumeCmd().withName(volumeName).exec();
    } catch (Exception e) {
        log.debug("Volume 已存在或创建失败: {}", volumeName);
    }
}
```

### 6.5 MinioStorageService 扩展

```java
// 在 MinioStorageService 中新增方法

/** 上传文件到用户工作空间 */
public void uploadToWorkspace(String objectKey, MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(bucketName)
            .object(objectKey)
            .stream(is, file.getSize(), -1)
            .contentType(file.getContentType())
            .build());
    } catch (Exception e) {
        throw new RuntimeException("工作空间文件上传失败: " + e.getMessage());
    }
}

/** 保存文本内容到工作空间（覆盖） */
public void uploadWorkspaceContent(String objectKey, String content, String contentType) {
    try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(bucketName)
            .object(objectKey)
            .stream(is, size, -1)
            .contentType(contentType != null ? contentType : "text/plain")
            .build());
    } catch (Exception e) {
        throw new RuntimeException("文件内容保存失败: " + e.getMessage());
    }
}

/** 读取工作空间文件内容 */
public String readWorkspaceContent(String objectKey) {
    return getContent(objectKey);  // 复用现有方法
}

/** 递归删除工作空间目录下所有对象 */
public void deleteWorkspacePath(String pathPrefix) {
    try {
        Iterable<io.minio.Result<io.minio.messages.Item>> objects =
            minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName).prefix(pathPrefix).recursive(true).build());
        for (var item : objects) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName).object(item.get().objectName()).build());
        }
    } catch (Exception e) {
        log.warn("工作空间路径删除失败: {}", pathPrefix);
    }
}
```

---

## 7. 角色差异化设计

### 7.1 角色能力矩阵

| 能力 | USER | BACKEND_DEV | FRONTEND_DEV | TESTER | ADMIN |
|------|------|-------------|--------------|--------|-------|
| 存储配额 | 1GB | 5GB | 5GB | 5GB | 20GB |
| 文件上传/下载 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 目录管理 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 源码读写 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 沙箱卷挂载 | ❌ | ✅ | ✅ | ✅ | ✅ |
| 模板预设 | - | Java/Shell | Node.js | Python | 全部 |
| Git 初始化 | ❌ | ✅ | ✅ | ❌ | ✅ |
| 配额调整 | ❌ | ❌ | ❌ | ❌ | ✅ |

### 7.2 角色模板预设

工作空间初始化时，按角色创建不同的默认目录结构：

**BACKEND_DEV**:
```
/workspace/
├── src/main/java/
├── src/main/resources/
├── src/test/java/
├── pom.xml (模板)
└── README.md
```

**FRONTEND_DEV**:
```
/workspace/
├── src/
├── public/
├── package.json (模板)
├── tsconfig.json (模板)
└── README.md
```

**TESTER**:
```
/workspace/
├── tests/
├── test-data/
├── reports/
└── README.md
```

**USER（默认）**:
```
/workspace/
├── uploads/
├── docs/
└── README.md
```

---

## 8. 安全设计

### 8.1 隔离机制

| 层级 | 机制 | 实现 |
|------|------|------|
| 数据隔离 | `@DataPermission` | Workspace 表 `user_id` 字段，拦截器自动追加 `user_id = ?` |
| 存储隔离 | MinIO 路径前缀 | `workspaces/{userId}/` 前缀，无法越权访问 |
| 沙箱隔离 | Docker 容器 | 独立容器 + 独立 Volume，`--security-opt no-new-privileges` |
| 路径穿越防护 | file_path 校验 | 上传/创建时校验路径不含 `..` 和绝对路径 |

### 8.2 配额安全

```java
private void checkQuota(Workspace ws, long additionalBytes) {
    if (ws.getUsedBytes() + additionalBytes > ws.getQuotaBytes()) {
        throw BusinessException.of(ResultCode.PARAM_INVALID,
            "存储空间不足: 已用 " + formatSize(ws.getUsedBytes())
            + " / 配额 " + formatSize(ws.getQuotaBytes()));
    }
}
```

- 每次上传/保存前检查配额
- 配额使用量通过 DB `used_bytes` 字段实时维护
- 可选：DragonflyDB 缓存配额信息，减少 DB 查询

### 8.3 文件类型限制

```java
private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
    ".exe", ".bat", ".cmd", ".sh", ".ps1",  // 可执行文件
    ".dll", ".so", ".dylib"                   // 库文件
);
private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

private void validateFile(String fileName, long fileSize) {
    if (fileSize > MAX_FILE_SIZE) {
        throw BusinessException.of(ResultCode.PARAM_INVALID, "文件超过 100MB 限制");
    }
    String lower = fileName.toLowerCase();
    for (String ext : BLOCKED_EXTENSIONS) {
        if (lower.endsWith(ext)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "不支持的文件类型: " + ext);
        }
    }
}
```

---

## 9. 沙箱持久化集成

### 9.1 工作空间沙箱创建流程

```
用户请求创建沙箱 (template=java)
        │
        ▼
WorkspaceService.createWorkspaceSandbox()
        │
        ├── 1. 获取/创建用户工作空间
        ├── 2. 构建 CreateSandboxCommand (含 workspaceId)
        ├── 3. 调用 SandboxService.createSandbox()
        │         │
        │         ├── SandboxProvider.create()
        │         │    ├── 创建 Docker Volume (gewu-ws-{userId})
        │         │    └── 创建容器 (挂载 /workspace)
        │         └── SandboxProvider.start()
        │
        └── 4. 返回沙箱信息 (含 mountPath=/workspace)
```

### 9.2 沙箱内文件操作

沙箱启动后，用户可在沙箱内直接操作 `/workspace` 目录：

```bash
# 沙箱内执行的命令（通过 SandboxService.execCommand）
ls /workspace/src/           # 查看源码
cat /workspace/src/Main.java # 读取文件
echo "..." > /workspace/src/NewClass.java  # 创建文件
javac /workspace/src/*.java  # 编译
java -cp /workspace/src Main # 运行
```

**同步机制**：沙箱内直接操作 Docker Volume 文件系统，与 MinIO 存储独立。两者通过 `syncWorkspaceToMinio()` 方法异步同步（可选）。

### 9.3 数据一致性策略

```
┌─────────────┐         ┌──────────────┐
│ Docker Vol  │         │    MinIO     │
│ /workspace  │  ←──→   │ workspaces/  │
│ (沙箱读写)   │  sync   │  {userId}/   │
└─────────────┘         └──────────────┘
        ↑                       ↑
        │                       │
    沙箱直接操作             API 上传/下载
    (实时)                  (按需)
```

- **主存储**：MinIO（API 通道，持久化保证）
- **工作存储**：Docker Volume（沙箱通道，高性能读写）
- **同步策略**：沙箱停止时可选执行 `tar | minio put` 将 Volume 内容同步到 MinIO
- **冲突处理**：以 Docker Volume 为准（沙箱内的修改覆盖 MinIO）

---

## 10. 前端设计

### 10.1 新增页面：WorkspacePage

```
┌─────────────────────────────────────────────────────────┐
│  我的工作空间                            [配额: 1.2/5GB] │
├──────────────┬──────────────────────────────────────────┤
│  文件树       │  文件内容 / 编辑器                       │
│              │                                          │
│  📁 src/     │  // App.java                             │
│   📁 main/   │  public class App {                      │
│    📁 java/  │      public static void main(String[] a)│
│     📄 App   │      {                                   │
│   📁 test/   │          System.out.println("Hello");   │
│  📁 docs/    │      }                                   │
│   📄 README  │  }                                       │
│  📁 uploads/ │                                          │
│              │  [保存 Ctrl+S]  [版本: v3]               │
│  [+ 新建]    │                                          │
│  [↑ 上传]    │                                          │
│  [▶ 沙箱]    │                                          │
└──────────────┴──────────────────────────────────────────┘
```

### 10.2 前端文件结构

```
gewu-web/src/
├── lib/
│   └── workspace.ts          # API 封装
├── components/pages/
│   └── WorkspacePage.tsx     # 工作空间页面
└── types/
    └── index.ts              # 新增 PageType: 'workspace'
```

### 10.3 Sidebar 菜单

在"工作空间"分组新增"我的文件"菜单项：

```typescript
{ label: '工作空间', items: [
  { id: 'dashboard', label: '主页', icon: Home },
  // ... 现有菜单 ...
  { id: 'workspace' as PageType, label: '我的文件', icon: FolderOpen },  // 新增
]},
```

---

## 11. 迁移脚本

### 11.1 V19__add_workspace.sql

```sql
-- 用户工作空间表
CREATE TABLE IF NOT EXISTS workspace (
    id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    workspace_name VARCHAR(128) NOT NULL DEFAULT '我的工作空间',
    storage_path VARCHAR(256) NOT NULL,
    quota_bytes BIGINT NOT NULL DEFAULT 1073741824,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    file_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_user (user_id),
    KEY idx_workspace_path (storage_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户工作空间';

-- 工作空间文件元数据表
CREATE TABLE IF NOT EXISTS workspace_file (
    id VARCHAR(26) NOT NULL,
    workspace_id VARCHAR(26) NOT NULL,
    parent_id VARCHAR(26) DEFAULT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type TINYINT NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    object_key VARCHAR(512) DEFAULT NULL,
    mime_type VARCHAR(128) DEFAULT NULL,
    file_size BIGINT DEFAULT 0,
    checksum VARCHAR(64) DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_file_workspace (workspace_id),
    KEY idx_file_parent (parent_id),
    KEY idx_file_path (workspace_id, file_path(768))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间文件元数据';

-- 为现有管理员账号初始化工作空间
INSERT INTO workspace (id, user_id, workspace_name, storage_path, quota_bytes, used_bytes, file_count, status, created_at, updated_at, created_by)
SELECT '01ARZ3NDEKTSV4RRFFQ69G5FW0', id, '我的工作空间', CONCAT('workspaces/', id, '/'), 21474836480, 0, 0, 1,
       UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000, 'system'
FROM user_account WHERE username = '001_gewu_admin';
```

### 11.2 测试 schema.sql 补充

```sql
-- H2 兼容的 workspace 表
CREATE TABLE IF NOT EXISTS workspace (
    id VARCHAR(26) NOT NULL,
    user_id VARCHAR(26) NOT NULL,
    workspace_name VARCHAR(128) NOT NULL DEFAULT '我的工作空间',
    storage_path VARCHAR(256) NOT NULL,
    quota_bytes BIGINT NOT NULL DEFAULT 1073741824,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    file_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_workspace_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS workspace_file (
    id VARCHAR(26) NOT NULL,
    workspace_id VARCHAR(26) NOT NULL,
    parent_id VARCHAR(26) DEFAULT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type TINYINT NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    object_key VARCHAR(512) DEFAULT NULL,
    mime_type VARCHAR(128) DEFAULT NULL,
    file_size BIGINT DEFAULT 0,
    checksum VARCHAR(64) DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(26) DEFAULT NULL,
    updated_by VARCHAR(26) DEFAULT NULL,
    PRIMARY KEY (id)
);
```

---

## 12. 测试策略

| 测试类型 | 范围 | 工具 |
|----------|------|------|
| 单元测试 | WorkspaceService 文件树/配额逻辑 | JUnit5 + Mockito |
| 集成测试 | 文件上传→MinIO→下载 全链路 | SpringBootTest + H2 |
| E2E | 注册→工作空间自动创建→上传文件→创建沙箱→沙箱内访问文件 | MockMvc |
| 安全测试 | 越权访问他人工作空间、路径穿越、配额超限 | 手动 + 自动化 |

---

## 13. 实施路线图

| 阶段 | 内容 | 预估工时 |
|------|------|----------|
| Phase 1 | DB 迁移 + 实体 + Mapper + Service 骨架 | 4h |
| Phase 2 | 文件 CRUD API + MinIO 集成 | 6h |
| Phase 3 | 沙箱卷挂载 + DockerSandboxProvider 扩展 | 4h |
| Phase 4 | 角色差异化 + 配额管理 | 3h |
| Phase 5 | 前端 WorkspacePage + 文件树 UI | 8h |
| Phase 6 | 测试 + 文档 | 4h |
| **合计** | | **29h** |

---

## 附录 A：ADR 记录

### ADR-001: 存储双轨制（MinIO + Docker Volume）

**状态**：已批准

**背景**：用户工作空间需要同时支持 API 文件操作（上传/下载/编辑）和沙箱内直接文件操作（编译/运行），两者对性能和一致性要求不同。

**决策**：采用双轨存储。MinIO 作为主存储（API 通道），Docker Volume 作为工作存储（沙箱通道），沙箱停止时可选同步。

**理由**：
- MinIO 支持预签名 URL、跨节点访问，但无法直接挂载到容器
- Docker Volume 可直接挂载到容器，性能好，但无法通过 API 访问
- 双轨制各取所长，同步策略可选不阻塞主流程

**后果**：
- 正面：API 和沙箱各自获得最优性能
- 负面：存在数据一致性问题，需同步机制
- 缓解：以 Docker Volume 为权威源，MinIO 为备份/共享源

### ADR-002: 工作空间延迟初始化

**状态**：已批准

**背景**：注册时立即创建工作空间（DB + MinIO + Volume）会增加注册延迟，且部分用户可能永不使用工作空间。

**决策**：采用延迟初始化，首次调用 `GET /workspaces/me` 时创建。

**理由**：
- 注册流程保持简洁（仅 user_account + user_role）
- 工作空间创建失败不影响注册
- 减少无用资源占用

**后果**：
- 正面：注册快，资源按需分配
- 负面：首次访问有初始化延迟（<500ms 可接受）
