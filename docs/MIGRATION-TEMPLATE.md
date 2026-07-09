# Migration Plan Template: OpenCode → Gewu

Use this template to document and track each migration project.

---

## Project Information

| Field | Value |
|-------|-------|
| **Project Name** | |
| **Source System** | OpenCode v___ |
| **Target System** | Gewu v___ |
| **Migration Lead** | |
| **Date** | |
| **Status** | ☐ Draft ☐ Approved ☐ In Progress ☐ Complete |

## Scope Definition

### In Scope

| Source Table | Target Table | Transformation |
|--------------|--------------|---------------|
| `opencode.users` | `gewu.users` | |
| `opencode.sessions` | `gewu.project` + `gewu.session` | |
| `opencode.message` + `opencode.message_part` | `gewu.session_message` | V1 merge |
| `opencode.agents` | `gewu.agent` | |
| `opencode.tools` | `gewu.agent_tool` | |

### Out of Scope

- [ ] Credentials (re-auth required)
- [ ] Audit logs (incompatible format)
- [ ] File attachments (re-upload required)
- [ ] API keys (re-generate required)
- [ ] Other: ____________________

## Migration Schedule

| Milestone | Date/Time | Owner |
|-----------|-----------|-------|
| Pre-migration quality check | | |
| Migration window start | | |
| Migration window end | | |
| Post-migration validation | | |
| Go/no-go decision | | |

## Team Roles

| Role | Name | Responsibilities |
|------|------|-----------------|
| Migration Lead | | Overall coordination, decision-making |
| Database Admin | | Backup, restore, schema management |
| Application Owner | | App configuration, smoke testing |
| Rollback Commander | | Authorize rollback decisions |
| Stakeholder Contact | | Status updates, communication |
| Operations | | Monitoring, alerting |

## Pre-migration Tasks

- [ ] Full backup of OpenCode database
- [ ] Full backup of Gewu database
- [ ] Run data quality check on source
- [ ] Generate validation report
- [ ] Confirm disk space (50 GB minimum)
- [ ] Verify network connectivity
- [ ] Notify stakeholders
- [ ] Disable writes to OpenCode
- [ ] Stop all running agents
- [ ] Other: ____________________

## Migration Steps

| Step | Command | Est. Duration | Verification |
|------|---------|---------------|-------------|
| 1. Migrate users | `gewu-migrate run --table users` | 5 min | Count match |
| 2. Migrate sessions | `gewu-migrate run --table sessions` | 10 min | Count match |
| 3. Migrate messages (V1 merge) | `gewu-migrate run --table messages --merge-v1` | 25 min | Count match, spot-check |
| 4. Migrate agents | `gewu-migrate run --table agents` | 5 min | Count match |
| 5. Migrate agent tools | `gewu-migrate run --table agent-tools` | 5 min | Count match |
| 6. Reconcile ULIDs | `gewu-migrate reconcile-ulids` | 5 min | Hash map alignment |

## Validation Plan

| Check | Method | Pass Criteria |
|-------|--------|---------------|
| Record counts | Automated comparison | All table counts match |
| FK integrity | FK constraint check | Zero orphaned references |
| Data sampling | Random 1% spot-check | < 0.1% discrepancy rate |
| ID reconciliation | SM3 hash map | Full alignment |
| App smoke test | Manual workflow | Login, sessions, agents all functional |

## Rollback Plan

| Tier | Trigger | Action | Est. Time |
|------|---------|--------|-----------|
| Full restore | Catastrophic failure | Restore both DBs from backup | 2 hours |
| Partial rollback | Table-level data quality issue | Roll back single table | 30 min |
| Checkpoint rollback | Step failure | Roll back to last checkpoint | 15 min |
| Application rollback | Post-migration app issue | Re-enable old deployment | 10 min |

## Communication Plan

| Audience | Timing | Method | Message |
|----------|--------|--------|---------|
| End users | 72 hours before | Email | Scheduled maintenance window |
| End users | 30 min before | Status page | System going down |
| Stakeholders | Every 30 min during | Slack/Dedicated channel | Progress update |
| End users | At go-live | Status page + email | System available |
| Engineering | Post-migration (24h) | Incident report | Lessons learned |
