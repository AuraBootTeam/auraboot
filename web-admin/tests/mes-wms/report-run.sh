#!/usr/bin/env bash
# Regenerate the self-contained MES/WMS evidence index from a live isolated stack.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_ROOT="$(git -C "${HERE}" rev-parse --show-toplevel)"
CANONICAL_CORE="$(cd "$(dirname "$(git -C "${CORE_ROOT}" rev-parse --git-common-dir)")" && pwd)"
WORKSPACE_ROOT="$(dirname "${CANONICAL_CORE}")"
BASE="${BASE:-http://127.0.0.1:5164}"
FIXED_BASE="${FIXED_BASE:-${BASE}}"
MUTANT_BASE="${MUTANT_BASE:-http://127.0.0.1:5264}"
PG_DB="${PG_DB:-auraboot_64}"
BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:6464}"
REPORT_GEN_DEFAULT="${WORKSPACE_ROOT}/.claude/skills/e2e-trust/scripts/report-gen.mjs"
GEN="${REPORT_GEN:-${REPORT_GEN_DEFAULT}}"

export BASE FIXED_BASE MUTANT_BASE PG_DB BACKEND_URL
export PG_HOST="${PG_HOST:-127.0.0.1}"
export PG_PORT="${PG_PORT:-5432}"
export PG_USER="${PG_USER:-auraboot}"
export PGPASSWORD="${PGPASSWORD:-auraboot}"
export ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
export ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"
export no_proxy="${no_proxy:-localhost,127.0.0.1}"

if [[ ! -f "${GEN}" ]]; then
  echo "report-gen.mjs not found at ${GEN}; set REPORT_GEN=<path>" >&2
  exit 2
fi

RUN="$(mktemp -d)"
trap 'rm -rf "${RUN}"' EXIT

run_node() {
  local name="$1"
  local dir="$2"
  local script="$3"
  local code=0
  (
    cd "${dir}"
    node "${script}"
  ) >"${RUN}/${name}.log" 2>&1 || code=$?
  echo "${code}" >"${RUN}/${name}.exit"
  echo "  ${name}: exit=${code} $(tail -n 1 "${RUN}/${name}.log")"
  if [[ "${code}" -ne 0 ]]; then
    tail -n 20 "${RUN}/${name}.log"
  fi
}

run_shell() {
  local name="$1"
  local script="$2"
  local code=0
  "${script}" >"${RUN}/${name}.log" 2>&1 || code=$?
  echo "${code}" >"${RUN}/${name}.exit"
  echo "  ${name}: exit=${code} $(tail -n 1 "${RUN}/${name}.log")"
  if [[ "${code}" -ne 0 ]]; then
    tail -n 20 "${RUN}/${name}.log"
  fi
}

echo "[report-run] backend goldens: ${BACKEND_URL} / ${PG_DB}"
run_node mes-wms-backend-golden "${HERE}" mes-wms-backend-golden.mjs
run_node fr10-fefo-golden "${HERE}" fr10-fefo-golden.mjs
run_node fr08-12-14-golden "${HERE}" fr08-12-14-golden.mjs
run_node fr08-12-14-deep-golden "${HERE}" fr08-12-14-deep-golden.mjs

echo "[report-run] browser goldens: ${BASE}"
run_node mes-wms-yellow-fr-golden "${HERE}/ui" mes-wms-yellow-fr-golden.mjs
run_node fr07-action-golden "${HERE}/ui" fr07-action-golden.mjs
run_node fr22-action-golden "${HERE}/ui" fr22-action-golden.mjs
run_node mes-action-points-golden "${HERE}/ui" mes-action-points-golden.mjs
run_node mes-wms-ui-golden "${HERE}/ui" mes-wms-ui-golden.mjs
run_node list-interaction-golden "${HERE}/ui" list-interaction-golden.mjs

echo "[report-run] mutation discriminator: mutant=${MUTANT_BASE} fixed=${FIXED_BASE}"
run_shell list-action-error-mutation "${HERE}/run-list-action-error-mutation.sh"

cp -f "${HERE}/ui/"*.png "${RUN}/" 2>/dev/null || true
cp -f "${HERE}/"*.png "${RUN}/" 2>/dev/null || true

echo "[report-run] render evidence index"
node "${GEN}" \
  --manifest "${HERE}/report-manifest.json" \
  --run-dir "${RUN}" \
  --out "${HERE}/mes-wms-acceptance-report.html"
echo "[report-run] ${HERE}/mes-wms-acceptance-report.html"
