#!/usr/bin/env bash
# Bootstrap the GA-E2E docker stack for OSS Playwright runs.
#
# What auto-bootstrap (AURABOOT_BOOTSTRAP_ENABLED=true on the backend) gives us:
#   - admin@auraboot.com user with TENANT_ADMIN role on a default tenant
# What it does NOT give us, but the OSS E2E suite needs:
#   - The OSS plugins published into the tenant (model + page + binding rows)
#   - operator / viewer test users that auth.setup expects
#
# This script idempotently fills the gap. Safe to re-run after `docker-ga-e2e-up.sh`.
#
# Usage:
#   ./scripts/docker-ga-e2e-bootstrap.sh                    # default plugin set
#   PLUGINS="showcase workflow-demo" ./scripts/docker-ga-e2e-bootstrap.sh

set -euo pipefail

cd "$(dirname "$0")/.."

# Default plugin set: dependency-ordered minimum that the showcase + workflow-
# demo + page-designer regression suites need. Extend by exporting PLUGINS or
# editing this list.
DEFAULT_PLUGINS=(
  # Layer 0 — no inter-plugin deps; the core platform plugins go first
  # so anything declaring `requires: core-*` resolves on the next pass.
  core-meta
  core-bpm
  core-announcement
  core-aurabot
  org-management
  agent-control-plane
  platform-admin
  page-manager
  golden-path
  showcase
  asset-management
  project-management
  # Layer 1 — depend on layer 0
  crm-starter
  crm-quick-start
  hr-essentials
  simple-inventory
  workflow-demo
  # acp-showcase removed: plugin directory was deleted on commit 59050eed
  # (platformization) and now contains only scripts/ — no plugin.json. Bootstrap
  # would always fail with "Directory does not contain plugin.json".
  #
  # test-fixtures provides the e2et_* model/page/command set that ~100+ specs
  # depend on (action-system, activity, data-tools, permission, saved-view, …).
  # Backend gates the plugin loader behind IMPORT_TEST_FIXTURES=true (set in
  # docker-compose.ga-e2e.override.yml) so this only resolves when run against
  # a stack that has the env var; on host bootRun without the flag, the import
  # api call returns "plugin not allowed".
  test-fixtures
)
PLUGINS=( ${PLUGINS:-${DEFAULT_PLUGINS[@]}} )

API_BASE="http://localhost:6444"
ADMIN_EMAIL="admin@auraboot.com"
ADMIN_PASSWORD="Test2026x"

echo "[ga-e2e-bootstrap] target stack: $API_BASE"
echo "[ga-e2e-bootstrap] plugins (${#PLUGINS[@]}): ${PLUGINS[*]}"

ensure_bootstrap_initialized() {
  local status initialized resp body http_code ok

  status=$(NO_PROXY=localhost curl -fsS "$API_BASE/api/bootstrap/status" || true)
  initialized=$(printf '%s' "$status" | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read() or '{}')
except Exception:
    d = {}
print('yes' if d.get('initialized') is True else 'no')
")

  if [ "$initialized" = "yes" ]; then
    echo "[ga-e2e-bootstrap] bootstrap already initialized"
    return
  fi

  echo "[ga-e2e-bootstrap] bootstrap is not initialized; running /api/bootstrap/setup..."
  resp=$(NO_PROXY=localhost curl -sS -w $'\n%{http_code}' -X POST "$API_BASE/api/bootstrap/setup" \
    -H 'Content-Type: application/json' \
    -d "{
      \"companyName\":\"AuraBoot Dev\",
      \"adminEmail\":\"$ADMIN_EMAIL\",
      \"adminPassword\":\"$ADMIN_PASSWORD\",
      \"adminDisplayName\":\"Admin User\",
      \"systemMode\":\"single\",
      \"seedDemoData\":true
    }")
  http_code=$(printf '%s\n' "$resp" | tail -n 1)
  body=$(printf '%s\n' "$resp" | sed '$d')
  ok=$(printf '%s' "$body" | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read() or '{}')
except Exception:
    d = {}
data = d.get('data') if isinstance(d, dict) else {}
success = d.get('success') is True or (isinstance(data, dict) and data.get('success') is True)
print('yes' if success else 'no')
")

  if [ "$http_code" != "200" ] || [ "$ok" != "yes" ]; then
    echo "[ga-e2e-bootstrap] ERROR: /api/bootstrap/setup failed (HTTP $http_code)" >&2
    printf '%s\n' "$body" >&2
    exit 1
  fi
  echo "[ga-e2e-bootstrap] bootstrap setup OK"
}

