#!/usr/bin/env bash
#
# Self-contained capability-eval runner for the digital-employee agent line.
#
# Answers one question: **is this digital employee actually competent?** — not
# "did it compile" or "is the page wired up". It brings up an isolated stack,
# runs the live capability evals against a real LLM, and exits non-zero when the
# agent fails a capability gate.
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
# a gate.
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
log "allocating runtime '$RUNTIME_NAME' on slot $SLOT"
(cd "$WORKSPACE_ROOT" && ./dev.sh runtime allocate auraboot "$RUNTIME_NAME" --slot "$SLOT" \
    --purpose "digital-employee capability eval (live LLM)" --ttl 2h) >>"$LOG" 2>&1
(cd "$WORKSPACE_ROOT" && ./dev.sh infra ensure "$RUNTIME_NAME" --yes) >>"$LOG" 2>&1

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
  --tests '*LiveIT' --tests '*LiveEvalIT' --tests '*LiveQualityIT' --tests '*FullTurnIT' \
  >>"$LOG" 2>&1 || rc=1

log "running aurabot skill evals (testAi)"
"$GRADLE" -p "$REPO_ROOT/platform" :testAi -PincludeLiveEvals \
  --tests '*DashboardGenerationLiveIT' \
  >>"$LOG" 2>&1 || rc=1

# --- report from the JUnit XML, not from the exit code -----------------------
# A gradle exit code cannot distinguish "every eval was skipped" from "every eval
# passed". The XML is the only evidence that the evals actually ran.
summarize() {
  local total=0 skipped=0 failures=0 errors=0 files=0
  for x in "$REPO_ROOT"/platform/build/test-results/testAgent/TEST-*.xml \
           "$REPO_ROOT"/platform/build/test-results/testAi/TEST-*.xml; do
    [ -f "$x" ] || continue
    case "$(basename "$x")" in *Live*|*FullTurn*) ;; *) continue ;; esac
    local line t s f e
    line=$(grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$x" | head -1)
    t=$(sed -E 's/.*tests="([0-9]+)".*/\1/' <<<"$line")
    s=$(sed -E 's/.*skipped="([0-9]+)".*/\1/' <<<"$line")
    f=$(sed -E 's/.*failures="([0-9]+)".*/\1/' <<<"$line")
    e=$(sed -E 's/.*errors="([0-9]+)".*/\1/' <<<"$line")
    printf '  %-52s tests=%-3s skip=%-3s fail=%-3s err=%s\n' \
      "$(basename "$x" .xml | sed 's/TEST-com.auraboot.framework.//')" "$t" "$s" "$f" "$e"
    total=$((total+t)); skipped=$((skipped+s)); failures=$((failures+f)); errors=$((errors+e)); files=$((files+1))
  done
  echo "  ----"
  echo "  suites=$files tests=$total skipped=$skipped failures=$failures errors=$errors"
  EVAL_FILES=$files EVAL_TOTAL=$total EVAL_SKIPPED=$skipped EVAL_FAILURES=$((failures+errors))
}

log "results:"
summarize | tee "$RUN_DIR/summary.txt"
read -r EVAL_FILES EVAL_TOTAL EVAL_SKIPPED EVAL_FAILED <<<"$(
  awk '/^  suites=/{gsub(/[a-z]+=/,""); print $1, $2, $3, $4+$5}' "$RUN_DIR/summary.txt")"

if [ "${EVAL_FILES:-0}" -eq 0 ] || [ "${EVAL_TOTAL:-0}" -eq 0 ]; then
  log "FAIL: no capability evals ran. An empty run is not a pass."
  exit 1
fi
if [ "${EVAL_SKIPPED:-0}" -gt 0 ]; then
  log "FAIL: ${EVAL_SKIPPED} eval(s) skipped — a skipped capability eval proves nothing."
  exit 1
fi
if [ "${EVAL_FAILED:-0}" -gt 0 ] || [ "$rc" -ne 0 ]; then
  log "FAIL: ${EVAL_FAILED} capability eval(s) failed. Log: $LOG"
  exit 1
fi

log "PASS: ${EVAL_TOTAL} capability evals across ${EVAL_FILES} suites, 0 skipped, 0 failed."
log "log: $LOG"
exit 0
