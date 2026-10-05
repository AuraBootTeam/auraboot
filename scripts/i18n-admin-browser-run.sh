#!/usr/bin/env bash
# Real-stack browser acceptance. Preserve the owned namespace and evidence.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GS="$SCRIPT_DIR/oss-golden-stack.sh"
NAME="${1:-ios-i18n-ci-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
BASELINE_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  echo 'ERROR: browser acceptance requires a clean committed source' >&2
  exit 2
fi
if "$GS" env "$NAME" >/dev/null 2>&1; then
  echo "ERROR: runtime $NAME already exists; refusing prior data" >&2
  exit 2
fi
trap 'echo "[i18n-browser] retained runtime and evidence: $NAME"' EXIT
# Exercise the documented placeholder strategy without paid provider calls.
export AGENT_LLM_STUB_MODE=true
# The runtime registry enforces one non-parallel task runtime per source identity. The
# retained rbac-golden-nightly qualification stack shares this checkout root, and every
# acceptance run owns a unique runtime name, so declare the genuinely parallel purpose
# instead of failing allocation.
"$GS" up "$NAME" --slot auto --ttl 2h --no-warm --runtime-mode verification --system-mode multi \
  --parallel-reason "ios i18n admin acceptance alongside retained rbac-golden-nightly stack" || exit 2
"$GS" import "$NAME" || exit 2
eval "$("$GS" env "$NAME")"
mkdir -p "$PW_REPORT_DIR"
[[ ! -e "$PW_RESULTS_JSON" && ! -e "$PW_REPORT_DIR/i18n-browser-receipt.json" ]] || exit 2
export PLAYWRIGHT_JSON_OUTPUT_FILE="$PW_RESULTS_JSON"
cd "$REPO_ROOT/web-admin"
set +e
pnpm exec playwright test tests/e2e/i18n/i18n-admin-workflow.real.spec.ts \
  --project chromium --no-deps --retries 0 --reporter line,json
TEST_RC=$?
set -e
[[ "$TEST_RC" == 0 ]] || exit 1
node "$SCRIPT_DIR/i18n-admin-browser-result.mjs" "$PW_RESULTS_JSON" "$NAME" "$REPO_ROOT" "$BASELINE_COMMIT" || exit 1
echo '[i18n-browser] five browser cases and one tenant-isolation API case passed; original screenshot review remains separate'
