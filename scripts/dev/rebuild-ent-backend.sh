#!/usr/bin/env bash
#
# One-command G-mob-14 backend rebuild: publish OSS artifacts to ~/.m2 →
# build the Enterprise bootJar → restart the :18191 test backend → seed smoke.
#
# Replaces the tribal-knowledge flow that broke twice (version coupling:
# OSS VERSION=x.y.z publishes x.y.z while Enterprise consumed a stale
# x.y.z-SNAPSHOT; restart needed undocumented datasource env).
#
# Usage:
#   scripts/dev/rebuild-ent-backend.sh                 # publish + jar + restart + smoke
#   RESTART=0 scripts/dev/rebuild-ent-backend.sh       # build only, keep old process
#   ENT_REPO=/path/to/auraboot-enterprise ...          # override enterprise checkout
#
# Requirements: both repos on the commits you want; OSS worktree guard is
# bypassed with FORCE_HOST=1 (audited in ~/.aura/host-override.log) because
# this script is the single sanctioned publisher for the host backend.
set -euo pipefail

OSS_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
ENT_ROOT="${ENT_REPO:-$(dirname "$OSS_ROOT")/auraboot-enterprise}"
PORT="${PORT:-18191}"
SKIP_TESTS="${SKIP_TESTS:-1}"
RESTART="${RESTART:-1}"

PG_PORT="${PG_PORT:-15591}"
PG_USER="${PG_USER:-auraboot}"
PG_PASSWORD="${PG_PASSWORD:-auraboot}"
PG_DB="${PG_DB:-aura_boot}"
REDIS_PORT="${REDIS_PORT:-16391}"

log() { echo "[rebuild-ent] $*"; }
fail() { echo "[rebuild-ent] ERROR: $*" >&2; exit 1; }

[ -d "$ENT_ROOT/platform" ] || fail "enterprise repo not found at $ENT_ROOT (set ENT_REPO)"

# --- 1. Publish OSS artifacts at the VERSION the enterprise consumes --------
OSS_VERSION="$(tr -d '[:space:]' < "$OSS_ROOT/VERSION")"
log "OSS VERSION=$OSS_VERSION — publishing to ~/.m2"
(cd "$OSS_ROOT/platform" && FORCE_HOST=1 ./gradlew publishToMavenLocal \
    ${SKIP_TESTS:+-x test} --console=plain > /tmp/rebuild-ent-publish.log) \
  || fail "publishToMavenLocal failed — see /tmp/rebuild-ent-publish.log"

# --- 2. Freshness + version-coupling guard ---------------------------------
CORE_JAR="$HOME/.m2/repository/com/auraboot/auraboot-core/$OSS_VERSION/auraboot-core-$OSS_VERSION.jar"
[ -f "$CORE_JAR" ] || fail "expected artifact missing: $CORE_JAR"
OSS_HEAD_TS="$(git -C "$OSS_ROOT" log -1 --format=%ct)"
JAR_TS="$(stat -f %m "$CORE_JAR" 2>/dev/null || stat -c %Y "$CORE_JAR")"
if [ "$JAR_TS" -lt "$OSS_HEAD_TS" ]; then
  fail "stale artifact: $CORE_JAR predates OSS HEAD. Re-run this script from OSS main."
fi
log "artifact fresh: auraboot-core:$OSS_VERSION"

if grep -rn "auraboot-core:[0-9.]*-SNAPSHOT" "$ENT_ROOT/platform" --include="build.gradle" >/dev/null 2>&1; then
  fail "enterprise still depends on a -SNAPSHOT auraboot-core — version coupling regressed.
       Fix the dependency lines to auraboot-core:$OSS_VERSION and re-run."
fi
log "enterprise dependency coupling OK (no SNAPSHOT refs)"

# --- 3. Build the Enterprise bootJar ----------------------------------------
log "building Enterprise bootJar"
(cd "$ENT_ROOT/platform" && ./gradlew bootJar --no-daemon \
    ${SKIP_TESTS:+-x test} --console=plain > /tmp/rebuild-ent-bootjar.log) \
  || fail "bootJar failed — see /tmp/rebuild-ent-bootjar.log"
ENT_JAR="$(ls -t "$ENT_ROOT/platform/build/libs/"AuraBoot-Enterprise-*.jar | grep -v plain | head -1)"
log "jar built: $ENT_JAR"

# --- 4. Restart the :18191 backend ------------------------------------------
if [ "$RESTART" = "1" ]; then
  OLD_PID="$(lsof -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | head -1 || true)"
  if [ -n "${OLD_PID:-}" ]; then
    log "stopping old backend pid=$OLD_PID"
    kill "$OLD_PID" 2>/dev/null || true
    sleep 3
  fi
  log "starting backend on :$PORT (PG :$PG_PORT, redis :$REDIS_PORT)"
  SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/$PG_DB" \
  SPRING_DATASOURCE_USERNAME="$PG_USER" \
  SPRING_DATASOURCE_PASSWORD="$PG_PASSWORD" \
  REDIS_HOST=127.0.0.1 REDIS_PORT="$REDIS_PORT" \
    nohup java -Xmx2g -jar "$ENT_JAR" --server.port=$PORT \
      > "/tmp/backend-$PORT.log" 2>&1 &
  for i in $(seq 1 60); do
    if curl -sf -m 3 "http://127.0.0.1:$PORT/actuator/health" | grep -q '"status":"UP"'; then
      log "backend UP on :$PORT"
      break
    fi
    [ "$i" = "60" ] && fail "backend health timed out — see /tmp/backend-$PORT.log"
    sleep 2
  done

  # --- 5. Seed smoke ---------------------------------------------------------
  SEED_JSON="$(curl -sf -m 60 -X POST "http://127.0.0.1:$PORT/api/test/seed" \
    -H 'Content-Type: application/json' -d '{}')" || fail "seed failed"
  TENANT="$(echo "$SEED_JSON" | python3 -c "import json,sys; d=json.load(sys.stdin); print((d.get('data') or d).get('tenantId'))")"
  log "seed OK — tenant=$TENANT"
  log "DONE. Logs: /tmp/backend-$PORT.log, /tmp/rebuild-ent-*.log"
else
  log "RESTART=0 — build only, backend untouched"
fi
