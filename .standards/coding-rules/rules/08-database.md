# 数据库设计规范

> Agent 在设计数据库表结构、创建数据库对象时必须遵守本文件规则。
> 适用于 MySQL / PostgreSQL / Oracle / SQL Server 等关系型数据库。
> 基础标准：业界共识 + 阿里巴巴《Java 开发手册》MySQL 数据库规约 deontic 子集（§6 SQL 性能规约，2026-10 收编）

---

## 1. 命名规范

### 1.1 总表

| 对象 | 前缀 | 风格 | 示例 |
|------|------|------|------|
| 表 | 无 | snake_case 复数 | `users`, `order_items` |
| 列 | 无 | snake_case | `user_id`, `created_at` |
| 主键 | `pk_` | `pk_table` | `pk_users` |
| 外键 | `fk_` | `fk_table_col` | `fk_orders_user_id` |
| 唯一约束 | `uk_` | `uk_table_col` | `uk_users_email` |
| 检查约束 | `ck_` | `ck_table_col` | `ck_users_age` |
| 默认约束 | `df_` | `df_table_col` | `df_users_status` |
| 普通索引 | `idx_` | `idx_table_col` | `idx_users_email` |
| 联合索引 | `idx_` | `idx_table_col1_col2` | `idx_orders_user_status` |
| 视图 | `v_` | `v_name` | `v_active_users` |
| 存储过程 | `sp_` | `sp_action` | `sp_create_order` |
| 函数 | `fn_` | `fn_name` | `fn_calculate_total` |
| 触发器 | `trg_` | `trg_table_action` | `trg_user_after_insert` |
| 序列 | `seq_` | `seq_name` | `seq_user_id` |

### 1.2 命名原则

- 禁止数据库保留字：不用 `order`, `user`, `group`, `select`
- 禁止中文拼音命名：`yonghu` 不如 `users`
- 布尔列：`is_active`, `has_paid`, `can_edit`
- 时间列：`created_at`, `updated_at`, `deleted_at`
- 金额列：`amount`, `total_price`, `discount_amount`
- 状态列：`status`, `order_status`
- 外键列：`<引用表>_id`，如 `user_id`, `order_id`

## 2. 表设计规范

### 2.1 必备列

每张业务表必须包含以下列：

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键，自增或雪花 ID |
| `created_at` | TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | 更新时间 |
| `created_by` | BIGINT | 创建人 ID（可选） |
| `updated_by` | BIGINT | 更新人 ID（可选） |
| `deleted_at` | TIMESTAMP | 软删除时间（NULL=未删除） |
| `version` | INT | 乐观锁版本号 |

```sql
CREATE TABLE users (
    id          BIGINT       PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP   NULL,
    version     INT          NOT NULL DEFAULT 0,

    UNIQUE KEY uk_users_email (email),
    INDEX idx_users_status (status)
);
```

### 2.2 设计原则

- **第三范式为基准**：消除传递依赖，允许反范式优化（冗余列减少 JOIN）
- **单表列数 ≤ 30**：超过考虑拆表
- **单表行数预期 > 1000 万**：提前设计分区方案
- **软删除优先**：`deleted_at IS NULL` 表示有效记录
- **乐观锁**：`version` 列用于并发更新控制
- **禁止物理删除**：生产数据用软删除，审计表除外

### 2.3 字段类型选择

| 数据 | 推荐类型 | 禁止类型 | 说明 |
|------|----------|----------|------|
| 主键 | BIGINT | INT（数据量可能超限） | 自增或雪花 ID |
| 字符串 | VARCHAR(n) | CHAR(n)（除非定长） | |
| 长文本 | TEXT | VARCHAR(10000+) | |
| 金额 | DECIMAL(18,2) | FLOAT/DOUBLE | 精度问题 |
| 布尔 | TINYINT(1) | BOOLEAN | 兼容性好 |
| 枚举 | TINYINT+映射表 | VARCHAR/ENUM | 修改需 DDL |
| 日期 | DATE | VARCHAR | |
| 时间 | TIMESTAMP | VARCHAR | |
| JSON | JSON | TEXT | 原生 JSON 类型可查询 |
| UUID | BINARY(16) | VARCHAR(36) | 二进制存储节省空间 |
| IP | VARBINARY(16) | VARCHAR(39) | |
| 时间范围 | TIMESTAMPTZ | TIMESTAMP | 带时区 |

### 2.4 禁止的设计

```sql
 -- DON'T — 使用 ENUM
CREATE TABLE users (
    role ENUM('admin', 'user', 'guest')  -- 增删枚举值需要 DDL
);
-- DO — 使用 TINYINT + 映射表
CREATE TABLE users (
    role_id TINYINT NOT NULL,  -- 1=admin, 2=user, 3=guest
    FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- DON'T — 使用 FLOAT 存金额
CREATE TABLE orders (
    amount FLOAT  -- 精度丢失！
);
-- DO
CREATE TABLE orders (
    amount DECIMAL(18, 2)
);

-- DON'T — 不带默认值的 NOT NULL
CREATE TABLE users (
    status TINYINT NOT NULL  -- 插入时必须指定，容易遗漏
);
-- DO
CREATE TABLE users (
    status TINYINT NOT NULL DEFAULT 1
);

-- DON'T — 物理外键（高并发性能问题）
CONSTRAINT fk_orders_user_id
    FOREIGN KEY (user_id) REFERENCES users(id)
-- DO — 逻辑外键（应用层维护关系）
-- 仅加索引
INDEX idx_orders_user_id (user_id)
```

