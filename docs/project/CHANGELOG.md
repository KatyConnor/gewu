# 变更日志

## Sprint 0 (2026-07-08 ~ 2026-07-21) — 准备期
- 21 份设计文档（PRD、架构、数据库、API、安全、部署等）
- 4 份技术 Spike 验证报告
- 32 张表 DDL 脚本
- Jenkinsfile CI/CD 流水线
- CONTRIBUTING.md 代码规范
- Sprint 0 Go/No-Go: ✅ GO

## Sprint 1 (W3-W6) — 基础设施层
- gewu-common: ULID 生成器、SM2/SM3/SM4 国密工具、JWT、统一返回
- gewu-domain: 32 张表领域实体（7 个限界上下文）
- gewu-infrastructure: 32 个 Mapper、缓存服务、分布式锁、RocketMQ 事件
- gewu-application: AuthService + UserService（8 个 API）
- gewu-interface: SecurityConfig + JWT 过滤器 + 全局异常处理
- 前端骨架: React + Ant Design + 登录页 + 主布局

## Sprint 2 (W7-W10) — 核心业务模块
- 项目管理: ProjectService + ProjectMemberService（10 个 API）
- 会话消息: SessionService + MessageService（13 个 API）
- Agent 系统: AgentService + AgentToolService + AgentExecutionService（16 个 API）
- 前端: ProjectList、SessionList、AgentList 页面

## Sprint 3 (W11-W12) — 工作流引擎 + SSE
- SSE 实时推送: SseEventManager + SseController
- 工作流引擎: WorkflowService + WorkflowInstanceService（21 个 API）
- 前端: SessionDetail 聊天页面 + SSE 实时接收
- 单元测试: 90 个测试用例（行覆盖率 71.3%）

## Sprint 4 (W13-W14) — 性能优化 + 安全加固
- 性能: @CacheResult 缓存切面、@RateLimit 限流、@Idempotent 防重、慢 SQL 监控
- 安全: 密码策略、XSS 防护、安全响应头、审计日志切面、登录风控
- 前端: React Flow 工作流设计器、Agent 配置页
- E2E 集成测试: 10 个业务步骤全链路验证

## Sprint 5 (W15-W16) — 运维部署
- XSS 修复: JSON 请求体跳过转义
- 监控接入: Prometheus + Actuator + DatabaseHealthIndicator
- Docker 多阶段构建: maven builder → alpine JRE
- K8s 部署清单: 6 文件（Namespace/ConfigMap/Deployment/Service/Ingress/HPA）
- 生产编排: docker-compose.prod.yml
- 性能基准测试: 8 个核心接口 P95/P99 测量

## Sprint 6 (W17-W18) — 交付优化
- 前端 Vite 代码分割 + React.lazy 路由懒加载
- OpenAPI 3.0 配置: JWT Bearer 认证 + 错误码说明
- README.md 项目文档
- 部署文档 DEPLOYMENT.md
- 变更日志
- 全量测试验证

## Sprint 7-8 (W19-W20) — 网关 + 沙箱模块
- API 网关: Spring Cloud Gateway 路由/限流/国密TLS/JWT验证
- 网关模块: 服务发现 + 动态路由 + 熔断降级
- 沙箱模块: 沙箱生命周期管理 (创建/启动/停止/销毁)
- 沙箱模块: 三层隔离架构 (Docker/gVisor/Firecracker)
- 沙箱模块: 资源限制 + 安全策略 + 审计日志
- 新增 34 个 Java 文件 (网关 8 + 沙箱 26)
- 后端 Java 文件总数: 222 个

## Sprint 9 (W21-W22) — AI 网关集成
- LLM 客户端: 通义千问 (Qwen) + DeepSeek 双模型支持，SSE 流式输出
- Agent 执行引擎: 对话循环 + 工具调用 + 上下文管理
- 工具执行服务: 工具注册/发现/执行 + 超时控制 + 结果限制 (10KB)
- 会话上下文管理: 历史压缩 + 向量缓存 + System Context
- AI 对话 API: SSE 流式输出 + 多轮对话 + 工具权限评估
- 新增 40 个 Java 文件 (LLM 11 + Agent 22 + AI 7)
- 后端 Java 文件总数: 252 个
- 全量测试通过: 90 单元测试 + 2 集成测试

