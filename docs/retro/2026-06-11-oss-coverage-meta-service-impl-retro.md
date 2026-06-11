---
type: retro
status: active
created: 2026-06-11
---

# OSS coverage — meta/service/impl IT wave — session retro (2026-06-11)

Follow-on to `2026-06-11-oss-coverage-session-retro.md` (which established the honest
baseline + infra unlock). This session executed the actual coverage lift on the
command-pipeline core and ratcheted the gate. This retro answers the post-session
questions: *why were there so many small problems? gate quality? insufficient input?
bad prompts? — and which lessons should be codified.*

## 1. What shipped (6 PRs merged to main)

| PR | Class / change | Lift |
|----|----------------|------|
| #565 | DictServiceImpl + DictCascadeServiceImpl IT + gate 0.68→0.69 | 26.7→79% / 1.8→77% |
| #566 | DictVersionServiceImpl IT (dict domain complete) | 36.7→74% |
| #568 | DynamicDataServiceImpl coverage IT | 34→39% |
| #569 | ReconciliationService IT (+ 4 product bugs surfaced) | 0.2→79% |
| #570 | NamedQueryServiceImpl IT (parallel sub-agent) | 47→85% |
| #573 | gate consolidation 0.69→0.71 | — |

Net: `meta/service/impl` package **47.0%→56.1%** (~+1437 covered lines), gate-denominator
bundle LINE **70.2%→73.2%**, ratchet floor **0.68→0.71** (2pt flaky margin preserved). The
6 new IT classes were **0-failure in every run** including the full suite (171 tests).
Method: real-stack IT (`@SpringBootTest` + `integration-test` profile + real shared DB, no
mocked mappers/bridges — AGENTS.md §2.2 seam).

Bonus deliverable: 4 real ReconciliationService product bugs
(`docs/backlog/2026-06-11-reconciliation-service-bugs.md`).

## 2. The friction log (every snag, honestly)

| # | Snag | Cost | Class |
|---|------|------|-------|
| 1 | DictService `dto.getDictType()` asserted "dynamic", real is "simple" (`toDTO` reverse-maps via `mapDictTypeToFrontend`) | 1 targeted re-run (~1min) | assumption-vs-real |
| 2 | DictCascade test missing `assertNull` static import | compile-time | typo |
| 3 | DictVersion test had a Cyrillic `assertДoesNotThrow` (autocomplete glitch) | compile-time | typo |
| 4 | `new FieldOptionRequest()` — `@Builder` suppresses the no-arg ctor | 1 compile cycle | assumption-vs-real |
| 5 | DynamicData operator sweep: `NOT_BETWEEN` throws "Invalid operator" (enum value not wired in query builder) | 1 targeted re-run | assumption-vs-real |
| 6 | DynamicData: 8 failures `DuplicateKeyException` — `@AfterAll` deleted model+bindings but NOT the tenant-unique meta `Field` rows | 1 manual DB clean + harness rework | **harness teardown gap** |
| 7 | DynamicData "biggest gap" only lifted +5pts (uncovered bulk is hard-to-test executeCustomAction/saveWithRelations/relations) | ~1 slice of effort for modest payoff | **planning/triage** |
| 8 | recon backlog doc `status: open` — invalid governance enum (should be `active`) | 1 docs-gov cycle | convention |
| 9 | `gh pr create` network EOF | 1 retry | transient |
| 10 | MEMORY.md size guard tripped on my edits | 1 trim | memory hygiene |
| 11 | 2× ~30-70min full-suite runs for gate verification | wall-clock | infra cost |

## 3. Root-cause analysis — "why so many problems?"

The honest answer: **it was NOT gate quality, and NOT insufficient input.** The gate
(jacoco) measured everything correctly; the shared DB was available; the handover + tracker
were accurate and complete. Of 11 snags:

