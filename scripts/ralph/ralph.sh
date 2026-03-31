#!/bin/bash
# Ralph × Superpowers — AuraMeta Autonomous Agent Loop
# Usage: ./scripts/ralph/ralph.sh [--yes] [--status] [max_iterations]
#
# Prerequisites:
#   1. prd.json must exist in scripts/ralph/
#   2. Backend + Frontend services should be running (for E2E stories)
#   3. Claude Code CLI must be installed
#
# Examples:
#   ./scripts/ralph/ralph.sh              # Default: 10 iterations
#   ./scripts/ralph/ralph.sh 20           # Up to 20 iterations
#   ./scripts/ralph/ralph.sh 1            # Single iteration (dry run)
#   ./scripts/ralph/ralph.sh --yes 5      # Skip service check confirmation
#   ./scripts/ralph/ralph.sh --status     # Show current PRD progress without starting

set -e

MAX_ITERATIONS=10
SKIP_CONFIRM=false
STATUS_ONLY=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --yes|-y)
      SKIP_CONFIRM=true
      shift
      ;;
    --status|-s)
      STATUS_ONLY=true
      shift
      ;;
    *)
      if [[ "$1" =~ ^[0-9]+$ ]]; then
        MAX_ITERATIONS="$1"
      fi
      shift
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PRD_FILE="$SCRIPT_DIR/prd.json"
PROGRESS_FILE="$SCRIPT_DIR/progress.txt"
ARCHIVE_DIR="$SCRIPT_DIR/archive"
LAST_BRANCH_FILE="$SCRIPT_DIR/.last-branch"
LOG_DIR="$SCRIPT_DIR/logs"

# Validate prerequisites
if [ ! -f "$PRD_FILE" ]; then
  echo "Error: prd.json not found at $PRD_FILE"
  echo "Create a prd.json first (use Superpowers brainstorming + writing-plans → prd.json)"
  exit 1
fi

if ! command -v claude &> /dev/null; then
  echo "Error: Claude Code CLI not found. Install it first."
  exit 1
fi

if ! command -v jq &> /dev/null; then
  echo "Error: jq not found. Install it: brew install jq"
  exit 1
fi

# Archive previous run if branch changed
if [ -f "$LAST_BRANCH_FILE" ]; then
  CURRENT_BRANCH=$(jq -r '.branchName // empty' "$PRD_FILE" 2>/dev/null || echo "")
  LAST_BRANCH=$(cat "$LAST_BRANCH_FILE" 2>/dev/null || echo "")

  if [ -n "$CURRENT_BRANCH" ] && [ -n "$LAST_BRANCH" ] && [ "$CURRENT_BRANCH" != "$LAST_BRANCH" ]; then
    DATE=$(date +%Y-%m-%d)
    FOLDER_NAME=$(echo "$LAST_BRANCH" | sed 's|^ralph/||')
    ARCHIVE_FOLDER="$ARCHIVE_DIR/$DATE-$FOLDER_NAME"

    echo "Archiving previous run: $LAST_BRANCH → $ARCHIVE_FOLDER"
    mkdir -p "$ARCHIVE_FOLDER"
    [ -f "$PRD_FILE" ] && cp "$PRD_FILE" "$ARCHIVE_FOLDER/"
    [ -f "$PROGRESS_FILE" ] && cp "$PROGRESS_FILE" "$ARCHIVE_FOLDER/"

    # Reset progress file
    cat > "$PROGRESS_FILE" <<'INITPROGRESS'
# Ralph Progress Log — AuraMeta

## Codebase Patterns
- Use MyBatis Mapper, not JdbcTemplate
- Dynamic tables (mt_*) have no deleted_flag
- Always NO_PROXY=localhost for curl/Playwright
- Check table schema before writing SQL
- DSL-first: use Page Designer / Command / Model for business pages

---
INITPROGRESS
  fi
fi

# Track current branch
CURRENT_BRANCH=$(jq -r '.branchName // empty' "$PRD_FILE" 2>/dev/null || echo "")
if [ -n "$CURRENT_BRANCH" ]; then
  echo "$CURRENT_BRANCH" > "$LAST_BRANCH_FILE"
