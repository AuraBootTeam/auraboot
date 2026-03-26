#!/usr/bin/env bash
# Regenerate the agent contract baseline files used by AgentContractSnapshotTest.
# Run this after any intentional change to agent/meta models or the tool schema.
#
# Usage:
#   bash scripts/regenerate-agent-contract-baseline.sh
#
# After running, review the diff and commit:
#   git add platform/src/test/resources/agent-contract-baseline.json \
#           platform/src/test/resources/agent-contract-schema-baseline.json
#   git commit -m "chore(agent): regenerate contract baseline"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PLATFORM_DIR="${REPO_ROOT}/platform"
RESOURCES_DIR="${PLATFORM_DIR}/src/test/resources"

echo "==> Removing stale baseline files..."
rm -f "${RESOURCES_DIR}/agent-contract-baseline.json"
rm -f "${RESOURCES_DIR}/agent-contract-schema-baseline.json"

echo "==> Running AgentContractSnapshotTest to regenerate baseline..."
cd "${PLATFORM_DIR}"
./gradlew --no-daemon test \
  --tests "com.auraboot.framework.agent.AgentContractSnapshotTest" \
  -Dspring.profiles.active=integration-test

echo ""
echo "==> Done. New baseline files generated:"
ls -lh "${RESOURCES_DIR}/agent-contract-baseline.json" 2>/dev/null \
  && echo "    agent-contract-baseline.json" \
  || echo "    WARNING: agent-contract-baseline.json was NOT created (no active tools?)"
ls -lh "${RESOURCES_DIR}/agent-contract-schema-baseline.json" 2>/dev/null \
  && echo "    agent-contract-schema-baseline.json" \
  || echo "    NOTE: agent-contract-schema-baseline.json not present (may be expected on first run)"

echo ""
echo "==> Review the diff, then commit:"
echo "    git diff platform/src/test/resources/"
echo "    git add platform/src/test/resources/agent-contract-baseline.json \\"
echo "            platform/src/test/resources/agent-contract-schema-baseline.json"
echo "    git commit -m 'chore(agent): regenerate contract baseline'"
