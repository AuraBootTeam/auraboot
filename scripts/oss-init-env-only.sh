#!/bin/bash

# AuraBoot Quick Environment Initialization
# This script only runs the Playwright initialization tests
# (Assumes backend and frontend are already running)
#
# Usage: ./scripts/oss-init-env-only.sh [--headed]
#   --headed  Run tests with browser visible

set -e

# Avoid Node warning: "NO_COLOR is ignored due to FORCE_COLOR being set"
unset NO_COLOR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
WEB_ADMIN_DIR="$PROJECT_ROOT/web-admin"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

# Parse arguments
HEADED=""
for arg in "$@"; do
    case $arg in
        --headed)
            HEADED="--headed"
            shift
            ;;
    esac
done

echo -e "${BLUE}=== AuraBoot Quick Initialization ===${NC}"
echo ""

# Check if services are running
echo -e "${YELLOW}Checking services...${NC}"

BACKEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:6443/actuator/health 2>/dev/null || echo "000")
FRONTEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:5173 2>/dev/null || echo "000")

if [ "$BACKEND_STATUS" != "200" ]; then
    echo -e "${RED}Backend not running (port 6443)${NC}"
    echo "Please start backend first: cd platform && ./gradlew bootRun"
    exit 1
fi
echo -e "${GREEN}   Backend: OK${NC}"

if [ "$FRONTEND_STATUS" != "200" ] && [ "$FRONTEND_STATUS" != "302" ] && [ "$FRONTEND_STATUS" != "304" ]; then
    echo -e "${RED}Frontend not running (port 5173)${NC}"
    echo "Please start frontend first: cd web-admin && pnpm dev:full"
    exit 1
fi
echo -e "${GREEN}   Frontend: OK${NC}"

# Run initialization tests
echo ""
echo -e "${YELLOW}Running initialization tests...${NC}"
cd "$WEB_ADMIN_DIR"

# Ensure storage directory exists
mkdir -p tests/storage

npx playwright test --config=playwright.init.config.ts --reporter=line $HEADED

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}=== Initialization Complete ===${NC}"
    echo ""
    echo "Environment is ready with:"
    echo "  - User: admin@example.com / Test2026x"
    echo "  - Tenant: Xinran"
    echo "  - Plugin import verified"
else
    echo ""
    echo -e "${YELLOW}Some tests may have warnings, check output above${NC}"
fi
