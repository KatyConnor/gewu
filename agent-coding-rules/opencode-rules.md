# OpenCode 编码约束规则

# 本文件供 OpenCode Agent 读取。OpenCode 在执行编码任务时必须遵守以下规则。
# 完整规则见 AGENTS.md，分语言规则见 rules/ 目录。

## 规则加载

执行编码任务前，必须先读取项目根目录的 AGENTS.md 文件。
针对特定语言，读取 rules/ 目录下对应文件。

## 行为约束

### 必须遵守

- 修改文件前先完整读取目标文件
- 最小变更原则
- 匹配现有代码风格
- 修改后运行 lint / typecheck / test 并报告
- Python 必须有 Type Hint
- SQL 必须参数化查询
- 公共 API 必须有文档注释

### 禁止事项

- 禁止硬编码密钥/密码/Token
- 禁止 eval/exec/os.system 处理用户输入
- 禁止 SQL 字符串拼接
- 禁止空 except/catch
- 禁止 git push --force / reset --hard 未确认
- 禁止修改 main/master 未经确认
- 禁止 print/console.log 残留
- 禁止注释掉的代码

### 命名规范

| 语言 | 变量/函数 | 类 | 常量 | 文件 |
|------|-----------|-----|------|------|
| Python | snake_case | PascalCase | UPPER_SNAKE | snake_case.py |
| Java | camelCase | PascalCase | UPPER_SNAKE | PascalCase.java |
| JS/TS | camelCase | PascalCase | UPPER_SNAKE | kebab/Pascal |
| Go | camelCase/Pascal | PascalCase | PascalCase | snake_case.go |
| Rust | snake_case | PascalCase | UPPER_SNAKE | snake_case.rs |
| C/C++ | snake_case | PascalCase | UPPER_SNAKE | snake_case.cpp |

### 函数设计

- ≤50行, ≤4参数
- 提前返回, ≤3层嵌套
- 动宾命名

### 错误处理

- 显式处理所有错误
- 保留原始上下文
- 区分业务/系统错误
- 快速失败

### 数据库

- 参数化查询
- 禁止 SELECT *
- 必须带 LIMIT
- 多行写入用事务
- 连接用后释放

### Commit 规范

Conventional Commits: `<type>(<scope>): <subject>`

## 语言规则文件

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
