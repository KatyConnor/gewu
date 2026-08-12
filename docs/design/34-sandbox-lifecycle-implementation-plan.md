# 格物平台 — 沙箱生命周期管理实施计划

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 沙箱生命周期管理实施计划 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-10 |
| 文档状态 | 执行中 |
| 关联设计文档 | 27-agent-sandbox-design.md §11-§15 (V1.2) |
| 关联 PRD | 17-product-requirements.md §3.6 US-SB-05~12 (V1.1) |

---

## 1. 实施总览

### 1.1 阶段划分

| 阶段 | 目标 | 预计工时 | 状态 |
|------|------|----------|------|
| Phase 1 | 数据模型 + DDL + 模板匹配 + 核心 API | 3 天 | ✅ 完成 |
| Phase 2 | 空闲自动停止 + 定时任务 + 过期管理 | 2 天 | ✅ 完成 |
| Phase 3 | Agent 集成（ToolExecutionService 调用沙箱） | 2 天 | ✅ 完成 |
| Phase 4 | 前端适配 + 项目绑定 | 2 天 | ✅ 完成 |

### 1.2 三种创建场景

| 场景 | source | 自动销毁 | 触发方 | 生命周期 |
|------|--------|----------|--------|----------|
| 手动创建 | manual | 否 | 用户前端操作 | 启动→空闲停止→启动→用户销毁 |
| Agent 自定 | agent | 是 | Agent 执行代码 | 创建→执行→自动销毁 |
| 项目绑定 | project | 否 | 项目创建/配置 | 项目存续期，项目归档时销毁 |

---

## 2. Phase 1: 数据模型 + DDL + 模板匹配 + 核心 API

### Day 1: 数据层 + 配置

#### 2.1.1 V4 DDL 迁移脚本

| 项 | 内容 |
|----|------|
| 文件 | `gewu-interface/src/main/resources/db/migration/V4__sandbox_lifecycle.sql` |
| 操作 | **新增** |
| SQL | `ALTER TABLE sandbox ADD COLUMN source/project_id/agent_id/auto_destroy/last_used_at/expire_at` + 4 个索引 |

#### 2.1.2 Sandbox 实体更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-domain/src/main/java/com/gewu/domain/sandbox/Sandbox.java` |
| 操作 | 修改 |
| 新增字段 | `source`(String), `projectId`(String), `agentId`(String), `autoDestroy`(Integer), `lastUsedAt`(Long), `expireAt`(Long) |

#### 2.1.3 SandboxDTO 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/dto/SandboxDTO.java` |
| 操作 | 修改 |
| 新增字段 | `source`, `projectId`, `agentId`, `autoDestroy`, `lastUsedAt`, `expireAt` |

#### 2.1.4 CreateSandboxCommand 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/dto/CreateSandboxCommand.java` |
| 操作 | 修改 |
| 新增字段 | `source`(默认 manual), `projectId`, `agentId`, `autoDestroy`, `template` |
| 变更 | `image` 的 `@NotBlank` 改为可选（模板模式下自动填充） |

#### 2.1.5 新增 ExecuteCodeRequest DTO

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/dto/ExecuteCodeRequest.java` |
| 操作 | **新增** |
| 字段 | `language`(String,必填), `code`(String,必填), `timeout`(Integer,可选默认30) |

#### 2.1.6 新增 CreateProjectSandboxCommand DTO

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/dto/CreateProjectSandboxCommand.java` |
| 操作 | **新增** |
| 字段 | `template`(String,可选), `sandboxName`(String,可选) |

#### 2.1.7 新增 RenewExpireRequest DTO

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/dto/RenewExpireRequest.java` |
| 操作 | **新增** |
| 字段 | `expireAt`(Long,必填), `ttlDays`(Integer,可选) |

#### 2.1.8 application.yml 配置更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/resources/application.yml` |
| 操作 | 修改 |
| 新增配置 | `gewu.sandbox.registry.url/username/password/repository`, `gewu.sandbox.idle-timeout-minutes=30`, `gewu.sandbox.auto-stop-enabled=true`, `gewu.sandbox.manual-ttl-days=7`, `gewu.sandbox.agent-max-lifetime-seconds=300` |

