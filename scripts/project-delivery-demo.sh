#!/usr/bin/env bash
# Explicit local evaluation setup; never clears or replaces existing business data.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
export BACKEND_URL="${BACKEND_URL:-http://localhost:3000}"
export ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
export ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
for tool in bash curl python3; do
  command -v "$tool" >/dev/null || { printf 'Install %s before continuing.\n' "$tool" >&2; exit 2; }
done
python3 - "$BACKEND_URL" <<'PY'
import sys
from urllib.parse import urlparse
if sys.version_info < (3, 8):
    raise SystemExit('Python 3.8 or later is required.')
url = urlparse(sys.argv[1])
if url.scheme != 'http' or url.hostname not in ('localhost', '127.0.0.1') or url.username or url.password:
    raise SystemExit('Project delivery demo setup only supports your own local HTTP instance.')
PY
bash "$SCRIPT_DIR/quickstart.sh" --project-delivery
export DELIVERY_BACKEND_URL="$BACKEND_URL"
export DELIVERY_EVIDENCE_ROOT="${DELIVERY_EVIDENCE_ROOT:-$(mktemp -d "${TMPDIR:-/tmp}/auraboot-project-delivery.XXXXXX")}"
python3 "$PROJECT_ROOT/plugins/project-management/scripts/seed-delivery.py"
printf '\nProject delivery is ready: %s/p/tpm_project\n' "$BACKEND_URL"
printf 'Open Project delivery > Projects. Sample customers and projects are fictional.\n'
printf 'Setup receipt: %s\n' "$DELIVERY_EVIDENCE_ROOT"
