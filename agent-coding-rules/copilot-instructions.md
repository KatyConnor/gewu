# GitHub Copilot Instructions

# 本文件供 GitHub Copilot 读取。Copilot 在生成代码建议时必须遵守以下规则。
# 完整规则见 AGENTS.md，分语言规则见 rules/ 目录。

## Core Rules

### Naming

- Python: snake_case variables/functions, PascalCase classes, UPPER_SNAKE constants
- Java: camelCase variables/methods, PascalCase classes, lowercase packages
- JS/TS: camelCase variables/functions, PascalCase classes/components, UPPER_SNAKE constants
- Go: PascalCase exported, camelCase unexported
- Rust: snake_case variables/functions, PascalCase types/traits

### Safety

- Never hardcode secrets/keys/tokens
- Never use eval/exec/os.system with user input
- Never concatenate SQL strings — use parameterized queries
- Never empty except/catch blocks
- Never commit debug print/console.log statements
- Never leave commented-out code

### Functions

- Max 50 lines, max 4 parameters
- Early return, max 3 nesting levels
- Single responsibility
- Verb-noun naming

### Errors

- Explicit error handling, no swallowing
- Preserve error context when propagating
- Separate business errors from system errors
- Fail fast on input validation

### Database

- Parameterized queries only
- No SELECT *, explicit column names
- Always include LIMIT
- Multi-row writes use transactions
- Release connections after use

### Testing

- New features must include tests
- Bug fixes start with reproduction test
- AAA pattern: Arrange → Act → Assert
- Tests independent, no execution order dependency

### Commits

Conventional Commits format: `<type>(<scope>): <subject>`
Types: feat/fix/docs/style/refactor/perf/test/chore/ci

## Language Rules

For detailed language-specific rules, read:
- `rules/01-python.md` — Python
- `rules/02-java.md` — Java
- `rules/03-javascript.md` — JavaScript/TypeScript
- `rules/04-go.md` — Go
- `rules/05-rust.md` — Rust
- `rules/06-cpp.md` — C/C++
- `rules/07-sql.md` — SQL
- `rules/08-database.md` — Database Design
- `rules/09-nosql.md` — NoSQL
- `rules/10-code-review.md` — Code Review
