---
title: HANDOVER — RBAC v2 completeness audit + real-stack test run
date: 2026-06-21
created: 2026-06-21
type: handover
status: shipped
area: permission / rbac / web-admin / testing
---

> **Shipped 2026-06-21** — real-stack run complete: **90 passed / 2 skipped / 0 failed**
> (backend unit 38 + backend IT 4 + frontend RTL 34 + E2E 14). Measured report:
> `docs/retro/2026-06-21-rbac-v2-real-stack-test-report.md`.

# Session Handover — 2026-06-21 · RBAC v2 audit + real-stack run

## Session Summary
Post-merge audit of the RBAC permission v2 line (already shipped: OSS #989/#993/#994/#1003 + ENT #639).
Confirmed the original task is fully delivered, investigated + **rejected** a proposed PID-endpoint lint
(not cleanly buildable), produced a retroactive testing-gate **acceptance report** (ENT #646, merged),
and is now executing a **real-stack re-run** of the RBAC v2 test suite to replace the inherited-green
claim with measured evidence.

## Tasks Completed
- [x] Verified RBAC v2 original task complete via live git/gh + #989 real diff (not handover prose):
      all §2 work items present on origin/main; 6 PRs MERGED; IA reorg semantically confirmed
      (assignments tab retired, matrix folded into ③ advanced, capability is default surface).
- [x] Investigated proposed **PID-endpoint lint** → **rejected with evidence**: name-based grep
      false-positives on correct code (`entitlements.tsx` keeps `tenantId` as a *string* + documents the
      snowflake concern); value-based needs heavy TS-AST + "is this a snowflake" is undecidable. R2
      (`String(x.id)`) = 18 hits, ~all legit (dnd-kit/sortable). Defenses already in place (#993 fix +
      ENT #639 gotcha + #994 golden + existing correct code patterns).
- [x] Produced testing-gate **acceptance report** + reconstructed feature/action matrix → **ENT #646
      MERGED** (`auraboot-enterprise/docs/retro/2026-06-21-rbac-v2-permission-testing-gate-acceptance-report.md`);
      marked stale `@auraboot/track #966` retro residual as resolved by OSS #1003.

## Tasks Completed (real-stack run — DONE)
- [x] **Real-stack run complete** — 90 passed / 2 skipped / 0 failed. Backend unit 38/38, backend IT
      4/4 (isolated `auraboot_67`, real 403/200), frontend RTL 34/34, E2E 14 passed / 2 skipped
      (role-members add/remove = no candidate members) / 0 failed. First-run E2E showed 3 failures →
      root-caused to empty-tenant ordering artifact (screenshot proved v2 IA renders correctly) →
      re-run 12/12 green. Report: `docs/retro/2026-06-21-rbac-v2-real-stack-test-report.md`.

## Tasks (original plan, now done)
- [x] Real-stack re-run — layers executed & measured:
  1. Backend unit: `CapabilityResolverTest`, `CapabilityViewServiceImplTest`, `RolePermissionServiceImplTest` (pure JVM).
  2. Backend IT: `CapabilityControllerEnforcementIT` (real PG + Redis).
  3. Frontend RTL (vitest, jsdom): `capability/__tests__/*` + `scopeHelpers` + `coverageHelpers` + `capabilityService`.
  4. E2E goldens (host stack, real browser): `permission-v2-golden`, `role-default-scope-golden`,
     `permission-management`, `role-members`, `decisionops-permission-negative` — **run isolated** (F2: abac
     `function.*` pollution trips v2-golden raw-leak in batch runs).
  5. Write the complete real-stack test report (replace `coverage_not_measured` / `not re-executed`
     with real pass/fail + evidence). Then commit handover+report, PR, destroy runtime + worktree.

## Key Decisions
| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| Build the PID-endpoint lint? | **No** | name-based = false-positive machine (entitlements proves it); value-based = heavy AST + undecidable | ship a noisy/narrow lint — rejected |
| Acceptance report location | **ENT docs/retro** (next to existing RBAC retro) | one coherent PR; sibling retro already there | OSS — split across repos |
| Real-stack run isolation | **fresh OSS worktree @ origin/main + dedicated runtime slot** | canonical OSS checkout occupied by concurrent codex session; §11 host-first | reuse canonical — forbidden (occupied + churning) |

## Files Changed
### Docs (ENT #646, merged)
- `auraboot-enterprise/docs/retro/2026-06-21-rbac-v2-permission-testing-gate-acceptance-report.md` — new acceptance report + matrix
- `auraboot-enterprise/docs/retro/2026-06-21-rbac-v2-permission-session-retro.md` — stale track residual marked resolved
### Docs (this worktree, OSS — pending PR)
- `docs/handover/HANDOVER-2026-06-21-rbac-v2-audit-and-real-stack.md` — this file
- (pending) real-stack test report

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **中途误判 "IA 重排没落地"** — 代价:~1 步 — 本可更早避免:第一次搜路径用了 `permission/PermissionManagement.tsx`,实际是 `enterprise/PermissionManagement.tsx`(不在 `permission/` 子目录)。靠核 #989 真实 diff 纠正。— 根因:`[D 验证]`(grep 空当结论,未先确认路径)。
2. **PID-lint 提案乐观先行** — 代价:~1 轮(建空 worktree + grep)— 本可更早避免:提案前先 grep 可行性。— 根因:`[D 验证]`(未取证就荐方案)。好在 verify-don't-trust 用在自己提案上当场纠正,成本只有 grep。
3. 核心审计无重大弯路 —— 全程 live git/gh 校准 + verify-before-flag,两个疑似 gap 都证伪。

### 为什么会发生(根因小结)
集中在 **D 验证纪律**:两处都是"先下结论/先荐方案、后取证"。纠正机制(核真实 diff / grep 可行性)都奏效,只是该更早。无门禁/输入/提示词类问题。

### 应该有哪些改进
- 个人纪律:grep 返回空时,先确认路径/模式对不对再下"不存在"结论(已是 §15 精神,本会话提醒)。
- 无需新增红线(§14/§15 已覆盖);非稳定可复用新模式,不固化。

### 已固化 / 待固化(更新文档)
- [x] ENT #646:验收报告 + 矩阵已落地并 merged。
- [ ] (本 handover 内,不上升)上述两处弯路是一次性 verify 提醒,非新红线。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前 worktree**:`/Users/ghj/work/auraboot-rbac-golden` · 分支 `test/rbac-v2-real-stack`(base origin/main @ `702ea6c8a`)
- **canonical OSS checkout** `/Users/ghj/work/auraboot/auraboot` 被并发 codex 会话占用(`codex/followup-bom-quote-completion`)— **勿碰**
- **PR**:ENT #646 MERGED(验收报告);OSS 本 worktree PR 待开(handover + 真栈报告)
- **本会话已收口 worktree**:`auraboot-pid-lint`(空,removed)、`auraboot-ent-rbac-gate`(#646 merged 后 removed)

### Runtime / 端口(host-first 零 docker)
- **常驻 broker 在跑**:Postgres :5432 · Redis :6379 · Kafka :9092
- **计划 runtime**:`rbac-v2-golden` slot 67(auraboot 已占 53/60/62/66;避开)
- **起栈命令**:`./scripts/oss-golden-stack.sh up rbac-v2-golden --slot 67 --ttl 6h`(在本 worktree 内跑,stack Vite 服务本 checkout)
- 并发会话多(516x/518x 上有别的 Vite/BFF)— teardown 只按本 slot 端口,**禁 pkill -f**

### Database / Seed
- golden-stack `up` 做 minimal bootstrap(admin+tenant);RBAC golden 不需 showcase seed。reset 由 stack 脚本管。

## Next Steps
1. 跑 4 层真栈(unit → RTL → IT → E2E 隔离),收集真实 pass/fail + 证据。
2. 写完整测试报告(真结果替换 `coverage_not_measured`)。
3. commit handover+报告 → OSS PR → merge;`oss-golden-stack.sh destroy rbac-v2-golden` + worktree remove。

## Context for Next Session
- 验收报告(矩阵真源):`auraboot-enterprise/docs/retro/2026-06-21-rbac-v2-permission-testing-gate-acceptance-report.md`
- 闭环 handover:`auraboot/docs/handover/HANDOVER-2026-06-21-rbac-v2-permission-complete.md`
- F2 caveat:E2E 必隔离跑(abac `function.*` 污染共享栈)