ensure_bootstrap_initialized

# 1. Login as admin -> JWT
JWT=$(NO_PROXY=localhost curl -fsS -X POST "$API_BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('jwt',''))")

if [ -z "$JWT" ]; then
  echo "[ga-e2e-bootstrap] ERROR: admin login failed against $API_BASE" >&2
  exit 1
fi
echo "[ga-e2e-bootstrap] admin login OK (jwt=${JWT:0:32}...)"

api_post() {
  NO_PROXY=localhost curl -s -X POST "$API_BASE$1" \
    -H "Authorization: Bearer $JWT" \
    -H 'Content-Type: application/json' \
    -d "$2"
}

command_exists() {
  local command_code="$1"
  local encoded_code="${command_code//:/%3A}"
  local resp
  resp=$(NO_PROXY=localhost curl -s "$API_BASE/api/meta/commands/by-code/$encoded_code" \
    -H "Authorization: Bearer $JWT")
  printf '%s' "$resp" | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
except Exception:
    print('no'); sys.exit(0)
print('yes' if d.get('code') == '0' and d.get('data') else 'no')
"
}

# 2. Import each plugin via import-directory-sync.
# The backend container mounts the host's `./plugins` at /app/plugins:ro,
# so plugin paths inside the container are /app/plugins/<name>.
import_failures=()
for plugin in "${PLUGINS[@]}"; do
  if [ ! -d "plugins/$plugin" ]; then
    echo "  [skip] plugins/$plugin not found in this worktree"
    import_failures+=("$plugin (missing)")
    continue
  fi
  echo -n "  Importing $plugin ... "
  resp=$(api_post "/api/plugins/import/import-directory-sync" \
    "{\"path\":\"/app/plugins/$plugin\",\"overwrite\":true}")
  status=$(printf '%s' "$resp" | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
except Exception:
    print('parse-error'); sys.exit(0)
if isinstance(d, dict):
    if d.get('success') is True:
        print('ok')
    elif d.get('errorMessage'):
        print('err:' + d['errorMessage'][:100])
    elif d.get('code') == '0':
        print('ok')
    else:
        print('err:' + str(d.get('message', d))[:100])
else:
    print('err:unexpected-shape')
")
  case "$status" in
    ok) echo "OK" ;;
    *)  echo "FAIL ($status)"; import_failures+=("$plugin: $status") ;;
  esac
done

if [ "${#import_failures[@]}" -gt 0 ]; then
  echo "[ga-e2e-bootstrap] WARNING: ${#import_failures[@]} plugin(s) failed:"
  printf '  - %s\n' "${import_failures[@]}"
fi

# 3. Provision operator / viewer test users (idempotent — backend rejects
# duplicate emails with a 4xx that we treat as success).
provision_user() {
  local email="$1" pwd="$2" role="$3"
  local display="${email%%@*}"
  local resp
  resp=$(api_post "/api/admin/users" "{
    \"email\":\"$email\",
    \"displayName\":\"$display\",
    \"initialPassword\":\"$pwd\",
    \"roleCodes\":[\"$role\"],
    \"sendInviteEmail\":false
  }")
  result=$(printf '%s' "$resp" | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
except Exception:
    print('parse-error'); sys.exit(0)
data = d.get('data') if isinstance(d, dict) else None
if isinstance(data, dict) and data.get('email'):
    print('created')
else:
    msg = (d.get('message') if isinstance(d, dict) else '') or ''
    print('exists' if 'already exists' in msg.lower() else 'fail:' + msg[:100])
")
  case "$result" in
    created) echo "  $email: created with $role role" ;;
    exists)  echo "  $email: already exists" ;;
    *)       echo "  $email: provision failed ($result)" ;;
  esac
}