#### 2.1.9 SandboxStatus 枚举更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-common/src/main/java/com/gewu/common/enums/SandboxStatus.java` |
| 操作 | 修改 |
| 新增 | `EXPIRED("expired", "已过期")` |

#### 2.1.10 ResultCode 新增错误码

| 项 | 内容 |
|----|------|
| 文件 | `gewu-common/src/main/java/com/gewu/common/result/ResultCode.java` |
| 操作 | 修改 |
| 新增 | `SANDBOX_EXPIRED(16003, "沙箱已过期")`, `SANDBOX_IMAGE_PULL_FAILED(16004, "镜像拉取失败")`, `SANDBOX_TEMPLATE_NOT_FOUND(16005, "沙箱模板不存在")` |

#### 2.1.11 编译验证

| 项 | 内容 |
|----|------|
| 命令 | `mvn compile -q` |
| 预期 | 全部 7 模块编译通过，无报错 |

---

### Day 2: Service + Controller + 模板匹配

#### 2.2.1 SandboxTemplateMatcher（新增）

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/template/SandboxTemplateMatcher.java` |
| 操作 | **新增** |
| 方法 | `resolveImage(String language)` → 根据语言匹配镜像 |
| 方法 | `resolveFromTechStack(String techStack)` → 从项目技术栈推断 |
| 方法 | `resolveFromTemplate(String template)` → 根据模板名返回镜像+资源配比 |
| 枚举 | `SandboxTemplate { PYTHON, NODEJS, JAVA, SHELL }` 含 image/defaultCpu/defaultMemoryMb |

#### 2.2.2 SandboxService 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/service/SandboxService.java` |
| 操作 | 修改 |
| 修改方法 | `createSandbox()` 处理 source/projectId/autoDestroy/expireAt 设置 |
| 新增方法 | `executeCode(ExecuteCodeRequest)` → 匹配模板→创建(source=agent)→启动→执行→销毁→返回结果 |
| 新增方法 | `createForProject(String projectId, CreateProjectSandboxCommand)` → 从项目 techStack 推断模板→创建(source=project) |
| 新增方法 | `destroyProjectSandboxes(String projectId)` → 销毁项目下所有沙箱 |
| 新增方法 | `renewExpire(String id, RenewExpireRequest)` → 更新过期时间 |
| 修改方法 | `execCommand()` 更新 lastUsedAt |
| 修改方法 | `toDTO()` 映射新增字段 |

#### 2.2.3 DockerConfig 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/config/DockerConfig.java` |
| 操作 | 修改 |
| 改动 | 新增 `@Value` 读取 registry 配置，创建 DockerClient 时注入 registry 认证 |

#### 2.2.4 SandboxController 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/controller/SandboxController.java` |
| 操作 | 修改 |
| 新增端点 | `POST /api/v1/sandboxes/execute` → Agent 自动执行代码 |
| 新增端点 | `POST /api/v1/sandboxes/project/{projectId}` → 创建项目绑定沙箱 |
| 新增端点 | `DELETE /api/v1/sandboxes/project/{projectId}` → 销毁项目绑定沙箱 |
| 新增端点 | `PUT /api/v1/sandboxes/{id}/expire` → 续期 |

#### 2.2.5 编译验证

| 项 | 内容 |
|----|------|
| 命令 | `mvn compile -q` |
| 预期 | 全部编译通过 |

---

### Day 3: 镜像管理 + 编译验证

#### 2.3.1 DockerSandboxProvider 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/provider/DockerSandboxProvider.java` |
| 操作 | 修改 |
| 新增方法 | `ensureImageExists(String image)` → 检查镜像→不存在则 pull→超时 120s |
| 修改方法 | `create()` 调用 `ensureImageExists()` |

#### 2.3.2 SandboxConstants 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/constant/SandboxConstants.java` |
| 操作 | 修改 |
| 变更 | `ALLOWED_IMAGES` 改为 `ALLOWED_IMAGE_PREFIXES`（前缀匹配） |

#### 2.3.3 SandboxValidator 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/validator/SandboxValidator.java` |
| 操作 | 修改 |
| 变更 | `validateImage()` 改为前缀匹配 |

#### 2.3.4 编译验证

| 项 | 内容 |
|----|------|
| 命令 | `mvn compile -q` + `mvn test -pl gewu-common` |
| 预期 | 编译通过，90 个单元测试通过 |

