# NoSQL 设计规范

> Agent 在设计和操作 NoSQL 数据库时必须遵守本文件规则。
> 覆盖 MongoDB / Redis / Elasticsearch 三大主流 NoSQL。

---

## 1. MongoDB 规范

### 1.1 命名规范

| 对象 | 风格 | 示例 | 说明 |
|------|------|------|------|
| 数据库 | snake_case | `user_service` | 禁止保留字 |
| 集合 | snake_case 复数 | `users`, `order_items` | |
| 字段 | snake_case | `user_id`, `created_at` | |
| 索引 | `idx_<field>` | `idx_user_id` | |
| `_id` | ObjectId 或自定义 | `ObjectId(...)` | 主键 |

### 1.2 文档设计

```javascript
// DON'T — 过度嵌套
db.users.insertOne({
    profile: {
        basic: {
            name: "Alice",
            contact: {
                email: "alice@example.com",
                phone: {
                    mobile: "13800000000",
                    work: "021-12345678"
                }
            }
        }
    }
});

// DO — 适度嵌套（2-3 层）
db.users.insertOne({
    name: "Alice",
    email: "alice@example.com",
    phone_mobile: "13800000000",
    phone_work: "021-12345678",
    created_at: ISODate("2026-01-01T00:00:00Z"),
    updated_at: ISODate("2026-01-01T00:00:00Z"),
    status: "active",
    tags: ["vip", "premium"]
});
```

### 1.3 文档设计原则

- 内嵌优先（1:1 和 1:N（少量）关系）
- 引用适用于 N:M 和大数据关联
- 单文档大小 ≤ 16MB
- 嵌套层级 ≤ 3
- 数组长度 ≤ 100（大数组用引用）
- 预留模式设计（字段预留扩展空间）

### 1.4 索引设计

```javascript
// 创建索引（后台执行避免锁表）
db.users.createIndex({ email: 1 }, { unique: true, background: true });
db.orders.createIndex({ user_id: 1, created_at: -1 }, { background: true });

// ESR 原则：Equality → Sort → Range
// 索引顺序：等值查询在前，排序在中，范围在后
db.orders.createIndex({
    status: 1,       // Equality
    created_at: -1,  // Sort
    amount: 1        // Range
});
```

### 1.5 查询规范

```javascript
// DON'T — 无索引查询
db.users.find({ name: "Alice" });  // name 无索引 → 全表扫描

// DON'T — 正则前导通配符
db.users.find({ name: /^.*alice/i });  // 不走索引

// DO — 前缀匹配
db.users.find({ name: /^alice/i });

// DO — 分页用 limit + skip（小范围）或游标（大范围）
db.users.find({}).limit(20).skip(40);
// 大范围分页用游标
db.users.find({ _id: { $gt: lastId } }).limit(20);
```

### 1.6 事务

```javascript
// MongoDB 4.0+ 支持多文档事务
const session = client.startSession();
try {
    session.startTransaction();
    db.accounts.updateOne(
        { _id: 1 }, { $inc: { balance: -100 } }, { session }
    );
    db.accounts.updateOne(
        { _id: 2 }, { $inc: { balance: 100 } }, { session }
    );
    session.commitTransaction();
} catch (e) {
    session.abortTransaction();
    throw e;
} finally {
    session.endSession();
}
```

## 2. Redis 规范

### 2.1 Key 命名规范

```
<业务域>:<对象类型>:<对象ID>:<属性>
```

**示例：**

```
user:1001:profile       → 用户 1001 的 profile
order:5001:detail       → 订单 5001 的详情
cache:weather:shanghai   → 上海天气缓存
lock:order:5001         → 订单 5001 的分布式锁
counter:pv:article:2001 → 文章 2001 的 PV 计数
```

**规则：**

- 冒号 `:` 分隔层级
- Key 长度 ≤ 128 字符
- 禁止特殊字符（空格、换行、中文）
- Key 必须见名知意
- 禁止大 Key（单个 Value > 10KB 拆分或用 Hash）

### 2.2 数据结构选择

| 场景 | 数据结构 | 示例 |
|------|----------|------|
| 缓存对象 | String (JSON) | `SET user:1001 '{"name":"Alice"}'` |
| 对象多字段 | Hash | `HSET user:1001 name Alice email alice@example.com` |
| 计数器 | String + INCR | `INCR counter:pv:1001` |
| 排行榜 | Sorted Set | `ZADD leaderboard 100 alice` |
| 标签/集合 | Set | `SADD tags:1001 vip premium` |
| 消息队列 | List | `LPUSH queue:tasks '{"task":"..."}'` |
| 限流 | Sorted Set / String + TTL | `INCR rate:1001` + `EXPIRE 60` |
| 分布式锁 | String + NX + TTL | `SET lock:1001 token NX PX 30000` |

