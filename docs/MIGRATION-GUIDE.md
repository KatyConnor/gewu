# Migration Guide: OpenCode → Gewu

## Prerequisites

| Requirement | Minimum |
|-------------|---------|
| MySQL | 8.0+ |
| Disk space | 50 GB free (source + target) |
| Backup location | Separate volume or object storage |
| Node.js | 18 LTS+ |
| Gewu toolkit | `@gewu/migration-toolkit` v1.0+ |
| Network | Connectivity between source and target DB |

## Pre-migration Checklist

- [ ] Full backup of OpenCode database
- [ ] Full backup of Gewu database (empty or seeded)
- [ ] Run data quality check script
- [ ] Verify disk space on migration host
- [ ] Confirm network connectivity to both databases
- [ ] Notify stakeholders of scheduled downtime
- [ ] Disable cron jobs and scheduled tasks on OpenCode
- [ ] Set OpenCode to read-only maintenance mode

## Step-by-Step Migration Walkthrough

### Step 1: Backup and Validate

```bash
# Source backup
mysqldump --single-transaction --routines --triggers \
  --databases opencode > /backup/opencode-full-$(date +%F).sql

# Target backup
mysqldump --single-transaction --databases gewu > /backup/gewu-full-$(date +%F).sql

# Run quality check
gewu-migrate quality-check --source opencode --target gewu --output quality-report.json
```

### Step 2: Migrate Users

```bash
gewu-migrate run --source opencode --target gewu --table users
```

Verify: count matches between source `opencode.users` and target `gewu.users`.

### Step 3: Migrate Sessions

```bash
gewu-migrate run --source opencode --target gewu --table sessions
```

Verify: all source session IDs mapped to `gewu.project` + `gewu.session` records.

### Step 4: Migrate Messages (V1 Merge)

```bash
gewu-migrate run --source opencode --target gewu --table messages \
  --merge-v1
```

Verify: every V1 `message` + `message_part` group appears as one `gewu.session_message`.

### Step 5: Migrate Agents and Tools

```bash
gewu-migrate run --source opencode --target gewu --table agents
gewu-migrate run --source opencode --target gewu --table agent-tools
```

Verify: agent and tool counts match; foreign keys reference valid user IDs.

## Verification Steps

1. **Record count validation** — Every source table row count matches target
2. **Foreign key integrity** — All FK relationships are satisfied
3. **Data sampling** — Spot-check 1% of records across all tables
4. **ID reconciliation** — SM3 hash map confirms ULID alignment
5. **App smoke test** — Login, view session history, inspect agent config

## Rollback Procedures

| Scenario | Action |
|----------|--------|
| Failure in current step | `gewu-migrate rollback --step <name>` |
| Data quality issue | `gewu-migrate rollback --table <name>` |
| Catastrophic failure | Full restore from SQL backup |
| Application issue | Re-enable old deployment, investigate |

## Post-migration Tasks

- [ ] Re-enable writes on Gewu
- [ ] Generate and archive final validation report
- [ ] Notify stakeholders of completion
- [ ] Monitor application logs for 24 hours
- [ ] Schedule clean-up of OpenCode database (7 days post-migration)
