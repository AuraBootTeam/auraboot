#!/bin/bash
# Documentation quality gate.
#
# Runs in two modes depending on what the repo has:
#   - "system-reference" mode  → numbering + INDEX consistency + markdownlint + deadlinks
#   - "flat docs" mode (OSS)   → markdownlint + deadlinks across docs/**
#
# Detection: presence of `docs/system-reference/subsystems/`.
# Exit code: 0 = clean, 1 = problems found.
#
# Usage: ./scripts/check-docs.sh [--strict]
#   --strict  fail on warnings (dead links, deprecated syntax)

set -e

STRICT=0
if [ "${1:-}" = "--strict" ]; then STRICT=1; fi

EXIT_CODE=0
SUBSYSTEMS_DIR="docs/system-reference/subsystems"

if [ -d "$SUBSYSTEMS_DIR" ]; then
    DOC_MODE="system-reference"
    DOC_GLOB="docs/system-reference/**/*.md"
    DEADLINK_ROOT="docs/system-reference"
else
    DOC_MODE="flat"
    DOC_GLOB="docs/**/*.md"
    DEADLINK_ROOT="docs"
fi

echo "=== Document Quality Check ==="
echo "Mode: $DOC_MODE"
echo "Strict: $STRICT"
echo ""

# ---------- 1. Numbering conflicts (system-reference only) ----------

if [ "$DOC_MODE" = "system-reference" ]; then
    echo "--- Numbering Conflict Check ---"
    DUPLICATES=$(ls "$SUBSYSTEMS_DIR"/*.md 2>/dev/null | xargs -I{} basename {} | grep -v INDEX | sed 's/-.*//' | sort -n | uniq -d)
    if [ -n "$DUPLICATES" ]; then
        echo "ERROR: Duplicate numbering found:"
        for num in $DUPLICATES; do
            echo "  Number $num:"
            ls "$SUBSYSTEMS_DIR/$num-"* 2>/dev/null | sed 's/^/    /'
        done
        EXIT_CODE=1
    else
        FILE_CT=$(ls "$SUBSYSTEMS_DIR"/*.md 2>/dev/null | grep -v INDEX | wc -l | tr -d ' ')
        echo "OK: No numbering conflicts ($FILE_CT files)"
    fi
    echo ""

    echo "--- INDEX.md Consistency Check ---"
    FILE_COUNT=$(ls "$SUBSYSTEMS_DIR"/*.md 2>/dev/null | grep -v INDEX | wc -l | tr -d ' ')
    INDEX_COUNT=$(grep -cE '^\| [0-9]{2} \|' "$SUBSYSTEMS_DIR/INDEX.md" 2>/dev/null || echo "0")
    if [ "$FILE_COUNT" != "$INDEX_COUNT" ]; then
        echo "WARNING: INDEX.md has $INDEX_COUNT entries but directory has $FILE_COUNT files"
        EXIT_CODE=1
    else
        echo "OK: INDEX.md matches directory ($FILE_COUNT files)"
    fi
    echo ""
fi

# ---------- 2. Total file count (always) ----------

TOTAL_MD=$(find docs -name "*.md" -type f 2>/dev/null | wc -l | tr -d ' ')
echo "--- Doc inventory ---"
echo "Markdown files under docs/: $TOTAL_MD"
echo ""

# ---------- 3. Markdownlint ----------

echo "--- Markdown Lint ---"
if command -v npx &>/dev/null && npx markdownlint-cli2 --version &>/dev/null 2>&1; then
    # Repo-level .markdownlint-cli2.jsonc is auto-detected if present
    npx markdownlint-cli2 "$DOC_GLOB" || EXIT_CODE=1
else
    echo "SKIP: markdownlint-cli2 not installed (npm install -g markdownlint-cli2)"
fi
echo ""

# ---------- 4. Dead-link check ----------

echo "--- Dead Link Check (internal refs) ---"
# Internal-only directories that are NOT published to docs.auraboot.com and
# whose dead-link state is not a launch-quality gate. (Their content is
# session notes, archived plans, and Claude scaffolding — they reference
# personal-machine paths and superseded files by design.)
DEADLINK_EXCLUDE='docs/backlog|docs/superpowers|docs/plans|docs/handover|docs/archive'
DEAD_LINKS=0
while IFS= read -r file; do
    # Extract markdown links to local .md files (excluding http(s)://, mailto:, anchor-only)
    refs=$(grep -oE '\]\([^)]+\.md[^)]*\)' "$file" 2>/dev/null | sed 's/\](//' | sed 's/)//' | grep -v -E '^(http|mailto:|#)' || true)
    for ref in $refs; do
        # strip anchor (#section)
        path_only="${ref%%#*}"
        [ -z "$path_only" ] && continue
        dir=$(dirname "$file")
        # Resolve relative path WITHOUT requiring existence — the previous
        # `realpath -q` form returned empty when the target was missing,
        # which the `[ -n "$target" ]` guard then silently swallowed,
        # causing real dead links to be reported "OK". Use bash path
        # joining + python normpath so we can flag truly missing files.
        case "$path_only" in
            /*) target="$path_only" ;;
            *)  target="$dir/$path_only" ;;
        esac
        target=$(python3 -c "import os,sys; print(os.path.normpath(sys.argv[1]))" "$target" 2>/dev/null \
                 || echo "$target")
        if [ ! -f "$target" ]; then
            echo "  BROKEN: $file → $ref"
            DEAD_LINKS=$((DEAD_LINKS + 1))
        fi
    done
done < <(find "$DEADLINK_ROOT" -name "*.md" -type f 2>/dev/null | grep -vE "$DEADLINK_EXCLUDE")

if [ "$DEAD_LINKS" -gt 0 ]; then
    echo "WARNING: $DEAD_LINKS dead links found"
    if [ "$STRICT" = "1" ]; then EXIT_CODE=1; fi
else
    echo "OK: No dead links detected"
fi
echo ""

echo "=== Done (exit code: $EXIT_CODE) ==="
exit $EXIT_CODE
