#!/usr/bin/env bash
#
# oss-e2e-gate-run.sh — self-contained, one-command OSS E2E regression gate.
#
# For teams that run their own gates at release time or via a nightly crontab
# (NOT GitHub Actions — the owner has no CI budget). One command, hands-off:
#
#   1. brings up a FRESH, slot-isolated host-first stack (zero docker, safe
#      alongside concurrent sessions, never oss-reset-and-init's global pkill);
#   2. imports the OSS demo plugins + the internal test-fixtures plugin;
#   3. runs the selected OSS project specs with zero retries;
#   4. exits with the runner result and retains database/artifacts/evidence;
#   5. stops only registered processes and closes its allocation on exit.
#
# Every verification uses a new name and an unused slot. Existing environments
# are rejected rather than reset. --keep leaves the owned processes running.
#
# ENV CONTRACT (baked in — an OSS survey documented each of these; getting any
# one wrong roughly doubles the apparent debt with false failures):
#   * the frontend/Vite gets BFF_INTERNAL_URL + SPRING_BOOT_URL pointed at THIS
#     slot's backend — oss-golden-stack.sh sets these on `up`;
#   * the backend runs with AGENT_LLM_STUB_MODE=true (deterministic, no key, no
#     spend) — oss-golden-stack.sh `up` sets it, and this runner exports it
#     before `up` so it is unambiguous;
#   * the Playwright run is PW_PROFILE=oss --project=oss (NOT --project=chromium:
#     under chromium the setup project skips the test-fixtures import and ~2x of
#     the failures are then phantom "Command not found: e2et:*" harness noise).
#
# The exit code distinguishes the two kinds of failure in its message:
#   * environment-invalid (stack did not come up / seed failed) -> exit 2
#   * test-failure        (the slice went red)                  -> exit = the
#                                                                  Playwright rc
#
# Prerequisites: the workspace native brokers (Postgres/Redis/Kafka) must be up —
# the same ones `dev.sh runtime` uses. Run from any OSS auraboot checkout/worktree.
#
# Usage:
#   scripts/oss-e2e-gate-run.sh [--slot N] [--name NAME] [--scope slice|full|<dir>...] [--keep] [--repeat K]
#     --slot N     isolated-stack slot. Default: auto-pick a free one. Pick one
#                  no other runtime uses (`../dev.sh runtime list`).
#     --name NAME  runtime name        (default: unique oss-e2e-gate timestamp)
#     --scope V    which specs the gate runs (default: slice):
#                    slice  the fixed regression selection
#                           (designer + saved-view + showcase + page-designer +
#                           automation). Bounded and meaningful — the right
#                           default for a gate.
#                    full   the whole OSS `oss` project (long; has its own known
#                           enterprise/deep exclusions). Use for a release sweep.
#                    <dir>  one or more explicit tests/e2e/<dir>/ paths — repeat
#                           --scope, or list them after --scope, to override.
#     --keep       leave the stack up after the run (to debug a failure). By
#                  default owned processes stop; database and evidence remain.
#     --repeat K   run the slice K times (flakiness check; default: 1)
#     --workers N  Playwright worker count (default: Playwright's own, PW_WORKERS
#                  or 4). Heavy-canvas areas (designer/page-designer) need a low
#                  count — they time out on visibility when 4 canvas specs
#                  contend for the browser. Use --workers 1 for those.
#     -h, --help   show this help
#
# Crontab example (nightly 02:00):
#   0 2 * * *  cd /path/to/auraboot && ./scripts/oss-e2e-gate-run.sh >> /var/log/oss-e2e-gate.log 2>&1
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"

# Locate the workspace dev.sh (for the slot-auto-pick's `runtime list` read).
# CI keeps repositories as siblings under /opt/aura-ci/repos, while developer
# worktrees usually find dev.sh by walking their ancestors.
WORKSPACE="${AURA_WORKSPACE_ROOT:-${AURA_CI_WORKSPACE_ROOT:-}}"
if [ -z "$WORKSPACE" ] && [ -f "$(dirname "$REPO_ROOT")/auraboot-workspace/dev.sh" ]; then
  WORKSPACE="$(dirname "$REPO_ROOT")/auraboot-workspace"
