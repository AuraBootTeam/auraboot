#!/usr/bin/env bash
#
# Self-contained capability-eval runner for the digital-employee agent line.
#
# Answers one question: **is this digital employee actually competent?** — not
# "did it compile" or "is the page wired up". It brings up an isolated stack,
# runs the live capability evals against a real LLM plus the plugin import seams,
# and exits non-zero when the agent fails a capability gate.
#
# Owner has no CI budget, so this is the deliverable form: one command, brings up
# its own stack, tears it down on any exit path, and its exit code IS the result.
# Run it at release time or from cron; there is no .yml workflow.
#
#   ./scripts/digital-employee-capability-eval-run.sh            # full suite
#   ./scripts/digital-employee-capability-eval-run.sh --keep     # keep stack for inspection
#   ./scripts/digital-employee-capability-eval-run.sh --slot 83  # pick a runtime slot
#
# Credentials (never printed, only reported SET/UNSET):
#   DASHSCOPE_API_KEY   preferred — qwen
#   DEEPSEEK_API_KEY    fallback
#   AURA_LIVE_EVAL_MODEL  optional per-run model override
#
# WHY A DEDICATED RUNNER (the incident this exists to prevent): the live evals are
# excluded from every default gradle task, so for months nothing ran them. When
# DeepSeek retired the `deepseek-chat` model name, all 14 live capability evals
# broke silently — the layer that proves the agent works was itself dead, and the
# only reason anyone found out was running it by hand. A gate nobody runs is not
# a gate. scripts/perf-ci/cron.example wires this into the nightly rotation, and
# scripts/release/tag-release.sh runs it on the exact commit before an OSS tag.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE_ROOT="$(cd "$REPO_ROOT/.." && pwd)"
[ -x "$WORKSPACE_ROOT/dev.sh" ] || WORKSPACE_ROOT="$(cd "$REPO_ROOT/../.." && pwd)"

SLOT="${SLOT:-83}"
KEEP=0
RUNTIME_NAME="de-capability-eval-$$"

while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --slot) SLOT="$2"; shift 2 ;;
    -h|--help) sed -n '3,30p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

RUN_DIR="${RUN_DIR:-$REPO_ROOT/build/capability-eval}"
mkdir -p "$RUN_DIR"
LOG="$RUN_DIR/capability-eval.log"

log() { printf '[capability-eval] %s\n' "$*"; }

# --- credential check (report SET/UNSET, never the value) --------------------
if [ -n "${DASHSCOPE_API_KEY:-}" ]; then
  log "live provider credential: DASHSCOPE_API_KEY=SET (qwen preferred)"
elif [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  log "live provider credential: DEEPSEEK_API_KEY=SET (deepseek fallback)"
else
  log "FAIL: no live LLM credential. Set DASHSCOPE_API_KEY (preferred) or DEEPSEEK_API_KEY."
  log "  Without one every eval self-skips — and a suite that skips everything reports"
  log "  green while proving nothing. Refusing to run."
  exit 2
fi

DB_NAME="auraboot_${SLOT}"

cleanup() {
  local rc=$?
  if [ "$KEEP" = "1" ]; then
    log "--keep set: leaving runtime '$RUNTIME_NAME' (db=$DB_NAME) up for inspection"
  else
    log "tearing down runtime '$RUNTIME_NAME'"
    (cd "$WORKSPACE_ROOT" && ./dev.sh infra cleanup "$RUNTIME_NAME" --yes >/dev/null 2>&1) || true
    (cd "$WORKSPACE_ROOT" && ./dev.sh runtime destroy "$RUNTIME_NAME" --yes >/dev/null 2>&1) || true
  fi
  exit $rc
}
trap cleanup EXIT INT TERM

# --- bring up an isolated stack ---------------------------------------------
# Slots are shared with every other session on this machine. Never grab one that
# is already allocated: that is someone else's running stack, and the eval would
# either fail confusingly or trample their work. Probe upward from the requested
# slot and take the first free one.
allocate_on_free_slot() {
  local slot="$1" attempts=0 out
  while [ "$attempts" -lt 12 ]; do
    if out=$(cd "$WORKSPACE_ROOT" && ./dev.sh runtime allocate auraboot "$RUNTIME_NAME" --slot "$slot" \
               --purpose "digital-employee capability eval (live LLM)" --ttl 2h 2>&1); then
      printf '%s\n' "$out" >>"$LOG"
      SLOT="$slot"
      DB_NAME="auraboot_${SLOT}"
      log "allocated runtime '$RUNTIME_NAME' on slot $SLOT (db=$DB_NAME)"
      return 0
    fi
    printf '%s\n' "$out" >>"$LOG"
    case "$out" in
      *"already allocated"*)
        log "slot $slot is taken by another session — trying $((slot+1))"
        slot=$((slot+1)); attempts=$((attempts+1)) ;;
      *)
        # Anything other than a slot clash is a real failure: surface it instead of
        # exiting silently through the trap with the reason buried in the log.
        log "FAIL: could not allocate a runtime:"; printf '%s\n' "$out" >&2; return 1 ;;
    esac
  done
  log "FAIL: no free runtime slot found starting at $1"
  return 1
}

