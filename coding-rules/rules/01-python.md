# Python 编码规范

> Agent 生成 Python 代码时必须遵守本文件规则。
> 基础标准：PEP 8 + Google Python Style Guide

---

## 1. 项目结构

```
project/
├── src/
│   └── mypackage/
│       ├── __init__.py
│       ├── module_a.py
│       └── module_b.py
├── tests/
│   ├── __init__.py
│   ├── test_module_a.py
│   └── conftest.py
├── pyproject.toml
├── ruff.toml
└── README.md
```

- 使用 src layout（`src/` 目录隔离包）
- 测试文件放 `tests/`，以 `test_` 前缀命名
- 配置统一在 `pyproject.toml`

## 2. 命名规范

| 对象 | 风格 | 示例 | 说明 |
|------|------|------|------|
| 模块 | snake_case | `user_service.py` | 简短，全小写 |
| 包 | snake_case | `my_package/` | 简短，全小写 |
| 函数 | snake_case | `def get_user():` | 动宾结构 |
| 方法 | snake_case | `def calculate_total():` | |
| 变量 | snake_case | `user_count = 0` | |
| 类 | PascalCase | `class UserService:` | 名词 |
| 常量 | UPPER_SNAKE | `MAX_RETRY = 3` | |
| 类型别名 | PascalCase | `type UserId = int` | |
| 枚举 | PascalCase | `class Color(Enum):` | 成员用 UPPER_SNIKE |
| 私有 | _前缀 | `_internal_method()` | |

## 3. 类型提示（强制）

所有函数必须包含类型提示：

```python
# DON'T
def get_user(uid):
    return db.find(uid)

# DO
def get_user(uid: int) -> User | None:
    return db.find(uid)
```

### 类型提示规则

- 参数和返回值必须有类型标注
- 使用 `X | Y` 替代 `Union[X, Y]`（Python 3.10+）
- 使用 `list[T]` 替代 `List[T]`（Python 3.9+）
- Optional 参数用 `T | None = None`
- 使用 `dataclass` 或 `TypedDict` 替代裸 `dict` 返回值

```python
# DON'T
def get_config() -> dict:
    return {"host": "localhost", "port": 8080}

# DO
from dataclasses import dataclass

@dataclass(frozen=True)
class ServerConfig:
    host: str
    port: int

def get_config() -> ServerConfig:
    return ServerConfig(host="localhost", port=8080)
```

## 4. 导入规范

```python
# 标准库
import os
import sys
from typing import Any

# 第三方
from fastapi import FastAPI
from pydantic import BaseModel

# 本项目
from mypackage.core import UserService
from mypackage.utils import format_date
```

- 导入分三组：标准库 → 第三方 → 本项目，组间空行
- 禁止 `from x import *`
- 禁止相对导入（用绝对导入）
- 排序用 `isort` 或 `ruff --select I`

## 5. 字符串格式化

```python
# DON'T — % 格式化
name = "world"
msg = "hello %s" % name

# DON'T — .format() (除非需要模板)
msg = "hello {}".format(name)

# DO — f-string
msg = f"hello {name}"
```

- 优先 f-string
- 多行字符串用三引号 `"""..."""`
- 长字符串拼接用括号隐式连接

## 6. 异常处理

```python
# DON'T
try:
    do_something()
except:
    pass

try:
    do_something()
except Exception as e:
    raise Exception("failed")

# DO
try:
    do_something()
except (ConnectionError, TimeoutError) as e:
    raise ServiceError(f"Operation failed: {e}") from e
except ValueError as e:
    logger.warning("Invalid input: %s", e)
    raise
```

### 异常规则

- 自定义异常继承 `Exception`，不继承 `BaseException`
- 异常类名以 `Error` 结尾
- 传播异常必须 `raise ... from err` 保留上下文
- 禁止 `except:` 裸捕获，必须指定异常类型
- `finally` 中禁止 `return`（会吞掉异常）

## 7. 异步编程

```python
# DON'T — 同步调用阻塞事件循环
async def fetch_data(url: str) -> Data:
    response = requests.get(url)  # 阻塞!
    return parse(response)

# DO — 使用异步库
import httpx

async def fetch_data(url: str) -> Data:
    async with httpx.AsyncClient() as client:
        response = await client.get(url)
        return parse(response)
```

### 异步规则

- async 函数中禁止同步 IO 调用
- 用 `asyncio.gather()` 并行独立任务
- 用 `asyncio.create_task()` 替代裸 `await` 实现并行
- 禁止 `asyncio.run()` 嵌套调用
- 信号量控制并发数

## 8. dataclass 与 Pydantic

```python
# 内部数据结构用 dataclass
from dataclasses import dataclass

@dataclass(frozen=True)
class User:
    uid: int
    name: str
    email: str | None = None

# API 边界数据用 Pydantic
from pydantic import BaseModel

class UserCreateRequest(BaseModel):
    name: str
    email: str
    age: int | None = None
```

## 9. 日志规范

```python
# DON'T — print
print(f"User {uid} not found")

# DO — logging
import logging
logger = logging.getLogger(__name__)

logger.info("User lookup: uid=%s", uid)
logger.warning("User not found: uid=%d", uid)
logger.error("Database connection failed", exc_info=True)
```

### 日志规则

- 禁止 `print()` 用于生产代码
- 用 `%s` 占位符（延迟格式化），不用 f-string（防止日志级别不够时仍格式化）
- 敏感信息脱敏：`logger.info("user=%s", mask_email(user.email))`
- 模块级 logger：`logger = logging.getLogger(__name__)`

## 10. 测试规范

```python
import pytest

def test_user_creation():
    # Arrange
    repo = FakeUserRepository()
    service = UserService(repo)

    # Act
    user = service.create("Alice", "alice@example.com")

    # Assert
    assert user.name == "Alice"
    assert user.email == "alice@example.com"

@pytest.mark.parametrize("email,valid", [
    ("user@example.com", True),
    ("invalid", False),
    ("", False),
])
def test_email_validation(email: str, valid: bool):
    assert validate_email(email) is valid
```

### 测试规则

- 文件名 `test_<module>.py`，类名 `Test<Module>`
- 测试方法 `test_<behavior>`，描述行为而非实现
- AAA 模式：Arrange → Act → Assert
- 用 `pytest.fixture` 共享 setup
- Mock 粒度最小化，优先真实组件
- 覆盖率门禁：核心模块 ≥ 80%，整体 ≥ 70%

## 11. 性能注意事项

- 列表推导优于 `for` 循环 + `append`
- 大数据用生成器替代列表
- 字符串拼接用 `"".join(parts)`
- 字典查找 O(1)，列表查找 O(n)
- 避免 `+` 在循环中拼接字符串
- 使用 `collections.deque` 替代 `list` 的头部操作
