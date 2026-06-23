---
type: handover
status: shipped
created: 2026-06-21
slug: site-key-sp2-anonymous-ingestion
---

# Session Handover - 2026-06-21 · site-key SP2 (anonymous keyed ingestion) build → merge + canonical codify

## Session Summary
Built **SP2 of the anonymous-telemetry subsystem** (public unauthenticated keyed ingestion) end-to-end via brainstorming → writing-plans → TDD execution, from the owner-aligned design through host-first golden, to **MERGED OSS #995**. Then codified the durable index lesson to enterprise canonical (**MERGED ENT #640**). No in-flight work; both worktrees torn down (MERGED_AND_DELETED).

## Tasks Completed
- [x] **Index来龙去脉 analysis doc** (owner asked for it twice, scenario-first) — `docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md` (§0 user-scenario narrative + §3 global-vs-tenant-prefix + §4 option elimination + §6 §4.1/§8 compatibility + §5.2 dual-trigger timing).
- [x] **SP2 design spec + impl plan** — `docs/superpowers/specs/2026-06-21-site-key-anonymous-ingestion-sp2-design.md` + `docs/superpowers/plans/2026-06-21-site-key-anonymous-ingestion-sp2.md` (7 TDD tasks).
- [x] **7 TDD tasks built (OSS #995)** — `recordAnonymous` · `SiteKeyOriginPolicy` · `KeyedCollectGuard` · `POST /api/collect/keyed` + whitelist · `SiteKeyIndexInitializer` (Option A dual-trigger) · real-PG HTTP golden IT · SP1 doc index-wording correction.
- [x] **Latent platform bug fixed** — `GlobalExceptionHandler` mapped every `ResponseStatusException` to 500; added a status-honoring handler (+2 unit tests; existing 24 still green).
- [x] **51 tests green** (host-first, zero docker): service 3 / origin 4 / guard 6 / initializer 4 / handler 26 / `KeyedCollectIT` 8.
- [x] **Static gates** OSS: jsonb ✅ / oss-boundary ✅ / permission-codes ✅ (0 drift) / controller-authz (only my controller baselined) / docs-governance (my warning fixed).
- [x] **Codified the index lesson to enterprise canonical (ENT #640)** — `engineering-gotchas/backend-spring-db.md` entry + AGENTS speed-table row.
- [x] **Both PRs merged + worktrees/branches cleaned (MERGED_AND_DELETED).** Memory active-work updated.

## Tasks In Progress
None. SP2 is closed. SP3 (SDK public mode) / SP4 (e2e golden) are separate fresh sessions by design.

## Key Decisions
| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| Anonymous entrypoint | **separate whitelisted `POST /api/collect/keyed`** | `JwtAuthenticationFilter` is fail-closed; whitelisting `/api/collect` wholesale would break the authenticated M1 path | whitelist `/api/collect` + modify the JWT filter (touches a security chokepoint) |
| `site_key` index semantic | **global `UNIQUE(site_key)` single-column** | resolve is cross-tenant (`WHERE site_key=?`, no tenant_id); composite can't serve it + allows cross-tenant key bleed | `(tenant_id, site_key)` (SP1 docs' wording — corrected) |
| Index creation mechanism | **Option A: `SchemaManagementService.createFieldIndex(...UNIQUE)` on dual trigger** (`PluginImportCompletedEvent` + `ApplicationReadyEvent`) | config-level inert on mt_, Flyway can't reach runtime-created table, createFieldIndex gives column-level global unique + `indexExists` idempotency | fix import-path root cause (MultiTenantIndexManager forces tenant prefix → wrong shape); reset/init script (prod-unreliable) |
| Abuse protection | **full baseline in one round** | public unauth endpoint = DDoS/dirty-data vector without it | split SP2a/SP2b |
| Rate limiter | mirror in-process `ApiRateLimiter` | platform's existing baseline (login uses it) | Redis cross-node — flagged as hardening follow-up |

## Files Changed
All merged in **OSS #995** (squash `4a38ae287`) and **ENT #640** (squash `cf6f08ee8`). Not duplicated in full; see PR diffs.

### Backend (OSS #995)
- `platform/.../behavior/service/BehaviorCollectService.java` — `recordAnonymous(events, tenantId)` + shared `recordBatch`
- `platform/.../behavior/keyed/{SiteKeyOriginPolicy,KeyedCollectGuard,KeyedCollectController}.java` — new keyed package
- `platform/.../behavior/sitekey/SiteKeyIndexInitializer.java` — Option A dual-trigger
- `platform/.../application/web/handler/GlobalExceptionHandler.java` — `@ExceptionHandler(ResponseStatusException.class)`
- `platform/.../application/security/WhiteList.java` — `/api/collect/keyed`

### Tests (OSS #995)
- `behavior/service/BehaviorCollectServiceAnonymousTest` · `behavior/keyed/{SiteKeyOriginPolicyTest,KeyedCollectGuardTest,KeyedCollectIT}` · `behavior/sitekey/SiteKeyIndexInitializerTest` · `application/web/handler/GlobalExceptionHandlerTest` (+2)

### Config / Docs
- `scripts/controller-authz-baseline.json` — +KeyedCollectController (mine only)
- Docs: analysis + spec + plan (new) + SP1 decomposition/handover/spec index-wording corrections
- **ENT #640:** `AGENTS.md` (speed-table row) + `docs/agent-rules/engineering-gotchas/backend-spring-db.md` (new gotcha)

## Pitfalls & Workarounds
1. **MockMvc returns 401 for a whitelisted endpoint**: `JwtAuthenticationFilter.shouldNotFilter` reads `request.getServletPath()`, which is empty under `@AutoConfigureMockMvc` → whitelist miss → filter rejects with 401.
   - **Solution**: switch the IT to `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate` (real embedded servlet → `getServletPath()` correct → whitelist applies).
   - **Prevention**: test whitelist/security-chain behavior with a real servlet container, not MockMvc.
2. **All keyed-guard rejections came back 500, not 403/429/400**: `GlobalExceptionHandler`'s catch-all matched `ResponseStatusException` before Spring's `ResponseStatusExceptionResolver` → 500.
   - **Solution**: add `@ExceptionHandler(ResponseStatusException.class)` honoring `ex.getStatusCode()` (also fixes the existing authenticated collect's 401). Caught by the IT, not in prod.
3. **Shared `aura_boot` (integration-test DB) had no `flyway_schema_history` and was missing `ab_behavior_event`** (314 ab_ tables but behind the 2026-06-20 migration; volatile per concurrent sessions).
   - **Solution**: IT self-provisions both `mt_behavior_site_key` + `ab_behavior_event` via `CREATE TABLE IF NOT EXISTS` (mirrors SP1's `SiteKeyRegistryIT`), scoped tenant range + `abk_it_` prefix, never drops. Additive/idempotent — safe for concurrent sessions.
4. **Wrong index name in my own analysis doc/spec** (`idx_..._unique`): assumed the name before reading `generateIndexName`. Real name is `uk_mt_behavior_site_key_site_key` (prefix `uk`). Caught in Task 6, corrected before merge.
5. **`createFieldIndex` needs the model registered** (`ab_meta_model` row) — absent in the bare IT DB → "Model not found". So the IT validates the index *artifact* via the identical production DDL; the live import→initializer→createFieldIndex path is unit-tested for wiring and deferred to SP4's assembled golden. Documented honestly in the IT javadoc + PR.

## Lessons Learned
- For a cross-tenant-resolved key, the DB constraint must be **global single-column unique**, not the multi-tenant `(tenant_id, col)` shape the platform's `MultiTenantIndexManager` forces.
- Dynamic `mt_` tables can't get a config/Flyway index; the platform's `createFieldIndex` on an import-lifecycle hook is the idiomatic, §4.1/§8-compatible way (now codified — ENT #640).
- Test whitelist/security behavior over a real servlet container (RANDOM_PORT), not MockMvc.

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
本会话整体顺畅(brainstorm→spec→plan→TDD,evidence-driven,无功能返工),弯路都在 build-IT 阶段被当场抓住、merge 前修掉:
1. **MockMvc 401**(whitelist 测不出)— 代价:1 轮 IT 迭代 — 本可如何更早避免:whitelist/安全链测试默认用 RANDOM_PORT+TestRestTemplate,不用 MockMvc — 根因:`[B 输入]`(MockMvc servletPath quirk 未先知,虽属已知 Spring 行为)。
2. **ResponseStatusException→500** — 代价:1 轮(IT 抓到)— 这是 IT 的正向战果(真栈抓出 latent 平台 bug),非弯路 — 根因:无(验证纪律奏效)。
3. **共享 aura_boot 缺 ab_behavior_event + 无 flyway history** — 代价:数次探测 — 本可如何更早避免:已知 env(memory `feedback-shared-aura-boot-it-db-reset-flakiness`),IT 自 provision 兜底 — 根因:`[B 输入]`(共享 DB 易变态)。
4. **自写 doc 索引名 idx vs uk** — 代价:小(merge 前改)— 本可如何更早避免:写 DDL 名前先读 `generateIndexName` 别假设 — 根因:`[D 验证]`(假设未先证;§15)。

### 为什么会发生(根因归类小结)
主要 **B 输入信息不足**(MockMvc/共享 DB env 的隐性状态)+ 一处 **D 验证纪律**(索引名假设)。无 A 门禁 / C 提示词类失败。TDD + 真栈 IT 把所有问题挡在 merge 前,没有 ship 出去的缺陷。

### 应该有哪些改进
- **B**:涉及 whitelist/安全链的后端 IT,默认 `@SpringBootTest(RANDOM_PORT)+TestRestTemplate`,不用 `@AutoConfigureMockMvc`(MockMvc 的 servletPath 让 `shouldNotFilter` 假 401)。
- **D**:dispatch/自己写「平台会生成的标识符」(索引名/约束名/表名)前先读生成规则(`generateIndexName` 等),不假设(§15 已有,本会话再触一次)。

### 已固化 / 待固化(更新文档)
- [x] 已固化 ENT canonical(**#640 MERGED**):`engineering-gotchas/backend-spring-db.md` §「给动态 mt_模型表加 DB 索引」+ `AGENTS.md` 速查表行(动态 mt_ 索引 → createFieldIndex 双触发)。
- [x] 已写 OSS 深度参考(**#995 MERGED**):`docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md`(用户场景叙事 + 选型 + 红线相容性 + 样板)。
- [ ] 待固化(owner 决策,单次发生暂留本 handover):**whitelist/安全链后端 IT 用 RANDOM_PORT+TestRestTemplate 而非 MockMvc**(MockMvc `getServletPath()` 空 → `shouldNotFilter` 假 401)。若复发 → 升 `engineering-gotchas/test-infra.md` 或 `e2e-playwright.md` + AGENTS 速查表一行。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **本任务 worktree**:全部 **MERGED_AND_DELETED**(`auraboot-sitekey-sp2` / `auraboot-ent-mt-index` 已 remove + 分支删)。本 handover 临时 worktree:`/Users/ghj/work/auraboot-handover-sp2`(分支 `docs/handover-site-key-sp2`,off origin/main,自身 PR 后删)。
- **本会话关键 commit(已落 origin/main)**:OSS squash `4a38ae287`(#995)· ENT squash `cf6f08ee8`(#640)。
- **PR**:**OSS #995 · MERGED · base main**(origin/main 含 `4a38ae287`,已核对)· **ENT #640 · MERGED · base main**(origin/main 含 `cf6f08ee8`,已核对)。
- **未提交改动(本任务)**:无。
- ⚠️ 旁注(并发,**勿动**):canonical OSS checkout `/Users/ghj/work/auraboot/auraboot` 当前停在 `codex/bom-quote-ui-completion`(远端已 gone,仅 `?? data/` untracked)— 别的会话遗留,非本任务。

### Runtime / 端口(host-first slot 模型,零 docker)
- **本任务未分配 dev.sh runtime**。ITs 跑共享 host `aura_boot:5432`(integration-test profile,user `ghj` 空密码)+ Redis `6379`;`@SpringBootTest(RANDOM_PORT)` 自起临时端口。
- **共享 DB 残留(无害,additive)**:IT 在 `aura_boot` 上 `CREATE TABLE IF NOT EXISTS` 了 `ab_behavior_event`(真 migration 的表)+ 建了 `uk_mt_behavior_site_key_site_key`(正确生产索引);scoped 行(tenant 990_2xx / `abk_it_%`)已 `@AfterAll` 清。**无需 cleanup**。
- **当前无本会话进程在跑**。

### Database / Seed 状态
- 无残留隔离栈。共享 `aura_boot` 状态由并发会话管理;下个 SP 自起隔离 runtime + reset 更稳(共享库无 flyway history、易变)。

## Next Steps
1. **SP3 — SDK 公开模式(fresh 会话)**:`@auraboot/track` 接 `siteKey` + 生成持久 `anon_id`(cookie),未登录发 `siteKey`+`anonId`,可独立部署到已发布应用。依赖 SP2 keyed 端点契约(已 ship)。
2. **SP4 — 端到端 golden(fresh 会话)**:真浏览器模拟已发布应用页(带 siteKey)→ 匿名采集 → 按 key 入对应 tenant → 看板 UV 计匿名;多 key/多 tenant 隔离 + key 禁用即停采;**含真·插件 import→SiteKeyIndexInitializer→createFieldIndex 端到端**(SP2 IT 未覆盖此活路径)。
3. 遥测平台其它独立线:Kafka 解耦 ingestion / §5.4 UI 元素身份治理 / OTel(SoT §12)。

## Context for Next Session
- **SP2 契约(SP3/SP4 消费)**:`POST /api/collect/keyed`,header `X-Site-Key: abk_…`,body `{events:[...]}`,响应 `{accepted:n}`;未知/禁用/缺 key → 403,超限 → 429,超量 → 400。`SiteKeyRegistry.resolveTenant(siteKey)→Optional<Long>`(跨租户、缓存)。
- **起点文档(绝对路径)**:
  - 子系统分解:`/Users/ghj/work/auraboot/auraboot/docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md`
  - SP2 spec:`.../docs/superpowers/specs/2026-06-21-site-key-anonymous-ingestion-sp2-design.md`(§9 非目标列 SP3/SP4)
  - 索引分析:`.../docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md`
- **SP4 注意**:`createFieldIndex` 需 `ab_meta_model` 有 `behavior_site_key` 行,故必须真 import `plugins/core-site-key`(config-only)再起后端,才会触发 `SiteKeyIndexInitializer` 建真索引;SP2 in-process IT 未走此路径(bare DB 无模型注册)。
- 其它活跃任务现状不在本 handover 范围,见 `MEMORY.md`「统一遥测与分析平台」条目。