## Sprint 10 (W23-W24) — 生产部署验证
- Docker 多阶段构建优化: 401MB → 277MB (-31%)
- K8s 部署验证: 6 清单 + HPA (2-10 副本, CPU 70%)
- 监控告警: 6 条 Prometheus 规则 (monitoring.yaml)
- JMeter 性能测试: 5 场景, 750 并发用户
- 等保 2.0 三级合规: 92/100 分, A 级, 38 项检查通过
- 安全配置验证: 29/29 项全部通过
- 部署/回滚脚本: deploy.sh + rollback.sh

## Sprint 11 (W25-W26) — 数据迁移工具
- 迁移脚本: 主入口 + 用户/会话/Agent 分阶段迁移 (5 个脚本)
- 验证工具: 记录数对比 + 外键校验 + ULID 格式检查 + 完整性验证 (3 个脚本)
- 备份工具: OpenCode 备份 + Gewu 备份 + 迁移前统一备份 (3 个脚本)
- 回滚工具: 全量回滚 + 部分回滚 + 检查点回滚 + 回滚验证 + 回滚演练 (5 个脚本)
- 清理工具: 日志归档 + 临时文件清理 + 跟踪表清理 (1 个脚本)
- 转换工具: UUID→ULID 映射 + SM3 哈希校验 (Python)
- 配置管理: 统一配置源 (MySQL 连接/批量大小/超时/日志)
- 迁移文档: 7 份 (设计文档/指南/最佳实践/检查清单/FAQ/案例研究/模板)
- 迁移工具总数: 18 个文件

## Sprint 12 (W27-W28) — 前端收尾
- AI 对话页: SSE 流式输出 + 多轮对话 + 模型选择 (Qwen/DeepSeek) + Markdown 渲染 + 停止生成
- 沙箱管理页: 统计卡片 + 沙箱表格 + 创建/启动/停止/销毁 + 详情抽屉 + 日志查看 + 自动轮询
- 工作流设计器增强: 节点校验 + 条件边动画 + 自动布局 + 网格对齐 + 边标签编辑 + 键盘快捷键 + 图例 + 计数徽章
- 路由更新: /ai-chat + /sandboxes 新增 + /ai-dialog 首页
- 导航更新: AI 对话 (ThunderboltOutlined) + 沙箱 (CloudServerOutlined) 菜单项
- API 扩展: ChatRequest + ChatMessage + ModelInfo + SandboxDTO + SandboxLogDTO + postSSE + del
- 构建脚本优化: 分离 build (vite) 和 typecheck (tsc --noEmit)
- 前端构建通过: 19 个产物分块 (新增 AiChat 8KB + SandboxList 10KB + WorkflowDesigner 20KB)

## Sprint 13 (W29-W30) — 前端移植 (mz-code/gewu)
- 核心布局: SidebarRail (56px 图标导航) + Titlebar + SidebarPanel + SessionPanel (260px)
- 主题系统: 37 个主题 (GitHub/OpenCode/Catppuccin/Dracula/Nord/Tokyo Night 等) + CSS 变量 + 暗色/亮色切换
- 状态管理: React Context 替代 Pinia (appStore + chatStore) + localStorage 持久化
- 聊天组件: ChatArea + ChatHeader + ChatInput (思考强度选择器) + MessageItem (Markdown/工具调用) + MessageTimeline
- 命令面板: Ctrl+K 命令搜索 + 键盘导航 + 快捷键执行
- 设置对话框: 通用/外观/通知/快捷键 分页设置 + 主题选择器
- 通知系统: Toast 通知 + 自动消失 + 类型区分
- 权限对话框: AI 工具执行权限请求 + 接受/拒绝 + 记住选项
- 代码编辑器: 文件标签 + 行号 + 语法高亮 + 保存对话框
- 项目管理: 项目对话框 + 文件树面板 + 递归文件节点
- 终端面板: 终端标签 + 命令输入 + 实时输出
- 测试面板: 测试历史 + 运行测试 + 测试报告
- 审查面板: 代码审查 + 文件分组 + 问题详情
- 待办面板: 待办列表 + 优先级 + 完成计数
- 上下文面板: 会话上下文 + 令牌统计 + 原始消息查看
- 首页: 格物·致虚 欢迎页 + 快速创建会话 + 提示
- 会话视图: 进度条 + 消息时间线 + 提示输入 + 自动恢复流式对话
- API 层: 会话/消息/聊天/Agent/权限/文件/项目/测试/审查 API
- 工具函数: usePacedText (打字机效果) + 简单 Markdown 渲染器
- 新增文件: 44 个 TSX/TS 文件 + 约 5,200 行代码
- 前端构建通过: 21 个产物分块 (新增 HomeView 4KB + SessionView 22KB + index 95KB)