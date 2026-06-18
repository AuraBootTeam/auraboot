# Session Handover - 2026-06-18

## Session Summary

Closed out the **T4–T6 renderer interaction upgrades** that the original UX-design-system backlog (`docs/backlog/2026-06-17-ux-design-system-tokens-activation.md`) required but earlier work had under-delivered: prior PRs token-ified the renderers + golden'd that they *render*, but the backlog's P1 "渲染器视觉 + 交互升级" (§3/§4/§5) interaction features were either not built or only grep-confirmed. Driven by the owner asking 3× "is the original task actually done?", I audited honestly, built the §3 gaps, and ran real-stack browser goldens that caught a status-dot bypass **four times** before it truly passed.

## Tasks Completed

- [x] **§3-A status/tag → 色点+文字** (standard §3/§1.3): `resolveStatusTone` + `StatusDot` (pure, 13 tests incl. hex→tone), de-pilled the `status`/`tag` cell renderers, and — the hard part — routed **all live dict renderers** through StatusDot (see pitfalls). Real-stack golden: 12 dots / 0 pills / 5 distinct semantic colors.
- [x] **§3-B 「已保存到当前视图」hint**: `useTransientFlag` (2 tests) + quiet token toast wired into `ensureViewAndUpdateConfig`; i18n `common.view_saved` (zh/en yaml + contract test).
- [x] **§3-C dark batch bar**: added a reusable **inverse** token set (always-dark surfaces) to `tokens.ts`; re-skinned `BulkActionToolbar`. Static + real-stack golden PASS.
- [x] **§4 first-error scroll/focus**: TDD'd `scrollToFormField` util (4 tests) wired into `FormPageContent.notifyValidationFailure`. Golden PASS (first invalid field focused + inline error).
- [x] **Verified §3-D / §4 / §5 already-conformant**: conditional format (`getRowStyle`→evaluator), reference picker remote search (`RelationField`), sub-table totals (`SubTableViewer`), detail toolbar+tabs, T10 import creates records (no 403) — all golden-PASS.
- [x] **ImportModal i18n + token sweep** (collected orphaned T10 WIP from the worktree so it wasn't lost).

## Tasks In Progress

None for this task line — the UX backlog interaction surface is golden-complete. See **Next Steps** for non-UX follow-ups surfaced by the goldens.

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives |
|----------|----------------|-----------|--------------|
| Status presentation | 色点 + 文字 (semantic dot) | Standard §3/§1.3 ("不用大块底色 pill") | Keep pills (rejected — spec) |
| Always-dark batch bar | New `inverse` token set (theme-independent) | Reusable for dark surfaces (bars/tooltips); not a light semantic token | Hardcode dark grays (rejected — not tokenized) |
| dict color → tone | name lookup **then hex→tone by hue (HSL)** | dict `extension.color` stores **hex** (`#10b981`), not names | name-only (was the bug — all gray) |
| first-error scroll | extract `scrollToFormField(code)` util + `data-testid="form-field-<code>"` | testable in jsdom; field wrappers already carry the testid | inline in the huge component (rejected — untestable) |

## Files Changed (all MERGED to main)

### Frontend — renderers
- `app/framework/meta/runtime/renderers/statusTone.tsx` (new) — `resolveStatusTone` (name + hex→tone) + `StatusDot`
- `app/framework/meta/runtime/renderers/CellRendererRegistry.tsx` — status/tag → StatusDot + full token sweep
- `app/framework/meta/rendering/pages/ListPageContent.tsx` — **live list** dict-pill → StatusDot; saved-to-view hint
- `app/framework/meta/rendering/pages/DetailPageContent.tsx` — sub-table dict-pill → StatusDot
- `app/routes/_shared/dynamic-route-utils.tsx` — **shared `DynamicField`** (live detail field value) dict-pill → StatusDot
- `app/framework/meta/rendering/blocks/{TableBlockRenderer,RecordListView}.tsx` — DSL block dict-pill → StatusDot
- `app/framework/meta/rendering/pages/FormPageContent.tsx` + `pages/form/scrollToFormField.ts` (new) — §4 first-error scroll
- `app/framework/smart/components/bulk/BulkActionToolbar.tsx` — inverse-token dark bar
- `app/framework/smart/components/data-tools/ImportModal.tsx` — i18n + token sweep
- `app/hooks/useTransientFlag.ts` (new) — saved-hint flag

### Config / tokens / tests
- `app/framework/meta/runtime/theme/tokens.ts` + `app/styles/tokens.theme.css` — inverse tokens
- `scripts/check-design-tokens.mjs` — palette baseline **1271→1216**
- `platform/src/main/resources/i18n.{zh-CN,en-US}.yaml` — `common.view_saved`
- New tests: `statusTone.test.ts`, `useTransientFlag.test.tsx`, `scrollToFormField.test.ts`
- Docs: `app/framework/meta/runtime/theme/{T4-T6-COVERAGE-MATRIX,RENDERER-GOLDEN-2026-06-17}.md`

## Pitfalls & Workarounds

1. **§3-A status dots: the dot conversion didn't reach the live page — 4 golden iterations.**
   - **Root Cause**: the platform has **several parallel dict/status renderers**, each with its own inline pill. Patching `CellRendererRegistry` (round 1), then `TableBlockRenderer`/`RecordListView` (round 2) had **zero visible effect** because the live admin-profile pages render through `ListPageContent.tsx` (own inline pill) + the shared `DynamicField` in `routes/_shared/dynamic-route-utils.tsx` + `DetailPageContent` sub-table — and `RecordListView` is on **no live route**. Round 3: dots rendered but **all gray** because dict colors are stored as **hex**, and `resolveStatusTone` only mapped names.
   - **Solution**: route ALL live renderers through StatusDot + add hex→tone-by-hue.
   - **Prevention**: codified → `[[feedback-dict-status-render-multiple-live-renderers-golden-only]]`. When changing dict/status rendering, grep ALL renderers + verify with real-browser golden reading the DOM (dot vs pill class, computed bg color), never grep/unit-only.

2. **Owner's i18n yaml edit accidentally hit canonical main.** Edited `platform/.../i18n.*.yaml` in the canonical checkout instead of the worktree → reverted with `git checkout`, re-applied in the worktree. (Main-guard slip; caught immediately.)

3. **Color-golden subagent's `pnpm dev:full` cleanup left Vite/BFF supervisor children alive** (ports 5193/3523 still listening post-run). Cleaned up at handover time (verified cwd = my worktree before killing; left enterprise sessions' java on 6551-6555 untouched). Known `concurrently`/`dev:full` supervisor gotcha.

