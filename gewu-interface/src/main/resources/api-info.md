# 格物平台 API 端点清单

## 认证模块 (8 endpoints)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/v1/auth/register | No | 用户注册 |
| POST | /api/v1/auth/login | No | 用户登录 |
| POST | /api/v1/auth/refresh | No | 刷新令牌 |
| POST | /api/v1/auth/logout | JWT | 用户登出 |
| GET | /api/v1/users/me | JWT | 获取当前用户 |
| GET | /api/v1/users/{id} | JWT | 获取指定用户 |
| GET | /api/v1/users | JWT | 分页用户列表 |
| PUT | /api/v1/users/me | JWT | 更新当前用户 |

## 项目模块 (10 endpoints)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/v1/projects | JWT | 创建项目 |
| GET | /api/v1/projects | JWT | 项目列表 |
| GET | /api/v1/projects/my | JWT | 我的项目 |
| GET | /api/v1/projects/{id} | JWT | 获取项目 |
| PUT | /api/v1/projects/{id} | JWT | 更新项目 |
| DELETE | /api/v1/projects/{id} | JWT | 删除项目 |
| GET | /api/v1/projects/{id}/members | JWT | 项目成员 |
| POST | /api/v1/projects/{id}/members | JWT | 添加成员 |
| DELETE | /api/v1/projects/{id}/members/{userId} | JWT | 移除成员 |
| PUT | /api/v1/projects/{id}/members/{userId} | JWT | 更新成员角色 |

## 会话消息模块 (13 endpoints)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/v1/sessions | JWT | 创建会话 |
| GET | /api/v1/sessions | JWT | 会话列表 |
| GET | /api/v1/sessions/my | JWT | 我的会话 |
| GET | /api/v1/sessions/{id} | JWT | 获取会话 |
| PUT | /api/v1/sessions/{id} | JWT | 更新会话 |
| DELETE | /api/v1/sessions/{id} | JWT | 删除会话 |
| GET | /api/v1/sessions/{id}/members | JWT | 会话成员 |
| POST | /api/v1/sessions/{id}/messages | JWT | 发送消息 |
| GET | /api/v1/sessions/{id}/messages | JWT | 消息列表 |
| GET | /api/v1/sessions/{id}/messages/search | JWT | 搜索消息 |
| GET | /api/v1/sessions/{id}/messages/{msgId} | JWT | 获取消息 |
| PUT | /api/v1/sessions/{id}/messages/{msgId} | JWT | 编辑消息 |
| DELETE | /api/v1/sessions/{id}/messages/{msgId} | JWT | 删除消息 |

## Agent 模块 (16 endpoints)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/v1/agents | JWT | 创建Agent |
| GET | /api/v1/agents | JWT | Agent列表 |
| GET | /api/v1/agents/{id} | JWT | 获取Agent |
| PUT | /api/v1/agents/{id} | JWT | 更新Agent |
| DELETE | /api/v1/agents/{id} | JWT | 删除Agent |
| GET | /api/v1/agents/{id}/tools | JWT | Agent工具列表 |
| POST | /api/v1/agents/tools | JWT | 创建工具 |
| GET | /api/v1/agents/tools/{id} | JWT | 获取工具 |
| GET | /api/v1/agents/tools/agent/{agentId} | JWT | Agent的工具列表 |
| PUT | /api/v1/agents/tools/{id} | JWT | 更新工具 |
| POST | /api/v1/agents/executions | JWT | 创建执行记录 |
| PUT | /api/v1/agents/executions/{id}/complete | JWT | 完成执行 |
| PUT | /api/v1/agents/executions/{id}/fail | JWT | 标记失败 |
| GET | /api/v1/agents/executions/{id} | JWT | 获取执行记录 |
| GET | /api/v1/agents/executions/agent/{agentId} | JWT | Agent执行列表 |
| GET | /api/v1/agents/executions/my | JWT | 我的执行记录 |

## 工作流模块 (21 endpoints)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/v1/workflows | JWT | 创建工作流 |
| GET | /api/v1/workflows | JWT | 工作流列表 |
| GET | /api/v1/workflows/{id} | JWT | 获取工作流 |
| PUT | /api/v1/workflows/{id} | JWT | 更新工作流 |
| DELETE | /api/v1/workflows/{id} | JWT | 删除工作流 |
| POST | /api/v1/workflows/{id}/publish | JWT | 发布工作流 |
| POST | /api/v1/workflows/{id}/archive | JWT | 归档工作流 |
| GET | /api/v1/workflows/{id}/graph | JWT | 获取工作流图 |
| PUT | /api/v1/workflows/{id}/graph | JWT | 保存工作流图 |
| GET | /api/v1/workflows/{id}/nodes | JWT | 工作流节点 |
| POST | /api/v1/workflows/instances/{workflowId}/start | JWT | 启动实例 |
| GET | /api/v1/workflows/instances | JWT | 实例列表 |
| GET | /api/v1/workflows/instances/my | JWT | 我的实例 |
| GET | /api/v1/workflows/instances/{id} | JWT | 获取实例 |
| PUT | /api/v1/workflows/instances/{id}/complete | JWT | 完成节点 |
| PUT | /api/v1/workflows/instances/{id}/suspend | JWT | 挂起 |
| PUT | /api/v1/workflows/instances/{id}/resume | JWT | 恢复 |
| PUT | /api/v1/workflows/instances/{id}/terminate | JWT | 终止 |
| GET | /api/v1/workflows/instances/{id}/nodes | JWT | 实例节点 |
| GET | /api/v1/workflows/instances/notifications | JWT | 我的通知 |
| PUT | /api/v1/workflows/instances/notifications/{id}/read | JWT | 标记通知已读 |

## SSE 推送 (1 endpoint)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /api/v1/sse/sessions/{sessionId} | JWT+Query | SSE消息推送 |

## 监控 (2 endpoints)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /actuator/health | No | 健康检查 |
| GET | /actuator/prometheus | No | Prometheus指标 |

## 汇总
- 认证模块: 8
- 项目模块: 10
- 会话消息模块: 13
- Agent 模块: 16
- 工作流模块: 21
- SSE推送: 1
- 监控: 2
- **总计: 71 个 API 端点**