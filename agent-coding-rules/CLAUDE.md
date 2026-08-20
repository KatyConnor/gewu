# CLAUDE.md — Claude Code 项目指令文件

> 本文件由 Claude Code 自动读取，作为编码行为的最高约束。

## 指令加载

在执行任何代码相关操作前，必须先读取以下文件：

1. `AGENTS.md` — 主规则文件（**必须完整读取**）
2. `rules/0X-<language>.md` — 对应语言的规则文件（编写该语言代码时读取）
3. `configs/` — 相关工具配置（格式化/lint 时参考）

## 行为约束

### 必须遵守

- 修改文件前先完整读取目标文件
- 只做最小必要变更，不重写整个文件
- 匹配项目现有代码风格
- 代码修改后运行 lint / typecheck / test 并报告结果
- 所有 Python 代码必须包含 Type Hint
- 所有 SQL 必须使用参数化查询
- 所有公共 API 必须有文档注释

### 禁止事项

- 禁止硬编码密钥、密码、Token
- 禁止使用 `eval()` / `exec()` / `os.system()` 处理用户输入
- 禁止 SQL 字符串拼接
- 禁止空 `except` / `catch` 块
- 禁止 `git push --force` / `git reset --hard` / `rm -rf` 未确认就执行
- 禁止修改 `main` / `master` 分支未经确认
- 禁止提交调试用的 `print` / `console.log`
- 禁止注释掉的代码残留

### 工作流程

1. **理解需求**：分析用户请求，确定涉及的语言和文件
2. **读取规则**：读取 `AGENTS.md` 和 `rules/` 下对应语言的规则
3. **检查现有代码**：读取目标文件，理解上下文和现有风格
4. **实施变更**：最小变更原则，只修改必要部分
5. **验证**：运行 lint / typecheck / test
6. **报告**：说明变更内容、原因、验证结果

## 语言规则索引

| 语言 | 规则文件 |
|------|----------|
| Python | `rules/01-python.md` |
| Java | `rules/02-java.md` |
| JS/TS | `rules/03-javascript.md` |
| Go | `rules/04-go.md` |
| Rust | `rules/05-rust.md` |
| C/C++ | `rules/06-cpp.md` |
| SQL | `rules/07-sql.md` |
| 数据库设计 | `rules/08-database.md` |
| NoSQL | `rules/09-nosql.md` |
| Code Review | `rules/10-code-review.md` |

## 完成检查清单

报告完成前逐项确认：

- [ ] 代码符合 AGENTS.md 命名/格式/结构要求
- [ ] 已运行 lint 且无 error
- [ ] 已运行类型检查（如适用）
- [ ] 已运行相关测试且通过
- [ ] 无硬编码密钥/密码
- [ ] 无调试输出残留
- [ ] 新增依赖已加入清单文件
- [ ] 公共 API 有文档注释
