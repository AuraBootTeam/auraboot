#!/bin/bash
#
# Targeted cleanup for one AuraBoot compose stack.
#
# Defaults to dry-run. This script never runs global docker system prune.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

SLUG=""
REMOVE_VOLUMES=0
REMOVE_IMAGES=0
APPLY=0

usage() {
    cat <<USAGE
Usage: $0 --slug=<name> [--volumes] [--images] [--apply] [--dry-run] [--help]

Options:
  --slug=<name>  Required stack slug. Cleans compose project auraboot-<slug>.
  --volumes      Also remove project-scoped named volumes via docker compose down --volumes.
  --images       Remove images whose repository starts with auraboot-<slug>.
  --apply        Execute cleanup. Default is dry-run.
  --dry-run      Print commands only.
  --help         Show this message.

This is targeted cleanup only. It never runs docker system prune.
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
        --slug=*) SLUG="$(normalize_slug "${arg#--slug=}")" ;;
        --volumes) REMOVE_VOLUMES=1 ;;
        --images) REMOVE_IMAGES=1 ;;
        --apply) APPLY=1 ;;
        --dry-run) APPLY=0 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

if [ -z "$SLUG" ]; then
    echo "ERROR: --slug=<name> is required" >&2
    usage
    exit 2
fi

PROJECT_NAME="auraboot-${SLUG}"
DOWN_FLAGS=()
if [ "$REMOVE_VOLUMES" = "1" ]; then
    DOWN_FLAGS+=(--volumes)
fi

echo "Targeted cleanup plan"
echo "  slug:         $SLUG"
echo "  project:      $PROJECT_NAME"
echo "  volumes:      $([ "$REMOVE_VOLUMES" = "1" ] && echo remove || echo preserve)"
echo "  images:       $([ "$REMOVE_IMAGES" = "1" ] && echo remove-matching || echo preserve)"
echo "  mode:         $([ "$APPLY" = "1" ] && echo apply || echo dry-run)"
echo ""

DOWN_CMD=(
    docker compose
    -p "$PROJECT_NAME"
    -f docker-compose.yml
    -f docker-compose.isolated.yml
    --profile isolated
    --profile cache
    --profile storage
    --profile playwright-runner
    --profile production-like
    down
)
if [ "${#DOWN_FLAGS[@]}" -gt 0 ]; then
    DOWN_CMD+=("${DOWN_FLAGS[@]}")
fi

echo "+ ${DOWN_CMD[*]}"

IMAGE_IDS=()
while IFS= read -r image_id; do
    [ -n "$image_id" ] && IMAGE_IDS+=("$image_id")
done < <(
    docker image ls --format '{{.Repository}}\t{{.ID}}' 2>/dev/null \
        | awk -v prefix="$PROJECT_NAME" '$1 ~ "^" prefix { print $2 }' \
        | sort -u
)

if [ "$REMOVE_IMAGES" = "1" ]; then
    if [ "${#IMAGE_IDS[@]}" -gt 0 ]; then
        echo "+ docker image rm ${IMAGE_IDS[*]}"
    else
        echo "# no matching images for repository prefix $PROJECT_NAME"
    fi
fi

if [ "$APPLY" != "1" ]; then
    echo ""
    echo "(dry-run mode: pass --apply to execute)"
    exit 0
fi

cd "$PROJECT_ROOT"
"${DOWN_CMD[@]}"

if [ "$REMOVE_IMAGES" = "1" ] && [ "${#IMAGE_IDS[@]}" -gt 0 ]; then
    docker image rm "${IMAGE_IDS[@]}"
fi

echo "Cleanup complete for $PROJECT_NAME."
