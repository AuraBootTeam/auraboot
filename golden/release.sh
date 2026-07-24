#!/usr/bin/env bash
# Release-tier hook for the OSS golden gate.
#
# Unlike a domain with one shared stack, every runner here is independently
# self-contained: it brings up its own host-first stack (zero docker, slot-isolated),
# runs, and tears the stack down again — including on failure. So this hook does not
# bring anything up; it dispatches.
#
# The engine passes GOLDEN_GATE_RELEASE_SUITES (space-separated suite ids). Honouring
# it is not optional here: without it, `--changed` on a single RBAC file would stand up
# every stack this repo owns, one after another, which is the same as having no
# --changed at all. Empty (or unset) means "all of them" — the --full case.
#
# Runners execute sequentially. They each reset and tear down, so a shared slot is
# fine; running them concurrently is not.
#
#   golden/release.sh                                   # everything
#   GOLDEN_GATE_RELEASE_SUITES="OSS-REL-RBAC" golden/release.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
cd "$REPO"

# id -> runner. This map and the catalog's release suites are two lists that must agree,
# and two lists that must agree drift. So they are compared at startup rather than by
# convention — a suite added to the catalog with no runner here would otherwise be
# silently skipped, which is the failure this whole exercise exists to remove.
runner_for() {
  case "$1" in
    OSS-REL-RBAC)                   echo "./scripts/rbac-golden-run.sh" ;;
    OSS-REL-AURABOT)                echo "./scripts/aurabot-scenario-golden-run.sh" ;;
    OSS-REL-DIGITAL-EMPLOYEE)       echo "./scripts/digital-employee-golden-run.sh" ;;
    OSS-REL-FAQ-LOOP)               echo "./scripts/faq-loop-golden-run.sh" ;;
    OSS-REL-KB-INGESTION)           echo "./scripts/kb-ingestion-golden-run.sh" ;;
    OSS-REL-QUICK-FILTER)           echo "./scripts/quick-filter-chip-golden-run.sh" ;;
    OSS-REL-SUSPENDED-TENANT-API)   echo "./scripts/suspended-tenant-login-golden.sh" ;;
    OSS-REL-SUSPENDED-TENANT-UI)    echo "./scripts/suspended-tenant-login-ui-golden.sh" ;;
    OSS-REL-OEE-DASHBOARD)          echo "./scripts/host-oee-dashboard-golden.sh" ;;
    *) return 1 ;;
  esac
}

ALL="OSS-REL-RBAC OSS-REL-AURABOT OSS-REL-DIGITAL-EMPLOYEE OSS-REL-FAQ-LOOP OSS-REL-KB-INGESTION OSS-REL-QUICK-FILTER OSS-REL-SUSPENDED-TENANT-API OSS-REL-SUSPENDED-TENANT-UI OSS-REL-OEE-DASHBOARD"

# Drift check: every release suite in the catalog must have a runner here, and vice
# versa. Without it, adding a suite to the catalog and forgetting this file produces a
# silent no-op that looks exactly like a pass.
CATALOG_IDS="$(node -e "
  const c = require('$HERE/test-catalog.json');
  process.stdout.write((c.suites||[]).filter(s=>s.tier==='release').map(s=>s.id).sort().join(' '));
")"
MAP_IDS="$(printf '%s\n' $ALL | sort | tr '\n' ' ' | sed 's/ $//')"
if [ "$CATALOG_IDS" != "$MAP_IDS" ]; then
  echo "[release] FAIL: catalog release suites and this dispatch map disagree."
  echo "  catalog: $CATALOG_IDS"
  echo "  release.sh: $MAP_IDS"
  exit 1
fi

SELECTED="${GOLDEN_GATE_RELEASE_SUITES:-}"
[ -z "$SELECTED" ] && SELECTED="$ALL"

status=0
ran=(); failed=(); skipped=()

for id in $SELECTED; do
  cmd="$(runner_for "$id")" || {
    echo "[release] FAIL: unknown release suite id '$id' — golden/release.sh and test-catalog.json disagree"
    status=1
    continue
  }

  # OEE deliberately does not start or reset services (see its header): it asserts
  # against a stack someone else brought up. Running it blind would fail for want of a
  # stack and read as a product regression, so it is opt-in. A skip is printed, never
  # folded into the green line.
  if [ "$id" = "OSS-REL-OEE-DASHBOARD" ] && [ "${OSS_GOLDEN_OEE_STACK:-}" != "1" ]; then
    echo "[release] SKIP $id — needs an already-running host stack; set OSS_GOLDEN_OEE_STACK=1 to run it. NOT green."
    skipped+=("$id")
    continue
  fi

  echo ""
  echo "───────────── $id"
  echo "  \$ $cmd"
  if bash -c "$cmd"; then ran+=("$id"); else status=1; failed+=("$id"); fi
done

echo ""
echo "==== OSS release goldens ===="
echo "  passed : ${#ran[@]} ${ran[*]+(${ran[*]})}"
echo "  failed : ${#failed[@]} ${failed[*]+(${failed[*]})}"
echo "  skipped: ${#skipped[@]} ${skipped[*]+(${skipped[*]})}  <- not green, just not run"
exit "$status"
