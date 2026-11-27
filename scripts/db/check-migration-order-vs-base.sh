#!/usr/bin/env bash
# A migration added by a change must be newer than every migration the base
# branch already carries.
#
# check-migration-version-floor.sh cannot tell a floor raise from a backdated
# insertion that also bumps the count, and the fresh-database gate applies a
# lower version in order and never sees the problem. Every preserved database
# that already recorded a higher version then rejects the upgrade
# ("Detected resolved migration not applied to database"): #2550 landed
# V20261119003000 under a 20261123090000 floor and blocked all of them.
# Out-of-order is retired on this platform, so the only repair is a new version.
#
# Usage: scripts/db/check-migration-order-vs-base.sh <base-ref>
# Exit: 0 = nothing backdated, 1 = backdated migration, 2 = usage or git failure.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_ROOT="${AURA_CORE_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
MIGRATIONS="platform/src/main/resources/db/migration/core"
[[ "$#" -eq 1 && -n "$1" ]] || { echo "[migration-order] usage: $0 <base-ref>" >&2; exit 2; }
base_ref="$1"

versions() { # sorted 14-digit versions of the core migrations a ref carries
  git -C "$CORE_ROOT" ls-tree -r --name-only "$1" -- "$MIGRATIONS" \
    | sed -nE 's#.*/V([0-9]{14})__[^/]*\.sql$#\1#p' | sort
}

git -C "$CORE_ROOT" rev-parse --verify --quiet "${base_ref}^{commit}" >/dev/null || {
  echo "[migration-order] base ref not found: $base_ref" >&2; exit 2; }
base_highest="$(versions "$base_ref" | tail -1)"
[[ "$base_highest" =~ ^[0-9]{14}$ ]] || { echo "[migration-order] $base_ref carries no core migration" >&2; exit 2; }

status=0
while IFS= read -r added; do
  if (( added <= base_highest )); then
    echo "[migration-order] V${added} is not above V${base_highest}, the highest migration on ${base_ref}." >&2
    echo "[migration-order] Preserved databases already recorded V${base_highest} and cannot apply it in order; re-version it above scripts/db/migration-version-floor.txt." >&2
    status=1
  fi
done < <(comm -13 <(versions "$base_ref") <(versions HEAD))
exit "$status"