- **5 of 11 (≈45%) were cheap, self-correcting iterations** (#1–#5, #8): I wrote a test
  assertion / DTO call from a *plausible but unverified* assumption about behavior, and the
  compiler or a ~1-minute targeted run corrected me. **This is the §15 verify-before-claim
  discipline applied to test-writing.** It cost little because the targeted-run loop is fast
  — but 2–3 of them were avoidable by reading the exact source first (the converter's
  reverse-map, the query-builder's operator `switch`, the DTO's `@Builder`). For a *large
  unfamiliar service*, "read the exact behavior before asserting, OR accept a fast
  iterate-against-real-DB loop" — both are valid; what's NOT valid is asserting blind on a
  slow path. Here the loop was fast, so the cost was acceptable, not alarming.

- **1 of 11 was a real process gap (#6, DuplicateKey):** I copied the model+table harness
  but did NOT fully replicate its *teardown*. The reference test (`DynamicDataServiceIntegrationTest`)
  had a `cleanupExistingTestModel` that deletes the meta `Field` rows by code; my first
  version only deleted model+bindings. The non-obvious bit: meta `Field.code` is
  **tenant-unique** (unique index on `(tenant_id, code) WHERE is_current`), so leftover
  fields from a failed run collide. **Lesson: copy the pattern COMPLETELY, especially the
  teardown.**

- **1 of 11 was a planning miss (#7):** I picked DynamicDataServiceImpl because it had the
  most *missed lines* (1042), without first assessing *testability per method*. Its
  uncovered bulk lives in `executeCustomAction` (~450 LOC), `saveWithRelations`, and
  relation methods that need multi-model + action/relation-definition fixtures — so the
  realistic lift was only +5pts. The same effort on **near-zero clean CRUD/logic classes**
  (ReconciliationService 0.2→79%, DictCascade 1.8→77%) produced 10–15× the lift. **Lesson:
  triage coverage targets by (missed-lines × testability), not raw missed-lines.**

- **3 of 11 were noise** (#9 transient network, #10 memory hygiene, #2/#3 typos) — not
  systemic.

- **The real *cost* was infra, not "problems" (#11):** the two full-suite runs (~1h total)
  dominated wall-clock. The mitigation I converged on — **land additive isolated test PRs
  without a per-slice gate bump, then do ONE consolidation full-run + ratchet** — is the key
  efficiency lesson (I did the dict slice with its own run early, then batched dyndata +
  recon + nq into one final consolidation run).

**Were the sub-agent prompts good?** Yes — both parallel sub-agents (recon, nq) succeeded
first-pass with 0 failures and big lifts, because the prompts carried: the exact harness
template file to copy, §20 worktree three-piece, §15 verify-before-claim, the
connection-discipline (targeted-only, never full-suite), the DuplicateKey/self-clean
warning, and the report format. The one prompt improvement worth noting: I should have told
them the *gate-denominator vs report-number* distinction so their coverage numbers were
directly comparable (they reported report-number, which is fine but slightly different from
the gate number).

**Net:** this was a *successful, low-defect* session whose friction was mostly the normal
cost of writing characterization tests against a large, partly-buggy codebase — discovered
by running, corrected fast. The two genuine lessons (teardown completeness; triage by
testability) and the efficiency lesson (defer gate-bump to one consolidation) are worth
codifying.

## 4. Improvements for next time

1. **Triage-first for coverage work:** before picking a class, read it and classify each big
   method `testable-with-simple-setup` vs `needs-heavy-fixtures`; prioritize near-zero clean
   classes (one-shot 0→80% wins) over large classes whose bulk needs multi-model/action
   fixtures. Rank by `missed × testability`, not raw missed.
2. **Harness reuse = copy teardown too.** When reusing a real-stack IT harness, replicate the
   FULL cleanup (incl. tenant-unique meta `Field`/`Model` rows), use a dedicated tenant +
   prefixed codes, and a self-cleaning purge in BOTH pre-create and `@AfterAll`.
3. **One consolidation full-run, not per-slice.** Land additive isolated test PRs (gate
   can't regress from added coverage); accumulate; then one full-suite run + ratchet bump.
4. **Parallel shared-DB IT = 2× targeted only.** Sub-agents run ONLY their targeted test
   (never full `:test`), own dedicated tenant, self-clean; cap 2; main-loop verify-don't-trust
   (`git branch --contains` + independent serial re-run).
5. **Assert real behavior, fast-iterate.** For unfamiliar services, either read the exact
   converter/query-builder/DTO source before asserting, or accept a fast targeted-run loop —
   don't assert blind on a slow path.

## 5. Codified to canonical (precipitation)

Added to `auraboot-enterprise/docs/agent-rules/engineering-gotchas/test-infra.md`
§覆盖率门禁 as **G-cov-6..10** (this session extends the earlier G-cov-1..5):
- G-cov-6 triage by (missed × testability)
- G-cov-7 IT harness teardown must delete tenant-unique meta Field rows
- G-cov-8 defer gate-bump to one consolidation full-run
- G-cov-9 parallel shared-DB IT = 2× targeted-only + verify-don't-trust
- G-cov-10 coverage IT over a buggy class = characterization (mark PRODUCT BUG + backlog, don't fix in the coverage PR)

Enterprise `AGENTS.md` keyword row (覆盖率门禁) extended with the new keywords.
