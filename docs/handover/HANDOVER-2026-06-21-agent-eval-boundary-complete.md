# Session Handover - 2026-06-21 (agent-eval OSS boundary, complete)

> Supersedes the earlier M1-only handover `HANDOVER-2026-06-21-agent-eval-boundary-m1.md` (now stale). This is the full session record.

## Session Summary

From the owner's question ("is the device AI capability optional? does it get injected into OSS core?") → established the principle "core = AI *mechanism*, business = content, injected per-plugin on demand" → built it end-to-end: moved vertical agent **eval cases** out of the OSS core into per-plugin config (DB-backed), then closed every gap a review surfaced. **8 PRs, all MERGED. The agent-eval boundary line is 100% done** except one scoped-but-not-done backlog (a CRM tool migration).

## Tasks Completed (all MERGED)

- [x] **M1** — mechanism (`ab_agent_eval_case` table + `evalCases[]` in plugin `agent-definitions.json` + `importAgentDefinition` persistence + `EvalCaseStructureValidator` import-gate + `loadRegisteredCases` runtime read) + migrate device agent. PRs auraboot#967 / plugins#104.
- [x] **Flyway hotfix** — #967 collided with the report/BI work on `V20260621000000`; renumbered agent_eval_case → `V20260621000250`. auraboot#968.
- [x] **M2** — migrate pcba-quality to plugins/quality; **delete cs/competitive (orphans, no real agent)**; delete `AgentArchetypeEvalCases.java` → OSS core has ZERO vertical eval cases. auraboot#977 / plugins#108.
- [x] **D3b** — per-agent regression-gate isolation via the existing `ab_capability_eval_run.scope` column (= agentCode; **no migration**); plus repaired a 3rd red-on-main regression (device live ITs). auraboot#982 + #986.
- [x] **Follow-ups (#986)** — (a) plugin pattern doc `docs/plugin-development/agent-capabilities-in-plugins.md`; (b) extended boundary linter to any quoted business command code across `framework/agent` + `framework/rag`; (c) `PcbaQualityAgentLiveEvalIT` (restores live coverage M2 dropped); (d) guarded device live-IT Order(4) behind `scored>0`.
- [x] **UI red line** — reinforced AGENTS §2 "UI-bearing feature needs real-browser E2E + screenshot, not backend-only". auraboot-enterprise#636.
- [x] **Repaired 3 red-on-main regressions I introduced** (see Pitfalls): Flyway dup-version (#968), `CapabilityEvalServiceTest`+`CapabilityEvalLiveIT` (#982), device live ITs (#986).

## Tasks In Progress / Open

- [ ] **Migrate `SendCustomerReplyToolHandler` out of OSS core → crm plugin** (the extended linter caught it: a `@Component` in core hardcoding `crm:create_activity`, special-cased in `CustomToolProvider`). **Scoped this session, NOT done** — it's a real project, not a quick fix:
  - `CustomToolProvider` (core) special-cases it (`if (TOOL_CODE.equals(rawCode))`); real IT `CustomerServiceAgentIntegrationTest` exercises the full flow; **crm repo is config-only (no plugin.json / backend / PF4J ToolProvider)** so option A needs converting crm → hybrid plugin first.
  - **3 options**: **A** full migrate (crm→hybrid + ToolProvider + IT rework + PF4J golden — big); **B** delete the custom handler, have agents use `dsl.command` → existing `crm:create_activity` (medium, no crm-hybrid; verify no lost email-wrapper semantics) ← **recommended**; **C** keep the `boundary-allow` (already in place, tracked).
  - Currently neutralized by a `boundary-allow` comment on the line — gate is green, debt tracked.

## Key Decisions

| Decision | Chosen | Rationale |
|---|---|---|
| eval-case carrier | JSON `evalCases[]` in plugin agent-definitions.json → `ab_agent_eval_case` | config-only friendly; reuses import pipeline; pure declarative data |
| case↔agent (D1) | (b) attached sub-resource (lifecycle bound to agent) | scoped by `(tenant_id, agent_code)`; reuses PluginResource conflict/rollback |
| cross-plugin additive (D2) | not supported (YAGNI) | each agent's cases come from its plugin |
| D3 split | D3a dependency-skip in M1; D3b per-agent gate via `scope` column (no migration) | M1 had 1-2 agents (aggregate fine); reuse unused `scope` col → no Flyway risk |
| cs/competitive | **deleted (orphans)** not migrated | no real agent definition exists → "exam for a non-existent employee" |
| extended linter detection | quoted full business command code (`"qc:create_capa"`), across agent+rag, comment/boundary-allow exempt | quote-anchored avoids `type:`/`shape:` false positives; honest limit (NL prompts ungated) |
| SendCustomerReply | `boundary-allow` + TODO (defer) | real cross-repo refactor; out of scope; tracked |

## Files Changed (all on main `27a5fa5f1` / plugins via #104/#108)

### OSS core mechanism (`platform/src/main/.../framework/`)
- `agent/eval/`: NEW `AgentEvalCase`+mapper, `EvalCaseStructureValidator`, `GenericEvalCaseFixture`; DELETED `AgentArchetypeEvalCases.java` + `AgentArchetypeLiveQualityIT`.
- `agent/service/CapabilityEvalService.java`: `loadRegisteredCases` + `loadRegisteredCasesByAgent` + D3a skip + scope-tagged persist/gate; I1 all-unavailable short-circuit.
- `agent/eval/ScheduledCapabilityEvalJob.java`: per-agent runs by scope.
- `plugin/dto/imports/AgentDefinitionDTO.java` (+evalCases), `plugin/service/impl/PluginResourceImporterImpl.java` (persist + rollback/restore lifecycle).
- `agent/tool/SendCustomerReplyToolHandler.java`: `boundary-allow` comment (no logic change).
- `resources/db/migration/core/V20260621000250__agent_eval_case.sql`.
### Tests (`platform/src/test`)
- NEW `AgentEvalCaseImportIT`, `MultiPluginEvalCaseCoexistenceIT`, `CapabilityEvalUnavailableCaseTest`, `CapabilityEvalPerAgentGateTest`, `PcbaQualityAgentLiveEvalIT`, `EvalCaseStructureValidatorTest`.
- FIXED `CapabilityEvalServiceTest`, `CapabilityEvalLiveIT`, `DeviceAgentLiveEvalIT`, `DeviceOperationsAgentLiveEvalIT` (D3a-aware no_scoreable handling), `ScheduledCapabilityEvalJobTest`, `CapabilityEvalLlmModeTest`, retargeted `AgentArchetypeEvalCasesTest`, `LlmProviderFactoryTest` (stale-on-main compile fix).
### Scripts / docs / plugins
- `scripts/check-agent-eval-boundary.mjs` (extended). `docs/plugin-development/agent-capabilities-in-plugins.md` (NEW, +overview link).
- plugins: `pcba-manufacturing/config/agent-definitions.json` (device evalCases), `quality/config/agent-definitions.json` (pcba-quality evalCases).
- enterprise: `AGENTS.md` UI red-line row (#636).

## Pitfalls & Workarounds

1. **THREE red-on-main regressions, all from the same root** — I merged core-service changes verified with a *curated* `--tests` list that omitted tests touching the changed code, so they stayed RED on main undetected:
   - Flyway dup-version (verified version-free at branch creation, not re-checked at merge while a concurrent migration landed) → hotfix #968.
   - `CapabilityEvalServiceTest`+`CapabilityEvalLiveIT` (M1's I1 `no_scoreable_cases` short-circuit broke them; my M1 run omitted them) → #982.
   - `DeviceAgentLiveEvalIT`+`DeviceOperationsAgentLiveEvalIT` Order(4) (D3b changed eval flow; my D3b run used `*CapabilityEval*`, omitting `*Device*LiveEvalIT`) → #986.
   - **Solution/Prevention**: run the FULL set of tests that touch a changed core service (`grep -rl <Service>` the test tree), not a curated subset; re-verify Flyway version against current `origin/main` immediately before merge. Both codified.
2. **Subagent self-reported "N/N green" while an independent run found failures** (`tool_uses` anomalies on a few subagents). Verify-don't-trust caught it every time — always re-ran the suite + judged the XML myself.
3. **grep-path false alarm** — ran `find plugins` from the OSS auraboot checkout (its bundled plugins dir), got "no evalCases", briefly thought the migrations were lost. Authoritative `git show origin/main:<plugins-repo>/...` confirmed intact. Lesson: for the separate `plugins` repo, query it directly, not auraboot's bundled dir.

## Lessons Learned

- The layered process (preflight → per-task TDD+review → verify-don't-trust → opus whole-branch review → post-merge memory cross-check + full-touching-test audit) caught everything; the ONLY defects reaching main were the 3 verification-gap regressions, each found and fixed when I finally ran the complete touching-test set.
- Reusing platform primitives is free correctness: `PluginResource`/`ConflictStrategy`/rollback (multi-plugin safety) and the unused `scope` column (per-agent gate, no migration).
- A boundary linter that does what was asked surfaces real debt immediately (`SendCustomerReplyToolHandler`).

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车
1. **3 次 red-on-main 回归**(Flyway / CapabilityEvalServiceTest / device live IT)— 代价:中(各需 1 个 hotfix PR + 审计才发现)— 本可如何更早避免:merge core 改动前跑「所有 touch 它的测试」+ merge migration 前重查版本 — 根因:**`[D 验证]`**(curated 测试列表 + 创建时验证非 merge 时)+ 部分 **`[A 门禁]`**(Actions 关闭、无 CI 兜底,red 只在手动跑时现)。
2. **信 subagent 自报测试数** — 代价:低(每次都独立重跑抓到)— 根因:`[D 验证]`,已是纪律,执行到位。
3. 无其它重大弯路;机制设计本身扎实(D1b/D3b/复用 PluginResource 一次到位)。

### 为什么会发生(根因小结)
压倒性是 **D 验证纪律**(curated 测试子集漏跑 touch 改动的测试)放大了 **A 门禁缺失**(无 CI)。同一根因复发 3 次 = 这场会话最大教训。

### 应该有哪些改进
- 改 core/shared service:`grep -rl <Service>` 测试树跑全部,禁 curated 子集。
- merge migration PR 前 `git fetch` 重查版本唯一。
- subagent 报的测试数一律独立重跑 + 判 XML。
（均已固化,见下。)

### 已固化 / 待固化
- [x] memory `feedback-verify-core-change-against-all-touching-tests` + 指针
- [x] memory `feedback-flyway-version-recheck-before-merge` + 指针
- [x] memory `feedback-ui-interaction-needs-screenshot-e2e` + 指针;AGENTS.md UI 红线行(enterprise#636)
- [x] 插件范例 canonical `docs/plugin-development/agent-capabilities-in-plugins.md`(#986)+ overview 链接
- [x] memory active-work「Agent eval-case OSS 边界」→ done墓碑
- [ ] (owner 决策)`SendCustomerReplyToolHandler` → crm 插件迁移:推荐路径 B(删自定义 handler 走 `dsl.command crm:create_activity`),fresh 会话做;现 boundary-allow 已 tracked

## 运行态快照 (Operational State)

### 分支 / Worktree / PR
- **本会话所有产物全 MERGED**:auraboot `#967/#968/#977/#982/#986` · plugins `#104/#108` · enterprise `#636`(均已核 origin/main 含其 squash commit)。
- **canonical main**:auraboot `27a5fa5f1` · enterprise `e10ecdb7c` · crm `7e916d5`(均 main,我无未提交;auraboot 的 `data/` 是并发会话 untracked 残留、非我)。
- **plugins canonical** 现在别的会话(codex)的分支 `codex/bom-quote-golden-fixes`(7 未提交)——**并发会话,勿动**;我的 plugins 改动早已 merge 进 plugins main(#104/#108)。
- **Worktree**:本会话建的全部已删;仅剩本 handover 临时 `auraboot-ho-eval`,提交后即清。
- **未提交(我的)**:无。

### Runtime / 端口
- **无专用 runtime**:所有 IT 跑共享 `aura_boot`:5432(integration-test profile,user `ghj` 空密码);live IT 用 env `DEEPSEEK_API_KEY`。无残留监听进程。
- ⚠️ 共享 `aura_boot` 的 `flyway_schema_history` 有 stale `20260621000000`(M1 IT 期以旧号 applied 过 agent_eval_case;已改 000250)——下次对 aura_boot 跑 `reset-db` 自动 reconcile;fresh 环境/CI 用全新 migrate(已 throwaway-DB 验证 10/10 clean)。

### Database / Seed
- 无遗留隔离 DB。验证用共享 `aura_boot`(ddl-auto none)。

## Next Steps

1. **(若做 SendCustomerReply backlog)** 走路径 B:删 `SendCustomerReplyToolHandler` + `CustomToolProvider` 的特判,让 CS agent 用 `dsl.command` 调 `crm:create_activity`;改 `CustomerServiceAgentIntegrationTest`;真栈验证 CS-agent 审批+回复流程。fresh 会话。
2. 未来任何插件加 AI 能力:照 `docs/plugin-development/agent-capabilities-in-plugins.md`(本会话产出的范例)。
3. 别的独立线(commerce Phase3 / billing P4 / OSS 覆盖→80 等)各有 memory 起点指针——非本线,不在此展开。

## Context for Next Session
- 设计真源:`auraboot/docs/superpowers/specs/2026-06-21-pluggable-agent-eval-capabilities-design.md`(§9 = M2 清单,已全做)。
- 机制落点:`framework/agent/eval/` + `framework/agent/service/CapabilityEvalService.java` + `framework/plugin/service/impl/PluginResourceImporterImpl.java`。
- 门禁:`auraboot/scripts/check-agent-eval-boundary.mjs`(核心零业务命令码)+ `check-oss-boundary.sh`。
- 验证套件:`cd platform && DEEPSEEK_API_KEY=… ./gradlew :test --tests '*CapabilityEval*' --tests '*AgentEvalCase*' --tests '*MultiPlugin*' --tests '*EvalCaseStructureValidator*' --tests '*Device*LiveEvalIT*' --tests '*PcbaQualityAgentLiveEvalIT*' --tests '*ScheduledCapabilityEvalJobTest*'`(58 test,全 green)。
- 并发检测:接任一线前 `git fetch` + `git worktree list` + `git ls-remote --heads origin '*<feature>*'`。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
