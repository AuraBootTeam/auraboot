#!/usr/bin/env bash
# Thin wrapper over oss-test.sh for BPM / workflow-designer E2E suites.
#
# Usage:
#   ./scripts/run-wf-e2e.sh <suite>
#
# Suites:
#   smoke     - tests/e2e/bpm-smoke
#   designer  - tests/e2e/bpm-designer
#   runtime   - tests/e2e/workflow-demo
#   all       - all three suites above
#
# Environment:
#   SKIP_RESET=true   Skip oss-reset-and-init.sh (default: reset runs)
#
# Examples:
#   ./scripts/run-wf-e2e.sh smoke
#   SKIP_RESET=true ./scripts/run-wf-e2e.sh designer
#   ./scripts/run-wf-e2e.sh all

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

usage() {
  echo "Usage: $0 <suite>" >&2
  echo "  suite: smoke | designer | runtime | all" >&2
  echo "" >&2
  echo "  SKIP_RESET=true  skip oss-reset-and-init.sh" >&2
  exit 1
}

SUITE="${1:-}"
if [[ -z "$SUITE" ]]; then
  usage
fi

case "$SUITE" in
  smoke)
    GLOBS=("tests/e2e/bpm-smoke")
    ;;
  designer)
    GLOBS=("tests/e2e/bpm-designer")
    ;;
  runtime)
    GLOBS=("tests/e2e/workflow-demo")
    ;;
  all)
    GLOBS=("tests/e2e/bpm-smoke" "tests/e2e/bpm-designer" "tests/e2e/workflow-demo")
    ;;
  *)
    echo "ERROR: unknown suite '$SUITE'" >&2
    usage
    ;;
esac

# Optionally run reset-and-init
SKIP_RESET="${SKIP_RESET:-false}"
if [[ "$SKIP_RESET" != "true" ]]; then
  echo "=== Running oss-reset-and-init.sh ==="
  bash "$SCRIPT_DIR/oss-reset-and-init.sh"
  echo ""
fi

echo "=== Delegating to oss-test.sh | suite=$SUITE globs=${GLOBS[*]} ==="
export OSS_TEST_SKIP_DEEP="${OSS_TEST_SKIP_DEEP:-true}"
exec bash "$SCRIPT_DIR/oss-test.sh" "${GLOBS[@]}"