### 2.3 缓存策略

```python
# Cache-Aside 模式（推荐）
def get_user(user_id):
    # 1. 查缓存
    cached = redis.get(f"user:{user_id}:profile")
    if cached:
        return json.loads(cached)

    # 2. 查数据库
    user = db.get_user(user_id)
    if user:
        # 3. 写缓存（带过期时间）
        redis.setex(
            f"user:{user_id}:profile",
            3600,  # 1小时过期
            json.dumps(user)
        )
    return user

# 防缓存穿透（空值缓存）
def get_user(user_id):
    cached = redis.get(f"user:{user_id}:profile")
    if cached is not None:
        return json.loads(cached) if cached != "NULL" else None
    user = db.get_user(user_id)
    if user:
        redis.setex(f"user:{user_id}:profile", 3600, json.dumps(user))
    else:
        redis.setex(f"user:{user_id}:profile", 60, "NULL")  # 空值缓存 60s
    return user

# 防缓存雪崩（随机过期时间）
redis.setex(key, 3600 + random.randint(0, 300), value)
```

### 2.4 禁止事项

- **禁止** `KEYS *`（阻塞，用 `SCAN` 替代）
- **禁止** 大 Key（Value > 10KB，Hash 单 Key 字段 > 1000）
- **禁止** 无过期时间的缓存
- **禁止** `FLUSHALL` / `FLUSHDB` 生产环境
- **禁止** 事务嵌套（用 Lua 脚本替代）

## 3. Elasticsearch 规范

### 3.1 索引命名

```
<业务域>-<对象>-<时间后缀>
```

**示例：**

```
logs-app-2026.08     → 应用日志索引
orders-2026.08       → 订单索引
users-v2             → 用户索引 v2
```

**规则：**

- 小写字母 + 横杠
- 禁止大写、下划线、特殊字符
- 时序数据按时间后缀命名，便于删除
- 版本变更用 vN 后缀

### 3.2 Mapping 设计

```json
{
    "mappings": {
        "properties": {
            "id":         { "type": "keyword" },
            "title":      { "type": "text", "analyzer": "ik_max_word" },
            "status":     { "type": "keyword" },
            "amount":     { "type": "double" },
            "created_at": { "type": "date" },
            "tags":       { "type": "keyword" },
            "description": {
                "type": "text",
                "analyzer": "ik_max_word",
                "fields": {
                    "keyword": { "type": "keyword", "ignore_above": 256 }
                }
            }
        }
    }
}
```

### 3.3 字段类型选择

| 数据 | 类型 | 说明 |
|------|------|------|
| ID | keyword | 不分词 |
| 精确匹配字段 | keyword | 状态、分类 |
| 全文搜索 | text + analyzer | 标题、描述 |
| 数字 | integer/long/double | 范围查询用 |
| 日期 | date | 时间范围查询 |
| 布尔 | boolean | |
| 嵌套对象 | nested | 对象数组 |
| 地理位置 | geo_point | 坐标查询 |

### 3.4 查询规范

```json
// DON'T — 全文搜索用 wildcard
{
    "query": {
        "wildcard": { "title": "*alice*" }
    }
}

// DO — 用 match 查询
{
    "query": {
        "match": { "title": "alice" }
    }
}

// DO — 精确匹配用 term
{
    "query": {
        "term": { "status": "active" }
    }
}

// 组合查询用 bool
{
    "query": {
        "bool": {
            "must": [
                { "match": { "title": "订单" } }
            ],
            "filter": [
                { "term": { "status": "active" } },
                { "range": { "amount": { "gte": 100 } } }
            ]
        }
    }
}
```

### 3.5 禁止事项

- **禁止** 深度分页（`from + size > 10000`，用 `search_after` 替代）
- **禁止** 通配符开头查询（`*alice` 不走索引）
- **禁止** 生产环境 `DELETE *`（按索引删除，不要按查询删除）
- **禁止** 大量字段 `text` 类型（磁盘和内存开销大）
- **禁止** 动态 Mapping（生产环境必须显式 Mapping）
