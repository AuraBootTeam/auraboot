#!/usr/bin/env bash
# Fixed high-fidelity BI gate: verified owned stack, exact profile, no retries.
# Environments and evidence are retained for owner review on every exit path.
# Usage: scripts/hifi-golden-gate-run.sh [--slot N] [--name NAME] [--reuse] [--keep] [--repeat K]
# --keep is retained as a compatibility alias; retention is always the default.
# Exit: 0=exact profile passed, 1=test/report failure, 2=environment-invalid.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"
PROFILE="$REPO_ROOT/scripts/gates/hifi-golden-profile.json"
AUDIT="$REPO_ROOT/scripts/gates/hifi-golden-results.mjs"
NAME="hifi-golden-$(date -u +%Y%m%dT%H%M%SZ)-$$"
SLOT="${AURA_REGRESSION_SLOT:-}"
REPEAT=1
REUSE=false
NAME_SET=false
log() { printf '[hifi-golden-gate] %s\n' "$*"; }
die_env() { log "ENVIRONMENT-INVALID: $*" >&2; exit 2; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot) [[ $# -ge 2 ]] || die_env '--slot requires a value'; SLOT="$2"; shift 2;;
    --name) [[ $# -ge 2 ]] || die_env '--name requires a value'; NAME="$2"; NAME_SET=true; shift 2;;
    --reuse) REUSE=true; shift;;
    --keep) shift;;
    --repeat) [[ $# -ge 2 ]] || die_env '--repeat requires a value'; REPEAT="$2"; shift 2;;
    -h|--help) sed -n '2,6p' "${BASH_SOURCE[0]}"; exit 0;;
    *) die_env "unknown arg: $1";;
  esac
