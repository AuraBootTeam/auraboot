---
type: handover
status: active
created: 2026-06-24
slug: public-record-pid
relates_to:
  - docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md
  - docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md
  - docs/plans/2026-06/2026-06-23-public-record-pid-endgame-follow-up-plan.md
---

# Session Handover - 2026-06-24 - Public Record PID Hard Mode

## Session Summary

本会话继续推进 public record 双 ID / pid-only hard mode 收口。当前没有完成 merge 条件,也没有完成 enterprise 验证。暂停点是一个可交接的里程碑:OSS v12 targeted/full 后端测试证据已回填到 tracker/plan,public-record 静态契约 gate 与 docs governance 已跑通;下一步必须继续补 schema/frontend/OpenAPI/runtime proof、clean publish、enterprise consumer 验证和 canonical 固化。

当前目标仍是:一次性做到位,禁止 public API / DSL / OpenAPI / frontend payload 暴露或兼容 `recordId` / `recordIds` / `targetRecordId` / `boundRecordId` / `triggerRecordId`; numeric id 只允许内部/admin 边界。

## Current State

- Worktree:`/Users/ghj/work/auraboot/.worktrees/oss-watch-field-history-pid`
- Branch:`codex/watch-field-history-pid`
- Latest commit on branch:`78292c928 docs: plan public record pid follow-ups`
- Relative to `origin/main`:`16` ahead, `3` behind
- Current tracker:`docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md`
- Primary plan:`docs/plans/2026-06/2026-06-23-public-record-pid-endgame-follow-up-plan.md`
- Original gap/backlog:`docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md`
- Worktree status is dirty and broad:latest count was `246` changed/untracked entries, with backend, schema, tests, scripts, frontend, docs all involved.

## PR State

- OSS PR #1060: `https://github.com/AuraBootTeam/auraboot/pull/1060`
  - Status:`OPEN`
  - Title:`fix: migrate watch and field history to record pid`
  - Head:`codex/watch-field-history-pid`
  - Base:`codex/public-record-dual-id-hardening`
  - Head SHA:`78292c9288a1ca68e07bcbe19d965e2ba0f1f517`
  - Merge state:`CLEAN`
- OSS PR #1059: `https://github.com/AuraBootTeam/auraboot/pull/1059`
  - Status:`OPEN`
  - Title:`Harden public record pid boundaries`
  - Head:`codex/public-record-dual-id-hardening`
  - Base:`main`
  - Head SHA:`8e53ae2ca7ca22b8d26263ffb6e7bf71189540e1`
  - Merge state:`UNKNOWN`

Owner earlier asked for "单独拉个 PR"; current topology is still stacked. Do not create another duplicate PR while hard-mode gates and enterprise validation are incomplete. Resolve topology only after green evidence is collected.

## Completed In This Pause Window

- [x] Updated `docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md`.
  - Status remains active.
  - G0/G1/G6 marked done.
  - Added OSS targeted/full v12 backend evidence.
  - Kept G7/schema/frontend/runtime/enterprise/final gates open.
- [x] Updated `docs/plans/2026-06/2026-06-23-public-record-pid-endgame-follow-up-plan.md`.
  - Added note that v11 red was seedless `schema-current` false recipe.
  - Added note that v12 seeded targeted/full now pass.
  - Clarified next work:static/OpenAPI/frontend/schema/runtime proof, clean OSS publish, enterprise validation.
- [x] Updated `docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md`.
  - Added pointer to the current executable tracker.
- [x] Ran public-record static contract gate.
- [x] Ran docs governance gate after tracker/plan updates.

## Verification Evidence Already Captured

### OSS backend targeted/full v12

- Targeted DB:`aura_public_record_pid_oss_targeted_20260624_v12`
- Full DB:`aura_public_record_pid_oss_full_20260624_v12`
- Schema source:`platform/src/main/resources/database/schema.sql`
- Targeted suite covered v11 failed clusters and recent pid edits, including template registry, agent task completion, customer-service agent integration, billing/quota/agent/dry-run related suites.
- Targeted result:`BUILD SUCCESSFUL in 2m 16s`
- Full result:`BUILD SUCCESSFUL in 27m 29s`
- XML summary:
  - files:`1509`
  - tests:`12020`
  - skipped:`43`
  - failures:`0`
  - errors:`0`
- Post-bootstrap seed counts:
  - `scheduled_task=11`
  - `billing_catalog=13`
  - `dry_run_support=6`
  - `agent_capability=6`
  - `object_alias=14`
  - `agent_skill=87`
  - `meta_model=149`

### Public-record static gate

Command:

```bash
scripts/check-public-record-id-contracts.sh
```

