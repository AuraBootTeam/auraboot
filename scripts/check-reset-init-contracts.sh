#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo "[reset-init-contracts] shell syntax"
bash -n scripts/oss-reset-and-init.sh
bash -n scripts/reset-db.sh
bash -n scripts/import-plugins.sh
bash -n scripts/seed-marketplace.sh
bash -n scripts/sync-marketplace-catalog.sh
bash -n scripts/docker-ga-e2e-bootstrap.sh
bash -n scripts/docker-ga-showcase-e2e.sh
bash -n scripts/env/reset-and-init.sh

echo "[reset-init-contracts] node regression"
node --test scripts/reset-init-contracts.test.mjs
node --test scripts/audit-oss-plugins.test.mjs
node web-admin/scripts/run-showcase-seed-sequence.test.mjs

echo "[reset-init-contracts] OK"