## Lessons Learned

- **grep-"the code exists" ≠ "works on the live page"** — the dominant failure this session. The owner's insistence on browser golden caught the §3-A bypass 4×; each catch was a renderer the registry change never touched.
- dict colors are **hex**, not names — any color→semantic mapping must handle hex.
- The live admin profile's list = `ListPageContent`, detail = `DetailPageContent` + shared `DynamicField` (`app/framework/meta/profiles/admin/index.ts`).

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车
1. **§3-A 状态色点翻车 4 次** — 代价:4 轮 golden + 3 次 fix 提交 — 本可如何更早避免:第一次改 dict 渲染前就 `grep -rn "dictCode\|colorMap\|extension?.color\|rounded-pill"` 全树清点所有并行渲染器、确认 admin profile 真正 mount 哪个,而不是改完 CellRendererRegistry 就以为生效 — 根因:`[D 验证纪律]`(grep-exists 替代真栈 golden)+ `[B 输入]`(不知道有多套并行渲染器)
2. **早期把 §4/§5「conformant」当完成依据是 grep 到代码存在** — 代价:被 owner 追问才发现 §4 首错滚动其实只在 FormDialog、不在 page form — 本可避免:`grep 到 ValidationSummary.scrollIntoView` 不等于 page form 用了它;应真栈验 — 根因:`[D 验证纪律]`
3. **i18n yaml 误改 canonical main** — 代价:1 次 revert — 根因:`[D 验证纪律]`(写文件前没跑 main-guard)

### 为什么会发生(根因归类小结)
主要是 **D 验证纪律**:反复用「代码存在 / grep 命中 / 单测绿」替代「真浏览器 golden 读 DOM」。次要 **B 输入**:不知道平台有多套并行 dict 渲染器、dict 颜色存 hex。**门禁(A)其实尽责**——真栈 golden 每次都抓到了,问题是我一开始没把它当 §3-A 的完成判定。

### 应该有哪些改进
- 改 dict/status/cell 渲染类任务,完成判定**必须**含真栈 golden 读 DOM(dot vs pill class + computed color),并先 grep 全部并行渲染器 —— 已写进 memory(下方)。
- dispatch 渲染 golden subagent 的 prompt 要求**报 DOM 计数**(dotCount/filledPillCount/distinctColors),不接受「看起来对了」。

