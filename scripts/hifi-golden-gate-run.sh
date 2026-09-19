#!/usr/bin/env bash
#
# hifi-golden-gate-run.sh — self-contained, one-command high-fidelity golden gate
# for the analytics designers (B118 slice).
#
# Same contract as scripts/oss-e2e-gate-run.sh (slot-isolated host-first stack,
# destroy-on-exit trap, exit code = gate result), but runs the two B118
# high-fidelity golden spec files under --project=chromium:
#
#   tests/e2e/designer/report-hifi-golden.spec.ts    HIFI-00/01/02
#   tests/e2e/dashboard/dashboard-hifi-golden.spec.ts DHIFI-01/02/03
#
# These specs assert business fidelity, not just contracts: a 48-order CJK seed,
# a multi-block report (header/stat cards/detail table), real aggregate-bound
# dashboard widgets, publish badges, presentation-mode chart canvas (the
# echarts-for-react/echarts-6 blank-paint regression guard), single-line toolbar
# badges, and the three-scope settings dialog.
#
# Exit codes: 0 = green, Playwright's rc = test-failure, 2 = environment-invalid
# (stack did not come up / plugin import failed). Teardown happens on success,
# failure, and interrupt alike.
#
# Prerequisites: workspace native brokers (Postgres/Redis) up. Run from any OSS
# auraboot checkout/worktree.
#
# Usage:
#   scripts/hifi-golden-gate-run.sh [--slot N] [--name NAME] [--keep] [--repeat K]
#     --slot N     isolated-stack slot. Default: auto-pick a free one (73..97).
#     --name NAME  runtime name        (default: hifi-golden-gate)
#     --keep       leave the stack up after the run (debug only).
#     --repeat K   run the specs K times (flakiness check; default: 1)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"
NAME="hifi-golden-gate"
SLOT=""
KEEP=0
REPEAT=1

