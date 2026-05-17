#!/usr/bin/env bash
# OSS / Enterprise boundary check.
# Fails if the OSS repo references enterprise-only code, packages, or paths.
# Run locally before pushing; runs in CI on every push to main.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> OSS boundary check (repo: $REPO_ROOT)"

FAIL=0

# --- 1. Java imports must not reference enterprise packages ---
echo "--> Checking Java imports..."
JAVA_HITS=$(grep -rEn \
    --include='*.java' \
    --include='*.kt' \
    'import\s+(com|cn|io)\.[a-zA-Z0-9_.]*\.(enterprise|ent)\.' \
    platform/ plugins/ packages/ 2>/dev/null \
  | grep -v '/build/' \
  | grep -v '/node_modules/' || true)

if [ -n "$JAVA_HITS" ]; then
  echo "ERROR: Java/Kotlin code references enterprise packages:"
  echo "$JAVA_HITS"
  FAIL=1
fi

# --- 2. TS/JS imports must not reference enterprise packages ---
echo "--> Checking TS/JS imports..."
TS_HITS=$(grep -rEn \
    --include='*.ts' \
    --include='*.tsx' \
    --include='*.js' \
    --include='*.jsx' \
    "from\s+['\"]@auraboot/enterprise" \
    web-admin/ packages/ 2>/dev/null \
  | grep -v '/node_modules/' \
  | grep -v '/dist/' || true)

if [ -n "$TS_HITS" ]; then
  echo "ERROR: TS/JS code imports from @auraboot/enterprise:"
  echo "$TS_HITS"
  FAIL=1
fi

# --- 3. Filesystem paths must not reference ../auraboot-enterprise ---
echo "--> Checking filesystem path references..."
PATH_HITS=$(grep -rEn \
    --include='*.json' \
    --include='*.yml' \
    --include='*.yaml' \
    --include='*.gradle' \
    --include='*.sh' \
    'auraboot-enterprise|web-admin-ext' \
    . 2>/dev/null \
  | grep -v '/node_modules/' \
  | grep -v '/build/' \
  | grep -v '/dist/' \
  | grep -v '/\.worktrees/' \
  | grep -v '/\.git/' \
  | grep -v 'check-oss-boundary' \
  | grep -v 'CLAUDE.md\|AGENTS.md' \
  | grep -v 'scripts/publish-repos\.sh' \
  | grep -v 'scripts/env/reset-and-init\.sh' \
  | grep -v 'permission-codes\.yml' \
  | grep -v 'docker-compose\.cleanup-batch\.override\.yml' \
  | grep -v 'docker-compose\.ga-e2e\.override\.yml' \
  | grep -v 'docker-compose\.isolated\.yml' \
  | grep -v 'oss-scope\.json' || true)
  # Intentional exclusions:
  #  - publish-repos.sh: multi-repo release script (OSS + enterprise sync)
  #  - reset-and-init.sh: normalized local lifecycle entrypoint can target
  #    side-by-side OSS/enterprise checkouts without importing enterprise code
  #  - permission-codes.yml: CI references enterprise repo in comments only
  #  - docker-compose.{cleanup-batch,ga-e2e,isolated}.yml: dev/test compose mounts enterprise plugins
  #    when both repos are checked out side-by-side (no-op in pure OSS clones)
  #  - oss-scope.json: documents which OSS specs depend on enterprise plugins (negative space)

if [ -n "$PATH_HITS" ]; then
  echo "ERROR: Config/script files reference enterprise paths:"
  echo "$PATH_HITS"
  FAIL=1
fi

# --- 4. plugin.json descriptors must not declare enterprise-tier ---
echo "--> Checking plugin descriptors..."
PLUGIN_HITS=$(grep -rEn \
    --include='plugin.json' \
    '"tier"\s*:\s*"enterprise"' \
    plugins/ 2>/dev/null || true)

if [ -n "$PLUGIN_HITS" ]; then
  echo "ERROR: OSS plugins declared with enterprise tier:"
  echo "$PLUGIN_HITS"
  FAIL=1
fi

if [ $FAIL -ne 0 ]; then
  echo ""
  echo "❌ OSS boundary check FAILED."
  echo "   Fix the references above before pushing."
  echo "   If a reference is intentional (e.g., docs explaining the relationship),"
  echo "   exclude that file path from this script."
  exit 1
fi

echo ""
echo "✅ OSS boundary check passed."
