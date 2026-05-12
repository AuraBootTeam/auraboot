#!/bin/bash
#
# Targeted cleanup for slug/date-scoped Playwright artifacts.
#
# Defaults to dry-run. Only removes directories created by the new isolated
# env contract:
#   web-admin/test-results/runs/<slug>/<run-id>
#   web-admin/tests/storage/<slug>/<run-id>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WEB_ADMIN_DIR="$PROJECT_ROOT/web-admin"

DAYS=14
SLUG=""
APPLY=0

usage() {
    cat <<USAGE
Usage: $0 [--days=<N>] [--slug=<name>] [--apply] [--dry-run] [--help]

Options:
  --days=<N>     Remove run directories older than N days. Default: 14.
  --slug=<name>  Limit cleanup to one slug.
  --apply        Execute cleanup. Default is dry-run.
  --dry-run      Print commands only.
  --help         Show this message.

This script only targets slug/date-scoped artifacts and storageState dirs.
It does not remove legacy test-results, reports, screenshots, or ga storage.
USAGE
}

normalize_slug() {
    local raw="$1"
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

for arg in "$@"; do
    case "$arg" in
        --days=*) DAYS="${arg#--days=}" ;;
        --slug=*) SLUG="$(normalize_slug "${arg#--slug=}")" ;;
        --apply) APPLY=1 ;;
        --dry-run) APPLY=0 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

if ! [[ "$DAYS" =~ ^[0-9]+$ ]]; then
    echo "ERROR: --days must be a non-negative integer, got '$DAYS'" >&2
    exit 2
fi

RUNS_ROOT="$WEB_ADMIN_DIR/test-results/runs"
STORAGE_ROOT="$WEB_ADMIN_DIR/tests/storage"

collect_candidates() {
    local root="$1"
    local base
    if [ -n "$SLUG" ]; then
        base="$root/$SLUG"
    else
        base="$root"
    fi
    [ -d "$base" ] || return 0

    if [ -n "$SLUG" ]; then
        find "$base" -mindepth 1 -maxdepth 1 -type d -mtime +"$DAYS" 2>/dev/null
    else
        find "$base" -mindepth 2 -maxdepth 2 -type d -mtime +"$DAYS" 2>/dev/null
    fi
}

CANDIDATES=()
while IFS= read -r path; do
    [ -n "$path" ] && CANDIDATES+=("$path")
done < <(
    {
        collect_candidates "$RUNS_ROOT"
        collect_candidates "$STORAGE_ROOT"
    } | sort -u
)

echo "Artifact cleanup plan"
echo "  days:    $DAYS"
echo "  slug:    ${SLUG:-all}"
echo "  mode:    $([ "$APPLY" = "1" ] && echo apply || echo dry-run)"
echo "  roots:"
echo "    $RUNS_ROOT"
echo "    $STORAGE_ROOT"
echo ""

if [ "${#CANDIDATES[@]}" -eq 0 ]; then
    echo "# no matching artifact directories"
else
    for path in "${CANDIDATES[@]}"; do
        echo "+ rm -rf $path"
    done
fi

if [ "$APPLY" != "1" ]; then
    echo ""
    echo "(dry-run mode: pass --apply to execute)"
    exit 0
fi

if [ "${#CANDIDATES[@]}" -gt 0 ]; then
    for path in "${CANDIDATES[@]}"; do
        rm -rf "$path"
    done
fi

echo "Artifact cleanup complete."
