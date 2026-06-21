# Session Handover - 2026-06-21

## Session Summary

Took owner's question ("is the device-agent capability optional? does it get injected into OSS?") all the way to shipped: designed + built **M1 of "pluggable per-business AI eval capabilities"** — vertical agent **eval cases** moved out of the OSS core into per-plugin config, DB-backed, multi-plugin-safe. Full cycle: brainstorm → spec → plan → 11-task subagent-driven TDD → opus final review → fixes → 2 PRs merged → **+ a post-merge Flyway duplicate-version hotfix** discovered via memory cross-check.

## Tasks Completed

- [x] Brainstorm + spec (`docs/superpowers/specs/2026-06-21-pluggable-agent-eval-capabilities-design.md`) — incl. multi-plugin coexistence design grounded in the existing `PluginResource` lifecycle.
- [x] Plan (`docs/superpowers/plans/2026-06-21-pluggable-agent-eval-capabilities-m1.md`) — 11 TDD tasks.
- [x] Executed all 11 tasks via subagent-driven-development (implementer + reviewer per task, verify-don't-trust between each).
- [x] opus whole-branch final review → "Ready to merge with fixes"; both gating findings fixed.
- [x] **OSS PR #967 MERGED** + **plugins PR #104 MERGED** (merge-coupled, plugins first).
- [x] Worktrees + branches cleaned (`MERGED_AND_DELETED`).
- [x] **Hotfix PR #968 MERGED** — resolved a Flyway duplicate-version collision the M1 merge introduced.

## Tasks In Progress

None. M1 + hotfix fully closed. **M2 is the only remaining work (deferred by design, see Next Steps).**

## Key Decisions

| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| Eval-case carrier | JSON `evalCases[]` in plugin `agent-definitions.json` → `ab_agent_eval_case` | Config-only friendly, reuses `importAgentDefinition`, cases are pure declarative data | PF4J extension (hybrid-only); Spring bean SPI (PF4J jars not component-scanned → can't leave OSS) |
| Case ↔ agent relationship (D1) | (b) attached sub-resource — lifecycle bound to the agent | Cases scoped by `(tenant_id, agent_code)`; agent's conflict/rollback already handled by `PluginResource` | (a) standalone `ResourceType.AGENT_EVAL_CASE` — needed only for cross-plugin additive |
| Cross-plugin additive (D2) | Not supported (YAGNI) | Each agent's cases come from its defining plugin | Defer to `chainsAfterPrimary`-style mechanism if a platform-wide red-line case set is ever needed |
| Engine robustness (D3) | D3a (dependency-aware skip) in M1; D3b (per-agent gate isolation) → M2 | M1 has only 1-2 device agents → aggregate gate doesn't get polluted; D3b needs `agent_code` on `ab_capability_eval_run` | Do all of D3 in M1 (rejected: schema churn for no M1 benefit) |
| Multi-plugin safety | Reuse platform `PluginResource` + `ConflictStrategy` + rollback | Already battle-tested for agent defs; don't reinvent | Hand-rolled delete-by-agent_code (rejected — was my original draft, unsafe) |

## Files Changed

All landed on `main` via #967 / #104 / #968 (no live worktree retains them). Deliverable = 21 files:

### Backend (OSS `platform/src/main`)
- `framework/agent/entity/AgentEvalCase.java`, `framework/agent/mapper/AgentEvalCaseMapper.java` — new ORM.
- `framework/agent/eval/EvalCaseStructureValidator.java` — pure validator (extracted from old archetype test).
- `framework/agent/eval/GenericEvalCaseFixture.java` — vertical-free fixture for OSS deterministic CI.
- `framework/agent/eval/AgentArchetypeEvalCases.java` — **device methods deleted** (cs/pcba/competitive remain for M2).
- `framework/agent/eval/ScheduledCapabilityEvalJob.java` — consumes `loadRegisteredCases` instead of `all()`.
- `framework/agent/service/CapabilityEvalService.java` — `loadRegisteredCases` (DB read) + D3a skip + all-unavailable short-circuit (no_scoreable_cases, no persist/gate).
- `framework/plugin/dto/imports/AgentDefinitionDTO.java` — `evalCases` field.
- `framework/plugin/service/impl/PluginResourceImporterImpl.java` — import persistence (DELETE+INSERT per `(tenant_id, agent_code)`) + import-time validator gate + rollback/restore lifecycle.
- `resources/db/migration/core/V20260621000250__agent_eval_case.sql` — table (**renumbered from 000000 by #968**).

### Tests (OSS `platform/src/test`)
- New: `EvalCaseStructureValidatorTest`, `AgentEvalCaseImportIT`, `MultiPluginEvalCaseCoexistenceIT`, `CapabilityEvalUnavailableCaseTest`.
- Modified: `AgentArchetypeEvalCasesTest` (retargeted at mechanism), `DeviceAgentLiveEvalIT` + `DeviceOperationsAgentLiveEvalIT` (repointed to test-local fixtures, D3a-aware, allow valid refusal), `ScheduledCapabilityEvalJobTest` (seam test), `CapabilityEvalLlmModeTest` (mapper-mock collateral), `LlmProviderFactoryTest` (**pre-existing main-health fix** — see Pitfalls).

### Plugins repo
- `pcba-manufacturing/config/agent-definitions.json` — `evalCases` on both device agents (#104).

### Scripts
- `scripts/check-agent-eval-boundary.mjs` — new boundary linter (forbids vertical eval-case literals in OSS `framework/agent` main; CWD guard).

## Pitfalls & Workarounds

1. **Flyway duplicate-version collision on main (the big one — caught POST-merge)**
   - **Problem**: #967 added `V20260621000000__agent_eval_case.sql`; the concurrently-merged report/BI work (#956) added `V20260621000000__create_ab_report.sql`. Two migrations sharing version `20260621000000` → Flyway fails on any fresh `migrate`/`reset-db` ("Found more than one migration with version").
   - **Root cause**: T1 checked version-freeness at branch-creation time (BASE 43ded70ca), but the report migration landed *between* T1 and my merge of #967. I did **not** re-run the version check immediately before merging.
   - **Solution**: #968 renamed `agent_eval_case` → `V20260621000250` (standalone table, no FK → pure rename). Verified by a **fresh `flyway migrate`** of all 10 core migrations onto a throwaway DB: succeeds, ends at `v20260621000250`, table created, 0 duplicates.
   - **Prevention**: **Re-verify Flyway version uniqueness against *current* `origin/main` immediately before merging any migration-bearing PR** — not just at branch creation. Concurrent same-day sessions collide on `V<date>0000xx`. (This recurred — the report session hit it same-day via #963. See codify below.)

2. **Plan code blocks had a wrong JSONB import path** — plan used `com.auraboot.framework.common.mybatis.JsonbListTypeHandler`; real path is `application.database.mybatis.*`. Mitigated by the plan instructing the implementer to grep `AgentDefinition.java` for the proven import; implementer caught it (T2). No impact.

3. **Plan's gradle invocation was wrong** — `:platform:test` from worktree root, but `gradlew` is at `platform/gradlew` and `platform` is the gradle root (module path `:test`). Caught at preflight before any task ran; fixed in plan Global Constraints.

4. **Device live ITs failed 3/7 on first T9 run** — `liveDeviceEvalRunsInLlmModeAndPersists` asserted `size == totalCases`, incompatible with T7's D3a (which excludes catalog-absent cases) when the bare `:test` catalog is empty; + one adversarial case where DeepSeek's empty selection is a *valid* refusal. **Verify-don't-trust** confirmed the cases were transcribed byte-identical → T9 behavior-preserving → failures pre-existing/environmental. Fixed: D3a-aware assertion + allow empty-as-refusal. 7/7 green.

5. **I1 — all-unavailable run polluted the gate** (found by opus final review): an all-unavailable run yielded `weightedScore=0.30`, got persisted + fed `RegressionGate`. Fixed: short-circuit to `no_scoreable_cases`, no persist/gate.

6. **Boundary linter false-green** (final review): `check-agent-eval-boundary.mjs` exited 0 when run from the wrong dir (scanned nothing). Fixed: `fs.existsSync(ROOT)` guard → exit 2.

7. **`origin/main` had a pre-existing broken test compile** — `LlmProviderFactoryTest` stale vs a 6-arg ctor. Carried a test-only fix so the suite compiles; rides in with #967.

8. **SDD scratch leaked into a commit** — a subagent force-added `.superpowers/sdd/final-fix-report.md` (gitignored scratch). Removed via `git rm --cached` + commit.

## Lessons Learned

- **The layered process worked**: preflight → per-task TDD+review → verify-don't-trust → opus whole-branch review → **post-merge memory cross-check** each caught real issues. The only defect that reached `main` was the Flyway collision (a *timing/merge-gate* gap, not a code-review gap) — and the memory cross-check during handover caught it before it bit anyone.
- **Verify-don't-trust paid off concretely**: the T9 "pre-existing, not my fault" claim was *true*, but only confirmable by diffing transcribed cases against originals — I proved behavior-preservation rather than trusting it.
- **Reusing platform primitives** (`PluginResource`/`ConflictStrategy`/rollback) made multi-plugin safety nearly free; my first-draft hand-rolled delete was the unsafe path.
- **Live LLM ITs need a loaded tool catalog** — in bare `:test` the catalog is empty (no plugins/models), so they're loaded-stack tests; assertions must account for D3a unavailability.

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车
1. **Flyway duplicate-version collision reached `main`** — 代价:中(post-merge 才发现,需 hotfix PR #968;但 main fresh-reset 在此之前是坏的)— 本可如何更早避免:merge #967 前 `git fetch origin` + 重查 migration 版本号是否仍唯一 — 根因:**`[B 输入]`**(plan 的 final-verification 写了"merge 前重查版本",但我执行 merge 时没跑那一步;并发会话的 #956 在 T1 与 merge 之间落了同号)+ **`[D 验证]`**(在 BASE 时验过、merge 前没复验)。
2. **Device live ITs 首轮 3/7 fail → 误判风险** — 代价:低-中(深入 root-cause ~若干轮)— 本可如何更早避免:plan 的 M1 acceptance 不应写"device live ITs 5/5 in `:test`"(bare `:test` catalog 空,这些是 loaded-stack 测试)— 根因:`[B 输入]`(plan acceptance criterion 过乐观)。验证纪律(D)反而做得对:transcription-identical 实证 + 修复。
3. 其余(JSONB import / gradle path / I1 / linter / scratch leak)均被流程**当场或终审拦下**,未流入 main —— 非翻车,是门禁生效。

### 为什么会发生(根因归类小结)
主因 **B 输入**(plan 的两处:migration 版本只在创建时验、live-IT acceptance 过乐观)+ 一处 **D 验证**(merge 前没复验版本)。门禁质量(A)/提示词(C)本会话表现良好:per-task review + opus 终审 + verify-don't-trust + handover 期 memory 交叉核对,逐层抓住了除 Flyway 外的所有问题。

### 应该有哪些改进
- **plan 模板**:任何含 Flyway migration 的 plan,"Final Verification" 必含一行硬步骤「merge 前 `git fetch origin` + 重查 `db/migration/core` 无重复版本号(对照当前 origin/main,不是 branch 创建时)」。
- **plan acceptance**:涉及 live-LLM IT 的 acceptance 要写明"需 loaded stack(plugin/model catalog 非空)";bare `:test` 只证机制不证 live 选择。
- **subagent-driven fix-dispatch prompt**:显式「不要把 `.superpowers/` 下的 report/scratch `git add`/commit」。

### 已固化 / 待固化(更新文档)
- [x] memory `feedback-flyway-version-recheck-before-merge.md` + MEMORY.md 指针:并发会话同日撞 `V<date>0000xx`,merge migration PR 前必 `git fetch` 重查版本唯一(非仅创建时)。本会话 #968 + 同日 designer #963 两次翻车。
- [x] memory active-work「Agent eval-case OSS 边界」更新为 M1 全 MERGED + M2 backlog。
- [ ] 待固化(owner 决策,enterprise canonical 需 worktree+PR):`auraboot-enterprise/docs/agent-rules/flyway-schema-change-and-local-bringup.md` 增「merge 前重查版本唯一」一节 + AGENTS 红线 flyway 行加关键字「merge 前 git fetch 重查版本(并发同日撞 V<date>0000xx)」。建议措辞见本 handover Pitfall 1 Prevention。
- [ ] 待固化(M2 时):移 `GenericEvalCaseFixture` 出 `src/main`;`replaceEvalCases` 对两个 list/map 字段 null-coalesce(解耦 MyBatis insert-strategy 默认依赖);plan live-IT acceptance 措辞改进。

## 运行态快照 (Operational State)

### 分支 / Worktree / PR — 全部收口
- **当前分支**:本任务三条 feature/fix 分支均已 `MERGED_AND_DELETED`(OSS `feat/pluggable-agent-eval-boundary` + plugins `feat/pcba-device-eval-cases` + OSS `fix/agent-eval-flyway-version-collision`)。
- **canonical main**:OSS `31e963e5c`(含 #967+#968)· plugins `cbf0ec1`(含 #104)—— 均已 ff-update。
- **PR**:`auraboot#967` MERGED · `auraboot-plugins#104` MERGED · `auraboot#968` MERGED(全部已核 origin/main 真含其 commit)。
- **Worktree**:本任务 worktree 全部移除(其它会话 worktree 未动)。**仅剩本 handover 自己的临时 worktree `auraboot-handover-eval`**,提交后即清。
- **未提交改动**:无(除本 handover 文件)。

### Runtime / 端口
- **无专用 runtime**:本工作未 `dev.sh runtime allocate`;后端 IT 直接跑共享 `aura_boot`(integration-test profile,user `ghj` 空密码),live IT 用 env `DEEPSEEK_API_KEY`。无常驻服务/监听进程残留。
- **依赖 broker**:仅 Postgres `:5432`(共享 aura_boot)+ DeepSeek(env key)。Redis/Kafka 起着但本工作未独占。

### Database / Seed 状态
- **⚠️ 共享 `aura_boot` 有 stale flyway_history**:IT 期间 `agent_eval_case` 以旧版本 `20260621000000` 应用过;main 已改为 `20260621000250`。共享 aura_boot 的 `flyway_schema_history` 因此与 filesystem 不一致(`flyway validate` 会报 20260621000000 的 description/checksum mismatch)。**不影响 ddl-auto none 的 IT(表存在即可),但下次对 aura_boot 跑 `reset-db` 即自动 reconcile**。Fresh 环境/CI 用 main 全新 migrate 无此问题(已 throwaway-DB 验证 10/10 clean)。

## Next Steps

1. **M2**(干净 fresh session,heavy):迁 `csAgent`(crm)/`pcbaQualityAgent`(quality)/`competitiveAgent`(归属待定)→ 删 `AgentArchetypeEvalCases.java` → 去掉 linter `EXCLUDE_FILES` → **D3b** per-agent gate 隔离(给 `ab_capability_eval_run` 加 `agent_code` 维度)。完整清单见 spec §9。
2. **(可选)owner 决策**:把 Flyway「merge 前重查版本」codify 到 enterprise canonical(见上 [ ] 待固化)。
3. **(可选)M2 顺带**:`GenericEvalCaseFixture` 移出 `src/main`;`competitiveAgent` 归属定夺。

## Context for Next Session

- 起点:spec `auraboot/docs/superpowers/specs/2026-06-21-pluggable-agent-eval-capabilities-design.md`(§9 = M2 清单)+ plan `.../plans/2026-06-21-pluggable-agent-eval-capabilities-m1.md`。
- 机制落点:`framework/agent/eval/` + `framework/agent/service/CapabilityEvalService.java` + `framework/plugin/service/impl/PluginResourceImporterImpl.java`(`importAgentDefinition`/`rollbackResource`/`replaceEvalCases`)。
- M2 迁移范式照 device:cases 进各 vertical 插件 `agent-definitions.json` `evalCases[]`,删 OSS 方法,linter EXCLUDE 移除后须保证 OSS `framework/agent` main 无 `crm:`/`qc:`/`iot_`/`pe:`/`mfg:` eval-case 字面。
- 并发检测(M2 起手):`git fetch && ls db/migration/core`(防再撞 Flyway 版本)+ `git ls-remote --heads origin '*eval*'`。
- DeepSeek live:env `DEEPSEEK_API_KEY` 已配(owner 持久授权)。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
