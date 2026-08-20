# Agent 编码规范约束文件包

> 一套可被所有主流 AI Agent 编码工具（Claude Code / ZCode / OpenCode / Cursor / Cline / Windsurf / GitHub Copilot）直接导入的编码开发规范约束文件。

## 快速开始

### 1. 选择你的工具

将对应入口文件复制到项目根目录：

| 工具 | 入口文件 | 安装方式 |
|------|----------|----------|
| Claude Code | `CLAUDE.md` | 复制到项目根目录 |
| Cursor | `.cursorrules` | 复制到项目根目录 |
| Cline | `.clinerules` | 复制到项目根目录 |
| Windsurf | `.windsurfrules` | 复制到项目根目录 |
| GitHub Copilot | `copilot-instructions.md` | 重命名为 `.github/copilot-instructions.md` |
| ZCode | `zcode-rules.md` | 复制到项目根目录 |
| OpenCode | `opencode-rules.md` | 复制到项目根目录 |
| 通用 | `AGENTS.md` | 复制到项目根目录 |

### 2. 安装规则文件

```bash
# 将整个 rules/ 目录复制到项目根目录
cp -r rules/ /your-project/

# 将 configs/ 中的配置文件按需复制
cp configs/.editorconfig /your-project/
cp configs/.pre-commit-config.yaml /your-project/

# 按语言复制对应配置（按需）
cp configs/ruff.toml /your-project/           # Python 项目
cp configs/eslint.config.js /your-project/    # JS/TS 项目
cp configs/.prettierrc /your-project/         # JS/TS 项目
cp configs/golangci.yml /your-project/        # Go 项目
cp configs/clippy.toml /your-project/         # Rust 项目
```

### 3. 启用自动化工具

```bash
# 安装 pre-commit
pip install pre-commit
pre-commit install

# Python: 安装 ruff
pip install ruff

# JS/TS: 安装 eslint + prettier
npm install -D eslint typescript-eslint prettier

# Go: 安装 golangci-lint
go install github.com/golangci/golangci-lint/cmd/golangci-lint@latest

# Rust: clippy 已内置
rustup component add clippy
```

## 文件结构

```
agent-coding-rules/
├── AGENTS.md                      # 主规则文件（通用，所有工具读取）
├── CLAUDE.md                      # Claude Code 入口
├── .cursorrules                   # Cursor 入口
├── .clinerules                    # Cline 入口
├── .windsurfrules                 # Windsurf 入口
├── copilot-instructions.md        # GitHub Copilot 入口
├── zcode-rules.md                 # ZCode 入口
├── opencode-rules.md              # OpenCode 入口
├── rules/                         # 分语言详细规则
│   ├── 00-general.md              # 通用编码原则
│   ├── 01-python.md               # Python 规范
│   ├── 02-java.md                 # Java 规范
│   ├── 03-javascript.md           # JS/TS 规范
│   ├── 04-go.md                   # Go 规范
│   ├── 05-rust.md                 # Rust 规范
│   ├── 06-cpp.md                  # C/C++ 规范
│   ├── 07-sql.md                  # SQL 编写规范
│   ├── 08-database.md             # 数据库设计规范
│   ├── 09-nosql.md                # NoSQL 规范
│   └── 10-code-review.md          # Code Review 规范
├── configs/                       # 自动化工具配置
│   ├── .editorconfig              # 编辑器统一格式
│   ├── ruff.toml                  # Python lint + format
│   ├── eslint.config.js           # JS/TS lint
│   ├── .prettierrc               # JS/TS 格式化
│   ├── golangci.yml               # Go lint
│   ├── clippy.toml                # Rust lint
│   └── .pre-commit-config.yaml    # Git pre-commit hooks
└── README.md                      # 本文件
```

## 规则优先级

Agent 在处理代码时按以下优先级应用规则：

1. **项目级配置**（最高优先级）
   - `pyproject.toml` / `eslint.config.js` / `go.mod` 等项目已有配置
2. **AGENTS.md 主规则**
   - 通用编码约束、安全红线
3. **rules/ 分语言规则**
   - 各语言特定的命名、格式、结构规范
4. **configs/ 工具配置**
   - 自动化 lint / format / pre-commit 配置

## 约束执行机制

### Agent 行为约束

每个入口文件都包含以下约束机制：

1. **强制行为准则** — Agent 必须遵守的工作流程
2. **安全红线** — 不可违反的禁止事项
3. **命名规范** — 各语言的命名规则
4. **函数设计** — 行数/参数/嵌套限制
5. **错误处理** — 异常处理原则
6. **数据库操作** — 参数化查询等安全规则
7. **测试规范** — 测试覆盖率要求
8. **Commit 规范** — Conventional Commits 格式
9. **完成检查清单** — Agent 报告完成前的自检项

### 自动化工具保障

| 工具 | 覆盖语言 | 功能 |
|------|----------|------|
| `.editorconfig` | 全部 | 编辑器格式统一 |
| `ruff` | Python | lint + format |
| `eslint` | JS/TS | lint |
| `prettier` | JS/TS | format |
| `golangci-lint` | Go | lint |
| `clippy` | Rust | lint |
| `pre-commit` | 全部 | Git 提交前拦截 |
| `detect-secrets` | 全部 | 密钥泄露检测 |
| `bandit` | Python | 安全漏洞扫描 |

## 落地实施路线

### 第一阶段：工具先行（1-2 周）

- [ ] 复制入口文件和 `AGENTS.md` 到项目根目录
- [ ] 复制 `rules/` 目录到项目根目录
- [ ] 复制 `configs/` 中对应语言的配置文件
- [ ] 安装并启用 pre-commit hooks
- [ ] CI 流水线加入 lint + test 检查

### 第二阶段：流程保障（2-4 周）

- [ ] PR 模板规范化
- [ ] Code Review Checklist 上线
- [ ] 覆盖率门禁启用
- [ ] 安全扫描启用

### 第三阶段：规范覆盖（1-2 月）

- [ ] 各工具入口文件部署
- [ ] 分语言规则文件完善
- [ ] 团队培训与考核

### 第四阶段：持续运营（长期）

- [ ] Code Review 文化建设
- [ ] 定期规范评审与更新
- [ ] 自动化指标大盘
- [ ] 季度质量报告

## 支持的 Agent 工具

| 工具 | 入口文件 | 官方文档 |
|------|----------|----------|
| Claude Code | `CLAUDE.md` | 自动读取项目根目录 |
| ZCode (Z.ai) | `zcode-rules.md` | ZCode Agent 配置 |
| OpenCode | `opencode-rules.md` | OpenCode 配置 |
| Cursor | `.cursorrules` | Cursor Settings |
| Cline | `.clinerules` | Cline 扩展配置 |
| Windsurf | `.windsurfrules` | Windsurf AI 配置 |
| GitHub Copilot | `copilot-instructions.md` | `.github/copilot-instructions.md` |
| Aider | `AGENTS.md` | Aider convention file |
| 通用 | `AGENTS.md` | 任何支持 `AGENTS.md` 的工具 |

## 版本

- **版本号**：V1.0
- **更新日期**：2026-08-10
- **适用范围**：所有主流 AI Agent 编码工具
