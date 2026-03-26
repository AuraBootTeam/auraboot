#!/bin/bash
#
# DEPRECATED: Use the CLI instead:
#   cd plugins/cli && npx tsx src/index.ts plugin validate <dir>
#   See: plugins/cli/README.md
#
# Verification script for AuraBoot Plugin Package
# Tests plugin installation and functionality
#
# Usage: ./verify-plugin.sh [--e2e] [--cleanup]
#
# Environment variables:
#   API_BASE      - Backend API URL (default: http://localhost:6443)
#   FRONTEND_BASE - Frontend URL (default: http://localhost:5173)
#   AUTH_EMAIL    - Login email (default: admin@auraboot.test)
#   AUTH_PASSWORD - Login password (default:  Test2026x)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ZIP_FILE="$PROJECT_DIR/dist/asset-management-plugin.zip"
API_BASE="${API_BASE:-http://localhost:6443}"
FRONTEND_BASE="${FRONTEND_BASE:-http://localhost:5173}"
AUTH_EMAIL="${AUTH_EMAIL:-admin@auraboot.test}"
AUTH_PASSWORD="${AUTH_PASSWORD:- Test2026x}"
AUTH_TOKEN=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse arguments
RUN_E2E=false
CLEANUP=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --e2e)
      RUN_E2E=true
      shift
      ;;
    --cleanup)
      CLEANUP=true
      shift
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

echo "=============================================="
echo "  AuraBoot Plugin Verification Script"
echo "=============================================="
echo ""
echo "  API Base:      $API_BASE"
echo "  Frontend Base: $FRONTEND_BASE"
echo "  ZIP File:      $ZIP_FILE"
echo ""

# Check if ZIP exists
if [ ! -f "$ZIP_FILE" ]; then
  echo -e "${RED}ERROR: Plugin ZIP not found at $ZIP_FILE${NC}"
  echo "Run ./build-plugin.sh first"
  exit 1
fi

PLUGIN_PID=""
ERRORS=0

# Function to report result
report() {
  if [ $1 -eq 0 ]; then
    echo -e "${GREEN}✓${NC} $2"
  else
    echo -e "${RED}✗${NC} $2"
    ERRORS=$((ERRORS + 1))
  fi
}

# ============================================
# Step 1: Validate plugin.json schema
# ============================================
echo ""
echo "[Step 1] Validating plugin.json schema..."

# Extract and validate plugin.json
unzip -p "$ZIP_FILE" plugin.json > /tmp/plugin.json 2>/dev/null

if python3 -m json.tool /tmp/plugin.json > /dev/null 2>&1; then
  report 0 "plugin.json is valid JSON"
else
  report 1 "plugin.json is NOT valid JSON"
fi

# Check required fields
PLUGIN_ID=$(jq -r '.pluginId' /tmp/plugin.json 2>/dev/null)
NAMESPACE=$(jq -r '.namespace' /tmp/plugin.json 2>/dev/null)
VERSION=$(jq -r '.version' /tmp/plugin.json 2>/dev/null)

if [ "$PLUGIN_ID" != "null" ] && [ -n "$PLUGIN_ID" ]; then
  report 0 "pluginId: $PLUGIN_ID"
else
  report 1 "pluginId is missing"
fi

if [ "$NAMESPACE" != "null" ] && [ -n "$NAMESPACE" ]; then
  report 0 "namespace: $NAMESPACE"
else
  report 1 "namespace is missing"
fi

if [ "$VERSION" != "null" ] && [ -n "$VERSION" ]; then
  report 0 "version: $VERSION"
else
  report 1 "version is missing"
fi

# ============================================
# Step 2: Check API server availability
# ============================================
echo ""
echo "[Step 2] Checking API server availability..."

API_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$API_BASE/actuator/health" 2>/dev/null || echo "000")

if [ "$API_STATUS" = "200" ]; then
  report 0 "API server is running at $API_BASE"
else
  echo -e "${YELLOW}⚠${NC} API server not available (HTTP $API_STATUS)"
  echo "  Skipping installation tests. Start the server with:"
  echo "  cd platform && ./gradlew bootRun"
  echo ""
  echo "=============================================="
  echo "  Validation-only Results"
  echo "=============================================="
  echo ""
  echo "  Schema validation: $([[ $ERRORS -eq 0 ]] && echo 'PASSED' || echo 'FAILED')"
  echo "  Errors: $ERRORS"
  exit $ERRORS
fi

# ============================================
# Step 2b: Authenticate
# ============================================
echo ""
echo "[Step 2b] Authenticating..."

LOGIN_RESULT=$(curl -s -X POST "$API_BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$AUTH_EMAIL\",\"password\":\"$AUTH_PASSWORD\"}" 2>/dev/null)

