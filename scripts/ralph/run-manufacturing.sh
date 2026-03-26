#!/bin/bash
# Manufacturing Round 2 — Ralph Run Launcher
# Usage: ./scripts/ralph/run-manufacturing.sh [run_number]
#
# Run 1: MRP + APS DSL models (8 stories, config-only)
# Run 2: MES + Quality DSL models (8 stories, config-only)
# Run 3: MRP Core Engine Java (9 stories + tests)
# Run 4: APS Engine + MES/Quality Handlers (8 stories + tests)
# Run 5: Platform Enhancements + Integration (7 stories + tests)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

RUN_NUM=${1:-}

if [ -z "$RUN_NUM" ] || [ "$RUN_NUM" -lt 1 ] || [ "$RUN_NUM" -gt 5 ]; then
    echo "Usage: $0 <run_number>"
    echo ""
    echo "  Run 1: MRP + APS DSL models (8 stories)"
    echo "  Run 2: MES + Quality DSL models (8 stories)"
    echo "  Run 3: MRP Core Engine Java (9 stories)"
    echo "  Run 4: APS Engine + Handlers (8 stories)"
    echo "  Run 5: Platform + Integration (7 stories)"
    echo ""
    echo "Current prd.json status:"
    if [ -f "$SCRIPT_DIR/prd.json" ]; then
        echo "  Branch: $(jq -r '.branchName' "$SCRIPT_DIR/prd.json")"
        TOTAL=$(jq '.userStories | length' "$SCRIPT_DIR/prd.json")
        DONE=$(jq '[.userStories[] | select(.passes == true)] | length' "$SCRIPT_DIR/prd.json")
        echo "  Stories: $DONE/$TOTAL complete"
    else
        echo "  No prd.json loaded"
    fi
    exit 1
fi

PRD_FILE="$SCRIPT_DIR/prd-run${RUN_NUM}.json"

if [ ! -f "$PRD_FILE" ]; then
    echo "Error: $PRD_FILE not found"
    exit 1
fi

BRANCH=$(jq -r '.branchName' "$PRD_FILE")
DESC=$(jq -r '.description' "$PRD_FILE")
STORIES=$(jq '.userStories | length' "$PRD_FILE")

echo "========================================="
echo "  Manufacturing Round 2 — Run $RUN_NUM"
echo "========================================="
echo "Branch:  $BRANCH"
echo "Stories: $STORIES"
echo "Desc:    $DESC"
echo ""

# Copy to active prd.json
cp "$PRD_FILE" "$SCRIPT_DIR/prd.json"
echo "✓ Copied prd-run${RUN_NUM}.json → prd.json"

# Check and create branch
cd "$PROJECT_ROOT"
CURRENT=$(git branch --show-current)
if [ "$CURRENT" != "$BRANCH" ]; then
    if git rev-parse --verify "$BRANCH" >/dev/null 2>&1; then
        echo "✓ Branch $BRANCH exists, switching..."
        git checkout "$BRANCH"
    else
        echo "✓ Creating branch $BRANCH from phenix..."
        git checkout -b "$BRANCH" phenix
    fi
fi

echo ""
echo "Ready to run Ralph. Execute:"
echo ""
echo "  ./scripts/ralph/ralph.sh"
echo ""
echo "Monitor progress:"
echo ""
echo "  tail -f scripts/ralph/progress.txt"
echo ""
