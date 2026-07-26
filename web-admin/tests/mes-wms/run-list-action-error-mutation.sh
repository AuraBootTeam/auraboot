#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXED_BASE="${FIXED_BASE:-http://127.0.0.1:5263}"
MUTANT_BASE="${MUTANT_BASE:-http://127.0.0.1:5264}"
SOURCE_PATH="/app/framework/meta/rendering/pages/ListPageContent.tsx"

fixed_source="$(curl --fail --silent --show-error "${FIXED_BASE}${SOURCE_PATH}")"
mutant_source="$(curl --fail --silent --show-error "${MUTANT_BASE}${SOURCE_PATH}")"

if grep -Fq 'setError(err.message)' <<<"${fixed_source}"; then
  echo "[mutation] fixed Vite unexpectedly contains setError(err.message)" >&2
  exit 1
fi
if ! grep -Fq 'setError(err.message)' <<<"${mutant_source}"; then
  echo "[mutation] mutant Vite does not contain the required setError(err.message) mutation" >&2
  exit 1
fi

echo "[mutation] source sentinels verified; running RED mutant then GREEN fixed"
BASE="${MUTANT_BASE}" EXPECT_ACTION_ERROR=blanked \
  node "${HERE}/ui/list-action-error-mutation-golden.mjs"
BASE="${FIXED_BASE}" EXPECT_ACTION_ERROR=retained \
  node "${HERE}/ui/list-action-error-mutation-golden.mjs"