---

## 3. Phase 2: 空闲管理 + 定时任务 + 过期策略

### Day 4: 定时任务

#### 3.1.1 SandboxScheduler（新增）

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/scheduler/SandboxScheduler.java` |
| 操作 | **新增** |
| 方法 | `autoStopIdleSandboxes()` → 每 5 分钟检查 running 沙箱，超过空闲超时则 stop |
| 方法 | `checkExpiredSandboxes()` → 每 1 小时检查 expireAt，过期的标记 EXPIRED + stop |
| 方法 | `autoDestroyAgentSandboxes()` → 每 1 分钟检查 Agent 沙箱是否超过最大存活时间 |

#### 3.1.2 SandboxApplication 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/SandboxApplication.java` |
| 操作 | 修改 |
| 改动 | 新增 `@EnableScheduling` |

#### 3.1.3 SandboxMapper 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-sandbox/src/main/java/com/gewu/sandbox/mapper/SandboxMapper.java` |
| 操作 | 修改 |
| 新增方法 | `selectIdleSandboxes()`, `selectExpiredSandboxes()`, `selectAgentSandboxesExpired()` |

### Day 5: 端到端验证

#### 3.2.1 编译 + 测试验证

| 项 | 内容 |
|----|------|
| 命令 | `mvn compile -q` + `mvn test` |
| 预期 | 全部编译通过，92 个测试通过 |

---

## 4. Phase 3: Agent 集成

### Day 6: Agent 端改动

#### 4.1.1 ToolContext 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-application/src/main/java/com/gewu/application/agent/ToolContext.java` |
| 操作 | 修改 |
| 新增字段 | `sandboxImage`(String), `projectId`(String) |

#### 4.1.2 ToolExecutionService 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-application/src/main/java/com/gewu/application/agent/ToolExecutionService.java` |
| 操作 | 修改 |
| 新增方法 | `executeInSandbox(language, code, timeout, context)` → 通过 HTTP 调用 Sandbox execute API |

#### 4.1.3 AgentExecutionEngine 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-application/src/main/java/com/gewu/application/agent/AgentExecutionEngine.java` |
| 操作 | 修改 |
| 变更 | `buildToolContext()` 中 `sandboxEnabled` 从 Agent modelConfig 读取（不再硬编码 false） |

### Day 7: 联调验证

#### 4.2.1 全链路编译验证

| 项 | 内容 |
|----|------|
| 命令 | `mvn compile -q` |
| 预期 | 全部 7 模块编译通过 |

---

## 5. Phase 4: 前端 + 项目绑定

### Day 8: 前端沙箱管理页面

#### 5.1.1 SandboxList.tsx 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-web/src/pages/SandboxList.tsx` |
| 操作 | 修改 |
| 变更 1 | 镜像选择改为模板选择（4 种模板 Radio + 自定义输入） |
| 变更 2 | 新增"来源"列（手动/自动/项目） |
| 变更 3 | 新增"过期时间"列 + 续期按钮 |
| 变更 4 | 新增"关联项目"显示 |

#### 5.1.2 api.ts 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-web/src/services/api.ts` |
| 操作 | 修改 |
| 新增接口 | `executeSandbox()`, `createProjectSandbox()`, `destroyProjectSandbox()`, `renewSandboxExpire()` |
| 新增类型 | `ExecuteCodeRequest`, `CreateProjectSandboxRequest` |

### Day 9: 项目绑定 + 端到端验证

#### 5.2.1 ProjectService 更新

| 项 | 内容 |
|----|------|
| 文件 | `gewu-application/src/main/java/com/gewu/application/project/ProjectService.java` |
| 操作 | 修改 |
| 变更 | 项目归档时调用 sandboxAPI 销毁项目沙箱 |

#### 5.2.2 端到端验证

| 项 | 内容 |
|----|------|
| 命令 | `mvn compile -q` + `npx vite build` |
| 预期 | 后端编译通过，前端 21 chunks 构建通过 |

---

## 6. 新增文件清单

