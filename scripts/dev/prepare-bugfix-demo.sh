#!/usr/bin/env bash
#
# Prepare a running daily bugfix environment for OSS demo debugging.
#
# Scenario contract: bugfix-oss-demo
#   - OSS product runtime
#   - e2e plugin profile (all OSS plugin packages)
#   - private per-env Playwright auth storage
#   - showcase seed data
#   - workflow-demo business data and task instances
#   - BPM process-management mirror
#   - scenario invariants

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../lib/reset-init-common.sh
source "$PROJECT_ROOT/scripts/lib/reset-init-common.sh"

SLUG="bugfix-daily"
PRODUCT="oss"
DRY_RUN=0
SKIP_IMPORT=0
SKIP_SEED=0
SKIP_AUTH=0

usage() {
  cat <<USAGE
Usage: scripts/dev/prepare-bugfix-demo.sh [options]

Options:
  --slug=<name>       Env registry slug (default: bugfix-daily)
  --product=oss       Product scenario. Only oss is supported for this demo contract.
  --skip-import       Do not import plugins
  --skip-seed         Do not run showcase/workflow seed
  --skip-auth         Do not refresh Playwright storageState
  --dry-run           Print the plan without mutating runtime state
  -h, --help          Show this help

Scenario: bugfix-oss-demo
  plugins: scripts/dev/plugin-import-profiles.json profile e2e
  seeds:   showcase default phases + workflow-demo business seed + BPM management mirror
  verify:  health + OSS demo invariants
USAGE
}

for arg in "$@"; do
  case "$arg" in
    --slug=*) SLUG="${arg#--slug=}" ;;
    --product=*) PRODUCT="${arg#--product=}" ;;
    --skip-import) SKIP_IMPORT=1 ;;
    --skip-seed) SKIP_SEED=1 ;;
    --skip-auth) SKIP_AUTH=1 ;;
    --dry-run) DRY_RUN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
  esac
done

if [ "$PRODUCT" != "oss" ]; then
  echo "ERROR: prepare-bugfix-demo currently supports --product=oss only" >&2
  exit 2
fi

scenario_summary() {
  cat <<PLAN
Bugfix demo scenario plan
  scenario:        bugfix-oss-demo
  product:         $PRODUCT
  slug:            $SLUG
  plugin profile:  e2e (all OSS plugins)
  auth storage:    per-env private storage from r2-env-export
  seeds:           showcase data/extended/workflow/ai/arsenal/supplement/dashboard-default/invariants
  workflow data:   wd_leave_balance + wd_leave_request + se_process_instance + se_task_instance
  bpm mirror:      ab_bpm_process_definition -> mt_bpm_process_management
  invariants:      OSS plugins, CRM data, BPM process management, workflow-demo tasks
PLAN
}

scenario_summary

if [ "$DRY_RUN" = "1" ]; then
  cat <<DRY
(dry-run mode: not preparing demo data)
Would run:
  source scripts/dev/r2-env-export.sh $SLUG
  scripts/import-plugins.sh --profile=e2e --edition=oss
  pnpm exec playwright test --project=setup --workers=1
  pnpm exec playwright test tests/auth.setup.ts --project=auth --no-deps
  node scripts/run-showcase-seed-sequence.mjs data extended workflow ai arsenal supplement dashboard-default invariants
  node scripts/seed-workflow-demo.mjs
  psql mirror sync for bpm_process_management
  node scripts/oss-demo-invariants.mjs
DRY
  exit 0
fi

cd "$PROJECT_ROOT"
# shellcheck disable=SC1090
source "$PROJECT_ROOT/scripts/dev/r2-env-export.sh" "$SLUG"

echo "[bugfix-oss-demo] verifying running services"
"$PROJECT_ROOT/scripts/dev/env.sh" verify --level=health --slug="$SLUG"

echo "[bugfix-oss-demo] bootstrap setup"
aura_bootstrap_setup_if_needed \
  "$BACKEND_URL" \
  "AuraBoot Dev" \
  "admin@auraboot.com" \
  "Test2026x" \
  "Admin User" \
  "single" \
  "[bugfix-oss-demo]"

if [ "$SKIP_IMPORT" != "1" ]; then
  echo "[bugfix-oss-demo] importing all OSS plugins via profile=e2e"
  PG_HOST="$PG_HOST" \
  PG_PORT="$PG_PORT" \
  PG_USER="$PG_USER" \
  PG_DB="$PG_DB" \
  PGPASSWORD="$PGPASSWORD" \
  BACKEND_URL="$BACKEND_URL" \
  "$PROJECT_ROOT/scripts/import-plugins.sh" \
    --profile=e2e \
    --edition=oss \
    --backend-url="$BACKEND_URL" \
    --plugin-root="$(cd "$PROJECT_ROOT/plugins" && pwd)"
else
  echo "[bugfix-oss-demo] SKIP_IMPORT=1"
fi

cd "$PROJECT_ROOT/web-admin"

if [ "$SKIP_AUTH" != "1" ]; then
  echo "[bugfix-oss-demo] refreshing private Playwright auth storage"
  pnpm exec playwright test --project=setup --workers=1
  pnpm exec playwright test tests/auth.setup.ts --project=auth --no-deps
else
  echo "[bugfix-oss-demo] SKIP_AUTH=1"
fi

if [ "$SKIP_SEED" != "1" ]; then
  echo "[bugfix-oss-demo] running showcase seed sequence"
  SHOWCASE_COMMERCIAL_SEED=auto \
  SHOWCASE_DEFAULT_DASHBOARD_CODE=crm_overview \
    node scripts/run-showcase-seed-sequence.mjs \
      --config=playwright.seed.config.ts \
      --reporter=line \
      --output-prefix="test-results/seed/$SLUG-showcase" \
      data extended workflow ai arsenal supplement dashboard-default invariants

  echo "[bugfix-oss-demo] seeding workflow-demo business data"
  node scripts/seed-workflow-demo.mjs \
    --base-url="$PLAYWRIGHT_BASE_URL" \
    --storage-state="$PW_ADMIN_STORAGE_STATE" \
    --min-requests=12

  echo "[bugfix-oss-demo] syncing BPM process-management mirror"
  PGPASSWORD="$PGPASSWORD" psql \
    -h "$PG_HOST" \
    -p "$PG_PORT" \
    -U "$PG_USER" \
    -d "$PG_DB" \
    -v ON_ERROR_STOP=1 \
    -c "insert into mt_bpm_process_management (pid, created_at, updated_at, tenant_id, process_key, process_name, category, version, status, deployed_at) select d.pid, coalesce(d.created_at, now()), coalesce(d.updated_at, now()), d.tenant_id, d.process_key, d.process_name, d.category, d.version, d.status, d.deployed_at from ab_bpm_process_definition d where d.deleted_flag=false on conflict (pid) do update set updated_at=excluded.updated_at, process_key=excluded.process_key, process_name=excluded.process_name, category=excluded.category, version=excluded.version, status=excluded.status, deployed_at=excluded.deployed_at;"
else
  echo "[bugfix-oss-demo] SKIP_SEED=1"
fi

echo "[bugfix-oss-demo] running scenario invariants"
node scripts/oss-demo-invariants.mjs \
  --base-url="$PLAYWRIGHT_BASE_URL" \
  --backend-url="$BACKEND_URL" \
  --storage-state="$PW_ADMIN_STORAGE_STATE"

echo "[bugfix-oss-demo] complete"
