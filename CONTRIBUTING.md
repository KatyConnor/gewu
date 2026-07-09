# 格物平台 贡献指南

## 目录

1. [代码规范](#代码规范)
2. [Git 工作流](#git-工作流)
3. [提交规范](#提交规范)
4. [代码审查](#代码审查)
5. [测试要求](#测试要求)
6. [文档要求](#文档要求)

---

## 代码规范

### Java 代码规范

#### 命名规范

```java
// ✅ 正确
public class UserService { }                    // 类名：大驼峰
private UserRepository userRepository;          // 变量名：小驼峰
public void createUser(CreateUserCommand cmd)   // 方法名：小驼峰
private static final int MAX_RETRY_COUNT = 3;   // 常量：全大写下划线

// ❌ 错误
public class userService { }                    // 类名小写
private UserRepository user_repository;         // 下划线命名
public void CreateUser(...)                     // 方法名大写
```

#### 包结构

```
com.gewu
├── common/              # 公共模块
│   ├── exception/       # 统一异常
│   ├── util/           # 工具类
│   └── constant/       # 常量
├── domain/             # 领域层
│   ├── user/           # 用户域
│   │   ├── model/      # 聚合根、实体、值对象
│   │   ├── repository/ # Repository 接口
│   │   └── service/    # 领域服务
│   ├── session/        # 会话域
│   └── workflow/       # 工作流域
├── application/        # 应用层
│   ├── user/           # 用户应用服务
│   │   ├── command/    # Command 对象
│   │   ├── query/      # Query 对象
│   │   └── dto/        # DTO 对象
│   └── session/        # 会话应用服务
├── infrastructure/     # 基础设施层
│   ├── persistence/    # 持久化实现
│   ├── security/       # 安全实现
│   └── mq/             # 消息队列实现
└── interface/          # 接口层
    ├── rest/           # REST Controller
    ├── sse/            # SSE Controller
    └── config/         # 配置类
```

#### DDD 分层依赖

```
interface → application → domain ← infrastructure
                ↓              ↑
            common ←──────────┘
```

**规则**：
- ✅ `interface` 可以依赖 `application` 和 `common`
- ✅ `application` 可以依赖 `domain` 和 `common`
- ✅ `infrastructure` 可以依赖 `domain` 和 `common`
- ❌ `domain` **不能**依赖 `application`、`interface`、`infrastructure`
- ❌ 任何层**不能**循环依赖

#### 异常处理

```java
// ✅ 正确：使用统一异常
public User findById(String userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, userId));
}

// ❌ 错误：直接抛出 RuntimeException
public User findById(String userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new RuntimeException("User not found"));
}
```

#### 日志规范

```java
// ✅ 正确：使用占位符
log.info("User created: userId={}, username={}", user.getId(), user.getUsername());

// ❌ 错误：字符串拼接
log.info("User created: " + user.getId() + ", " + user.getUsername());

// ✅ 正确：异常日志
try {
    // ...
} catch (Exception e) {
    log.error("Failed to create user: userId={}", userId, e);
}

// ❌ 错误：吞掉异常
try {
    // ...
} catch (Exception e) {
    log.error("Error");  // 丢失异常堆栈
}
```

### 前端代码规范

#### React 组件

```tsx
// ✅ 正确：函数组件 + TypeScript
interface UserCardProps {
  userId: string;
  username: string;
  onEdit?: () => void;
}

export const UserCard: React.FC<UserCardProps> = ({ userId, username, onEdit }) => {
  return (
    <div className="user-card">
      <h3>{username}</h3>
      {onEdit && <button onClick={onEdit}>编辑</button>}
    </div>
  );
};

// ❌ 错误：类组件（除非必要）
class UserCard extends React.Component { }
```

#### 状态管理

```tsx
// ✅ 正确：使用 Context + useReducer（局部状态）
const UserContext = React.createContext<UserState | null>(null);

// ✅ 正确：使用 Redux Toolkit（全局状态）
const userSlice = createSlice({
  name: 'user',
  initialState,
  reducers: {
    setUser: (state, action: PayloadAction<User>) => {
      state.current = action.payload;
    },
  },
});

// ❌ 错误：Props drilling 过深
<Parent user={user}>
  <Child user={user}>
    <GrandChild user={user} />
  </Child>
</Parent>
```

---

## Git 工作流

### 分支策略

```
main (生产)
  ├── develop (开发)
  │   ├── feature/user-auth (功能分支)
  │   ├── feature/session-sse (功能分支)
  │   └── feature/workflow-engine (功能分支)
  ├── release/1.0.0 (发布分支)
  └── hotfix/fix-login-bug (热修复)
```

**分支命名**：
- 功能分支：`feature/<描述>`，如 `feature/user-auth`
- 修复分支：`fix/<描述>`，如 `fix/login-bug`
- 发布分支：`release/<版本号>`，如 `release/1.0.0`
- 热修复：`hotfix/<描述>`，如 `hotfix/fix-login-bug`

### 开发流程

```
1. 从 develop 创建功能分支
   git checkout develop
   git pull origin develop
   git checkout -b feature/user-auth

2. 开发并提交（小步提交）
   git add .
   git commit -m "feat(user): add user authentication"

3. 推送到远程
   git push origin feature/user-auth

4. 创建 Merge Request (MR)
   - 目标分支：develop
   - 审查人：至少 1 人
   - 通过 CI/CD 流水线

5. 合并到 develop
   - Squash merge（保持提交历史清晰）
   - 删除功能分支

6. 发布到 main
   - 从 develop 创建 release 分支
   - 测试通过后合并到 main
   - 打标签：git tag v1.0.0
```

---

## 提交规范

### Commit Message 格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Type**：
- `feat`: 新功能
- `fix`: 修复 Bug
- `docs`: 文档更新
- `style`: 代码格式（不影响功能）
- `refactor`: 重构（既不是新功能也不是修复）
- `test`: 测试相关
- `chore`: 构建/工具/依赖

**Scope**（可选）：
- `user`: 用户模块
- `session`: 会话模块
- `workflow`: 工作流模块
- `gateway`: 网关模块
- `common`: 公共模块

**示例**：

```bash
# ✅ 正确
feat(user): add SM3 password hashing
fix(session): fix SSE reconnection logic
docs(api): update workflow API documentation
refactor(domain): extract User aggregate root
test(user): add unit tests for UserService

# ❌ 错误
update code                    # 没有 type
feat: add new feature          # 没有 scope（可选但建议）
FIX: fix bug                   # type 应该小写
```

---

## 代码审查

### 审查清单

**功能正确性**：
- [ ] 代码是否实现了需求？
- [ ] 边界条件是否处理？
- [ ] 异常是否正确处理？

**代码质量**：
- [ ] 命名是否清晰？
- [ ] 是否符合 DDD 分层？
- [ ] 是否有重复代码？
- [ ] 是否有魔法数字？

**性能**：
- [ ] 是否有 N+1 查询？
- [ ] 是否有不必要的循环？
- [ ] 是否使用了合适的索引？

**安全**：
- [ ] 是否使用了国密算法？
- [ ] 是否有 SQL 注入风险？
- [ ] 是否有 XSS 风险？

**测试**：
- [ ] 单元测试覆盖率是否达标？
- [ ] 集成测试是否通过？

### 审查流程

```
1. 提交 MR
   - 标题清晰
   - 描述变更内容
   - 关联 Issue

2. 自动检查
   - CI/CD 流水线通过
   - 代码扫描通过
   - 测试覆盖率达标

3. 人工审查
   - 至少 1 人审查
   - 审查人提出建议
   - 作者修改并回复

4. 合并
   - 所有审查通过
   - Squash merge
   - 删除分支
```

---

## 测试要求

### 单元测试

**覆盖率要求**：
- 领域层（`gewu-domain`）：100%
- 应用层（`gewu-application`）：90%
- 接口层（`gewu-interface`）：80%
- 基础设施层（`gewu-infrastructure`）：70%

**示例**：

```java
@Test
void should_create_user_successfully() {
    // Given
    CreateUserCommand cmd = new CreateUserCommand("testuser", "test@example.com");
    when(userRepository.existsByUsername("testuser")).thenReturn(false);
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    User user = userService.createUser(cmd);

    // Then
    assertThat(user).isNotNull();
    assertThat(user.getUsername()).isEqualTo("testuser");
    verify(userRepository).save(any(User.class));
}

@Test
void should_throw_exception_when_username_exists() {
    // Given
    CreateUserCommand cmd = new CreateUserCommand("existinguser", "test@example.com");
    when(userRepository.existsByUsername("existinguser")).thenReturn(true);

    // When & Then
    assertThatThrownBy(() -> userService.createUser(cmd))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Username already exists");
}
```

### 集成测试

使用 Testcontainers 启动真实数据库：

```java
@Testcontainers
class UserRepositoryIT {
    @Container
    static OceanBaseContainer ob = new OceanBaseContainer("oceanbase/oceanbase-ce:4.2.0.0");

    @Test
    void should_save_and_find_user() {
        User user = new User("testuser", "test@example.com");
        userRepository.save(user);

        User found = userRepository.findById(user.getId()).orElseThrow();
        assertThat(found.getUsername()).isEqualTo("testuser");
    }
}
```

---

## 文档要求

### 代码注释

```java
/**
 * 用户服务
 * 
 * <p>负责用户账户的创建、更新、删除等操作。
 * 使用 SM3 算法对密码进行哈希存储。</p>
 *
 * @author 张三
 * @since 1.0.0
 */
@Service
public class UserService {

    /**
     * 创建用户
     *
     * @param cmd 创建用户命令
     * @return 创建的用户
     * @throws BusinessException 当用户名已存在时抛出
     */
    public User createUser(CreateUserCommand cmd) {
        // ...
    }
}
```

### API 文档

使用 Swagger/OpenAPI 自动生成：

```java
@Operation(summary = "创建用户", description = "创建新的用户账户")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "创建成功"),
    @ApiResponse(responseCode = "400", description = "参数错误"),
    @ApiResponse(responseCode = "409", description = "用户名已存在")
})
@PostMapping("/users")
public ResponseEntity<UserDTO> createUser(@Valid @RequestBody CreateUserRequest request) {
    // ...
}
```

---

## 附录

### 常用命令

```bash
# 运行单元测试
mvn test

# 运行集成测试
mvn verify

# 代码扫描
mvn sonar:sonar

# 构建镜像
docker build -t gewu-gateway:latest -f gewu-gateway/Dockerfile .

# 启动开发环境
docker-compose up -d ob dragonfly rocketmq-namesrv

# 查看日志
kubectl logs -f deployment/gewu-gateway -n gewu-dev
```

### 参考文档

- [统一 PRD](docs/design/20-unified-prd.md)
- [统一架构](docs/design/21-unified-architecture.md)
- [统一数据库](docs/design/22-unified-db-schema.md)
- [统一 API](docs/design/23-unified-api-spec.md)
- [统一安全](docs/design/24-unified-security.md)

---

**最后更新**: 2026-07-08
