---
type: handover
status: shipped
created: 2026-06-21
slug: site-key-registry-sp1
---

# Session Handover - 2026-06-21 · site-key registry (SP1) build → merge

## Session Summary
Built **SP1 of the anonymous-telemetry subsystem** (the public site-key → tenant registry) end-to-end via `/aura-endgame` from the owner-locked design (#976), through full host-first golden verification, to **MERGED #984** (squash `e2c5bc32e` on origin/main). No in-flight code; worktree + runtime fully torn down.

## Tasks Completed
- [x] **§9 architecture spike** (decisive first step) — verified server-set-on-create works via a platform `@Component CommandHandlerExtension`; empirically found config-level unique index does NOT work on `mt_` dynamic tables (see Reflection).
- [x] **Config plugin `plugins/core-site-key`** — dynamic model `behavior_site_key` (dual id + `site_key` + `name` + `status` dict + `origin_allowlist` jsonb) + DSL list/form/detail pages + `behavior.site_key.{read,create,manage}` permissions + role + menu + default-bootstrap.
- [x] **Platform `behavior/sitekey/`** — `SiteKeyGenerator` (`abk_` + 32 base62), `SiteKeyRegistry` (cross-tenant `resolveTenant` + Caffeine cache + evict), `SiteKeyCommandHandler` (`@Component`, owns create/disable via `requiresDslPersistence()=false`, server-gen globally-unique key, evict on disable).
- [x] **Verification (host-first, zero docker, all green):** 18 unit · 6 real-PG IT (`SiteKeyRegistryIT`) · DSL validator `import-directory-sync` `success:true` · command-pipeline golden (create→DB active, disable→DB disabled) · browser golden (list/create/disable + no raw-code + 0 console errors + screenshots) · deny=403 (read-only viewer, 0 writes) · static gates (permission/jsonb/oss-boundary).
- [x] **Docs/codify** — spec §9.1 (build outcome + decisions), decomposition backlog (SP2 index prerequisite), retro `docs/retro/2026-06-21-site-key-registry-sp1-retro.md`, MEMORY active-work updated.

## Tasks In Progress
None. SP1 is MERGED and closed out. SP2 is a separate fresh session (see Next Steps).

## Key Decisions
| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| Data model | **dynamic model** (kept) | Functionally complete; config index gap is non-blocking for SP1 | platform table `ab_behavior_site_key` + skipTableCreation model (heavier; kind:detail-list + custom-endpoint gotchas) |
| Create/disable handler | **platform `@Component` `CommandHandlerExtension`** | Telemetry is platform-native + resolver must be platform-side for SP2; mirrors shipped `AgentApprovalCommandHandler` | PF4J plugin jar (no in-repo precedent; untested classloader path) |
| Server-set key | command `type:create` + `requiresDslPersistence()=false`, **no explicit `handler`** | Handler owns insert; no-`handler` → import skips `S-EXT-HANDLER` | `handlerParams.dslPersistence` config flag (no in-repo example) |
| site_key unique index | **app-layer uniqueness (handler `existsAnyTenant` pre-check)**; DB index = SP2 prereq | Config `constraints.unique` is inert for `mt_` tables (verified 0/9) | (none viable in dynamic-model config) |

## Files Changed
All merged in #984 (squash `e2c5bc32e`). See PR diff; not duplicated here.
- **Plugin:** `plugins/core-site-key/**` (plugin.json + model/fields/bindings/commands/dicts/permissions/roles/default-bootstrap/menus + 3 DSL pages)
- **Platform:** `platform/src/main/java/com/auraboot/framework/behavior/sitekey/{SiteKeyGenerator,SiteKeyRegistry,SiteKeyCommandHandler}.java` + 4 test classes (`*Test` + `SiteKeyRegistryIT`)
- **Frontend:** `web-admin/tests/e2e/behavior/site-key-registry.golden.spec.ts`
- **Docs:** `docs/superpowers/specs/2026-06-21-site-key-registry-design.md` (§9.1) · `docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md` · `docs/retro/2026-06-21-site-key-registry-sp1-retro.md`

## Pitfalls & Workarounds
1. **Config `constraints.unique`/`searchable` produce NO index on `mt_` dynamic tables** (verified 0/9 mt_ tables have feature-driven indexes; field `feature` column not persisted at index-gen time).
   - **Solution**: keep dynamic model, enforce uniqueness in handler; defer `(tenant_id, site_key)` index to SP2. **Prevention**: for any dynamic-model field needing a DB index, don't assume config yields one — verify on a real table.
2. **Golden-stack slot-64 frontend collision**: another worktree (`auraboot-report-golden`) squatted ports 5164/6164 with a broken `iconv-lite`; the stack's health poll saw *their* Vite and falsely reported "frontend UP" (proxy login 500'd).
   - **Solution**: ran my own frontend on free ports 5180/6180 against my backend (6464), cleaned up **by PID only** (never touched their procs, §20). **Prevention**: before `--slot N`, check the slot's frontend ports for listeners + `lsof -p <pid> -d cwd` ownership, not just the allocation registry.