Result:

```text
Summary: 0 finding(s), 0 accepted, 0 new.
Baseline: scripts/public-record-id-baseline.json
PASSED
```

Included Node tests:

- inventory scanner tests:6/6 pass
- OpenAPI scanner tests:3/3 pass

### Docs governance

Command:

```bash
scripts/check-docs-governance.sh
```

Result:

```text
profile=lite checked=243 doc(s)
Summary: 0 error(s), 1 warning(s)
PASSED
```

Known warning is pre-existing and unrelated:

```text
S-DOCS-LINK-RELATES docs/plans/2026-06/2026-06-23-saved-view-endgame-baseline.md
relates_to target not found: /Users/ghj/work/auraboot/.worktrees/enterprise-saved-view-vnext/docs/assets/mockups/saved-view-vnext-mockup.html
```

## Important Diagnosis

### v11 red was a bad full-test recipe, not product regressions

The previous v11 full run used only:

```text
platform/src/main/resources/db/snapshots/schema-current.sql
```

That file is schema-only and misses seed data. v11 seed counts showed:

- `billing_catalog=0`
- `agent_dry_run_support=0`
- `agent_capability=0`
- `object_alias=0`
- `agent_skill` and `meta_model` existed

Failures grouped into missing-seed clusters:billing catalog/quota/metering, grounding/capability routing, dry-run support. Do not weaken product tests or add compatibility aliases to fix that. Correct OSS full recipe uses `database/schema.sql` or Flyway-backed setup with seeds.

### Schema gate was attempted but not completed

Initial attempt failed because `psql` on PATH was broken:

- `/usr/local/bin/psql` is a broken symlink.
- Actual client exists at `/opt/homebrew/Cellar/postgresql@17/17.6/bin/psql`.

Under the previous restricted sandbox, PostgreSQL socket access was blocked. After environment restart, `lsof` showed PostgreSQL listening:

```text
postgres ... TCP [::1]:5432 (LISTEN)
postgres ... TCP 127.0.0.1:5432 (LISTEN)
```

Next session should rerun schema gates with explicit PATH. Do not treat the previous schema failure as a product failure.

## Runtime State

- Root dev script exists at `/Users/ghj/work/auraboot/dev.sh`.
- The OSS worktree path does not contain `./dev.sh`.
- No host-first runtime slot was allocated from this worktree during this pause window.
- Port scan evidence only confirmed PostgreSQL on `5432`; no backend/Vite/BFF runtime was proven running for this worktree.
- Any runtime proof in the next session should start from fresh `git status`, runtime allocation, and port ownership checks.

## Key Changed Areas

This branch/worktree has a large surface. Main areas:

- Backend controllers/services/entities/mappers for agent, automation, email, IM, inbox, meta/dynamic, mobile, permission/record share, template registry, and test fixture controller.
- Schema files:
  - `platform/src/main/resources/database/schema.sql`
  - `platform/src/main/resources/db/snapshots/schema-current.sql`
  - `platform/src/main/resources/db/migration/core/V20260623001000__public_record_pid_record_links.sql`
- Public-record pid contract tests across agent, automation, email, permission, template, field-history/watch paths.
- Scripts:
  - `scripts/check-public-record-id-contracts.sh`
  - `scripts/public-record-id-baseline.json`
  - `scripts/validate-public-record-id-contracts.mjs`
  - `scripts/check-public-record-openapi-contract.mjs`
  - `scripts/check-public-record-openapi-contract.test.mjs`
- Frontend `web-admin` runtime/rendering/service files migrated away from record-id fallback toward pid.
- New API fixture directory:`docs/api-fixtures/`

## Untracked Files To Preserve

Do not discard these:

- `docs/api-fixtures/`
- `docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md`
- `platform/src/main/resources/db/migration/core/V20260623001000__public_record_pid_record_links.sql`
- `platform/src/test/java/com/auraboot/framework/agent/controller/AgentRunQuerySupportPidContractTest.java`
- `platform/src/test/java/com/auraboot/framework/automation/controller/AutomationControllerPidContractTest.java`
- `platform/src/test/java/com/auraboot/framework/automation/dto/`
- `platform/src/test/java/com/auraboot/framework/email/EmailMessageControllerPidContractTest.java`
- `platform/src/test/java/com/auraboot/framework/email/EmailSequenceControllerPidContractTest.java`
- `platform/src/test/java/com/auraboot/framework/permission/controller/RecordShareControllerPidContractTest.java`
- `scripts/check-public-record-openapi-contract.mjs`
- `scripts/check-public-record-openapi-contract.test.mjs`

## Next Steps For The New Session

1. Re-read current state:

