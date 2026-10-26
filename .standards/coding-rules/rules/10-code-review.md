# Code Review 与质量保障规范

> Agent 在执行代码审查、生成 PR、编写测试时必须遵守本文件规则。

---

## 1. Code Review 流程

### 1.1 提交 PR 前自检清单

Agent 提交代码前必须逐项确认：

- [ ] 代码通过 lint 检查，无 error 级别问题
- [ ] 代码通过类型检查（如适用）
- [ ] 代码通过所有测试
- [ ] 新增功能有对应测试
- [ ] Bug 修复有复现测试
- [ ] 无硬编码密钥/密码/Token
- [ ] 无调试输出语句（print/console.log）
- [ ] 无注释掉的代码
- [ ] 公共 API 有文档注释
- [ ] 命名符合规范（见 `rules/00-general.md`）
- [ ] 函数 ≤ 50 行，参数 ≤ 4
- [ ] 嵌套 ≤ 3 层
- [ ] 无魔法数字（用命名常量）
- [ ] 新增依赖已加入依赖清单文件
- [ ] Commit message 符合 Conventional Commits

### 1.2 Review 关注点

| 维度 | 检查项 |
|------|--------|
| **正确性** | 逻辑是否正确？边界条件是否处理？ |
| **安全性** | 有无注入风险？权限校验？敏感信息泄露？ |
| **性能** | 有无 N+1 查询？不必要的循环？大数据量处理？ |
| **可读性** | 命名是否清晰？逻辑是否自解释？注释是否必要？ |
| **可维护性** | 是否过度设计？是否可以复用？是否易于扩展？ |
| **一致性** | 是否匹配现有代码风格？是否符合规范？ |
| **测试** | 测试是否覆盖核心路径？测试是否有效？ |

## 2. 静态分析工具链

### 2.1 Python

```toml
# pyproject.toml
[tool.ruff]
line-length = 88
target-version = "py312"

[tool.ruff.lint]
select = [
    "E",    # pycodestyle errors
    "W",    # pycodestyle warnings
    "F",    # pyflakes
    "I",    # isort
    "B",    # flake8-bugbear
    "C4",   # flake8-comprehensions
    "UP",   # pyupgrade
    "N",    # pep8-naming
    "SIM",  # flake8-simplify
    "TCH",  # flake8-type-checking
]
ignore = ["E501"]  # 行长由 formatter 处理

[tool.ruff.format]
quote-style = "double"
indent-style = "space"
indent-width = 4

[tool.mypy]
python_version = "3.12"
strict = true
warn_return_any = true
warn_unused_configs = true
disallow_untyped_defs = true
```

### 2.2 JavaScript/TypeScript

```javascript
// eslint.config.js
import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
    js.configs.recommended,
    ...tseslint.configs.strict,
    {
        rules: {
            '@typescript-eslint/no-explicit-any': 'error',
            '@typescript-eslint/explicit-function-return-type': 'error',
            'no-console': ['error', { allow: ['warn', 'error'] }],
            'prefer-const': 'error',
            'no-var': 'error',
        }
    }
);
```

```json
// .prettierrc
{
    "printWidth": 100,
    "tabWidth": 2,
    "semi": true,
    "singleQuote": true,
    "trailingComma": "all",
    "arrowParens": "always"
}
```

### 2.3 Go

```yaml
# golangci.yml
linters:
    enable:
        - errcheck
        - govet
        - staticcheck
        - gocritic
        - revive
        - gofmt
        - goimports
        - misspell
        - gocyclo
        - dupl

linters-settings:
    gocyclo:
        min-complexity: 15
    gocritic:
        enabled-tags:
            - performance
            - style
```

### 2.4 Rust

```toml
# clippy.toml
msrv = "1.75.0"
too-many-arguments-threshold = 6

# Cargo.toml
[lints.clippy]
all = "deny"
pedantic = "warn"
nursery = "warn"
unwrap_used = "deny"
expect_used = "warn"
```

### 2.5 Java

