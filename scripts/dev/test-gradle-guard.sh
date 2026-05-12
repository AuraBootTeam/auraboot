#!/bin/bash
#
# Smoke test for platform/build.gradle multi-worktree Maven publish guard.
#
# This script does not publish artifacts. It runs verifyMultiWorktreeGuard,
# which executes only the guard logic.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PLATFORM_DIR="$PROJECT_ROOT/platform"

PASS=0
FAIL=0

pass() {
    echo "  PASS: $1"
    PASS=$((PASS + 1))
}

fail() {
    echo "  FAIL: $1"
    FAIL=$((FAIL + 1))
}

assert_exit() {
    local description="$1"
    local expected="$2"
    local actual="$3"
    if [ "$actual" -eq "$expected" ]; then
        pass "$description (exit=$actual)"
    else
        fail "$description (expected exit=$expected, got $actual)"
    fi
}

WORKTREE_COUNT="$(git -C "$PROJECT_ROOT" worktree list 2>/dev/null | wc -l | tr -d ' ')"

echo "Scenario 1: per-worktree maven.repo.local passes"
(
    cd "$PLATFORM_DIR"
    ./gradlew verifyMultiWorktreeGuard -q -Dmaven.repo.local="$PROJECT_ROOT/.m2/repository" \
        >/tmp/gradle-guard-private-$$.out 2>&1
)
assert_exit "private maven repo guard" 0 $?
rm -f /tmp/gradle-guard-private-$$.out

echo "Scenario 2: default ~/.m2 behavior"
(
    cd "$PLATFORM_DIR"
    ./gradlew verifyMultiWorktreeGuard -q >/tmp/gradle-guard-default-$$.out 2>&1
)
DEFAULT_EXIT=$?

if [ "${WORKTREE_COUNT:-0}" -lt 2 ]; then
    assert_exit "default maven repo guard in single-worktree repo" 0 "$DEFAULT_EXIT"
else
    if [ "$DEFAULT_EXIT" -ne 0 ] && grep -q "refusing to run 'publishToMavenLocal'" /tmp/gradle-guard-default-$$.out; then
        pass "default maven repo blocked with multiple worktrees"
    else
        fail "default maven repo should be blocked with multiple worktrees"
        cat /tmp/gradle-guard-default-$$.out
    fi
fi
rm -f /tmp/gradle-guard-default-$$.out

echo ""
echo "==========================================="
echo "  Results: $PASS passed, $FAIL failed"
echo "==========================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
