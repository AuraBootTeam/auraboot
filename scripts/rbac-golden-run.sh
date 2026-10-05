#!/usr/bin/env bash
#
# rbac-golden-run.sh — self-contained RBAC platform-baseline browser golden runner.
#
# Runs the platform-baseline browser suite using an isolated host-first stack.
# The named runtime, database, caches and evidence are retained on success/failure.
# Capacity exhaustion is a failure; this runner never closes other runtime owners.
# Reuses the stable name/slot. Schema drift must be resolved explicitly by the owner.
# Backend RBAC integration tests run separately in the backend suite.
#
# Prerequisites: native workspace brokers and the managed workspace controller.
# Usage:
#   scripts/rbac-golden-run.sh [--slot N] [--name NAME] [--repeat K]
#                            [--runtime-mode MODE] [--keep]
#     --slot N       stable isolated slot (default: 71)
#     --name NAME    stable runtime name (default: rbac-golden-nightly)
#     --repeat K     positive browser repetition count (default: 1)
#     --runtime-mode development|verification|control|performance
#                    defaults to development; does not override capacity
#     --keep         compatibility option; retention is always enabled
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"

NAME="rbac-golden-nightly"
SLOT="71"
KEEP=0
REPEAT=1
RUNTIME_MODE="development"

die() { echo "[rbac-golden-run] ERROR: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot)   [[ $# -ge 2 ]] || die "--slot requires a value"; SLOT="$2"; shift 2;;
    --name)   [[ $# -ge 2 ]] || die "--name requires a value"; NAME="$2"; shift 2;;
    --repeat) [[ $# -ge 2 ]] || die "--repeat requires a value"; REPEAT="$2"; shift 2;;
    --keep)   KEEP=1; shift;;
    --runtime-mode) [[ $# -ge 2 ]] || die "--runtime-mode requires a value"; RUNTIME_MODE="$2"; shift 2;;
    -h|--help) sed -n '2,/^set -uo pipefail/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "unknown arg: $1";;
  esac
done

[[ -x "$GS" ]] || die "oss-golden-stack.sh not found/executable at $GS"

[[ "$SLOT" =~ ^[0-9]+$ ]] || die "--slot must be a nonnegative integer"
[[ "$REPEAT" =~ ^[1-9][0-9]*$ ]] || die "--repeat must be a positive integer"
[[ "$NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || die "--name must be a runtime identifier"
case "$RUNTIME_MODE" in
  development|verification|control|performance) ;;
  *) die "invalid --runtime-mode";;
esac

retain() {
  local rc=$?
  echo "[rbac-golden-run] retained stack '$NAME' and evidence (exit=$rc; env: $GS env $NAME)"
  return "$rc"
}
trap retain EXIT

run_phase() {
  local rc
  "$@"
  rc=$?
  [[ "$rc" == 0 ]] || exit "$rc"
}

echo "[rbac-golden-run] === RBAC platform-baseline golden — name=$NAME slot=$SLOT mode=$RUNTIME_MODE repeat=$REPEAT ==="
echo "[rbac-golden-run] 1/4 ensure stable stack + import (no reset or automatic retry)"
run_phase "$GS" up "$NAME" --slot "$SLOT" --ttl 2h --no-warm --runtime-mode "$RUNTIME_MODE"
run_phase "$GS" import "$NAME"

# 2. Export the Playwright env (PW_SKIP_WEBSERVER + base URL + backend + PG*).
echo "[rbac-golden-run] 2/4 resolve stack env"
env_exports="$("$GS" env "$NAME")"
env_rc=$?
[[ "$env_rc" == 0 ]] || exit "$env_rc"
eval "$env_exports" || die "could not resolve stack env"
[[ -n "${PLAYWRIGHT_BASE_URL:-}" && -n "${BACKEND_URL:-}" ]] || die "stack env lacks browser/backend URLs"
echo "[rbac-golden-run]     base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL"

# 3. Run the golden.
echo "[rbac-golden-run] 3/4 run per-role browser golden (x$REPEAT)"
[[ -n "${PW_REPORT_DIR:-}" && -n "${PW_RESULTS_JSON:-}" ]] || die "stack env lacks registered report paths"
# Each run retains independent evidence while reusing the stable runtime.
PW_REPORT_DIR="$PW_REPORT_DIR/runs/$(date -u +%Y%m%dT%H%M%SZ)-$$"
PW_RESULTS_JSON="$PW_REPORT_DIR/results.json"
mkdir -p "$PW_REPORT_DIR" || die "cannot create registered report directory"
[ ! -e "$PW_RESULTS_JSON" ] || die "result JSON already exists; refusing stale evidence"
export PLAYWRIGHT_JSON_OUTPUT_FILE="$PW_RESULTS_JSON"
cd "$REPO_ROOT/web-admin" || die "web-admin not found"
set +e
NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/rbac/ \
  --project=chromium --no-deps --repeat-each="$REPEAT" --reporter=line,json
GOLDEN_RC=$?
set -e 2>/dev/null || true

# Reject empty, skipped, retried or missing role execution before publishing PASS.
if [[ "$GOLDEN_RC" == 0 ]]; then
  node "$SCRIPT_DIR/rbac-golden-result.mjs" "$PW_RESULTS_JSON" "$REPEAT" "$NAME" "$REPO_ROOT" \
    || GOLDEN_RC=$?
fi

# 4. Report.
echo "[rbac-golden-run] 4/4 result"
if [[ "$GOLDEN_RC" == 0 ]]; then
  echo "[rbac-golden-run] ============================================"
  echo "[rbac-golden-run]   RBAC GOLDEN: PASS  (name=$NAME slot=$SLOT mode=$RUNTIME_MODE)"
  echo "[rbac-golden-run] ============================================"
else
  echo "[rbac-golden-run] ############################################"
  echo "[rbac-golden-run]   RBAC GOLDEN: FAIL (rc=$GOLDEN_RC)"
  echo "[rbac-golden-run]   artifacts: ${PW_ARTIFACT_DIR:-not registered}"
  echo "[rbac-golden-run]   stack retained for inspection"
  echo "[rbac-golden-run] ############################################"
fi

exit "$GOLDEN_RC"
