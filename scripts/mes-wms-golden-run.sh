#!/usr/bin/env bash
#
# mes-wms-golden-run.sh — isolated real-stack golden runner for delivered MES/WMS FRs.
#
# One command owns build → stage → allocate → schema → backend → import → backend goldens.
# When BASE points at a matching live Vite+BFF pair, it also runs the registered browser
# goldens. Host-first, zero docker, isolated dev.sh runtime, and a runtime artifact manifest.
#
# Usage:
#   scripts/mes-wms-golden-run.sh [--slot N] [--name NAME] [--keep] [--no-ui]
#     --slot N : dev.sh runtime slot (default 64)
#     --name   : runtime allocation name (default mes-wms-handover-<slot>)
#     --keep   : do not tear down the stack on exit (for debugging / UI golden authoring)
#     --no-ui  : backend command-pipeline goldens only
#
# Source roots are explicit and may point at feature worktrees:
#   AURA_WORKSPACE_ROOT, AURA_CORE_PROJECT_ROOT, AURA_PLUGINS_PROJECT_ROOT,
#   AURA_QUOTE_PROJECT_ROOT. AURA_PREBUILT_PLUGIN_JARS_DIR may point at an explicitly
#   prebuilt, hashable jar set; otherwise every hybrid jar is rebuilt. Never silently
#   falls back from a failed build.
#
# Covers: FR-04 HandlingUnit · FR-05 Interlock · FR-09 Tooling · FR-10 FEFO · FR-13 Kitting ·
#         FR-16 Hold · FR-20 Downtime · FR-22 Shift Handover.
set -euo pipefail

SLOT=64; NAME=""; KEEP=0; RUN_UI=1
while [ $# -gt 0 ]; do case "$1" in
  --slot) SLOT="$2"; shift 2;;
  --name) NAME="$2"; shift 2;;
  --keep) KEEP=1; shift;;
  --no-ui) RUN_UI=0; shift;;
  *) echo "unknown arg: $1" >&2; exit 2;;
esac; done

