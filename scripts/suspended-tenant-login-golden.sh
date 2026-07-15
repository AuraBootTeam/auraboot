#!/usr/bin/env bash
#
# suspended-tenant-login-golden.sh — E5: a suspended organization cannot log in.
#
# The change lives in LoginCompletionHelper: after a password is verified, if the user's TENANT
# (the organization, not the member row) is suspended, login is refused before any JWT or session
# is minted. This is auth-core: get it wrong in the other direction and you lock everyone out. So
# the golden proves BOTH directions against a real stack, and proves the block is reversible:
#
#   1. baseline    — bootstrap tenant A, log in → a JWT comes back.
#   2. suspend     — set the tenant's status to 'suspended' (what the admin console's suspend does).
#   3. blocked     — the SAME credentials now return NO JWT and an error that names the suspension.
#   4. resume      — set the status back to 'active'.
#   5. unblocked   — the same credentials log in again → the block was the suspension, nothing else.
#
# Host-first, zero docker. Brings up an OSS backend built from THIS worktree (so the change under
# test is the one exercised) and tears it down afterward. Its exit code IS the result. The full
# matrix (only SUSPENDED blocks; inactive/none do not; no token is minted) is covered by
# LoginCompletionHelperTest; this is the end-to-end proof.
#
#   scripts/suspended-tenant-login-golden.sh [--slot N] [--keep] [--rebuild]
#
set -euo pipefail

