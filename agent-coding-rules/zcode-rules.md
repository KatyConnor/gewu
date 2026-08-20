# ZCode 编码约束规则

# 本文件供 ZCode Agent 读取。ZCode 在执行编码任务时必须遵守以下规则。
# 完整规则见 AGENTS.md，分语言规则见 rules/ 目录。

## 指令优先级

1. 项目级配置 (.editorconfig / pyproject.toml / eslint.config.js)
2. AGENTS.md 主规则文件
3. rules/ 分语言规则文件
4. configs/ 工具配置

## 核心行为

### 文件操作

- 修改前先读取目标文件完整内容
- 最小变更，不重写整个文件
- 不引入无关变更
- 禁止修改 .env / .git / lock 文件未经确认

### 安全红线

- 禁止硬编码密钥/密码/Token
- 禁止 eval/exec/os.system 处理用户输入
- 禁止 SQL 字符串拼接
- 禁止空 except/catch 块
- 禁止 print/console.log 调试残留
- 禁止注释掉的代码

### 编码规范

- 命名: 见 AGENTS.md §2.1 命名规范总表
- 函数: ≤50行, ≤4参数, 提前返回, 单一职责
- 错误: 显式处理, 保留上下文, 区分业务/系统错误
- 注释: 代码自解释优先, 公共API必须有文档

### 数据库

- 参数化查询
- 禁止 SELECT *
- 必须带 LIMIT
- 多行写入用事务
- 连接用后释放

### 测试

- 新功能必带测试
- Bug修复先写复现测试
- AAA模式
- 核心模块覆盖率≥80%

### Commit

Conventional Commits 格式: `<type>(<scope>): <subject>`
type: feat/fix/docs/style/refactor/perf/test/chore/ci

## 语言规则文件索引

- Python: rules/01-python.md
- Java: rules/02-java.md
- JS/TS: rules/03-javascript.md
- Go: rules/04-go.md
- Rust: rules/05-rust.md
- C/C++: rules/06-cpp.md
- SQL: rules/07-sql.md
- 数据库: rules/08-database.md
- NoSQL: rules/09-nosql.md
- Code Review: rules/10-code-review.md

## 完成检查

- [ ] lint 无 error
- [ ] typecheck 通过
- [ ] test 通过
- [ ] 无硬编码密钥
- [ ] 无调试输出
- [ ] 公共 API 有文档
- [ ] commit message 规范
