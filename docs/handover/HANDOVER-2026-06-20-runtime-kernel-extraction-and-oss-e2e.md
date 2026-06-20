---
type: handover
status: shipped
created: 2026-06-20
---

# Session Handover - 2026-06-20

## Session Summary
Executed commerce **task C — frontend runtime-kernel extraction** end-to-end:
extracted the product-agnostic multi-runtime frontend kernel out of
`auraboot-app` into a new workspace package `@auraboot/runtime-kernel` (Phase 1
seam + Phase 2 BlockRenderer dispatcher via injection + Phase 4 federation
profile-gating), ran a full OSS E2E regression on an isolated host-first stack
(no kernel regression), then root-caused the E2E failures and fixed the one
cluster that was test-drift (create-button locator colliding with a new
quick-filter preset). Three PRs open, all verified, awaiting owner review/merge.

## Tasks Completed
- [x] **Phase 1** — kernel seam (`runtime/` profiles + `profiles/` ProfileRegistry/RenderProfile/ProfileContext + `rendering/BlockErrorBoundary`), zero-admin-dep → PR #895
- [x] **Phase 2** — BlockRenderer dispatcher → kernel via injection (`rendering/blockResolver.ts`; admin wires `setBlockResolver(BlockRegistry)` + lazy `setCustomBlockComponent(ComponentLoader)` in `initBlockRegistry()`) → PR #895
- [x] **Phase 4** — federation profile-gating → kernel (`isRuntimeProfileEnabled` / `isRuntimeProfileAllowed`; `FederationManager` predicates delegate) → PR #895
- [x] commerce analysis doc §8 marked kernel extraction landed → commerce PR #124
- [x] **Full OSS E2E regression** on isolated host-first stack → 970 passed; **no kernel regression** (zero kernel-signature failures; all block-type specs render through the moved dispatcher)
- [x] **Root-caused the 139 E2E failures** → 3 clusters (see below); fixed cluster 1
- [x] **Cluster 1 fix** — create-button locators stop matching the `今日新建` quick-filter preset (39 occ / 30 files), verified on isolated stack → PR #902