fi
if [ -z "$WORKSPACE" ]; then
  WORKSPACE="$REPO_ROOT"
fi
while [ "$WORKSPACE" != "/" ] && [ ! -f "$WORKSPACE/dev.sh" ]; do WORKSPACE="$(dirname "$WORKSPACE")"; done
if [ ! -f "$WORKSPACE/dev.sh" ]; then
  main_wt="$(git -C "$REPO_ROOT" worktree list --porcelain 2>/dev/null | awk '/^worktree /{print $2; exit}')"
  [ -n "${main_wt:-}" ] && [ -f "$(dirname "$main_wt")/dev.sh" ] && WORKSPACE="$(dirname "$main_wt")"
fi
DEV="$WORKSPACE/aura"
STATE_ROOT="${AURA_WORKSPACE_STATE_DIR:-$WORKSPACE/.workspace}"
export AURA_WORKSPACE_ROOT="$WORKSPACE" AURA_WORKSPACE_STATE_DIR="$STATE_ROOT"

NAME="oss-e2e-gate-$(date -u +%Y%m%dT%H%M%SZ)-$$"
SLOT=""            # empty => auto-pick
SCOPE_MODE="slice"
SCOPE_DIRS=()      # explicit override paths
KEEP=0
REPEAT=1
WORKERS=""         # empty => Playwright base default (PW_WORKERS||4)

# Fixed default selection; each run supplies its own execution evidence.
SLICE_DIRS=(
  tests/e2e/page-designer/form-buttons-refresh-runtime.spec.ts
  tests/e2e/showcase/runtime-rendering-e2e.spec.ts
  tests/e2e/saved-view/saved-view-gantt.spec.ts
  tests/e2e/saved-view/saved-view-kanban.spec.ts
)

