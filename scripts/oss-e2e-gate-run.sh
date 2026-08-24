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
#   3. runs the FULL showcase seed sequence + the workflow-demo seed, so the
#      ~28 seed-data-dependent specs are green rather than red-for-want-of-data;
#   4. runs a meaningful, currently-green OSS regression slice under the exact
#      env contract a real OSS run needs (PW_PROFILE=oss --project=oss);
#   5. prints a PASS/FAIL banner and EXITS WITH THE GATE RESULT — 0 = green,
#      nonzero = a real failure. The exit code is the whole point: it is what a
#      crontab line or a release step checks;
#   6. tears the stack down on the way out, on success OR failure OR interrupt,
#      via a trap — a failed gate never leaks a stack.
#
# The stack is destroyed-then-recreated each run (--fresh-db), so the gate never
# inherits a stale-slot DB that would skip bootstrap and quietly run against the
# wrong schema.
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
#     --name NAME  runtime name        (default: oss-e2e-gate)
#     --scope V    which specs the gate runs (default: slice):
#                    slice  the curated, currently-green regression areas
#                           (designer + saved-view + showcase + page-designer +
#                           automation). Bounded and meaningful — the right
#                           default for a gate.
#                    full   the whole OSS `oss` project (long; has its own known
#                           enterprise/deep exclusions). Use for a release sweep.
#                    <dir>  one or more explicit tests/e2e/<dir>/ paths — repeat
#                           --scope, or list them after --scope, to override.
#     --keep       leave the stack up after the run (to debug a failure). By
#                  default the stack is ALWAYS torn down, even on failure.
#     --repeat K   run the slice K times (flakiness check; default: 1)
#     --workers N  Playwright worker count (default: 1). The host-first gate uses
#                  Vite development mode; concurrent routes can discover new
#                  optimized dependencies and trigger a global dev-page reload
#                  while another worker is editing a form. Higher concurrency is
#                  available only as an explicit diagnostic override.
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
DEV="$WORKSPACE/dev.sh"

NAME="oss-e2e-gate"
SLOT=""            # empty => auto-pick
SCOPE_MODE="slice"
SCOPE_DIRS=()      # explicit override paths
KEEP=0
REPEAT=1
WORKERS="1"        # deterministic Vite dev runtime; see --workers contract above

