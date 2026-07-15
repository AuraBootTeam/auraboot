#!/usr/bin/env bash
#
# suspended-tenant-login-ui-golden.sh — E5, at the glass: what a user sees when their org is
# suspended. Brings up a host-first OSS stack (Vite + BFF + backend built from THIS worktree),
# suspends the bootstrapped tenant, and drives the real login form in a real browser, asserting the
# localized "该组织已被暂停…" message shows — not a raw code, not a generic "Business error". The
# backend golden (suspended-tenant-login-golden.sh) covers the API contract; this covers the UX.
#
#   scripts/suspended-tenant-login-ui-golden.sh [--slot N] [--keep]
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"
NAME="cs-e5-login-ui"
SLOT="73"
KEEP=0
ADMIN_EMAIL="admin@auraboot.com"
ADMIN_PW="Test2026x"
SHOTS="$REPO_ROOT/test-results/e5-suspended-login-ui"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot) SLOT="${2:?}"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[36m[e5-ui]\033[0m %s\n' "$*"; }
fail() { printf '\033[31m[e5-ui] ❌ %s\033[0m\n' "$*" >&2; exit 1; }
[[ -x "$GS" ]] || fail "oss-golden-stack.sh not found at $GS"

cleanup() {
  local rc=$?
  if [[ "$KEEP" == 1 ]]; then log "--keep: stack '$NAME' left up ($GS env $NAME)"; return; fi
  log "tearing down '$NAME'"
  "$GS" down "$NAME" >/dev/null 2>&1 || true
  "$GS" destroy "$NAME" >/dev/null 2>&1 || true
  exit $rc
}
trap cleanup EXIT INT TERM

log "1/4 fresh OSS stack (Vite + BFF + backend from this worktree → includes E5)"
"$GS" up "$NAME" --slot "$SLOT" --no-warm --fresh-db --ttl 2h || fail "stack bring-up failed"
eval "$("$GS" env "$NAME")" || fail "could not resolve stack env"
BE="$BACKEND_URL"; VITE="$PLAYWRIGHT_BASE_URL"
export PGPASSWORD="${PGPASSWORD:-auraboot}"
log "    backend=$BE  vite=$VITE  db=$PG_DB"

log "2/4 suspend the bootstrapped tenant"
TID=$(curl -sS -X POST "$BE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"identifier\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PW\"}" \
  | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('tenantId') or '')")
[ -n "$TID" ] || fail "could not resolve the admin's tenant id (login failed?)"
psql -h "${PG_HOST:-127.0.0.1}" -p "${PG_PORT:-5432}" -U "${PG_USER:-auraboot}" -d "$PG_DB" -q \
  -c "UPDATE ab_tenant SET status='suspended' WHERE id=$TID;" || fail "could not suspend tenant $TID"
log "    tenant $TID suspended"

log "3/4 warm the login route once (no-warm stack: first hit can cold-reopt)"
curl --noproxy '*' -s -o /dev/null -m 30 "$VITE/login" || true

log "4/4 drive the login form in a real browser"
cd "$REPO_ROOT/web-admin" || fail "web-admin not found"
NO_PROXY=localhost,127.0.0.1 node tests/golden/suspended-tenant-login-ui.mjs \
  --base-url "$VITE" --email "$ADMIN_EMAIL" --password "$ADMIN_PW" --shots "$SHOTS" \
  || fail "suspended-login UI golden FAILED — see screenshots in $SHOTS"

log "✅ suspended-tenant-login UI golden PASSED — screenshots in $SHOTS"
