#!/usr/bin/env bash
# Run Flyway validate for AuraBoot (checks applied migrations against the
# configured locations: checksums, ordering, naming).
#
# Usage:
#   PG_DB=<target_db> scripts/db/flyway-validate.sh --edition oss
#   PG_DB=<target_db> scripts/db/flyway-validate.sh --edition enterprise --enterprise-root <enterprise-repo-root>
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/db/flyway-common.sh
source "$SCRIPT_DIR/flyway-common.sh"

EDITION="oss"
ENTERPRISE_ROOT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition) EDITION="${2:?--edition needs a value}"; shift 2 ;;
    --enterprise-root) ENTERPRISE_ROOT="${2:?--enterprise-root needs a value}"; shift 2 ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[flyway-validate] unknown arg: $1" >&2; exit 2 ;;
  esac
done

run_flyway validate "$EDITION" "$ENTERPRISE_ROOT"
