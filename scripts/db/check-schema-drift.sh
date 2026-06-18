#!/usr/bin/env bash
# Regenerate the schema snapshot from Flyway and diff it against the committed
# snapshot. Non-empty diff = drift (exit 1) — regenerate + commit to fix.
# See end-state spec §3.4 / P5.
#
# Usage:
#   scripts/db/check-schema-drift.sh --edition oss
#   scripts/db/check-schema-drift.sh --edition enterprise --enterprise-root <enterprise-repo-root>
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/db/flyway-common.sh
source "$SCRIPT_DIR/flyway-common.sh"

EDITION="oss"; ENTERPRISE_ROOT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition) EDITION="${2:?}"; shift 2 ;;
    --enterprise-root) ENTERPRISE_ROOT="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[drift] unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ "$EDITION" == "enterprise" ]]; then
  COMMITTED="$ENTERPRISE_ROOT/platform/src/main/resources/db/snapshots/schema-enterprise-current.sql"
else
  COMMITTED="$AURA_CORE_ROOT/platform/src/main/resources/db/snapshots/schema-current.sql"
fi
if [[ ! -f "$COMMITTED" ]]; then
  echo "[drift] committed snapshot missing: $COMMITTED — run generate-schema-snapshot.sh and commit." >&2
  exit 2
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
"$SCRIPT_DIR/generate-schema-snapshot.sh" --edition "$EDITION" \
  ${ENTERPRISE_ROOT:+--enterprise-root "$ENTERPRISE_ROOT"} --out "$TMP" >&2

if diff -u "$COMMITTED" "$TMP"; then
  echo "[drift] OK — committed snapshot matches the Flyway result." >&2
else
  echo "[drift] DRIFT: committed snapshot differs from the regenerated one." >&2
  echo "[drift] Fix: scripts/db/generate-schema-snapshot.sh --edition $EDITION ... && commit the snapshot." >&2
  exit 1
fi
