---
type: retro
status: active
created: 2026-06-11
---

# OSS test-coverage-to-80 session — deep retro (2026-06-11)

Task: "对 OSS 所有模块增强单元/集成测试,整体覆盖率到 80%,给我建议" → escalated to autonomous
execution. Outcome: **13 PRs merged to main** (#527, #529, #531–#535, #540–#544, #555).

## 1. What actually shipped

| Area | Before | After | How |
|---|---|---|---|
| Backend coverage gate | LINE floor **0.50** (actual 80%, so it caught nothing) | **0.78** then **0.68** (honest denom) | #527, #555 |
| Backend test-infra | full IT suite **exhausted PostgreSQL max_connections=400** | bounded `spring.test.context.cache.maxSize=8` | #527 |
| Backend honesty | bundle **80.1%** on a denominator that **excluded the command pipeline** | **70.2%** with `meta/service/impl` measured | #555 |
| Frontend coverage | **19.08%** line, no coverage gate wired (provider declared, unused) | **25.61%** line, ratchet floor 25, +1500 tests (2099→3599 all green) | #529–#544 |
| Tracker / plan | none | `docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md` | #527 |

8 parallel sub-agents wrote the frontend tests; each output was independently re-verified by the
main loop (commit is test-only, 0 `.skip`/`.todo`, re-ran green, commit oid on the branch). The
sub-agents caught **real source bugs** while testing: `feedback.ts` error path (`String(null)` =
`'null'` is truthy, so the fallback message is unreachable), `signals.ts` `effect()` (exponential
closure growth → OOM), `EventBus.destroy()` (no `destroyed` flag), `schemaUtils.makeSchema` (spread
order overwrote overrides), ICU plural / `validateExpression` edge cases.

## 2. The central finding — the headline number was a lie

The single most important thing this session uncovered: **the project's "80% coverage" was an
artefact of a dishonest jacoco exclude list, not real test strength.**

- The committed jacoco report read **7.1%** — it was a *partial single-test run*, not a baseline.
  The real full-suite number was **80.1%**.
- But that 80.1% was computed over a denominator that excluded `meta/service/impl` (the command
  pipeline + metadata orchestration — the literal core of the platform), plus `bpm/finance/aps/mrp/
  agent/ai/...`. Measurement showed `meta/service/impl` is **already 47% covered** by 47 existing
  test files — it was never "untestable", it was just *hidden*.
- Measuring the real business logic (excluding only controllers [E2E] + data-layer + infra adapters)
  gives **70.1% line / 31% branch**. The genuinely-low areas are **controllers** (meta 18.8%, bpm
  10.2%, email 2.7%) — which are correctly E2E territory.

So the org was tracking a number that **went up by hiding code, not by testing it.** Un-excluding
the core (#555) dropped the headline 80.1% → 70.2% — *the number went down but the scope went up;
that is more rigorous, not less.*

## 3. Why were there so many problems? (root-cause attribution)

The user asked directly: gate quality, insufficient input, or bad prompts? Honest breakdown:

### 3a. Primarily — GATE / TEST-INFRA QUALITY (the dominant cause, ~60%)
This is where the real defects were, and they were **pre-existing**, not introduced this session:
1. **Dishonest coverage denominator.** The jacoco exclude list hid the orchestration core, inflating
   the bundle. A coverage gate that can be satisfied by *excluding tested code* is worse than no gate
   — it actively misleads. **This is a gate-quality defect.**
2. **A floor of 0.50 against an 80% actual catches nothing.** The gate existed but was set so far
   below reality it never fired. A ratchet must sit just under actual.
3. **Latent shared-DB DoS.** Running the full IT suite pinned PostgreSQL `max_connections=400`
   (dozens of cached `@SpringBootTest` contexts × HikariCP pools) and broke concurrent sessions.
   No gate or doc warned of this; it only surfaced under a full run. **Test-infra defect.**
4. **Frontend coverage was declared but never wired.** `@vitest/coverage-v8` was in the lockfile but
   no `coverage` config / threshold existed; the checkout wasn't even `pnpm install`-ed. The frontend
   had effectively zero coverage governance.

### 3b. Secondarily — AMBIGUOUS INPUT (~20%)
- "整体覆盖率到 80%" is ambiguous: 80% of *what*? The curated set? The full codebase? Including
  controllers? This genuinely changed the scope by ~10×, and had to be resolved with a scope
  question. Not a fault — but a reminder that "X% coverage" is underspecified without a denominator.
- The stale 7.1% committed report was misleading input that cost an investigation loop.

### 3c. Least — MY OWN PROCESS ERRORS (~20%, and worth owning)
These were self-inflicted and slowed things down:
1. **Trusted the stale 7.1% report too long** before regenerating — should have suspected a partial
   run immediately (an exec that small + a 50% gate that "passes" at 7.1% is contradictory).
2. **`nohup ... &` background launch got reaped** when the Bash tool returned — should have used the
   harness's `run_in_background` from the start (cost one wasted ~partial run + corrupted exec).
3. **Over-corrected the connection issue with a global env-var HikariCP cap**
   (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2`) which tripped `HikariConfig` validation and
   poisoned the context cache → 2700-test cascade. The right fix (bound the *context cache*, not the
   pool) was found on the next attempt. One wasted full run.
4. **Mild oscillation** on "is the session done?" across several `继续` turns before committing to a
   clear stance (vitest has a ~30% ceiling; rest is E2E).

**Net:** the friction was *mostly the codebase's pre-existing coverage/test-infra debt surfacing
under real scrutiny* — exactly what a "raise coverage to 80%" task is supposed to expose. The prompts
were fine; the input was ambiguous in one resolvable way; a few of my diagnostic steps were
over-eager and should have been verified-first.

## 4. Improvements (what should change)

1. **Coverage gates must measure honestly.** Exclude only data-layer + controllers(E2E) +
   infra-adapters. Never exclude a service/engine/orchestration package to make the number look
   better. If a package is "tested via E2E", prove it with E2E coverage, don't just drop it.
2. **A coverage floor must be a ratchet at-or-just-under actual**, raised in lockstep. A floor 30
   points below actual is decorative.
3. **Frontend needs a real coverage metric.** vitest covers logic (~30% line ceiling); UI is
   Playwright. "80% frontend vitest line coverage" is the wrong target — either merge vitest + E2E
   V8 coverage, or define the target as "vitest logic ≥ X% + E2E UI flows".
4. **Bound the Spring TestContext cache** in any large IT suite sharing one DB.
5. **Measure before planning.** The whole first hour was spent fighting to get a *real* baseline; the
   stale report nearly anchored the plan to a false 7.1%. Always regenerate coverage from a known
   full run before citing a number.

## 5. Lessons to 固化 into canonical (AGENTS.md / agent-rules)

Promoted to `auraboot-enterprise/docs/agent-rules/engineering-gotchas/test-infra.md` (this session):
- **G-cov-1 Coverage-gate honesty**: excludes must not hide tested business logic to inflate the
  number; only data-layer + controllers(E2E) + infra-adapters may be excluded; verify a package is
  actually 0% before excluding it (`meta/service/impl` was 47%, not 0%).
- **G-cov-2 Ratchet floor**: a coverage floor must sit just under measured actual; a floor far below
  actual catches no regression.
- **G-cov-3 Full IT exhausts shared Postgres**: bound `spring.test.context.cache.maxSize` (the suite
  has dozens of `@SpringBootTest` contexts × HikariCP pools → `max_connections=400` exhaustion →
  breaks concurrent sessions). NEVER globally rewrite the pool via `SPRING_DATASOURCE_HIKARI_*` env
  (trips `HikariConfig` validation → poisons the context cache → mass `Failed to load
  ApplicationContext`).
- **G-cov-4 Coverage baseline discipline**: a committed jacoco report may be a partial run — don't
  cite it; regenerate from a full run. `jacocoTestReport` is skipped when `test` fails (even with
  `--continue`) → regenerate with `:jacocoTestReport -x test` from the existing `test.exec`.
- **G-cov-5 vitest ceiling**: frontend vitest covers logic only (~30% line ceiling); presentation
  components are Playwright territory — don't chase vitest line% on `.tsx` components.

## 6. Process notes (non-canonical, session-specific)
- Use the harness `run_in_background`, not manual `nohup &` (process group reaped on tool return).
- Concurrently-churned files (memory under autosync) — use atomic single-process replace, not
  Read→Edit (the file changes between the two calls and Edit rejects).
- A shared feature checkout (here `codex/crm-endgame-gaps`) may lag main — verify the base branch
  before diffing/editing build.gradle (its gate was still 0.50, not main's 0.78).
- GitHub API was intermittently 401/EOF — PR create/merge needed a small retry loop.

## 7. Remaining work (handed off)
- #8/#9: real-stack IT to lift `meta/service/impl` 47%→80% + branch coverage 31%→up. Shared-DB →
  **serial, isolated `dev.sh runtime`** (not mass-parallel; concurrent IT re-exhausts connections).
- #14: frontend→80% needs the GA E2E stack + vitest/E2E V8 coverage merge → **dedicated infra session**.
- #3: infra subprojects (storage/mq) baseline → needs Docker.
