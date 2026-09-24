# 通用编码规范

> 本文件定义跨语言的通用编码原则。Agent 在编写任何语言代码前必须遵守。

---

## 1. 命名规范总表

| 语言 | 变量/函数 | 类/类型 | 常量 | 文件名 | 包/模块 |
|------|-----------|---------|------|--------|---------|
| Python | snake_case | PascalCase | UPPER_SNAKE | snake_case.py | snake_case |
| Java | camelCase | PascalCase | UPPER_SNAKE | PascalCase.java | 全小写 |
| JS/TS | camelCase | PascalCase | UPPER_SNAKE | kebab-case.ts | kebab-case |
| Go | camelCase¹ | PascalCase | PascalCase | snake_case.go | 单词小写 |
| Rust | snake_case | PascalCase | UPPER_SNAKE | snake_case.rs | snake_case |
| C/C++ | snake_case | PascalCase | UPPER_SNAKE | snake_case.cpp | — |

¹ Go: 导出标识符用 PascalCase，未导出用 camelCase

### 命名原则

- **见名知意**：`user_count` 优于 `n` / `cnt` / `data`
- **避免缩写**：除非是领域通用缩写（URL、HTTP、ID）
- **布尔值**：用 `is_` / `has_` / `can_` 前缀（Python/JS），`is` / `has` 前缀（Java/Go）
- **集合**：用复数形式 `users`、`items`
- **避免否定**：`is_valid` 优于 `is_not_invalid`

---

## 2. 代码格式

### 缩进

| 语言 | 缩进 | 备注 |
|------|------|------|
| Python | 4 空格 | 强制 |
| Java | 4 空格 | |
| JS/TS | 2 空格 | |
| Go | Tab | gofmt 强制 |
| Rust | 4 空格 | rustfmt 默认 |
| C/C++ | 4 空格 | |

### 行宽限制

| 语言 | 限制 | 备注 |
|------|------|------|
| Python | 88 | Black 默认 |
| Java | 100 | Google Style |
| JS/TS | 100 | Prettier 可配置 |
| Go | 无限制 | gofmt 处理 |
| Rust | 100 | rustfmt 默认 |
| C/C++ | 80 | Google Style |

### 文件编码

- UTF-8 无 BOM
- LF 换行符（\n）
- 文件末尾保留一个空行
- 行尾禁止空格

---

## 3. 函数设计规范

### 3.1 规则

- **行数限制**：单函数不超过 50 行（不含空行和注释）
- **参数限制**：不超过 4 个参数；超过 4 个必须用对象/结构体封装
- **嵌套深度**：不超过 3 层
- **圈复杂度**：不超过 10
- **单一职责**：一个函数只做一件事

### 3.2 结构规范

- **提前返回（Guard Clause）**：先处理边界条件和异常情况，提前 return

```python
# DON'T
def get_user(uid):
    if uid is not None:
        if uid > 0:
            user = db.find(uid)
            if user:
                return user
            else:
                return None
        else:
            return None
    else:
        return None

# DO
def get_user(uid: int | None) -> User | None:
    if uid is None or uid <= 0:
        return None
    return db.find(uid)
```

### 3.3 参数设计

- 必填参数在前，可选参数在后
- 布尔参数应考虑用枚举替代（避免 `process(data, true, false, true)`）
- 默认值用不可变对象（Python: `None` + 函数内初始化，禁止 `def f(x=[])`)

---

## 4. 错误处理规范

### 4.1 核心原则

- **显式处理**：所有错误必须被处理，禁止空 catch/except
- **保留上下文**：错误传播时必须保留原始错误信息
- **分类处理**：区分业务错误（可预期）与系统错误（不可预期）
- **快速失败**：输入校验在入口完成，尽早暴露问题

### 4.2 语言示例

```python
# DON'T — 空except
try:
    result = api.call()
except Exception:
    pass

# DO — 显式处理+保留上下文
try:
    result = api.call()
except NetworkError as e:
    raise ServiceUnavailableError(f"API call failed: {e}") from e
except ValidationError as e:
    logger.warning("Validation failed: %s", e)
    raise
```

```go
// DON'T — 忽略错误
result, _ := doSomething()

// DO — 显式处理
result, err := doSomething()
if err != nil {
    return fmt.Errorf("doSomething failed: %w", err)
}
```

---

## 5. 注释与文档

### 5.1 注释原则

- **代码自解释优先**：好的命名和结构优于注释
- **注释解释 WHY 而非 WHAT**：代码做了什么看代码就懂，注释解释为什么这样做
- **公共 API 必须有文档注释**
- **TODO 必须带署名和日期**：`// TODO(zhang, 2026-08): 需要支持分布式场景`

### 5.2 文档注释格式

| 语言 | 格式 | 示例 |
|------|------|------|
| Python | Google Style docstring | `"""获取用户信息。Args: uid: 用户ID. Returns: User.` |
| Java | Javadoc | `/** 获取用户信息. @param uid 用户ID @return User */` |
| JS/TS | JSDoc | `/** 获取用户信息. @param {number} uid @returns {User} */` |
| Go | Godoc | `// getUser 返回指定用户` (注释紧贴函数) |
| Rust | rustdoc | `/// 获取用户信息` |

### 5.3 禁止的注释

```python
# DON'T — 注释掉的代码
# result = old_method(data)
# if result.success:
#     process(result)

# DON'T — 显而易见的注释
i = i + 1  # i加1

# DON'T — 陈旧的注释
# 使用Redis缓存 (实际代码已改为Memcached)
```

---

## 6. 版本控制规范

### 6.1 Commit Message

Conventional Commits 格式：

```
<type>(<scope>): <subject>

<body>

<footer>
```

**type 列表：**

| type | 说明 |
|------|------|
| feat | 新功能 |
| fix | Bug 修复 |
| docs | 文档变更 |
| style | 代码格式（不影响功能） |
| refactor | 重构（非新功能、非修复） |
| perf | 性能优化 |
| test | 测试相关 |
| chore | 构建/工具链/依赖 |
| ci | CI/CD 变更 |

**规则：**
- subject 不超过 50 字符，现在时态，小写开头
- body 解释 WHY（为什么做这个变更）
- footer 包含 BREAKING CHANGE 和 Closes #issue

### 6.2 分支策略

- `main` / `master`：生产代码，只通过 PR 合入
- `develop`：开发集成分支
- `feature/<task>`：功能开发分支
- `fix/<issue>`：Bug 修复分支
- `hotfix/<issue>`：生产紧急修复

### 6.3 PR 规则

- 一个 PR 只做一件事
- PR 描述必须包含：变更原因、变更内容、测试方法
- 至少 1 人 Code Review 通过
- CI 通过（lint + typecheck + test）
- 无 merge commit（squash merge）
