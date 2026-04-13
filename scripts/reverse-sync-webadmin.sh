#!/bin/bash
# ─────────────────────────────────────────────────────────────
# Reverse-sync web-admin from enterprise repo → core (OSS) repo
#
# Rationale: The two web-admin directories diverged; enterprise is the
# single source of truth (Studio V2, Page Kind V2, DSL engine, etc).
# This script reverse-syncs B → A while excluding enterprise-only paths
# (see scripts/webadmin-enterprise-only.txt).
#
# Usage:
#   ./scripts/reverse-sync-webadmin.sh                 # dry-run (default)
#   ./scripts/reverse-sync-webadmin.sh --execute       # actually sync
#   ./scripts/reverse-sync-webadmin.sh --show-deletes  # dry-run, only show deletions
#
# Safety:
# - Dry-run by default. Review diff output before executing.
# - --delete removes files in dest not present in src (used to drop V1
#   dead code like Studio V1 / old Record/Transaction renderers).
# - Generated/ephemeral paths (node_modules, build, .react-router, .env)
#   are always excluded.
# ─────────────────────────────────────────────────────────────
set -euo pipefail

SRC="/Users/ghj/work/auraboot/auraboot-enterprise/web-admin/"
DST="/Users/ghj/work/auraboot/auraboot/web-admin/"
EXCLUDE_FILE="$(cd "$(dirname "$0")" && pwd)/webadmin-enterprise-only.txt"

MODE="${1:---dry-run}"

if [ ! -d "$SRC" ]; then echo "ERROR: source not found: $SRC" >&2; exit 1; fi
if [ ! -d "$DST" ]; then echo "ERROR: dest not found: $DST" >&2; exit 1; fi
if [ ! -f "$EXCLUDE_FILE" ]; then echo "ERROR: exclude file not found: $EXCLUDE_FILE" >&2; exit 1; fi

COMMON_EXCLUDES=(
  --exclude='.git/'
  --exclude='.DS_Store'
  --exclude='node_modules/'
  --exclude='build/'
  --exclude='.react-router/'
  --exclude='.playwright-mcp/'
  --exclude='.env'
  --exclude='.env.*'
  --exclude='test-results/'
  --exclude='playwright-report/'
  --exclude='tests/.auth/'
  --exclude='tests/storage/'
  --exclude='vite.config.ts.timestamp-*.mjs'
  --exclude='report.json'
  --exclude='extract-bad-tests.cjs'
)

case "$MODE" in
  --execute)
    echo "[execute] rsync $SRC → $DST"
    rsync -av --delete \
      "${COMMON_EXCLUDES[@]}" \
      --exclude-from="$EXCLUDE_FILE" \
      "$SRC" "$DST"
    echo "[done] core web-admin has been synced from enterprise baseline"
    ;;
  --show-deletes)
    echo "[dry-run] files that would be DELETED in core (not in enterprise src, excluding ent-only paths):"
    rsync -avn --delete \
      "${COMMON_EXCLUDES[@]}" \
      --exclude-from="$EXCLUDE_FILE" \
      "$SRC" "$DST" | grep '^deleting ' || echo "(no deletions)"
    ;;
  --dry-run|"")
    echo "[dry-run] preview of changes. Use --execute to apply."
    echo "[dry-run] source:  $SRC"
    echo "[dry-run] dest:    $DST"
    echo "[dry-run] exclude: $EXCLUDE_FILE"
    echo ""
    rsync -avn --delete \
      "${COMMON_EXCLUDES[@]}" \
      --exclude-from="$EXCLUDE_FILE" \
      "$SRC" "$DST" | tail -80
    echo ""
    echo "[dry-run] summary:"
    rsync -avn --delete \
      "${COMMON_EXCLUDES[@]}" \
      --exclude-from="$EXCLUDE_FILE" \
      "$SRC" "$DST" | awk '
        /^deleting / { del++; next }
        /^$/ { next }
        /^sending / { next }
        /^total size / { print; next }
        /^sent .* bytes/ { print; next }
        /\/$/ { dir++; next }
        { file++ }
        END {
          print "  files to update/create:", file+0
          print "  dirs to create:", dir+0
          print "  files to delete:", del+0
        }'
    ;;
  *)
    echo "Usage: $0 [--dry-run|--execute|--show-deletes]" >&2
    exit 1
    ;;
esac
