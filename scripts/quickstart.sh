#!/usr/bin/env bash
# quickstart.sh — turn a freshly-started AuraBoot stack into a usable one.
#
#     docker compose --profile full up --build -d
#     ./scripts/quickstart.sh
#     open http://localhost:3000
#
# WHY THIS EXISTS
#
# `docker compose up` starts Postgres, the backend and the frontend — and that
# is all it does. It does NOT create an admin user, and it does NOT import any
# plugins. Until 2026-07-13 both the README and the docs told you to bring the
# stack up and then log in as admin@auraboot.com, which could never have worked:
#
#   - the schema loaded by Postgres on first boot seeds no users, so the login
#     you were told to use returned "Invalid username or password"
#   - even past the login, no plugin config had been imported, so the platform
#     had zero models and zero menus
#
# Creating the admin at application startup is deliberately not an option — see
# the bootstrap-ownership rule: the app must never write bootstrap data during
# startup. `/api/bootstrap/setup` is the explicit, auditable entry point, and an
# init script is the sanctioned place to call it. That is this script.
#
# It is idempotent: run it as many times as you like.
#
#   BACKEND_URL     default http://localhost:3000 — the URL you open in the
#                   browser, NOT the backend's own port.
#
#                   `docker compose --profile full` publishes ONLY the frontend
#                   (3000). The backend's 6443 is never mapped to the host; the
#                   BFF proxies /api/* to it over the compose network. A script
#                   pointed at localhost:6443 therefore hangs forever on a stack
#                   that is perfectly healthy — which is exactly what the first
#                   version of this file did, because it was tested against a
#                   backend running directly on the host. Health is probed via
#                   /api/bootstrap/status for the same reason: /actuator is not
#                   proxied, /api/* is.
#
#   ADMIN_EMAIL     default admin@auraboot.com
#   ADMIN_PASSWORD  default Test2026x
#   PLUGINS_PATH    path to plugins/ AS THE BACKEND SEES IT.
#                   Defaults to /app/plugins, which is where docker-compose
#                   mounts ./plugins. Running the backend on the host instead?
#                   Point this at the repo's plugins/ directory.

set -euo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:3000}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
ADMIN_NAME="${ADMIN_NAME:-Admin User}"
COMPANY_NAME="${COMPANY_NAME:-AuraBoot}"
PLUGINS_PATH="${PLUGINS_PATH:-/app/plugins}"

# Dependency order matters: core-meta defines the metadata the rest build on.
# One pass is not always enough — a plugin can reference something imported
# later in the list — so anything that fails is retried once at the end.
PLUGINS=(
  core-meta
  core-bpm
  core-decisionops
  core-aurabot
  page-manager
  platform-admin
  org-management
  crm-starter
  showcase
  agent-control-plane
  workflow-demo
)

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; DIM='\033[2m'; NC='\033[0m'
say() { printf '%b\n' "$*"; }
curl_() { NO_PROXY=localhost curl -sS --max-time 180 "$@"; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);$1" 2>/dev/null || true; }

# ── 1. wait for the backend ───────────────────────────────────────────────────
# Probed through /api/*, not /actuator: on the docker stack only the frontend is
# published, and the BFF proxies /api/* — /actuator is not reachable from the host.
say "${DIM}Waiting for AuraBoot at ${BACKEND_URL} …${NC}"
initialized=""
for i in $(seq 1 60); do
  probe="$(curl_ "${BACKEND_URL}/api/bootstrap/status" 2>/dev/null || true)"
  if [[ -n "${probe}" ]] && echo "${probe}" | grep -q '"initialized"'; then
    initialized="$(echo "${probe}" | json "print(d.get('data',{}).get('initialized'))")"
    break
  fi
  if [[ $i -eq 60 ]]; then
    say "${RED}AuraBoot never answered at ${BACKEND_URL}.${NC}"
    say "Cold start takes 2–4 minutes. If it is still failing:  docker compose logs backend"
    exit 1
  fi
  sleep 5
done
say "${GREEN}✓${NC} AuraBoot is answering"

# ── 2. create the admin user (idempotent) ─────────────────────────────────────
if [[ "${initialized}" == "True" ]]; then
  say "${GREEN}✓${NC} already bootstrapped ${DIM}(skipping admin creation)${NC}"
else
  resp="$(curl_ -X POST "${BACKEND_URL}/api/bootstrap/setup" -H 'Content-Type: application/json' -d "{
    \"companyName\": \"${COMPANY_NAME}\",
    \"adminEmail\": \"${ADMIN_EMAIL}\",
    \"adminPassword\": \"${ADMIN_PASSWORD}\",
    \"adminDisplayName\": \"${ADMIN_NAME}\",
    \"systemMode\": \"single\"
  }")"
  ok="$(echo "${resp}" | json "print(d.get('data',{}).get('success'))")"
  if [[ "${ok}" != "True" ]]; then
    say "${RED}✗ bootstrap failed${NC}\n${resp}"
    exit 1
  fi
  say "${GREEN}✓${NC} admin created — ${ADMIN_EMAIL}"
fi

# ── 3. log in ─────────────────────────────────────────────────────────────────
JWT="$(curl_ -X POST "${BACKEND_URL}/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}" \
  | json "print(d.get('data',{}).get('jwt',''))")"
if [[ -z "${JWT}" ]]; then
  say "${RED}✗ could not log in as ${ADMIN_EMAIL}.${NC}"
  exit 1
fi
say "${GREEN}✓${NC} logged in"

# ── 4. import plugins ─────────────────────────────────────────────────────────
import_one() {
  local plugin="$1"
  local resp
  resp="$(curl_ -X POST "${BACKEND_URL}/api/plugins/import/import-directory-sync" \
    -H "Authorization: Bearer ${JWT}" -H 'Content-Type: application/json' \
    -d "{\"path\":\"${PLUGINS_PATH}/${plugin}\",\"conflictStrategy\":\"OVERWRITE\"}")"
  [[ "$(echo "${resp}" | json "print(d.get('success'))")" == "True" ]]
}

say "${DIM}Importing plugins …${NC}"
retry=()
for p in "${PLUGINS[@]}"; do
  if import_one "$p"; then say "  ${GREEN}✓${NC} ${p}"; else say "  ${YELLOW}·${NC} ${p} ${DIM}(will retry)${NC}"; retry+=("$p"); fi
done

failed=()
for p in ${retry[@]+"${retry[@]}"}; do
  if import_one "$p"; then say "  ${GREEN}✓${NC} ${p} ${DIM}(retry)${NC}"; else say "  ${RED}✗${NC} ${p}"; failed+=("$p"); fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  say "\n${RED}${#failed[@]} plugin(s) failed to import:${NC} ${failed[*]}"
  say "If PLUGINS_PATH is wrong the backend cannot see the files. It is currently"
  say "  ${PLUGINS_PATH}  — the path as the BACKEND sees it, not as you see it."
  exit 1
fi

say "\n${GREEN}AuraBoot is ready.${NC}"
say "  ${BACKEND_URL%:*}:3000   ${DIM}(or wherever you mapped the frontend)${NC}"
say "  ${ADMIN_EMAIL} / ${ADMIN_PASSWORD}   ${DIM}— change this password${NC}"
