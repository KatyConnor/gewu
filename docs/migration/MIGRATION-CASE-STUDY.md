# Migration Case Study: OpenCode → Gewu

## Environment

| Metric | Value |
|--------|-------|
| Users | 25,000 |
| Sessions | 100,000 |
| Agents | 50,000 |
| Agent tools | 120,000 |
| Messages (V1) | 1,200,000 rows across `message` + `message_part` |
| Database size | 45 GB |
| Migration host | c5.4xlarge (16 vCPU, 32 GB RAM) |
| Source DB | MySQL 8.0.35, RDS db.r5.xlarge |
| Target DB | MySQL 8.0.38, RDS db.r6g.xlarge |

## Pre-migration

The data quality check took approximately **2 hours**. Checks performed:

- NULL value scan across all source columns
- Foreign key orphan detection
- ULID format validation for all ID columns
- Character set verification (utf8mb4 confirmed)
- Duplicate detection (none found)

**Findings:** 12 sessions with trailing whitespace in titles (auto-trimmed during migration). 8 agent tool records with NULL description fields (mapped to empty string for Gewu).

## Migration Execution

| Step | Duration | Records | Issues |
|------|----------|---------|--------|
| User migration | 4m 32s | 25,000 | None |
| Session migration | 8m 15s | 100,000 → 100,000 sessions + 100,000 projects | None |
| Message merge + migration | 22m 40s | 1,200,000 → 800,000 merged records | 3 FK violations (see below) |
| Agent migration | 4m 10s | 50,000 | None |
| Agent tool migration | 3m 05s | 120,000 | None |
| ULID reconciliation | 2m 22s | All entities | None |
| **Total** | **45m 04s** | | |

## Issues Encountered

### Issue #1: Orphaned Session References (3 records)

Three `message` records referenced `session_id` values pointing to sessions whose owning users had been deleted from OpenCode. The sessions themselves existed but were unreachable through normal FK paths.

**Resolution:** A cleanup script removed the orphaned session references by:

1. Identifying the 3 affected messages
2. Creating placeholder tombstone references in the target for FK compliance
3. Flagging the orphaned records in the validation report for manual review

### Issue #2: Truncation on Long Tool Descriptions (1 record)

One agent tool had a description field exceeding Gewu's 1024-character VARCHAR limit (1,823 characters).

**Resolution:** The description was truncated to 1024 characters at the nearest sentence boundary. The original full description was preserved in a `legacy.description` JSON field on the record.

### Issue #3: Session Title Encoding (12 records)

Unicode emoji in session titles encoded as `utf8mb3` in OpenCode but required `utf8mb4` in Gewu.

**Resolution:** The migration toolkit detected the encoding mismatch and re-encoded the affected titles during ingestion. No data loss.

## Validation

| Check | Duration | Result |
|-------|----------|--------|
| Record count match | 2m 10s | 6/6 tables matched |
| FK integrity | 1m 45s | All constraints satisfied |
| Data spot-check (1%) | 12m 30s | 99.97% pass rate |
| SM3 hash reconciliation | 3m 20s | All ID maps aligned |
| Application smoke test | 10m 00s | All workflows verified |
| **Total** | **29m 45s** | **PASS** |

## Post-migration

- Logins functional: first user signed in within 30 seconds of go-live
- Session history browsable: all 100,000 sessions accessible
- Agent configurations preserved: all 50,000 agents operational
- Peak load (2,000 concurrent users) sustained without errors
- 24-hour monitoring: no data integrity incidents reported

## Lessons Learned

1. Run FK orphan check earlier in the pre-migration phase to budget cleanup time
2. Add character encoding detection to the pre-migration quality check
3. Reserve buffer time for edge-case data issues (~15% of total migration window)
4. Automate the orphan cleanup script as part of the migration toolkit
