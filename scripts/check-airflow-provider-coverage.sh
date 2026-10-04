#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/airflow-provider"
PYTHON="${AIRFLOW_PROVIDER_PYTHON:-$PROJECT/.venv/bin/python}"
[[ -x "$PYTHON" ]] || { echo 'Install the isolated Airflow test environment documented in airflow-provider/README.md' >&2; exit 2; }
cd "$PROJECT"
export AIRFLOW_HOME="$PROJECT/.venv/airflow-home"
export AIRFLOW__CORE__UNIT_TEST_MODE=True
export AIRFLOW__CORE__LOAD_EXAMPLES=False
export PYTEST_ADDOPTS=''

# DataFrame behavior is part of the denominator; an absent optional dependency
# must fail setup rather than silently skip those tests.
"$PYTHON" -c 'import airflow, pandas, pytest_cov, responses'
"$PYTHON" -m pytest tests --cov=airflow_provider_auraboot --cov-branch \
  --cov-report=term-missing --cov-report=json:coverage-unit.json --junitxml=coverage-unit.xml
"$PYTHON" - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('coverage-unit.xml').getroot()
suites = list(root.iter('testsuite'))
tests = sum(int(suite.get('tests', 0)) for suite in suites)
skips = sum(int(suite.get('skipped', 0)) for suite in suites)
if tests == 0 or skips:
    raise SystemExit(f'Invalid test evidence: tests={tests}, skipped={skips}')
PY
"$PYTHON" scripts/check_coverage.py coverage-unit.json
