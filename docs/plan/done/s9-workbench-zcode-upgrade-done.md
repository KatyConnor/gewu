# S9「会话工作台 zcode 化升级」完成报告

> 日期：2026-09-15
> 范围：1 个 P0 缺陷修复（流式回复中途截断）+ 5 项功能（F1-F5，参考 zcode 交互）
> 提交序列：43b0096(M0) → e2c3a90(M1) → e314c2a(M2) → d7ef1ba(M3) → 265946b(M4)

## 一、缺陷修复：会话回复未完全输出（M0，P0）

**现象**：智能体写脚本时正文只渲染头两行即静默结束（如 scripts/fnamefix.sh），无任何提示。

**根因**：上轮（S8）截断自愈只覆盖「正文为空」场景；glm-5.3-flash 思考完、正文输出中途耗尽 token 时（finish_reason=length 且正文非空），全链路（引擎 DONE→控制器 [DONE]→前端正常收尾→落库无标记）零提示。次因：上游连接干净断开（EOF 无 [DONE]）被 LLM 客户端吞为正常完成。

**修复**（43b0096，18 文件）：
1. 截断自愈放宽到 finish=length 全场景（同步+流式）：正文非空先发 `content_reset` 事件清空已流出内容 → 加倍 max_tokens 整体重新生成；重试上限 3 次（8192→65536 硬顶）；预算升级同步扩容 tokenBudget/timeBudgetMs/maxRounds（consume 占轮次，否则重试被 shouldStop 熔断）
2. map→flatMapIterable：先记录 finish_reason 再处理增量（修复 finish_reason 与 delta 同 chunk 丢失、reasoning+content 同 chunk 丢内容）
3. OpenAiCompatibleClient：EOF 无 [DONE] 按上游中断报错（不再静默 complete）；新增流式空闲看门狗（默认 180s，`agent.engine.llm.stream-idle-timeout-ms` 可配）
4. 事件链三层（AgentEvent/AgentChunk/ChatStreamEvent）补 finishReason 透传；done 带 length 时前端显示琥珀色「回复不完整」横幅（ChatErrorBanner 支持 warning 色系）；截断内容不再入语义缓存/经验沉淀

**实测**：脚本生成任务两次空截断自动升级 8192→16384→32768 后第三轮完整生成 12350 字符，done=stop 正常收尾（原缺陷场景）。

## 二、F1+F2：项目分组导航、工作空间绑定、归档、顶栏（M1，e2c3a90）

- **后端**：createSession 工作空间绑定（项目会话 directory=/workspace/projects/{id}/repo，workspaceId=用户默认空间，解析失败不阻断）；GET /v1/sessions/my 增 projectId/defaultSpace/status 过滤（默认排除归档）；SessionDTO 补 workspaceId；ProjectDTO 补 repoBranch 透传
- **前端**：新组件 SessionSidebar 消除 ChatPage 两份重复侧栏；项目树形分组（默认空间+各项目+未知项目兜底）、项目名右侧新建会话按钮、会话行悬浮置顶/归档、「已归档」视图（取消归档）、分组可折叠；createSession 全链路传 projectId
- **F2 顶栏**：标题 + 项目文件夹 chip + Git 分支 chip（含 headCommit 摘要）；无项目会话显示「默认空间」
- **实测 7/7**：默认空间过滤/项目列表 repoBranch/项目会话目录与工作空间绑定/项目过滤/归档/归档视图隔离/取消归档

## 三、F4：活动单行流式视图（M2，e314c2a）

- 思考行流式期间单行滚动显示最新思考内容（尾部预览+光标），完成后回落持续时长；展开区固定 max-h-40 滚动
- 工具行完成未展开时单行尾部预览执行结果
- AIProcessTimeline 原有「单行+折叠箭头+固定展开区+手动优先于自动」骨架保留增强

## 四、F5：任务流程卡片（M3，d7ef1ba）