C_INFO=$'\033[36m'; C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_OFF=$'\033[0m'
log()  { printf '%s[oss-e2e-gate]%s %s\n' "$C_INFO" "$C_OFF" "$*"; }
die()  { printf '%s[oss-e2e-gate] ERROR:%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 2; }
# environment-invalid: the stack could not be made ready. Exit 2 is the workspace
# orchestrator's canonical environment-invalid contract; Playwright/product failures use exit 1.
die_env() { printf '%s[oss-e2e-gate] ENVIRONMENT-INVALID:%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; ENV_INVALID=1; exit 2; }
ENV_INVALID=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot)   [[ $# -ge 2 ]] || die "--slot requires a value";  SLOT="$2"; shift 2;;
    --name)   [[ $# -ge 2 ]] || die "--name requires a value";  NAME="$2"; shift 2;;
    --repeat)  [[ $# -ge 2 ]] || die "--repeat requires a value";  REPEAT="$2";  shift 2;;
    --workers) [[ $# -ge 2 ]] || die "--workers requires a value"; WORKERS="$2"; shift 2;;
    --scope)
      [[ $# -ge 2 ]] || die "--scope requires a value (slice|full|<dir>)"
      case "$2" in
        slice|full) SCOPE_MODE="$2"; shift 2;;
        *)          SCOPE_MODE="dirs"; SCOPE_DIRS+=("$2"); shift 2;;
      esac
      ;;
    --keep)   KEEP=1; shift;;
    -h|--help) awk 'NR>=2 && /^#/{sub(/^# ?/,""); print; next} NR>=2{exit}' "${BASH_SOURCE[0]}"; exit 0;;
    --) shift; while [[ $# -gt 0 ]]; do SCOPE_MODE="dirs"; SCOPE_DIRS+=("$1"); shift; done;;
    tests/e2e/*) SCOPE_MODE="dirs"; SCOPE_DIRS+=("$1"); shift;;   # bare path after --scope <dir>
    *) die "unknown arg: $1";;
  esac
done

[[ -x "$GS" ]] || die "oss-golden-stack.sh not found/executable at $GS"
[[ -x "$DEV" ]] || die "workspace public aura CLI not found above $REPO_ROOT"
[[ "$NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || die "invalid runtime name"
[[ "$SLOT" =~ ^[0-9]*$ && "$REPEAT" =~ ^[1-9][0-9]*$ ]] || die "invalid slot or repeat count"
[[ -z "$WORKERS" || "$WORKERS" =~ ^[1-9][0-9]*$ ]] || die "invalid worker count"

# --- resolve the spec paths the gate will run --------------------------------
RUN_PATHS=()
case "$SCOPE_MODE" in
  slice) RUN_PATHS=("${SLICE_DIRS[@]}");;
  full)  RUN_PATHS=();;                       # no positional => whole `oss` project
  dirs)  RUN_PATHS=("${SCOPE_DIRS[@]}");;
esac

# --- pick a free slot if the caller did not name one -------------------------
# A free slot = not claimed by any dev.sh runtime AND whose computed host ports
# (backend 6400+slot / web 5100+slot / bff 6100+slot for the auraboot repo) have
# no listener. oss-golden-stack.sh `up` also verifies port ownership and dies on
# a foreign listener, so this is a courtesy pre-check, not the only guard.
slot_in_use() {
  local s="$1"
  "$DEV" runtime list 2>/dev/null | awk 'NR>1{print $3}' | grep -qx "$s" && return 0
  local be=$((6400 + s)) web=$((5100 + s)) bff=$((6100 + s))
  lsof -nP -iTCP:"$be"  -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
  lsof -nP -iTCP:"$web" -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
  lsof -nP -iTCP:"$bff" -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
  return 1
}
registered_slot_for_name() {
  "$DEV" runtime list 2>/dev/null | awk -v name="$NAME" 'NR > 1 && $1 == name { print $3; exit }'
}
registered_slot="$(registered_slot_for_name)"
[[ -z "$registered_slot" ]] || die "runtime '$NAME' already exists; choose a new verification name (existing data preserved)"
if [[ -z "$SLOT" ]]; then
  for cand in $(seq 73 249); do
    if ! slot_in_use "$cand"; then SLOT="$cand"; break; fi
  done
  [[ -n "$SLOT" ]] || die "could not auto-pick a free slot; pass --slot N"
elif slot_in_use "$SLOT"; then
  die "slot $SLOT is already in use; choose an unused slot"
fi

STACK_ATTEMPTED=0
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ "$STACK_ATTEMPTED" == 1 && "$KEEP" != 1 ]]; then
    log "stopping owned processes; retaining database and evidence (exit rc=$rc)"
    local allocation
    allocation="$("$DEV" runtime show "$NAME" --json | node -e 'let s="";process.stdin.on("data",x=>s+=x);process.stdin.on("end",()=>{const d=JSON.parse(s);if(d.allocation===null)console.log("absent");else if(d.allocation&&typeof d.allocation==="object")console.log("present");else process.exitCode=1})')" || allocation=unknown
    if [[ "$allocation" == absent ]]; then
      log "no allocation registered; startup state and evidence preserved"
    elif [[ "$allocation" != present ]]; then
      log "allocation status unavailable; refusing cleanup"
      [[ "$rc" != 0 ]] || rc=2
    elif "$GS" down "$NAME"; then
      "$DEV" runtime close "$NAME" || { [[ "$rc" != 0 ]] || rc=2; }
    else
      log "owned stop refused; runtime retained for diagnosis"
      [[ "$rc" != 0 ]] || rc=2
    fi
  elif [[ "$KEEP" == 1 ]]; then
    log "--keep: stack '$NAME' remains available; database and evidence retained"
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

LOG="/tmp/oss-e2e-gate-${NAME}-$(date +%Y%m%d-%H%M%S).log"
echo "=============================================================="
log "OSS E2E gate — name=$NAME slot=$SLOT scope=$SCOPE_MODE repeat=$REPEAT"
[[ ${#RUN_PATHS[@]} -gt 0 ]] && log "  paths: ${RUN_PATHS[*]}"
log "  log:   $LOG"
echo "=============================================================="

# --- 1. fresh isolated stack -------------------------------------------------
# Backend-side contract set BEFORE the stack starts: exported here so it is plain
# that the backend booted with it, not asserted after the fact.
export AGENT_LLM_STUB_MODE=true
log "1/4 new verification stack; require an empty isolated database"
STACK_ATTEMPTED=1
"$GS" up "$NAME" --slot "$SLOT" --ttl 3h --runtime-mode verification --require-empty-db --plugin-profile demo \
  || die_env "stack bring-up failed; inspect $STATE_ROOT/golden/$NAME/"

# The demo profile does not carry the internal test-fixtures plugin, and ~60 OSS
# specs (incl. saved-view / automation) reference e2et_* models. Import it
# explicitly so those specs test the product, not a missing fixture. (Under
# --no-deps the Playwright setup project does not run, so we cannot lean on its
# PW_PROFILE=oss auto-import — we do it here, deterministically.)
log "1b/4 import internal test-fixtures plugin (e2et_* models)"
"$GS" import "$NAME" --plugin-profile none --plugin test-fixtures \
  || die_env "test-fixtures import failed — see $STATE_ROOT/golden/$NAME/import.log"

"$GS" verify-artifacts "$NAME" || die_env "runtime identity or import receipts invalid"

# --- 2. resolve the stack env (base URL + backend + PG*) ---------------------
log "2/4 resolve stack env"
eval "$("$GS" env "$NAME")" || die_env "could not resolve stack env for '$NAME'"
mkdir -p "$AURA_EVIDENCE_ROOT/logs"
LOG="$AURA_EVIDENCE_ROOT/logs/oss-e2e-gate-$(date +%Y%m%d-%H%M%S).log"
log "    base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL bff=$BFF_PORT (AGENT_LLM_STUB_MODE=$AGENT_LLM_STUB_MODE)"

# Product seed and product journeys are intentionally absent. The independent
# aura-bpm and aura-crm release suites own those fixtures and denominators.

# --- 3. run the gate slice under the OSS env contract ------------------------
log "3/4 run gate: PW_PROFILE=oss --project=oss --no-deps (x$REPEAT)"
cd "$REPO_ROOT/web-admin" || die_env "web-admin not found under $REPO_ROOT"
mkdir -p "$AURA_EVIDENCE_ROOT/playwright/report"
export PLAYWRIGHT_JSON_OUTPUT_FILE="$AURA_EVIDENCE_ROOT/playwright/report/results.json"
PW_ARGS=(--project=oss --no-deps --repeat-each="$REPEAT" --retries=0 --reporter=line,json)
[[ -n "$WORKERS" ]] && PW_ARGS+=(--workers="$WORKERS")
[[ ${#RUN_PATHS[@]} -gt 0 ]] && PW_ARGS+=("${RUN_PATHS[@]}")
set +e
PW_PROFILE=oss NO_PROXY=localhost,127.0.0.1 \
  pnpm exec playwright test "${PW_ARGS[@]}" 2>&1 | tee "$LOG"
GATE_RC=${PIPESTATUS[0]}
if [[ "$GATE_RC" == 0 ]]; then
  node "$SCRIPT_DIR/dev/oss-gate-results.mjs" "$PLAYWRIGHT_JSON_OUTPUT_FILE" || GATE_RC=1
fi

# --- 4. report + exit = gate result ------------------------------------------
log "4/4 result"
# Informational counts parsed from the reporter line. The runner and structured execution audit determine the result. The runner signal is
# GATE_RC (the process exit code), never the parsed text — a tee pipeline's own
# exit code would lie, which is why GATE_RC comes from PIPESTATUS above.
SUMMARY="$(grep -aoE '[0-9]+ (passed|failed|flaky|skipped|did not run)' "$LOG" 2>/dev/null | tail -6 | tr '\n' ' ')"
echo "=============================================================="
if [[ "$GATE_RC" == 0 ]]; then
  printf '%s[oss-e2e-gate]   OSS E2E GATE: PASS%s  (name=%s slot=%s scope=%s)\n' "$C_OK" "$C_OFF" "$NAME" "$SLOT" "$SCOPE_MODE"
  [[ -n "$SUMMARY" ]] && log "  $SUMMARY"
else
  printf '%s[oss-e2e-gate]   OSS E2E GATE: FAIL (test-failure, rc=%s)%s\n' "$C_ERR" "$GATE_RC" "$C_OFF"
  [[ -n "$SUMMARY" ]] && log "  $SUMMARY"
  log "  full log:    $LOG"
  log "  artifacts:   $AURA_EVIDENCE_ROOT"
  log "  (re-run with --keep to inspect the live stack)"
fi
echo "=============================================================="
exit "$GATE_RC"
