#!/usr/bin/env bash
#
# rbac-golden-run.sh — self-contained RBAC platform-baseline browser golden runner.
#
# For teams that run their own gates at release time or via a nightly crontab (NOT
# GitHub Actions). One command, hands-off: it brings up an isolated host-first stack
# (zero docker, slot-isolated — safe alongside concurrent sessions, never
# oss-reset-and-init's global pkill), imports the core plugins so menus exist, runs
# the per-role browser golden (web-admin/tests/e2e/rbac/), prints a PASS/FAIL banner,
# and tears the stack down. Exit code == golden result (0 = all green).
#
# The stack is destroyed-then-recreated each run, so the golden always sees a fresh
# bootstrap (tenant_admin + tenant_member) — never a stale-DB role model from a prior
# slot reuse. The golden's own assertions catch any role-model drift.
#
# NOTE: this covers the B-layer (browser) golden that needs the full stack. The A-layer
# backend ITs (RbacEnforcementMatrixIT / RbacAccessMatrixConsistencyTest /
# AgentDiscoveryAnonymousAuthIT) run via `./gradlew :test` in the normal backend suite.
#
# Prerequisites: the workspace native brokers (Postgres/Redis/Kafka) must be up — the
# same ones `dev.sh runtime` uses. Run from any OSS auraboot checkout/worktree.
#
# Usage:
#   scripts/rbac-golden-run.sh [--slot N] [--name NAME] [--keep] [--repeat K]
#     --slot N     isolated-stack slot (default: 71). Pick one not used by other runtimes.
#     --name NAME  runtime name        (default: rbac-golden-nightly)
#     --keep       leave the stack up after the run (for debugging a failure)
#     --repeat K   run the golden K times (flakiness check; default: 1)
#
# Crontab example (nightly 02:30):
#   30 2 * * *  cd /path/to/auraboot && ./scripts/rbac-golden-run.sh >> /var/log/rbac-golden.log 2>&1
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"

NAME="rbac-golden-nightly"
SLOT="71"
KEEP=0
REPEAT=1

die() { echo "[rbac-golden-run] ERROR: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot)   [[ $# -ge 2 ]] || die "--slot requires a value"; SLOT="$2"; shift 2;;
    --name)   [[ $# -ge 2 ]] || die "--name requires a value"; NAME="$2"; shift 2;;
    --repeat) [[ $# -ge 2 ]] || die "--repeat requires a value"; REPEAT="$2"; shift 2;;
    --keep)   KEEP=1; shift;;
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "unknown arg: $1";;
  esac
done

[[ -x "$GS" ]] || die "oss-golden-stack.sh not found/executable at $GS"

cleanup() {
  local rc=$?
  if [[ "$KEEP" == 1 ]]; then
    echo "[rbac-golden-run] --keep set; leaving stack '$NAME' up (env: $GS env $NAME)"
  else
    echo "[rbac-golden-run] tearing down stack '$NAME'..."
    "$GS" destroy "$NAME" >/dev/null 2>&1 || true
  fi
  return $rc
}
trap cleanup EXIT

echo "[rbac-golden-run] === RBAC platform-baseline golden — name=$NAME slot=$SLOT repeat=$REPEAT ==="

# 1. Fresh isolated stack (destroy any prior instance of this name first so the DB is
#    always freshly bootstrapped — guards against a stale-slot role model).
echo "[rbac-golden-run] 1/4 fresh stack (destroy prior + up + import)"
"$GS" destroy "$NAME" >/dev/null 2>&1 || true
# --no-warm: the rbac golden self-provisions its member and runs with --no-deps, so it does
# NOT need the setup/auth/pre-warm step (which runs the full generic setup project).
"$GS" up "$NAME" --slot "$SLOT" --ttl 2h --no-warm || die "stack bring-up failed"
"$GS" import "$NAME" || die "plugin import failed"

# 2. Export the Playwright env (PW_SKIP_WEBSERVER + base URL + backend + PG*).
echo "[rbac-golden-run] 2/4 resolve stack env"
eval "$("$GS" env "$NAME")" || die "could not resolve stack env"
echo "[rbac-golden-run]     base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL"

# 3. Run the golden.
echo "[rbac-golden-run] 3/4 run per-role browser golden (x$REPEAT)"
cd "$REPO_ROOT/web-admin" || die "web-admin not found"
set +e
NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/rbac/ \
  --project=chromium --no-deps --repeat-each="$REPEAT" --reporter=line
GOLDEN_RC=$?
set -e 2>/dev/null || true

# 4. Report.
echo "[rbac-golden-run] 4/4 result"
if [[ "$GOLDEN_RC" == 0 ]]; then
  echo "[rbac-golden-run] ============================================"
  echo "[rbac-golden-run]   RBAC GOLDEN: PASS  (name=$NAME slot=$SLOT)"
  echo "[rbac-golden-run] ============================================"
else
  echo "[rbac-golden-run] ############################################"
  echo "[rbac-golden-run]   RBAC GOLDEN: FAIL (rc=$GOLDEN_RC)"
  echo "[rbac-golden-run]   artifacts: web-admin/test-results/"
  echo "[rbac-golden-run]   (re-run with --keep to inspect the live stack)"
  echo "[rbac-golden-run] ############################################"
fi

exit "$GOLDEN_RC"
