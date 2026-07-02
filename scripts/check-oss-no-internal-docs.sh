#!/usr/bin/env bash
# Fail if internal-process docs are tracked in the public OSS repo.
# Internal process docs belong in the team's private documentation repos.
set -euo pipefail

BANNED=(docs/backlog docs/handover docs/plans docs/retro docs/superpowers docs/mockups docs/standards)

tracked="$(git ls-files -- "${BANNED[@]}" 2>/dev/null || true)"
if [ -n "$tracked" ]; then
  echo "ERROR: internal-process docs must not be tracked in the public OSS repo:"
  echo "$tracked" | sed 's/^/  /'
  echo ""
  echo "Move them to the team's private documentation repo and remove them from this public repository."
  exit 1
fi
echo "OK: no internal-process docs tracked in OSS."