```bash
git status --short
git rev-parse --abbrev-ref HEAD
git rev-list --left-right --count origin/main...HEAD
gh pr view 1060 --json number,state,title,url,headRefName,baseRefName,headRefOid,mergeStateStatus
gh pr view 1059 --json number,state,title,url,headRefName,baseRefName,headRefOid,mergeStateStatus
```

2. Rerun schema gates with the fixed PostgreSQL client PATH:

```bash
env PATH=/opt/homebrew/Cellar/postgresql@17/17.6/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  PG_HOST=localhost PG_PORT=5432 PG_USER=ghj PG_PASSWORD= \
  scripts/check-schema-sql.sh --local

env PATH=/opt/homebrew/Cellar/postgresql@17/17.6/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  scripts/db/check-schema-drift.sh --edition oss
```

If local scripts require a specific DB name, create/use a fresh pid-only validation database and record it in the tracker.

3. Run frontend gates:

```bash
pnpm --dir web-admin typecheck
pnpm --dir web-admin check
pnpm --dir web-admin test:unit:run
```

4. Start backend/runtime as needed, capture final OpenAPI, then run the pid-only OpenAPI scanner:

```bash
node scripts/check-public-record-openapi-contract.mjs --input <captured-v3-api-docs-json>
```

5. Complete G7 runtime proof with a real flow:

- dynamic list -> detail -> edit/action
- route/query uses pid
- request payloads use pid
- response public contract has no forbidden public aliases
- capture browser/API evidence in tracker

6. Publish a clean OSS artifact only after OSS gates are green:

```bash
cd platform
./gradlew --no-daemon publishToMavenLocal \
  -Dmaven.repo.local=/tmp/aura-public-record-pid-oss-v12-m2/repository \
  --console=plain
```

7. Verify enterprise consumer path before relying on it.

The expected enterprise worktree from the tracker was:

```text
/Users/ghj/work/auraboot/.worktrees/enterprise-public-record-pid-consumer
```

However, the latest `git worktree list` output in this session did not show that path. The next session must verify whether it exists and what branch it is on before starting enterprise validation.

8. Run enterprise targeted pid consumer tests and full enterprise validation against the final OSS artifact.

9. Canonical docs still need to be updated after behavior is proven:

- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/standards/core/data-and-api.md`
- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/standards/meta/id-pid-cross-module-reference-policy.md`
- relevant `auraboot-enterprise/docs/agent-rules/` entries for public record pid hard mode and schema/test recipe gotchas.

10. Only after OSS + enterprise evidence is green:

- resolve stacked PR topology into the expected final PR shape
- stage/commit/push
- update PR description with evidence
- request/perform final review
- merge only when all required checks and manual gates are green

## Reflection & Codify

### Bends / Rework

1. **双 ID 策略被反复违背,根因是规范没有变成可执行禁令。** 口头规则不足以挡住 old alias fallback,尤其是 `recordId` 这种历史字段名会自然回流到 DTO、frontend payload、OpenAPI 和 DSL。后续必须以 scanner、baseline、OpenAPI gate、runtime proof 同时约束。
2. **v11 full 红灯来自 seedless schema recipe。** `schema-current.sql` 是 schema-only,不能代表产品启动后的 seedful runtime。后续所有 full evidence 要写清 schema/seed 来源,避免把环境配方错误误判成产品缺陷。
3. **本地工具链问题容易伪装成 schema 失败。** `psql` PATH、PostgreSQL socket、sandbox/network 权限必须先取证。下一会话直接使用 Homebrew PG17 client path。
4. **stacked PR 与 owner 的 single-PR 意图存在偏差。** 在 gates 未清前先不要重排 PR;但收口前必须主动整理成 owner 要的最终形态,避免让 review/merge 路径变复杂。
5. **handover 必须讲清未完成项。** 这次只到里程碑暂停,不能把 v12 后端 pass 等同于 full migration done。

### Already Distilled

- Current executable tracker updated:`docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md`
- Follow-up plan updated:`docs/plans/2026-06/2026-06-23-public-record-pid-endgame-follow-up-plan.md`
- Original backlog now points to the executable tracker:`docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md`

### Still To Distill

- pid-only hard-mode canonical standard under enterprise standards.
- Schema/test recipe warning:do not use seedless `schema-current.sql` as full product test DB.
- Public API/OpenAPI/frontend payload alias ban pattern, including scanner and runtime proof requirements.

## Handoff Boundary

This document is a pause handover, not a closeout. The implementation is not merge-ready until OSS schema/frontend/OpenAPI/runtime gates, clean artifact publish, enterprise consumer validation, canonical docs, final review, and PR topology are all completed and recorded.