done
[[ "$NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || die_env 'invalid runtime name'
[[ "$REPEAT" =~ ^[1-9][0-9]*$ ]] || die_env 'repeat must be a positive integer'
[[ -z "$SLOT" || "$SLOT" =~ ^[0-9]+$ ]] || die_env 'slot must be an integer'
WORKSPACE="${AURA_WORKSPACE_ROOT:-${AURA_CI_WORKSPACE_ROOT:-}}"
if [[ -z "$WORKSPACE" && -x "$(dirname "$REPO_ROOT")/auraboot-workspace/aura" ]]; then
  WORKSPACE="$(dirname "$REPO_ROOT")/auraboot-workspace"
fi
WORKSPACE="${WORKSPACE:-$REPO_ROOT}"
while [[ "$WORKSPACE" != / && ! -x "$WORKSPACE/aura" ]]; do WORKSPACE="$(dirname "$WORKSPACE")"; done
[[ -x "$WORKSPACE/aura" ]] || die_env 'workspace aura CLI not found'
source "$REPO_ROOT/scripts/lib/workspace-control.sh"
aura_bind_workspace_control "$WORKSPACE" || exit 2
WORKSPACE_STATE="${AURA_WORKSPACE_STATE_DIR:-$WORKSPACE/.workspace}"
[[ -x "$GS" && -f "$PROFILE" && -f "$AUDIT" ]] || die_env 'missing gate dependency'
command -v pdftotext >/dev/null 2>&1 || die_env 'pdftotext (Poppler) is required to inspect exported PDF contents'
# Reuse is explicit and must pass both product and workspace identity checks.
if [[ "$REUSE" == true ]]; then
  [[ "$NAME_SET" == true ]] || die_env '--reuse requires an explicit --name'
  [[ -f "$WORKSPACE_STATE/env/$NAME.env" ]] || die_env 'reuse runtime is not registered'
  "$GS" verify-artifacts "$NAME" || die_env 'reuse product ownership verification failed'
  "$WORKSPACE/aura" runtime verify "$NAME" || die_env 'reuse runtime ownership verification failed'
  requested_slot="$SLOT"
  eval "$("$GS" env "$NAME")" || die_env 'reuse stack env unavailable'
  registered_slot="$(awk -F= '$1 == "AURA_WORKSPACE_SLOT" {print $2}' "$WORKSPACE_STATE/env/$NAME.env")"
  [[ "$registered_slot" =~ ^[0-9]+$ ]] || die_env 'reuse slot unavailable'
  [[ -z "$requested_slot" || "$requested_slot" == "$registered_slot" ]] || die_env 'reuse slot mismatch'
  SLOT="$registered_slot"
  [[ "$AURA_EVIDENCE_ROOT" == "$WORKSPACE_STATE/evidence/$NAME" || "$AURA_EVIDENCE_ROOT" == "$WORKSPACE_STATE/evidence/$NAME/"* ]] \
    || die_env 'reuse evidence is outside the registered runtime'
  REUSE_EVIDENCE_ROOT="$(mktemp -d "$AURA_EVIDENCE_ROOT/hifi-reuse-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")" \
    || die_env 'reuse evidence round unavailable'
else
  # A supplied name alone never grants ownership of an existing runtime or database.
  [[ ! -e "$WORKSPACE_STATE/env/$NAME.env" && ! -e "$WORKSPACE_STATE/golden/$NAME" ]] \
    || die_env "runtime name '$NAME' already exists; use --reuse for a verified owned runtime"
fi
RUNTIMES="$("$WORKSPACE/aura" runtime list)" || die_env 'runtime inventory unavailable'
slot_in_use() {
  local s="$1"
  printf '%s\n' "$RUNTIMES" | awk 'NR>1{print $3}' | grep -qx "$s" && return 0
  local port
  for port in "$((6400 + s))" "$((5100 + s))" "$((6100 + s))"; do
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
  done
  return 1
}
if [[ "$REUSE" == true ]]; then
  : # The verified runtime owns this slot and its live listeners.
elif [[ -z "$SLOT" ]]; then
  for candidate in {73..97}; do
    if ! slot_in_use "$candidate"; then SLOT="$candidate"; break; fi
  done
  [[ -n "$SLOT" ]] || die_env 'no free slot in 73..97; pass --slot N'
else
  slot_in_use "$SLOT" && die_env "slot $SLOT is already in use"
fi
export AURA_EVIDENCE_ROOT="${REUSE_EVIDENCE_ROOT:-$WORKSPACE_STATE/evidence/$NAME}"
export AURA_EVIDENCE_DIR="$AURA_EVIDENCE_ROOT/hifi-golden"
mkdir -p "$AURA_EVIDENCE_DIR"
trap 'log "runtime retained: name=$NAME slot=$SLOT evidence=$AURA_EVIDENCE_ROOT"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
SPEC_FILES=()
while IFS= read -r file; do SPEC_FILES+=("$file"); done < <(
  node -e 'const p=require(process.argv[1]);for(const f of new Set(p.tests.map(t=>t.file)))console.log(f)' "$PROFILE"
)
[[ ${#SPEC_FILES[@]} -gt 0 ]] || die_env 'empty profile'
cd "$REPO_ROOT/web-admin"
log '1/6 collect fixed profile (execution remains zero)'
pnpm exec playwright test "${SPEC_FILES[@]}" --project=chromium --no-deps \
  --repeat-each="$REPEAT" --list --reporter=json >"$AURA_EVIDENCE_ROOT/collection.json" \
  2>"$AURA_EVIDENCE_ROOT/collection.stderr.log" || exit 1
node "$AUDIT" "$PROFILE" "$AURA_EVIDENCE_ROOT/collection.json" collection "$REPEAT" \
  >"$AURA_EVIDENCE_ROOT/collection-ledger.json" || exit 1
export AGENT_LLM_STUB_MODE=true
if [[ "$REUSE" == true ]]; then
  log "2/6 reuse verified stack name=$NAME slot=$SLOT (no reset, allocation or destruction)"
else
  log "2/6 fresh stack name=$NAME slot=$SLOT (no existing runtime is destroyed)"
  # A runtime whose source-set pins a frozen Workspace dependency only re-ups with the
  # same binding; the checkout is validated fail-closed by the stack launcher.
  workspace_source_args=()
  [[ -z "${AURA_GOLDEN_WORKSPACE_SOURCE_ROOT:-}" ]] || workspace_source_args=(--workspace-source-root "$AURA_GOLDEN_WORKSPACE_SOURCE_ROOT")
  "$GS" up "$NAME" --slot "$SLOT" --ttl 12h --runtime-mode verification --require-new-db --plugin-profile demo \
    "${workspace_source_args[@]}" \
    || die_env 'stack bring-up failed; inspect retained golden-stack logs'
fi
log '3/6 import test-fixtures'
"$GS" import "$NAME" --plugin-profile none --plugin test-fixtures || die_env 'test-fixtures import failed'
log '4/6 verify runtime identity and resolve env'
"$WORKSPACE/aura" runtime verify "$NAME" >"$AURA_EVIDENCE_ROOT/runtime-verify.log" \
  || die_env 'runtime ownership verification failed'
PREFLIGHT_EVIDENCE_ROOT="$AURA_EVIDENCE_ROOT"
eval "$("$GS" env "$NAME")" || die_env 'stack env unavailable'
if [[ "$REUSE" == true ]]; then
  export AURA_EVIDENCE_ROOT="$PREFLIGHT_EVIDENCE_ROOT"
elif [[ "$PREFLIGHT_EVIDENCE_ROOT" != "$AURA_EVIDENCE_ROOT" ]]; then
  mkdir -p "$AURA_EVIDENCE_ROOT" || die_env 'native evidence round unavailable'
  for evidence_file in collection.json collection.stderr.log collection-ledger.json runtime-verify.log; do
    [[ ! -e "$AURA_EVIDENCE_ROOT/$evidence_file" && ! -L "$AURA_EVIDENCE_ROOT/$evidence_file" ]] \
      || die_env "native evidence round already contains $evidence_file; prior bytes retained"
  done
  for evidence_file in collection.json collection.stderr.log collection-ledger.json runtime-verify.log; do
    cp "$PREFLIGHT_EVIDENCE_ROOT/$evidence_file" "$AURA_EVIDENCE_ROOT/$evidence_file" \
      || die_env "cannot retain $evidence_file in the native evidence round"
  done
fi
# Keep collection, execution, Playwright artifacts and screenshots in this round.
export PW_ARTIFACT_DIR="$AURA_EVIDENCE_ROOT/playwright/artifacts"
export PW_REPORT_DIR="$AURA_EVIDENCE_ROOT/playwright/report"
export PW_RESULTS_JSON="$AURA_EVIDENCE_ROOT/playwright/report/results.json"
export AURA_EVIDENCE_DIR="$AURA_EVIDENCE_ROOT/hifi-golden"
mkdir -p "$AURA_EVIDENCE_DIR" || die_env 'golden screenshot directory unavailable'
log "5/6 run exact profile: base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL"
set +e
PLAYWRIGHT_JSON_OUTPUT_FILE="$AURA_EVIDENCE_ROOT/results.json" NO_PROXY=localhost,127.0.0.1 \
  pnpm exec playwright test "${SPEC_FILES[@]}" --project=chromium --no-deps --workers=1 --retries=0 \
  --repeat-each="$REPEAT" --reporter=line,json 2>&1 | tee "$AURA_EVIDENCE_ROOT/runner.log"
GATE_RC=${PIPESTATUS[0]}
set -e
[[ "$GATE_RC" == 0 ]] || exit "$GATE_RC"
log '6/6 reconcile collected scope and actual results'
node "$AUDIT" "$PROFILE" "$AURA_EVIDENCE_ROOT/results.json" execution "$REPEAT" \
  >"$AURA_EVIDENCE_ROOT/execution-ledger.json" || exit 1
log "PASS: fixed profile executed with no missing/skipped/retried results; evidence=$AURA_EVIDENCE_ROOT"
log "Review URLs: web=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL"