3. **Cross-checkout edit mislanding**: the decomposition edit + retro landed in the **canonical** working tree (main) instead of my worktree.
   - **Solution**: moved both into the worktree, restored canonical (only my 2 files). **Prevention**: when a file exists in both canonical + worktree, confirm the edit path is the worktree.
4. **`setup` Playwright project blocks `auth`** (02-test-pages needs full showcase seed, absent in minimal golden-stack bootstrap) → minted storageState with `--project=auth --no-deps`, ran golden with `--no-deps`.

## Lessons Learned
- Dynamic-model config indexes are inert (`mt_` tables) — enforce at app layer or own the index in the consuming slice.
- Server-set-on-create for a dynamic model = platform `@Component CommandHandlerExtension` + `requiresDslPersistence()=false` + command with no explicit `handler`.
- §15 win: verifying the spike's index claim empirically (real `\d`) before building on it caught a false guarantee at the DB, not in production.

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **跨 checkout 误写**(decomposition + retro 落 canonical main 而非 worktree)— 代价:~3 工具调用排查+修 — 本可如何更早避免:文件在 canonical/worktree 都存在时,Edit 前确认路径是 worktree — 根因:`[D 验证]`。
2. **golden-stack slot-64 前端端口撞并发 worktree**(5164/6164 被 report-golden 占,health poll 误报 UP)— 代价:多轮诊断(误以为 backend/node_modules 坏,实为端口归属)+ 自起前端绕过 — 本可如何更早避免:`--slot N` 前查该 slot 的 web/bff 端口 listener + cwd 归属,不只查 allocation registry — 根因:`[B 输入]` + `[A 门禁]`(golden-stack health poll 不校验监听进程归属 → 撞 slot 时说谎)。
3. **§9 index 推断翻转**(spike code-reading 说 config unique 建索引,实测不建)— 这是 §15 **成功**不是弯路:§9 规定 build 前实证,所以在 DB 层(`\d`)就抓到,没把假保证 ship。

### 为什么会发生(根因归类小结)
主要 **B 输入信息不足**(slot 端口/node_modules 并发状态未先验)+ 一处 **D 验证纪律**(跨 checkout 路径)。§9 的 index 发现是 D-纪律的正向战果。无逻辑/功能返工(核心一次到位)。

### 应该有哪些改进
- **B/A**:golden-stack `--slot N` 前,对该 slot 的 web/bff 端口跑 `lsof -iTCP:<port> -sTCP:LISTEN -t` + `lsof -p <pid> -d cwd`,确认无并发 worktree 占用;golden-stack health poll 应校验监听进程 cwd 属于本 checkout(否则撞 slot 时误报 UP)。
- **D**:多 checkout 并存时,凡 Edit 一个 canonical/worktree 同名文件,先 `pwd`/确认 file_path 前缀是 worktree。