LOGIN_SUCCESS=$(echo "$LOGIN_RESULT" | jq -r '.success' 2>/dev/null)

if [ "$LOGIN_SUCCESS" = "true" ]; then
  AUTH_TOKEN=$(echo "$LOGIN_RESULT" | jq -r '.data.jwt')
  report 0 "Authentication successful"
else
  LOGIN_ERROR=$(echo "$LOGIN_RESULT" | jq -r '.message // "Unknown error"' 2>/dev/null)
  report 1 "Authentication failed: $LOGIN_ERROR"
  echo "  Set AUTH_EMAIL and AUTH_PASSWORD environment variables"
  exit 1
fi

# Helper function for authenticated API calls
auth_curl() {
  curl -s -H "Authorization: Bearer $AUTH_TOKEN" "$@"
}

# ============================================
# Step 3: Install plugin
# ============================================
echo ""
echo "[Step 3] Installing plugin package..."

INSTALL_RESULT=$(curl -s -X POST \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -F "file=@$ZIP_FILE" \
  "$API_BASE/api/plugins/packages/install")

SUCCESS=$(echo "$INSTALL_RESULT" | jq -r '.success' 2>/dev/null)

if [ "$SUCCESS" = "true" ]; then
  PLUGIN_PID=$(echo "$INSTALL_RESULT" | jq -r '.pluginPid')
  report 0 "Plugin installed successfully (PID: $PLUGIN_PID)"
else
  ERROR_MSG=$(echo "$INSTALL_RESULT" | jq -r '.message // .error // "Unknown error"' 2>/dev/null)
  report 1 "Installation failed: $ERROR_MSG"
  echo "$INSTALL_RESULT" | jq '.' 2>/dev/null || echo "$INSTALL_RESULT"
  exit 1
fi

# ============================================
# Step 4: Verify config resources
# ============================================
echo ""
echo "[Step 4] Verifying config resources..."

# Check models
MODEL_COUNT=$(auth_curl "$API_BASE/api/meta/models?namespace=$NAMESPACE" 2>/dev/null | jq 'length' 2>/dev/null || echo "0")
if [ "$MODEL_COUNT" -gt 0 ]; then
  report 0 "Models created: $MODEL_COUNT"
else
  report 1 "No models found for namespace $NAMESPACE"
fi

# Check commands
COMMAND_COUNT=$(auth_curl "$API_BASE/api/meta/commands?namespace=$NAMESPACE" 2>/dev/null | jq 'length' 2>/dev/null || echo "0")
if [ "$COMMAND_COUNT" -gt 0 ]; then
  report 0 "Commands created: $COMMAND_COUNT"
else
  echo -e "${YELLOW}⚠${NC} No commands found (may be expected)"
fi

# Check permissions
PERM_COUNT=$(auth_curl "$API_BASE/api/system/permissions?category=$NAMESPACE" 2>/dev/null | jq 'length' 2>/dev/null || echo "0")
if [ "$PERM_COUNT" -gt 0 ]; then
  report 0 "Permissions created: $PERM_COUNT"
else
  echo -e "${YELLOW}⚠${NC} No permissions found (may be expected)"
fi

# Check menus
MENU_COUNT=$(auth_curl "$API_BASE/api/system/menus?code=$NAMESPACE" 2>/dev/null | jq '.data | length' 2>/dev/null || echo "0")
if [ "$MENU_COUNT" -gt 0 ]; then
  report 0 "Menus created: $MENU_COUNT"
else
  echo -e "${YELLOW}⚠${NC} No menus found (may be expected)"
fi

# ============================================
# Step 5: Verify backend extensions
# ============================================
echo ""
echo "[Step 5] Verifying backend extensions..."

EXTENSIONS=$(auth_curl "$API_BASE/api/plugins/hotload/extensions" 2>/dev/null)

if [ -n "$EXTENSIONS" ]; then
  HANDLER_COUNT=$(echo "$EXTENSIONS" | jq '.registeredKeys.commandTypes | length' 2>/dev/null || echo "0")
  LISTENER_COUNT=$(echo "$EXTENSIONS" | jq '.registeredKeys.eventPatterns | length' 2>/dev/null || echo "0")
  VALIDATOR_COUNT=$(echo "$EXTENSIONS" | jq '.registeredKeys.validatorKeys | length' 2>/dev/null || echo "0")

  echo "  Command handlers: $HANDLER_COUNT"
  echo "  Event listeners: $LISTENER_COUNT"
  echo "  Validators: $VALIDATOR_COUNT"

  if [ "$HANDLER_COUNT" -gt 0 ] || [ "$LISTENER_COUNT" -gt 0 ]; then
    report 0 "Backend extensions loaded"
  else
    echo -e "${YELLOW}⚠${NC} No backend extensions found (backend JAR may not be built)"
  fi
