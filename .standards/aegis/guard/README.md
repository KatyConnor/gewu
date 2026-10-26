# AEGIS Guard —— 修复护栏检查器原型与智能体集成指南

配套规范：《智能体代码问题修复方法与标准规范（AEGIS V1.0）》
本工具是规范第六章（行为约束与护栏机制）的可运行实现，也是把规范「集成到智能体」的粘合件。

**核心立场：规范执行不依赖模型自觉，依赖架构。** 模型的「自觉」只决定一次通过率（效率），
护栏的「拒绝」决定合规率（下限）。护栏必须运行在模型权限之外——模型无法关闭它、
无法跳过它、也无法说服它。

---

## 1. 快速开始

```bash
# 双场景演示：违规修复应 BLOCK，合规修复应 PASS
python3 aegis_guard.py demo

# 对任意 diff 做检查（配合方案单；diff 可来自 git diff 管道）
git diff main...fix-branch | python3 aegis_guard.py check - \
    --plan docs/FP-003.yaml --level S2

# 三单契约校验（阶段门用）
python3 aegis_guard.py docs --rc docs/RC-003.yaml --fp docs/FP-003.yaml --vr docs/VR-003.yaml
```

退出码即门禁信号：`0=PASS  1=BLOCK  2=仅警告  3=配置错误`。

## 2. 检查器清单与规范条款映射

| 检查器 | 拦截对象 | 规范条款 | 默认动作 |
|---|---|---|---|
| DOC_SCHEMA | 三单缺失字段/枚举越界 | 6.4 | BLOCK |
| L5_REDLINE | 无红基线且无复扫替代证据 | L5 / 5.3 | BLOCK |
| FROZEN_TOUCH | 触碰 CI 配置、密钥、迁移脚本等冻结资产 | 6.3 | BLOCK（人工 --allow-frozen 降级留痕） |
| ASSERT_REMOVED | 测试断言删除/改写 | L3 | BLOCK |
| TEST_SKIPPED | 新增 skip/xfail/only | L3 | BLOCK |
| TEST_TOUCHED | 无认定记录修改既有测试 | 9.1 | WARN |
| AP001-AP005 | 吞异常、硬编码凭证、注释代码、TODO 残留、调试输出 | L2 / 3.2 | block 或 warn |
| BUDGET_EXCEED / _NEAR / _DEPS | 文件数/行数/函数数/依赖数超预算 | 6.2 | BLOCK（≥90% 先 WARN 收尾） |
| PLAN_DEVIATION | 实际 diff 超出方案单变更清单 | L4 / L8 | BLOCK |

配置（预算表、冻结清单、禁改模式库）在 `config.default.yaml`，按仓库覆盖、按规范 8.2 反哺回路持续校准。

## 3. 集成架构：三层防线的落位

```
┌─────────────────────────────────────────────────────────────┐
│  修复任务: (问题单, S级, 预算, 判例)                          │
│      │                                                       │
│      ▼                                                       │
│  防线一 意图层  ……  系统提示注入守则（附录B）+ 结构化产出要求  │
│      │   影响「想做什么」，可被绕过，最弱                      │
│      ▼                                                       │
│  防线二 行为层  ……  ★ 本工具的主战场 ★                       │
│   · 工具调用拦截: 每次写文件后跑 check，BLOCK 即回滚+反馈      │
│   · 阶段门: 三单 schema 校验不过，禁止进入下一阶段            │
│      │   模型无法绕过：护栏在平台侧运行，权限高于模型          │
│      ▼                                                       │
│  防线三 结果层  ……  pre-commit / CI 流水线独立复跑同款检查    │
│      │   上下文之外的最终裁决 + ATLAS 留痕上报                │
│      ▼                                                       │
│  主干分支                                                     │
└─────────────────────────────────────────────────────────────┘
```

## 4. 四个挂载点

### 4.1 任务启动：注入（规范 6.5 / 附录 B）

把守则片段注入系统提示或规则文件（AGENTS.md / .cursorrules），同时把问题单
结构化注入任务上下文。注入内容 = 铁律摘要 + 本单级别 + 本单预算 + 检索到的
相似判例（知识回路）。**注意：注入只影响意图，必须配合 4.2 才有约束力。**

### 4.2 工具调用拦截（核心粘合点）

包装智能体的「写文件」工具：每次写入落盘后，对本次增量 diff 跑护栏；
BLOCK 则由平台侧回滚该次写入，并把违规报告**作为工具错误**返回给模型。
模型收到的不是「规范说教」，而是一次具体的失败反馈——它必须修正后重试。
这是让模型「按规范执行」的真正机制：不是它想遵守，是别的路走不通。

```python
# 平台侧工具包装器（伪代码，自研 Agent 直接照此接线）
def guarded_write(path, content, session):
    diff = session.staged_incremental_diff()          # 本次增量
    report = aegis_guard.check(diff, plan=session.fp, level=session.issue.level)
    if report.verdict == 'BLOCK':
        session.revert_last_write(path)               # 平台回滚，模型无感但无效
        raise ToolError(report.text())                # 违规报告回流 → 模型自我修正
    disk.write(path, content)
    atlas.emit('x-fix.guard', report.json())          # 拦截事件留痕（L2 级）
```