```xml
<!-- pom.xml -->
<plugin>
    <groupId>com.puppycrawl.tools</groupId>
    <artifactId>checkstyle</artifactId>
    <version>10.12.0</version>
    <configuration>
        <configLocation>google_checks.xml</configLocation>
        <failOnViolation>true</failOnViolation>
    </configuration>
</plugin>
```

## 3. 测试规范

### 3.1 测试金字塔

```
       /\
      /E2E\          ← 5%   端到端测试
     /------\
    /Integ   \       ← 15%  集成测试
   /----------\
  /  Unit      \     ← 80%  单元测试
 /--------------\
```

### 3.2 覆盖率门禁

| 层级 | 覆盖率要求 | 说明 |
|------|-----------|------|
| 核心业务模块 | ≥ 80% | 支付、订单、用户 |
| 工具类 | ≥ 90% | 纯函数逻辑 |
| API 控制器 | ≥ 70% | 接口层 |
| 基础设施 | ≥ 50% | 配置、启动 |
| 整体 | ≥ 70% | 加权平均 |

### 3.3 测试命名

| 语言 | 命名模式 | 示例 |
|------|----------|------|
| Python | `test_<behavior>` | `test_user_creation_with_valid_email` |
| Java | `<MethodName>_<Condition>` | `shouldThrowWhenUserNotFound` |
| JS/TS | `should <behavior>` | `should create user with valid input` |
| Go | `Test<Function>_<Condition>` | `TestCreateUser_ValidInput` |
| Rust | `test_<behavior>` | `test_user_creation` |

### 3.4 测试规则

- **AAA 模式**：Arrange → Act → Assert，每部分注释标明
- **单一断言原则**：一个测试只验证一个行为
- **独立无序**：测试不依赖其他测试的执行结果和顺序
- **确定性**：不依赖时间、网络、随机数（用 Mock/Stub）
- **快速失败**：单元测试 ≤ 100ms/个，整套 ≤ 30s
- **Bug 先测**：Bug 修复前先写复现测试

## 4. CI/CD 质量门禁

### 4.1 PR 阶段门禁

```yaml
# .github/workflows/ci.yml
name: CI
on: [pull_request]

jobs:
    quality:
        runs-on: ubuntu-latest
        steps:
            - uses: actions/checkout@v4
            - name: Lint
              run: |
                  # Python
                  ruff check .
                  mypy .
                  # JS/TS
                  eslint .
                  # Go
                  golangci-lint run
                  # Rust
                  cargo clippy -- -D warnings

            - name: Test
              run: |
                  pytest --cov --cov-fail-under=70
                  vitest run --coverage
                  go test -race -coverprofile=coverage.out ./...
                  cargo test

            - name: Security Scan
              run: |
                  pip-audit
                  npm audit
                  govulncheck ./...
                  cargo audit
```

### 4.2 门禁阈值

| 检查项 | 阈值 | 失败动作 |
|--------|------|----------|
| Lint errors | 0 | 阻止合并 |
| Type errors | 0 | 阻止合并 |
| Test failures | 0 | 阻止合并 |
| Coverage | ≥ 70% | 阻止合并 |
| Security vulnerabilities | 0 Critical | 阻止合并 |
| Code duplication | ≤ 5% | 警告 |
| Cyclomatic complexity | ≤ 15 | 警告 |
| Function length | ≤ 50 lines | 警告 |

## 5. 规范落地实施路线

### 第一阶段：工具先行（1-2 周）

- [ ] 配置 `.editorconfig`
- [ ] 集成各语言 lint 工具
- [ ] 配置 pre-commit hooks
- [ ] CI 流水线加入 lint + test

### 第二阶段：流程保障（2-4 周）

- [ ] PR 模板规范化
- [ ] Code Review Checklist 上线
- [ ] 覆盖率门禁启用
- [ ] 安全扫描启用

### 第三阶段：规范覆盖（1-2 月）

- [ ] AGENTS.md 入口文件部署
- [ ] 各工具入口文件部署
- [ ] 分语言规则文件完善
- [ ] 团队培训与考核

### 第四阶段：持续运营（长期）

- [ ] Code Review 文化建设
- [ ] 定期规范评审与更新
- [ ] 自动化指标大盘
- [ ] 季度质量报告
