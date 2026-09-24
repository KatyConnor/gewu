# AETC 核心管控规则（00-core.md）

<!--
  规范来源：《AI Agent 执行过程全留痕管理规范》AETC-STD-001 V1.0
  规则编号：前缀-序号；级别：M=强制 R=推荐 O=可选
  本文件是留痕、风险、审批、检查点、回退、复核六类规则的完整定义。
  阶段专属规则见 01~07 文件。
-->

## 1. 通用规则（GEN）

- GEN-001 [M] 本规范适用于在本项目内执行任何工作的 AI Agent 智能体，包括编码、
  审查、测试、部署、运维、文档等全部工作内容。
- GEN-002 [M] 规范分层：全局级（原则层）→ 项目级（执行层）。项目级规则只能收紧、
  不能放松全局级规则（单向棘轮原则）。
- GEN-003 [M] Agent 的一切行为以"可解释、可复核、可回退"为前提；三者冲突时，
  优先保证可回退，其次可复核，最后可解释。
- GEN-004 [M] 留痕记录、检查点、复核文件属于项目审计资产，删除、修改、伪造视为红线违规。
- GEN-005 [R] 推荐将 audit/traces/ 纳入版本控制；若项目不允许，须在 audit/config.json
  中声明 traces_in_git=false 并另行归档。
- GEN-006 [R] 会话开始时读取最近一次 TASK_CLOSE/BLOCKED 记录以恢复上下文，
  禁止凭记忆断言之前发生过什么。

## 2. 留痕规则（TRC）

- TRC-001 [M] 留痕介质：audit/traces/<session-id>.jsonl，一行一条 JSON 记录，
  会话内按 seq 递增。
- TRC-002 [M] 记录六要素：WHO（actor）/ WHEN（ts）/ WHY（intent 或 decision）/
  WHAT（event + summary + artifacts）/ HOW（skill/tool）/ RESULT（risk + status）。
- TRC-003 [M] 每条记录包含 prev_hash 与 hash，构成哈希链；hash 为去掉 hash 字段后
  记录的 SHA-256。写入由 audit_log.py 完成，禁止手工编辑已写入记录。
- TRC-004 [M] 留痕触发点：任务开始、计划确定、每个计划步骤完成、每个决策点、
  每次技能调用、每个 R2/R3 操作前后、每个检查点、每个审批门、每次阻塞、
  每次纠偏、任务结束。共 13 类事件（见 AGENTS.md 第三节）。
- TRC-005 [M] STEP_DONE 的 detail 必须包含三段：分析过程摘要 / 技能与工具使用 / 实际执行内容。
  缺任一段视为不合格记录（audit_check.py 会给出 WARN）。
- TRC-006 [R] 连续同类只读操作（如浏览多个文件）可合并为一条 STEP_DONE，但须列出涉及文件清单。
- TRC-007 [R] 单条 detail 控制在 500 字以内，超长内容写入附件（audit/reports/notes/）并在记录中引用路径。
- TRC-008 [M] 记录时间使用 ISO 8601 带时区（如 2026-08-22T20:15:00+08:00）。
- TRC-009 [M] 阶段字段 phase 取值：requirements/design/coding/testing/release/ops/retro。
- TRC-010 [O] 敏感信息（密钥、口令、个人数据）不得明文进入留痕，以 <REDACTED> 占位。

## 3. 风险分级规则（RSK）

- RSK-001 [M] 默认分级表见 AGENTS.md 第二节；不确定级别时从高认定。
- RSK-002 [M] 项目可在 audit/config.json 的 extra_r2/extra_r3 中追加操作模式（glob 或正则），
  不得将默认表中的 R2/R3 项降级。
- RSK-003 [M] R2 操作前必须：创建检查点（可选但推荐）→ RISK_PRE → 获得人工确认 → 执行 → RISK_POST。
- RSK-004 [M] R3 操作前必须：创建检查点（强制）→ RISK_PRE → GATE_REQUEST →
  GATE_RESULT(approved) → 执行 → RISK_POST → 提交复核。
- RSK-005 [M] 执行命令命中 config.json 中 deny_patterns 的，直接拒绝执行并记录 BLOCKED。
- RSK-006 [R] 批量操作（影响超过 10 个文件）无论内容，至少按 R2 处理。
- RSK-007 [R] 涉及网络出站（非包管理器源）的操作按 R2 起步。

