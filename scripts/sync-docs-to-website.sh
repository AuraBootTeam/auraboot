#!/usr/bin/env bash
# Sync OSS docs into the auraboot-website site so the public docs site
# stays in lockstep with the code repo.
#
# Source:  auraboot/docs/{community,getting-started,system-reference,...}
# Target:  auraboot-website/site/src/content/docs/
#
# Usage:
#   ./scripts/sync-docs-to-website.sh                # dry run
#   ./scripts/sync-docs-to-website.sh --apply        # actually copy
#   ./scripts/sync-docs-to-website.sh --apply --commit  # also commit & push website
#
# Run from auraboot/ root. The website repo must be at sibling path
# ../auraboot-website (default) or set WEBSITE_PATH env.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEBSITE_PATH="${WEBSITE_PATH:-$(cd "$REPO_ROOT/.." && pwd)/auraboot-website}"
TARGET_DIR="$WEBSITE_PATH/site/src/content/docs"

APPLY=0
COMMIT=0
for arg in "$@"; do
  case "$arg" in
    --apply) APPLY=1 ;;
    --commit) COMMIT=1 ;;
    --help|-h)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *) echo "Unknown arg: $arg"; exit 1 ;;
  esac
done

cd "$REPO_ROOT"

echo "==> Sync OSS docs to website"
echo "    source: $REPO_ROOT/docs/"
echo "    target: $TARGET_DIR"
echo "    apply:  $APPLY"
echo "    commit: $COMMIT"
echo ""

if [ ! -d "$WEBSITE_PATH" ]; then
  echo "ERROR: website repo not found at $WEBSITE_PATH"
  echo "       Set WEBSITE_PATH env or clone the website repo as a sibling"
  exit 1
fi

# Sections to sync. Map: <source-path>:<target-subdir>
# Only public-safe sections — skip plans, backlog, agent-rules, internal handover.
SECTIONS=(
  "docs/getting-started:getting-started"
  "docs/community:community"
  "docs/architecture:architecture"
  "docs/core-concepts:core-concepts"
  "docs/guides:guides"
  "docs/api-reference:api"
  "docs/deployment:deployment"
  "docs/plugin-development:plugin-development"
  "docs/use-cases:use-cases"
)

# Patterns we never publish (internal-only)
EXCLUDE_GLOBS=(
  '*.draft.md'
  'INTERNAL-*'
  '*-INTERNAL.md'
  'TODO*.md'
)

RSYNC_OPTS=(-a --delete --prune-empty-dirs)
[ $APPLY -eq 0 ] && RSYNC_OPTS+=(--dry-run --itemize-changes)

# Honor includes (only .md and .mdx) and excludes
RSYNC_OPTS+=(--include='*/' --include='*.md' --include='*.mdx' --include='*.png' --include='*.jpg' --include='*.svg')
for pat in "${EXCLUDE_GLOBS[@]}"; do
  RSYNC_OPTS+=(--exclude="$pat")
done
RSYNC_OPTS+=(--exclude='*')

mkdir -p "$TARGET_DIR"

TOTAL=0
for spec in "${SECTIONS[@]}"; do
  src="${spec%%:*}"
  sub="${spec##*:}"

  if [ ! -d "$src" ]; then
    echo "    skip  (no source): $src"
    continue
  fi

  echo "--> $src  →  docs/$sub"
  if [ $APPLY -eq 1 ]; then
    rsync "${RSYNC_OPTS[@]}" "$src/" "$TARGET_DIR/$sub/"
  else
    rsync "${RSYNC_OPTS[@]}" "$src/" "$TARGET_DIR/$sub/" | head -10
  fi
  count=$(find "$src" -name '*.md' -o -name '*.mdx' 2>/dev/null | wc -l | tr -d ' ')
  TOTAL=$((TOTAL + count))
done

echo ""
echo "==> $TOTAL .md/.mdx files in scope"

# Ensure each top-level synced section has an _index.md placeholder so
# the docs nav shows the section even if empty.
if [ $APPLY -eq 1 ]; then
  for spec in "${SECTIONS[@]}"; do
    sub="${spec##*:}"
    if [ -d "$TARGET_DIR/$sub" ] && [ ! -f "$TARGET_DIR/$sub/_index.md" ]; then
      cat > "$TARGET_DIR/$sub/_index.md" <<EOF
---
title: ${sub//-/ }
sidebar_position: 1
---
EOF
    fi
  done
fi

if [ $COMMIT -eq 1 ] && [ $APPLY -eq 1 ]; then
  echo ""
  echo "==> Commit + push website"
  cd "$WEBSITE_PATH"
  if [ -n "$(git status --porcelain $TARGET_DIR)" ]; then
    git add "$TARGET_DIR"
    OSS_SHA=$(cd "$REPO_ROOT" && git rev-parse --short HEAD)
    git commit -m "docs: sync from auraboot@$OSS_SHA"
    git push origin main
  else
    echo "    nothing to commit"
  fi
fi

echo ""
if [ $APPLY -eq 0 ]; then
  echo "Dry-run complete. Re-run with --apply to actually sync."
else
  echo "✅ Sync complete."
fi
