#!/usr/bin/env bash
# Shared Flyway environment + invocation for AuraBoot PostgreSQL schema governance.
#
# Source this file, then call run_flyway <command> <edition> [enterprise-root].
# It resolves connection settings and Flyway locations from the environment and
# invokes the Flyway CLI with the AuraBoot-standard flags.
#
# End-state contract (see the schema-governance end-state spec at
# docs/superpowers/specs/2026-06-18-postgresql-flyway-schema-governance-endstate.md):
#   - Flyway is the only hand-authored schema source of truth.
#   - History table is ab_flyway_schema_history.
#   - baselineOnMigrate is never enabled by default (do not mask a dirty DB).
#   - Migration naming is validated.
#   - clean is disabled (never drop the whole schema by accident).
set -euo pipefail

command -v flyway >/dev/null 2>&1 || {
  echo "[flyway-common] flyway CLI not found on PATH. Install host-first: brew install flyway" >&2
  exit 127
}

# Connection (PG_* preferred, PG-standard PGxxx accepted as fallback).
PG_HOST="${PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${PG_PORT:-${PGPORT:-5432}}"
PG_USER="${PG_USER:-${PGUSER:-auraboot}}"
PG_PASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}"
PG_DB="${PG_DB:-${PGDATABASE:-}}"

# Core repo root = the repo that owns db/migration/core. Defaults to the repo
# containing this script (scripts/db/ -> repo root).
AURA_CORE_ROOT="${AURA_CORE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
CORE_MIGRATION_DIR="$AURA_CORE_ROOT/platform/src/main/resources/db/migration/core"
if [[ -n "${AURA_FLYWAY_CORE_MIGRATION_DIR:-}" ]]; then
  CORE_MIGRATION_DIR="$AURA_FLYWAY_CORE_MIGRATION_DIR"
fi

_require_db() {
  if [[ -z "$PG_DB" ]]; then
    echo "[flyway-common] PG_DB (target database) is required" >&2
    exit 2
  fi
}

# Build the comma-separated Flyway locations for an edition.
# oss        -> core
# enterprise -> core + enterprise (layered into the same database/history)
# AURA_FLYWAY_EXTRA_LOCATIONS (colon-separated filesystem paths) is appended
# when set — extracted product applications (e.g. aura-bpm/migrations/bpm)
# layer their migrations into the same database/history this way.
_build_locations() {
  local edition="$1" ent_root="${2:-}"
  if [[ ! -d "$CORE_MIGRATION_DIR" ]]; then
    echo "[flyway-common] core migration dir not found: $CORE_MIGRATION_DIR" >&2
    exit 2
  fi
  local locs="filesystem:$CORE_MIGRATION_DIR"
  if [[ "$edition" == "enterprise" ]]; then
    if [[ -z "$ent_root" ]]; then
      echo "[flyway-common] --enterprise-root is required for edition=enterprise" >&2
      exit 2
    fi
    local ent_dir="$ent_root/platform/src/main/resources/db/migration/enterprise"
    if [[ ! -d "$ent_dir" ]]; then
      echo "[flyway-common] enterprise migration dir not found: $ent_dir" >&2
      exit 2
    fi
    locs="$locs,filesystem:$ent_dir"
  fi
  if [[ -n "${AURA_FLYWAY_EXTRA_LOCATIONS:-}" ]]; then
    local extra="" part
    while IFS= read -r part; do
      [[ -n "$part" ]] || continue
      extra="${extra:+$extra,}filesystem:$part"
    done < <(printf '%s\n' "$AURA_FLYWAY_EXTRA_LOCATIONS" | tr ':' '\n')
    locs="$locs,$extra"
  fi
  printf '%s' "$locs"
}

# Known-drift checksum realignment policy (2026-09-19 quote/BOM fresh-runtime fixes).
#
# V20260919050000 / V20260919051000 (enterprise) and V20260919052000 (aura-crm,
# layered via AURA_FLYWAY_EXTRA_LOCATIONS) were made fresh-safe in place AFTER
# they had already shipped on main and been applied to long-lived databases.
# Those databases record the ORIGINAL checksums, so a plain `flyway migrate`
# would fail validation on upgrade. Policy: before `migrate`, run the official
# `flyway repair` realignment ONLY when the history table records exactly the
# known pre-change checksum below for one of these versions. Any other drift is
# never repaired here — Flyway validation still fails loudly. Set
# AURA_FLYWAY_SKIP_KNOWN_DRIFT_REPAIR=1 to disable the realignment.
KNOWN_DRIFT_OLD_CHECKSUMS_20260919050000=-1983915974
KNOWN_DRIFT_OLD_CHECKSUMS_20260919051000=-1381363862
KNOWN_DRIFT_OLD_CHECKSUMS_20260919052000=450260653

# _preflight_known_drift_repair <flyway args without command>
# Inspects ab_flyway_schema_history and, when exactly a known pre-change
# checksum is recorded for one of the versions above, runs `flyway repair`
# with the same configuration the subsequent migrate would use.
_preflight_known_drift_repair() {
  if [ -n "${AURA_FLYWAY_SKIP_KNOWN_DRIFT_REPAIR:-}" ]; then
    return 0
  fi
  command -v psql >/dev/null 2>&1 || return 0
  local query_result
  query_result="$(PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" \
    -v ON_ERROR_STOP=1 -Atc \
    "SELECT version || '=' || checksum FROM ab_flyway_schema_history
      WHERE version IN ('20260919050000','20260919051000','20260919052000')" 2>/dev/null)" || return 0
  [ -n "$query_result" ] || return 0

  local version checksum var known needs_repair=0
  while IFS='=' read -r version checksum; do
    [ -n "$version" ] || continue
    var="KNOWN_DRIFT_OLD_CHECKSUMS_${version}"
    known="${!var:-}"
    if [ -n "$known" ] && [ "$checksum" = "$known" ]; then
      needs_repair=1
    fi
  done <<< "$query_result"

  if [ "$needs_repair" -eq 1 ]; then
    echo "[flyway] known-drift preflight: history records pre-2026-09-19 checksums for the" >&2
    echo "[flyway] fresh-safe quote/BOM migrations; running official 'flyway repair' realignment first" >&2
    flyway "$@" repair
  fi
}

# run_flyway <flyway-command> <edition: oss|enterprise> [enterprise-root]
run_flyway() {
  local cmd="$1" edition="${2:-oss}" ent_root="${3:-}"
  _require_db
  local locations
  locations="$(_build_locations "$edition" "$ent_root")"
  local -a args=(
    "-url=jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB"
    "-user=$PG_USER"
    "-password=$PG_PASSWORD"
    "-locations=$locations"
    "-table=ab_flyway_schema_history"
    "-baselineOnMigrate=false"
    "-validateMigrationNaming=true"
    "-cleanDisabled=true"
  )
  if [[ "${AURA_FLYWAY_OUT_OF_ORDER:-}" == 1 ]]; then
    args+=("-outOfOrder=true")
  fi
  echo "[flyway] $cmd  db=$PG_DB  edition=$edition" >&2
  echo "[flyway] locations=$locations" >&2
  if [ "$cmd" = "migrate" ]; then
    _preflight_known_drift_repair "${args[@]}"
  fi
  flyway "${args[@]}" "$cmd"
}