要点：
- **回滚先行**：违例内容不能留在工作区，否则模型会在脏状态上继续叠加；
- **反馈可执行**：报告中的「处置」字段直接告诉模型下一步动作（拆单/披露/走审批）；
- **升级出口**：同一问题连续 2 轮 BLOCK → 触发安灯升级（5.4），停止重试转人工。

### 4.3 阶段门：三单契约（规范 6.4）

外层控制器用 `docs` 子命令卡状态迁移：

```python
STAGES = {'P1': 'RC', 'P2': 'FP', 'P4': 'VR'}
def advance(stage, doc_path):
    kind = STAGES.get(stage)
    if kind and not aegis_guard.validate_doc(kind, doc_path).ok:
        raise StageNotComplete(f'{kind} 不合 schema，阶段未完成（规范 6.4）')
    # schema 合规才放行；P2→P3 之间另有 GATE 人工审批（联动 ATLAS D 级）
```

模型的产出必须是合规 YAML——解码层可用 JSON Schema 约束 + 校验失败自动重试。

### 4.4 pre-commit / CI 门禁（防线三）

```yaml
# .github/workflows/fix-gate.yml（节选）
- name: AEGIS Guard
  run: |
    git diff ${{ github.event.pull_request.base.sha }} > /tmp/pr.diff
    python3 aegis_guard.py check /tmp/pr.diff --plan .aegis/FP-current.yaml \
      --level ${{ vars.AEGIS_LEVEL }} --strict
```

`--strict` 使警告也失败。CI 复跑与模型侧相同的检查，防止模型侧被绕过或上下文被污染；
两份报告一并按 ATLAS L2 留痕，供事后审计比对。

## 5. 不同智能体形态的接法

| 形态 | 接法 |
|---|---|
| **自研控制器型**（zcode 类） | 完整接线：4.1 注入 + 4.2 工具包装 + 4.3 阶段门 + 4.4 CI。控制器在平台侧，模型只看到工具与反馈 |
| **hooks 型**（Claude Code 等） | 用 hooks 机制挂本工具：PostToolUse 匹配 Write/Edit，命令非零退出（exit 1）即阻断并把报告回喂模型；Stop/PreCommit 再挂一次兜底 |
| **规则文件型**（Cursor / Codex / AGENTS.md） | 只能做 4.1 注入 + pre-commit 兜底；建议平台侧再部署 CI 门禁补齐防线三 |
| **MCP 工具型** | 把 check/docs 封装为 MCP 工具供模型自查（快回路），平台侧仍强制服务端调用做终审（防「模型只查不遵」） |

hooks 配置示例（Claude Code 语义：exit 2 = 阻断 + stderr 回喂模型）：

```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "Write|Edit",
      "hooks": [{"type": "command",
                 "command": "python3 /path/aegis_guard.py check --staged --plan .aegis/FP-current.yaml"}]
    }]
  }
}
```

## 6. 让模型按方法论执行：机制设计小结

模型「遵守规范」由四个机制叠加保证，缺一不可：

1. **注入（意图）**：守则 + 问题单 + 判例进上下文——决定「想不想」；
2. **产出契约（阶段）**：三单 schema 是阶段门票——决定「能不能推进」；
3. **工具拒绝（行为）**：违规即回滚 + 工具错误回流——决定「能不能做成」；
4. **独立裁决（结果）**：CI 复跑 + ATLAS 留痕——决定「算不算数」。

红绿基线在此框架下有机制意义：红基线证据先于修复代码存档，验证单的
`red_case` 字段与之比对，「改测试迁就代码」在证据层面无法自洽——
这正是对 reward hacking 的结构性反制，而非提示词层面的恳求。

## 7. 与 ATLAS V1.0 对接

- 每次拦截：上报 `x-fix.guard` 事件（severity 按违例级别），工件挂载 JSON 报告；
- GATE 例外（`--allow-frozen`）：只允许人工通道使用，走 ATLAS 决策工单（D2/D3），
  模型侧不可传入该参数；
- 安灯升级：上报 `x-fix.escalate`（critical），触发人工接管流程。

## 8. 原型边界（已知不做的）

- diff 解析只支持 unified diff（`git diff` 输出），不解析二进制与重命名检测；
- 函数计数按行正则启发，不构建 AST——生产化可替换为 tree-sitter；
- 回归选择集（7.2）不在本工具范围，属于 CI 侧测试选择器职责；
- 禁改模式库初始仅 5 条，按反哺回路扩充（规范 8.2）。

## 9. 集成层（integration/）

智能体集成已落地为可运行代码，见 `integration/README.md`:
自研智能体走进程内 SDK（`aegis_agent`，写-检-回滚-回流闭环 + P0-P6 阶段门），
zcode 类外部智能体走 MCP 自查 / pre-commit + CI 终审 / ATLAS 平台侧三接法。
回归验证: `python3 integration/test_integration.py`（9/9 全绿）。
