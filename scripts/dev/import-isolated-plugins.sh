#!/usr/bin/env bash
#
# Compatibility wrapper for the old isolated-stack plugin import entrypoint.
# New scripts should call scripts/import-plugins.sh directly.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

normalize_slug() {
    local raw="$1"
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

has_slug=0
has_profile=0
has_explicit_plugin=0
for arg in "$@"; do
    case "$arg" in
        --slug=*) has_slug=1 ;;
        --profile=*) has_profile=1 ;;
        --help|-h) ;;
        --*) ;;
        *) has_explicit_plugin=1 ;;
    esac
done

args=()
if [ "$has_slug" -eq 0 ]; then
    branch="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)"
    if [ "$branch" = "HEAD" ]; then
        branch="$(basename "$PROJECT_ROOT")"
    fi
    args+=("--slug=$(normalize_slug "$branch")")
fi

if [ "$has_profile" -eq 0 ] && [ "$has_explicit_plugin" -eq 0 ]; then
    args+=("--profile=default")
fi

exec "$PROJECT_ROOT/scripts/import-plugins.sh" "${args[@]}" "$@"
