#!/usr/bin/env bash
# Local replacement for the required GitHub status checks.
# Run from any directory before merging a branch into main.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
REPORT_ROOT="${LOCAL_GATE_REPORT_DIR:-$REPO_ROOT/.workspace/reports/local-pr-gate}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
REPORT_DIR="$REPORT_ROOT/$RUN_ID"
SUMMARY="$REPORT_DIR/summary.txt"
mkdir -p "$REPORT_DIR"

FAILED=0

run_gate() {
  local name="$1"
  shift
  local log="$REPORT_DIR/${name}.log"
  printf '[RUN] %s\n' "$name" | tee -a "$SUMMARY"
  set +e
  (cd "$REPO_ROOT" && "$@") >"$log" 2>&1
  local rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    printf '[PASS] %s\n' "$name" | tee -a "$SUMMARY"
  else
    printf '[FAIL] %s (exit=%s, log=%s)\n' "$name" "$rc" "$log" | tee -a "$SUMMARY"
    FAILED=1
  fi
}

run_gitleaks() {
  if command -v gitleaks >/dev/null 2>&1; then
    gitleaks detect --source . --no-banner --redact --config .gitleaks.toml
    return
  fi
  echo "gitleaks is required: brew install gitleaks" >&2
  return 127
}

set -e
printf 'commit=%s\nstarted_at=%s\n' \
  "$(git -C "$REPO_ROOT" rev-parse HEAD)" "$(date -u +%FT%TZ)" >"$SUMMARY"

run_gate oss-boundary bash scripts/check-oss-boundary.sh
run_gate secret-scan run_gitleaks
run_gate docs bash scripts/check-docs.sh --strict
run_gate permission-codes node scripts/validate-permission-codes.mjs --oss-only

printf 'finished_at=%s\nresult=%s\n' "$(date -u +%FT%TZ)" \
  "$([[ $FAILED -eq 0 ]] && echo PASS || echo FAIL)" | tee -a "$SUMMARY"
echo "Local gate report: $SUMMARY"
exit "$FAILED"
