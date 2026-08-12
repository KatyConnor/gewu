# Migration Checklist: OpenCode → Gewu

## Pre-migration

- [ ] Full backup of OpenCode database
- [ ] Full backup of Gewu database
- [ ] Run data quality check — validate source data
- [ ] Generate and review validation report
- [ ] Confirm disk space (50 GB free minimum)
- [ ] Verify network connectivity between hosts
- [ ] Disable write access to OpenCode (maintenance mode)
- [ ] Notify stakeholders of scheduled downtime window

## Migration Execution

- [ ] Migrate users — `gewu-migrate run --table users`
- [ ] Verify user count matches source
- [ ] Migrate sessions — `gewu-migrate run --table sessions`
- [ ] Migrate messages — `gewu-migrate run --table messages --merge-v1`
- [ ] Migrate agents and tools — `gewu-migrate run --table agents && agent-tools`
- [ ] Reconcile ULID mappings — `gewu-migrate reconcile-ulids`

## Post-migration

- [ ] Validate record counts across all tables
- [ ] Verify foreign key integrity (no orphaned references)
- [ ] Spot-check 1% of migrated records
- [ ] Generate final validation report
- [ ] Run application smoke tests (login, session history, agent config)
- [ ] Enable writes on Gewu and disable maintenance mode

## Rollback Readiness

- [ ] Backup files verified (checksums match before migration)
- [ ] Rollback scripts prepared for each migration step
- [ ] Rollback commander assigned (single decision-maker)
- [ ] Old deployment artifact retained and accessible for immediate re-enable
