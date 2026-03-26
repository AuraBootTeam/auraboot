#!/usr/bin/env bash
# E2E Compliance Gate — scans test files for violations of testing constitution
# Reference: docs/e2e/00-E2E测试宪法-深度测试完整性.md + AGENTS.md
# Exit code: 0 = pass, 1 = violations found
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ERROR_COUNT=0
WARNING_COUNT=0
ERRORS=""
WARNINGS=""

# --- Helpers ---

add_error() {
  local rel_line="$1"
  local msg="$2"
  ERRORS+="  ❌ $rel_line — $msg"$'\n'
  ((ERROR_COUNT++)) || true
}

add_warning() {
  local rel_line="$1"
  local msg="$2"
  WARNINGS+="  ⚠️  $rel_line — $msg"$'\n'
  ((WARNING_COUNT++)) || true
}

# Search files and collect hits. Uses grep -rn (portable).
# Usage: search_files <dir> <include_pattern> <regex>
# Outputs matching lines with file:line format relative to REPO_ROOT
search_files() {
  local dir="$1"
  local include="$2"
  local pattern="$3"
  grep -rn --include="$include" -E "$pattern" "$dir" 2>/dev/null | while IFS= read -r line; do
    echo "${line#"$REPO_ROOT"/}"
  done
}

echo "=== E2E Compliance Gate ==="
echo ""

# ============================================================
# Web E2E: web-admin/tests/e2e/*.spec.ts
# ============================================================
WEB_E2E="$REPO_ROOT/web-admin/tests/e2e"
if [[ -d "$WEB_E2E" ]]; then
  echo "Scanning: web-admin/tests/e2e/"

  # ERROR: waitForTimeout (actual calls only, skip comments mentioning it)
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    # Skip lines that are comments (content part starts with * or //)
    _content="${hit#*:[0-9]*:}"
    [[ "$_content" =~ ^[[:space:]]*(\*|//) ]] && continue
    add_error "$hit" "waitForTimeout (use waitForResponse/waitFor instead)"
  done < <(search_files "$WEB_E2E" "*.spec.ts" "\.waitForTimeout\(")

  # ERROR: afterAll with cleanup/delete/remove (skip comments)
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    _content="${hit#*:[0-9]*:}"
    [[ "$_content" =~ ^[[:space:]]*(\*|//) ]] && continue
    add_error "$hit" "afterAll cleanup (test data must be preserved)"
  done < <(search_files "$WEB_E2E" "*.spec.ts" "afterAll.*(delete|cleanup|remove|clean)")

  # WARNING: page.goto in test files (may be legitimate in beforeAll, but flag for review)
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_warning "$hit" "page.goto (prefer menu navigation in test body; OK in beforeAll)"
  done < <(search_files "$WEB_E2E" "*.spec.ts" "page\.goto\(")
fi

# ============================================================
# Android: apps/android/**/*Test.kt
# ============================================================
ANDROID_DIR="$REPO_ROOT/apps/android"
if [[ -d "$ANDROID_DIR" ]]; then
  echo "Scanning: apps/android/"

  # ERROR: Thread.sleep
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_error "$hit" "Thread.sleep (use waitForTag/waitUntil with real condition)"
  done < <(search_files "$ANDROID_DIR" "*Test.kt" "Thread\.sleep")

  # ERROR: waitUntil always-true
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_error "$hit" "waitUntil { true } (always-true wait — fake pass pattern)"
  done < <(search_files "$ANDROID_DIR" "*Test.kt" "waitUntil.*\{\s*true\s*\}")

  # ERROR: tryWaitForTag + return (silent skip)
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_error "$hit" "tryWaitForTag (silent skip — use waitForTag + fail() instead)"
  done < <(search_files "$ANDROID_DIR" "*Test.kt" "tryWaitForTag")
fi

# ============================================================
# iOS: apps/ios/**/*Test*.swift
# ============================================================
IOS_DIR="$REPO_ROOT/apps/ios"
if [[ -d "$IOS_DIR" ]]; then
  echo "Scanning: apps/ios/"

  # ERROR: sleep( in test files
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_error "$hit" "sleep() (use waitForExistence/expectation instead)"
  done < <(search_files "$IOS_DIR" "*Tests.swift" "sleep\(")
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_error "$hit" "sleep() (use waitForExistence/expectation instead)"
  done < <(search_files "$IOS_DIR" "*Test.swift" "sleep\(")

  # ERROR: Thread.sleep
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_error "$hit" "Thread.sleep (use waitForExistence/expectation instead)"
  done < <(search_files "$IOS_DIR" "*Tests.swift" "Thread\.sleep")
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    add_error "$hit" "Thread.sleep (use waitForExistence/expectation instead)"
  done < <(search_files "$IOS_DIR" "*Test.swift" "Thread\.sleep")
fi

# ============================================================
# Output
# ============================================================
echo ""

if [[ -n "$ERRORS" ]]; then
  echo "ERRORS:"
  echo -n "$ERRORS"
  echo ""
fi

if [[ -n "$WARNINGS" ]]; then
  echo "WARNINGS:"
  echo -n "$WARNINGS"
  echo ""
fi

echo "Summary: $ERROR_COUNT errors, $WARNING_COUNT warnings"

if [[ $ERROR_COUNT -gt 0 ]]; then
  echo ""
  echo "❌ Compliance check FAILED — fix errors before merging."
  exit 1
else
  echo ""
  echo "✅ Compliance check passed."
  exit 0
fi