echo "[ga-e2e-bootstrap] provisioning test users..."
provision_user "e2e-operator@test.com" "Test2026x" "operator"
provision_user "e2e-viewer@test.com"   "Test2026x" "viewer"

# 3b. Ensure admin@auraboot.com is a member of a "System" (platform) tenant.
# The auraboot bootstrap only seeds a single business tenant ("AuraBoot Demo"),
# but the space-selection E2E suite (and any UI flow that surfaces a Platform
# Console toggle) requires admin to belong to BOTH a platform tenant (named
# "System" — see TenantSelectionController.getMySpaces logic) and a business
# tenant. Idempotently create + bind via /api/tenant-selection/process action=create.
echo "[ga-e2e-bootstrap] ensuring admin is bound to a 'System' platform tenant..."
sys_resp=$(api_post "/api/tenant-selection/process" \
  '{"action":"create","tenantName":"System","displayName":"System Tenant","industry":"technology"}')
sys_status=$(printf '%s' "$sys_resp" | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
except Exception:
    print('parse-error'); sys.exit(0)
if isinstance(d, dict):
    data = d.get('data') if isinstance(d.get('data'), dict) else {}
    if data.get('status') == 'success':
        print('created')
    else:
        msg = (d.get('message') or '') + ' ' + str(d.get('context') or '')
        print('exists' if 'already exists' in msg.lower() else 'fail:' + msg[:120])
else:
    print('fail:unexpected-shape')
")
case "$sys_status" in
  created) echo "  System tenant: created and bound to admin" ;;
  exists)  echo "  System tenant: already provisioned" ;;
  *)       echo "  System tenant: provision failed ($sys_status)" >&2 ;;
esac