## Tasks In Progress / Remaining
- [ ] **Owner: review/merge PR #895, #902, commerce #124** (all verified, OPEN). decision-defaults = no auto-merge to main without explicit authorization.
- [ ] **Phase 3 — design tokens → kernel**: kernel-clean but **blocked** (UX-DS T3 smart-sweep is an active contention point on `tokens.ts`; move after it settles to avoid path-rename conflict). See `packages/runtime-kernel/README.md`.
- [ ] **Cluster 2 (E2E permission 403s) → routes to the default-deny authz rollout** (NOT this session's to fix; the owner's PR #820 workstream). `model.meta_models.read` 403 ×178 etc. = role matrix not yet fully granted under `AURA_AUTHZ_UNANNOTATED_MODE`. Optional follow-up offered: compile the exact role→permission gap list for `docs/backlog/2026-06-18-fail-open-controller-triage.md`.
- [ ] **Cluster 3 (E2E)** — scattered validation/known-OSS specs (enterprise-plugin-dependent, by-design OSS exclusions per `oss-scope.json`); low ROI.
- [ ] **commerce task D** — tenant-isolation guard (every public/repo query carries `tenant_id`); independent, fresh slice.

## Key Decisions
| Decision | Chosen Approach | Rationale |
|----------|----------------|-----------|
| Kernel home | new `@auraboot/runtime-kernel` (not fold into `@auraboot/core`) | `@auraboot/core` imports `app/plugins/*/routes` (not zero-admin-dep); a new package keeps the zero-admin-dep invariant provable |
| Phase 2 dispatcher decouple | inject block resolver + custom-loader via kernel setters wired in `initBlockRegistry()` | keeps the kernel free of admin `BlockRegistry`/`ComponentLoader`; wiring in initBlockRegistry (not just boot) means boot + tests both wire the dispatcher |
| Custom-block loader | `React.lazy(ComponentLoader)` injected | keeps the heavy ComponentLoader out of the entry chunk |
| Phase 3 tokens | deferred | `tokens.ts` is an active UX-DS T3 contention point; moving now = path-rename conflict |
| E2E cluster-1 fix | append `:not(:has-text("今日"))` to `button:has-text("新建")` | surgical — excludes only the `今日新建` preset, never narrows a real match (cannot regress a passing spec). Product is correct; the substring text locators were the bug |
| E2E cluster-2 | report, don't fix | it's the owner's in-progress default-deny authz rollout; granting perms would interfere with that deliberate redesign |

## Files Changed
PR #895 (`feat/web-admin-kernel-package`): new `web-admin/packages/runtime-kernel/*` (runtime / profiles / rendering / blockResolver / index / README) + 13 admin import-rewrite sites (root.tsx, FederationManager.ts, BlockRegistry.ts injection wiring, ListPageContent/DetailPageContent/FormPageContent/DslFormRenderer/DynamicPageRenderer + profiles/admin|report + SchemaRenderer + block renderers) + kernel gating tests + `BlockRegistry.bootstrap.test.ts` count fix (card-grid).
PR #902 (`fix/oss-e2e-create-locator-quickfilter-collision`): 30 E2E spec/helper files, `has-text("新建")` → `has-text("新建"):not(:has-text("今日"))`.
commerce #124: `docs/system-reference/2026-06-19-storefront-dsl-ssr-kernel-static-analysis.zh-CN.md` §8.

## Pitfalls & Workarounds
1. **`oss-reset-and-init.sh` + `oss-test.sh` are NOT slot-safe** — they do global `pkill -f "...MetaApplication"|"pnpm dev"|"vite"...`, which would kill ALL concurrent sessions' backends/dev-servers on a shared machine. **Workaround for isolated E2E**: in a throwaway worktree copy, neutralize the global pkills + point backend-start at a prebuilt bootJar (`java -jar`), then run with isolated env + `FORCE_HOST=1` AFTER auditing `reset-db.sh` honors `POSTGRES_DB` (it does — hardened after the 2026-06-11 incident; line ~21). Revert the script patch before commit.
2. **Wrong plugin profile on first bringup** — used `PLUGIN_IMPORT_PROFILE=e2e`; the showcase-extended seed needs `crm-starter` which only the **demo** profile imports. Canonical OSS E2E env = **demo profile + `IMPORT_TEST_FIXTURES=true`**.
3. **Extended-seed "failure" was a false alarm** — `seed-showcase-extended.spec.ts` queries `crm_account_common` (enterprise model name); OSS uses `crm_account` (60 rows seeded fine). Seed is good; the verification spec has an OSS/enterprise naming mismatch.

## Lessons Learned
- A frontend-only refactor cannot cause backend 500s/403s — when a regression run is dominated by backend errors, the diff scope (`git diff --name-only origin/main...HEAD`) is the fastest disproof.
- `.first()` on a comma-OR Playwright locator resolves by **DOM order**, not selector order — a testid-primary locator with a text fallback still picks the wrong element if the text matches something earlier.

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **首版失败直方图把 `5000ms` 当 HTTP `500`**(grep 子串 `500`)→ 一度误判"69× 500 后端崩"为主因 — 代价:~1 轮分析(owner 追问根因时纠正)— 本可更早避免:错误类型直方图应解析 error-context 的 `# Error details` 段,不做裸子串匹配 — 根因:`[D 验证]`
2. **首次 E2E bringup 用错 plugin profile(e2e 而非 demo)** → 扩展 showcase seed 失败 — 代价:~1 个 bringup 周期 — 本可更早避免:先确认 canonical OSS E2E 的 profile(demo + IMPORT_TEST_FIXTURES)— 根因:`[B 输入]`
3. **首轮回归只把 139 failures 归类为"env",未深挖根因**,直到 owner 追问才定位到 locator 撞 `今日新建` 预设 — 代价:owner 多问一轮 — 本可更早避免:回归一旦有非平凡失败数,首轮就做错误类型直方图 + 取证根因,而非停在"env" — 根因:`[D 验证]`
> 内核抽包本体(Phase 1/2/4)顺畅,无重大弯路;弯路集中在 E2E 回归的环境编排与失败归因。

### 为什么会发生(根因归类小结)
主要 `[D 验证纪律]`(失败归因取证不足 + 子串误匹配)+ `[B 输入信息]`(OSS E2E 隔离运行的 profile/slot-safety 不在已知文档里)。

### 应该有哪些改进
- 失败直方图改为解析 error-context 段,不裸 grep 数字子串。
- 把"隔离运行全量 OSS E2E"的 recipe(neutralize 全局 pkill + 复用 bootJar + demo profile + FORCE_HOST + 审计 reset-db 认 POSTGRES_DB)固化,避免下次重新摸索 + 误杀并发会话。

### 已固化 / 待固化(更新文档)
- [x] 本 handover §Pitfalls 1-3 记录了隔离 OSS E2E recipe + profile + 误判
- [ ] 待固化 `auraboot-enterprise/docs/agent-rules/oss-e2e-and-playwright.md`:新增「在多 worktree / 共享机器上隔离跑全量 OSS E2E」一节(草稿见本 handover Pitfall 1 + Lessons);留 owner 决策是否上升(需 enterprise 仓 worktree,本会话未开)

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **PR #895** · OPEN · head `e8ce69adc` · base main — kernel extraction (Phase 1+2+4). worktree `/Users/ghj/work/auraboot-web-admin-kernel` [`feat/web-admin-kernel-package`], clean.
- **PR #902** · OPEN · head `a4a6a78c4` · base main — E2E locator fix. worktree `/Users/ghj/work/auraboot-oss-e2e-locator-fix` [`fix/oss-e2e-create-locator-quickfilter-collision`], clean.
- **commerce PR #124** · OPEN · base main — analysis doc §8.
- All three verified; none merged (awaiting owner). To verify a PR head: `gh pr view <n> --json headRefOid`.

### Runtime / 端口
- **None live** — both isolated runtimes used this session (`kernel-e2e-oss-54`, `kernel-e2e-locatorfix-55`) were fully destroyed (DBs dropped, allocations removed). `./dev.sh runtime list` shows 0 of mine.
- To re-verify E2E: allocate a fresh slot, reuse the prebuilt bootJar `/Users/ghj/work/auraboot-web-admin-kernel/platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar` (base 135011628, schema == current origin/main — no new migrations), bring up via the Pitfall-1 isolated recipe (demo profile).

### Database / Seed
- No live DB. Fresh bringup needs reset + bootstrap + demo-profile import + showcase seed (the isolated recipe does all of it).

## Next Steps
1. Owner: review/merge **#895** (kernel), then **#902** (locator fix), then commerce **#124**.
2. After UX-DS T3 settles: **Phase 3** tokens → kernel.
3. Route **cluster-2 403s** into the default-deny authz backlog (optional: I can compile the exact grant gap list).
4. **commerce task D** (tenant isolation) — fresh slice, new session.

## Context for Next Session
- Kernel package + staged plan: `web-admin/packages/runtime-kernel/README.md`.
- commerce driver/analysis: `commerce/docs/system-reference/2026-06-19-storefront-dsl-ssr-kernel-static-analysis.zh-CN.md` §8.
- Default-deny authz (cluster 2): `auraboot/docs/backlog/2026-06-18-fail-open-controller-triage.md` + the default-deny handover.
- Isolated full OSS E2E recipe: this doc, Pitfalls §1.
