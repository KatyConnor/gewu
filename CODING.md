<!--
  STD-HUB-001 统一调度体系说明：
  本文件是编码规范（CODING-STD-001）在统一调度体系中的入口文件。
  由根目录 AGENTS.md（统一调度器）按触发面路由加载：编码/实现/修复/重构实施任务时读取。
  规范本体目录（coding-rules/）按根相对路径引用：coding-rules/rules/（分语言规则）与
  coding-rules/configs/（自动化工具链配置）。
  若单独安装本规范（不使用统一调度），将本文件改名 AGENTS.md、coding-rules/ 平铺到项目根即可独立工作。

  【收编声明】本规范 rules/10-code-review.md 为 2026-08-10 历史简化版代码审查规则；
  完整版代码审查以 CR-RULES V1.0（code-review/RULES.md）为准——编码审查/提交门禁
  一律执行 CR 的 P0/P1 项，rules/10 仅作背景参考不作为门禁依据。
-->
# CODING — AI Agent 编码开发约束规范（CODING-STD-001）

> 本文件是所有 AI Agent 智能体编码行为的最高约束（STD-HUB 第七套外叠加约束，与 UI-STD-001 同型）。
> Agent 在执行任何代码生成、修改操作前，必须先读取并遵守本文件及 `coding-rules/rules/` 目录下的分语言规则。
> **叠加语义**：可与 LPU/LR/SDLC/OPS 并存（LR R4 单元实施、SDLC 04 开发、OPS 根治轨修复均叠加本规范）；
> 产出代码后先过 UI 自检（界面类），再过 CR 质量门（P0/P1）——三层时序详见调度器接力协议。
> 适用工具：Claude Code / ZCode / OpenCode / Cursor / Cline / Windsurf / GitHub Copilot / Aider 等所有主流 Agent 编码工具。

---

## 0. 强制行为准则

### 0.1 执行优先级

Agent 必须按以下优先级处理规则冲突：

1. **安全红线**（本文件 §1）— 不可违反，违反即终止
2. **项目级配置**（项目根目录 `.editorconfig` / `pyproject.toml` / `eslint.config.js` 等）— 优先于本文件
3. **本文件 AGENTS.md** — 通用约束
4. **`coding-rules/rules/` 分语言规则** — 语言级约束
5. **`coding-rules/configs/` 工具配置** — 自动化工具链配置

### 0.2 工作流程约束

- **先读后写**：修改任何文件前，必须先完整读取目标文件内容，理解上下文后再修改
- **最小变更**：只做必要的最小修改，不重写整个文件，不引入无关变更
- **匹配现有风格**：如果现有代码风格与本规范冲突，优先匹配现有风格，并在 PR 中备注
- **验证后报告**：代码修改后必须运行相关 lint / typecheck / test，通过后再报告完成
- **禁止猜测**：不确定的 API、类型、行为，必须查证后再使用，不可猜测

---

## 1. 安全红线（不可违反）

### 1.1 禁止事项

- **禁止** 将密钥、Token、密码硬编码到源代码中
- **禁止** 在日志中输出敏感信息（密码、Token、身份证号、手机号）
- **禁止** 使用 `eval()`、`exec()`、`os.system()` 处理用户输入
- **禁止** 构建 SQL 字符串拼接查询（必须使用参数化查询）
- **禁止** 在生产代码中使用 `console.log` / `print` 调试语句（必须使用 logger）
- **禁止** 忽略异常（空 `except` / `catch` 块必须有注释说明原因）
- **禁止** 引入未经审计的第三方依赖（需检查 license 兼容性）
- **禁止** 修改 `.env` / 密钥文件 / CI-CD 配置文件，除非用户明确要求

### 1.2 文件操作红线

- **禁止** 删除以下文件类型：`.git/`、`*.lock`、`*.lockb`、`node_modules/`、`.env*`
- **禁止** 覆盖他人未提交的修改（`git stash` 前必须确认）
- **禁止** 执行 `git push --force` / `git reset --hard` / `rm -rf` 而不询问用户
- **禁止** 修改 `main` / `master` / `production` 分支而未经用户确认

