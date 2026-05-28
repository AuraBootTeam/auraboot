# APS V2 Strategy Comparison Fixtures

Synthetic PCBA planned-order / resource / calendar fixtures and a comparison
harness for `/api/manufacturing/aps/schedule/v2`.

## What's here

| File | Purpose |
|---|---|
| `seed.sql` | 50 planned orders + 5 resources + 30-day calendar, idempotent insert |
| `compare-strategies.sh` | Runs each of 5 strategies, prints scheduledCount / conflictCount / makespan / runtime |
| `clear.sql` | Cleans the fixtures (delete by `pe_plo_remark='APS_FIXTURE'`) |

## Prerequisites

- PCBA plugin imported into the target tenant (mt_pe_* tables exist)
- A docker-isolated stack OR host backend with PCBA plugin loaded
- `PGURL`, `BACKEND_URL`, `AUTH_TOKEN`, `TENANT_ID` env vars set

## Run

```bash
# 1. seed
psql "$PGURL" -f scripts/aps-fixtures/seed.sql

# 2. compare 5 strategies (sequentially, clearing between runs)
bash scripts/aps-fixtures/compare-strategies.sh

# 3. (optional) clean up
psql "$PGURL" -f scripts/aps-fixtures/clear.sql
```

## Sample output

```
strategy=forwardFifo     scheduled=48 conflict=2  makespan=14d  runtime=312ms
strategy=forwardEdd      scheduled=50 conflict=0  makespan=11d  runtime=287ms
strategy=backward        scheduled=49 conflict=1  makespan=12d  runtime=302ms
strategy=bottleneckFirst scheduled=50 conflict=0  makespan=10d  runtime=421ms
strategy=genetic         scheduled=50 conflict=0  makespan=9d   runtime=4812ms

best_by_makespan: genetic (9d)
best_by_throughput: forwardEdd / bottleneckFirst / genetic (50/50)
best_by_runtime: forwardEdd (287ms)
```

## Use for V2 decisions

- If `genetic` consistently wins makespan but is > 5s for 50 orders, may not scale to 500 → consider OR-Tools as V3
- If `forwardEdd` and `bottleneckFirst` are within 10% of `genetic` makespan, GA is overkill → default to `forwardEdd`
- `conflictCount > 0` on any strategy = bottleneck resource or unrealistic due dates → revisit fixtures or add more capacity
