# AMOS-T01 reliable integration runtime evidence

Verdict: **11 pass, 0 fail, 0 skipped, 3 untested**. This is a verified Core runtime and
contract result, not an assembled Procurement-to-Inventory business-journey result.

## Reproduction

The test runtime was `amos-t01-reliable-dev-20260823`, backed by isolated PostgreSQL database
`auraboot_6`. After sourcing that runtime environment, `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` were set from the managed database values.

```text
./dev.sh gradle amos-t01-reliable-dev-20260823 --project <worktree>/platform -- :test \
  --tests com.auraboot.framework.integration.ReliableIntegrationRuntimeIT \
  --tests com.auraboot.framework.integration.IntegrationBackoffPolicyTest \
  --tests com.auraboot.framework.meta.service.impl.OutboxWorkerImplCoverageIT

./dev.sh gradle amos-t01-reliable-dev-20260823 --project <worktree>/platform -- \
  :platform-plugin-api:test \
  --tests com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelopeTest
```

Both Gradle invocations completed with `BUILD SUCCESSFUL`. The four JUnit XML files record nine
tests, zero failures, zero errors, and zero skipped tests. The JaCoCo report records 121 covered and
9 missed lines for `com/auraboot/framework/integration` (93.1%) and 39 covered / 7 missed lines for
`IntegrationEventEnvelope` (84.8%).

Fresh-database migration and schema evidence:

```text
scripts/db/generate-schema-snapshot.sh --edition oss
scripts/db/check-schema-drift.sh
```

The drift gate reported that the committed snapshot matches the result of all 78 Flyway migrations.

## Falsifiability

The ordering predicate was temporarily mutated from predecessor sequence `<` to `>` before the
final run. The real PostgreSQL lifecycle test failed at the ordering assertion because sequence 2
remained pending while sequence 1 was incorrectly fenced. Restoring `<` made the same test pass.
The final evidence files are from the restored implementation.

The envelope payload copy was also temporarily mutated back to `Map.copyOf`. The hash-pinned T04
`release-v2.json` contract test failed with `NullPointerException` at envelope construction because
`specificationVersion` is a legal JSON null. Restoring the recursive null-safe snapshot made the
same exact-fixture test pass.

## Evidence map

- `acceptance-manifest.json`: denominator, four-axis classification, verdict, and explicit gaps.
- `TEST-*.xml`: machine-readable contract, lifecycle, backoff, and legacy-worker test results.
- `jacoco-summary.json`: extracted package/class counters from the final JaCoCo XML reports; the
  full reports remain reproducible local build artifacts and are not committed.
- [`runtime contract`](../../../system-reference/reliable-integration-runtime-v1.md) and
  [`Procurement-to-Inventory fixture`](../../../system-reference/fixtures/integration-runtime/v1/procurement-inventory-receipt-request.json).
