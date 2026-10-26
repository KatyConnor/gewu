# Java 编码规范

> Agent 生成 Java 代码时必须遵守本文件规则。
> 基础标准：Google Java Style Guide + Oracle Java Conventions + 阿里巴巴《Java 开发手册》deontic 子集（并发/常量/集合/日志/依赖规约，2026-10 收编）

---

## 1. 项目结构

```
src/main/java/com/example/project/
├── controller/       # HTTP 入口
├── service/           # 业务逻辑
├── repository/        # 数据访问
├── model/             # 实体与 DTO
│   ├── entity/
│   └── dto/
├── config/            # 配置类
├── exception/         # 自定义异常
├── util/              # 工具类
└── Application.java   # 入口
src/test/java/...
```

- 包名全小写，不用下划线
- 类名与文件名一致，一个文件一个 public 类

## 2. 命名规范

| 对象 | 风格 | 示例 |
|------|------|------|
| 包 | 全小写 | `com.example.user` |
| 类 | PascalCase | `UserService` |
| 接口 | PascalCase | `UserRepository` |
| 方法 | camelCase | `getUserById` |
| 变量 | camelCase | `userCount` |
| 常量 | UPPER_SNAKE | `MAX_RETRY_COUNT` |
| 枚举值 | UPPER_SNAKE | `Color.RED` |
| 泛型 | 单大写字母 | `T`, `K`, `V`, `E` |
| 注解 | PascalCase | `@Override` |

## 3. 类设计

### 3.1 类组织

```java
public class UserService {
    // 1. 静态常量
    public static final int MAX_USERS = 1000;

    // 2. 实例字段
    private final UserRepository repository;
    private int currentUserCount;

    // 3. 构造器
    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    // 4. 公共方法
    public User getUser(int id) { ... }

    // 5. 私有方法
    private void validateId(int id) { ... }

    // 6. 内部类
    private static class CacheEntry { ... }
}
```

### 3.2 SOLID 原则

```java
// DON'T — God class
public class UserOrderNotificationService {
    public void createUserAndOrderAndNotify(...) { ... }
}

// DO — 单一职责
public class UserService { public User createUser(...) { ... } }
public class OrderService { public Order createOrder(...) { ... } }
public class NotificationService { public void notify(...) { ... } }
```

## 4. 异常处理

```java
// DON'T — 吞异常
try {
    doSomething();
} catch (Exception e) {
    e.printStackTrace();
}

// DON'T — 用异常控制流程
try {
    map.get(key);
} catch (NullPointerException e) {
    return defaultValue;
}

// DO — 自定义业务异常
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(int userId) {
        super(String.format("User not found: id=%d", userId));
    }
}

// DO — try-with-resources
try (Connection conn = dataSource.getConnection()) {
    // use conn
} catch (SQLException e) {
    throw new DataAccessException("Query failed", e);
}
```

### 异常规则

- Checked Exception 用于可恢复的业务异常
- Unchecked (RuntimeException) 用于编程错误
- 自定义异常必须包含上下文信息
- 禁止 `e.printStackTrace()`，用 logger
- try-with-resources 管理资源
- 不用异常控制正常流程

## 5. 并发编程

```java
// DON'T — new Thread
new Thread(() -> doWork()).start();

// DO — 线程池
ExecutorService executor = Executors.newFixedThreadPool(4);
Future<Result> future = executor.submit(() -> doWork());

// DO — CompletableFuture
CompletableFuture<User> userFuture = CompletableFuture
    .supplyAsync(() -> userService.getUser(id))
    .thenApplyAsync(user -> enrichUser(user));
```

### 并发规则

- 禁止 `new Thread()`，用线程池
- 禁止 `Executors.newCachedThreadPool()`（无上限风险）；线程池必须手动指定核心线程数、队列容量与拒绝策略（`ThreadPoolExecutor` 显式构造）
- 并发集合优先：`ConcurrentHashMap` 替代 `synchronized Map`；多线程环境禁用 `HashMap`（扩容死循环/数据覆盖风险）
- `synchronized` 锁对象用 `private final Object lock = new Object()`；禁止锁 Integer/Long 等包装类与 String（缓存池导致锁对象被复用）
- 包装类缓存陷阱：`Integer`/`Long` 在 -128~127 之外 `==` 结果不可靠，对象相等判断统一 `Objects.equals()`；高频计数用 `LongAdder`（JDK8+）替代 `AtomicLong`
- 日期时间线程安全：禁止多线程共享 `SimpleDateFormat`（非线程安全），统一用 `java.time.*`（`DateTimeFormatter`）或 `ThreadLocal<SimpleDateFormat>`
- 双重检查锁必须加 `volatile`；`volatile` 用于状态标志，复合操作用 `Atomic*`
- `ThreadLocal` 用完必须 `remove()`（线程池复用导致脏值与内存泄漏）
- 高并发写入同一共享变量优先无锁结构或分段设计

## 6. Stream API

```java
// DON'T — 嵌套 Stream
list.stream()
    .map(x -> x.stream().map(y -> y.getValue()).collect(toList()))
    .collect(toList());

// DON'T — 过度使用并行流
list.parallelStream().map(this::slowOperation).collect(toList());

// DO — 清晰的 Stream 链
List<String> names = users.stream()
    .filter(User::isActive)
    .map(User::getName)
    .sorted()
    .collect(Collectors.toList());
```