C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_OFF=$'\033[0m'
log() { printf '[hifi-golden-gate] %s\n' "$*"; }
die()  { printf '%s[hifi-golden-gate] FATAL: %s%s\n' "$C_ERR" "$*" "$C_OFF" >&2; exit 2; }
die_env() { printf '%s[hifi-golden-gate] ENVIRONMENT-INVALID: %s%s\n' "$C_ERR" "$*" "$C_OFF" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot)   [[ $# -ge 2 ]] || die "--slot requires a value";  SLOT="$2"; shift 2;;
    --name)   [[ $# -ge 2 ]] || die "--name requires a value";  NAME="$2"; shift 2;;
    --keep)   KEEP=1; shift;;
    --repeat) [[ $# -ge 2 ]] || die "--repeat requires a value"; REPEAT="$2"; shift 2;;
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0;;
    *) die "unknown arg: $1 (see --help)";;
  esac
done

[[ -x "$GS" ]] || die "oss-golden-stack.sh not found/executable at $GS"

# Locate the workspace dev.sh for the slot auto-pick's `runtime list` read
# (same walk as the oss-e2e gate).
WORKSPACE=""
main_wt="$(git -C "$REPO_ROOT" worktree list --porcelain 2>/dev/null | sed -n 's/^worktree //p' | head -1)"
if [ -f "$(dirname "$REPO_ROOT")/dev.sh" ]; then
  WORKSPACE="$(dirname "$REPO_ROOT")"
fi
while [ -n "$WORKSPACE" ] && [ "$WORKSPACE" != "/" ] && [ ! -f "$WORKSPACE/dev.sh" ]; do
  WORKSPACE="$(dirname "$WORKSPACE")"
done
if [ -z "$WORKSPACE" ] || [ ! -f "$WORKSPACE/dev.sh" ]; then
  WORKSPACE=""
  [ -n "${main_wt:-}" ] && [ -f "$(dirname "$main_wt")/dev.sh" ] && WORKSPACE="$(dirname "$main_wt")"
fi

slot_in_use() {
  local s="$1"
  if [ -n "$WORKSPACE" ]; then
    "$WORKSPACE/dev.sh" runtime list 2>/dev/null | awk 'NR>1{print $3}' | grep -qx "$s" && return 0
  fi
  local be=$((6400 + s)) web=$((5100 + s)) bff=$((6100 + s))
  lsof -nP -iTCP:"$be"  -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
  lsof -nP -iTCP:"$web"  -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
  lsof -nP -iTCP:"$bff"  -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
  return 1
}

if [[ -z "$SLOT" ]]; then
  for cand in 73 74 75 76 77 80 81 82 83 84 85 86 87 90 91 92 93 94 95 96 97; do
    if ! slot_in_use "$cand"; then SLOT="$cand"; break; fi
  done
  [[ -n "$SLOT" ]] || die "could not auto-pick a free slot in 73..97 — pass --slot N explicitly"
  log "auto-picked free slot $SLOT"
elif slot_in_use "$SLOT"; then
  die "slot $SLOT is already in use — pick another with --slot"
fi

cleanup() {
  local rc=$?
  if [[ "$KEEP" == 1 ]]; then
    log "--keep set; leaving stack '$NAME' up (env: $GS env $NAME; destroy: $GS destroy $NAME)"
  else
    log "tearing down stack '$NAME' (trap on exit rc=$rc)..."
    "$GS" destroy "$NAME" >/dev/null 2>&1 || true
  fi
  return "$rc"
}
trap cleanup EXIT INT TERM

echo "=============================================================="
log "HIFI golden gate — name=$NAME slot=$SLOT repeat=$REPEAT"
echo "=============================================================="

export AGENT_LLM_STUB_MODE=true
log "1/5 fresh stack: destroy any prior '$NAME' + up --fresh-db --plugin-profile demo"
"$GS" destroy "$NAME" >/dev/null 2>&1 || true
"$GS" up "$NAME" --slot "$SLOT" --ttl 3h --runtime-mode verification --fresh-db --plugin-profile demo \
  || die_env "stack bring-up failed — see the golden-stack logs under ${WORKSPACE:-$REPO_ROOT}/.workspace/golden/$NAME/"

# The demo profile does not carry the internal test-fixtures plugin; the hifi
# specs seed orders through the e2et_order model it provides. Import it before
# anything that references e2et_* or the run fails on phantom missing models.
log "2/5 import internal test-fixtures plugin (e2et_* models)"
"$GS" import "$NAME" --plugin-profile none --plugin test-fixtures \
  || die_env "test-fixtures import failed — see ${WORKSPACE:-$REPO_ROOT}/.workspace/golden/$NAME/import.log"

log "3/5 resolve stack env"
eval "$("$GS" env "$NAME")" || die_env "could not resolve stack env for '$NAME'"
export AURA_EVIDENCE_DIR="${AURA_EVIDENCE_ROOT:-/tmp}/hifi-golden"
mkdir -p "$AURA_EVIDENCE_DIR"
LOG="$AURA_EVIDENCE_DIR/hifi-golden-gate-$(date +%Y%m%d-%H%M%S).log"
log "    base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL bff=$BFF_PORT evidence=$AURA_EVIDENCE_DIR"

log "4/5 run gate: --project=chromium --workers=1 --retries=0 (x$REPEAT)"
cd "$REPO_ROOT/web-admin" || die_env "web-admin not found under $REPO_ROOT"
set +e
NO_PROXY=localhost,127.0.0.1 \
  pnpm exec playwright test \
    tests/e2e/designer/report-hifi-golden.spec.ts \
    tests/e2e/dashboard/dashboard-hifi-golden.spec.ts \
    --project=chromium --workers=1 --retries=0 \
    --repeat-each="$REPEAT" --reporter=line 2>&1 | tee "$LOG"
GATE_RC=${PIPESTATUS[0]}
set -e 2>/dev/null || true

log "5/5 result"
SUMMARY="$(grep -aoE '[0-9]+ (passed|failed|flaky|skipped|did not run)' "$LOG" 2>/dev/null | tail -6 | tr '\n' ' ')"
echo "=============================================================="
if [[ "$GATE_RC" == 0 ]]; then
  printf '%s[hifi-golden-gate] HIFI GOLDEN GATE: PASS%s  (name=%s slot=%s)\n' "$C_OK" "$C_OFF" "$NAME" "$SLOT"
  [[ -n "$SUMMARY" ]] && log "  $SUMMARY"
  log "  evidence: $AURA_EVIDENCE_DIR"
else
  printf '%s[hifi-golden-gate] HIFI GOLDEN GATE: FAIL (test-failure, rc=%s)%s\n' "$C_ERR" "$GATE_RC" "$C_OFF"
  [[ -n "$SUMMARY" ]] && log "  $SUMMARY"
  log "  full log:    $LOG"
  log "  (re-run with --keep to inspect the live stack)"
fi
echo "=============================================================="
exit "$GATE_RC"
