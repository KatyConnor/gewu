# Migration FAQ: OpenCode → Gewu

## Q1: What if migration fails mid-way?

**A:** The migration toolkit supports checkpoint rollback. Depending on failure severity, you can roll back the current step, the current table, or restore from the full database backup. Every step is designed to be idempotent — once the root cause is fixed, re-run from the failed step.

## Q2: Will users need to reset passwords?

**A:** No. Password hashes are migrated from `opencode.users` to `gewu.users` without re-hashing. Users authenticate immediately after migration with their existing credentials.

## Q3: Will session history be preserved?

**A:** Yes. V1 platform sessions and messages are fully migrated. V1 `message` + `message_part` records are merged into single `gewu.session_message` records with JSON content fields. Original metadata (timestamps, author info) is preserved.

## Q4: Are credentials migrated?

**A:** No. Credentials (API keys, OAuth tokens, third-party service auth) are **out of scope**. Users and integrations must re-authenticate per the new security policy.

## Q5: Can we rollback after migration completes?

**A:** Yes. A four-tier rollback strategy is in place:

| Tier | Scope | Conditions |
|------|-------|------------|
| 1 — Full restore | Entire system | Any post-migration issue |
| 2 — Partial | Single table | Data quality issue isolated to one table |
| 3 — Checkpoint | Current step | Failure during migration execution |
| 4 — Application | Deployment | Switch back to old OpenCode deployment |

## Q6: How long does the migration take?

**A:** Estimated timeline for 25K users, 100K sessions, 50K agents:

| Phase | Duration |
|-------|----------|
| Pre-migration quality check | ~2 hours |
| User migration | ~5 minutes |
| Session migration | ~10 minutes |
| Message merge + migration | ~20 minutes |
| Agent/tool migration | ~5 minutes |
| ULID reconciliation | ~5 minutes |
| Validation | ~30 minutes |
| **Total** | **~3 hours 15 minutes** |

The bulk of time is the pre-migration data quality check and post-migration validation.

## Q7: What happens to running agents during migration?

**A:** All agents must be stopped before migration begins. OpenCode is set to read-only maintenance mode, which prevents agent execution. After migration completes and Gewu is live, agents are re-registered with their configuration preserved. Agent execution resumes only after user verification.

## Q8: Is there downtime required?

**A:** Yes. OpenCode must be placed in read-only maintenance mode for the duration of the migration (~3–4 hours). The maintenance window should be scheduled during lowest traffic periods (recommended 02:00–06:00). Users will see a maintenance page during this time.

## Q9: How do I verify data integrity after migration?

**A:** Run the following verification steps:
1. Record count comparison — every source table count matches its target
2. Foreign key integrity check — no orphaned references
3. SM3 hash reconciliation — source and target ID maps align
4. Data spot-check — randomly sample 1% of records
5. Application smoke test — login, browse session history, inspect agent config

## Q10: What if a ULID collision occurs?

**A:** ULID collision is extremely unlikely with 26-character Crockford Base32 encoding. If detected during reconciliation, the migration toolkit re-encodes the colliding ID with a random suffix and updates the SM3 hash map. All referencing foreign keys are updated atomically within the same transaction.

## Q11: Are audit logs migrated?

**A:** No. Audit log format is incompatible between platforms. A fresh audit trail starts in Gewu. Historical OpenCode audit logs remain accessible in the old database backup for compliance purposes.

## Q12: Can I run the migration in parallel across multiple workers?

**A:** No. Tables have dependency ordering (users → sessions → messages → agents). Parallelizing across dependent tables would cause FK violations. Within a single table, the migration tool batches records but runs sequentially.

## Q13: What happens to file attachments?

**A:** File attachments are out of scope and require re-upload. The migration produces a manifest of all attachment paths and filenames from OpenCode storage to assist users with re-uploading.

## Q14: Will custom integrations or webhooks keep working?

**A:** No. API keys are re-generated and endpoints change. Integration owners must update their configurations post-migration. A migration guide for integration owners is provided separately.