### Stream 规则

- Stream 操作不超过 3-4 步，超过则拆分
- 避免在 Stream 中修改外部状态
- `collect(toList())` 优于 `collect(toUnmodifiableList())`（版本兼容）
- 谨慎用 `parallelStream()`（有共享状态风险）

## 7. Optional

```java
// DON'T
public User getUser(int id) {
    User user = repo.findById(id);
    if (user != null) return user;
    throw new UserNotFoundException(id);
}

// DO — 返回 Optional 表示可空
public Optional<User> findUser(int id) {
    return Optional.ofNullable(repo.findById(id));
}

// 使用
User user = findUser(id)
    .orElseThrow(() -> new UserNotFoundException(id));
```

### Optional 规则

- 用于返回值，不用于字段
- 不用 `Optional.of(null)`，用 `Optional.empty()`
- 不用 `isPresent()` + `get()`，用 `orElse` / `orElseThrow` / `map`
- 序列化字段不用 Optional

## 8. Lombok

```java
// DO — data 类用 @Data 或 @Value
@Data
@Builder
public class UserDTO {
    private final int id;
    private final String name;
    private final String email;
}

// 禁止 — 滥用 @SneakyThrows
@SneakyThrows  // DON'T
public void doSomething() {
    Files.readAllBytes(path);
}

// 禁止 — @Data 用于 Entity
@Entity
@Data  // DON'T — 用 @Getter @Setter
public class UserEntity { ... }
```

### Lombok 规则

- `@Data` 用于 DTO，`@Value` 用于不可变对象
- `@Builder` 用于多字段构造
- 禁止 `@SneakyThrows`（吞掉 Checked Exception）
- JPA Entity 不用 `@Data`（会生成 `equals/hashCode` 导致懒加载问题）
- `@Slf4j` 统一日志

## 9. 测试

```java
// JUnit 5 + AssertJ
@DisplayName("UserService")
class UserServiceTest {
    @Mock private UserRepository repository;
    @InjectMocks private UserService service;

    @BeforeEach
    void setUp() {
        Mockito.openMocks(this);
    }

    @Test
    @DisplayName("用户不存在时抛出异常")
    void shouldThrowWhenUserNotFound() {
        // Arrange
        given(repository.findById(999)).willReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.getUser(999))
            .isInstanceOf(UserNotFoundException.class)
            .hasMessageContaining("999");
    }
}
```

## 10. 常量定义

- **禁止魔法值**：不允许未经定义的常量直接出现在代码中（`if (status == 3)` — 3 是什么？）
- `long` 赋值用**大写 `L`**（`long id = 2L` 不是 `2l`——小写 l 与 1 难辨）
- 不要用一个常量维护所有语义（按用途分为多个常量；固定取值域用枚举）
- 常量类按功能归档，不搞全局大杂烩 `Constants`

## 11. 集合处理

- `ArrayList` 在已知容量时**必须指定初始容量**（避免扩容拷贝）
- `subList()` 返回原列表视图：修改原列表会导致视图失效（`ConcurrentModificationException`），不可序列化
- 集合转数组：`c.toArray(new T[0])`（定长入参）
- 集合转 `Map`（`toMap`）时 key/value 不得为 null（NPE）；key 冲突必须显式指定合并函数
- 遍历中修改集合用 `Iterator.remove()` 或 `removeIf()`，禁止 for-each 中直接删
- 判空与判大小：集合判空用 `isEmpty()`（不是 `size() == 0`——语义与性能）

## 12. 日志规约

- **占位符方式打日志**：`log.warn("user {} not found", userId)` — 禁止字符串拼接 `log.warn("user " + userId + " not found")`（无效开销）
- **异常堆栈必须完整入日志**：`log.error("msg", e)` — 禁止 `log.error("msg: " + e.getMessage())`（丢堆栈）
- 生产代码禁止 `System.out` / `System.err` / `e.printStackTrace()`（性能与日志级别失控）——统一 `@Slf4j` logger
- 日志级别纪律：ERROR=系统级故障需人工介入 / WARN=可自愈异常 / INFO=关键业务动作 / DEBUG=调试细节（生产不开 DEBUG）
- 可疑日志输出前判断级别：`if (log.isDebugEnabled())`（构造开销大的场景）
- 日志语言与项目约定一致；对仗工整（同域日志格式统一，便于 grep 与统计）

## 13. 依赖管理（Maven/Gradle）

- 版本统一管理：Maven 用 `<dependencyManagement>`（或父 POM / BOM），Gradle 用版本目录（version catalog）——**禁止版本号散落在各模块**
- 禁止 `SNAPSHOT` 依赖进入 release 构建
- 依赖冲突显式裁决：用 `mvn dependency:tree` 排查，`<exclusions>` 只排明确冲突（不无脑传递排除）
- 二方库/三方库引入前查证：活跃度、许可证（license 兼容）、传递依赖体积
- 最小依赖面：只引用到的模块，不引聚合包（如仅用 guava 某工具不引整个重型框架）
