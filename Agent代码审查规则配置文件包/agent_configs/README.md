# Agent 代码审查规则集成使用说明

## 目录结构

```
agent_configs/
├── global-code-review-rules.json    # 全局配置文件（所有项目通用）
├── project-config-template.json     # 项目级配置模板（按需复制修改）
├── .cursorrules                     # Cursor 简明配置
├── .cursor/
│   └── rules/
│       └── project-review.jsonc     # Cursor 项目级JSON配置
├── .claude/
│   └── commands/
│       └── code-review.md           # Claude Code 审查指令
└── .github/
    ├── copilot-instructions.md      # GitHub Copilot 配置
    └── workflows/
        └── code-review.yml          # GitHub Actions CI/CD集成
```

## 快速开始

### 方式一：全局配置（推荐新项目使用）

1. 将 `global-code-review-rules.json` 复制到Agent全局配置目录：
   - **Cursor**: `~/.cursor/rules/global-code-review-rules.json`
   - **Claude Code**: `~/.claude/commands/code-review.md`（使用对应的md格式）
   - **GitHub Copilot**: `~/.github/copilot-instructions.md`

2. 在任何项目中触发代码审查即可自动加载全局规则。

### 方式二：项目级配置（推荐定制化项目使用）

1. 将 `agent_configs/` 目录下的对应文件复制到项目根目录：
   ```bash
   # Cursor 项目
   cp .cursorrules /your-project/
   cp -r .cursor/ /your-project/

   # Claude Code 项目
   cp -r .claude/ /your-project/

   # GitHub Copilot 项目
   cp -r .github/ /your-project/
   ```

2. 编辑 `project-review.jsonc`，根据项目技术栈启用/禁用语言规则。

3. 在 `overrides` 中自定义规则覆盖。

4. 提交配置文件到版本控制，确保团队共享。

### 方式三：混合配置（推荐大型团队使用）

1. 全局配置作为基准（方式一）
2. 项目级配置覆盖特定规则（方式二）
3. 项目级配置自动继承全局配置，`overrides` 字段用于差异化定制

## 配置加载优先级

| 优先级 | 配置来源 | 典型路径 |
|--------|---------|---------|
| 1（最高） | 项目级配置 | `.cursor/rules/` 或 `.claude/commands/` |
| 2 | 环境变量 | `CODE_REVIEW_RULES_PATH` |
| 3 | 全局配置 | `~/.cursor/rules/` 或 `~/.claude/commands/` |
| 4（最低） | 内置默认规则 | 规范文档内置 |

## 规则编号说明

| 前缀 | 含义 | 示例 |
|------|------|------|
| L1-* | 通用规则（所有语言） | L1-NAME-001 |
| L2-{LANG}-* | 语言专项规则 | L2-PY-001 |
| L3-SEC-* | 安全审查规则 | L3-SEC-001 |
| L3-ARCH-* | 架构审查规则 | L3-ARCH-001 |
| L3-PERF-* | 性能审查规则 | L3-PERF-001 |
| L3-OBS-* | 可观测性规则 | L3-OBS-001 |
| L3-DOC-* | 文档审查规则 | L3-DOC-001 |
| CUSTOM-* | 自定义规则 | CUSTOM-001 |

## 严重级别说明

| 级别 | 含义 | CI/CD行为 |
|------|------|-----------|
| P0 | 阻断级 | 必须修复才能合入 |
| P1 | 严重 | 强烈建议修复，默认阻断 |
| P2 | 重要 | 建议修复，默认告警 |
| P3 | 建议 | 可选修复，仅提示 |

## 自定义规则

在项目配置文件的 `custom_rules` 数组中添加：

```json
{
  "id": "CUSTOM-001",
  "title": "规则标题",
  "description": "规则描述",
  "severity": "P2",
  "category": "custom",
  "languages": ["python", "typescript"],
  "check_points": ["检查点1", "检查点2"]
}
```

## 覆盖规则

在 `overrides` 数组中添加：

```json
// 修改严重级别
{
  "id": "L1-LOGIC-004",
  "action": "modify",
  "severity": "P3",
  "reason": "遗留项目允许较长函数"
}

// 禁用规则
{
  "id": "L1-COMMENT-008",
  "action": "disable",
  "reason": "项目不要求文件头注释"
}
```
