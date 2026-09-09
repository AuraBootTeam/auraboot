#!/usr/bin/env bash
# CRM full-matrix e2e corpus runner (slot 105 golden stack).
set -uo pipefail
cd /Users/ghj/work/auraboot/auraboot/web-admin

export PLAYWRIGHT_BASE_URL=http://localhost:5205
export BACKEND_URL=http://localhost:6505
export BE_PORT=6505
export BFF_PORT=6205
export PG_DB=auraboot_105
export PW_SKIP_WEBSERVER=1
export AURA_EVIDENCE_ROOT=/Users/ghj/work/auraboot/.workspace/evidence/crm-full-matrix-s105
export AURA_RUNTIME_NAME=crm-full-matrix
export CRM_CONTACT_SAVED_VIEW_RUN_ID=sv-20260907-crmfull
export CRM_CONTACT_SAVED_VIEW_ADMIN_EMAIL=admin@auraboot.com
export CRM_CONTACT_SAVED_VIEW_ADMIN_PASSWORD=Test2026x
export CRM_CONTACT_SAVED_VIEW_PERSONA_PASSWORD=Test2026x
export CRM_ACCOUNT_MANAGEMENT_RUN_ID=acct-20260907-crmfull
export CRM_ACCOUNT_IMPORT_RUN_ID=acctimp-20260907-crmfull
export CRM_LEAD_MANAGEMENT_RUN_ID=lead-20260907-crmfull
export CRM_IMPORT_EVIDENCE_DIR=/Users/ghj/work/auraboot/.workspace/evidence/crm-full-matrix-s105/imports
export CRM_IMPORT_RUNTIME_NAME=crm-full-matrix
export CRM_IMPORT_RUNTIME_SLOT=105
export PW_ADMIN_STORAGE_STATE=./tests/storage/admin.json
export PW_OPERATOR_STORAGE_STATE=./tests/storage/operator.json
export PW_STORAGE_DIR=./tests/storage
export no_proxy='*'
export NO_PROXY='*'

FILES="tests/api/setup tests/auth.setup.ts $(ls tests/e2e/crm/*.spec.ts | tr '\n' ' ')"

if [ "${1:-}" = "--list" ]; then
  exec npx playwright test $FILES --list
fi
exec npx playwright test $FILES --workers=1 --retries=0
