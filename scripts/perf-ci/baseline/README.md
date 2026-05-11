# Performance Regression Baselines

This directory holds the **reference k6 summary JSON files** used by `run-perf-regression.sh`
when comparing current run results against established baselines.

## File Naming Convention

```
<label>-baseline.json
```

| Label     | k6 script              | Description                     |
|-----------|------------------------|---------------------------------|
| `auth`    | `auth-baseline.js`     | Login / token acquisition       |
| `list`    | `list-query.js`        | List endpoint with pagination   |
| `command` | `command-execution.js` | Command execution (write paths) |

## How to Capture a New Baseline

1. Ensure k6 and jq are installed:
   ```bash
   k6 version
   jq --version
   ```

2. Ensure the server is running and warmed up:
   ```bash
   curl -s http://localhost:6443/actuator/health
   ```

3. Run the full public benchmark suite:
   ```bash
   BASE_URL=http://localhost:6443 \
   USERNAME=admin@auraboot.com \
   PASSWORD=Test2026x \
   scripts/perf-ci/run-perf-regression.sh --profile smoke
   ```

4. Run the relevant k6 test with `--summary-export`:
   ```bash
   k6 run \
     --env BASE_URL=http://localhost:6443 \
     --env USERNAME=admin@auraboot.com \
     --env PASSWORD=Test2026x \
     --summary-export scripts/perf-ci/baseline/auth-baseline.json \
     tests/load/k6/auth-baseline.js
   ```

5. Repeat for `list-query.js` → `list-baseline.json`
   and `command-execution.js` → `command-baseline.json`.

6. Commit the updated baselines:
   ```bash
   git add scripts/perf-ci/baseline/
   git commit -m "perf: update regression baselines $(date +%Y-%m-%d)"
   ```

## When to Update Baselines

- After an **intentional performance improvement** — capture the better numbers as the new standard.
- After a **deliberate architectural change** that shifts expected latencies (document the reason in the commit message).
- **Never** update baselines to "fix" a regression without first understanding and resolving the root cause.

## Thresholds (reference)

| Metric     | Warning    | Critical   |
|------------|------------|------------|
| p50 (med)  | +15%       | +30%       |
| p95        | +20%       | +50%       |
| p99        | +35%       | +80%       |
| error rate | +0.5 pp    | +2 pp      |

Full algorithm: `scripts/perf-ci/compare-baseline.sh`
