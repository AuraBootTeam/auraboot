---
type: handover
status: shipped
created: 2026-06-21
slug: telemetry-sp4-close-kafka-decouple-start
distilled_to:
  - auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md (3 import-path bugs codified, ENT #647)
---

# Session Handover — 2026-06-21 · site-key SP3+SP4 deliver/unblock + Kafka-decouple line start

## Session Summary
Built **SP3 (public/anonymous `@auraboot/track` SDK mode) + SP4 (anonymous keyed-collect end-to-end golden)** of the telemetry subsystem — fully verified, OSS PR #1013. SP4's first real plugin-import golden caught + fixed **3 production bugs**. Then unblocked #1013's required docs gate (2 merged governance PRs clearing other-session debt), and **started the next telemetry line** (Kafka decouple ingestion) with a design spec. #1013 + ENT #647 await owner approval/merge; Kafka build is a fresh focused session.

## Tasks Completed
- [x] **SP3** — `@auraboot/track` public mode: optional `anonId` in envelope + `createTracker getAnonId`; `createPublicTracker({siteKey, collectUrl?})` (zero platform deps, cookie+localStorage anon_id, sessionStorage sid, POST `/api/collect/keyed` w/ `X-Site-Key`); IIFE build (`dist/aura-track.global.js` → `window.AuraTrack.init`) + `verify-global-build.mjs`. 22 vitest green.
- [x] **SP4** — anonymous keyed golden (5/5, host-first zero docker) + fixed **3 import-path bugs** (see Pitfalls). Added `KeyedCollectIT` CORS preflight (now 10 tests). `SiteKeyIndexInitializer` AFTER_COMMIT + backstop tenant-context fixes.
- [x] **keyed-collect resilience** — `recordBatch` skips a single constraint-violating event (e.g. over-long client field on the public endpoint) instead of 500'ing the batch (+`KeyedCollectIT.oversizedEvent_skippedNotBatchFatal`).
- [x] **Unblocked #1013's required docs gate** — 2 governance PRs (both MERGED): **#1016** (docs-governance checker accepts cross-repo `auraboot-enterprise/` precipitation targets) + **#1018** (drop off-convention `title:` frontmatter from 2 #1015 rbac docs → fixes the real blocker, markdownlint MD025).
- [x] **Codified** — ENT PR #647 (canonical backend-spring-db.md correction of the buggy `@EventListener` pattern + public-CORS gotcha + speed-table) **OPEN**; OSS spec §4.5/§4.6 + retro; memory updated.
- [x] **Started next line** — Kafka decouple ingestion **design spec** committed/pushed on `feat/behavior-kafka-decouple-ingestion`.

## Tasks In Progress
- [ ] **OSS #1013** — all 3 required checks GREEN, MERGEABLE, **BLOCKED only on the required 1 approving review** (I can't self-approve). → owner approve/merge.
- [ ] **ENT #647** — OPEN, enterprise has no CI gates → directly mergeable. → owner merge.
- [ ] **Kafka decouple build** — spec landed; TDD + golden build is the fresh-session continuation (this branch).

## Key Decisions
| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| SP4 index-init import trigger | `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` | sync `@EventListener` fires inside the uncommitted import tx → `createFieldIndex` existence check (other connection) → "Table does not exist" | plain `@EventListener` (the shipped-but-broken pattern) |
| SP4 public CORS | path-specific permissive CORS for `/api/collect/keyed` (any origin, POST/OPTIONS, `X-Site-Key`, no creds) registered before `/api/**` | global `/api/**` CORS only allows admin origins + omits `X-Site-Key` | widen `/api/**` (would open admin API to any origin) |
| #1013 docs-gate unblock | fix the gate (cross-repo accept) + fix the off-convention frontmatter, NOT touch content / not admin-merge | the gate was red repo-wide on every doc-PR; #1011 precedent = sanctioned "repair to unblock"; owner asked to unlock | edit the cross-repo docs (the config-author philosophy: fix, don't disable) |
| Next line | Kafka decouple ingestion (memory MQ local / kafka prod) | only remaining SoT §12 M1 slice; code self-flagged it; graduates SP4 resilience → quarantine topic | server-outcome outbox / OTel (separate independent lines) |

## Files Changed
### SP3 (OSS #1013, `web-admin/packages/track/`)
- `src/types.ts` `src/envelope.ts` `src/tracker.ts` — optional `anonId` + `getAnonId`
- `src/public.ts` (new) — `createPublicTracker` + anonId/session stores
- `src/global.ts` (new) + `vite.lib.config.ts` (new) — IIFE build; `scripts/verify-global-build.mjs` (new); `index.ts`/`package.json`
- `src/__tests__/{envelope,tracker,public}.test.ts`
### SP4 backend (OSS #1013, `platform/`)
- `behavior/sitekey/SiteKeyIndexInitializer.java` — AFTER_COMMIT + backstop MetaContext tenant
- `application/security/SecurityConfig.java` — public keyed CORS
- `behavior/service/BehaviorCollectService.java` — per-event DataIntegrityViolation skip
- `behavior/keyed/KeyedCollectIT.java` (+CORS, +oversized) · `behavior/sitekey/SiteKeyIndexInitializerTest.java`
### SP4 golden + docs (OSS #1013)
- `web-admin/tests/e2e/behavior/anonymous-keyed-collect.golden.spec.ts` (new, 5 tests)
- `docs/superpowers/specs/2026-06-21-site-key-sp3-sp4-public-sdk-and-golden-design.md` · `docs/retro/2026-06-21-site-key-sp3-sp4-public-sdk-golden-retro.md`
### Governance (MERGED, separate PRs)
- #1016: `scripts/check-docs-governance.mjs` (+test) — accept cross-repo precipitation
- #1018: 2 rbac docs frontmatter (`title:` removed)
### Next line (this branch)
- `docs/superpowers/specs/2026-06-21-behavior-kafka-decouple-ingestion-design.md` (new)
### Canonical (ENT #647)
- `docs/agent-rules/engineering-gotchas/backend-spring-db.md` + `AGENTS.md` speed-table

## Pitfalls & Workarounds
1. **Import-time global index never created** ("Table does not exist") — sync `@EventListener` runs inside the uncommitted import tx; `createFieldIndex`'s existence check (separate connection) can't see the uncommitted `CREATE TABLE`. → `@TransactionalEventListener(AFTER_COMMIT)`. **Prevention**: any import-completed DDL listener → AFTER_COMMIT, never `@EventListener` (codified ENT #647).
2. **App-ready backstop crashed every startup** ("Tenant context is required") — startup thread has no `MetaContext` for `createFieldIndex`→`getModelDefinition`'s logging. → borrow owning tenant, set/clear `MetaContext`. Note: `MetaContext.getCurrentTenantId()` **throws** when unset (not null) — the first save/restore attempt was itself the crash.
3. **Cross-origin keyed preflight 403** — global `/api/**` CORS only allows admin origins + omits `X-Site-Key`. → path-specific public CORS for the keyed endpoint.
4. **Golden real-stack fixture debugging** (each ~1 round, caught in-loop): over-long synthetic `eventId` > `varchar(40)` → 500 (real SDK uses 26-char ULID); psql `boolean||text` renders `true` not `t`; `ab_behavior_event` missing from host stack (schema.sql baseline behind the migration → self-provision idempotently); `behavior_analytics` dashboard page lives in `core-dashboard` (must import that plugin too, not just core-site-key).
5. **Wrong docs-gate diagnosed first** — assumed the required "Documentation Quality Gate" = `check-docs-governance.mjs` (it's actually `check-docs.sh` → markdownlint). Fixed the governance checker (#1016, still a valid improvement) before discovering the real blocker was markdownlint MD025 (#1018). **Prevention**: when a CI check is red, read the workflow step's actual command, don't assume which script a check name maps to.

## Lessons Learned
- The real-import golden (SP4) caught 3 bugs that compileJava + unit + the SP2 in-process IT all passed — the assembled-product runtime gate is irreplaceable (AGENTS §2.2/§15).
- Import-completed DDL/meta listeners → AFTER_COMMIT; non-request-thread (startup/scheduler/event) platform-meta calls → set `MetaContext` tenant first. (ENT #647)
- The required OSS "Documentation Quality Gate" = `check-docs.sh --strict` (markdownlint MD025 + dead links); the `.mjs` governance checker is a separate, non-required job.

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **误判 required docs-gate 是 governance `.mjs`(实为 `check-docs.sh` markdownlint)** — 代价:多开 1 个 PR(#1016)+ 一轮分析才找到真 blocker(#1018,markdownlint MD025)— 本可如何更早避免:CI check 红时**先读该 workflow step 的真实命令**,别按 check 名猜映射到哪个脚本 — 根因:`[B 输入/诊断]`(check 名 → 脚本映射不透明,假设未先证)。注:#1016 本身仍是真改进(governance checker 的跨仓盲点),非纯浪费。
2. **backstop tenant-context fix v1 用 `getCurrentTenantId()` 取 previous 自己崩** — 代价:1 轮单测 — 本可如何更早避免:写「读当前 tenant」前先确认 unset 时行为(抛 vs null)— 根因:`[D 验证]`(假设 API 行为未先证;§15)。
3. **docs-gate 债多层(frontmatter #1011 → 跨仓 distill #1016 → markdownlint MD025 #1018)逐层揭开** — 代价:3 个 governance PR — 本可如何更早避免:第一次就跑**真正的 required gate 命令**(`check-docs.sh --strict`)拿全量,而非逐 error 追 — 根因:`[B 输入]`(required gate 的真实 checker 不显眼)。
> SP4 抓 3 bug、golden fixture 逐个修好 = 验证纪律的**正向战果**,非弯路。

### 为什么会发生(根因归类小结)
主要 **B 输入/诊断**(CI check 名→脚本映射不透明、required gate 真 checker 不显眼,导致先修错 gate + 逐层追债)+ 一处 **D 验证**(MetaContext API 行为假设未先证)。无 A 门禁假绿 / C 红线缺失类失败;SP3/SP4 本体经 TDD + 真栈 golden,零 ship 缺陷。

### 应该有哪些改进
- **B**:CI check 红 → 先 `gh run view --job <id> --log` 读该 step 的真实命令(本会话已自行纠正);OSS 两个 docs gate 的映射记入 memory 参考(下条)。
- **D**:调用平台/框架 API 前(尤其 throws-vs-null 这类),先读实现或单测确认行为(§15 已有,再触一次)。

### 已固化 / 待固化(更新文档)
- [x] ENT #647(canonical,OPEN 待 merge):`backend-spring-db.md` 改对「双触发都 @EventListener」+ 公开 keyed CORS 条目 + AGENTS 速查表行(3 bug 的通用教训)。
- [x] OSS #1016(MERGED):docs-governance checker 接受跨仓 precipitation(结构性修复,未来跨仓 distill 文档受益)。
- [x] OSS spec §4.5/§4.6 + 本 retro;memory「统一遥测」条目。
- [ ] 待固化 memory `reference-oss-docs-gates`(留 owner 决策):OSS 两个 docs gate 映射——**required「Documentation Quality Gate」= `scripts/check-docs.sh --strict`(markdownlint MD025〔frontmatter `title:` 当 H1〕+ dead-link);非 required「Docs Quality Gate」= `scripts/check-docs-governance.mjs`(frontmatter/precipitation)**。仓内 frontmatter 约定无 `title:`(用 `type/status/created`)。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前任务分支**:`feat/behavior-kafka-decouple-ingestion`(本 worktree,head `0b8e52391`,仅 design spec)；`feat/site-key-sp3-sp4-anonymous-sdk`(#1013,worktree `/Users/ghj/work/auraboot-sitekey-sp34`,head `1a87e7b67`,ahead/behind 6/0,clean)；ENT `docs/site-key-sp4-canonical-correction`(worktree `/Users/ghj/work/auraboot-ent-sitekey-canon`,head `711f9d8ad`)。
- **PR**:
  - **OSS #1013 · OPEN · BLOCKED(待 1 approval,3 required 全绿)· head `1a87e7b67` · base main**(SP3+SP4+resilience+retro,6 commits)
  - **OSS #1016 · MERGED**(origin/main `e198ac350`,已核对)· **OSS #1018 · MERGED**(origin/main `9ecca7d63`,已核对)
  - **ENT #647 · OPEN · head `711f9d8ad`**(canonical;enterprise 无 CI,直接可 merge)
- **本会话关键 commit(#1013 branch)**:`e2fe82dd0` SP3 / `a6e2a883e` SP4 / `b827f0fab` resilience / `1a87e7b67` retro。
- **未提交改动**:无(本 worktree spec 已 commit+push)。
- ⚠️ 旁注:canonical OSS checkout `/Users/ghj/work/auraboot/auraboot` 停在别会话的 codex 分支(本会话**全程未碰**,所有写入在隔离 worktree)。

### Runtime / 端口(host-first slot 模型,零 docker)
- **本会话用过 slot-66**(`sitekey-sp4-golden-66`,backend 6466 / vite 5166 / bff 6166 / DB `auraboot_66` / redis db 2)跑 SP4 golden,**已 `oss-golden-stack.sh destroy` 收口**(端口释放、无残留 java)。
- **当前无本会话 runtime / 进程在跑**。
- **Kafka build 起栈**:`scripts/oss-golden-stack.sh up <name> --slot <free>`;MQ 用平台 `MqProperties.type`(memory 本地 / kafka 用常驻 9092 broker)。

### Database / Seed 状态
- 无残留隔离栈(slot-66 destroyed)。SP4 golden 自 provision `ab_behavior_event`(host 栈 schema.sql 基线落后于 migration);Kafka build 起新隔离 runtime + reset 即可。

## Next Steps
1. **owner**:approve/merge **OSS #1013**(全绿待 review)+ **ENT #647**(canonical)。
2. **Kafka decouple build(fresh session,本分支)**:按 spec §7 — Phase 0 grep `infrastructure/mq` 确认 publish/subscribe API → TDD publisher/consumer(含 quarantine)→ service enqueue 改造 → memory+kafka 两档真栈 IT → SP4 AK-* 解耦回归 + quarantine golden。
3. 遥测平台其它独立线:server-outcome outbox / §5.4 / OTel。

## Context for Next Session
- **起点 spec(本线)**:`docs/superpowers/specs/2026-06-21-behavior-kafka-decouple-ingestion-design.md`(D1-D6 决策 + 组件 + 测试策略 + 执行顺序)。
- **复用**:`BehaviorCollectService.recordBatch`(下沉为 consumer)；平台 MQ 抽象 `platform/.../infrastructure/mq/MqProperties.java`(memory/redis/kafka);SP4 golden harness `scripts/oss-golden-stack.sh` + `web-admin/tests/e2e/behavior/`。
- **冻结契约**:topics `aura.behavior.events.v1` / `aura.behavior.quarantine.v1`(SoT §2.7);幂等 `unique(tenant_id,event_id)`;envelope §5.5。
- **SoT**:`docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md`（§2.4 可靠性 / §2.7 wire / §5.5 信封 / §12 M1 backlog）。
- 其它活跃任务现状见 `MEMORY.md`（本 handover 不复述）。