| # | 文件路径 | 模块 |
|---|----------|------|
| 1 | `gewu-interface/.../migration/V4__sandbox_lifecycle.sql` | interface |
| 2 | `gewu-sandbox/.../dto/ExecuteCodeRequest.java` | sandbox |
| 3 | `gewu-sandbox/.../dto/CreateProjectSandboxCommand.java` | sandbox |
| 4 | `gewu-sandbox/.../dto/RenewExpireRequest.java` | sandbox |
| 5 | `gewu-sandbox/.../template/SandboxTemplateMatcher.java` | sandbox |
| 6 | `gewu-sandbox/.../scheduler/SandboxScheduler.java` | sandbox |

## 7. 修改文件清单

| # | 模块 | 文件 |
|---|------|------|
| 1 | common | SandboxStatus.java |
| 2 | common | ResultCode.java |
| 3 | domain | Sandbox.java |
| 4 | sandbox | SandboxDTO.java |
| 5 | sandbox | CreateSandboxCommand.java |
| 6 | sandbox | SandboxService.java |
| 7 | sandbox | SandboxController.java |
| 8 | sandbox | DockerConfig.java |
| 9 | sandbox | DockerSandboxProvider.java |
| 10 | sandbox | SandboxConstants.java |
| 11 | sandbox | SandboxValidator.java |
| 12 | sandbox | SandboxApplication.java |
| 13 | sandbox | application.yml |
| 14 | application | ToolContext.java |
| 15 | application | ToolExecutionService.java |
| 16 | application | AgentExecutionEngine.java |
| 17 | application | ProjectService.java |
| 18 | web | SandboxList.tsx |
| 19 | web | api.ts |

## 8. 进度跟踪

| 阶段 | 任务 | 状态 | 完成时间 |
|------|------|------|----------|
| Phase 1 | V4 DDL 迁移脚本 | ✅ 完成 | 2026-07-10 |
| Phase 1 | Sandbox 实体新增字段 | ✅ 完成 | 2026-07-10 |
| Phase 1 | SandboxDTO 新增字段 | ✅ 完成 | 2026-07-10 |
| Phase 1 | CreateSandboxCommand 新增字段 | ✅ 完成 | 2026-07-10 |
| Phase 1 | 新增 DTO（3 个） | ✅ 完成 | 2026-07-10 |
| Phase 1 | application.yml 配置更新 | ✅ 完成 | 2026-07-10 |
| Phase 1 | SandboxStatus 新增 EXPIRED | ✅ 完成 | 2026-07-10 |
| Phase 1 | ResultCode 新增错误码 | ✅ 完成 | 2026-07-10 |
| Phase 1 | 编译验证通过 | ✅ 完成 | 2026-07-10 |
| Phase 1 | SandboxTemplateMatcher | ✅ 完成 | 2026-07-10 |
| Phase 1 | SandboxService 更新 | ✅ 完成 | 2026-07-10 |
| Phase 1 | DockerConfig 更新 | ✅ 完成 | 2026-07-10 |
| Phase 1 | SandboxController 更新 | ✅ 完成 | 2026-07-10 |
| Phase 1 | DockerSandboxProvider 镜像拉取 | ✅ 完成 | 2026-07-10 |
| Phase 1 | SandboxConstants + Validator | ✅ 完成 | 2026-07-10 |
| Phase 2 | SandboxScheduler 定时任务 | ✅ 完成 | 2026-07-10 |
| Phase 2 | SandboxApplication @EnableScheduling | ✅ 完成 | 2026-07-10 |
| Phase 2 | SandboxMapper 新增查询 | ✅ 完成（无需改动，使用 BaseMapper+LambdaQueryWrapper） | 2026-07-10 |
| Phase 3 | ToolContext 新增字段 | ✅ 完成 | 2026-07-10 |
| Phase 3 | ToolExecutionService 新增沙箱执行 | ✅ 完成 | 2026-07-10 |
| Phase 3 | AgentExecutionEngine sandboxEnabled | ✅ 完成 | 2026-07-10 |
| Phase 4 | SandboxList.tsx 模板选择 | ✅ 完成 | 2026-07-10 |
| Phase 4 | api.ts 新增接口 | ✅ 完成 | 2026-07-10 |
| Phase 4 | ProjectService 项目归档销毁沙箱 | ✅ 完成 | 2026-07-10 |
| Phase 4 | 端到端编译+前端构建验证 | ✅ 完成 | 2026-07-10 |
| Phase 3 | 全链路编译验证 | ✅ 完成 | 2026-07-10 |