## 3. 索引设计规范

### 3.1 索引创建原则

- 主键：必须有
- 外键列：必须建索引
- WHERE 条件列：建索引
- ORDER BY / GROUP BY 列：建索引
- JOIN ON 列：建索引
- 唯一约束列：建唯一索引
- 单表索引数 ≤ 5
- 联合索引列数 ≤ 4

### 3.2 联合索引顺序

```sql
-- 最左前缀原则
-- 等值查询在前，范围查询在后
CREATE INDEX idx_orders_user_status_created
ON orders(user_id, status, created_at);
-- 有效使用场景：
-- WHERE user_id = 1
-- WHERE user_id = 1 AND status = 1
-- WHERE user_id = 1 AND status = 1 ORDER BY created_at
```

### 3.3 索引类型选择

| 场景 | 索引类型 | 说明 |
|------|----------|------|
| 等值查询 | B-Tree | 默认 |
| 范围查询 | B-Tree | |
| 全文搜索 | FULLTEXT | |
| 地理坐标 | SPATIAL (R-Tree) | |
| 精确匹配 | HASH | Memory 引擎 |
| JSON 路径 | 函数索引 | `INDEX((json_col->'$.key'))` |

## 4. 分库分表设计

### 4.1 分片策略

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| 按范围 | id 1-1000万一张表 | 时序数据 |
| 按 Hash | `hash(user_id) % N` | 均匀分布 |
| 按时间 | 按月/日分表 | 日志类数据 |
| 按业务 | 用户表 / 订单表分开 | 微服务 |

### 4.2 分片规则

- 分片键选查询频率最高的列
- 禁止跨分片 JOIN（用应用层聚合）
- 全局唯一 ID：雪花算法 / UUID
- 分表数量从 2 的幂开始（4, 8, 16...）

## 5. SQL 性能规约（上线前验证）

### 5.1 索引失效防线

- **禁止对索引列做函数/运算**：`WHERE YEAR(created_at) = 2026` 索引失效——改范围查询 `created_at >= '2026-01-01' AND created_at < '2027-01-01'`
- **禁止隐式类型转换**：字符串列用数字查询（`WHERE phone = 13800138000`）索引失效——保持类型一致
- **禁止左模糊/前缀通配**：`LIKE '%xx'` 无法走索引（`LIKE 'xx%'` 可以）
- **OR 连接非索引列**：任一侧无索引则全表扫描——改 UNION
- **联合索引最左前缀**：查询条件必须含首列，等值在前范围在后

### 5.2 查询规约

- **上线前必须 EXPLAIN**：新增/变更 SQL 在类生产数据量下 `EXPLAIN` 验证——type 至少 range 级、rows 估算合理、无 Using filesort/temporary（除非确认可接受）
- **深分页优化**：`LIMIT 1000000, 20` 禁止——改游标分页（`WHERE id > last_id LIMIT 20`）或子查询先定位主键
- **IN 子句控制规模**：元素数 ≤1000；超限分批或临时表 JOIN
- **count 优化**：`COUNT(*)` 语义明确且性能等效（MyISAM 有缓存/InnoDB 会扫）；统计行数用 `COUNT(*)` 不用 `COUNT(列)`（后者忽略 NULL 语义不同）；大表实时计数用计数表/缓存不直查
- **禁 `SELECT *`** 已在 CODING 主文件 §5——此处重申：只取需要的列（减少 IO 与网络）
- 负向条件（`!=` / `NOT IN` / `NOT EXISTS`）难走索引——能改正向写法就改

### 5.3 DML 纪律

- 大批量 UPDATE/DELETE **分批执行**（单批影响行数受控，避免长事务锁与主从延迟）
- INSERT 必须显式列名（表结构演进不改断代码）
- 事务范围最小化：事务内禁止 RPC/耗时计算（连接占用+锁持有时间放大）

## 6. 数据迁移与变更

### 6.1 DDL 变更规范

- 生产环境 DDL 必须通过工具执行（gh-ost / pt-online-schema-change）
- 禁止 ALTER 大表（锁表风险）
- 新增列必须有默认值
- 禁止删除列（先标记废弃，保留 2 个迭代后再删）
- 禁止修改列类型（新建列 + 数据迁移 + 删旧列）

### 6.2 数据迁移

- 旧列废弃标记：`deprecated_column` 保留
- 新旧列双写过渡期
- 数据校验通过后切流
- 回滚方案必须提前准备
