# Upgrading AuraBoot

This guide covers the process for upgrading AuraBoot to a new version, including database migrations, plugin compatibility, and rollback procedures.

## Version Scheme

AuraBoot follows semantic versioning: `MAJOR.MINOR.PATCH`

- **MAJOR** -- Breaking changes to the DSL schema, API contracts, or database structure
- **MINOR** -- New features, new API endpoints, non-breaking DSL extensions
- **PATCH** -- Bug fixes, performance improvements, security patches

---

## Standard Upgrade Process

### 1. Read the Release Notes

Before upgrading, check the release notes for:

- Breaking changes and required manual steps
- Database schema changes
- Deprecated features
- Plugin compatibility notes

### 2. Backup

```bash
# Database backup
docker compose exec postgres pg_dump -U auraboot aura_boot > backup_$(date +%Y%m%d_%H%M%S).sql

# File storage backup (if using local storage)
tar -czf files_backup_$(date +%Y%m%d).tar.gz ./data/files

# Record the current version
curl -s http://localhost:6443/actuator/info | jq .
```

### 3. Pull the New Version

```bash
# If running from source
git fetch origin
git checkout v1.2.0  # Replace with target version

# If using Docker images
docker compose --profile full pull
```

### 4. Apply Database Migrations

AuraBoot manages schema changes through `schema.sql`. On startup, the application applies any new DDL statements automatically using `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS` semantics.

For most upgrades, simply restarting the backend is sufficient:

```bash
# Docker
docker compose --profile full up --build -d

# Manual
cd platform && ./gradlew bootRun
```

For major version upgrades, check if a migration script is provided:

```bash
ls scripts/migrations/
# If a migration script exists for your version:
psql -h localhost -U auraboot -d aura_boot -f scripts/migrations/v1_to_v2.sql
```

### 5. Verify

```bash
# Health check
curl -s http://localhost:6443/actuator/health | jq .status

# Version check
curl -s http://localhost:6443/actuator/info | jq .

# Smoke test
curl -s -X POST http://localhost:6443/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@auraboot.com\",\"password\":\"${AURABOOT_ADMIN_PASSWORD}\"}" | jq .code
```

---

## Database Migration Handling

### Automatic DDL

AuraBoot uses `CREATE IF NOT EXISTS` patterns, so schema changes are applied idempotently on startup:

- New tables are created automatically
- New columns added via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
- New indexes created via `CREATE INDEX IF NOT EXISTS`

### Manual Migration Scenarios

Some changes require manual intervention:

| Change Type | Automatic? | Manual Step |
|-------------|------------|-------------|
| New table | Yes | None |
| New column | Yes | None |
| New index | Yes | None |
| Column rename | No | Run provided migration SQL |
| Column type change | No | Run provided migration SQL |
| Data backfill | No | Run provided migration SQL |
| Table drop | No | Run provided migration SQL |

When manual migration is required, scripts are provided in `scripts/migrations/` and documented in the release notes.

### Pre-Migration Checklist

1. **Backup** the database (see above)
2. **Check disk space** -- migrations may temporarily double table sizes during `ALTER TABLE`
3. **Plan for downtime** -- large table migrations can lock tables for seconds to minutes
4. **Test on staging** -- run the upgrade on a copy of production data first

---

## Plugin Compatibility

### Semantic Versioning

Plugins declare their minimum platform version in `plugin.json`:

```json
{
  "code": "crm",
  "version": "1.2.0",
  "platformVersion": ">=1.0.0"
}
```

### Upgrade Order

When upgrading both the platform and plugins:

1. Upgrade the platform first
2. Then re-import plugins

```bash
# After platform upgrade, re-import all plugins
aura plugin publish plugins/crm --yes
aura plugin publish plugins/sales --yes
# ... repeat for all plugins
```

### Breaking Changes in Plugins

If a platform upgrade changes DSL schema, field types, or command pipeline stages, affected plugins may need updates. The import process validates plugin compatibility and reports errors:

```bash
aura plugin validate plugins/crm
```

### Plugin Import Idempotency

Plugin imports are idempotent -- re-importing a plugin updates existing resources without creating duplicates. It is safe to re-import all plugins after any upgrade.

---

## Rollback Procedures

### Quick Rollback (< 5 minutes)

If the new version has issues and no database migration was applied:

```bash
# Docker: revert to previous image
docker compose --profile full down
git checkout v1.1.0  # Previous version
docker compose --profile full up --build -d

# Manual: checkout previous version and restart
git checkout v1.1.0
cd platform && ./gradlew bootRun
```

### Rollback After Database Migration

If a database migration was applied:

1. **Stop the application**

```bash
docker compose --profile full down
```

2. **Restore the database backup**

```bash
# Drop and recreate the database
docker compose exec postgres psql -U auraboot -c "DROP DATABASE aura_boot;"
docker compose exec postgres psql -U auraboot -c "CREATE DATABASE aura_boot;"

# Restore from backup
cat backup_20260411_120000.sql | docker compose exec -T postgres psql -U auraboot aura_boot
```

3. **Revert to the previous version**

```bash
git checkout v1.1.0
docker compose --profile full up --build -d
```

4. **Re-import plugins** (if plugin resources were modified during upgrade)

```bash
aura plugin publish plugins/crm --yes
```

---

## Breaking Changes Checklist

Before upgrading a major version, verify these items:

### API Changes

- [ ] Check for deprecated API endpoints that were removed
- [ ] Update client code for renamed parameters or changed response formats
- [ ] Verify webhook payload format if consuming webhooks externally

### DSL Changes

- [ ] Check for renamed or removed DSL properties
- [ ] Validate all plugins against the new platform version
- [ ] Re-import plugins after upgrade

### Configuration Changes

- [ ] Check for renamed environment variables
- [ ] Check for changed default values
- [ ] Review `application.yml` for new required settings

### Frontend Changes

- [ ] Clear browser cache after upgrade
- [ ] Check for changed route paths
- [ ] Verify custom pages still render correctly

### Security Changes

- [ ] Rotate JWT secret if the signing algorithm changed
- [ ] Review new permission codes and update roles
- [ ] Check CORS settings if frontend URL changed

---

## Zero-Downtime Upgrades

For production environments requiring zero downtime:

### Rolling Update (Kubernetes)

```bash
kubectl set image deployment/backend backend=ghcr.io/aurabootteam/auraboot-backend:v1.2.0 -n auraboot
kubectl rollout status deployment/backend -n auraboot
```

Kubernetes performs a rolling update -- old pods serve traffic until new pods pass health checks.

### Blue-Green (Docker Compose)

1. Start new version on different ports
2. Run health checks against new version
3. Switch load balancer to new version
4. Stop old version

### Requirements for Zero-Downtime

- Database schema changes must be backward-compatible (additive only)
- Redis must be enabled for session/lock coordination
- JWT key rotation uses the `PREVIOUS_SECRET` mechanism (both old and new tokens work during transition)

---

## Monitoring After Upgrade

After upgrading, monitor for 1 hour:

```bash
# Error rate
docker compose logs backend | grep ERROR | wc -l

# Response times (via Prometheus/Grafana)
# Check /actuator/metrics/http.server.requests

# Application health
watch -n 5 'curl -s http://localhost:6443/actuator/health | jq .status'
```

Key metrics to watch:

- HTTP error rate (5xx responses)
- Average response time
- Database connection pool usage
- Memory consumption
- Background job failures (webhook delivery, async commands)
