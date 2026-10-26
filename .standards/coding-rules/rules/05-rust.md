# Rust 编码规范

> Agent 生成 Rust 代码时必须遵守本文件规则。
> 基础标准：Rust API Guidelines + rustfmt + Clippy

---

## 1. 项目结构

```
project/
├── src/
│   ├── main.rs          # 二进制入口
│   ├── lib.rs           # 库入口
│   ├── module_a/
│   │   ├── mod.rs
│   │   └── sub.rs
│   └── module_b.rs
├── tests/
│   ├── integration.rs
│   └── common/mod.rs
├── benches/
│   └── benchmark.rs
├── Cargo.toml
└── clippy.toml
```

## 2. 命名规范

| 对象 | 风格 | 示例 |
|------|------|------|
| 函数/方法 | snake_case | `fn get_user() {}` |
| 变量 | snake_case | `let user_count = 0` |
| 常量 | UPPER_SNAKE | `const MAX_RETRY: u32 = 3` |
| 静态 | UPPER_SNAKE | `static GLOBAL: AtomicUsize` |
| 结构体 | PascalCase | `struct User {}` |
| 枚举 | PascalCase | `enum Color { Red, Blue }` |
| 枚举变体 | PascalCase | `Color::Red` |
| Trait | PascalCase | `trait Display {}` |
| 类型别名 | PascalCase | `type UserId = u64` |
| 模块 | snake_case | `mod user_service {}` |
| 文件 | snake_case | `user_service.rs` |
| 宏 | snake_case | `macro_rules! vec {}` |

## 3. 所有权与生命周期

```rust
// DON'T — 不必要的 Clone
fn process(user: User) -> User {
    let copy = user.clone();
    transform(&copy)
}

// DO — 借用
fn process(user: &User) -> User {
    transform(user)
}

// DON'T — 返回引用的生命周期不明确
fn get_name(user: &User) -> &str {
    user.name.as_str()
}

// DO — 显式生命周期（编译器可以推断时省略）
fn get_name<'a>(user: &'a User) -> &'a str {
    user.name.as_str()
}
```

### 所有权规则

- 优先借用（`&T` / `&mut T`），避免不必要的 `clone()`
- 需要所有权时才传值
- `Cow<T>` 处理"可能克隆可能借用"的场景
- 禁止 `unsafe` 除非有充分理由和 SAFETY 注释

```rust
// unsafe 必须带 SAFETY 注释
unsafe {
    // SAFETY: `ptr` is valid for reads of 4 bytes because it was
    // returned by `alloc` which guarantees alignment and size.
    let val = ptr::read_unaligned(ptr);
}
```

## 4. 错误处理

```rust
// DON'T — panic 用于业务错误
fn get_user(id: u64) -> User {
    let user = db.find(id);
    if user.is_none() {
        panic!("user not found");  // 禁止！
    }
    user.unwrap()
}

// DO — Result 类型
#[derive(Debug, thiserror::Error)]
pub enum UserError {
    #[error("user not found: id={0}")]
    NotFound(u64),
    #[error("database error: {0}")]
    Database(#[from] sqlx::Error),
}

pub fn get_user(id: u64) -> Result<User, UserError> {
    db.find(id)
        .map_err(UserError::Database)?
        .ok_or(UserError::NotFound(id))
}
```

### 错误规则

- 用 `Result<T, E>` 处理可恢复错误，不用 `panic!`
- `thiserror` 用于库错误类型，`anyhow` 用于应用错误
- `?` 操作符传播错误
- `unwrap()` / `expect()` 仅用于不可恢复的编程错误（或测试中）
- 禁止 `unwrap()` 在生产代码中（用 `?` 或 `match`）
- `Option` 不用 `unwrap()`，用 `ok_or()` / `?` / `map`

## 5. Trait 设计

```rust
// DON'T — 大 trait
trait UserRepo {
    fn get(&self, id: u64) -> Result<User, Error>;
    fn create(&self, user: User) -> Result<u64, Error>;
    fn update(&self, user: User) -> Result<(), Error>;
    fn delete(&self, id: u64) -> Result<(), Error>;
    fn list(&self, filter: Filter) -> Result<Vec<User>, Error>;
    fn count(&self, filter: Filter) -> Result<u64, Error>;
}

// DO — 小 trait
trait UserReader {
    fn get(&self, id: u64) -> Result<User, Error>;
}

trait UserWriter {
    fn create(&self, user: User) -> Result<u64, Error>;
}
```

### Trait 规则

- Trait 方法不超过 5 个
- 用 `trait X: Y` 组合 trait
- 默认实现优于无默认实现
- 禁止 trait 方法返回 `impl Trait`（用 `Box<dyn Trait>` 或具体类型）

## 6. 枚举与模式匹配

```rust
// DON'T — 用字符串表示状态
struct User {
    status: String,  // "active" | "inactive" | "pending"
}

// DO — 用枚举
#[derive(Debug, Clone, PartialEq)]
pub enum UserStatus {
    Active,
    Inactive,
    Pending { since: DateTime<Utc> },
}

// 模式匹配
match user.status {
    UserStatus::Active => { ... }
    UserStatus::Inactive => { ... }
    UserStatus::Pending { since } => { ... }
}
```

### 枚举规则

- 枚举变体覆盖所有状态，模式匹配必须穷尽
- 用 `#[non_exhaustive]` 标注可能扩展的公开枚举
- `if let` 用于只关心一个分支，`match` 用于多分支

## 7. 迭代器

```rust
// DON'T — 命令式循环
let mut results = Vec::new();
for user in users {
    if user.is_active() {
        results.push(user.name);
    }
}

// DO — 迭代器
let results: Vec<&str> = users
    .iter()
    .filter(|u| u.is_active())
    .map(|u| u.name.as_str())
    .collect();
```

### 迭代器规则

- 优先迭代器方法链，不用命令式循环
- `&` 借用优先，`&mut` 用于修改，owned 用于消费
- `collect()` 类型必须显式
- 无限迭代器用 `take(n)` 限制

## 8. 并发

```rust
use std::sync::{Arc, Mutex};
use tokio::sync::RwLock;

// 线程安全共享状态
let counter = Arc::new(Mutex::new(0));

// 异步读写锁
let cache = Arc::new(RwLock::new(HashMap::new()));

// DON'T — std::sync::Mutex 用于 async 上下文
// DO — tokio::sync::Mutex 用于 async

// DON'T — 长时间持有锁
{
    let mut data = cache.write().await;
    let result = expensive_operation().await;  // 锁内执行耗时操作!
    data.insert(key, result);
}

// DO — 最小化锁持有
let result = expensive_operation().await;
{
    let mut data = cache.write().await;
    data.insert(key, result);
}
```

## 9. 测试

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_user_creation() {
        // Arrange
        let repo = MockRepo::new();
        let service = UserService::new(repo);

        // Act
        let user = service.create("Alice", "alice@example.com").unwrap();

        // Assert
        assert_eq!(user.name, "Alice");
        assert!(user.id > 0);
    }
}
```

### 测试规则

- `#[cfg(test)]` 模块放同文件
- 集成测试放 `tests/` 目录
- `assert_eq!` 替代 `assert!(a == b)`
- 参数化用 `rstest` crate
