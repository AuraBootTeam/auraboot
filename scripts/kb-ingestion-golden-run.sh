#!/usr/bin/env bash
#
# kb-ingestion-golden-run.sh — one command, whole knowledge-ingestion golden, exit code = verdict.
#
# Brings up an isolated host-first stack (zero docker), runs every knowledge-base ingestion golden
# against it, and tears the stack down again — including on failure. Nothing to set up, nothing left
# behind, no GitHub Actions.
#
#   ./scripts/kb-ingestion-golden-run.sh                 # run it
#   ./scripts/kb-ingestion-golden-run.sh --slot 7        # pick a runtime slot (default 2)
#   ./scripts/kb-ingestion-golden-run.sh --keep          # leave the stack up to inspect a failure
#
# What it covers (web-admin/tests/e2e/ai/knowledge-*-golden.spec.ts):
#   - PPTX / XLSX ingestion, including speaker notes and the reprocess path
#   - a deck whose charts are pictures — the scenario "upload the quarterly deck"  [needs DASHSCOPE_API_KEY]
#   - URL ingestion, and the SSRF refusals that guard it
#   - the embedding provider being selectable from the dialog
#   - vector retrieval, and chart understanding          [need DASHSCOPE_API_KEY]
#   - AuraBot answering from a document uploaded seconds earlier — "takes effect", end to end
#
# Optional environment — supply these and the suite covers more; leave them out and the tests that
# need them SKIP rather than fail, so a bare run is still a meaningful green:
#
#   DASHSCOPE_API_KEY               real embeddings + vision. Without it, chunks cannot be embedded
#                                   and no vision model can read a chart, so those goldens skip.
#   AURA_SSRF_ALLOWED_PRIVATE_HOSTS the URL goldens fetch a loopback fixture server, which
#                                   SsrfValidator refuses by design. Set to 127.0.0.1 to let the
#                                   fetch path run; the SSRF *refusal* tests run either way.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_NAME="kb-ingestion-golden"
SLOT=2
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot) SLOT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SPECS=(
  tests/e2e/ai/knowledge-ingestion-golden.spec.ts
  tests/e2e/ai/knowledge-url-ingestion-golden.spec.ts
  tests/e2e/ai/knowledge-provider-select-golden.spec.ts
  tests/e2e/ai/knowledge-vector-retrieval-golden.spec.ts
  tests/e2e/ai/knowledge-chart-vision-golden.spec.ts
  tests/e2e/ai/knowledge-deck-with-charts-golden.spec.ts
  tests/e2e/ai/knowledge-aurabot-answers-golden.spec.ts
)

teardown() {
  if [[ "$KEEP" == "1" ]]; then
    echo "[kb-golden] --keep: leaving '$STACK_NAME' up. Tear down with:"
    echo "[kb-golden]   ./scripts/oss-golden-stack.sh destroy $STACK_NAME"
    return
  fi
  echo "[kb-golden] tearing down '$STACK_NAME'"
  "$REPO_ROOT/scripts/oss-golden-stack.sh" destroy "$STACK_NAME" >/dev/null 2>&1 || true
}
# A failed run must not leave a stack holding the slot — that is how the next run inherits a stale
# database and reports someone else's state as its own.
trap teardown EXIT

# Report presence, never the value: this banner ends up in nightly logs and terminal scrollback,
# and `${VAR:-default}` prints the *value* when the variable is set — which would put the API key
# straight into them.
if [[ -n "${DASHSCOPE_API_KEY:-}" ]]; then
  KEY_STATE="SET"
else
  KEY_STATE="UNSET — vector + chart goldens will skip"
fi
if [[ "${AURA_SSRF_ALLOWED_PRIVATE_HOSTS:-}" == *127.0.0.1* ]]; then
  SSRF_STATE="allows 127.0.0.1"
else
  SSRF_STATE="not set — URL fetch goldens will skip; SSRF refusals still run"
fi

echo "[kb-golden] optional capabilities:"
echo "[kb-golden]   DASHSCOPE_API_KEY               = $KEY_STATE"
echo "[kb-golden]   AURA_SSRF_ALLOWED_PRIVATE_HOSTS = $SSRF_STATE"

# destroy-then-up: reusing a slot inherits its old database, and bootstrap is skipped on a database
# that already looks initialised — so the run would silently grade a stale environment.
echo "[kb-golden] bringing up isolated stack '$STACK_NAME' (slot $SLOT, host-first, zero docker)"
"$REPO_ROOT/scripts/oss-golden-stack.sh" destroy "$STACK_NAME" >/dev/null 2>&1 || true
"$REPO_ROOT/scripts/oss-golden-stack.sh" up "$STACK_NAME" --slot "$SLOT" --ttl 2h

cd "$REPO_ROOT/web-admin"
# shellcheck disable=SC2046
eval "$("$REPO_ROOT/scripts/oss-golden-stack.sh" env "$STACK_NAME")"

echo "[kb-golden] running ${#SPECS[@]} golden suites"
set +e
npx playwright test -c playwright.gt5.config.ts "${SPECS[@]}" --reporter=line
STATUS=$?
set -e

if [[ "$STATUS" -eq 0 ]]; then
  echo "[kb-golden] PASS"
else
  echo "[kb-golden] FAIL (exit $STATUS) — screenshots + traces under web-admin/test-results/"
fi
exit "$STATUS"
