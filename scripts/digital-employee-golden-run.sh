#!/usr/bin/env bash
#
# digital-employee-golden-run.sh — self-contained browser golden for the digital
# employee (AI colleague) journey.
#
# WHY THIS EXISTS
#
# On 2026-07-20 this one feature had seven defects while every existing signal
# said it was finished: the classes were written, the plugin imported with
# success:true, the backend suites were green, the docs described it as done.
# Four of the seven only appear when a person walks the flow — the wizard
# omitted a NOT NULL column so creation was a 100% 400; the enrolment button's
# precondition could never hold for a tenant-created agent; the provider list
# arrived empty because a bare array went through an envelope normaliser; and
# the colleague that got created could not hold a conversation because its model
# column defaulted to a vendor the tenant had never configured.
#
# The coverage matrix said create ✓, enrol ✓, listed ✓ — four green cells for a
# colleague that could not talk. Backend tests cannot see any of that. This
# runner is the thing that can.
#
# WHAT IT RUNS — web-admin/tests/e2e/agent-control-plane/, under PW_PROFILE=contract
# so the setup→auth dependency chain runs (bootstrap, multi-role users, test
# pages). Running these specs under --project=chromium instead would skip the
# fixture import steps and produce failures that are about the harness rather
# than the product.
#
# Screenshots land in web-admin/test-results/digital-employee/ and are the
# evidence a person can actually check — assertions cannot show layout.
#
# For teams running their own gates at release time or via a nightly crontab
# (NOT GitHub Actions). One command, hands-off: fresh isolated host-first stack
# (zero docker, slot-isolated, safe alongside concurrent sessions — never
# oss-reset-and-init's global pkill), plugin import, browser golden, PASS/FAIL
# banner, teardown. Exit code == golden result.
#
# The stack is destroyed-then-recreated each run so the golden always sees a
# fresh bootstrap. A reused slot carries a stale DB, and this journey's whole
# point is that it starts from an empty tenant.
#
# Prerequisites: the workspace native brokers (Postgres/Redis/Kafka) must be up —
# the same ones `dev.sh runtime` uses. Run from any OSS auraboot checkout/worktree.
#
# Usage:
#   scripts/digital-employee-golden-run.sh [--slot N] [--name NAME] [--keep] [--repeat K]
#     --slot N     isolated-stack slot (default: 72). Pick one no other runtime uses.
#     --name NAME  runtime name        (default: digital-employee-golden)
#     --keep       leave the stack up after the run (to inspect a failure)
#     --repeat K   run the golden K times (flakiness check; default: 1)
#
# On failure the stack is kept regardless of --keep: a torn-down stack cannot be
# inspected, and the evidence is the point.
#
# Crontab example (nightly 03:10):
#   10 3 * * *  cd /path/to/auraboot && ./scripts/digital-employee-golden-run.sh >> /var/log/de-golden.log 2>&1

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"

NAME="digital-employee-golden"
SLOT="72"
KEEP=0
REPEAT=1
LIVE=0
LIVE_ENV=(env)

# One spec in this directory asserts `.not.toContain('[stub response]')`, and it
# is right to: a colleague that cannot answer is the defect this whole suite was
# written after, and a stub answers, so a stubbed run of it proves nothing. That
# makes it structurally unable to pass here, and a check that can only ever be
# red is worse than no check — it buries every real failure after it.
#
# So it runs in the live tier instead. The spec now declares that itself, with
# test.skip(AGENT_LLM_STUB_MODE), the same way its two siblings do — excluding it
# from here by name kept this runner green but left the spec born-red under every
# other way of running it, and a check that is red for unrelated reasons hides the
# next real failure.
#
# What stays here is the reporting: a suite that silently skips something reads
# exactly like a suite that covered it, so the gap is printed at the end.
STUB_SELF_SKIPPING_SPEC="ai-colleague-can-talk.spec.ts"

die() { echo "[de-golden] ERROR: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot)   [[ $# -ge 2 ]] || die "--slot requires a value"; SLOT="$2"; shift 2;;
    --name)   [[ $# -ge 2 ]] || die "--name requires a value"; NAME="$2"; shift 2;;
    --repeat) [[ $# -ge 2 ]] || die "--repeat requires a value"; REPEAT="$2"; shift 2;;
    --keep)   KEEP=1; shift;;
    --live)   LIVE=1; shift;;
    -h|--help) sed -n '2,60p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "unknown arg: $1";;
  esac
done

[[ -x "$GS" ]] || die "oss-golden-stack.sh not found/executable at $GS"

