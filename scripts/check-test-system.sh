#!/usr/bin/env bash
# Umbrella gate for the test system's own integrity. exit code = result.
#
# Answers one question: can this repo's test suite still be believed?
#   1. Does every spec actually run, or can one exist and never be selected?
#   2. Does every declared command have a UI entry point, or is its coverage
#      unreachable by construction?
#   3. Is the denominator still generated, or has someone started hand-keeping it
#      again in a markdown table that only ever drifts in the flattering direction?
#
# Both fail open by default in the underlying tooling, which is why they need a
# gate rather than a convention. Pre-existing violations are recorded in each
# gate's baseline file and warn; new ones fail.
#
# Owner has no CI — run before push and at release time.
#   ./scripts/check-test-system.sh
set -uo pipefail
cd "$(dirname "$0")/.."

status=0
for gate in check-e2e-spec-registration check-command-reachability check-coverage-manifest-freshness check-derived-field-writers check-hand-written-page-matrix check-scripts-index; do
  echo "───── $gate"
  if ! node "scripts/$gate.mjs" "$@"; then
    status=1
  fi
  echo
done

if [ "$status" -ne 0 ]; then
  echo "[test-system] FAIL — see the errors above."
  echo "Each gate accepts --update-baseline to record pre-existing debt, but that"
  echo "is for adopting the gate, not for silencing a violation you just created."
  exit 1
fi
echo "[test-system] PASS"
