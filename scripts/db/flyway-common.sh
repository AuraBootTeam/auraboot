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
  echo "[flyway-common] migration directory overlays are retired; use a reviewed release payload" >&2
  exit 2
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

# run_flyway <flyway-command> <edition: oss|enterprise> [enterprise-root]
run_flyway() {
  local cmd="$1" edition="${2:-oss}" ent_root="${3:-}"
  case "$cmd" in
    migrate|validate|info) ;;
    *) echo "[flyway-common] only migrate, validate and info are permitted" >&2; return 2 ;;
  esac
  case "$edition" in
    oss|enterprise) ;;
    *) echo "[flyway-common] unknown edition" >&2; return 2 ;;
  esac
  if [[ "${AURA_FLYWAY_OUT_OF_ORDER:-0}" != 0 || "${AURA_FLYWAY_PRE1900_CORE_COMPAT:-0}" != 0 ]]; then
    echo "[flyway-common] historical compatibility and out-of-order overrides are retired" >&2
    return 2
  fi
  _require_db
  local locations
  locations="$(_build_locations "$edition" "$ent_root")"
  local -a args=(
    "-url=jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB"
    "-user=$PG_USER"
    "-locations=$locations"
    "-table=ab_flyway_schema_history"
    "-baselineOnMigrate=false"
    "-outOfOrder=false"
    "-validateMigrationNaming=true"
    "-cleanDisabled=true"
  )
  echo "[flyway] $cmd  db=$PG_DB  edition=$edition" >&2
  echo "[flyway] locations=$locations" >&2
  FLYWAY_PASSWORD="$PG_PASSWORD" flyway "${args[@]}" "$cmd"
}

# Explicit control-plane migration boundary, separate from the Core/enterprise
# public history. The caller owns artifact admission and target authorization.
# This is not a route for ordinary product entities (use dynamic models).
# run_product_flyway <migrate|validate|info> <private-schema> <absolute-payload-dir>
run_product_flyway() {
  if [[ "$#" != 3 ]]; then
    echo "[flyway-common] product mode requires command, schema and payload directory" >&2; return 2
  fi
  local cmd="$1" schema="$2" payload="$3" variable
  case "$cmd" in
    migrate|validate|info) ;;
    *) echo "[flyway-common] only migrate, validate and info are permitted" >&2; return 2 ;;
  esac
  if [[ ! "$schema" =~ ^[a-z][a-z0-9_]{0,62}$ || "$schema" == public || "$schema" == information_schema || "$schema" == pg_* ]]; then
    echo "[flyway-common] an explicit non-system private schema is required" >&2; return 2
  fi
  if [[ "${AURA_FLYWAY_OUT_OF_ORDER:-0}" != 0 || "${AURA_FLYWAY_PRE1900_CORE_COMPAT:-0}" != 0 || -n "${AURA_FLYWAY_EXTRA_LOCATIONS:-}" ]]; then
    echo "[flyway-common] product mode rejects history overrides and extra locations" >&2; return 2
  fi
  # Do not inherit a developer's URL, callbacks, init SQL, target or validation
  # exceptions. Password comes only from the already resolved PG connection.
  while IFS= read -r variable; do
    if [[ "$variable" == FLYWAY_* ]]; then
      echo "[flyway-common] product mode rejects ambient Flyway configuration" >&2; return 2
    fi
  done < <(compgen -e)
  if [[ "$payload" != /* || "$payload" == *','* || "$payload" == *':'* || ! -d "$payload" ||
        "$(cd "$payload" && pwd -P)" != "$payload" || -n "$(find "$payload" -type l -print -quit)" ]]; then
    echo "[flyway-common] product payload must be an absolute regular directory without symlinks or location separators" >&2; return 2
  fi
  _require_db
  local config="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/flyway-product.conf"
  local -a args=(
    "-configFiles=$config"
    "-url=jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB"
    "-user=$PG_USER"
    "-locations=filesystem:$payload"
    "-schemas=$schema" "-defaultSchema=$schema"
    "-table=ab_flyway_schema_history"
    "-baselineOnMigrate=false" "-outOfOrder=false"
    "-validateMigrationNaming=true" "-validateOnMigrate=true"
    "-ignoreMigrationPatterns=" "-cleanDisabled=true"
    "-failOnMissingLocations=true" "-placeholderReplacement=false"
    "-skipDefaultCallbacks=true" "-callbacks=" "-resolvers="
  )
  echo "[flyway] $cmd db=$PG_DB private-schema=$schema product-only" >&2
  FLYWAY_PASSWORD="$PG_PASSWORD" flyway "${args[@]}" "$cmd"
}
