#!/usr/bin/env bash
#
# mes-wms-golden-run.sh — self-contained golden gate runner for the 8 delivered MES/WMS FRs.
#
# Owner has no CI budget → this is a one-command runner (build → stage → start → import →
# seed → backend command-pipeline golden (real-stack IT) → [UI golden] → report → teardown),
# exit code = result. Host-first, zero docker, isolated dev.sh runtime (safe alongside other
# sessions). trap guarantees teardown even on failure.
#
# Usage:
#   scripts/mes-wms-golden-run.sh [--slot N] [--keep] [--no-ui]
#     --slot N : dev.sh runtime slot (default 63)
#     --keep   : do not tear down the stack on exit (for debugging / UI golden authoring)
#     --no-ui  : backend command-pipeline golden only (skip Playwright UI golden)
#
# Covers: FR-04 HandlingUnit · FR-05 Interlock · FR-09 Tooling · FR-10 FEFO · FR-13 Kitting ·
#         FR-16 Hold · FR-20 Downtime · FR-22 Shift Handover.
set -euo pipefail

SLOT=63; KEEP=0; RUN_UI=1
while [ $# -gt 0 ]; do case "$1" in
  --slot) SLOT="$2"; shift 2;;
  --keep) KEEP=1; shift;;
  --no-ui) RUN_UI=0; shift;;
  *) echo "unknown arg: $1" >&2; exit 2;;
esac; done