CORE_ROOT="${AURA_CORE_PROJECT_ROOT:-$(git -C "$(cd "$(dirname "$0")/.." && pwd)" rev-parse --show-toplevel)}"
CANONICAL_CORE="$(cd "$(dirname "$(git -C "$CORE_ROOT" rev-parse --git-common-dir)")" && pwd)"
WORKSPACE_ROOT="${AURA_WORKSPACE_ROOT:-$(dirname "$CANONICAL_CORE")}"
PLUGINS="${AURA_PLUGINS_PROJECT_ROOT:-$WORKSPACE_ROOT/plugins}"
QUOTE_ROOT="${AURA_QUOTE_PROJECT_ROOT:-$WORKSPACE_ROOT/aura-quote}"
DEV_SH="$WORKSPACE_ROOT/dev.sh"
NAME="${NAME:-mes-wms-handover-${SLOT}}"
SD="$(mktemp -d)"
STAGE="$SD/plugins"; mkdir -p "$STAGE"
EVIDENCE_DIR="$SD/evidence"; mkdir -p "$EVIDENCE_DIR"
BE_PORT=$((6400 + SLOT)); BACKEND_URL="http://127.0.0.1:${BE_PORT}"
PG_DB="auraboot_${SLOT}"; REDIS_DB=$((SLOT % 16))
export PGPASSWORD=auraboot BACKEND_URL PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=auraboot PG_DB
export ADMIN_EMAIL=admin@auraboot.com ADMIN_PASSWORD=Test2026x
log() { printf '\033[36m[mes-wms-golden]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[mes-wms-golden] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

BACKEND_PID=""
BACKEND_TMUX_SESSION="mes-wms-${NAME}-backend"
FRONTEND_PID=""
start_backend() {
  if [ "$KEEP" = "1" ]; then
    command -v tmux >/dev/null || die "--keep requires tmux for detached backend ownership"
    tmux has-session -t "$BACKEND_TMUX_SESSION" 2>/dev/null \
      && die "owned backend tmux session already exists: $BACKEND_TMUX_SESSION"
    local backend_command
    printf -v backend_command \
      'exec env SERVER_PORT=%q SPRING_DATASOURCE_URL=%q SPRING_DATASOURCE_USERNAME=%q SPRING_DATASOURCE_PASSWORD=%q SPRING_DATA_REDIS_HOST=%q SPRING_DATA_REDIS_PORT=%q SPRING_DATA_REDIS_DATABASE=%q SPRING_KAFKA_BOOTSTRAP_SERVERS=%q AURA_PLUGINS_DIR=%q AURA_BUILTIN_PLUGINS_DIR=%q AGENT_LLM_STUB_MODE=true java -jar %q >> %q 2>&1' \
      "$BE_PORT" "jdbc:postgresql://127.0.0.1:5432/${PG_DB}?charSet=UTF8" \
      auraboot auraboot 127.0.0.1 6379 "$REDIS_DB" 127.0.0.1:9092 \
      "$STAGE" "$CORE_ROOT/plugins" "$JAR" "$SD/backend.log"
    tmux new-session -d -s "$BACKEND_TMUX_SESSION" -c "$CORE_ROOT/platform" "$backend_command"
    tmux display-message -p -t "$BACKEND_TMUX_SESSION:0.0" '#{pane_pid}' > "$SD/backend.pid"
  else
    (
      cd "$CORE_ROOT/platform"
      env SERVER_PORT="$BE_PORT" \
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/${PG_DB}?charSet=UTF8" \
        SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot \
        SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=6379 SPRING_DATA_REDIS_DATABASE="$REDIS_DB" \
        SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
        AURA_PLUGINS_DIR="$STAGE" AURA_BUILTIN_PLUGINS_DIR="$CORE_ROOT/plugins" AGENT_LLM_STUB_MODE=true \
        java -jar "$JAR" >> "$SD/backend.log" 2>&1 &
      echo $! > "$SD/backend.pid"
    )
  fi
  BACKEND_PID="$(cat "$SD/backend.pid")"
  for i in $(seq 1 120); do
    curl --noproxy '*' -sf "$BACKEND_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && return
    kill -0 "$BACKEND_PID" 2>/dev/null || { tail -20 "$SD/backend.log"; die "backend died"; }
    [ "$i" = 120 ] && { tail -20 "$SD/backend.log"; die "backend not healthy in 240s"; }
    sleep 2
  done
}
stop_backend() {
  [ -n "$BACKEND_PID" ] || return
  kill "$BACKEND_PID" 2>/dev/null || true
  for _ in $(seq 1 30); do
    kill -0 "$BACKEND_PID" 2>/dev/null || break
    sleep 1
  done
  kill -0 "$BACKEND_PID" 2>/dev/null && die "backend did not stop cleanly"
  tmux kill-session -t "$BACKEND_TMUX_SESSION" 2>/dev/null || true
  BACKEND_PID=""
}
cleanup() {
  if [ "$KEEP" != "1" ]; then
    [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID" 2>/dev/null || true
    if [ -n "$BACKEND_PID" ]; then
      kill "$BACKEND_PID" 2>/dev/null || true
      for _ in $(seq 1 30); do
        kill -0 "$BACKEND_PID" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "$BACKEND_PID" 2>/dev/null; then
        log "backend did not stop after 30s; forcing owned pid $BACKEND_PID before DB cleanup"
        kill -9 "$BACKEND_PID" 2>/dev/null || true
      fi
      BACKEND_PID=""
    fi
    log "teardown runtime '$NAME'"
    if ! ( cd "$WORKSPACE_ROOT" && "$DEV_SH" infra cleanup "$NAME" --yes >/dev/null 2>&1 ); then
      log "WARNING: infra cleanup failed for owned runtime '$NAME'"
    fi
    ( cd "$WORKSPACE_ROOT" && "$DEV_SH" runtime destroy "$NAME" --yes >/dev/null 2>&1 ) || true
  else
    log "--keep: leaving stack up (backend pid $BACKEND_PID, db $PG_DB, temp $SD)"
    trap - EXIT
  fi
}
trap cleanup EXIT

# Every staged hybrid jar must be fresh: stale workspace artifacts can miss already-merged
# handlers and make a valid source checkout fail at runtime. Config-only packages need no jar.
# The import below intentionally uses the MES/WMS dependency closure instead of pcba-agent:
# unrelated reserve plugins must not make this focused gate fail (for example, pcba-warehouse
# currently owns commands/pages for pe_wave and pe_asn but does not provide those models).
HYBRID_JARS=(product-catalog crm inventory finance quality procurement pcba-solution
  pcba-procurement jiejia-integration bom-standardization pcba-sales pcba-manufacturing
  pcba-warehouse pcba-finance pcba-compliance)
QUOTE_HYBRID=(quote-engine)  # built from aura-quote/plugin-aura/<p>/backend
IMPORT_PLUGINS=(core-meta core-bpm platform-admin core-decisionops core-announcement core-aurabot
  page-manager org-management agent-control-plane product-catalog crm pcba-crm req inventory finance
  sales quality procurement quote-core pcba-base pcba-industry pcba-manufacturing pcba-compliance
  pcba-solution)

log "1/7 prepare verified hybrid plugin jars"
build_jar() {  # <plugin> <backend-dir>
  [ -d "$2" ] || die "missing backend for hybrid plugin $1: $2"
  "$CORE_ROOT/platform/gradlew" --project-dir "$2" jar --console=plain -q --no-daemon \
    || die "build failed: $1"
  local j; j="$(ls "$2/build/libs/"*.jar 2>/dev/null | head -1)"
  [ -n "$j" ] || die "build produced no jar: $1"
  cp "$j" "$STAGE/"
}
if [ -n "${AURA_PREBUILT_PLUGIN_JARS_DIR:-}" ]; then
  [ -d "$AURA_PREBUILT_PLUGIN_JARS_DIR" ] \
    || die "AURA_PREBUILT_PLUGIN_JARS_DIR does not exist: $AURA_PREBUILT_PLUGIN_JARS_DIR"
  cp "$AURA_PREBUILT_PLUGIN_JARS_DIR"/*.jar "$STAGE/"
else
  ( unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL
    for p in "${HYBRID_JARS[@]}"; do build_jar "$p" "$PLUGINS/$p/backend"; done
    for p in "${QUOTE_HYBRID[@]}"; do build_jar "$p" "$QUOTE_ROOT/plugin-aura/$p/backend"; done )
fi
STAGED_COUNT="$(find "$STAGE" -maxdepth 1 -type f -name '*.jar' | wc -l | tr -d ' ')"
[ "$STAGED_COUNT" = 16 ] || die "staged $STAGED_COUNT hybrid jars; expected exactly 16"
for p in "${HYBRID_JARS[@]}" "${QUOTE_HYBRID[@]}"; do
  compgen -G "$STAGE/${p}-plugin-*.jar" >/dev/null || die "missing staged jar: $p"
done
log "    staged $STAGED_COUNT verified hybrid jars"

log "2/7 allocate runtime (slot $SLOT) + infra + schema"
if ! "$DEV_SH" runtime env "$NAME" >/dev/null 2>&1; then
  "$DEV_SH" runtime allocate auraboot "$NAME" --slot "$SLOT" \
    --purpose "MES/WMS handover closure real-stack golden" --ttl 8h >/dev/null
fi
"$DEV_SH" infra cleanup "$NAME" --yes >/dev/null || die "pre-run infra reset failed"
"$DEV_SH" infra ensure "$NAME" --yes >/dev/null || die "infra ensure failed"
psql -h 127.0.0.1 -p 5432 -U auraboot -d "$PG_DB" -c 'select 1 from ab_meta_model limit 1' >/dev/null 2>&1 \
  || psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5432 -U auraboot -d "$PG_DB" -q \
       -f "$CORE_ROOT/platform/src/main/resources/db/snapshots/schema-current.sql" >/dev/null 2>&1 \
  || die "schema apply failed"

log "3/7 start backend (java -jar, AURA_PLUGINS_DIR=fresh hybrid jars) on $BE_PORT"
( cd "$CORE_ROOT/platform" && ./gradlew --no-daemon :bootJar -x test -q ) \
  || die "current-source bootJar build failed"
JAR="$(ls "$CORE_ROOT"/platform/build/libs/*-boot.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || die "current-source bootJar build produced no artifact"
start_backend
log "    backend UP"

log "4/7 bootstrap"
BOOTSTRAP_RESPONSE="$(curl --noproxy '*' -sS -X POST "$BACKEND_URL/api/bootstrap/setup" \
  -H 'Content-Type: application/json' \
  -d '{"companyName":"MES-WMS Golden","adminEmail":"admin@auraboot.com","adminPassword":"Test2026x","adminDisplayName":"Admin","systemMode":"single","seedDemoData":false}')"
printf '%s' "$BOOTSTRAP_RESPONSE" | grep -q '"code":"0"' \
  || die "bootstrap failed: ${BOOTSTRAP_RESPONSE:0:500}"

log "5/7 import MES/WMS dependency closure (${#IMPORT_PLUGINS[@]} packages)"
IARGS="--edition=enterprise --plugin-root=$CORE_ROOT/plugins --enterprise-plugin-root=$PLUGINS --extra-plugin-root=$QUOTE_ROOT/plugin-aura"
IMPORT_LOG="$SD/import.log"
"$CORE_ROOT/scripts/import-plugins.sh" $IARGS "${IMPORT_PLUGINS[@]}" >"$IMPORT_LOG" 2>&1 \
  || { grep -iE 'FAIL|unresolved|unregistered|missing' "$IMPORT_LOG" | head -12; die "MES/WMS dependency import failed — see $IMPORT_LOG"; }
MFG_CMDS="$(psql -h 127.0.0.1 -p 5432 -U auraboot -d "$PG_DB" -tAc "select count(*) from ab_command_definition where code like 'mfg%'")"
[ "${MFG_CMDS:-0}" -gt 0 ] || die "pcba-manufacturing commands not registered (import incomplete)"
log "    imported — $MFG_CMDS mfg commands registered"
log "    restart backend so imported model/field projections are loaded"
stop_backend
start_backend
log "    backend UP after import"

log "6/7 backend command-pipeline goldens (real-stack IT)"
GOLDEN_DIR="$CORE_ROOT/web-admin/tests/mes-wms"
RC=0
run_golden() {
  local script="$1"
  ( cd "$GOLDEN_DIR" && node "$script" ) >"$EVIDENCE_DIR/${script%.mjs}.log" 2>&1 || RC=1
  tail -3 "$EVIDENCE_DIR/${script%.mjs}.log"
}
run_golden mes-wms-backend-golden.mjs
run_golden fr10-fefo-golden.mjs
run_golden fr08-12-14-golden.mjs
run_golden fr08-12-14-deep-golden.mjs

if [ "$RUN_UI" = "1" ]; then
  BASE="${BASE:-http://127.0.0.1:$((5100 + SLOT))}"
  curl --noproxy '*' -sf "$BASE/" >/dev/null \
    || die "UI requested but Vite+BFF is not live at $BASE; use --no-ui or start the matching frontend"
  for ui_script in mes-wms-yellow-fr-golden.mjs fr07-action-golden.mjs \
    fr22-action-golden.mjs mes-action-points-golden.mjs mes-wms-ui-golden.mjs \
    list-interaction-golden.mjs; do
    ( cd "$GOLDEN_DIR/ui" && BASE="$BASE" node "$ui_script" ) \
      >"$EVIDENCE_DIR/${ui_script%.mjs}.log" 2>&1 || RC=1
    tail -2 "$EVIDENCE_DIR/${ui_script%.mjs}.log"
  done
fi

CORE_HEAD="$(git -C "$CORE_ROOT" rev-parse HEAD)"
PLUGINS_HEAD="$(git -C "$PLUGINS" rev-parse HEAD)"
JAR_HASH="$(shasum -a 256 "$STAGE"/pcba-manufacturing-plugin-*.jar | awk '{print $1}')"
CONFIG_HASH="$(find "$PLUGINS/pcba-manufacturing/config" -type f -print0 | sort -z | xargs -0 shasum -a 256 | shasum -a 256 | awk '{print $1}')"
printf '{"runtime":"%s","generatedAt":"%s","sourceRoots":{"core":"%s","plugins":"%s"},"git":{"coreHead":"%s","pluginsHead":"%s"},"backend":{"pid":%s,"serverPort":%s,"bootJarPath":"%s"},"plugin":{"code":"pcba-manufacturing","jarHash":"%s","configHash":"%s","importLog":"%s"}}\n' \
  "$NAME" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$CORE_ROOT" "$PLUGINS" "$CORE_HEAD" "$PLUGINS_HEAD" \
  "$BACKEND_PID" "$BE_PORT" "$JAR" "$JAR_HASH" "$CONFIG_HASH" "$IMPORT_LOG" \
  >"$SD/artifact-manifest.json"
log "    artifact manifest: $SD/artifact-manifest.json"

[ "$RC" = 0 ] && log "GOLDEN PASSED ✓" || log "GOLDEN FAILED ✗"
exit "$RC"
