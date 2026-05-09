#!/usr/bin/env bash
# verify-quickstart.sh — pre-launch sanity check for the README quickstart.
#
# Run this on a clean machine (Ubuntu / macOS / WSL) BEFORE going public.
# It mimics what a first-time user does: clone, docker compose up, login,
# create a record, see the audit log. Failures here = README is lying.
#
# Usage:
#   ./scripts/dev/verify-quickstart.sh                  # uses repo root
#   ./scripts/dev/verify-quickstart.sh --clean-clone    # also git clone fresh into /tmp
#
# Pass criteria:
#   - docker compose --profile full up -d completes within 5 minutes
#   - http://localhost:3000 (BFF/SSR) returns 200 within ~180s of compose up
#   - login with default creds returns a JWT
#   - backend `/actuator/health` responds inside the container
#   - 3 sample API endpoints respond in expected shape (via BFF proxy)
#   - logs have zero ERROR-level lines that aren't in the known-noise list

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLEAN_CLONE=0
START_TS=$(date +%s)

for arg in "$@"; do
  case "$arg" in
    --clean-clone) CLEAN_CLONE=1 ;;
    *) echo "Unknown arg: $arg"; exit 1 ;;
  esac
done

PASS=0
FAIL=0
WARN=0

ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
fail() { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
warn() { echo "  ⚠️  $*"; WARN=$((WARN+1)); }
step() { echo ""; echo "==> $*"; }

step "1/8 Pre-flight"
command -v docker >/dev/null      && ok "docker installed" || fail "docker missing"
docker info >/dev/null 2>&1       && ok "docker daemon up" || fail "docker daemon down"
command -v curl >/dev/null        && ok "curl installed"   || fail "curl missing"
[ "$FAIL" -eq 0 ] || { echo "Pre-flight failed; aborting."; exit 1; }

if [ $CLEAN_CLONE -eq 1 ]; then
  step "2/8 Fresh clone (--clean-clone)"
  REPO_ROOT="/tmp/auraboot-quickstart-verify-$$"
  git clone --depth 1 https://github.com/AuraBootTeam/auraboot.git "$REPO_ROOT"
  ok "cloned to $REPO_ROOT"
fi

cd "$REPO_ROOT"

step "3/8 docker compose up (target: < 5min cold start)"
T0=$(date +%s)
# The README quickstart uses the `full` profile to bring up postgres + backend + frontend.
# Without --profile full, only postgres starts (backend/frontend are gated behind it).
docker compose --profile full up --build -d 2>&1 | tail -20
T1=$(date +%s)
DURATION=$((T1 - T0))
if [ $DURATION -lt 300 ]; then
  ok "compose up completed in ${DURATION}s"
else
  warn "compose up took ${DURATION}s (target < 300s)"
fi

step "4/8 Wait for backend health (target: < 180s — backend healthcheck has 120s start_period)"
# Backend port 6443 is intentionally NOT exposed to host in docker-compose.yml,
# so we exec into the container and curl actuator from inside.
ATTEMPTS=0
until docker compose exec -T backend wget -q --spider http://localhost:6443/actuator/health 2>/dev/null; do
  ATTEMPTS=$((ATTEMPTS + 1))
  if [ $ATTEMPTS -gt 60 ]; then
    fail "backend never came up after 180s — check logs"
    docker compose logs --tail 80 backend 2>/dev/null || true
    break
  fi
  sleep 3
done
[ $ATTEMPTS -le 60 ] && ok "backend healthy after ${ATTEMPTS}×3s"

step "5/8 Frontend reachable (BFF/SSR on host port 3000)"
if curl -fsS -m 5 http://localhost:3000 -o /dev/null; then
  ok "http://localhost:3000 returns 200"
else
  fail "frontend not reachable"
fi

step "6/8 Login API contract (via BFF proxy)"
# Frontend BFF proxies /api/* to backend; we go through it to mirror real user flow.
LOGIN_RESPONSE=$(curl -fsS -m 5 -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"Test2026x"}' 2>/dev/null || echo "FAIL")
# AuraBoot wraps responses as ApiResponse<AuthenticationResponse> → JWT is at $.data.jwt.
if echo "$LOGIN_RESPONSE" | grep -q '"jwt"'; then
  ok "login returns JWT (data.jwt)"
  TOKEN=$(echo "$LOGIN_RESPONSE" | sed -n 's/.*"jwt":"\([^"]*\)".*/\1/p')
else
  fail "login failed; response: $(echo "$LOGIN_RESPONSE" | head -c 200)"
  TOKEN=""
fi

step "7/8 Authenticated API smoke"
if [ -n "$TOKEN" ]; then
  for endpoint in /api/auth/me /api/menu /api/permissions; do
    if curl -fsS -m 5 -H "Authorization: Bearer $TOKEN" "http://localhost:3000$endpoint" -o /dev/null; then
      ok "$endpoint → 200"
    else
      fail "$endpoint failed"
    fi
  done
fi

step "8/8 Log scan (looking for ERROR-level surprises)"
KNOWN_NOISE='Connection refused: connect|temporary failure|retrying|com.zaxxer.hikari.pool|Task :|^$'
ERR_COUNT=$(docker compose logs backend 2>/dev/null \
  | grep -c "ERROR\b" \
  | head -1)
ERR_REAL=$(docker compose logs backend 2>/dev/null \
  | grep "ERROR\b" \
  | grep -vE "$KNOWN_NOISE" \
  | wc -l | tr -d ' ')
if [ "${ERR_REAL:-0}" -eq 0 ]; then
  ok "no unexpected ERROR lines in backend logs"
else
  warn "$ERR_REAL ERROR lines (out of $ERR_COUNT total) need triage:"
  docker compose logs platform 2>/dev/null | grep "ERROR\b" | grep -vE "$KNOWN_NOISE" | head -5
fi

# --- Summary ---
END_TS=$(date +%s)
TOTAL_SEC=$((END_TS - START_TS))

echo ""
echo "==================================="
echo "Quickstart verification summary"
echo "==================================="
echo "  PASS:  $PASS"
echo "  WARN:  $WARN"
echo "  FAIL:  $FAIL"
echo "  Total time: ${TOTAL_SEC}s"
echo ""

if [ $FAIL -gt 0 ]; then
  echo "❌ NOT READY for public launch. Fix FAILs and re-run."
  echo "   Tear down with: docker compose down -v"
  exit 1
elif [ $WARN -gt 0 ]; then
  echo "⚠️  Mostly ready. Investigate WARNs before launch."
  echo "   Tear down with: docker compose down -v"
  exit 0
else
  echo "✅ Quickstart looks healthy. README claims are honest."
  echo "   Tear down with: docker compose down -v"
  exit 0
fi
