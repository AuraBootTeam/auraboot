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

# The 8 FRs live in inventory + pcba-manufacturing; both are hybrid. Their dep chain
# (finance→procurement/sales→pcba-industry→pcba-manufacturing) must all import, so every
# hybrid dep needs a FRESH jar (stale platform/plugins jars miss handlers → S-EXT-HANDLER).
HYBRID_JARS=(inventory pcba-manufacturing product-catalog quality finance crm procurement)
IMPORT_BASE=(core-meta core-bpm platform-admin core-decisionops core-announcement core-aurabot
  page-manager org-management product-catalog crm inventory quality)
IMPORT_CHAIN=(finance procurement sales pcba-industry pcba-manufacturing)

log "1/7 build fresh hybrid plugin jars"
( unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL
  for p in "${HYBRID_JARS[@]}"; do
    [ -d "$PLUGINS/$p/backend" ] && ( cd "$PLUGINS/$p/backend" && gradle jar --console=plain -q >/dev/null 2>&1 ) || true
    j="$(ls "$PLUGINS/$p/backend/build/libs/"*.jar 2>/dev/null | head -1)"
    [ -n "$j" ] && cp "$j" "$STAGE/"
  done )
log "    staged $(ls "$STAGE"/*.jar | wc -l | tr -d ' ') hybrid jars"

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

log "5/7 import plugin configs (cross-repo: enterprise-plugin-root=sibling plugins repo)"
IARGS="--edition=enterprise --plugin-root=$REPO/plugins --enterprise-plugin-root=$PLUGINS"
"$REPO/scripts/import-plugins.sh" $IARGS "${IMPORT_BASE[@]}" >"$SD/import-base.log" 2>&1 || { tail -8 "$SD/import-base.log"; die "base import failed"; }
for p in "${IMPORT_CHAIN[@]}"; do
  "$REPO/scripts/import-plugins.sh" $IARGS "$p" >"$SD/import-$p.log" 2>&1 || { tail -8 "$SD/import-$p.log"; die "import $p failed"; }
done
MFG_CMDS="$(psql -h 127.0.0.1 -p 5432 -U auraboot -d "$PG_DB" -tAc "select count(*) from ab_command_definition where code like 'mfg%'")"
[ "${MFG_CMDS:-0}" -gt 0 ] || die "pcba-manufacturing commands not registered (import chain incomplete)"
log "    imported — $MFG_CMDS mfg commands registered"

log "6/7 backend command-pipeline golden (real-stack IT)"
GOLDEN_DIR="$REPO/web-admin/tests/mes-wms"
RC=0
( cd "$GOLDEN_DIR" && node mes-wms-backend-golden.mjs ) || RC=1

if [ "$RUN_UI" = "1" ]; then
  log "7/7 UI golden — (requires frontend; see mes-wms/*.spec.ts) [not yet wired]"
fi

[ "$RC" = 0 ] && log "GOLDEN PASSED ✓" || log "GOLDEN FAILED ✗"
exit "$RC"