# The curated, currently-green regression areas. These are the areas the recent
# OSS E2E survey work hardened; `slice` runs them minus their *-deep specs (which
# the `oss` project routes to `oss-deep`, kept out of the gate to stay bounded).
SLICE_DIRS=(
  # Curated, deterministically-green specs the recent OSS E2E survey work verified
  # (each confirmed green across multiple runs this session). The gate stays SMALL and
  # reliable on purpose — a born-red gate protects nothing. Broaden with
  # `--scope <dir>...` (see --help); broad areas carry known-flaky / vertical-excluded
  # specs and are NOT a clean gate.
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

# True if a dashboard `code` is imported into ab_dashboard (any tenant). Uses the
# PG* coordinates the caller must already have eval'd from `oss-golden-stack env`.
dashboard_exists() {
  local code="$1" got
  got="$(psql -tAc "select exists(select 1 from ab_dashboard where code = '${code}')" 2>/dev/null | tr -d '[:space:]')"
  [[ "$got" == "t" ]]
}

# Resolve SHOWCASE_DEFAULT_DASHBOARD_CODE the way scripts/oss-reset-and-init.sh's
# select_default_showcase_dashboard does: honour an explicit override (verified to
# exist), otherwise require the official CRM dashboard. This fails loudly when
# the product profile drifts away from the canonical public CRM package.
resolve_default_dashboard() {
  if [[ -n "${SHOWCASE_DEFAULT_DASHBOARD_CODE:-}" ]]; then
    dashboard_exists "$SHOWCASE_DEFAULT_DASHBOARD_CODE" \
      || die_env "SHOWCASE_DEFAULT_DASHBOARD_CODE=$SHOWCASE_DEFAULT_DASHBOARD_CODE is not imported into ab_dashboard"
    return
  fi
  dashboard_exists crm_dashboard \
    || die_env "official CRM dashboard is not imported (expected crm_dashboard)"
  export SHOWCASE_DEFAULT_DASHBOARD_CODE=crm_dashboard
}

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
[[ -f "$DEV" ]] || die "workspace dev.sh not found above $REPO_ROOT"

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
if [[ -z "$SLOT" ]]; then
  if [[ -n "$registered_slot" ]]; then
    SLOT="$registered_slot"
    log "reusing prior slot $SLOT for runtime name '$NAME' before the fresh rebuild"
  else
    for cand in 73 74 75 76 77 80 81 82 83 84 85 86 87 90 91 92 93 94 95 96 97; do
      if ! slot_in_use "$cand"; then SLOT="$cand"; break; fi
    done
  fi
  [[ -n "$SLOT" ]] || die "could not auto-pick a free slot in 73..97 — pass --slot N explicitly"
  [[ -n "$registered_slot" ]] || log "auto-picked free slot $SLOT"
elif [[ -n "$registered_slot" && "$registered_slot" != "$SLOT" ]]; then
  die "runtime '$NAME' is registered on slot $registered_slot, not requested slot $SLOT"
elif [[ -n "$registered_slot" ]]; then
  log "reusing requested slot $SLOT owned by runtime name '$NAME' before the fresh rebuild"
elif slot_in_use "$SLOT"; then
  die "slot $SLOT is already in use (a runtime claims it, or a port is bound) — pick another with --slot"
fi

# --- teardown trap: destroy on EXIT (success | failure | interrupt) ----------
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
log "1/5 fresh stack: destroy any prior '$NAME' + up --fresh-db --plugin-profile demo"
"$GS" destroy "$NAME" >/dev/null 2>&1 || true
# --fresh-db => destroy-then-recreate the slot DB, guaranteeing a fresh bootstrap.
# Default `up` runs the warm step (setup -> auth storageState -> pre-warm), which
# is what produces tests/storage/admin.json — both the seed and the `oss` project
# need that storageState, so warm is required here (do NOT pass --no-warm).
"$GS" up "$NAME" --slot "$SLOT" --ttl 3h --runtime-mode verification --fresh-db --plugin-profile demo \
  || die_env "stack bring-up failed — see the golden-stack logs under $WORKSPACE/.workspace/golden/$NAME/"

# The demo profile does not carry the internal test-fixtures plugin, and ~60 OSS
# specs (incl. saved-view / automation) reference e2et_* models. Import it
# explicitly so those specs test the product, not a missing fixture. (Under
# --no-deps the Playwright setup project does not run, so we cannot lean on its
# PW_PROFILE=oss auto-import — we do it here, deterministically.)
log "1b/5 import internal test-fixtures plugin (e2et_* models)"
"$GS" import "$NAME" --plugin-profile none --plugin test-fixtures \
  || die_env "test-fixtures import failed — see $WORKSPACE/.workspace/golden/$NAME/import.log"

# --- 2. resolve the stack env (base URL + backend + PG*) ---------------------
log "2/5 resolve stack env"
eval "$("$GS" env "$NAME")" || die_env "could not resolve stack env for '$NAME'"
mkdir -p "$AURA_EVIDENCE_ROOT/logs" "$AURA_EVIDENCE_ROOT/seed"
LOG="$AURA_EVIDENCE_ROOT/logs/oss-e2e-gate-$(date +%Y%m%d-%H%M%S).log"
log "    base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL bff=$BFF_PORT (AGENT_LLM_STUB_MODE=$AGENT_LLM_STUB_MODE)"

# Resolve the demo default dashboard BEFORE seeding (the finalization phase reads
# SHOWCASE_DEFAULT_DASHBOARD_CODE). The OSS demo profile imports the official
# public CRM and therefore resolves to crm_dashboard.
resolve_default_dashboard
export SHOWCASE_DEFAULT_DASHBOARD_CODE
log "    default dashboard target: $SHOWCASE_DEFAULT_DASHBOARD_CODE"

# --- 3. full showcase seed + workflow-demo seed ------------------------------
# The gate's default scope includes seed-data-dependent specs; a minimal
# bootstrap alone leaves ~28 of them red. Seed loudly-or-die.
log "3/5 seed: full showcase sequence + workflow-demo (loud on failure)"
SEED_LOG_DIR="$AURA_EVIDENCE_ROOT/seed/oss-e2e-gate"
mkdir -p "$SEED_LOG_DIR"
(
  cd "$REPO_ROOT/web-admin" || exit 90
  set -o pipefail
  node scripts/run-showcase-seed-sequence.mjs --config=playwright.seed.config.ts \
       --output-prefix="$SEED_LOG_DIR/showcase" \
       data extended workflow ai arsenal supplement 2>&1 | tee "$SEED_LOG_DIR/showcase-seed.log" || exit 91
  # workflow-demo carries its own leave balances/requests/approval tasks; without a
  # balance row wd_leave_validation rejects every request the demo can submit.
  node scripts/seed-workflow-demo.mjs --base-url="$PLAYWRIGHT_BASE_URL" 2>&1 | tee "$SEED_LOG_DIR/workflow-demo-seed.log" || exit 92
  # finalization: default dashboard + invariant assertions over what was seeded.
  node scripts/run-showcase-seed-sequence.mjs --config=playwright.seed.config.ts \
       --output-prefix="$SEED_LOG_DIR/showcase" \
       dashboard-default invariants 2>&1 | tee "$SEED_LOG_DIR/showcase-finalize.log" || exit 93
)
SEED_RC=$?
[[ "$SEED_RC" == 0 ]] || die_env "showcase/workflow-demo seed failed (rc=$SEED_RC) — see $SEED_LOG_DIR/*.log"
log "    seed OK"

# --- 4. run the gate slice under the OSS env contract ------------------------
log "4/5 run gate: PW_PROFILE=oss --project=oss --no-deps (x$REPEAT)"
cd "$REPO_ROOT/web-admin" || die_env "web-admin not found under $REPO_ROOT"
PW_ARGS=(--project=oss --no-deps --repeat-each="$REPEAT" --reporter=line)
[[ -n "$WORKERS" ]] && PW_ARGS+=(--workers="$WORKERS")
[[ ${#RUN_PATHS[@]} -gt 0 ]] && PW_ARGS+=("${RUN_PATHS[@]}")
set +e
PW_PROFILE=oss NO_PROXY=localhost,127.0.0.1 \
  pnpm exec playwright test "${PW_ARGS[@]}" 2>&1 | tee "$LOG"
GATE_RC=${PIPESTATUS[0]}
set -e 2>/dev/null || true

# --- 5. report + exit = gate result ------------------------------------------
log "5/5 result"
# Informational counts parsed from the reporter line. The AUTHORITATIVE signal is
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