GOLDEN_RC=1
cleanup() {
  local rc=$?
  if [[ "$KEEP" == 1 || "$rc" != 0 ]]; then
    echo "[de-golden] leaving stack '$NAME' up for inspection after rc=$rc (env: $GS env $NAME)"
    echo "[de-golden] destroy it with: $GS destroy $NAME"
  else
    echo "[de-golden] tearing down stack '$NAME'..."
    "$GS" destroy "$NAME" >/dev/null 2>&1 || true
  fi
  return $rc
}
trap cleanup EXIT

echo "[de-golden] === digital employee journey golden — name=$NAME slot=$SLOT repeat=$REPEAT ==="

# Before the stack starts, not after: the backend reads this at boot, and an
# export placed further down would look like it was doing the work while the
# backend had already been started without it.
if [[ "$LIVE" == 1 ]]; then
  # The live tier costs real money and is not deterministic, which is why it is
  # opt-in and belongs to the nightly rotation rather than to every run.
  for required_var in AURA_LIVE_LLM_PROVIDER AURA_LIVE_LLM_MODEL AURA_LIVE_LLM_API_KEY_ENV; do
    [[ -n "${!required_var:-}" ]] \
      || die "--live requires $required_var"
  done
  key_env="$AURA_LIVE_LLM_API_KEY_ENV"
  [[ "$AURA_LIVE_LLM_PROVIDER" =~ ^[A-Za-z0-9_-]+$ ]] \
    || die "AURA_LIVE_LLM_PROVIDER is invalid"
  [[ "$AURA_LIVE_LLM_MODEL" =~ ^[A-Za-z0-9._:-]+$ ]] \
    || die "AURA_LIVE_LLM_MODEL is invalid"
  [[ "$key_env" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
    || die "AURA_LIVE_LLM_API_KEY_ENV is invalid"
  [[ -n "${!key_env:-}" ]] \
    || die "configured credential variable $key_env is UNSET"
  # Make the declared live profile the only eligible seeded provider. A developer
  # may have credentials for several vendors in the shell; letting a lower-priority
  # unrelated provider win would make the receipt disagree with the requested run.
  if [[ "$AURA_LIVE_LLM_PROVIDER" == "qianwen" ]]; then
    LIVE_ENV=(env -u DEEPSEEK_API_KEY)
  fi
  export AGENT_LLM_STUB_MODE=false
  echo "[de-golden] mode: LIVE — provider=$AURA_LIVE_LLM_PROVIDER model=$AURA_LIVE_LLM_MODEL key=$key_env=SET"
else
  # The default tier asserts that the plumbing works: that a colleague can be
  # created, enrolled, suspended and hold a turn at all. Whether the model
  # answers *well* is a different question and a different budget.
  export AGENT_LLM_STUB_MODE=true
  echo "[de-golden] mode: STUB — $STUB_SELF_SKIPPING_SPEC will self-skip (needs a real model; run --live for it)"
fi

echo "[de-golden] 1/4 fresh stack (destroy prior + up + import)"
"$GS" destroy "$NAME" >/dev/null 2>&1 || true
"${LIVE_ENV[@]}" "$GS" up "$NAME" --slot "$SLOT" --ttl 2h || die "stack bring-up failed"
# The e2e profile, not the default core one: core's seven plugins do not include
# agent-control-plane, and without it two thirds of this suite fails on "ACP
# plugin must be installed" — a harness answer wearing a product failure's
# clothes. e2e is a strict superset of core.
"$GS" import "$NAME" --plugin-profile e2e || die "plugin import failed"

echo "[de-golden] 2/4 resolve stack env"
eval "$("$GS" env "$NAME")" || die "could not resolve stack env"
echo "[de-golden]     base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL"

echo "[de-golden] 3/4 run digital employee browser golden (x$REPEAT)"
cd "$REPO_ROOT/web-admin" || die "web-admin not found"
rm -rf test-results/digital-employee
set +e
PW_ARGS=(tests/e2e/agent-control-plane/ --project=contract
         --workers=1 --repeat-each="$REPEAT" --reporter=line)
# No --grep-invert: the spec self-skips under stub mode, and a reported skip is
# better evidence than a test that was never selected.
NO_PROXY=localhost,127.0.0.1 PW_PROFILE=contract \
  pnpm exec playwright test "${PW_ARGS[@]}" 2>&1 | tee /tmp/de-golden-run.$$.log
GOLDEN_RC=${PIPESTATUS[0]}
set -e 2>/dev/null || true

USAGE_CALLS=0
USAGE_MISMATCHES=0
STUCK_INTERACTIVE_TURNS=0
if [[ "$LIVE" == 1 ]]; then
  echo "[de-golden]     verify live provider/model ledger + interactive terminality"
  set +e
  usage_gate=$(
    PGPASSWORD="$PGPASSWORD" psql \
      -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
      -v ON_ERROR_STOP=1 \
      -v expected_provider="$AURA_LIVE_LLM_PROVIDER" \
      -v expected_model="$AURA_LIVE_LLM_MODEL" \
      -t -A <<'SQL'
        SELECT
          COUNT(*),
          COUNT(*) FILTER (
            WHERE provider IS DISTINCT FROM :'expected_provider'
               OR request_model IS DISTINCT FROM :'expected_model'
               OR response_model IS DISTINCT FROM :'expected_model'
          )
        FROM ab_gen_ai_usage;
SQL
  )
  usage_gate_rc=$?
  terminal_gate=""
  terminal_gate_rc=0
  # Browser success can become visible a few seconds before the async approval
  # continuation commits the task lifecycle update. A single immediate SELECT
  # therefore reported a false stranded turn even though the exact row reached
  # completed 3.7s later. Require bounded convergence instead: this still fails
  # a genuinely stuck task, but gives the normal transaction/async tail up to
  # 30 seconds to become observable.
  for ((terminal_attempt = 1; terminal_attempt <= 30; terminal_attempt++)); do
    terminal_gate=$(
      PGPASSWORD="$PGPASSWORD" psql \
        -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        -v ON_ERROR_STOP=1 \
        -t -A <<'SQL'
          SELECT COUNT(*)
          FROM ab_agent_task
          WHERE deleted_flag = false
            AND assignee_type = 'ai'
            AND task_status = 'in_progress'
            AND COALESCE(input_data, '{}')::jsonb ? 'turnId';
SQL
    )
    terminal_gate_rc=$?
    if [[ "$terminal_gate_rc" != 0 || "$terminal_gate" == 0 ]]; then
      break
    fi
    if [[ "$terminal_attempt" == 1 ]]; then
      echo "[de-golden]     waiting for interactive task terminality (max 30s)"
    fi
    sleep 1
  done
  set -e 2>/dev/null || true

  if [[ "$usage_gate_rc" != 0 || ! "$usage_gate" =~ ^[0-9]+\|[0-9]+$ ]]; then
    echo "[de-golden] FAIL: could not verify the live usage ledger"
    [[ "$GOLDEN_RC" == 0 ]] && GOLDEN_RC=4
  else
    IFS='|' read -r USAGE_CALLS USAGE_MISMATCHES <<<"$usage_gate"
    echo "[de-golden]     usage calls=$USAGE_CALLS mismatched-provider-or-model=$USAGE_MISMATCHES"
    if [[ "$USAGE_CALLS" == 0 || "$USAGE_MISMATCHES" != 0 ]]; then
      echo "[de-golden] FAIL: live browser evidence was not produced exclusively by the selected provider/model"
      [[ "$GOLDEN_RC" == 0 ]] && GOLDEN_RC=4
    fi
  fi

  if [[ "$terminal_gate_rc" != 0 || ! "$terminal_gate" =~ ^[0-9]+$ ]]; then
    echo "[de-golden] FAIL: could not verify interactive task terminality"
    [[ "$GOLDEN_RC" == 0 ]] && GOLDEN_RC=5
  else
    STUCK_INTERACTIVE_TURNS="$terminal_gate"
    echo "[de-golden]     stuck interactive turns=$STUCK_INTERACTIVE_TURNS"
    if [[ "$STUCK_INTERACTIVE_TURNS" != 0 ]]; then
      echo "[de-golden] FAIL: at least one browser-driven turn is stranded in progress"
      echo "[de-golden]     stranded task details:"
      PGPASSWORD="$PGPASSWORD" psql \
        -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        -v ON_ERROR_STOP=1 \
        -P pager=off \
        -c "SELECT pid, assignee_id, input_data, created_at, updated_at
              FROM ab_agent_task
             WHERE deleted_flag = false
               AND assignee_type = 'ai'
               AND task_status = 'in_progress'
               AND COALESCE(input_data, '{}')::jsonb ? 'turnId'
             ORDER BY created_at" || true
      [[ "$GOLDEN_RC" == 0 ]] && GOLDEN_RC=5
    fi
  fi
fi

echo "[de-golden] 4/4 result"
SHOTS=$(find test-results/digital-employee -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
# A clean run has no "<n> failed" summary line. With `set -euo pipefail`,
# grep's expected no-match exit code would otherwise terminate the runner
# before it writes the PASS banner and receipt.
set +e
PASSED_COUNT=$(grep -aoE '[0-9]+ passed' "/tmp/de-golden-run.$$.log" 2>/dev/null | tail -1 | awk '{print $1}')
SKIPPED_COUNT=$(grep -aoE '[0-9]+ skipped' "/tmp/de-golden-run.$$.log" 2>/dev/null | tail -1 | awk '{print $1}')
FAILED_COUNT=$(grep -aoE '[0-9]+ failed' "/tmp/de-golden-run.$$.log" 2>/dev/null | tail -1 | awk '{print $1}')
set -e 2>/dev/null || true
PASSED_COUNT=${PASSED_COUNT:-0}
SKIPPED_COUNT=${SKIPPED_COUNT:-0}
FAILED_COUNT=${FAILED_COUNT:-0}
if [[ "$GOLDEN_RC" == 0 ]]; then
  echo "[de-golden] ============================================"
  echo "[de-golden]   DIGITAL EMPLOYEE GOLDEN: PASS  (name=$NAME slot=$SLOT, mode=$([[ "$LIVE" == 1 ]] && echo LIVE || echo STUB))"
  [[ "$LIVE" == 1 ]] || echo "[de-golden]   NOT COVERED HERE: $STUB_SELF_SKIPPING_SPEC — a stub answers, so it cannot fail the way a mute colleague does"
  # Skips are printed because a pass that quietly swallowed them reads exactly
  # like a pass that ran everything. Some of this suite's skips fire when the
  # row under test cannot be found — which declares success for the case where
  # the thing being tested is missing — so the count is worth a person's eye
  # even though it does not fail the gate.
  [[ "$SKIPPED_COUNT" != 0 ]] && echo "[de-golden]   $SKIPPED_COUNT skipped — check what, a skip on 'row not found' is a pass for a missing thing"
  echo "[de-golden]   screenshots: web-admin/test-results/digital-employee/ ($SHOTS)"
  echo "[de-golden] ============================================"
  # A pass with no screenshots means the evidence step silently stopped
  # running, which is the failure this golden is least able to notice about
  # itself — so it is called out rather than reported as a clean green.
  if [[ "$SHOTS" == 0 ]]; then
    echo "[de-golden] WARNING: green with zero screenshots — the evidence step did not run"
    GOLDEN_RC=3
  fi
else
  echo "[de-golden] ############################################"
  echo "[de-golden]   DIGITAL EMPLOYEE GOLDEN: FAIL (rc=$GOLDEN_RC)"
  echo "[de-golden]   artifacts:   web-admin/test-results/"
  echo "[de-golden]   screenshots: web-admin/test-results/digital-employee/ ($SHOTS)"
  echo "[de-golden]   use --keep on a focused rerun when live-stack inspection is needed"
  echo "[de-golden] ############################################"
fi

RECEIPT_DIR="$REPO_ROOT/build/digital-employee-golden/$NAME"
mkdir -p "$RECEIPT_DIR"
SOURCE_SHA=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)
TRACKED_SOURCE_DIRTY=false
[[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no 2>/dev/null)" ]] \
  && TRACKED_SOURCE_DIRTY=true
{
  printf 'source_sha=%s\n' "$SOURCE_SHA"
  printf 'tracked_source_dirty=%s\n' "$TRACKED_SOURCE_DIRTY"
  printf 'result=%s\n' "$([[ "$GOLDEN_RC" == 0 ]] && echo passed || echo failed)"
  printf 'runtime=%s\n' "$NAME"
  printf 'database=%s\n' "$PGDATABASE"
  printf 'live_profile=%s\n' "$LIVE"
  printf 'provider=%s\n' "${AURA_LIVE_LLM_PROVIDER:-stub}"
  printf 'model=%s\n' "${AURA_LIVE_LLM_MODEL:-stub}"
  printf 'api_key_env=%s\n' "${AURA_LIVE_LLM_API_KEY_ENV:-none}"
  printf 'passed=%s\n' "$PASSED_COUNT"
  printf 'skipped=%s\n' "$SKIPPED_COUNT"
  printf 'failed=%s\n' "$FAILED_COUNT"
  printf 'usage_calls=%s\n' "$USAGE_CALLS"
  printf 'usage_mismatches=%s\n' "$USAGE_MISMATCHES"
  printf 'stuck_interactive_turns=%s\n' "$STUCK_INTERACTIVE_TURNS"
  printf 'screenshots=%s\n' "$SHOTS"
  printf 'completed_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$RECEIPT_DIR/receipt.env"
echo "[de-golden] receipt: $RECEIPT_DIR/receipt.env"

rm -f "/tmp/de-golden-run.$$.log"
exit "$GOLDEN_RC"