fi

# Initialize progress file if needed
if [ ! -f "$PROGRESS_FILE" ]; then
  cat > "$PROGRESS_FILE" <<'INITPROGRESS'
# Ralph Progress Log — AuraMeta

## Codebase Patterns
- Use MyBatis Mapper, not JdbcTemplate
- Dynamic tables (mt_*) have no deleted_flag
- Always NO_PROXY=localhost for curl/Playwright
- Check table schema before writing SQL
- DSL-first: use Page Designer / Command / Model for business pages

---
INITPROGRESS
fi

# Create log directory
mkdir -p "$LOG_DIR"

# Check services
BACKEND_OK="no"
FRONTEND_OK="no"

BACKEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:6443/actuator/health 2>/dev/null || echo "000")
if [ "$BACKEND_STATUS" = "200" ]; then
  BACKEND_OK="yes"
fi

FRONTEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:5173 2>/dev/null || echo "000")
if [ "$FRONTEND_STATUS" = "200" ] || [ "$FRONTEND_STATUS" = "304" ]; then
  FRONTEND_OK="yes"
fi

# Show PRD summary
TOTAL=$(jq '.userStories | length' "$PRD_FILE")
DONE=$(jq '[.userStories[] | select(.passes == true)] | length' "$PRD_FILE")
PROJECT=$(jq -r '.project // "unknown"' "$PRD_FILE")
BRANCH=$(jq -r '.branchName // "unknown"' "$PRD_FILE")

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Ralph × Superpowers — AuraMeta                            ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  Project:    $PROJECT"
echo "║  Branch:     $BRANCH"
echo "║  Stories:    $DONE / $TOTAL complete"
echo "║  Max Iter:   $MAX_ITERATIONS"
echo "║  Discipline: TDD + Systematic Debugging + Verification"
echo "║  Backend:    $BACKEND_OK (port 6443)"
echo "║  Frontend:   $FRONTEND_OK (port 5173)"
echo "╚══════════════════════════════════════════════════════════════╝"

# --status mode: show per-story progress and exit
if [ "$STATUS_ONLY" = true ]; then
  echo ""
  echo "Story Progress:"
  echo ""
  jq -r '.userStories[] | "  " + (if .passes then "✅" else "⬜" end) + " [" + .id + "] " + .title' "$PRD_FILE"
  echo ""
  if [ "$DONE" -eq "$TOTAL" ]; then
    echo "All stories complete! Run '/aura-ralph finish' in Claude Code to review."
  else
    echo "$DONE / $TOTAL stories complete. $((TOTAL - DONE)) remaining."
  fi
  exit 0
fi

if [ "$BACKEND_OK" = "no" ] || [ "$FRONTEND_OK" = "no" ]; then
  echo ""
  echo "  Warning: Some services are not running."
  echo "   Backend (6443):  $BACKEND_OK"
  echo "   Frontend (5173): $FRONTEND_OK"
  echo "   E2E stories will fail without running services."
  echo "   Start with: cd platform && ./gradlew bootRun"
  echo "              cd web-admin && pnpm dev:full"
  echo ""
  if [ "$SKIP_CONFIRM" = true ]; then
    echo "  --yes flag set, continuing..."
  else
    read -p "Continue anyway? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
      echo "Aborted. Start services first."
      exit 1
    fi
  fi
fi
echo ""

if [ "$DONE" -eq "$TOTAL" ]; then
  echo "All stories already complete! Nothing to do."
  exit 0
fi

