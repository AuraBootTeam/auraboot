#!/bin/bash
# Check documentation quality: numbering conflicts + markdownlint
# Usage: ./scripts/check-docs.sh

set -e

SUBSYSTEMS_DIR="docs/system-reference/subsystems"
EXIT_CODE=0

echo "=== Document Quality Check ==="

# 1. Check numbering conflicts
echo ""
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
    echo "OK: No numbering conflicts ($(ls "$SUBSYSTEMS_DIR"/*.md 2>/dev/null | grep -v INDEX | wc -l | tr -d ' ') files)"
fi

# 2. Check INDEX.md consistency
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

# 3. Markdownlint (if installed)
echo ""
echo "--- Markdown Lint ---"
if command -v npx &>/dev/null; then
    if npx markdownlint-cli2 --version &>/dev/null 2>&1; then
        npx markdownlint-cli2 "docs/system-reference/**/*.md" || EXIT_CODE=1
    else
        echo "SKIP: markdownlint-cli2 not installed (npm install -g markdownlint-cli2)"
    fi
else
    echo "SKIP: npx not found"
fi

# 4. Internal dead link check (basic)
echo ""
echo "--- Dead Link Check (internal refs) ---"
DEAD_LINKS=0
while IFS= read -r file; do
    # Extract markdown links to local .md files
    refs=$(grep -oE '\]\([^)]+\.md[^)]*\)' "$file" 2>/dev/null | sed 's/\](//' | sed 's/)//' | grep -v 'http')
    for ref in $refs; do
        # Resolve relative path
        dir=$(dirname "$file")
        target=$(cd "$dir" && realpath -q "$ref" 2>/dev/null || echo "")
        if [ -n "$target" ] && [ ! -f "$target" ]; then
            echo "  BROKEN: $file → $ref"
            DEAD_LINKS=$((DEAD_LINKS + 1))
        fi
    done
done < <(find docs/system-reference -name "*.md" -type f)

if [ "$DEAD_LINKS" -gt 0 ]; then
    echo "WARNING: $DEAD_LINKS dead links found"
else
    echo "OK: No dead links detected"
fi

echo ""
echo "=== Done (exit code: $EXIT_CODE) ==="
exit $EXIT_CODE
