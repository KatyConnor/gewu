# SQL 编写规范

> Agent 生成 SQL 语句时必须遵守本文件规则。
> 适用于 MySQL / PostgreSQL / Oracle / SQL Server 等关系型数据库。

---

## 1. 基本风格

### 1.1 关键字大写

```sql
-- DON'T
select id, name from users where status = 'active';

-- DO
SELECT id, name
FROM users
WHERE status = 'active';
```

### 1.2 缩进与换行

```sql
-- 每个子句独占一行，关键字右对齐或左对齐
SELECT
    u.id,
    u.name,
    u.email,
    o.order_id,
    o.amount
FROM users u
INNER JOIN orders o ON u.id = o.user_id
WHERE u.status = 'active'
  AND o.created_at >= '2026-01-01'
ORDER BY u.id
LIMIT 100;
```

### 1.3 列名显式指定

```sql
-- DON'T — SELECT *
SELECT * FROM users;

-- DO — 显式列名
SELECT
    id,
    name,
    email,
    created_at
FROM users;
```

## 2. 命名规范

| 对象 | 风格 | 示例 | 说明 |
|------|------|------|------|
| 表 | snake_case | `users` | 复数形式 |
| 列 | snake_case | `user_id` | |
| 主键 | `id` 或 `table_id` | `id` / `user_id` | |
| 外键 | `table_id` | `order_id` | |
| 索引 | `idx_table_col` | `idx_users_email` | |
| 唯一索引 | `uk_table_col` | `uk_users_email` | |
| 视图 | `v_name` | `v_active_users` | |
| 存储过程 | `sp_name` | `sp_create_order` | |
| 触发器 | `trg_name` | `trg_user_insert` | |
| 约束 | `pk/cuk/fk_table_col` | `pk_users_id` | |

## 3. 查询规范

### 3.1 必须带 LIMIT

```sql
-- DON'T — 无 LIMIT 的全表查询
SELECT * FROM orders;

-- DO
SELECT id, user_id, amount, created_at
FROM orders
WHERE created_at >= '2026-01-01'
ORDER BY created_at DESC
LIMIT 100;
```

### 3.2 参数化查询

```sql
-- DON'T — 字符串拼接（SQL 注入风险）
-- Agent 禁止生成此类代码
sql = "SELECT * FROM users WHERE name = '" + name + "'"

-- DO — 参数化查询
-- Python
cursor.execute("SELECT id, name FROM users WHERE name = %s", (name,))

-- Java
PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM users WHERE name = ?");
ps.setString(1, name);

-- Go
db.QueryContext(ctx, "SELECT id, name FROM users WHERE name = $1", name)
```

### 3.3 JOIN 规范

```sql
-- DO — 显式 JOIN，不用隐式连接
SELECT
    u.id,
    u.name,
    o.order_id,
    o.amount
FROM users u
INNER JOIN orders o ON u.id = o.user_id
WHERE u.status = 'active';

-- JOIN 列必须有索引
-- CREATE INDEX idx_orders_user_id ON orders(user_id);

-- 多表 JOIN 不超过 3 张表
-- 超过 3 张考虑拆分子查询或分步查询
```

### 3.4 子查询 vs JOIN

```sql
-- 关联子查询性能差，优先 JOIN
-- DON'T
SELECT id, name
FROM users u
WHERE EXISTS (
    SELECT 1 FROM orders o
    WHERE o.user_id = u.id
    AND o.amount > 1000
);

-- DO
SELECT DISTINCT u.id, u.name
FROM users u
INNER JOIN orders o ON u.id = o.user_id
WHERE o.amount > 1000;
```

### 3.5 聚合查询

```sql
-- DON'T — 无 GROUP BY 的聚合+列混合
SELECT name, COUNT(*) FROM users;

-- DO
SELECT
    department,
    COUNT(*) AS user_count,
    AVG(salary) AS avg_salary
FROM users
GROUP BY department
HAVING COUNT(*) > 10
ORDER BY user_count DESC;
```

## 4. 事务规范

```sql
-- 显式事务，明确隔离级别
BEGIN TRANSACTION;
    -- 或 SET TRANSACTION ISOLATION LEVEL READ COMMITTED;

    INSERT INTO orders (user_id, amount, status) VALUES (1001, 99.99, 'pending');
    UPDATE inventory SET stock = stock - 1 WHERE product_id = 5001;

    -- 检查影响行数
    -- 如果影响行数不匹配预期，ROLLBACK

COMMIT;
-- 出错时: ROLLBACK;
```

### 事务规则

- 事务范围最小化（不包含网络调用、文件操作）
- 大事务拆分为小事务
- 读写分离场景用 READ COMMITTED
- 关键业务用 REPEATABLE READ 或 SERIALIZABLE
- 检查影响行数，不匹配时回滚
- 避免长事务（超过 5 秒）

## 5. 索引使用

```sql
-- 索引列必须出现在 WHERE / JOIN ON / ORDER BY
SELECT id, name
FROM users
WHERE email = 'alice@example.com'  -- email 有索引
ORDER BY created_at DESC;           -- created_at 有索引

-- 禁止在索引列上使用函数
-- DON'T
SELECT * FROM users WHERE DATE(created_at) = '2026-01-01';
-- DO
SELECT * FROM users
WHERE created_at >= '2026-01-01'
  AND created_at < '2026-01-02';

-- 禁止在索引列上使用运算
-- DON'T
SELECT * FROM orders WHERE amount / 100 > 10;
-- DO
SELECT * FROM orders WHERE amount > 1000;

-- LIKE 前导通配符不走索引
-- DON'T
SELECT * FROM users WHERE name LIKE '%alice';
-- DO
SELECT * FROM users WHERE name LIKE 'alice%';
```

## 6. 数据修改

```sql
-- UPDATE 必须带 WHERE
UPDATE users
SET status = 'inactive'
WHERE last_login_at < '2025-01-01'
  AND status = 'active';

-- DELETE 必须带 WHERE
DELETE FROM logs
WHERE created_at < '2025-01-01';

-- 大量数据删除用分批
-- DON'T — 一次性删除百万行
DELETE FROM logs WHERE created_at < '2025-01-01';
-- DO — 分批删除
DELETE FROM logs
WHERE id IN (
    SELECT id FROM logs
    WHERE created_at < '2025-01-01'
    LIMIT 1000
);
-- 重复执行直到删除完毕

-- INSERT 批量优于逐条
-- DON'T
INSERT INTO users (name) VALUES ('Alice');
INSERT INTO users (name) VALUES ('Bob');
INSERT INTO users (name) VALUES ('Charlie');
-- DO
INSERT INTO users (name) VALUES
('Alice'), ('Bob'), ('Charlie');

-- INSERT ... ON CONFLICT (UPSERT)
INSERT INTO users (id, name, updated_at)
VALUES (1, 'Alice', NOW())
ON CONFLICT (id)
DO UPDATE SET name = EXCLUDED.name, updated_at = EXCLUDED.updated_at;
```

## 7. 性能注意事项

- `EXPLAIN` 分析执行计划，确认走索引
- 避免全表扫描（type = ALL）
- 避免 filesort（Using filesort）
- 避免 temporary table（Using temporary）
- `COUNT(*)` 优于 `COUNT(列名)`（列名可能为 NULL）
- `LIMIT` 分页深度大时用游标分页：
  ```sql
  -- DON'T — 深度分页
  SELECT * FROM orders ORDER BY id LIMIT 10000, 20;
  -- DO — 游标分页
  SELECT * FROM orders WHERE id > 10000 ORDER BY id LIMIT 20;
  ```