# ============================================================
#  parse_stream: Real-time progress display from stream-json
#  Shows tool calls, text output, and errors as they happen
# ============================================================
parse_stream() {
  local logfile="$1"

  while IFS= read -r line; do
    # Save raw JSON to log
    echo "$line" >> "$logfile"

    # Try to parse as JSON — skip non-JSON lines
    type=$(echo "$line" | jq -r '.type // empty' 2>/dev/null) || continue
    [ -z "$type" ] && continue

    case "$type" in
      assistant)
        # Extract content blocks (text or tool_use)
        echo "$line" | jq -r '
          .message.content[]? //
          .content[]? |
          if .type == "tool_use" then
            "  >> " + .name +
            (if .input.file_path then "  " + .input.file_path
             elif .input.command then "  $ " + (.input.command | split("\n")[0] | .[0:120])
             elif .input.pattern then "  ~ " + .input.pattern
             elif .input.query then "  ? " + .input.query
             elif .input.content then "  (write " + (.input.file_path // "file") + ")"
             else ""
             end)
          elif .type == "text" then
            .text
          else empty
          end
        ' 2>/dev/null
        ;;
      result)
        # Final result
        echo ""
        echo "  -- iteration result received --"
        ;;
    esac
  done
}

# ============================================================
#  Main loop
# ============================================================
for i in $(seq 1 $MAX_ITERATIONS); do
  DONE=$(jq '[.userStories[] | select(.passes == true)] | length' "$PRD_FILE")
  REMAINING=$((TOTAL - DONE))
  LOGFILE="$LOG_DIR/iteration-$i.jsonl"
  TIMESTAMP=$(date +%Y%m%d-%H%M%S)

  echo ""
  echo "==========================================================="
  echo "  Iteration $i / $MAX_ITERATIONS  |  $DONE/$TOTAL done  |  $REMAINING remaining"
  echo "  Log: $LOGFILE"
  echo "  Started: $TIMESTAMP"
  echo "==========================================================="
  echo ""

  # Clear log file
  > "$LOGFILE"

  # Read CLAUDE.md prompt
  PROMPT=$(cat "$SCRIPT_DIR/CLAUDE.md")

  # Run Claude Code with stream-json for real-time output
  # - env -u CLAUDECODE: allow launching inside existing session
  # - --print: non-interactive mode
  # - --output-format stream-json: stream events as JSON lines
  cd "$PROJECT_ROOT" && env -u CLAUDECODE claude \
    --print \
    --verbose \
    --output-format stream-json \
    --dangerously-skip-permissions \
    -p "$PROMPT" \
    2>>"$LOG_DIR/iteration-$i.stderr" \
    | parse_stream "$LOGFILE" \
    || true

  echo ""
  echo "  Iteration $i finished at $(date +%H:%M:%S)"

  # Check for completion signal in log
  if grep -q "COMPLETE" "$LOGFILE" 2>/dev/null; then
    echo ""
    echo "==========================================================="
    echo "  Ralph completed all stories!"
    echo "  Finished at iteration $i of $MAX_ITERATIONS"
    echo "==========================================================="
    echo ""
    echo "  Next step: Run '/aura-ralph finish' in Claude Code to review."
    echo ""
    exit 0
  fi

  # Show quick status
  DONE_NOW=$(jq '[.userStories[] | select(.passes == true)] | length' "$PRD_FILE")
  if [ "$DONE_NOW" -gt "$DONE" ]; then
    echo "  Progress: $DONE -> $DONE_NOW / $TOTAL stories"
  else
    echo "  No new stories completed this iteration"
  fi

  if [ "$i" -lt "$MAX_ITERATIONS" ]; then
    echo "  Pausing 3s before next iteration..."
    sleep 3
  fi
done

echo ""
echo "==========================================================="
echo "  Max iterations ($MAX_ITERATIONS) reached"
echo "  Check progress.txt and logs/ for details"
echo "==========================================================="

DONE=$(jq '[.userStories[] | select(.passes == true)] | length' "$PRD_FILE")
echo "Final progress: $DONE / $TOTAL stories complete"

if [ "$DONE" -eq "$TOTAL" ]; then
  echo ""
  echo "  All stories complete! Run '/aura-ralph finish' in Claude Code to review."
  echo ""
  exit 0
fi

echo ""
echo "  Run './scripts/ralph/ralph.sh' to continue, or"
echo "  Run '/aura-ralph finish' in Claude Code for partial review."
echo ""
exit 1