SLOT="${E5_SLOT:-72}"
BE_PORT=$((6400 + SLOT))
DB="auraboot_e5_${SLOT}"
KEEP=0
REBUILD=0
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
ADMIN_EMAIL="admin@e5-suspend.local"
ADMIN_PW="Admin@12345"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot) SLOT="${2:?}"; BE_PORT=$((6400 + SLOT)); DB="auraboot_e5_${SLOT}"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --rebuild) REBUILD=1; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[36m[e5-suspend]\033[0m %s\n' "$*"; }
fail() { printf '\033[31m[e5-suspend] ❌ %s\033[0m\n' "$*" >&2; exit 1; }

cleanup() {
  local code=$?
  if [ "$KEEP" -eq 1 ]; then log "--keep: leaving stack up (backend :$BE_PORT, db $DB)"; return; fi
  log "tearing down"
  [ -n "${BE_PID:-}" ] && kill "$BE_PID" 2>/dev/null || true
  psql -h localhost -d postgres -q -c "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE);" 2>/dev/null || true
  exit $code
}
trap cleanup EXIT INT TERM

jwt_of() { python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('jwt') or '')" 2>/dev/null; }
tid_of() { python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('tenantId') or '')" 2>/dev/null; }
# The user-facing text is the localized 'context'/detail (message is the generic code desc,
# "Business error", for every BusinessException); in dev the detail is a small object.
msg_of() { python3 -c "
import sys,json
d=json.load(sys.stdin); c=d.get('context')
if isinstance(c,dict): c=c.get('detail') or c.get('message') or json.dumps(c,ensure_ascii=False)
print((c or d.get('message') or '')[:120])" 2>/dev/null; }

# ---- build (once) ------------------------------------------------------------------------
JAR="$(ls "$REPO_ROOT"/platform/build/libs/*-boot.jar 2>/dev/null | head -1 || true)"
if [ "$REBUILD" -eq 1 ] || [ -z "$JAR" ]; then
  log "build OSS bootJar (default ~/.gradle / ~/.m2 — no per-runtime cache surprises)"
  ( cd "$REPO_ROOT/platform" && env -u MAVEN_OPTS -u GRADLE_OPTS -u MAVEN_REPO_LOCAL \
      ./gradlew --no-daemon :bootJar -x test --console=plain ) >/tmp/e5-bootjar.log 2>&1 \
    || fail "bootJar build failed — see /tmp/e5-bootjar.log"
  JAR="$(ls "$REPO_ROOT"/platform/build/libs/*-boot.jar 2>/dev/null | head -1 || true)"
fi
[ -n "$JAR" ] || fail "no OSS bootJar found under platform/build/libs (pass --rebuild)"
log "jar: $JAR"

# ---- stack -------------------------------------------------------------------------------
log "fresh db $DB"
IN_USE=$(psql -h localhost -d postgres -tAc "SELECT count(*) FROM pg_stat_activity WHERE datname='$DB' AND pid <> pg_backend_pid();")
[ "$IN_USE" = "0" ] || fail "$IN_USE connection(s) to $DB — slot $SLOT belongs to someone else"
psql -h localhost -d postgres -q -c "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE);"
psql -h localhost -d postgres -q -c "CREATE DATABASE \"$DB\";"

log "schema (flyway, oss)"
PG_DB="$DB" "$REPO_ROOT/scripts/db/flyway-migrate.sh" --edition oss >/dev/null

log "backend :$BE_PORT"
java -Xmx3g -Dspring.profiles.active=dev -Dserver.port="$BE_PORT" \
  -Dspring.datasource.url="jdbc:postgresql://localhost:5432/${DB}?charSet=UTF8" \
  -Dspring.data.redis.host="${REDIS_HOST:-localhost}" -Dspring.data.redis.port="${REDIS_PORT:-6379}" \
  -Dspring.data.redis.database="$((SLOT % 16))" -Dspring.flyway.enabled=false \
  -jar "$JAR" > /tmp/e5-suspend-be.log 2>&1 &
BE_PID=$!
BE_UP=0
for _ in $(seq 1 60); do
  sleep 3
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$BE_PORT/actuator/health")" = "200" ] && { BE_UP=1; break; }
done
[ "$BE_UP" = "1" ] || { grep -A4 "APPLICATION FAILED TO START" /tmp/e5-suspend-be.log | head -6 >&2; fail "backend never healthy on :$BE_PORT — see /tmp/e5-suspend-be.log"; }

BE="http://localhost:$BE_PORT"
login() { curl -sS -X POST "$BE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"identifier\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PW\"}"; }

PASS=0; TOTAL=0
ok() { PASS=$((PASS+1)); TOTAL=$((TOTAL+1)); log "  ✓ $1"; }

log "bootstrap tenant A"
curl -sS -X POST "$BE/api/bootstrap/setup" -H 'Content-Type: application/json' \
  -d "{\"companyName\":\"E5 Suspend\",\"adminEmail\":\"$ADMIN_EMAIL\",\"adminPassword\":\"$ADMIN_PW\",\"systemMode\":\"single\"}" >/dev/null

# ---- 1: baseline — an active tenant logs in --------------------------------------------
log "1/4 baseline login (active tenant)"
BASE=$(login)
JWT=$(printf '%s' "$BASE" | jwt_of)
TENANT_ID=$(printf '%s' "$BASE" | tid_of)
[ -n "$JWT" ] && [ -n "$TENANT_ID" ] && ok "baseline: active tenant $TENANT_ID logs in (JWT issued)" \
  || fail "baseline login failed — cannot test the block. Response: $(printf '%s' "$BASE" | head -c 300)"

# ---- 2: suspend the organization -------------------------------------------------------
log "2/4 suspend tenant $TENANT_ID (what the admin console's suspend writes)"
psql -h localhost -d "$DB" -q -c "UPDATE ab_tenant SET status='suspended' WHERE id=$TENANT_ID;" || fail "could not suspend tenant in DB"

# ---- 3: the SAME credentials are now refused, minting no token -------------------------
log "3/4 login while suspended must be refused"
BLOCKED=$(login)
printf '%s\n' "$BLOCKED" > /tmp/e5-blocked-response.json
BJWT=$(printf '%s' "$BLOCKED" | jwt_of)
[ -z "$BJWT" ] && ok "suspended tenant is refused — no JWT issued" \
  || fail "SUSPENDED TENANT STILL LOGGED IN (JWT issued) — the block did not fire. Response: $(printf '%s' "$BLOCKED" | head -c 300)"
if printf '%s' "$BLOCKED" | grep -qiE "suspend|暂停|tenant.suspended"; then
  ok "the refusal names the suspension (message: $(printf '%s' "$BLOCKED" | msg_of))"
else
  fail "login was refused but the error does not name the suspension: $(printf '%s' "$BLOCKED" | head -c 300)"
fi

# ---- 4: resume → the same credentials work again ---------------------------------------
log "4/4 resume tenant $TENANT_ID → the same credentials log in again (block was reversible)"
psql -h localhost -d "$DB" -q -c "UPDATE ab_tenant SET status='active' WHERE id=$TENANT_ID;" || fail "could not resume tenant in DB"
RESUMED=$(login)
RJWT=$(printf '%s' "$RESUMED" | jwt_of)
[ -n "$RJWT" ] && ok "resumed tenant logs in again (JWT issued) — the block was the suspension, nothing permanent" \
  || fail "tenant was resumed but login still fails — the change locked the account out permanently. Response: $(printf '%s' "$RESUMED" | head -c 300)"

if [ "$PASS" = "$TOTAL" ]; then
  log "✅ suspended-tenant-login golden PASSED ($PASS/$TOTAL) — suspend blocks, resume restores, no permanent lockout"
  exit 0
fi
fail "suspended-tenant-login golden FAILED ($PASS/$TOTAL)"
