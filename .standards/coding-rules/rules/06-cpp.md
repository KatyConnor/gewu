# C/C++ 编码规范

> Agent 生成 C/C++ 代码时必须遵守本文件规则。
> 基础标准：Google C++ Style Guide + C++ Core Guidelines + MISRA C

---

## 1. 命名规范

| 对象 | 风格 | 示例 |
|------|------|------|
| 函数 | snake_case / camelCase | `get_user()` (C) / `getUser()` (C++) |
| 变量 | snake_case | `int user_count = 0` |
| 类/结构体 | PascalCase | `class UserService` |
| 常量 | UPPER_SNAKE / kPascalCase | `MAX_RETRY` (C) / `kMaxRetry` (C++ Google) |
| 宏 | UPPER_SNAKE | `#define BUFFER_SIZE 1024` |
| 文件 | snake_case | `user_service.c` / `user_service.cpp` |
| 头文件 | snake_case.h | `user_service.h` |
| 命名空间 | snake_case | `namespace user_service` |
| 枚举值 | UPPER_SNAKE / kPascalCase | `Color::kRed` |

## 2. 头文件

```cpp
// user_service.h
#pragma once  // 优先 #pragma once，兼容性不够时用 #ifndef guard

#include <string>
#include <vector>

namespace myproject {

class UserService {
public:
    UserService();
    ~UserService() = default;

    User get_user(int id) const;
    void add_user(const User& user);

private:
    std::vector<User> users_;
};

}  // namespace myproject
```

### 头文件规则

- `#pragma once` 优先，老编译器用 `#ifndef` guard
- 前向声明优先于 `#include`
- 头文件包含顺序：对应头文件 → C 标准库 → C++ 标准库 → 第三方 → 项目内
- 禁止在头文件中 `using namespace std`
- 内联函数放 `.h`，大函数放 `.cpp`

## 3. 内存管理（C++）

```cpp
// DON'T — 裸指针 new/delete
User* user = new User();
// ... 使用 user ...
delete user;  // 容易忘记或异常时泄漏

// DO — 智能指针
#include <memory>

auto user = std::make_unique<User>();
// 自动释放

auto shared = std::make_shared<User>();
// 引用计数自动释放

// DON'T — new[]
int* arr = new int[100];
// ...
delete[] arr;

// DO — 容器
std::vector<int> arr(100);
```

### 内存规则

- 禁止裸 `new`/`delete`（用 `make_unique` / `make_shared`）
- 禁止 `malloc`/`free`（C++ 中）
- `unique_ptr` 表示独占所有权
- `shared_ptr` 表示共享所有权（有引用计数开销）
- RAII 管理资源：构造函数获取，析构函数释放

## 4. RAII

```cpp
// DON'T — 手动管理锁
mu.Lock();
do_something();
mu.Unlock();  // 异常时不会释放

// DO — RAII 锁
std::lock_guard<std::mutex> guard(mu);
do_something();  // 异常或返回时自动释放

// DON'T — 手动文件管理
FILE* f = fopen("file.txt", "r");
// ... 使用 f ...
fclose(f);  // 忘记关闭 → 资源泄漏

// DO — RAII 文件
std::ifstream f("file.txt");
// 自动关闭

// RAII 资源管理类
class ScopedTimer {
public:
    ScopedTimer() : start_(std::chrono::steady_clock::now()) {}
    ~ScopedTimer() {
        auto end = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start_);
        std::cerr << "Duration: " << ms.count() << "ms\n";
    }
private:
    std::chrono::steady_clock::time_point start_;
};
```

## 5. 现代 C++ 特性（C++17+）

```cpp
// auto 类型推导
auto user = get_user(id);  // DO — 清晰时用 auto
const auto& users = get_all_users();  // DO — 常量引用

// 结构化绑定
for (const auto& [key, value] : map) {
    process(key, value);
}

// std::optional 替代指针可空
std::optional<User> find_user(int id) {
    auto it = users_.find(id);
    if (it != users_.end()) {
        return *it;
    }
    return std::nullopt;
}

// std::variant 替代 union
using Result = std::variant<User, Error>;
Result r = get_result();
std::visit([](auto&& arg) { ... }, r);

// constexpr 编译期计算
constexpr int factorial(int n) {
    return n <= 1 ? 1 : n * factorial(n - 1);
}

// 概念（C++20）
template<typename T>
requires std::integral<T>
T add(T a, T b) { return a + b; }
```

## 6. 错误处理

```cpp
// DON'T — 错误码忽略
int result = do_something();
// 不检查返回值

// DON'T — 异常用于控制流
try {
    map.at(key);
} catch (std::out_of_range&) {
    // 用异常处理正常流程
}

// DO — 错误码 + 检查
auto result = do_something();
if (!result.ok()) {
    return Status(StatusCode::Internal, result.error());
}

// DO — std::optional 表示可空
auto user = find_user(id);
if (!user) {
    return Status::NotFound("user not found");
}
process(*user);
```

### 错误规则

- 禁止用异常做正常流程控制
- 错误码必须检查
- 析构函数禁止抛异常
- `noexcept` 标注不抛异常的函数
- 移动构造/赋值用 `noexcept`

## 7. 并发

```cpp
// DO — std::thread + RAII
#include <thread>
#include <mutex>
#include <future>

auto future = std::async(std::launch::async, [&] {
    return expensive_computation();
});

// DO — 原子操作
std::atomic<int> counter{0};
counter.fetch_add(1, std::memory_order_relaxed);

// DON'T — 数据竞争
int counter = 0;
// thread 1: counter++;  // UB!
// thread 2: counter++;
```

## 8. C 语言特定规则

```c
// 头文件 guard
#ifndef USER_SERVICE_H
#define USER_SERVICE_H
// ...
#endif  // USER_SERVICE_H

// 固定宽度整型
#include <stdint.h>
int32_t user_id;  // 不用 int（大小不确定）
uint8_t buffer[256];  // 不用 unsigned char*

// 返回值检查
char* result = malloc(size);
if (result == NULL) {
    // 处理分配失败
    return ERROR_ALLOC;
}

// 字符串处理（安全版本）
char dest[256];
// DON'T — strcpy 可能溢出
strcpy(dest, src);
// DO — 带长度限制
strncpy(dest, src, sizeof(dest) - 1);
dest[sizeof(dest) - 1] = '\0';

// 或用 snprintf
snprintf(dest, sizeof(dest), "%s", src);
```

## 9. 测试

```cpp
#include <gtest/gtest.h>

TEST(UserServiceTest, CreateUser) {
    // Arrange
    UserService service;

    // Act
    auto user = service.create_user("Alice", "alice@example.com");

    // Assert
    ASSERT_TRUE(user.has_value());
    EXPECT_EQ(user->name(), "Alice");
}
```