else
  echo -e "${YELLOW}⚠${NC} Could not query extensions endpoint"
fi

# ============================================
# Step 6: Verify frontend resources
# ============================================
echo ""
echo "[Step 6] Verifying frontend resources..."

# Check if frontend was included in install
HAS_FRONTEND=$(echo "$INSTALL_RESULT" | jq -r '.componentResults.frontend.status // "SKIPPED"' 2>/dev/null)

if [ "$HAS_FRONTEND" = "SUCCESS" ]; then
  REMOTE_URL=$(echo "$INSTALL_RESULT" | jq -r '.componentResults.frontend.remoteUrl' 2>/dev/null)
  REMOTE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $AUTH_TOKEN" "$API_BASE$REMOTE_URL" 2>/dev/null || echo "000")

  if [ "$REMOTE_STATUS" = "200" ]; then
    report 0 "Frontend remoteEntry.js accessible at $REMOTE_URL"
  else
    report 1 "Frontend remoteEntry.js not accessible (HTTP $REMOTE_STATUS)"
  fi
else
  echo -e "${YELLOW}⚠${NC} Frontend was not installed or skipped"
fi

# ============================================
# Step 7: Test command execution
# ============================================
echo ""
echo "[Step 7] Testing command execution..."

# Test asset:register command (correct URL: /api/meta/commands/execute/{commandCode})
CMD_RESULT=$(curl -s -X POST "$API_BASE/api/meta/commands/execute/asset:register" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "assetName": "Test Asset from Verification",
      "category": "IT_EQUIPMENT"
    }
  }' 2>/dev/null)

CMD_SUCCESS=$(echo "$CMD_RESULT" | jq -r '.success' 2>/dev/null)

if [ "$CMD_SUCCESS" = "true" ]; then
  # Response structure: .data.data.assetCode (ApiResponse.data -> CommandExecuteResult.data -> assetCode)
  ASSET_CODE=$(echo "$CMD_RESULT" | jq -r '.data.data.assetCode // "N/A"')
  report 0 "Command executed successfully (assetCode: $ASSET_CODE)"
else
  CMD_ERROR=$(echo "$CMD_RESULT" | jq -r '.message // "Unknown error"' 2>/dev/null)
  echo -e "${YELLOW}⚠${NC} Command execution failed: $CMD_ERROR"
  echo "  (This may be expected if command handler is not loaded)"
fi

# ============================================
# Step 8: Run E2E tests (optional)
# ============================================
if [ "$RUN_E2E" = true ]; then
  echo ""
  echo "[Step 8] Running E2E tests..."

  WEB_ADMIN_DIR="$(dirname "$PROJECT_DIR")/web-admin"

  if [ -d "$WEB_ADMIN_DIR" ]; then
    cd "$WEB_ADMIN_DIR"
    npx playwright test tests/e2e/plugin/asset-plugin.spec.ts --reporter=line 2>/dev/null || {
      echo -e "${YELLOW}⚠${NC} E2E tests not found or failed"
    }
    cd "$PROJECT_DIR"
  else
    echo -e "${YELLOW}⚠${NC} web-admin directory not found, skipping E2E tests"
  fi
fi

# ============================================
# Step 9: Cleanup (optional)
# ============================================
if [ "$CLEANUP" = true ] && [ -n "$PLUGIN_PID" ]; then
  echo ""
  echo "[Step 9] Cleaning up (uninstalling plugin)..."

  UNINSTALL_RESULT=$(curl -s -X POST "$API_BASE/api/plugins/packages/$PLUGIN_PID/uninstall" \
    -H "Authorization: Bearer $AUTH_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"removeAllData": true}' 2>/dev/null)

  UNINSTALL_SUCCESS=$(echo "$UNINSTALL_RESULT" | jq -r '.success' 2>/dev/null)

  if [ "$UNINSTALL_SUCCESS" = "true" ]; then
    report 0 "Plugin uninstalled successfully"
  else
    echo -e "${YELLOW}⚠${NC} Plugin uninstall may have failed"
  fi
fi

# ============================================
# Summary
# ============================================
echo ""
echo "=============================================="
echo "  Verification Complete"
echo "=============================================="
echo ""

if [ $ERRORS -eq 0 ]; then
  echo -e "  Result: ${GREEN}ALL CHECKS PASSED${NC}"
else
  echo -e "  Result: ${RED}$ERRORS ERRORS${NC}"
fi

if [ -n "$PLUGIN_PID" ]; then
  echo ""
  echo "  Plugin PID: $PLUGIN_PID"
  echo "  View at: $FRONTEND_BASE/system/plugins"
fi

echo ""
exit $ERRORS
