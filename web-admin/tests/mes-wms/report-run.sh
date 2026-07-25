#!/usr/bin/env bash
#
# report-run.sh — regenerate the MES/WMS acceptance HTML report (evidence index).
#
# Re-runs the 4 goldens against a live host-first stack (default slot 63), captures
# their exit signals + screenshots into a run-dir, then renders the self-contained
# HTML via the e2e-trust report generator. Honest by construction: rows with empty
# evidence render ⬜ untested (pinned, counted in the denominator); a pass row whose
# evidence glob does not resolve is forced 🔴 缺证据.
#
# Usage:  BASE=http://127.0.0.1:5163 PG_DB=auraboot_63 BACKEND_URL=http://127.0.0.1:6463 ./report-run.sh
# Needs:  a live MES/WMS stack (see scripts/mes-wms-golden-run.sh) + the e2e-trust skill.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="${BASE:-http://127.0.0.1:5163}"
PG_DB="${PG_DB:-auraboot_63}"
BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:6463}"
export PG_HOST="${PG_HOST:-127.0.0.1}" PG_PORT="${PG_PORT:-5432}" PG_USER="${PG_USER:-auraboot}" PGPASSWORD="${PGPASSWORD:-auraboot}"
export ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}" ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
export PG_DB BACKEND_URL BASE

# report generator ships with the e2e-trust skill (machine-local, not vendored here)
GEN="${REPORT_GEN:-$HOME/.claude/skills/e2e-trust/scripts/report-gen.mjs}"
[ -f "$GEN" ] || { echo "report-gen.mjs not found at $GEN — set REPORT_GEN=<path>"; exit 2; }

RUN="$(mktemp -d)"
trap 'rm -rf "$RUN"' EXIT

run_golden() { # <name> <dir> <script>
  ( cd "$2" && node "$3" > "$RUN/$1.log" 2>&1 ) && echo 0 > "$RUN/$1.exit" || echo $? > "$RUN/$1.exit"
  echo "  $1: exit=$(cat "$RUN/$1.exit")  $(tail -1 "$RUN/$1.log")"
}

echo "[report-run] running goldens against $BASE / DB $PG_DB"
run_golden mes-wms-backend-golden "$HERE"     mes-wms-backend-golden.mjs
run_golden mes-wms-yellow-fr-golden "$HERE/ui" mes-wms-yellow-fr-golden.mjs
run_golden fr07-action-golden      "$HERE/ui" fr07-action-golden.mjs
run_golden mes-wms-ui-golden       "$HERE/ui" mes-wms-ui-golden.mjs
cp -f "$HERE/ui/"*.png "$RUN/" 2>/dev/null || true
cp -f "$HERE/backend-golden.log" "$RUN/backend-golden.log" 2>/dev/null || cp -f "$RUN/mes-wms-backend-golden.log" "$RUN/backend-golden.log"

echo "[report-run] rendering HTML"
node "$GEN" --manifest "$HERE/report-manifest.json" --run-dir "$RUN" --out "$HERE/mes-wms-acceptance-report.html"
echo "[report-run] → $HERE/mes-wms-acceptance-report.html"