# 3c. Verify admin@auraboot.com holds platform_admin in the System tenant.
#
# BootstrapStartupRunner now owns the platform_admin bootstrap invariant through
# BootstrapRepairService.repairAll(): role creation, System membership, and admin
# grant happen during backend startup. Keep this script as an API-only verifier.
# Do not call /api/roles/* here: those endpoints require org.role.* permissions,
# while the System tenant platform_admin role is intentionally a path-scope role
# for /api/admin/infrastructure/** and /api/admin/cloud-config/**, not a general
# role-management role.
echo "[ga-e2e-bootstrap] verifying admin holds platform_admin in System tenant..."
SYS_TID=$(NO_PROXY=localhost curl -fsS -H "Authorization: Bearer $JWT" \
  "$API_BASE/api/tenant-selection/my-spaces" \
  | python3 -c "
import sys, json
try:
    spaces = json.load(sys.stdin).get('data') or []
except Exception:
    spaces = []
plat = [s for s in spaces if s.get('spaceType') == 'platform']
print(plat[0]['tenantId'] if plat else '')
")
if [ -z "$SYS_TID" ]; then
  echo "  ERROR: could not locate System tenant — bootstrap invariant failed" >&2
  exit 1
else
  JWT_SYS=$(NO_PROXY=localhost curl -fsS -X POST "$API_BASE/api/tenant-selection/process" \
    -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
    -d "{\"action\":\"select\",\"tenantId\":\"$SYS_TID\"}" \
    | python3 -c "import sys,json; print((json.load(sys.stdin).get('data') or {}).get('jwt',''))")
  if [ -z "$JWT_SYS" ]; then
    echo "  ERROR: could not select System tenant — bootstrap invariant failed" >&2
    exit 1
  else
    PLATFORM_ADMIN_OK=$(NO_PROXY=localhost curl -fsS -H "Authorization: Bearer $JWT_SYS" \
      "$API_BASE/api/auth/me" \
      | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('no'); sys.exit(0)
data = d.get('data') if isinstance(d, dict) else {}
permissions = data.get('permissions') if isinstance(data, dict) else {}
roles = permissions.get('roles') if isinstance(permissions, dict) else []
print('yes' if any(r.get('code') == 'platform_admin' for r in roles if isinstance(r, dict)) else 'no')
")
    if [ "$PLATFORM_ADMIN_OK" = "yes" ]; then
      echo "  platform_admin: verified for admin@auraboot.com in System tenant (tenantId=$SYS_TID)"
    else
      echo "  ERROR: admin@auraboot.com lacks platform_admin in System tenant — bootstrap invariant failed" >&2
      exit 1
    fi
  fi
fi

# 4. Seed showcase records via the OSS playwright seed config.
# Skip with SKIP_SEED=1 if the operator has already seeded (e.g. during a
# tight rerun loop). The seed run uses tests/storage/admin.json from a
# prior auth.setup; if missing, generate it via a quick auth-only run.
if [ "${SKIP_SEED:-0}" = "1" ]; then
  echo "[ga-e2e-bootstrap] SKIP_SEED=1 — skipping showcase data seed"
else
  echo "[ga-e2e-bootstrap] seeding showcase records..."
  cd web-admin

  echo "  Refreshing Playwright storage for the current GA stack..."
  rm -f tests/storage/admin.json tests/storage/operator.json tests/storage/viewer.json
  PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 PW_SKIP_WEBSERVER=1 NO_PROXY=localhost,127.0.0.1 \
    npx playwright test tests/auth.setup.ts \
    --reporter=line >/dev/null 2>&1 || true

  if [ ! -f tests/storage/admin.json ]; then
    echo "  ERROR: admin.json still missing — cannot run showcase seed (auth.setup failed)" >&2
    exit 1
  else
    seed_names=(data extended workflow ai arsenal supplement)
    case "${SHOWCASE_COMMERCIAL_SEED:-auto}" in
      skip)
        echo "  seed-showcase-commercial ... SKIP (SHOWCASE_COMMERCIAL_SEED=skip)"
        ;;
      required)
        if [ "$(command_exists 'crm:create_quote')" != "yes" ] || [ "$(command_exists 'crm:create_complaint')" != "yes" ]; then
          echo "  seed-showcase-commercial ... FAIL (full CRM quote/complaint commands are not imported)" >&2
          exit 1
        else
          seed_names+=(commercial)
        fi
        ;;
      auto|"")
        if [ "$(command_exists 'crm:create_quote')" = "yes" ] && [ "$(command_exists 'crm:create_complaint')" = "yes" ]; then
          seed_names+=(commercial)
        else
          echo "  seed-showcase-commercial ... SKIP (OSS crm-starter lacks full CRM quote/complaint commands)"
        fi
        ;;
      *)
        echo "  seed-showcase-commercial ... FAIL (SHOWCASE_COMMERCIAL_SEED must be auto|required|skip)" >&2
        exit 1
        ;;
    esac
    seed_names+=(dashboard-default invariants)

    seed_log="/tmp/ga-e2e-seed-sequence.log"
    if SHOWCASE_DEFAULT_DASHBOARD_CODE="${SHOWCASE_DEFAULT_DASHBOARD_CODE:-crm_overview}" \
         PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 NO_PROXY=localhost,127.0.0.1 \
         node scripts/run-showcase-seed-sequence.mjs \
           --output-prefix=test-results/ga-e2e-seed "${seed_names[@]}" \
           > "$seed_log" 2>&1; then
      passed=$(grep -oE "[0-9]+ passed" "$seed_log" | tail -1)
      echo "  showcase seed sequence ... OK (${passed:-completed})"
    else
      echo "  showcase seed sequence ... FAIL (see $seed_log)" >&2
      tail -80 "$seed_log" >&2 || true
      exit 1
    fi
  fi
  cd ..
fi

echo
echo "[ga-e2e-bootstrap] done. Run Playwright with:"
echo "  cd web-admin"
echo "  PLAYWRIGHT_BASE_URL=http://localhost:5174 PW_SKIP_WEBSERVER=1 NO_PROXY=localhost \\"
echo "    npx playwright test ..."
