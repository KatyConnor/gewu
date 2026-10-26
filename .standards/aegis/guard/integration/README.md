# AEGIS 集成层 —— 智能体集成实现（zcode / 自研）

本目录把《AEGIS V1.0》第六章的集成设计落成可运行代码，回答一个问题:
**护栏如何接进智能体，让模型事实上按规范执行。**

验证: `python3 test_integration.py`（9/9 全绿）；全流程演示 `python3 demo_agent.py`。

---

## 1. 两种形态的本质区别

| | 自研智能体 | zcode 类外部智能体 |
|---|---|---|
| 信任边界 | 自己的进程，可进程内拦截 | 不可改其内部，只能在**模型权限之外**设卡 |
| 集成位置 | SDK 进程内（防线二前置） | 仓库钩子 + CI + MCP + 平台审批（防线二/三外置） |
| 接线件 | `aegis_agent` SDK | `mcp_server.py` / `hooks/pre-commit` / `ci/aegis-gate.yml` |

## 2. 自研智能体: 进程内 SDK（推荐接法）

### 2.1 最小接入（约 15 行）

```python
from integration.aegis_agent import (GuardedWorkspace, StageController,
                                     AtlasBridge, build_system_prompt)

bridge = AtlasBridge('.aegis/atlas-events.jsonl')
ctrl   = StageController(issue='VUL-2026-0928-001', level='S3', bridge=bridge)
ws     = GuardedWorkspace(repo_dir, plan=fp, level='S3', bridge=bridge)

# ① 把 ws.write 挂成你智能体的写文件工具（接口不变: path + content）
# ② 把 build_system_prompt(issue, level, budget) 注入系统提示
# ③ 阶段推进走 ctrl.go('P1'...); 三单产出走 ctrl.accept_doc('RC', rc)
```

### 2.2 五个挂载点（demo_agent.py 即完整范例）

| 挂载点 | 组件 | 作用 |
|---|---|---|
| 任务启动 | `build_system_prompt()` | 守则+问题单+预算注入（意图层） |
| P1 只读 | `GuardedWorkspace(readonly=True)` | 写工具直接不可用（只读锁） |
| 阶段门 | `StageController.accept_doc()` | 三单 schema 不过 = 阶段未完成；连续 2 次安灯升级 |
| 工具层 | `GuardedWorkspace.write()` | **写-检-回滚-回流闭环（核心）**: 写入前对全量 diff 跑检查，BLOCK 则不落盘，违例以 ToolError 回流给模型 |
| 留痕 | `AtlasBridge.emit()` | x-fix.stage/guard/escalate 全程留痕 |

### 2.3 核心闭环的运行证据（demo_agent.py 摘录）

```
第 1 轮: 2 次写入被护栏拒绝，工具错误回流给模型:
  [AP002] @token.py 疑似硬编码凭证（禁止行为 11）
  [AP001] @token.py 空捕获吞异常（铁律 L2：禁止压制表象）
  [FROZEN_TOUCH] @.github/workflows/ci.yml 触碰冻结清单资产
第 2 轮收敛: 实际 diff {files: 2, added: 22, removed: 1}
门二 定向验证: 红转绿 → 绿（PASS）
```

模型收到的是**工具错误**，不是劝告——它想绕过，别的路走不通。
红基线用例先于修复代码落盘（P3a），事后改测试迁就代码会与存证冲突。

## 3. zcode 智能体: 三接法（可组合，B 必装）

### 接法 A · MCP 自查（体验层）

```bash
python3 integration/mcp_server.py     # stdio / ndjson JSON-RPC 2.0
```

工具: `aegis_check`（diff 护栏检查，返回结构化报告）、`aegis_docs`（三单校验）。
客户端配置（zcode 支持 MCP 时）:

```json
{ "mcpServers": { "aegis-guard": {
    "command": "python3", "args": ["/abs/path/integration/mcp_server.py"] } } }
```

`python3 integration/mcp_client_demo.py` 可验证通道（合规 PASS / 违规 BLOCK 双向）。
注意: 自查决定体验，**不能替代终审**——模型可以不调用 MCP，纪律必须由 B/C 保证。

### 接法 B · 仓库钩子 + CI 终审（纪律层，必装）

- `hooks/pre-commit` → 拷贝到 `.git/hooks/` 并 `chmod +x`:
  提交时对 staged diff 跑护栏，exit 1 拦截提交。**与智能体无关**，
  zcode 写仓库的任何路径都被同一道门管住;
- `ci/aegis-gate.yml` → PR 门禁: 门零三单校验（修复 PR 必须携带
  `.aegis/FP-current.yaml`）→ 门一护栏终检 → 门二/三接入项目测试与复扫。

### 接法 C · 平台侧（治理层，ATLAS 对接）

按 ATLAS V1.0 三通道:
- **指令下发**: task.dispatch 的 payload 携带问题单号、S 级与预算
  （Agent 侧即 `build_system_prompt` 的输入）;
- **事件上行**: 每次拦截/门禁结果经 `AtlasBridge` 上报
  `x-fix.guard`（warning）、`x-fix.escalate`（critical，触发人工接管）;
- **决策回路**: GATE 审批矩阵（`StageController.gate()`）对应 ATLAS
  D0-D3 决策工单; `--allow-frozen` 冻结例外只允许人工通道传入。

### 组合建议

```
zcode:  B（仓库钩子+CI，必装） + A（MCP，可装） + C（平台化后）
自研:   SDK 进程内（2.1） + CI 终审（复用 B 的 yml）
```

## 4. 为什么模型最终会按规范执行（机制回顾)

| 机制 | 层 | 决定 | 失效兜底 |
|---|---|---|---|
| 守则+问题单注入 | 提示词 | 想不想 | 护栏/CI |
| 三单 schema 阶段门票 | 产出 | 能否推进 | 控制器不放行 |
| 工具拒绝反馈闭环 | 行为 | 能否做成 | 写入不落盘 + ToolError 回流 |
| CI 独立复跑 + ATLAS 留痕 | 结果 | 算不算数 | 人工 + 审计 |

合规率由架构决定（下限），一次通过率由模型能力决定（效率）。
护栏运行在模型权限之外: 模型无法关闭、无法跳过、无法说服。

## 5. 目录清单

```
integration/
├── aegis_agent/          SDK: guarded_tools / stage_controller / promptpack / atlas_bridge
├── demo_agent.py         自研智能体全流程演示（含 --stubborn 安灯演示）
├── demo_repo/            迷你仓库 fixture（缺陷代码 + 冒烟测试 + 冻结资产）
├── mcp_server.py         MCP 服务器（接法 A）
├── mcp_client_demo.py    MCP 通道验证客户端
├── hooks/pre-commit      仓库钩子（接法 B-1）
├── ci/aegis-gate.yml     CI PR 门禁（接法 B-2）
└── test_integration.py   回归测试（9 项）
```