- **引擎内置 plan_task 工具**（无需 DB 注册，始终注入工具定义）：模型自主创建/更新任务清单；流式路径发 plan_created/plan_updated 事件（先于工具结果），同步路径仅更新状态；done 事件携带最终计划快照
- **事件链**：AgentEvent/AgentChunk/ChatStreamEvent 三层 planTitle/plan 载荷透传；ToolContext.PlanState 跨轮持有
- **落库回放**：控制器以 `<!--PLAN:json-->` 注释嵌入 content（复用 FILES 成熟模式），前端 loadConversation 解析恢复卡片
- **前端 PlanCard**：右上角浮动可折叠卡片（标题+进程 n/m+步骤清单，完成绿勾/进行中旋转/待办空心圆），plan 事件实时更新
- **实测**：实况对话中 plan_created → plan_updated → done 带快照全链路走通

## 五、F3：文件工具、变更追踪与右侧编辑面板（M4，265946b）

- **引擎文件工具**：FileWorkspaceSpi SPI（NoOp 默认）+ 内置 read_file/write_file/edit_file/list_dir（available 时注册）；内置工具统一分发；文件 IO 调度工具执行池；edit_file 唯一匹配校验
- **变更追踪**：V39 session_file_change（session_id+file_path 唯一键，写前 before 快照基线）；SessionFileWorkspaceService 会话→项目仓库/默认空间路由 + dev 沙箱惰性创建（ensureDevSandboxForUser 显式 userId）+ LCS 行级 diff（+N/-N 与 unified diff）
- **Diff API**：变更列表/diff 审查/当前内容读写（成员校验）
- **前端面板**：Monaco 自托管（public/monaco + sync 脚本免 CDN）；FileEditorPanel 变更列表（+N/-N/审查/打开）+ 多文件 tab + DiffEditor 审查 + 可编辑 Ctrl+S 保存写回（脏标记）；顶栏「N 个文件已更改」chip；面板默认折叠
- **单测**：文件工具 SPI 执行（write→edit 两轮、定义透出、不外调 ToolExecutor）

## 六、连带修复（M3/M4 实测发现的既有缺陷）

| 缺陷 | 修复 |
|---|---|
| wenshi 5 个知识服务以 36 字符 UUID 写 varchar(26) id 列，insert 全部静默失败——情景/语义/过程记忆从未落库（知识库为空的根因） | 统一改用 26 字符 ULID（d7ef1ba） |
| SandboxDTO 缺 Jackson 构造器注解，反序列化必败——dev 沙箱创建从未走通 | 补 @NoArgsConstructor/@AllArgsConstructor（265946b） |
| git_credential 缺 updated_by（V20 建表遗漏），沙箱创建的凭证查询失败 | V40 幂等补列（265946b） |
| LlmConfig 旧静态 QwenClient 用 dashscope 原生协议，与 compatible-mode 端点协议不匹配且优先于 DB 动态加载——qwen 供应商静默返回空流 | 移除静态客户端，统一 DB 驱动 OpenAI 兼容路径；删除 LlmClientAdapterRegistration（265946b） |

## 七、验证与遗留

- **全量测试**：447 绿（引擎 194 含 plan/文件工具/截断 7 个新用例；application 131 含会话过滤适配）
- **迁移**：V39/V40 Flyway 落库成功（baseline 链 37→40）
- **实况冒烟**：M0 截断自愈 done=stop ✓、M3 计划事件链 ✓、M1 生命周期 7/7 ✓；M4 文件工具实况因 ark-code 5h 配额窗口耗尽（多轮冒烟消耗）顺延——机制已由单测覆盖，配额重置后补测：写/编辑文件 → 变更列表 +N/-N → diff 审查 → 面板保存写回
- **环境注意**：qwen/opencode 供应商 DB 中 api_key 为空（仅 ark-code 有密钥），动态客户端不可用属配置问题非代码问题
- **遗留低优先级**：同项目多会话并发共享工作空间时变更归属为后写覆盖；Monaco 资源 24MB（sync-monaco.sh 按需同步）；沙箱 dev 镜像默认 alpine（git 操作需换装 git 的镜像，GEWU_DEV_IMAGE 可覆盖）