allocate_on_free_slot "$SLOT" || exit 2

if ! out=$(cd "$WORKSPACE_ROOT" && ./dev.sh infra ensure "$RUNTIME_NAME" --yes 2>&1); then
  printf '%s\n' "$out" >>"$LOG"
  log "FAIL: infra ensure failed for '$RUNTIME_NAME':"
  printf '%s\n' "$out" >&2
  exit 2
fi
printf '%s\n' "$out" >>"$LOG"

# The integration-test profile assumes a migrated database. A freshly created one
# is empty, so the Spring context dies on the first missing table — apply the
# committed schema snapshot before running anything. ON_ERROR_STOP so a broken
# snapshot fails here loudly instead of surfacing as a confusing test error.
log "applying schema snapshot to $DB_NAME"
PGPASSWORD="${PGPASSWORD:-postgres}" psql -v ON_ERROR_STOP=1 -h localhost -U auraboot -d "$DB_NAME" \
  -f "$REPO_ROOT/platform/src/main/resources/db/snapshots/schema-current.sql" >>"$LOG" 2>&1
log "schema applied ($(PGPASSWORD="${PGPASSWORD:-postgres}" psql -h localhost -U auraboot -d "$DB_NAME" \
  -tAc "select count(*) from information_schema.tables where table_name like 'ab_%'") ab_* tables)"

# --- run the capability evals ------------------------------------------------
# Two gradle tasks are needed: `testAgent` covers **/agent/**, but the dashboard
# generation eval lives under **/aurabot/** and is only reachable via `testAi`.
# Running just one silently omits an eval — that is how DashboardGenerationLiveIT
# went unrun.
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/${DB_NAME}?charSet=UTF8"
export SPRING_DATA_REDIS_HOST=127.0.0.1
export SPRING_DATA_REDIS_PORT=6379
export SPRING_DATA_REDIS_DATABASE="${REDIS_DATABASE:-1}"
# Keep the shared ~/.m2 and ~/.gradle: a per-runtime cache lacks the released
# artifacts these modules need and would fail resolution, not capability.
unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL GRADLE_USER_HOME || true

GRADLE="$REPO_ROOT/platform/gradlew"
rc=0

log "running agent capability evals (testAgent)"
"$GRADLE" -p "$REPO_ROOT/platform" :testAgent -PincludeLiveEvals \
  --tests '*AgentEvalCaseImportIT' \
  --tests '*MultiPluginEvalCaseCoexistenceIT' \
  --tests '*DeviceAgentSeedImportIT' \
  --tests '*AgentFormFillHardLiveIT' \
  --tests '*AgentFormFillLiveIT' \
  --tests '*AgentMultiStepConvergenceLiveIT' \
  --tests '*CapabilityEvalLiveIT' \
  --tests '*CapabilityScorecardLiveIT' \
  --tests '*ChatBiToolIntentLiveIT' \
  --tests '*ConversationFaqExtractionLiveIT' \
  --tests '*CsComplaintEmailExtractionLiveIT' \
  --tests '*DeviceAgentLiveEvalIT' \
  --tests '*DeviceDiagnosticsFullTurnIT' \
  --tests '*DeviceOperationsAgentLiveEvalIT' \
  --tests '*LlmTurnQualityJudgeLiveIT' \
  --tests '*NlModelingApplyV4LiveIT' \
  --tests '*NlModelingLiveQualityIT' \
  --tests '*PcbaQualityAgentLiveEvalIT' \
  >>"$LOG" 2>&1 || rc=1

log "running aurabot skill evals (testAi)"
"$GRADLE" -p "$REPO_ROOT/platform" :testAi -PincludeLiveEvals \
  --tests '*DashboardGenerationLiveIT' \
  >>"$LOG" 2>&1 || rc=1

# --- report from an explicit JUnit inventory, not from the exit code ----------
# Gradle cannot distinguish "every eval skipped" from "every eval passed". A
# broad XML glob also cannot distinguish "required suite omitted" from "suite
# intentionally absent". The checked inventory is the authoritative receipt.
log "results:"
summary_rc=0
node "$REPO_ROOT/scripts/lib/capability-eval-junit.mjs" \
  --results-root "$REPO_ROOT/platform/build/test-results" \
  | tee "$RUN_DIR/summary.txt" || summary_rc=$?

if [ "$summary_rc" -ne 0 ] || [ "$rc" -ne 0 ]; then
  log "FAIL: required capability eval suite is missing, skipped, or failed. Log: $LOG"
  exit 1
fi

git_sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
provider="deepseek"
[ -n "${DASHSCOPE_API_KEY:-}" ] && provider="qwen"
{
  printf 'git_sha=%s\n' "$git_sha"
  printf 'provider=%s\n' "$provider"
  printf 'completed_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'summary=%s\n' "$RUN_DIR/summary.txt"
} >"$RUN_DIR/receipt.env"

log "PASS: every required deterministic and live capability suite ran; 0 skipped, 0 failed."
log "receipt: $RUN_DIR/receipt.env"
log "log: $LOG"
exit 0