### 已固化 / 待固化(更新文档)
- [x] 已写入 `docs/superpowers/specs/2026-06-21-site-key-registry-design.md` §9.1:dynamic-model 实测结论 + handler 决策 + index gap。
- [x] 已写入 `docs/backlog/2026-06-21-...-decomposition.md`:SP2 index 硬前置。
- [x] 已写入 `docs/retro/2026-06-21-site-key-registry-sp1-retro.md`:5 项完成复核 + A/B/C/D 反思 + 3 条 durable lessons。
- [x] 已更新 MEMORY active-work「统一遥测与分析平台」:SP1 MERGED #984 + SP2 index 前置 + 配置层 mt_ 索引不生成。
- [ ] 待固化(owner 决策,单次发生暂留 retro):**dynamic-model 配置层 unique/searchable 对 mt_ 表不生成索引** + **平台 @Component server-set 模式** → 若复发,升 `auraboot-enterprise/docs/agent-rules/engineering-gotchas/frontend-ssr-build.md` 或 `plugins-import-overlay.md` + AGENTS 速查表一行。
- [ ] 待固化(owner 决策):**golden-stack slot 撞并发 worktree → health poll 误报 UP**,排查用 `lsof -p <pid> -d cwd` → 若复发,升 `auraboot-enterprise/docs/agent-rules/oss-e2e-and-playwright.md` 或 golden-stack runbook。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **SP1 分支**:`feat/site-key-registry-sp1` — **MERGED_AND_DELETED**(本地 + 远端均删).
- **SP1 worktree**:`/Users/ghj/work/auraboot/auraboot-sitekey-sp1` — **已 remove**.
- **PR**:**#984 · MERGED · squash `e2c5bc32e` · base main**(已核对 `git log origin/main` 含该 commit + 23 个新文件在 origin/main).
- **本 handover 分支**:`docs/handover-2026-06-21-site-key-sp1`(base origin/main `e2c5bc32e`,待自身 PR).
- **未提交改动**(SP1 线):无.
- ⚠️ 旁注(并发,**勿动**):`auraboot-report-golden` worktree 的 orphan 前端进程仍占端口 5164/6164(其 backend 已无);非本任务,只报告。

### Runtime / 端口(host-first slot 模型,零 docker)
- **Runtime `sitekey-sp1-golden`(slot 64)= 已 destroy**:DB `auraboot_64` dropped · Redis `aura:auraboot:64:*` cleared · Kafka `auraboot.64.*` deleted · runtime state 删 · 端口 6464/5164/6164 释放(我的进程已停).
- **当前无本会话相关进程在跑**.
- 起栈样板(供 SP2 复用):`scripts/oss-golden-stack.sh up <name> --slot <free>`;⚠️ 选 slot 前查 web/bff 端口 listener + cwd 归属(见反思).

### Database / Seed
- 无残留(隔离栈已销毁)。SP2 自起新隔离栈 + reset。

## Next Steps
1. **SP2 — 匿名 ingestion 路径(fresh 会话)**:`/api/collect` keyed-anonymous 分支(从 site-key resolve tenant、不走 JWT)+ security 开放 keyed 路径 + 滥用防护基线(限流/origin/payload/key 状态).
2. **⚠️ SP2 硬前置(必做先于上线)**:给 `mt_behavior_site_key` 加 `(tenant_id, site_key)` 唯一索引 + `site_key` resolve 索引(`resolveTenant` 当前 seq-scan;SP1 仅 handler 跨租户预检兜底)+ 复核全局唯一性.
3. SP3(SDK 公开模式)、SP4(端到端匿名采集 golden)按依赖序,各自 fresh 会话.

## Context for Next Session
- **SP2 起点**(绝对路径):
  - SP1 retro(完整反思 + 决策):`/Users/ghj/work/auraboot/auraboot/docs/retro/2026-06-21-site-key-registry-sp1-retro.md`
  - 子系统分解:`/Users/ghj/work/auraboot/auraboot/docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md`
  - SP1 spec §9.1(build 实测结论):`/Users/ghj/work/auraboot/auraboot/docs/superpowers/specs/2026-06-21-site-key-registry-design.md`
- **SP2 消费的契约**:`SiteKeyRegistry.resolveTenant(siteKey) -> Optional<Long>`(platform `behavior/sitekey/`,跨租户、缓存、disable 时 evict).
- **采集后端现状**:`BehaviorCollectService`(tenant 来自 MetaContext,匿名需 SP2 改)· `BehaviorEventInput.anonId` 已存在 · `ab_behavior_event` UV 聚合已计 anon_id.