REPO=/Users/ghj/work/auraboot/auraboot
PLUGINS=/Users/ghj/work/auraboot/plugins
NAME=mes-wms-golden
SD="$(mktemp -d)"
STAGE="$SD/plugins"; mkdir -p "$STAGE"
BE_PORT=$((6400 + SLOT)); BACKEND_URL="http://127.0.0.1:${BE_PORT}"
PG_DB="auraboot_${SLOT}"; REDIS_DB=$((SLOT % 16))
export PGPASSWORD=auraboot BACKEND_URL PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=auraboot PG_DB
export ADMIN_EMAIL=admin@auraboot.com ADMIN_PASSWORD=Test2026x
log() { printf '\033[36m[mes-wms-golden]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[mes-wms-golden] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

BACKEND_PID=""
cleanup() {
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  if [ "$KEEP" != "1" ]; then
    log "teardown runtime '$NAME'"
    ( cd "$REPO" && ./dev.sh infra cleanup "$NAME" --yes >/dev/null 2>&1 || true; ./dev.sh runtime destroy "$NAME" --yes >/dev/null 2>&1 || true )
  else
    log "--keep: leaving stack up (backend pid $BACKEND_PID, db $PG_DB)"
  fi
}
trap cleanup EXIT

# The 8 FRs live in inventory + pcba-manufacturing, but pcba-manufacturing's transitive
# dependency web pulls in the FULL pcba-agent plugin set (pcba-industry→pcba-sales→
# pcba-solution/pcba-crm/quote-core/bom-standardization→…). Every hybrid plugin in that set
# needs a FRESH jar staged into AURA_PLUGINS_DIR — stale platform/plugins jars miss handlers
# → S-EXT-HANDLER. Config-only plugins (req, sales, pcba-base, pcba-crm, quote-core,
# pcba-industry, pcba-quote) and OSS built-ins (core-*, page-manager, org-management,
# agent-control-plane) need no jar. quote-engine lives in the aura-quote repo.
HYBRID_JARS=(product-catalog crm inventory finance quality procurement pcba-solution
  pcba-procurement jiejia-integration bom-standardization pcba-sales pcba-manufacturing
  pcba-warehouse pcba-finance pcba-compliance)
QUOTE_HYBRID=(quote-engine)  # built from aura-quote/plugin-aura/<p>/backend
IMPORT_PROFILE=pcba-agent    # import-plugins.sh resolves the full set + two-phase defer order

log "1/7 build fresh hybrid plugin jars (full pcba-agent set)"
build_jar() {  # <plugin> <backend-dir>
  [ -d "$2" ] || { echo "  WARN no backend: $1" >&2; return; }
  ( cd "$2" && gradle jar --console=plain -q >/dev/null 2>&1 ) || echo "  WARN build failed: $1" >&2
  local j; j="$(ls "$2/build/libs/"*.jar 2>/dev/null | head -1)"
  [ -n "$j" ] && cp "$j" "$STAGE/"
}
( unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL
  for p in "${HYBRID_JARS[@]}"; do build_jar "$p" "$PLUGINS/$p/backend"; done
  for p in "${QUOTE_HYBRID[@]}"; do build_jar "$p" "/Users/ghj/work/auraboot/aura-quote/plugin-aura/$p/backend"; done )
log "    staged $(ls "$STAGE"/*.jar | wc -l | tr -d ' ') hybrid jars (expect 16)"

log "2/7 allocate runtime (slot $SLOT) + infra + schema"
( cd "$REPO" && ./dev.sh runtime allocate auraboot "$NAME" --slot "$SLOT" --purpose "MES/WMS 8FR golden" --ttl 4h >/dev/null 2>&1 || true )
( cd "$REPO" && ./dev.sh infra ensure "$NAME" --yes >/dev/null 2>&1 ) || die "infra ensure failed"
psql -h 127.0.0.1 -p 5432 -U auraboot -d "$PG_DB" -c 'select 1 from ab_meta_model limit 1' >/dev/null 2>&1 \
  || psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5432 -U auraboot -d "$PG_DB" -q \
       -f "$REPO/platform/src/main/resources/db/snapshots/schema-current.sql" >/dev/null 2>&1 \
  || die "schema apply failed"

log "3/7 start backend (java -jar, AURA_PLUGINS_DIR=fresh hybrid jars) on $BE_PORT"
JAR="$(ls "$REPO"/platform/build/libs/*-boot.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || ( cd "$REPO/platform" && ./gradlew --no-daemon :bootJar -x test -q ) && JAR="$(ls "$REPO"/platform/build/libs/*-boot.jar | head -1)"
( cd "$REPO/platform" && env SERVER_PORT="$BE_PORT" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/${PG_DB}?charSet=UTF8" \
    SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot \
    SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=6379 SPRING_DATA_REDIS_DATABASE="$REDIS_DB" \
    SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
    AURA_PLUGINS_DIR="$STAGE" AURA_BUILTIN_PLUGINS_DIR="$REPO/plugins" AGENT_LLM_STUB_MODE=true \
    java -jar "$JAR" > "$SD/backend.log" 2>&1 & echo $! > "$SD/backend.pid" )
BACKEND_PID="$(cat "$SD/backend.pid")"
for i in $(seq 1 120); do
  curl --noproxy '*' -sf "$BACKEND_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && break
  kill -0 "$BACKEND_PID" 2>/dev/null || { tail -20 "$SD/backend.log"; die "backend died"; }
  [ "$i" = 120 ] && { tail -20 "$SD/backend.log"; die "backend not healthy in 240s"; }
  sleep 2
done
log "    backend UP"

log "4/7 bootstrap"
curl --noproxy '*' -sS -X POST "$BACKEND_URL/api/bootstrap/setup" -H 'Content-Type: application/json' \
  -d '{"companyName":"MES-WMS Golden","adminEmail":"admin@auraboot.com","adminPassword":"Test2026x","adminDisplayName":"Admin","systemMode":"single","seedDemoData":false}' \
  | grep -q '"code":"0"' || die "bootstrap failed"

log "5/7 import full pcba-agent profile (one call → two-phase defer resolves the dep web)"
# Importing the whole profile in ONE call is essential: the two-phase import defers
# cross-plugin refs until every plugin is in, so interdependent plugins (pcba-industry
# needs pcba-sales' pe:* handler; pcba-manufacturing needs pcba-industry's dict) resolve.
# Importing one-at-a-time makes each plugin's closing reference-integrity sweep fail.
IARGS="--edition=enterprise --plugin-root=$REPO/plugins --enterprise-plugin-root=$PLUGINS"
"$REPO/scripts/import-plugins.sh" $IARGS --profile="$IMPORT_PROFILE" >"$SD/import.log" 2>&1 \
  || { grep -iE 'FAIL|unresolved|unregistered|missing' "$SD/import.log" | head -12; die "profile import failed — see $SD/import.log"; }
MFG_CMDS="$(psql -h 127.0.0.1 -p 5432 -U auraboot -d "$PG_DB" -tAc "select count(*) from ab_command_definition where code like 'mfg%'")"
[ "${MFG_CMDS:-0}" -gt 0 ] || die "pcba-manufacturing commands not registered (import incomplete)"
log "    imported — $MFG_CMDS mfg commands registered"

log "6/7 backend command-pipeline golden (real-stack IT)"
GOLDEN_DIR="$REPO/web-admin/tests/mes-wms"
RC=0
( cd "$GOLDEN_DIR" && node mes-wms-backend-golden.mjs ) || RC=1

if [ "$RUN_UI" = "1" ]; then
  # UI golden needs the frontend (Vite+BFF) up on this slot; the runner assumes it was started
  # separately (pnpm dev:full with SPRING_BOOT_URL=backend). Vite port = 5100+slot.
  ( cd "$REPO/web-admin" && BASE="http://127.0.0.1:$((5100 + SLOT))" node tests/mes-wms/ui/mes-wms-ui-golden.mjs ) || RC=1
fi

[ "$RC" = 0 ] && log "GOLDEN PASSED ✓" || log "GOLDEN FAILED ✗"
exit "$RC"
