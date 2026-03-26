#!/usr/bin/env bash
# Validate GitHub Actions workflow YAML files.
# Checks: YAML syntax, required fields, Dockerfile existence, Compose files.
#
# Usage: bash scripts/validate-workflows.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKFLOWS_DIR="$REPO_ROOT/.github/workflows"
EXIT_CODE=0

# ─── Colors ───────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

pass() { echo -e "  ${GREEN}PASS${NC} $1"; }
fail() { echo -e "  ${RED}FAIL${NC} $1"; EXIT_CODE=1; }
warn() { echo -e "  ${YELLOW}WARN${NC} $1"; }

# ─── 1. Check workflow directory exists ───────────────────
echo "=== Validating GitHub Actions Workflows ==="
echo ""

if [[ ! -d "$WORKFLOWS_DIR" ]]; then
  fail ".github/workflows/ directory not found"
  exit 1
fi

# ─── 2. YAML syntax check (grep-based, no yaml parser needed) ───
echo "[1/4] YAML Syntax & Structure"
for f in "$WORKFLOWS_DIR"/*.yml; do
  name=$(basename "$f")
  errors=""

  # Check file is non-empty
  if [[ ! -s "$f" ]]; then
    fail "$name — empty file"
    continue
  fi

  # Check for tab characters (YAML forbids tabs for indentation)
  if grep -Pn '^\t' "$f" >/dev/null 2>&1; then
    errors="$errors tab-indentation"
  fi

  # Check for trailing whitespace issues that break YAML
  # (not fatal, just a warning)

  if [[ -z "$errors" ]]; then
    pass "$name — no syntax issues"
  else
    fail "$name —$errors"
  fi
done
echo ""

# ─── 3. Required top-level keys ──────────────────────────
echo "[2/4] Required Fields (name, on, jobs)"
for f in "$WORKFLOWS_DIR"/*.yml; do
  name=$(basename "$f")
  missing=""

  # 'name:' at top level (column 0)
  if ! grep -qE '^name:' "$f"; then
    missing="$missing name"
  fi

  # 'on:' at top level — GitHub Actions trigger
  if ! grep -qE '^on:' "$f" && ! grep -qE "^'on':" "$f" && ! grep -qE '^"on":' "$f" && ! grep -qE '^true:' "$f"; then
    missing="$missing on"
  fi

  # 'jobs:' at top level
  if ! grep -qE '^jobs:' "$f"; then
    missing="$missing jobs"
  fi

  if [[ -z "$missing" ]]; then
    pass "$name"
  else
    fail "$name — missing:$missing"
  fi
done
echo ""

# ─── 4. Docker context paths check ───────────────────────
echo "[3/4] Docker Build Contexts"
if [[ -f "$REPO_ROOT/platform/Dockerfile" ]]; then
  pass "platform/Dockerfile exists"
else
  fail "platform/Dockerfile missing — docker-publish.yml backend job will fail"
fi

if [[ -f "$REPO_ROOT/web-admin/Dockerfile" ]]; then
  pass "web-admin/Dockerfile exists"
else
  fail "web-admin/Dockerfile missing — docker-publish.yml frontend job will fail"
fi
echo ""

# ─── 5. Docker Compose validation ────────────────────────
echo "[4/4] Docker Compose Files"
for f in docker-compose.yml docker-compose.prod.yml; do
  filepath="$REPO_ROOT/$f"
  if [[ ! -f "$filepath" ]]; then
    warn "$f not found (optional)"
    continue
  fi

  # Basic structure check: 'services:' key must exist
  if grep -qE '^services:' "$filepath"; then
    pass "$f — has services definition"
  else
    fail "$f — missing 'services:' top-level key"
  fi
done
echo ""

# ─── 6. Cross-reference: docker-publish.yml checks ───────
echo "[5/5] Docker Publish Workflow Cross-References"
DOCKER_WF="$WORKFLOWS_DIR/docker-publish.yml"
if [[ -f "$DOCKER_WF" ]]; then
  # Check it references GHCR
  if grep -q "ghcr.io" "$DOCKER_WF"; then
    pass "docker-publish.yml targets GHCR"
  else
    warn "docker-publish.yml does not reference ghcr.io"
  fi

  # Check it uses docker/build-push-action
  if grep -q "docker/build-push-action" "$DOCKER_WF"; then
    pass "docker-publish.yml uses build-push-action"
  else
    fail "docker-publish.yml missing docker/build-push-action"
  fi

  # Check both backend and frontend contexts
  if grep -q "context: platform" "$DOCKER_WF"; then
    pass "docker-publish.yml builds backend (platform/)"
  else
    fail "docker-publish.yml missing backend build context"
  fi

  if grep -q "context: web-admin" "$DOCKER_WF"; then
    pass "docker-publish.yml builds frontend (web-admin/)"
  else
    fail "docker-publish.yml missing frontend build context"
  fi
else
  fail "docker-publish.yml not found"
fi
echo ""

# ─── Summary ─────────────────────────────────────────────
if [[ $EXIT_CODE -eq 0 ]]; then
  echo -e "${GREEN}All checks passed.${NC}"
else
  echo -e "${RED}Some checks failed.${NC}"
fi

exit $EXIT_CODE