## 4. 审批门规则（GAT）

- GAT-001 [M] 五个标准审批门：G1 需求确认 / G2 设计评审 / G3 编码自审 / G4 测试通过 /
  G5 发布审批。G5 属 R3。
- GAT-002 [M] GATE_REQUEST 必须写明：请求内容、风险级别、影响范围、回滚方式、
  不批准时的替代方案。缺项视为无效请求。
- GAT-003 [M] 审批决定记录为 GATE_RESULT，取值 approved / rejected / modified；
  modified 须附修改意见，Agent 按修改后方案执行。
- GAT-004 [M] 未获批准时：保持当前状态；不得执行请求中的动作；可提出替代方案再次请求。
- GAT-005 [M] 审批记录归档：G2/G5 的完整审批单使用 audit/templates/gate-approval.md
  填写并存入 audit/reviews/。
- GAT-006 [R] 支持平台原生权限机制的（如 OpenCode permission、Claude Code ask），
  优先使用原生机制发起审批，同时仍写 GATE_* 留痕。

## 5. 检查点规则（CKP）

- CKP-001 [M] 检查点命名：cp-<主题>-<yyyymmdd-hhmm> 或 cp-<序号>，全项目唯一。
- CKP-002 [M] 检查点创建时机：R3 操作前（强制）、R2 操作前（推荐）、
  阶段切换时（推荐）、批量修改前（强制，见 RSK-006）。
- CKP-003 [M] git 仓库内优先使用 git ref 快照（refs/aetc/cp/<name>，不污染分支历史）；
  非仓库目录使用 tar 归档至 audit/checkpoints/。
- CKP-004 [M] 每次创建检查点写 CHECKPOINT 事件，记录恢复命令。
- CKP-005 [M] 恢复检查点属于 R2 操作（恢复到 R3 操作前状态按 R2 执行即可，
  因为它是回退到安全态），但必须先留痕 RISK_PRE。
- CKP-006 [R] 检查点保留策略：最近 20 个或 30 天，清理前记录清单。

## 6. 回退规则（RLB）

- RLB-001 [M] 任何回退必须走"回退五步法"：停止 → 冻结 → 定位 → 恢复 → 记录。
- RLB-002 [M] 回退前先创建"回退前检查点"（保存问题现场，供复盘）。
- RLB-003 [M] 恢复后必须验证（构建/测试/关键路径抽查），验证结果写入记录。
- RLB-004 [M] 回退完成后填写 audit/templates/rollback-record.md 归档 audit/reviews/。
- RLB-005 [R] 回退原因分类：需求变更 / 实现缺陷 / 环境问题 / 误操作 / 其他。

## 7. 复核规则（REV）

- REV-001 [M] 复核范围：R2/R3 操作及其产出、五个人工审批门的产物（PRD、设计文档、
  代码变更、测试报告、发布单）、抽查的 R1 产出。
- REV-002 [M] 复核人角色：请求人不能自任复核人；Agent 产出的复核由人工复核人执行。
- REV-003 [M] 复核记录使用 audit/templates/review-checklist.md，结论：
  pass / pass-with-notes / fail（fail 触发回退或返工流程）。
- REV-004 [R] 抽审策略：每会话 R1 产出按不低于 10% 抽查；连续 3 次抽查无问题的
  Agent/模块可降频至 5%。
- REV-005 [R] 复核发现的共性问题应转化为新规则（进入 audit/rules/ 或全局级），
  形成闭环（见 S7 复盘阶段）。

## 8. 留痕三通道（技术实现）

- 通道T1 规则驱动留痕 [全平台基线]：Agent 按本规则调用 audit/tools/audit_log.py(.sh) 写入。
- 通道T2 钩子增强留痕 [支持平台]：Claude Code 通过 hooks（settings-hooks.json + audit_hook.py）
  自动捕获工具调用，写入 audit/traces/hooks/。T2 与 T1 互为印证，T2 缺席不免责。
- 通道T3 环境审计 [工程加固]：git pre-commit 钩子 / CI 流水线调用 audit_check.py
  校验哈希链完整性与事件合规性，校验失败阻断合入。
