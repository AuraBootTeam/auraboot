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

# One spec in this directory asserts `.not.toContain('[stub response]')`, and it
# is right to: a colleague that cannot answer is the defect this whole suite was
# written after, and a stub answers, so a stubbed run of it proves nothing. That
# makes it structurally unable to pass here, and a check that can only ever be
# red is worse than no check — it buries every real failure after it.
#
# So it runs in the live tier instead, and the exclusion is printed rather than
# quietly applied: a suite that silently skips something reads exactly like a
# suite that covered it.
STUB_EXCLUDED_SPEC="ai-colleague-can-talk.spec.ts"

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
  if [[ "$KEEP" == 1 || "$GOLDEN_RC" != 0 ]]; then
    echo "[de-golden] leaving stack '$NAME' up for inspection (env: $GS env $NAME)"
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
  [[ -n "${DASHSCOPE_API_KEY:-}${DEEPSEEK_API_KEY:-}" ]] \
    || die "--live needs a provider key in the environment (DASHSCOPE_API_KEY or DEEPSEEK_API_KEY)"
  export AGENT_LLM_STUB_MODE=false
  echo "[de-golden] mode: LIVE — a real model answers; the full suite runs"
else
  # The default tier asserts that the plumbing works: that a colleague can be
  # created, enrolled, suspended and hold a turn at all. Whether the model
  # answers *well* is a different question and a different budget.
  export AGENT_LLM_STUB_MODE=true
  echo "[de-golden] mode: STUB — excluding $STUB_EXCLUDED_SPEC (needs a real model; run --live for it)"
fi

echo "[de-golden] 1/4 fresh stack (destroy prior + up + import)"
"$GS" destroy "$NAME" >/dev/null 2>&1 || true
"$GS" up "$NAME" --slot "$SLOT" --ttl 2h || die "stack bring-up failed"
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
         --repeat-each="$REPEAT" --reporter=line)
[[ "$LIVE" == 1 ]] || PW_ARGS+=(--grep-invert "conversation")
NO_PROXY=localhost,127.0.0.1 PW_PROFILE=contract \
  pnpm exec playwright test "${PW_ARGS[@]}" 2>&1 | tee /tmp/de-golden-run.$$.log
GOLDEN_RC=${PIPESTATUS[0]}
set -e 2>/dev/null || true

echo "[de-golden] 4/4 result"
SHOTS=$(find test-results/digital-employee -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
if [[ "$GOLDEN_RC" == 0 ]]; then
  echo "[de-golden] ============================================"
  echo "[de-golden]   DIGITAL EMPLOYEE GOLDEN: PASS  (name=$NAME slot=$SLOT, mode=$([[ "$LIVE" == 1 ]] && echo LIVE || echo STUB))"
  [[ "$LIVE" == 1 ]] || echo "[de-golden]   NOT COVERED HERE: $STUB_EXCLUDED_SPEC — a stub answers, so it cannot fail the way a mute colleague does"
  # Skips are printed because a pass that quietly swallowed them reads exactly
  # like a pass that ran everything. Some of this suite's skips fire when the
  # row under test cannot be found — which declares success for the case where
  # the thing being tested is missing — so the count is worth a person's eye
  # even though it does not fail the gate.
  SKIPPED=$(grep -aoE '[0-9]+ skipped' "/tmp/de-golden-run.$$.log" 2>/dev/null | tail -1)
  [[ -n "$SKIPPED" ]] && echo "[de-golden]   $SKIPPED — check what, a skip on 'row not found' is a pass for a missing thing"
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
  echo "[de-golden]   the stack is still up — inspect it before destroying"
  echo "[de-golden] ############################################"
fi

rm -f "/tmp/de-golden-run.$$.log"
exit "$GOLDEN_RC"
