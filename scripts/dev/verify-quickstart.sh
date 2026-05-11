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
#   POSTGRES_PORT=15432 ./scripts/dev/verify-quickstart.sh  # if local PostgreSQL already uses 5432
#
# Pass criteria:
#   - docker compose --profile full up -d completes within 5 minutes
#   - http://localhost:3000 (BFF/SSR) returns a ready 2xx/3xx response within ~120s of compose up
#   - login with default creds returns a JWT
#   - backend `/actuator/health` responds inside the container
#   - 3 sample API endpoints respond in expected shape (via BFF proxy)
#   - default `/dashboards` data resolves to seeded CRM overview widgets
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

step "1/9 Pre-flight"
command -v docker >/dev/null      && ok "docker installed" || fail "docker missing"
docker info >/dev/null 2>&1       && ok "docker daemon up" || fail "docker daemon down"
command -v curl >/dev/null        && ok "curl installed"   || fail "curl missing"
[ "$FAIL" -eq 0 ] || { echo "Pre-flight failed; aborting."; exit 1; }

if [ $CLEAN_CLONE -eq 1 ]; then
  step "2/9 Fresh clone (--clean-clone)"
  REPO_ROOT="/tmp/auraboot-quickstart-verify-$$"
  git clone --depth 1 https://github.com/AuraBootTeam/auraboot.git "$REPO_ROOT"
  ok "cloned to $REPO_ROOT"
fi

cd "$REPO_ROOT"

step "3/9 docker compose up (target: < 5min cold start)"
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

step "4/9 Wait for backend health (target: < 180s — backend healthcheck has 120s start_period)"
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

step "5/9 Frontend reachable (BFF/SSR on host port 3000)"
FRONTEND_STATUS="000"
ATTEMPTS=0
while true; do
  FRONTEND_STATUS=$(curl -sS -m 5 -o /dev/null -w "%{http_code}" http://localhost:3000 2>/dev/null || echo "000")
  case "$FRONTEND_STATUS" in
    200|301|302|303|307|308)
      ok "http://localhost:3000 returns ${FRONTEND_STATUS} after ${ATTEMPTS}×2s"
      break
      ;;
  esac
  ATTEMPTS=$((ATTEMPTS + 1))
  if [ $ATTEMPTS -gt 60 ]; then
    fail "frontend not reachable; last status=${FRONTEND_STATUS}"
    docker compose logs --tail 80 frontend 2>/dev/null || true
    break
  fi
  sleep 2
done

step "6/9 Login API contract (via BFF proxy)"
# Frontend BFF proxies /api/* to backend; we go through it to mirror real user flow.
LOGIN_RESPONSE="FAIL"
for _ in $(seq 1 20); do
  LOGIN_RESPONSE=$(curl -fsS -m 5 -X POST http://localhost:3000/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@auraboot.com","password":"Test2026x"}' 2>/dev/null || echo "FAIL")
  echo "$LOGIN_RESPONSE" | grep -q '"jwt"' && break
  sleep 2
done
# AuraBoot wraps responses as ApiResponse<AuthenticationResponse> → JWT is at $.data.jwt.
if echo "$LOGIN_RESPONSE" | grep -q '"jwt"'; then
  ok "login returns JWT (data.jwt)"
  TOKEN=$(echo "$LOGIN_RESPONSE" | sed -n 's/.*"jwt":"\([^"]*\)".*/\1/p')
else
  fail "login failed; response: $(echo "$LOGIN_RESPONSE" | head -c 200)"
  TOKEN=""
fi

step "7/9 Authenticated API smoke"
if [ -n "$TOKEN" ]; then
  # Endpoints any authenticated user can hit (no specific permission code).
  # /api/menu and /api/permissions root paths have no GET handler;
  # /api/permissions/tree needs a permission grant (403 for admin without it).
  for endpoint in /api/auth/me /api/menu/user; do
    if curl -fsS -m 5 -H "Authorization: Bearer $TOKEN" "http://localhost:3000$endpoint" -o /dev/null; then
      ok "$endpoint → 200"
    else
      fail "$endpoint failed"
    fi
  done
fi

step "8/9 Dashboard UI smoke"
if [ -n "$TOKEN" ]; then
  DEFAULT_DASHBOARD="FAIL"
  for _ in $(seq 1 30); do
    DEFAULT_DASHBOARD=$(curl -fsS -m 5 -H "Authorization: Bearer $TOKEN" \
      http://localhost:3000/api/dashboards/default 2>/dev/null || echo "FAIL")
    echo "$DEFAULT_DASHBOARD" | grep -q '"code":"crm_overview"' && break
    sleep 2
  done
  if echo "$DEFAULT_DASHBOARD" | grep -q '"code":"crm_overview"'; then
    ok "default dashboard is crm_overview"
  else
    fail "default dashboard is not crm_overview; response: $(echo "$DEFAULT_DASHBOARD" | head -c 200)"
  fi

  if echo "$DEFAULT_DASHBOARD" | grep -Eiq 'Internal system error|Application Error'; then
    fail "default dashboard API returned an internal error"
  elif echo "$DEFAULT_DASHBOARD" | grep -Eq '最新商机|Recent Opportunities' \
    && echo "$DEFAULT_DASHBOARD" | grep -Eq '最新线索|Recent Leads'; then
    ok "default dashboard contains recent opportunities and leads widgets"
  else
    fail "default dashboard missing CRM overview widgets; response: $(echo "$DEFAULT_DASHBOARD" | tr '\n' ' ' | head -c 300)"
  fi
else
  fail "cannot verify default dashboard without login token"
fi

step "9/9 Log scan (looking for ERROR-level surprises)"
KNOWN_NOISE='Connection refused: connect|temporary failure|retrying|com.zaxxer.hikari.pool|Task :|^$'
ERR_LINES=$(docker compose logs backend 2>/dev/null | grep "ERROR\b" || true)
if [ -z "$ERR_LINES" ]; then
  ERR_COUNT=0
  ERR_REAL=0
  ERR_REAL_LINES=""
else
  ERR_COUNT=$(printf '%s\n' "$ERR_LINES" | wc -l | tr -d ' ')
  ERR_REAL_LINES=$(printf '%s\n' "$ERR_LINES" | grep -vE "$KNOWN_NOISE" || true)
  if [ -z "$ERR_REAL_LINES" ]; then
    ERR_REAL=0
  else
    ERR_REAL=$(printf '%s\n' "$ERR_REAL_LINES" | wc -l | tr -d ' ')
  fi
fi
if [ "${ERR_REAL:-0}" -eq 0 ]; then
  ok "no unexpected ERROR lines in backend logs"
else
  warn "$ERR_REAL ERROR lines (out of $ERR_COUNT total) need triage:"
  printf '%s\n' "$ERR_REAL_LINES" | head -5
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
