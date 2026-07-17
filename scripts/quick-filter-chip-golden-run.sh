#!/usr/bin/env bash
#
# quick-filter-chip-golden-run.sh — self-contained quick-filter view-chip browser golden runner.
#
# For teams that run their own gates at release time or via a nightly crontab (NOT
# GitHub Actions). One command, hands-off: it brings up an isolated host-first stack
# (zero docker, slot-isolated — safe alongside concurrent sessions, never
# oss-reset-and-init's global pkill), imports the test-fixtures plugin so the e2et_order
# model + list page exist, runs the three quick-filter chip browser goldens, prints a
# PASS/FAIL banner, and tears the stack down. Exit code == golden result (0 = all green).
#
# Goldens covered (web-admin/tests/e2e/saved-view/):
#   - quick-filter-view-chip-golden.spec.ts   (M1: plugin/admin pinned view -> chip)
#   - quick-filter-user-pin-golden.spec.ts    (M2: user personal pin/unpin)
#   - quick-filter-team-pin-golden.spec.ts    (M3: team pin visibility + authoring UI)
#
# These goldens have view-limit / timing contention under the default 2-worker parallel
# run (they create + reuse SavedViews on one e2et_order model), so this runner pins
# --workers=1 — single-worker isolation is the pass baseline. They need auth (storageState)
# and the e2et_order seed, so the run uses the normal setup+auth deps (NOT --no-deps).
#
# NOTE: the B-layer (browser) goldens here need the full stack. The A-layer backend ITs
# (SavedViewChipPinServiceTest / SavedViewChipPinTeamIT / SavedViewChipPinAuthzTest) run
# via `platform/gradlew :test` in the normal backend suite.
#
# Prerequisites: the workspace native brokers (Postgres/Redis/Kafka) must be up — the
# same ones `dev.sh runtime` uses. Run from any OSS auraboot checkout/worktree.
#
# Usage:
#   scripts/quick-filter-chip-golden-run.sh [--slot N] [--name NAME] [--keep] [--repeat K]
#     --slot N     isolated-stack slot (default: 79). Pick one not used by other runtimes.
#     --name NAME  runtime name        (default: quick-filter-chip-golden)
#     --keep       leave the stack up after the run (for debugging a failure)
#     --repeat K   run each golden K times (flakiness check; default: 1)
#
# Crontab example (nightly 03:10):
#   10 3 * * *  cd /path/to/auraboot && ./scripts/quick-filter-chip-golden-run.sh >> /var/log/quick-filter-chip-golden.log 2>&1
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"

NAME="quick-filter-chip-golden"
SLOT="79"
KEEP=0
REPEAT=1

SPECS=(
  tests/e2e/saved-view/quick-filter-view-chip-golden.spec.ts
  tests/e2e/saved-view/quick-filter-user-pin-golden.spec.ts
  tests/e2e/saved-view/quick-filter-team-pin-golden.spec.ts
)

die() { echo "[qf-chip-golden] ERROR: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot)   [[ $# -ge 2 ]] || die "--slot requires a value"; SLOT="$2"; shift 2;;
    --name)   [[ $# -ge 2 ]] || die "--name requires a value"; NAME="$2"; shift 2;;
    --repeat) [[ $# -ge 2 ]] || die "--repeat requires a value"; REPEAT="$2"; shift 2;;
    --keep)   KEEP=1; shift;;
    -h|--help) sed -n '2,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "unknown arg: $1";;
  esac
done

[[ -x "$GS" ]] || die "oss-golden-stack.sh not found/executable at $GS"

cleanup() {
  local rc=$?
  if [[ "$KEEP" == 1 ]]; then
    echo "[qf-chip-golden] --keep set; leaving stack '$NAME' up (env: $GS env $NAME)"
  else
    echo "[qf-chip-golden] tearing down stack '$NAME'..."
    "$GS" destroy "$NAME" >/dev/null 2>&1 || true
  fi
  return $rc
}
trap cleanup EXIT

echo "[qf-chip-golden] === quick-filter view-chip golden — name=$NAME slot=$SLOT repeat=$REPEAT ==="

# 1. Fresh isolated stack: destroy any prior instance of this name first so the DB is
#    always freshly bootstrapped, then bring it up with the test-fixtures plugin (e2et_order).
echo "[qf-chip-golden] 1/3 fresh stack (destroy prior + up --fresh-db --plugin test-fixtures)"
"$GS" destroy "$NAME" >/dev/null 2>&1 || true
"$GS" up "$NAME" --slot "$SLOT" --fresh-db --plugin test-fixtures --ttl 2h \
  || die "stack bring-up failed"

# 2. Export the Playwright env (PW_SKIP_WEBSERVER + base URL + backend + PG*).
echo "[qf-chip-golden] 2/3 resolve stack env"
eval "$("$GS" env "$NAME")" || die "could not resolve stack env"
echo "[qf-chip-golden]     base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL"

# 3. Run the three chip goldens single-worker (see header: view-limit/timing contention
#    under parallel). gt5 config scopes the setup project to the 00/01 bootstrap specs.
echo "[qf-chip-golden] 3/3 run chip goldens single-worker (x$REPEAT)"
cd "$REPO_ROOT/web-admin" || die "web-admin not found"
set +e
NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test -c playwright.gt5.config.ts \
  "${SPECS[@]}" \
  --project=chromium --workers=1 --repeat-each="$REPEAT" --reporter=line
GOLDEN_RC=$?
set -e 2>/dev/null || true

if [[ "$GOLDEN_RC" == 0 ]]; then
  echo "[qf-chip-golden] ============================================"
  echo "[qf-chip-golden]   QUICK-FILTER CHIP GOLDEN: PASS  (name=$NAME slot=$SLOT)"
  echo "[qf-chip-golden] ============================================"
else
  echo "[qf-chip-golden] ############################################"
  echo "[qf-chip-golden]   QUICK-FILTER CHIP GOLDEN: FAIL (rc=$GOLDEN_RC)"
  echo "[qf-chip-golden]   artifacts: web-admin/test-results/"
  echo "[qf-chip-golden]   (re-run with --keep to inspect the live stack)"
  echo "[qf-chip-golden] ############################################"
fi

exit "$GOLDEN_RC"