---

## 2. 通用编码规范

### 2.1 命名规范总表

| 语言 | 变量/函数 | 类/类型 | 常量 | 文件名 | 包/模块 |
|------|-----------|---------|------|--------|---------|
| Python | snake_case | PascalCase | UPPER_SNAKE | snake_case.py | snake_case |
| Java | camelCase | PascalCase | UPPER_SNAKE | PascalCase.java |全小写 |
| JS/TS | camelCase | PascalCase | UPPER_SNAKE | kebab-case / PascalCase | kebab-case |
| Go | camelCase(导出Pascal) | PascalCase | PascalCase / UPPER | snake_case.go | 单词小写 |
| Rust | snake_case | PascalCase | UPPER_SNAKE | snake_case.rs | snake_case |
| C/C++ | snake_case | PascalCase | UPPER_SNAKE | snake_case.cpp | — |

### 2.2 函数设计

- **单一职责**：一个函数只做一件事，超过 50 行需评估是否拆分
- **参数数量**：超过 4 个参数必须使用参数对象/结构体
- **命名动宾结构**：函数名用动词+名词（`get_user` / `calculate_total` / `send_email`）
- **禁止布尔参数反模式**：`process(data, true, false, true)` — 用枚举或拆分为独立函数
- **提前返回**：使用 Guard Clause 替代深层嵌套

```python
# DO
def get_user(user_id: int) -> User | None:
    if user_id <= 0:
        return None
    return db.query(User, user_id)

# DON'T
def get_user(user_id):
    if user_id > 0:
        return db.query(User, user_id)
    else:
        return None
```

### 2.3 注释与文档

- **代码自解释优先**：好的命名 > 注释。注释解释"为什么"，不解释"是什么"
- **公共 API 必须有文档注释**：使用各语言的文档注释格式（docstring / JSDoc / rustdoc / JavaDoc）
- **TODO 格式统一**：`// TODO(作者): 描述 [ticket-123]` 或 `# TODO(@name): 描述`
- **禁止注释掉的代码**：用版本控制管理历史代码，不要留在文件中
- **变更说明写 commit message**：不写"修改了某文件"这种无信息注释

### 2.4 错误处理

- **显式错误处理**：不可忽略错误/异常。Python 的 `except` 块、Go 的 `err` 返回值、Rust 的 `Result` 必须处理
- **错误传播**：底层错误向上传播时必须保留原始上下文（Python: `raise ... from err`，Go: `fmt.Errorf("...: %w", err)`，Rust: `.context()`）
- **错误分类**：区分业务错误（可预期）与系统错误（不可预期），使用不同的异常/错误类型
- **快速失败**：输入校验在入口处完成，不合法输入立即返回，不深入执行
- **不吞异常**：

```python
# DON'T
try:
    result = do_something()
except Exception:
    pass  # 禁止空 except

# DO
try:
    result = do_something()
except NetworkError as e:
    logger.warning(f"Network error, retrying: {e}")
    result = retry()
except Exception as e:
    logger.error(f"Unexpected error: {e}", exc_info=True)
    raise
```

### 2.5 版本控制规范

