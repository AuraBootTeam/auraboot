# Phase 3 — CI matrix recommendation (PR-gate vs nightly)

Recommendation only — **does not change any workflow yet**. Inputs:
[`phase3-stack-timings.md`](./phase3-stack-timings.md) and the existing
ga-e2e workflow stack (`docker-compose.ga-e2e.override.yml`).

## Goal

Decide which Playwright suites run on every PR (must be fast and
high-signal) vs. which run nightly (full-coverage, may be slow or
flaky-tolerant).

## Inputs

- **r2 isolated stack** (host machine, image cache warm):
  - cold start ≈ 266 s (~4.5 min);
  - warm restart ≈ 28 s.
- **GitHub Actions runner** has no docker layer cache between jobs,
  so the cold cost there is dominated by `docker build` of the backend
  image (currently not measured here — historical data on `main` puts
  full first-run image build at 8–12 min on a default-tier runner).
- **OSS suite size** (from `oss-scope.json`): `chromium` ≈ 540 specs;
  `chromium-deep` ≈ 60 specs; setup/auth ≈ 5 specs.
- Past full-suite r2 run: ~22 min wall-clock at 4-worker parallelism.

## Proposed matrix

### PR-gate (every push, must finish ≤ 10 min wall-clock)

| Job | Suite | Approx wall-clock | Why on PR gate |
|---|---|---|---|
| `oss-smoke` | `tests/e2e/smoke/**` (~30 specs) on r2 stack | 3-4 min (cold start factored in once per run) | Catches platform-wide regressions (login / page-render / a critical happy path per plugin) |
| `oss-typecheck-lint` | `pnpm typecheck` + `pnpm lint` + `pnpm test:env-lint` | ~2 min | Cheap, blocks all visible drift |
| `oss-backend-it-smoke` | curated 1-tag IT subset | 4-5 min | Already enforced; keep |

PR-gate **excludes** `chromium-deep` and the long-tail of `chromium`
specs because their false-positive rate (cross-DB, flaky timing) costs
more than they catch on a per-PR basis.

### Nightly (cron 02:00 UTC, full coverage)

| Job | Suite | Approx wall-clock |
|---|---|---|
| `oss-full-chromium` | full `chromium` project on r2 stack | 22-28 min |
| `oss-deep` | `chromium-deep` project | 8-12 min |
| `oss-flaky-rerun` | failed-only re-run of above with retries: 2 | 5-10 min |
| `enterprise-overlay` | enterprise-suite parity run on `enterprise-env-export.sh` profile | TBD |

Nightly results post a digest comment on `main`; failures don't block
PRs but file a `nightly-fail` label for triage.

### On-demand label (`run-full-e2e`)

For risky PRs (touching auth / bootstrap / env contract), allow opting
in to the full suite via PR label. Same workflow as nightly but
attached to the PR.

## Justification — why not run full suite on PR gate

- **Cost per PR**: full suite ≈ 25 min × N revisions = burns runner
  minutes for low marginal signal vs. smoke + nightly aggregate.
- **Cold-start cost amortizes poorly**: even on warm host (~28 s) the
  CI runner has to cold-start every job (no shared docker layer cache
  between jobs unless explicitly persisted).
- **False-positive rate**: long-tail specs were the source of the 95
  cross-DB false-positives uncovered on `fix/oss-suite-r2`; running
  them on every PR generates triage noise.

## Migration plan (out of scope of Phase 3 — capture for owner)

1. Define `tests/e2e/smoke/` directory + tag subset.
2. Add nightly workflow `oss-nightly.yml` mirroring current `ga-e2e`
   but on cron schedule.
3. Trim PR-gate workflow to smoke-only.
4. Add label-based opt-in (`run-full-e2e`).

Each step lands as a separate PR so we can revert independently if
the smoke set turns out to under-cover a real-world regression
class.
