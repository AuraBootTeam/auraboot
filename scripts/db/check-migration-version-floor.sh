#!/usr/bin/env bash
# Fail before schema generation if the released migration inventory is stale.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_ROOT="${AURA_CORE_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
FLOOR="$CORE_ROOT/scripts/db/migration-version-floor.txt"
MIGRATIONS="$CORE_ROOT/platform/src/main/resources/db/migration/core"
[[ -f "$FLOOR" && -d "$MIGRATIONS" ]] || { echo "[migration-floor] Missing explicit Core inventory" >&2; exit 2; }
version="$(sed -n 's/^version=//p' "$FLOOR")"
count="$(sed -n 's/^count=//p' "$FLOOR")"
[[ "$version" =~ ^[0-9]{14}$ && "$count" =~ ^[0-9]+$ ]] || { echo "[migration-floor] Invalid floor record" >&2; exit 2; }
inventory="$(find "$MIGRATIONS" -maxdepth 1 -type f -name 'V*.sql' -exec basename {} \; | sed -nE 's/^V([0-9]{14})__.*\.sql$/\1/p' | sort)"
actual_count="$(printf '%s\n' "$inventory" | sed '/^$/d' | wc -l | tr -d ' ')"
highest="$(printf '%s\n' "$inventory" | tail -1)"
if [[ "$highest" != "$version" || "$actual_count" != "$count" ]]; then
  echo "[migration-floor] Stale record: declared=$version/$count actual=$highest/$actual_count; update the reviewed floor with the migration change" >&2
  exit 1
fi
echo "[migration-floor] OK version=$version count=$count" >&2