- **Commit Message**：遵循 Conventional Commits
  ```
  <type>(<scope>): <subject>
  
  <body>
  
  <footer>
  ```
  type: `feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `chore` / `ci`
- **分支模型**：`main` / `develop` / `feature/*` / `fix/*` / `hotfix/*` / `release/*`
- **PR 要求**：必须关联 issue、通过 CI、至少 1 人 review、描述变更内容与影响
- **禁止大 PR**：单 PR 变更不超过 1000 行（不含自动生成代码），大变更拆分为多个 PR

---

## 3. 项目结构规范

### 3.1 通用目录结构

```
project-root/
├── src/              # 源代码（不放在根目录）
├── tests/            # 测试代码
├── docs/             # 文档
├── scripts/          # 构建/部署脚本
├── coding-rules/configs/  # 自动化工具链配置
├── .editorconfig     # 编辑器配置（必须存在）
├── .gitignore
├── README.md         # 项目说明（必须存在）
├── CHANGELOG.md      # 变更日志
└── <lang-specific>   # 语言特定文件（pyproject.toml / package.json / go.mod 等）
```

### 3.2 文件大小限制

| 类型 | 最大行数 | 超限处理 |
|------|----------|----------|
| 单个源文件 | 500 行 | 拆分为模块 |
| 单个函数 | 50 行 | 拆分为子函数 |
| 单个类 | 300 行 | 评估是否拆分职责 |
| 单个测试文件 | 300 行 | 按功能拆分 |

### 3.3 依赖管理

- **锁定文件必须提交**：`requirements.txt` + `requirements.lock` / `package-lock.json` / `go.sum` / `Cargo.lock`
- **禁止直接依赖子依赖**：不绕过包管理器直接引用 `node_modules/` 内部包
- **版本范围**：生产依赖锁定到具体版本，开发依赖可用 `^` / `~`
- **安全审计**：定期运行 `npm audit` / `pip-audit` / `cargo audit` / `govulncheck`

---

## 4. 分语言规则索引

Agent 在编写特定语言代码时，**必须先读取对应规则文件**：

| 语言 | 规则文件 | 适用场景 |
|------|----------|----------|
| Python | `coding-rules/rules/01-python.md` | 脚本/后端/数据/AI |
| Java | `coding-rules/rules/02-java.md` | 企业后端/Android |
| JavaScript/TypeScript | `coding-rules/rules/03-javascript.md` | 前端/Node.js |
| Go | `coding-rules/rules/04-go.md` | 后端/云原生/CLI |
| Rust | `coding-rules/rules/05-rust.md` | 系统/高性能/CLI |
| C/C++ | `coding-rules/rules/06-cpp.md` | 系统/嵌入式/底层 |
| SQL | `coding-rules/rules/07-sql.md` | 数据库查询 |
| 数据库设计 | `coding-rules/rules/08-database.md` | 表/索引/约束设计 |
| NoSQL | `coding-rules/rules/09-nosql.md` | MongoDB/Redis/ES |
| Code Review | `coding-rules/rules/10-code-review.md` | 代码审查 |

### 执行规则

当用户要求编写某语言的代码时，Agent 必须执行以下流程：

1. 识别目标语言
2. 读取 `coding-rules/rules/0X-<language>.md` 规则文件
3. 读取 `coding-rules/configs/` 目录下对应的工具配置（如有）
4. 按规则编写代码
5. 运行 lint / format / test 验证
6. 报告结果

---

## 5. 数据库操作规范

### 5.1 硬性规则

- **必须使用参数化查询**：禁止字符串拼接 SQL
- **必须显式指定列名**：禁止 `SELECT *`（调试/临时查询除外）
- **必须有索引支撑**：WHERE / JOIN / ORDER BY 涉及的列必须有索引
- **必须控制返回行数**：查询必须带 LIMIT，禁止无限制全表扫描
- **必须使用事务**：多行写入操作必须包裹在事务中
- **连接必须释放**：数据库连接使用后必须关闭（使用连接池 / context manager）

### 5.2 命名规范

- 表名：`snake_case`，复数形式（`users` / `orders` / `order_items`）
- 列名：`snake_case`（`user_id` / `created_at` / `is_active`）
- 索引名：`idx_<table>_<columns>`（`idx_users_email` / `idx_orders_user_id_status`）
- 外键名：`fk_<table>_<ref_table>`（`fk_orders_users`）
- 约束名：`uk_<table>_<columns>` 唯一约束 / `ck_<table>_<desc>` 检查约束

### 5.3 详细规则

数据库设计的详细规则见 `coding-rules/rules/08-database.md`，SQL 编写规则见 `coding-rules/rules/07-sql.md`，NoSQL 见 `coding-rules/rules/09-nosql.md`。

---

## 6. 测试规范

### 6.1 测试要求

- **新增功能必须带测试**：无测试的代码不允许合并
- **Bug 修复必须带回归测试**：先写复现测试，再修复
- **覆盖率门禁**：核心模块 ≥ 80%，工具类 ≥ 90%，整体 ≥ 60%
- **测试命名**：`test_<功能>_<条件>_<预期>`（`test_get_user_when_not_exist_returns_none`）

### 6.2 测试分层

| 层级 | 占比 | 内容 |
|------|------|------|
| 单元测试 | 70% | 函数/类级别，Mock 外部依赖 |
| 集成测试 | 20% | 模块间交互，真实数据库/服务 |
| 端到端测试 | 10% | 完整流程，模拟用户操作 |

### 6.3 测试代码规范

- **AAA 模式**：Arrange（准备）→ Act（执行）→ Assert（断言），每段用空行分隔
- **一个测试一个断言原则**：尽量每个测试只验证一个行为
- **测试不依赖执行顺序**：每个测试独立，可单独运行
- **测试数据自管理**：测试创建自己的数据，测试后清理（使用 fixture / setup-teardown）

---

## 7. Agent 输出规范

### 7.1 代码生成输出格式

Agent 生成代码时必须：

1. **说明文件路径**：在代码块前注明目标文件路径
2. **完整文件或精确 diff**：要么给完整文件，要么给明确标注的 diff
3. **说明变更原因**：每次变更说明为什么改、改了什么、有什么影响
4. **标注 TODO**：未完成部分明确标注 `// TODO: 描述`
5. **提供导入语句**：新增函数/类必须包含完整的 import / use / require 语句

### 7.2 禁止行为

- **禁止生成没有类型标注的代码**（动态类型语言除外，但 Python 必须有 Type Hint）
- **禁止生成未导入依赖的代码**（`import` 必须完整）
- **禁止生成含硬编码路径的代码**（使用配置或环境变量）
- **禁止生成含魔法数字的代码**（数字必须有命名常量或注释说明）
- **禁止生成过度嵌套的代码**（超过 3 层嵌套必须重构）

### 7.3 完成检查清单

Agent 报告任务完成前，必须逐项确认：

- [ ] 代码符合本规范的命名/格式/结构要求
- [ ] 已运行 lint 工具且无 error 级别问题
- [ ] 已运行类型检查且通过（如适用）
- [ ] 已运行相关测试且全部通过
- [ ] 无硬编码密钥/密码/路径
- [ ] 无调试输出语句残留
- [ ] 新增依赖已加入依赖清单文件
- [ ] 公共 API 有文档注释
- [ ] 变更说明已写入 commit message

---

## 8. 与工具配置集成

### 8.1 自动化工具链

本规范包提供以下自动化工具配置（位于 `coding-rules/configs/` 目录）：

| 配置文件 | 功能 | 适用语言 |
|----------|------|----------|
| `.editorconfig` | 编辑器基础格式统一 | 全部 |
| `ruff.toml` | Python lint + format | Python |
| `eslint.config.js` | JS/TS lint | JavaScript/TypeScript |
| `.prettierrc` | JS/TS 格式化 | JavaScript/TypeScript |
| `golangci.yml` | Go lint | Go |
| `.pre-commit-config.yaml` | Git pre-commit hooks | 全部 |

### 8.2 工具集成优先级

Agent 在处理代码格式时应：

1. 优先使用项目已有的工具配置
2. 其次使用 `coding-rules/configs/` 目录下的配置模板
3. 最后回退到本 AGENTS.md 中的规则

---

*本文件版本：V1.0 | 更新日期：2026-08-10 | 适用所有主流 AI Agent 编码工具*
