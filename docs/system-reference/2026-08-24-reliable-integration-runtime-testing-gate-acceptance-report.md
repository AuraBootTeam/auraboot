---
type: system-reference
status: active
created: 2026-08-24
---

# Reliable integration runtime v1 testing-gate acceptance report

AMOS-T01 is accepted for its owned Core runtime and public contract surface: **10 pass, 0 fail,
0 skipped, 3 untested**. It is not accepted as a complete assembled Procurement-to-Inventory
journey because the real T02 producer/adapter and T10 composition are not on this branch.

The executable denominator and machine evidence are in the
[`acceptance manifest`](../e2e/evidence/amos-t01-reliable-integration-2026-08-24/acceptance-manifest.json)
and its adjacent JUnit/JaCoCo artifacts. The tests cover source transaction atomicity and fail-closed
behavior, crash/restart, duplicate delivery, ordering, poison/DLQ, lease takeover, attributed replay,
reconciliation, counters, and health alerts against isolated PostgreSQL.

The runtime package reached 121 covered and 9 missed lines (93.1%). A mutation that reversed the
ordering fence caused the real-stack lifecycle test to fail before the final restored green run.
No test was skipped or retried.

## Residual dependencies

- T02 must bind the versioned fixture to the real Procurement source transaction and Inventory
  receipt command, then prove the business records and the runtime receipt commit together.
- T05 must validate its consumer after T04 freezes the Quality Hold event types.
- T10 must compose exact PR heads, regenerate the shared schema snapshot, and rerun the migration,
  contract, and lifecycle gates. This lane does not edit the central AMOS ledger.
