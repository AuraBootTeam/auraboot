#!/usr/bin/env bash
set -euo pipefail

node scripts/validate-public-record-id-contracts.mjs \
  --oss-only \
  --baseline=scripts/public-record-id-baseline.json

node --test scripts/validate-public-record-id-contracts.test.mjs
node --test scripts/check-public-record-openapi-contract.test.mjs
