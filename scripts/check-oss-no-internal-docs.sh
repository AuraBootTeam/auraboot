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

# Content-level patterns that indicate internal artifacts or personal
# environment details leaked into tracked files.
leaks="$(git grep -InE '/Users/[a-z][a-z0-9]*/|浙ICP备[0-9]' -- . 2>/dev/null || true)"
if [ -n "$leaks" ]; then
  echo "ERROR: tracked files contain local-machine paths or real ICP record numbers:"
  echo "$leaks" | sed 's/^/  /'
  echo ""
  echo "Scrub the identity details (use ~/, \$HOME, or neutral placeholders) or move"
  echo "the file to the team's private documentation repo."
  exit 1
fi
echo "OK: no internal-process docs tracked in OSS."
