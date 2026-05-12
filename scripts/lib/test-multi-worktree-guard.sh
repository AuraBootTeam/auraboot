#!/bin/bash
#
# Sanity tests for scripts/lib/multi-worktree-guard.sh
#
# Drives the guard against a throwaway git repo so we can control
# worktree count without touching the real auraboot repo.
#
# Run:
#   bash scripts/lib/test-multi-worktree-guard.sh
#
# Exit code: 0 if all scenarios pass, 1 if any fail.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD_SCRIPT="$SCRIPT_DIR/multi-worktree-guard.sh"

if [ ! -f "$GUARD_SCRIPT" ]; then
    echo "FAIL: $GUARD_SCRIPT not found"
    exit 1
fi

PASS=0
FAIL=0

assert_exit() {
    local description="$1"
    local expected="$2"
    local actual="$3"
    if [ "$actual" -eq "$expected" ]; then
        echo "  PASS: $description (exit=$actual)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $description (expected exit=$expected, got $actual)"
        FAIL=$((FAIL + 1))
    fi
}

assert_file_contains() {
    local description="$1"
    local file="$2"
    local needle="$3"
    if [ -f "$file" ] && grep -q "$needle" "$file"; then
        echo "  PASS: $description"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $description (file=$file, needle=$needle)"
        if [ -f "$file" ]; then
            echo "        contents: $(cat "$file")"
        fi
        FAIL=$((FAIL + 1))
    fi
}

# ----- Scenario 1: single worktree → silent pass -----

echo "Scenario 1: single-worktree repo → guard passes silently"
WORKDIR_1=$(mktemp -d)
(
    cd "$WORKDIR_1"
    git init -q
    git config user.email test@test.test
    git config user.name test
    : > a.txt && git add a.txt && git commit -q -m init
    bash "$GUARD_SCRIPT" check test-op-single > /tmp/guard-stdout-$$ 2> /tmp/guard-stderr-$$
)
assert_exit "scenario 1 exit code" 0 $?
if [ ! -s /tmp/guard-stderr-$$ ]; then
    echo "  PASS: scenario 1 produced no stderr noise"
    PASS=$((PASS + 1))
else
    echo "  FAIL: scenario 1 produced stderr:"
    cat /tmp/guard-stderr-$$
    FAIL=$((FAIL + 1))
fi
rm -rf "$WORKDIR_1" /tmp/guard-stdout-$$ /tmp/guard-stderr-$$

# ----- Scenario 2: two worktrees, no FORCE_HOST → refuse -----

echo "Scenario 2: two-worktree repo without FORCE_HOST → guard refuses"
WORKDIR_2=$(mktemp -d)
(
    cd "$WORKDIR_2"
    git init -q
    git config user.email test@test.test
    git config user.name test
    : > a.txt && git add a.txt && git commit -q -m init
    git worktree add -q ./wt-2 -b second-branch
    bash "$GUARD_SCRIPT" check test-op-two > /tmp/guard-stdout-$$ 2> /tmp/guard-stderr-$$
)
assert_exit "scenario 2 exit code (refuse)" 1 $?
if grep -q "ERROR: refusing to run" /tmp/guard-stderr-$$; then
    echo "  PASS: scenario 2 emitted refusal error"
    PASS=$((PASS + 1))
else
    echo "  FAIL: scenario 2 missing refusal error"
    cat /tmp/guard-stderr-$$
    FAIL=$((FAIL + 1))
fi
rm -rf "$WORKDIR_2" /tmp/guard-stdout-$$ /tmp/guard-stderr-$$

# ----- Scenario 3: two worktrees + isolated PG env → pass -----

echo "Scenario 3: two-worktree repo with isolated PG env → guard passes"
WORKDIR_3=$(mktemp -d)
(
    cd "$WORKDIR_3"
    git init -q
    git config user.email test@test.test
    git config user.name test
    : > a.txt && git add a.txt && git commit -q -m init
    git worktree add -q ./wt-2 -b second-branch
    PG_PORT=15432 bash "$GUARD_SCRIPT" check reset-db.sh > /tmp/guard-stdout-$$ 2> /tmp/guard-stderr-$$
)
assert_exit "scenario 3 exit code (isolated PG)" 0 $?
if [ ! -s /tmp/guard-stderr-$$ ]; then
    echo "  PASS: scenario 3 produced no stderr noise"
    PASS=$((PASS + 1))
else
    echo "  FAIL: scenario 3 produced stderr:"
    cat /tmp/guard-stderr-$$
    FAIL=$((FAIL + 1))
fi
rm -rf "$WORKDIR_3" /tmp/guard-stdout-$$ /tmp/guard-stderr-$$

# ----- Scenario 4: two worktrees + FORCE_HOST=1 → escape + audit log -----

echo "Scenario 4: two-worktree repo + FORCE_HOST=1 → escape with audit log"
WORKDIR_4=$(mktemp -d)
HOME_4=$(mktemp -d)
(
    cd "$WORKDIR_4"
    git init -q
    git config user.email test@test.test
    git config user.name test
    : > a.txt && git add a.txt && git commit -q -m init
    git worktree add -q ./wt-2 -b second-branch
    HOME="$HOME_4" FORCE_HOST=1 bash "$GUARD_SCRIPT" check test-op-force \
        > /tmp/guard-stdout-$$ 2> /tmp/guard-stderr-$$
)
assert_exit "scenario 4 exit code (escape)" 0 $?
assert_file_contains "scenario 4 audit log captured operation" \
    "$HOME_4/.aura/host-override.log" "operation=test-op-force"
assert_file_contains "scenario 4 audit log captured worktree count" \
    "$HOME_4/.aura/host-override.log" "worktree_count=2"
if grep -q "WARN: FORCE_HOST=1" /tmp/guard-stderr-$$; then
    echo "  PASS: scenario 4 emitted WARN on stderr"
    PASS=$((PASS + 1))
else
    echo "  FAIL: scenario 4 missing WARN line"
    cat /tmp/guard-stderr-$$
    FAIL=$((FAIL + 1))
fi
rm -rf "$WORKDIR_4" "$HOME_4" /tmp/guard-stdout-$$ /tmp/guard-stderr-$$

echo ""
echo "==========================================="
echo "  Results: $PASS passed, $FAIL failed"
echo "==========================================="

if [ $FAIL -gt 0 ]; then
    exit 1
fi
exit 0
