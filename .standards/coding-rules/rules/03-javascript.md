# JavaScript / TypeScript 编码规范

> Agent 生成 JS/TS 代码时必须遵守本文件规则。
> 基础标准：Airbnb Style Guide + Google TypeScript Guide

---

## 1. 命名规范

| 对象 | 风格 | 示例 |
|------|------|------|
| 变量 | camelCase | `const userCount = 10` |
| 函数 | camelCase | `function getUserName()` |
| 类 | PascalCase | `class UserService` |
| 组件 | PascalCase | `function UserCard()` |
| 常量 | UPPER_SNAKE | `const MAX_RETRY = 3` |
| 文件 | kebab-case | `user-service.ts` |
| 类型 | PascalCase | `type User = { ... }` |
| 接口 | PascalCase | `interface IUser {}` 或 `User {}` |
| 枚举 | PascalCase + PascalCase 值 | `enum Color { Red, Blue }` |
| 私有 | #前缀 | `#privateMethod()` |

## 2. 类型规范（TypeScript）

### 2.1 类型标注

```typescript
// DON'T — 隐式 any
function getUser(id) {
  return fetch(`/users/${id}`);
}

// DO — 显式类型
function getUser(id: number): Promise<User> {
  return fetch(`/users/${id}`).then(r => r.json());
}
```

### 2.2 类型选择

```typescript
// DON'T — 过度使用 any
function process(data: any): any { ... }

// DO — 精确类型
function process(data: UserInput): ProcessedResult { ... }

// DON'T — interface 与 type 混用
interface User { name: string }
type Order = { id: number }

// DO — 统一用 interface 描述对象形状
interface User { name: string }
interface Order { id: number }

// DO — type 用于联合类型和工具类型
type Status = 'active' | 'inactive' | 'pending';
type UserOrNull = User | null;
```

### 2.3 unknown vs any

```typescript
// DON'T — any
function parseJSON(str: string): any {
  return JSON.parse(str);
}

// DO — unknown + 类型收窄
function parseJSON(str: string): unknown {
  return JSON.parse(str);
}

const data = parseJSON(str);
if (isUser(data)) {
  // data: User
}
```

### 2.4 类型工具

- `unknown` 优于 `any`
- `Record<K, V>` 优于 `{ [key: string]: V }`
- `Partial<T>` / `Required<T>` / `Readonly<T>` / `Pick<T, K>` / `Omit<T, K>`
- `as const` 用于常量推断

## 3. ES6+ 最佳实践

### 3.1 解构

```typescript
// DON'T
const name = user.name;
const age = user.age;

// DO
const { name, age } = user;

// 函数参数
// DON'T
function createUser(name, age, email) { ... }

// DO
interface CreateUserParams {
  name: string;
  age: number;
  email?: string;
}
function createUser({ name, age, email }: CreateUserParams): User { ... }
```

### 3.2 模板字符串

```typescript
// DON'T
const msg = 'Hello ' + name + ', you are ' + age + ' years old';

// DO
const msg = `Hello ${name}, you are ${age} years old`;
```

### 3.3 默认参数

```typescript
// DON'T
function greet(name) {
  name = name || 'Guest';
  return `Hello ${name}`;
}

// DO
function greet(name: string = 'Guest'): string {
  return `Hello ${name}`;
}
```

### 3.4 展开/剩余

```typescript
// DON'T — Object.assign
const merged = Object.assign({}, defaults, overrides);

// DO — 展开运算符
const merged = { ...defaults, ...overrides };

// 数组
const combined = [...first, ...second, item];
```

## 4. 异步编程

```typescript
// DON'T — 回调
function getData(callback) {
  fetch(url).then(res => callback(res.json()));
}

// DON'T — 混合 async/await 和 .then()
async function getUser(id: number): Promise<User> {
  return await fetch(`/users/${id}`).then(r => r.json());
}

// DO — 纯 async/await
async function getUser(id: number): Promise<User> {
  const res = await fetch(`/users/${id}`);
  return res.json();
}

// DO — 并行
async function getUsers(ids: number[]): Promise<User[]> {
  const users = await Promise.all(ids.map(id => getUser(id)));
  return users;
}
```

### 异步规则

- 禁止回调地狱，用 async/await
- async 函数必须 try/catch 处理错误
- 独立异步操作用 `Promise.all()` 并行
- 避免 `.then()` 与 `await` 混用
- 注意 `Promise.all` 的 fail-fast 行为（全部失败用 `Promise.allSettled`）

## 5. 模块规范

```typescript
// DON'T — CommonJS
const express = require('express');
module.exports = { foo };

// DO — ES Modules
import express from 'express';
export { foo };
export default class UserService { ... }
```

### 模块规则

- 用 ES Module (`import`/`export`)，不用 CommonJS (`require`)
- 导入分三组：Node 标准库 → 第三方 → 本项目
- 禁止 `export *`
- 命名导出优先于默认导出
- 循环依赖用接口抽象打破

## 6. React 组件规范

```tsx
// 函数组件
interface UserCardProps {
  user: User;
  onEdit?: (user: User) => void;
}

export function UserCard({ user, onEdit }: UserCardProps): JSX.Element {
  const [isEditing, setIsEditing] = useState(false);

  const handleSave = useCallback((updated: User) => {
    onEdit?.(updated);
    setIsEditing(false);
  }, [onEdit]);

  return (
    <div className="user-card">
      {isEditing ? <UserForm user={user} onSave={handleSave} /> : <UserDisplay user={user} />}
    </div>
  );
}
```

### React 规则

- 函数组件 + Hooks，不用 Class 组件
- Props 必须有 Interface 定义
- 状态提升到最近公共父组件
- `useMemo` / `useCallback` 仅用于引用相等导致重渲染的场景
- 组件文件名 PascalCase: `UserCard.tsx`
- 组件 > 150 行考虑拆分

## 7. 错误处理

```typescript
// DON'T
try {
  await fetchData();
} catch (e) {
  console.log(e);
}

// DO
class ApiError extends Error {
  constructor(public statusCode: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

try {
  await fetchData();
} catch (e) {
  if (e instanceof ApiError) {
    logger.error(`API failed: ${e.statusCode} ${e.message}`);
    throw new ServiceError(`API unavailable: ${e.message}`);
  }
  throw e;
}
```

## 8. 日志

```typescript
// DON'T — console.log
console.log('user data:', user);

// DO — 结构化 logger
import { logger } from '@/utils/logger';

logger.info('User fetched', { userId: id, duration: Date.now() - start });
logger.error('Database connection failed', { error: e.message, stack: e.stack });
```

### 日志规则

- 禁止 `console.log` 用于生产代码
- 用 `logger` 封装，支持级别和环境过滤
- 敏感信息脱敏
- 日志带上下文对象

## 9. 测试

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { UserService } from './user-service';

describe('UserService', () => {
  let service: UserService;

  beforeEach(() => {
    service = new UserService(mockRepo);
  });

  it('should create user with valid input', async () => {
    // Arrange
    const input = { name: 'Alice', email: 'alice@example.com' };

    // Act
    const user = await service.create(input);

    // Assert
    expect(user.name).toBe('Alice');
    expect(user.id).toBeDefined();
  });
});
```
