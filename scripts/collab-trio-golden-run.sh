#!/usr/bin/env bash
#
# collab-trio-golden-run.sh — self-contained browser golden runner for the
# collaboration trio: Inbox (unified action queue), the notification centre,
# and IM group chat / AI-colleague routing.
#
# For teams that run their own gates at release time or via a nightly crontab (NOT
# GitHub Actions). One command, hands-off: brings up an isolated host-first stack
# (zero docker, slot-isolated — safe alongside concurrent sessions, never
# oss-reset-and-init's global pkill), runs the collab goldens, prints a PASS/FAIL
# banner, and tears the stack down. Exit code == golden result (0 = all green).
#
# Goldens covered (web-admin/tests/e2e/collab-review/):
#   - notification-actions.spec.ts  category/read filters, delete, header bell + SSE
#   - inbox-actions.spec.ts         unread badge arithmetic, dismiss, mark-all-read, type tabs
#
# Why these exist: each one pins a defect that shipped green under the previous
# suite — a filter the backend accepted and ignored, a delete button whose endpoint
# 404'd, a header bell that was built but never mounted, and an unread badge that
# summed the summary map's own `total` key and so read exactly double. Presence-only
# assertions ("the delete button is visible") passed through all of them, so every
# assertion here checks an outcome instead.
#
# Each spec self-provisions its rows through the `notifications` / `inbox_*` test
# fixtures and keys assertions on that run's id, so specs cannot poison each other.
#
# Prerequisites: the workspace native brokers (Postgres/Redis) must be up — the same
# ones `dev.sh runtime` uses. Run from any OSS auraboot checkout/worktree.
#
# Usage:
#   scripts/collab-trio-golden-run.sh [--slot N] [--name NAME] [--keep] [--repeat K]
#     --slot N     isolated-stack slot (default: 88). Pick one not used by other runtimes.
#     --name NAME  runtime name        (default: collab-trio-golden)
#     --keep       leave the stack up after the run (for debugging a failure)
#     --repeat K   run each golden K times (flakiness check; default: 1)
#
# Crontab example (nightly 03:40):
#   40 3 * * *  cd /path/to/auraboot && ./scripts/collab-trio-golden-run.sh >> /var/log/collab-trio-golden.log 2>&1
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$REPO_ROOT/scripts/oss-golden-stack.sh"

NAME="collab-trio-golden"
SLOT="88"
KEEP=0
REPEAT=1

SPECS=(
  tests/e2e/collab-review/notification-actions.spec.ts
  tests/e2e/collab-review/inbox-actions.spec.ts
)

die() { echo "[collab-golden] ERROR: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot)   [[ $# -ge 2 ]] || die "--slot requires a value"; SLOT="$2"; shift 2;;
    --name)   [[ $# -ge 2 ]] || die "--name requires a value"; NAME="$2"; shift 2;;
    --repeat) [[ $# -ge 2 ]] || die "--repeat requires a value"; REPEAT="$2"; shift 2;;
    --keep)   KEEP=1; shift;;
    -h|--help) sed -n '2,38p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "unknown arg: $1";;
  esac
done

[[ -x "$GS" ]] || die "oss-golden-stack.sh not found/executable at $GS"

cleanup() {
  local rc=$?
  if [[ "$KEEP" == 1 ]]; then
    echo "[collab-golden] --keep set; leaving stack '$NAME' up (env: $GS env $NAME)"
  else
    echo "[collab-golden] tearing down stack '$NAME'..."
    "$GS" destroy "$NAME" >/dev/null 2>&1 || true
  fi
  return $rc
}
trap cleanup EXIT

echo "[collab-golden] === collaboration trio golden — name=$NAME slot=$SLOT repeat=$REPEAT ==="

# 1. Fresh isolated stack. destroy-then-up guarantees a freshly bootstrapped DB:
#    reusing a slot's old database silently skips bootstrap and the goldens then fail
#    for reasons that have nothing to do with the code under test.
echo "[collab-golden] 1/3 fresh stack (destroy prior + up --fresh-db)"
"$GS" destroy "$NAME" >/dev/null 2>&1 || true
"$GS" up "$NAME" --slot "$SLOT" --fresh-db --ttl 2h \
  || die "stack bring-up failed"

# 2. Export the Playwright env (PW_SKIP_WEBSERVER + base URL + backend + PG*).
echo "[collab-golden] 2/3 resolve stack env"
eval "$("$GS" env "$NAME")" || die "could not resolve stack env"
echo "[collab-golden]     base=$PLAYWRIGHT_BASE_URL backend=$BACKEND_URL"

# 2b. Pre-warm the two routes these goldens drive. `up`'s warm step only touches
#     /report-designer and /dashboard, so /inbox and /notifications are cold on the
#     first run: Vite compiles them on demand and the first navigation can outrun the
#     assertion timeouts. That made this gate flaky — 2 failures on a cold stack,
#     green on the next run once the Vite cache was populated. A gate that fails for
#     that reason teaches people to ignore it, so warm the routes explicitly.
echo "[collab-golden] 2b/3 pre-warm /inbox + /notifications (cold Vite compile)"
for route in /inbox /notifications; do
  curl --noproxy '*' -s -o /dev/null -m 120 "$PLAYWRIGHT_BASE_URL$route" \
    && echo "[collab-golden]     warmed $route" \
    || echo "[collab-golden]     WARN could not pre-warm $route (continuing)"
done

# 3. Run the goldens single-worker: the specs assert on list contents for the
#    signed-in user, so two workers mutating that user's notifications concurrently
#    would make the assertions race.
echo "[collab-golden] 3/3 run collab goldens single-worker (x$REPEAT)"
cd "$REPO_ROOT/web-admin" || die "web-admin not found"
set +e
NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test -c playwright.gt5.config.ts \
  "${SPECS[@]}" \
  --project=chromium --workers=1 --repeat-each="$REPEAT" --reporter=line
GOLDEN_RC=$?
set -e 2>/dev/null || true

if [[ "$GOLDEN_RC" == 0 ]]; then
  echo "[collab-golden] ============================================"
  echo "[collab-golden]   COLLAB TRIO GOLDEN: PASS  (name=$NAME slot=$SLOT)"
  echo "[collab-golden] ============================================"
else
  echo "[collab-golden] ############################################"
  echo "[collab-golden]   COLLAB TRIO GOLDEN: FAIL (rc=$GOLDEN_RC)"
  echo "[collab-golden]   artifacts: web-admin/test-results/"
  echo "[collab-golden]   (re-run with --keep to inspect the live stack)"
  echo "[collab-golden] ############################################"
fi

exit "$GOLDEN_RC"
