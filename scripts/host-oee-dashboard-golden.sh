#!/usr/bin/env bash
#
# Host-first OEE dashboard golden.
#
# This validates the PCBA manufacturing OEE dashboard against a running host
# stack. It intentionally does not start or reset services. Use reset-db /
# bootRun / pnpm dev:full outside this script, then run this script to
# bootstrap, import the minimal plugin set, seed deterministic OEE data, assert
# the backend API, and run the browser golden.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB_ADMIN="$PROJECT_ROOT/web-admin"

MODE="${1:-all}"
case "$MODE" in
  all|prepare|api|ui|help|--help|-h) ;;
  *)
    echo "Usage: $0 [all|prepare|api|ui]" >&2
    exit 2
    ;;
esac

if [[ "$MODE" == "help" || "$MODE" == "--help" || "$MODE" == "-h" ]]; then
  cat <<'USAGE'
Usage: scripts/host-oee-dashboard-golden.sh [all|prepare|api|ui]

Modes:
  all      preflight + bootstrap + import + seed + API assertion + UI golden
  prepare  preflight + bootstrap + import + seed
  api      preflight + API assertion only
  ui       preflight + UI golden only

Environment:
  BE_PORT / BACKEND_URL             default: 6443 / http://localhost:${BE_PORT}
  VITE_PORT / PLAYWRIGHT_BASE_URL   default: 5173 / http://127.0.0.1:${VITE_PORT}
  BFF_PORT / BFF_URL                default: 3500 / http://127.0.0.1:${BFF_PORT}
  PG_HOST / PG_PORT / PG_USER / PG_DB / PGPASSWORD
                                    default: localhost / 5432 / $USER / aura_oee_host_verify / empty
  GREPTIME_URL or IOT_OEE_GREPTIME_URL
                                    default: http://127.0.0.1:4000
  ENTERPRISE_PLUGIN_ROOT            default: /Users/ghj/work/auraboot/plugins
  SKIP_IMPORT=1                     skip plugin import in all/prepare
  SKIP_SEED=1                       skip Postgres + Greptime seed in all/prepare
USAGE
  exit 0
fi

export NO_PROXY="${NO_PROXY:-127.0.0.1,localhost}"
export no_proxy="$NO_PROXY"

BE_PORT="${BE_PORT:-6443}"
VITE_PORT="${VITE_PORT:-5173}"
BFF_PORT="${BFF_PORT:-3500}"
BACKEND_URL="${BACKEND_URL:-http://localhost:${BE_PORT}}"
PLAYWRIGHT_BASE_URL="${PLAYWRIGHT_BASE_URL:-http://127.0.0.1:${VITE_PORT}}"
BFF_URL="${BFF_URL:-http://127.0.0.1:${BFF_PORT}}"
GREPTIME_URL="${GREPTIME_URL:-${IOT_OEE_GREPTIME_URL:-http://127.0.0.1:4000}}"

PG_HOST="${PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${PG_PORT:-${PGPORT:-5432}}"
PG_USER="${PG_USER:-${PGUSER:-${USER:-ghj}}}"
PG_DB="${PG_DB:-${PGDATABASE:-aura_oee_host_verify}}"
PGPASSWORD="${PGPASSWORD:-${PG_PASSWORD:-}}"

ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
ENTERPRISE_PLUGIN_ROOT="${ENTERPRISE_PLUGIN_ROOT:-${AURA_ENTERPRISE_ROOT:+$AURA_ENTERPRISE_ROOT/plugins}}"
ENTERPRISE_PLUGIN_ROOT="${ENTERPRISE_PLUGIN_ROOT:-/Users/ghj/work/auraboot/plugins}"
OSS_PLUGIN_ROOT="${OSS_PLUGIN_ROOT:-$PROJECT_ROOT/plugins}"

