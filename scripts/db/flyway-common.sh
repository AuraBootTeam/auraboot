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

_require_db() {
  if [[ -z "$PG_DB" ]]; then
    echo "[flyway-common] PG_DB (target database) is required" >&2
    exit 2
  fi
}

# Build the comma-separated Flyway locations for an edition.
# oss        -> core
# enterprise -> core + enterprise (layered into the same database/history)
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
  printf '%s' "$locs"
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
  echo "[flyway] $cmd  db=$PG_DB  edition=$edition" >&2
  echo "[flyway] locations=$locations" >&2
  flyway "${args[@]}" "$cmd"
}
