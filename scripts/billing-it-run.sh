#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_ROOT="$REPO_ROOT/platform"
RESULT_DIR="$PLATFORM_ROOT/build/test-results/test"

# Exact class filters are intentional. Gradle's trailing wildcard form
# '*SomeIT*' can match zero tests while the task itself still exits cleanly.
TEST_CLASSES=(
  com.auraboot.framework.billing.account.AccountResolverCrossTenantIT
  com.auraboot.framework.billing.account.AccountResolverIT
  com.auraboot.framework.billing.account.BillingAccountFkIntegrityIT
  com.auraboot.framework.billing.catalog.ResourceCatalogServiceIntegrationTest
  com.auraboot.framework.billing.metering.MeteringServiceIntegrationTest
  com.auraboot.framework.billing.observability.BillingCoreMetricsTest
  com.auraboot.framework.billing.quota.QuotaPriorityIntegrationTest
  com.auraboot.framework.billing.quota.QuotaProvisionIntegrationTest
  com.auraboot.framework.billing.quota.QuotaServiceIntegrationTest
  com.auraboot.framework.billing.quota.service.QuotaReserveConcurrencyGuardTest
)

gradle_args=(:test --no-daemon --console=plain)
for class_name in "${TEST_CLASSES[@]}"; do
  gradle_args+=(--tests "$class_name")
  rm -f "$RESULT_DIR/TEST-${class_name}.xml"
done

set +e
(cd "$PLATFORM_ROOT" && ./gradlew "${gradle_args[@]}")
gradle_status=$?
set -e

evidence_status=0
total_tests=0
for class_name in "${TEST_CLASSES[@]}"; do
  xml="$RESULT_DIR/TEST-${class_name}.xml"
  if [[ ! -f "$xml" ]]; then
    echo "[billing-it] FAIL no XML: $class_name" >&2
    evidence_status=1
    continue
  fi

  suite_line="$(grep -m1 '<testsuite ' "$xml" || true)"
  tests="$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' <<<"$suite_line")"
  failures="$(sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p' <<<"$suite_line")"
  errors="$(sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p' <<<"$suite_line")"

  if [[ -z "$tests" || "$tests" == "0" ]]; then
    echo "[billing-it] FAIL zero tests: $class_name" >&2
    evidence_status=1
    continue
  fi
  if [[ "${failures:-0}" != "0" || "${errors:-0}" != "0" ]]; then
    echo "[billing-it] FAIL assertions: $class_name failures=${failures:-?} errors=${errors:-?}" >&2
    evidence_status=1
    continue
  fi

  total_tests=$((total_tests + tests))
  echo "[billing-it] PASS $class_name tests=$tests"
done

if [[ "$gradle_status" != "0" || "$evidence_status" != "0" ]]; then
  echo "[billing-it] FAILED gradle_status=$gradle_status evidence_status=$evidence_status" >&2
  exit 1
fi

echo "[billing-it] PASS classes=${#TEST_CLASSES[@]} tests=$total_tests"
