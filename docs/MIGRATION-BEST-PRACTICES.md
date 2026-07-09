# Migration Best Practices: OpenCode → Gewu

## General Principles

- **Backup first, always** — Never run a migration step without a reversible backup of both databases.
- **Test on staging** — Run the full migration on a staging environment at least twice before production.
- **Keep the old system** — Retain read-only access to OpenCode for at least 7 days post-migration.
- **One table at a time** — Migrate in dependency order; never parallelize across dependent entities.
- **Idempotent operations** — Every migration step should be safe to re-run.
- **Log everything** — Capture stdout, stderr, and timing for every step.

## Data Mapping Strategies

### ULID Compatibility

OpenCode CHAR(26) IDs are ULID-native. Verify upstream assumptions before adding transforms:

```sql
-- Check that all IDs are valid ULID format
SELECT COUNT(*) FROM opencode.users WHERE id NOT REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$';
```

### V1 Message Merge

When merging `message` + `message_part`:

- Preserve original `created_at` of the parent message as the session message timestamp
- Concatenate `message_part.body` in part sequence order using double newline (`\n\n`)
- Attach `message_part.metadata` as a JSON array on the merged record
- Include original V1 `message_id` in a `legacy.v1_message_id` field for traceability

### Character Encoding

Both systems use UTF-8 MB4. Confirm with:

```sql
SHOW VARIABLES LIKE 'character_set_database';
-- Must return utf8mb4
```

## Testing Approaches

| Test Type | Scope | Frequency |
|-----------|-------|-----------|
| Dry run | Full pipeline, no writes | Every migration run |
| Count match | All tables | After each step |
| FK integrity | Cross-table references | After migration |
| Data spot-check | 1% random sample | After migration |
| App smoke test | Key user workflows | Post-migration |
| Load test | Simulate 10x normal traffic | Pre-production |

## Production Migration Checklist

- [ ] Schedule during lowest traffic window (recommended: 02:00–06:00)
- [ ] Announce maintenance window 72 hours in advance
- [ ] Freeze deployments 24 hours before migration
- [ ] Run dry-run on production copy the day before
- [ ] Assign a rollback commander (single decision-maker)
- [ ] Monitor dashboards and alerts during migration
- [ ] Have a conference bridge open for the team
- [ ] Keep a manual SQL fallback script for each step

## Common Pitfalls and How to Avoid Them

### Pitfall 1: Orphaned Foreign Keys

**Problem:** Rows in `opencode.sessions` referencing deleted users.

**Prevention:** Run this query pre-migration:

```sql
SELECT s.id FROM opencode.sessions s
LEFT JOIN opencode.users u ON s.user_id = u.id
WHERE u.id IS NULL;
```

**Resolution:** Scripted cleanup or flagging for manual review.

### Pitfall 2: Unexpected NULL Values

**Problem:** Gewu `NOT NULL` constraints fail on nullable source columns.

**Prevention:** Compare schemas before migration:

```bash
gewu-migrate schema-compare --source opencode --target gewu
```

### Pitfall 3: Transaction Log Bloat

**Problem:** Large batch inserts fill MySQL transaction logs.

**Prevention:** Batch inserts in chunks of 1,000 with periodic commits.

### Pitfall 4: Timeout Mid-batch

**Problem:** Long-running queries hit `wait_timeout`.

**Prevention:** Set generous timeouts before starting:

```sql
SET SESSION wait_timeout = 28800;
SET SESSION net_read_timeout = 600;
SET SESSION net_write_timeout = 600;
```

### Pitfall 5: Duplicate ULIDs

**Problem:** Two source systems generate the same ULID.

**Prevention:** ULID recomputation with collision detection. Gewu re-encodes using Crockford Base32 with a random suffix on collision.
