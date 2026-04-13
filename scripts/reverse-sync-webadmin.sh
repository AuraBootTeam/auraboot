#!/bin/bash
# Reverse-sync web-admin from enterprise repo → core (OSS) repo.
# Internal dev tool — only used by AuraBoot maintainers when both repos
# are checked out side-by-side. Not part of the OSS user surface.
#
# Expected repo layout:
#   <parent>/auraboot/             (this repo, OSS)
#   <parent>/auraboot-enterprise/  (private)
#
# See full docs in the original location.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PARENT="$(cd "$ROOT/.." && pwd)"

SRC="${PARENT}/auraboot-enterprise/web-admin/"
DST="${ROOT}/web-admin/"
EXCLUDE_FILE="${SCRIPT_DIR}/webadmin-enterprise-only.txt"

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
