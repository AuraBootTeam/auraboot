#!/usr/bin/env bash
# Fail if internal-process docs are tracked in the public OSS repo.
# Internal process docs live in auraboot-website (private) / auraboot-enterprise.
set -euo pipefail

BANNED=(docs/backlog docs/handover docs/plans docs/retro docs/superpowers docs/mockups docs/standards)

tracked="$(git ls-files -- "${BANNED[@]}" 2>/dev/null || true)"
if [ -n "$tracked" ]; then
  echo "ERROR: internal-process docs must not be tracked in the public OSS repo:"
  echo "$tracked" | sed 's/^/  /'
  echo ""
  echo "Move them to auraboot-website (private) or auraboot-enterprise, and remove from OSS."
  exit 1
fi
echo "OK: no internal-process docs tracked in OSS."