### 已固化 / 待固化(更新文档)
- [x] 已写入 memory `feedback-dict-status-render-multiple-live-renderers-golden-only.md`:多套并行 dict 渲染器 + hex 颜色 + 真栈 golden 唯一能抓
- [x] 已写入 memory `feedback-stop-hook-loop-call-done-when-substantively-complete.md`(本会话早段的熬夜元振荡教训)
- [ ] 待 owner 决策:是否把「改 dict/status 渲染必走真栈 golden + grep 全部并行渲染器」上升为 AGENTS.md §2.2 红线速查表一行(目前在 memory;有 incident 翻车记录,符合上升标准)

## 运行态快照 (Operational State)

### 分支 / Worktree / PR
- **当前分支**:`docs/handover-ux-t4-t6-golden`(base `main` @ `0de947e2e`,仅含本 handover doc;功能分支均已 merged+deleted)
- **Worktree**:`/Users/ghj/work/auraboot/auraboot/.worktrees/ux-design-tokens`;其它 worktree 属并发会话(automation-gap / bom-followups / deep-review / gaps / s5s6-golden / sqlpath-21 — **勿动**)
- **本会话 merged PR**(oid 已核对在 origin/main):
  - `#796` MERGED `ca2c9dc5a` — T4-T6 §3 interaction upgrades
  - `#798` MERGED `65236758c` — ImportModal i18n
  - `#812` MERGED `a201fb973` — live status dots + §4 scroll + hex fix
  - `#815` MERGED `bc6bc17d6` — color confirmation golden (→ `ac8984588` on main)
- **未提交改动**:仅本 handover doc(尚未提交)

### Runtime / 端口(host-first 零 docker)
- **无持久 runtime** — 所有 golden 用临时隔离栈(prebuilt boot jar + 隔离 DB + Redis DB + `pnpm dev:full`,recipe 在 `RENDERER-GOLDEN-2026-06-17.md`),跑完即销毁。
- **本会话遗留已清**:color-golden 的 Vite 5193 / BFF 3523(我的 orphan,已 kill)。
- **勿动**:`:6551-6555` java 后端 = `auraboot-enterprise/platform` 并发会话(已核 cwd)。
- **接手起栈**(若要再 golden):`RENDERER-GOLDEN-2026-06-17.md` §recipe — prebuilt jar `platform/build/libs/AuraBoot-*-boot.jar`(⚠️ OTel exclude flags,见下)+ 隔离 DB + 自由端口(避开 6543/6551-6560)+ `pnpm dev:full`。

### Database / Seed
- 无遗留隔离 DB(golden 各自 drop)。共享 `aura_boot` 全程未碰。

## Next Steps

非本 UX 任务、但 golden 期间发现的**真 bug**(owner 待决定是否修):
1. **OTel boot-jar 打包错位** — prebuilt `AuraBoot-*-boot.jar` 起不来(`opentelemetry-exporter-otlp 1.62` vs `sdk-common 1.49`,build.gradle 写 1.63 → `NoClassDefFoundError ...StandardComponentId$ExporterType`)。现靠启动期 `--spring.autoconfigure.exclude=...OpenTelemetry.../ObservationAutoConfiguration` + `MANAGEMENT_TRACING_ENABLED=false` 绕过。真修=重建 jar 时对齐 OTel 依赖。**影响所有跑 boot jar 的人**。
2. **crm-starter 权限缺口** — 缺 `model.crm_account.export`(连带 T9 导出选中);插件 `roles.json` 补。(T10 import 走 `meta.model.update` 平台级权限,无 403。)
3. (可选)将 pitfall #1 的教训上升为 AGENTS.md §2.2 红线一行(见上「待固化」)。

## Context for Next Session

- 原始 backlog:`docs/backlog/2026-06-17-ux-design-system-tokens-activation.md`
- 审计/进度真源:`web-admin/app/framework/meta/runtime/theme/T4-T6-COVERAGE-MATRIX.md` + `TOKENS-BURNDOWN.md`
- golden 证据 + host-first recipe + OTel workaround:`web-admin/app/framework/meta/runtime/theme/RENDERER-GOLDEN-2026-06-17.md`
- 渲染器关键教训:`[[feedback-dict-status-render-multiple-live-renderers-golden-only]]`
- 完整 UX 项目现状(其它 active 任务)在 memory,**勿复制进此 handover**。
