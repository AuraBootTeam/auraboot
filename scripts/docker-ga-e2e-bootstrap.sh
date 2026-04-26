#!/usr/bin/env bash
# Bootstrap the GA-E2E docker stack for OSS Playwright runs.
#
# What auto-bootstrap (AURABOOT_BOOTSTRAP_ENABLED=true on the backend) gives us:
#   - admin@example.com user with TENANT_ADMIN role on a default tenant
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
  acp-showcase
)
PLUGINS=( ${PLUGINS:-${DEFAULT_PLUGINS[@]}} )

API_BASE="http://localhost:6444"
ADMIN_EMAIL="admin@example.com"
ADMIN_PASSWORD="Test2026x"

echo "[ga-e2e-bootstrap] target stack: $API_BASE"
echo "[ga-e2e-bootstrap] plugins (${#PLUGINS[@]}): ${PLUGINS[*]}"

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
    echo "  WARNING: admin.json still missing — skipping seed (auth.setup failed)" >&2
  else
    seed_failures=()
    for seed in data extended workflow ai arsenal supplement commercial; do
      printf '  seed-showcase-%s ... ' "$seed"
      if PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 NO_PROXY=localhost,127.0.0.1 \
           npx playwright test --config=playwright.seed.config.ts \
             -g "seed-showcase-$seed" --reporter=line \
             > "/tmp/ga-e2e-seed-$seed.log" 2>&1; then
        passed=$(grep -oE "[0-9]+ passed" "/tmp/ga-e2e-seed-$seed.log" | head -1)
        echo "OK ($passed)"
      else
        # commercial currently has a pre-existing Quote model gap that fails
        # one phase even on enterprise; do not block the bootstrap on it.
        if [ "$seed" = "commercial" ]; then
          echo "PARTIAL (Quote gap, see /tmp/ga-e2e-seed-$seed.log)"
          # Surface the failing seed step + last error line so operators don't
          # have to open the log file manually to know which phase failed.
          failed_phase=$(grep -oE "seed-showcase-commercial[^[:space:]]*" \
                          "/tmp/ga-e2e-seed-$seed.log" | head -1)
          last_error=$(grep -E "Error:|FAIL|✘|×|expect\(" \
                          "/tmp/ga-e2e-seed-$seed.log" | tail -1)
          echo "    [ga-e2e-bootstrap] commercial PARTIAL detail:" >&2
          echo "      phase: ${failed_phase:-unknown}" >&2
          echo "      cause: ${last_error:-Quote model not seeded — see full log}" >&2
          echo "      log:   /tmp/ga-e2e-seed-$seed.log" >&2
        else
          echo "FAIL (see /tmp/ga-e2e-seed-$seed.log)"
          seed_failures+=("$seed")
        fi
      fi
    done

    if [ "${#seed_failures[@]}" -gt 0 ]; then
      echo "[ga-e2e-bootstrap] WARNING: ${#seed_failures[@]} seed run(s) failed:" >&2
      printf '  - %s\n' "${seed_failures[@]}" >&2
    fi
  fi
  cd ..
fi

echo
echo "[ga-e2e-bootstrap] done. Run Playwright with:"
echo "  cd web-admin"
echo "  PLAYWRIGHT_BASE_URL=http://localhost:5174 PW_SKIP_WEBSERVER=1 NO_PROXY=localhost \\"
echo "    npx playwright test ..."