log() { printf '\033[36m[oee-host-golden]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[oee-host-golden] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

curl_json() {
  curl --noproxy '*' -sS "$@"
}

json_get() {
  local expr="$1"
  python3 -c "import json,sys; data=json.load(sys.stdin); print($expr)"
}

psql_run() {
  if [ -n "$PGPASSWORD" ]; then
    PGPASSWORD="$PGPASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" "$@"
  else
    psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" "$@"
  fi
}

greptime_sql() {
  local sql="$1"
  curl --noproxy '*' -sS -X POST "$GREPTIME_URL/v1/sql" --data-urlencode "sql=$sql"
}

preflight() {
  need curl
  need python3
  need psql

  log "preflight backend=$BACKEND_URL web=$PLAYWRIGHT_BASE_URL bff=$BFF_URL db=$PG_DB greptime=$GREPTIME_URL"

  local health status
  health="$(curl_json "$BACKEND_URL/actuator/health" 2>/dev/null || echo '{}')"
  status="$(printf '%s' "$health" | json_get "data.get('status','')" 2>/dev/null || true)"
  [ "$status" = "UP" ] || die "backend is not healthy: $health"

  if [[ "$MODE" == "all" || "$MODE" == "ui" ]]; then
    local bff_health bff_status web_code
    bff_health="$(curl_json "$BFF_URL/health" 2>/dev/null || echo '{}')"
    bff_status="$(printf '%s' "$bff_health" | json_get "data.get('status','')" 2>/dev/null || true)"
    [ "$bff_status" = "ok" ] || die "BFF is not healthy: $bff_health"
    web_code="$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' "$PLAYWRIGHT_BASE_URL/" 2>/dev/null || echo 000)"
    [ "$web_code" != "000" ] || die "Vite is not reachable: $PLAYWRIGHT_BASE_URL"
  fi

  if [[ "$MODE" == "all" || "$MODE" == "prepare" ]]; then
    curl_json "$GREPTIME_URL/health" >/dev/null || die "Greptime is not reachable: $GREPTIME_URL"
  fi
}

login() {
  local login_resp jwt tenant_id spaces biz_tenant select_resp selected_jwt
  login_resp="$(curl_json -X POST "$BACKEND_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")"
  jwt="$(printf '%s' "$login_resp" | json_get "data.get('data',{}).get('jwt','')" 2>/dev/null || true)"
  tenant_id="$(printf '%s' "$login_resp" | json_get "data.get('data',{}).get('tenantId','')" 2>/dev/null || true)"
  [ -n "$jwt" ] && [ "$jwt" != "None" ] || die "admin login failed: $login_resp"

  if [ -z "$tenant_id" ] || [ "$tenant_id" = "None" ]; then
    spaces="$(curl_json "$BACKEND_URL/api/tenant-selection/my-spaces" -H "Authorization: Bearer $jwt")"
    biz_tenant="$(printf '%s' "$spaces" | python3 -c "import json,sys; data=json.load(sys.stdin).get('data',[]); print(next((str(x.get('tenantId')) for x in data if x.get('spaceType') == 'business' and x.get('tenantId')), ''))")"
    [ -n "$biz_tenant" ] || die "no business tenant found: $spaces"
    select_resp="$(curl_json -X POST "$BACKEND_URL/api/tenant-selection/process" \
      -H "Authorization: Bearer $jwt" \
      -H 'Content-Type: application/json' \
      -d "{\"action\":\"select\",\"tenantId\":$biz_tenant}")"
    selected_jwt="$(printf '%s' "$select_resp" | json_get "data.get('data',{}).get('jwt','')" 2>/dev/null || true)"
    [ -n "$selected_jwt" ] && [ "$selected_jwt" != "None" ] || die "tenant selection failed: $select_resp"
    jwt="$selected_jwt"
    tenant_id="$biz_tenant"
  fi

  JWT="$jwt"
  TENANT_ID="$tenant_id"
}

bootstrap_if_needed() {
  local status initialized setup_resp
  status="$(curl_json "$BACKEND_URL/api/bootstrap/status")"
  initialized="$(printf '%s' "$status" | json_get "data.get('data',{}).get('initialized', False)" 2>/dev/null || echo False)"
  if [ "$initialized" != "True" ]; then
    log "bootstrap default tenant"
    setup_resp="$(curl_json -X POST "$BACKEND_URL/api/bootstrap/setup" \
      -H 'Content-Type: application/json' \
      -d "{\"companyName\":\"AuraBoot OEE Host Verify\",\"adminEmail\":\"$ADMIN_EMAIL\",\"adminPassword\":\"$ADMIN_PASSWORD\",\"adminDisplayName\":\"Admin\",\"systemMode\":\"single\",\"seedDemoData\":false}")"
    printf '%s' "$setup_resp" | grep -q '"code":"0"\|"code":0\|"success":true' || die "bootstrap failed: $setup_resp"
  fi
  login
  log "authenticated tenant=$TENANT_ID"
}

import_plugins() {
  if [ "${SKIP_IMPORT:-0}" = "1" ]; then
    log "plugin import skipped (SKIP_IMPORT=1)"
    return
  fi
  [ -d "$OSS_PLUGIN_ROOT" ] || die "OSS plugin root not found: $OSS_PLUGIN_ROOT"
  [ -d "$ENTERPRISE_PLUGIN_ROOT" ] || die "enterprise plugin root not found: $ENTERPRISE_PLUGIN_ROOT"

  local common_env=(
    "NO_PROXY=$NO_PROXY"
    "BACKEND_URL=$BACKEND_URL"
    "PG_HOST=$PG_HOST"
    "PG_PORT=$PG_PORT"
    "PG_USER=$PG_USER"
    "PG_DB=$PG_DB"
    "PGPASSWORD=$PGPASSWORD"
    "ADMIN_EMAIL=$ADMIN_EMAIL"
    "ADMIN_PASSWORD=$ADMIN_PASSWORD"
  )
  local import_args=(
    --edition=enterprise
    "--plugin-root=$OSS_PLUGIN_ROOT"
    "--enterprise-plugin-root=$ENTERPRISE_PLUGIN_ROOT"
  )
  local pre_plugins=(
    core-meta core-bpm platform-admin core-decisionops core-announcement core-aurabot
    page-manager org-management product-catalog crm inventory finance sales quality
    procurement pcba-base pcba-industry
  )

  log "import base OEE plugin set"
  env "${common_env[@]}" "$PROJECT_ROOT/scripts/import-plugins.sh" "${import_args[@]}" "${pre_plugins[@]}"
  ensure_parent_menus
  log "import pcba-manufacturing"
  env "${common_env[@]}" "$PROJECT_ROOT/scripts/import-plugins.sh" "${import_args[@]}" pcba-manufacturing
}

ensure_parent_menus() {
  log "ensure pcba-manufacturing parent menu dirs"
  psql_run -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
WITH root AS (
  SELECT id, tenant_id, plugin_pid
  FROM ab_menu
  WHERE code = 'pe_root' AND deleted_flag = false
  ORDER BY id
  LIMIT 1
),
rows(code, pid, name, path, order_no) AS (
  VALUES
    ('pe_planning_dir', 'host_oee_planning_dir', '计划排程', '/pcba-erp/planning', 50),
    ('pe_production_dir', 'host_oee_production_dir', '生产执行', '/pcba-erp/production', 60)
)
INSERT INTO ab_menu (
  id, pid, tenant_id, parent_id, code, name, path, type, visible, order_no,
  status, deleted_flag, plugin_pid, created_at, updated_at
)
SELECT
  (floor(extract(epoch from clock_timestamp()) * 1000)::bigint + row_number() OVER ()),
  rows.pid,
  root.tenant_id,
  root.id,
  rows.code,
  rows.name,
  rows.path,
  0,
  true,
  rows.order_no,
  'active',
  false,
  root.plugin_pid,
  now(),
  now()
FROM root
CROSS JOIN rows
ON CONFLICT (tenant_id, code) WHERE deleted_flag = false AND code IS NOT NULL
DO UPDATE SET
  parent_id = excluded.parent_id,
  name = excluded.name,
  path = excluded.path,
  type = excluded.type,
  visible = excluded.visible,
  order_no = excluded.order_no,
  status = excluded.status,
  plugin_pid = excluded.plugin_pid,
  updated_at = now();

DO $$
DECLARE
  owned_count integer;
BEGIN
  SELECT count(*) INTO owned_count
  FROM ab_menu
  WHERE code IN ('pe_planning_dir', 'pe_production_dir')
    AND deleted_flag = false
    AND plugin_pid IS NOT NULL;
  IF owned_count <> 2 THEN
    RAISE EXCEPTION 'expected two owned PCBA parent menus, found %', owned_count;
  END IF;
END $$;
SQL
}

seed_postgres() {
  log "seed deterministic PCBA OEE Postgres rows"
  psql_run -v ON_ERROR_STOP=1 -v tenant_id="$TENANT_ID" <<'SQL' >/dev/null
DELETE FROM mt_mfg_equipment_downtime_pcba_asset WHERE tenant_id = :'tenant_id'::bigint AND pid LIKE 'oee_%';
DELETE FROM mt_mfg_work_order_operation_pcba_execution WHERE tenant_id = :'tenant_id'::bigint AND pid LIKE 'oee_%';
DELETE FROM mt_mfg_resource_calendar_pcba_capacity WHERE tenant_id = :'tenant_id'::bigint AND pid LIKE 'oee_%';
DELETE FROM mt_mfg_equipment_pcba_asset WHERE tenant_id = :'tenant_id'::bigint AND pid LIKE 'oee_%';
DELETE FROM mt_mfg_resource_pcba_capacity WHERE tenant_id = :'tenant_id'::bigint AND pid LIKE 'oee_%';

INSERT INTO mt_mfg_resource_pcba_capacity (
  pid, tenant_id, created_at, updated_at, mfg_res_code, mfg_res_name, mfg_res_type,
  mfg_res_capacity_per_hour, mfg_res_status
) VALUES
  ('oee_res_smt_01', :'tenant_id'::bigint, now(), now(), 'SMT-L1', 'SMT Line 1', 'line', 100.00, 'active'),
  ('oee_res_test_01', :'tenant_id'::bigint, now(), now(), 'TEST-L1', 'Test Line 1', 'line', 80.00, 'active');

INSERT INTO mt_mfg_equipment_pcba_asset (
  pid, tenant_id, created_at, updated_at, mfg_eq_code, mfg_eq_name, mfg_eq_type,
  mfg_eq_resource_id, mfg_eq_status
) VALUES
  ('oee_eq_smt_01', :'tenant_id'::bigint, now(), now(), 'SMT-01', 'SMT Printer 01', 'smt', 'oee_res_smt_01', 'running'),
  ('oee_eq_test_01', :'tenant_id'::bigint, now(), now(), 'TEST-01', 'AOI Test 01', 'test', 'oee_res_test_01', 'running');

INSERT INTO mt_mfg_resource_calendar_pcba_capacity (
  pid, tenant_id, created_at, updated_at, mfg_rc_resource_id, mfg_rc_date,
  mfg_rc_shift, mfg_rc_start_time, mfg_rc_end_time, mfg_rc_available_hours,
  mfg_rc_is_holiday
)
SELECT
  'oee_cal_smt_' || to_char(day, 'YYYYMMDD'),
  :'tenant_id'::bigint, now(), now(), 'oee_res_smt_01', day::date,
  'day', '08:00', '16:00', 8.00, false
FROM generate_series(date '2026-06-01', date '2026-06-04', interval '1 day') AS day
UNION ALL
SELECT
  'oee_cal_test_' || to_char(day, 'YYYYMMDD'),
  :'tenant_id'::bigint, now(), now(), 'oee_res_test_01', day::date,
  'day', '08:00', '16:00', 8.00, false
FROM generate_series(date '2026-06-01', date '2026-06-04', interval '1 day') AS day;

INSERT INTO mt_mfg_work_order_operation_pcba_execution (
  pid, tenant_id, created_at, updated_at, mfg_wop_work_order_id, mfg_wop_seq,
  mfg_wop_name, mfg_wop_status, mfg_wop_resource_id, mfg_wop_actual_start,
  mfg_wop_actual_end, mfg_wop_planned_qty, mfg_wop_actual_qty, mfg_wop_defect_qty
) VALUES
  ('oee_op_smt_01', :'tenant_id'::bigint, now(), now(), 'oee_wo_smt_01', 10,
   'SMT print', 'completed', 'oee_res_smt_01', '2026-06-04T08:00:00Z',
   '2026-06-04T16:00:00Z', 2200.00, 1600.00, 90.00),
  ('oee_op_test_01', :'tenant_id'::bigint, now(), now(), 'oee_wo_test_01', 20,
   'AOI test', 'completed', 'oee_res_test_01', '2026-06-04T08:00:00Z',
   '2026-06-04T16:00:00Z', 1500.00, 1100.00, 120.00);

INSERT INTO mt_mfg_equipment_downtime_pcba_asset (
  pid, tenant_id, created_at, updated_at, mfg_dt_equipment_id, mfg_dt_start_time,
  mfg_dt_end_time, mfg_dt_duration_hours, mfg_dt_type, mfg_dt_reason
) VALUES
  ('oee_dt_smt_breakdown', :'tenant_id'::bigint, now(), now(), 'oee_eq_smt_01',
   '2026-06-04T10:00:00Z', '2026-06-04T11:00:00Z', 1.00, 'breakdown', 'feeder fault'),
  ('oee_dt_smt_planned', :'tenant_id'::bigint, now(), now(), 'oee_eq_smt_01',
   '2026-06-04T12:00:00Z', '2026-06-04T14:00:00Z', 2.00, 'planned', 'planned changeover'),
  ('oee_dt_test_breakdown', :'tenant_id'::bigint, now(), now(), 'oee_eq_test_01',
   '2026-06-04T09:00:00Z', '2026-06-04T13:00:00Z', 4.00, 'breakdown', 'camera calibration');
SQL
}

seed_greptime() {
  log "seed deterministic Greptime OEE rows"
  greptime_sql "delete from oee where tenant_id = '$TENANT_ID' and kind = 'host-golden' and asset_code in ('SMT-01', 'TEST-01')" >/dev/null
  greptime_sql "insert into oee (ts, tenant_id, asset_code, kind, operating_hours, output_qty, good_qty) values ('2026-06-04T23:59:00Z', '$TENANT_ID', 'SMT-01', 'host-golden', 24, 1800, 1710)" >/dev/null
  greptime_sql "insert into oee (ts, tenant_id, asset_code, kind, operating_hours, output_qty, good_qty) values ('2026-06-04T23:59:00Z', '$TENANT_ID', 'TEST-01', 'host-golden', 20, 1200, 1080)" >/dev/null
}

seed_data() {
  if [ "${SKIP_SEED:-0}" = "1" ]; then
    log "seed skipped (SKIP_SEED=1)"
    return
  fi
  seed_postgres
  seed_greptime
}

assert_api() {
  login
  log "assert OEE backend API"
  local tmpdir fleet_file summary_file
  tmpdir="$(mktemp -d)"
  fleet_file="$tmpdir/fleet.json"
  summary_file="$tmpdir/summary.json"
  curl_json "$BACKEND_URL/api/manufacturing/oee/fleet?start=2026-06-01T00:00:00&end=2026-06-05T00:00:00" \
    -H "Authorization: Bearer $JWT" >"$fleet_file"
  curl_json "$BACKEND_URL/api/manufacturing/oee/fleet/summary?start=2026-06-01T00:00:00&end=2026-06-05T00:00:00" \
    -H "Authorization: Bearer $JWT" >"$summary_file"
  python3 - "$fleet_file" "$summary_file" <<'PY'
import json
import sys

fleet = json.load(open(sys.argv[1]))
summary = json.load(open(sys.argv[2]))
records = fleet.get("data", {}).get("records", [])
summary_row = (summary.get("data", {}).get("records") or [{}])[0]
by_code = {row.get("code"): row for row in records}

expected = {
    "SMT-01": {"oeePct": 57, "availabilityPct": 80, "performancePct": 75, "qualityPct": 95},
    "TEST-01": {"oeePct": 42.2, "availabilityPct": 62.5, "performancePct": 75, "qualityPct": 90},
}
failures = []
if len(records) != 2:
    failures.append(f"fleet records={len(records)}")
for code, fields in expected.items():
    row = by_code.get(code)
    if not row:
        failures.append(f"missing {code}")
        continue
    for key, value in fields.items():
        if row.get(key) != value:
            failures.append(f"{code}.{key}={row.get(key)} expected {value}")
for key, value in {"oeePct": 49.6, "teepPct": 47.8, "equipmentWithDataCount": 2}.items():
    if summary_row.get(key) != value:
        failures.append(f"summary.{key}={summary_row.get(key)} expected {value}")
if failures:
    raise SystemExit("OEE API assertion failed: " + "; ".join(failures))
print(f"API_ASSERTIONS_OK rows={len(records)} summary_oeePct={summary_row.get('oeePct')}")
PY
}

run_ui() {
  log "run Playwright OEE dashboard golden"
  (
    cd "$WEB_ADMIN"
    PLAYWRIGHT_BASE_URL="$PLAYWRIGHT_BASE_URL" \
      BACKEND_URL="$BACKEND_URL" \
      BFF_URL="$BFF_URL" \
      BE_PORT="$BE_PORT" \
      BFF_PORT="$BFF_PORT" \
      PW_SKIP_WEBSERVER=1 \
      PW_PROFILE=fast \
      PW_WORKERS=1 \
      NO_PROXY="$NO_PROXY" \
      npx playwright test -c playwright.config.ts \
        tests/e2e/pcba/oee-dashboard-host-golden.spec.ts \
        --project=chromium \
        --no-deps \
        --reporter=line
  )
}

preflight

case "$MODE" in
  all)
    bootstrap_if_needed
    import_plugins
    seed_data
    assert_api
    run_ui
    ;;
  prepare)
    bootstrap_if_needed
    import_plugins
    seed_data
    ;;
  api)
    assert_api
    ;;
  ui)
    run_ui
    ;;
esac

log "$MODE complete"
