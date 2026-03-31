#!/usr/bin/env bash
# sync-dsl-registry.sh — Extract enums from backend Java DslRegistry and update dsl-registry.json
#
# Usage:
#   ./scripts/sync-dsl-registry.sh            # Use live backend if running
#   ./scripts/sync-dsl-registry.sh --offline  # Parse Java source directly (no backend needed)
#
# The registry file is the single source of truth consumed by:
#   - CLI validation (plugins/cli/src/utils/dsl-registry-loader.ts)
#   - Frontend chart type validation (SharedChartFactory.ts)
#   - Agent tooling (AgentToolAutoGenerator.java)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
REGISTRY_FILE="$REPO_ROOT/plugins/schemas/dsl-registry.json"
BACKEND_URL="${AURA_BACKEND_URL:-http://localhost:6443}"
JAVA_SOURCE="$REPO_ROOT/platform/src/main/java/com/auraboot/framework/meta/constant/DslRegistry.java"

MODE="${1:-}"

echo "=== AuraBoot DSL Registry Sync ==="
echo ""

# ────────────────────────────────────────────────────────────────────────────
# Mode 1: Fetch from live backend (preferred — exact runtime values)
# ────────────────────────────────────────────────────────────────────────────
if [[ "$MODE" != "--offline" ]]; then
  echo "Attempting live sync from $BACKEND_URL/api/dsl/registry ..."

  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND_URL/actuator/health" 2>/dev/null || echo "000")

  if [[ "$HTTP_STATUS" == "200" ]]; then
    echo "Backend is up — fetching registry..."

    TOKEN_RESPONSE=$(curl -s -X POST "$BACKEND_URL/api/auth/login" \
      -H "Content-Type: application/json" \
      -d '{"email":"admin@example.com","password":"Test2026x"}' 2>/dev/null || echo "")

    JWT=$(echo "$TOKEN_RESPONSE" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('jwt',''))" 2>/dev/null || echo "")

    if [[ -n "$JWT" ]]; then
      REGISTRY_RESPONSE=$(curl -s -w "\n%{http_code}" "$BACKEND_URL/api/dsl/registry" \
        -H "Authorization: Bearer $JWT" 2>/dev/null || echo "")

      REGISTRY_STATUS=$(echo "$REGISTRY_RESPONSE" | tail -1)
      REGISTRY_BODY=$(echo "$REGISTRY_RESPONSE" | head -n -1)

      if [[ "$REGISTRY_STATUS" == "200" ]] && [[ -n "$REGISTRY_BODY" ]]; then
        echo "$REGISTRY_BODY" > "$REGISTRY_FILE"
        echo "Registry updated from live backend."
        echo "Saved to: $REGISTRY_FILE"
        echo ""
        echo "EnumCount: $(echo "$REGISTRY_BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('enums',{})))" 2>/dev/null)"
        exit 0
      else
        echo "Warning: /api/dsl/registry returned $REGISTRY_STATUS — falling back to offline mode."
      fi
    else
      echo "Warning: Could not authenticate — falling back to offline mode."
    fi
  else
    echo "Backend not reachable (health check: $HTTP_STATUS) — falling back to offline mode."
  fi

  echo ""
fi

# ────────────────────────────────────────────────────────────────────────────
# Mode 2: Parse Java source directly (offline mode)
# ────────────────────────────────────────────────────────────────────────────
echo "Parsing Java source: $JAVA_SOURCE"

if [[ ! -f "$JAVA_SOURCE" ]]; then
  echo "Error: DslRegistry.java not found at $JAVA_SOURCE"
  exit 1
fi

EXPORTED_AT=$(date -u +"%Y-%m-%dT%H:%M:%S.000000Z")

python3 << PYTHON_EOF
import json, re, sys

java_file = """$JAVA_SOURCE"""
registry_file = """$REGISTRY_FILE"""
exported_at = """$EXPORTED_AT"""

with open(java_file, 'r') as f:
    source = f.read()

# Extract all enum blocks: "public enum EnumName implements DslEnum { ... }"
enum_pattern = re.compile(
    r'public\s+enum\s+(\w+)\s+implements\s+DslEnum\s*\{([^}]+)\}',
    re.DOTALL
)

# Extract individual entries: NAME("code", "label", "since"),
entry_pattern = re.compile(
    r'(\w+)\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)'
)

enums = {}
for m in enum_pattern.finditer(source):
    enum_name = m.group(1)
    enum_body = m.group(2)
    entries = []
    for e in entry_pattern.finditer(enum_body):
        entries.append({
            "code": e.group(2),
            "label": e.group(3),
            "since": e.group(4)
        })
    if entries:
        enums[enum_name] = entries

# Load existing registry to preserve extensions and mappings sections
try:
    with open(registry_file, 'r') as f:
        existing = json.load(f)
except Exception:
    existing = {}

registry = {
    "version": "2.0",
    "exportedAt": exported_at,
    "enums": enums,
    "extensions": existing.get("extensions", {}),
    "mappings": existing.get("mappings", {})
}

with open(registry_file, 'w') as f:
    json.dump(registry, f, indent=4, ensure_ascii=False)

print(f"Registry written with {len(enums)} enums:")
for name, entries in sorted(enums.items()):
    print(f"  {name}: {len(entries)} values")
PYTHON_EOF

echo ""
echo "Done. Saved to: $REGISTRY_FILE"
echo ""
echo "Next steps:"
echo "  1. Review the updated registry: cat plugins/schemas/dsl-registry.json | jq '.enums | keys'"
echo "  2. Commit: git add plugins/schemas/dsl-registry.json && git commit -m 'chore(meta): sync dsl-registry.json'"
echo "  3. Rebuild CLI: cd plugins/cli && pnpm build"
