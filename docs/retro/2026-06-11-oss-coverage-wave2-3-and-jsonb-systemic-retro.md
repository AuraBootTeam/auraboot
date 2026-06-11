---
type: retro
status: active
created: 2026-06-11
---

# Retro — OSS coverage wave 2/3 + the systemic jsonb problem (2026-06-11)

Honest post-mortem of the session that landed PRs #581/#582/#585/#587/#591 (OSS coverage →
80%, 10 near-zero classes, 4 jsonb fixes, 3 defect fixes) — written because the session *felt*
like it had "a lot of problems." It mostly didn't; this separates the real process misses from
the noise, and answers the three framing questions: **was it gate quality, input, or prompts?**

## 0. What actually happened (so the analysis is grounded)
- Wave 2: 6 near-zero `meta/service/impl` classes → real-stack IT (#581), gate 0.71→0.73.
- Wave 3: 4 more (#585). Gate bump deferred (shared DB was noisy).
- Repo-wide jsonb audit (#587), then fixed the surfaced defects (#591): SecureQuery cache +
  timeout, KbChunk jsonb; corrected 2 mis-reported findings.
- In parallel, **another session independently shipped 4+ jsonb fixes the same day**
  (#580/#586/#589/#598 read-side PGobject + #592 a shared `JsonbColumns` helper). Combined with
  this session's 4 write-side fixes, **~8 jsonb bugs were fixed across the codebase in one day.**

## 1. The headline: most "failures" were the tooling *working*, not breaking
Real-stack IT is a bug-finder. The red test runs that looked like "problems" were the IT
**discovering 4 live production bugs** (EDI endpoints 500, query-audit silently broken,
OtDevice register/data 500, KbChunk latent). That is the system functioning as designed. A
coverage session that finds zero bugs is the suspicious one. So "why so many problems" is
partly a framing artifact — surfacing latent defects *is* the value.

## 2. The genuine root cause: a documented bug with a **phantom guardrail**
This is the real finding, and it answers "why did the SAME bug recur 8 times in one day."

The jsonb-typeHandler bug was **already documented in canonical**
(`engineering-gotchas/backend-spring-db.md` §"column is of type jsonb…") — *and that doc
referenced a prevention script, `scripts/check-jsonb-typehandler.sh`*. **That script did not
exist.** The guardrail was a phantom: a doc promising a check that was never built (or was
removed), so nothing actually enforced the rule. Meanwhile:
- **Actions are off** (billing) → even real local gates don't auto-run pre-merge.
- **Two equivalent String→jsonb handlers** exist (`tenant/…/JsonStringTypeHandler` vs
  `application/database/mybatis/JsonbStringTypeHandler`); canonical points at one, the meta
  entities use the other → confusion about "the" handler.
- Neither the main loop nor the sub-agents **grepped canonical for "jsonb" before touching
  entities** (a §18 "the坑 was already recorded" miss).

So the recurrence was not bad luck. The knowledge existed; the *enforcement* did not.

**Improvement shipped this PR:** `scripts/check-jsonb-typehandler.sh` now actually exists,
is tested (negative test: strip a typeHandler → it flags the field, exit 1; restore → exit 0),
and recognizes **both** safe forms (a `@TableField` typeHandler **or** a mapper `#{x}::jsonb`
cast), so it does not false-positive on custom mappers. It would have caught all 4 of this
session's bugs. On current main it passes (38 String→jsonb fields protected, 0 issues).

## 3. The one real *process* miss: under-verified findings propagated to a merged doc
My #587 audit claimed "2 remaining latent jsonb bugs" (`KbChunk.metadata`,
`InvariantEvaluationLog.context_snapshot`) from a name+column-type match — **without checking
each entity's mapper**. When I went to fix them (#591), §15 verify-before-fix caught that
`InvariantEvaluationLog` was a **false positive**: its mapper inserts with an explicit
`#{contextSnapshot}::jsonb` cast, so no typeHandler is needed. The false claim had already
landed in a merged backlog (#587) and had to be corrected.

**Lesson:** §15 ("verify before claim") applies to **my own audits and findings docs**, not
only to sub-agent reports. A finding written into a merged backlog as "confirmed" must be
verified to the same bar I'd demand of a sub-agent. For jsonb specifically: a name/column match
is necessary but **not sufficient** — check whether the mapper casts `::jsonb` (custom mapper =
safe) before declaring a typeHandler bug. The new lint encodes exactly this check, so the
discipline is now mechanical, not manual.

## 4. Smaller, repeated frictions (avoidable with checklist discipline)
| Friction | Why | Fix going forward |
|---|---|---|
| Full-suite jacoco read 9% | BUILD FAILED leaves a **partial** report | always `./gradlew :jacocoTestReport -x test` to regenerate from `.exec` before reading |
| 2554 env-flaky full-suite failures | a concurrent enterprise `bootRun` churned shared `:5432` | `pgrep -fl bootRun` **before** any full-suite; if up, defer or stay targeted (targeted = per-tenant isolated = robust) |
| jsonb assertion failed twice | jsonb normalizes whitespace **and reorders keys** | assert jsonb round-trips **semantically** (contains key/value, or parse) from the start, never exact-string |
| had to set separate waiters | `nohup … &` inside a `run_in_background:true` Bash double-detaches | use ONE mechanism: either `run_in_background` with an `until` exit-condition, not nested nohup |
| `./gradlew` not found / wrong path | shell cwd resets between Bash calls | use absolute paths or `cd X && cmd` in one invocation |

## 5. Direct answer: gate quality vs input vs prompts?
- **Gate quality / enforcement — the dominant factor.** Not "low quality" but a **gap**: a
  documented guardrail that was never built, plus Actions-off so nothing auto-runs. The single
  highest-ROI fix is the one shipped here (build + wire the lint). 80% of the recurrence traces
  to this.
- **Input sufficiency — adequate.** The starting handover was good. The only "bad input" was
  *self-inflicted*: my own under-verified #587 findings became the (partly wrong) brief for the
  "fix 5 defects" task. Fix the finding quality at the source (§3) and the input is fine.
- **Prompt quality — good, minor tweak.** The sub-agents succeeded, found real bugs, and
  reported honestly. The only improvement: bake "before flagging a jsonb finding, check the
  mapper for `::jsonb`; before fixing a defect, confirm the service is wired" into dispatch
  prompts. Already generalized into the canonical rules below.

## 6. What to codify (→ AGENTS.md / engineering-gotchas)
1. **jsonb has two failure faces, one rule** — write side: a `String @TableField` on a jsonb
   column needs `JsonbStringTypeHandler` (or a mapper `::jsonb` cast); read side: a jsonb column
   read via a generic query returns a `PGobject`, handle via `JsonbColumns.toJsonText` (#592),
   never `(String)` cast / `writeValueAsString`. **Run `scripts/check-jsonb-typehandler.sh`
   before merging entity changes** (it exists now). (→ `backend-spring-db.md`)
2. **§15 covers my own audits/findings** — a "confirmed defect/bug" in a backlog/handover must
   be verified to sub-agent bar (for jsonb: check the mapper, not just the column). A
   static-only match is "🟡 candidate", not "confirmed". (→ `spike-verification-discipline.md`)
3. **Before "fixing" a surfaced defect, confirm it's wired** — `SchemaAccessProjector` /
   `SecureQueryExecutor` had ~no OSS callers; implementing a dead stub is the wrong fix.
   (→ `main-conversation-discipline.md`)
4. **jsonb test assertions are semantic** (key reorder + whitespace). (→ `test-infra.md`)
5. **Operational**: `pgrep -fl bootRun` before a full-suite on shared `:5432`; regenerate
   jacoco with `-x test` after a failed full run; don't nest `nohup` inside `run_in_background`.
   (→ `test-infra.md` / `oss-e2e-and-playwright.md`)
6. **Before entity/jsonb work, grep canonical** (`backend-spring-db.md` for "jsonb") — the坑 was
   already recorded; not reading it is on the main loop (§18). (→ §18 reinforcement)

## 7. Net assessment
The session shipped a large, correct, well-verified increment (5 PRs, 4 real bug fixes, a clean
gate bump). The recurrence pain was a **guardrail-enforcement gap on a known bug**, now closed
with a real, tested lint. The one true craft miss — propagating an under-verified finding — is
exactly what §15 exists for, now extended to cover self-authored findings. Not an input or
prompt problem.
