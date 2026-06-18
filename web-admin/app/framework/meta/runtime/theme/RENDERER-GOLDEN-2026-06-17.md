# T4–T6 renderer golden — real-browser verification (2026-06-17)

> Golden evidence that the design-system-tokenized list/form/detail page renderers
> (PRs #733/#735) render correctly in a real browser against a seeded backend.
> Spec: `auraboot-enterprise/docs/standards/core/ux-design-system.md` §3/§4/§5.
> Screenshots are gitignored (`*.png`); this report records the inspected verdict.

## Host-first stack (isolated, zero disruption to concurrent sessions)

- **Backend**: the prebuilt `platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`
  run with `java -jar` (no gradle build) on **port 6543**, pointed at an
  **isolated DB `aura_ux_golden`** (schema auto-applied, 307 tables) + Redis DB 5.
  The shared `aura_boot` DB and other sessions' processes were never touched (§20).
- **Frontend**: `pnpm dev:full` (Vite 5174 / BFF 3501), both with
  `SPRING_BOOT_URL` + `PROXY_TARGET` → 6543 (the Vite SSR loader needs it too).
- **Seed**: `POST /api/bootstrap/setup` + import the `demo` profile (11 plugins,
  reference-integrity clean), then seed records via the **real command pipeline**
  (`POST /api/meta/commands/execute/{code}`, not raw SQL): 4 `crm_account` +
  1 `showcase_all_fields` (35-field demo).

### ⚠️ Boot-jar packaging bug found (flag for whoever rebuilds the jar)

The prebuilt boot jar would not start: a bundled OpenTelemetry version skew —
`opentelemetry-exporter-otlp 1.62.0` against `opentelemetry-sdk-common 1.49.0`
(build.gradle declares 1.63.0) → `NoClassDefFoundError ...StandardComponentId$ExporterType`
during tracing-bean wiring. Worked around at launch with
`--spring.autoconfigure.exclude=…OpenTelemetry…/…ObservationAutoConfiguration` +
`MANAGEMENT_TRACING_ENABLED=false`. This is a packaging defect in the jar, not an
env problem — the OTel deps need re-aligning when the boot jar is rebuilt.

## Pages golden'd + inspected verdict

| Layer             | Page                                           | Verdict (design-system tokens render correctly)                                                                                                                                                                                                                                                                                                     |
| ----------------- | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **T4 list**       | `crm_account` / `showcase_all_fields`          | accent-blue 新建; **semantic status/rating badges** (活跃; A-重点/B-重要/C-一般); compact high-density rows; Airtable view toolbar (view switch / 排序 / 字段 / 筛选 / 我的记录·今日新建·本周修改 / 添加筛选); 全部/活跃/停用 tabs; localized headers (no raw field-code leak)                                                                      |
| **T5 form**       | `crm_account` / `showcase_all_fields` (create) | card panel + section headers; **2-column field grid**; **required `*` (red)**; typed inputs — select, textarea, number stepper, ¥ currency, slider, star-rating, color-picker swatch, rich-text toolbar, **drag-drop upload zone (T7)**, AI-assist; accent 保存 / secondary 取消                                                                    |
| **T5 validation** | empty-required submit                          | **field-level message "请填写名称"** in a red status-token banner (not a generic toast) — mixed-validation presentation per §4 met                                                                                                                                                                                                                  |
| **T6 detail**     | `crm_account` / `showcase_all_fields`          | breadcrumb + title + state-transition toolbar (分享/Report/打印/编辑/激活/**归档 red destructive**); **tab nav** (概览/联系人/评论/活动记录/变更历史); 2-col read-only description list; **accent hyperlink** (website); status/rating badges; toggle switch (ON); progress bar (68%); star rating (★★★★☆) — typed value renderers all token-styled |

**Verdict: T4/T5/T6 list/form/detail renderers render correctly with the design
system applied** — no raw-code leakage, no empty stub pages, field-level validation.
(Two screenshots — T4 list, T6 detail — were inspected directly by the main agent;
the rest by the golden subagent.)

## Minor follow-ups (not renderer defects — config/coverage)

- **Batch/multi-select bar** (§3) not exercised: the demo models' default page config
  has no selection column enabled — needs a model/page with selection to verify.
- **Detail sub-tabs' inner content** (timeline §5, comments, activity) render as tab
  headers but only the overview tab's description-list was drill-asserted this pass.
- A first-load Vite `504 Outdated Optimize Dep` + pre-auth 401 race occurred during
  navigation (recovered via networkidle waits); a stricter CI golden should add a
  warm-up navigation before asserting.

These are coverage/config items for a deeper golden pass, not blockers — the
renderers support the features; this seed simply didn't exercise all of them.

## T9 golden — cross-page select-all + export-selected (2026-06-17)

> Follow-up to the "batch/multi-select bar not exercised" item above.

Host-first isolated stack: prebuilt boot jar on **port 6543** → isolated DB
`aura_ux_t9` (schema.sql + 44 migrations applied, 307 tables) + Redis DB 7;
worktree `pnpm dev:full` (Vite 5175 / BFF 3502) with `SPRING_BOOT_URL` +
`PROXY_TARGET` → 6543. Seeded **25 `crm_account`** records via the real command
pipeline (`POST /api/meta/commands/execute/crm:create_account`), with selection
enabled on the imported `crm_account_list` page. Playwright + bundled chromium,
authed via the BFF `/login` form POST (session cookie).

Asserted (all PASS — see `/tmp/t9-0*.png`):

| Step | Assertion |
| ---- | --------- |
| List loads | 20 of 25 rows shown → pagination active |
| Before selection | no `select-all-matching-banner` |
| Header select-all | banner appears: **"20 on this page selected · Select all 25 matching"** |
| Click "Select all 25 matching" | banner → **"All 25 records selected · Clear selection"**; bulk bar count = **25** |
| De-select one row (all-matching) | bulk bar count → **24** (exclusion set), header checkbox indeterminate |
| Export-selected (explicit pick of 2) | export POST carries `conditions:[{field:"pid",operator:"IN",value:[<2 ids>]}]` — exports ONLY the selected records |

**Pre-existing seed gap (NOT a T9 defect):** the export endpoint is
`@RequirePermission("model.{pageKey}.export")`, but `crm-starter` registers only
`create/read/update/delete` perms for `crm_account` — no `export`. So the export
*response* is 403 for **both** export-all and export-selected (identical 403,
proven by direct API call). T9 builds + sends the correct request payload; the
file-generation step is blocked by this plugin permission gap that equally
blocks the existing export feature. Fix = register `model.<model>.export` in the
plugin's permissions (out of T9 scope).

## §3 list-interaction golden (2026-06-18)

> Real-browser verification of the three §3 list-interaction upgrades on this
> branch (`feat/ux-t4-t6-interactions`, HEAD `f41d413d5`): §3-A status as 色点+文字,
> §3-B 「已保存到当前视图」hint, §3-C dark batch bar.

### Stack (host-first, isolated, zero docker)

- **Backend**: prebuilt `platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`
  (canonical-main build, mtime Jun 16 23:40) via `java -jar` on a **free port 6554**
  (6543 was taken by a concurrent session — left untouched), pointed at **isolated
  DB `aura_ux_s3`** (schema.sql + 44 migrations, 307 tables) + **Redis DB 13**,
  `SPRING_PROFILES_ACTIVE=dev`, `JWT_SECRET=…`. Same OTel autoconfigure-exclude
  launch flags + `MANAGEMENT_TRACING_ENABLED=false` workaround as the T4–T6 run
  (the bundled OTel skew still applies to this jar). The renderer changes are
  **frontend-only**, so the canonical-built backend jar is fine unmodified.
- **Frontend (from THIS worktree)**: `pnpm dev:full` — Vite **5186** / BFF **3516**,
  both with `SPRING_BOOT_URL` + `PROXY_TARGET` → 6554. **Verified the served
  frontend is the new code, not stale**: `curl http://localhost:5186/app/.../statusTone.tsx`
  and `.../CellRendererRegistry.tsx` both contain `StatusDot`; the served
  `TableBlockRenderer.tsx` still contains the inline dict-pill (the bypass, see §3-A below).
- **Seed**: `POST /api/bootstrap/setup` (admin@auraboot.com / AuraBoot Dev,
  tenant `325806045060534272`) + `import-directory-sync` of `crm-starter`
  (`success:true`, 6 models / 19 pages / 41 commands / 54 fields). **6 `crm_account`**
  rows seeded via the **real command pipeline** (`POST /api/meta/commands/execute/crm:create_account`).
  `crm:create_account` force-sets `crm_acc_status=active` (autoSetField fixed_value),
  so to get varied dots **2 rows were flipped to `inactive` via the real
  `crm:update_account` command** → final 4 active / 2 inactive
  (dict `crm_account_status`: active=活跃 #10b981, inactive=停用 #9ca3af).
- **Driver**: Playwright 1.60 + its bundled chromium (`chromium.launch()`),
  authed via BFF `/api/auth/login` → `__session` cookie (react-router cookie
  session storage) + auto-select business space. Standalone scripts, removed
  after the run (no source files added).

### Behavior results

| # | Behavior | Verdict | Evidence |
| - | -------- | ------- | -------- |
| §3-A | Status as 色点+文字 (semantic dot + label, not a filled pill) | **FAIL (real product gap)** | `/tmp/s3-1-list.png` |
| §3-B | 「已保存到当前视图」hint on sort/filter change | **UI PASS; text shows raw i18n key (env artifact — stale jar)** | `/tmp/s3-2-hint.png` |
| §3-C | Dark batch bar on row selection | **PASS** | `/tmp/s3-3-batchbar.png` |

#### §3-A — FAIL: status still renders as a filled pill, NOT a dot+text  🔴

On `/p/crm_account` the 状态 (and 评级) columns render as **filled rounded pills**
(`rounded-pill inline-flex px-2 py-1 text-xs font-medium bg-blue-100 text-blue-800`),
NOT the §3-A `StatusDot` (small `rounded-pill h-2 w-2 bg-status-<tone>` dot + `text-text` label).
DOM-inspected: `hasDot=false`, `hasFilledPill=true`, pill `padX=16px`, `rounded=9999px`,
`bg=oklch(0.932 0.032 255.585)` (blue tint). Screenshot shows pill-shaped 活跃/停用 + A-重点/etc.

**Root cause (characterized, not stale code):** the §3-A commit `fb5039fee` only
migrated `CellRendererRegistry.tsx` (`status`/`tag` renderers → `StatusDot`) +
`statusTone.tsx`. But the live DSL list table renders **dict-coded** columns through
a *separate* inline dict-tag path that **short-circuits before `CellRendererRegistry`**:

- `TableBlockRenderer.tsx:288-313` — `if (column.dictCode) { … return <span className={\`rounded-pill inline-flex px-2 py-1 text-xs font-medium ${tagCls}\`}>… }`
- `RecordListView.tsx:248-258` — same inline dict pill `if (dictCode && !column.cellRenderer) { … }`

The page schema column even declares `renderType:"tag"` (`crm_acc_status`,
`dictCode:"crm_account_status"`), but the `dictCode` branch fires first and the
`tag` renderer (now `StatusDot`) is never reached. Since every enum/dict status
column has a `dictCode`, the §3-A dot conversion is bypassed on real list pages.
**Fix scope:** route the dict-coded branch in `TableBlockRenderer` + `RecordListView`
through `StatusDot` (or delete the inline pill and fall through to the `tag`/`status`
cell renderer). Verified the served code is the new code (StatusDot live in
CellRendererRegistry/statusTone) and the bypass is live in the served
TableBlockRenderer — so this is a genuine gap, not a Vite cache / wrong-worktree artifact.

#### §3-B — UI PASS; raw i18n key leak is a stale-jar env artifact  🟡

After clicking a sortable column header (状态 / 客户名称) the
`[data-testid="view-saved-hint"]` element **appears at the top-right of the list card**
(`position:absolute top-2 right-3`, `bg-accent-weak text-accent rounded-pill` with a
green status dot), and a real autoSave fires (`PUT 200 /api/views/{id}`, the
`useAutoSaveView` 2s debounce → `flashViewSavedHint()`); it fades after ~2.2s
(`useTransientFlag(2200)`). So the hint **UI/placement/timing/trigger all work**.

**BUT** the rendered text is the **raw i18n key `common.view_saved`** instead of
「已保存到当前视图」 / "Saved to current view" (screenshot top-right shows `common.view_saved`).
The render is `{t('common.view_saved') || 'Saved to current view'}` — `t()` returns
the key string itself when unregistered (truthy), so the `||` fallback never triggers.
**Root cause = stale backend jar, NOT a source bug:** the `view_saved` key
(`已保存到当前视图` / `Saved to current view`) IS present in **this worktree's**
`platform/src/main/resources/i18n.{zh-CN,en-US}.yaml` (zh line 47), but the prebuilt
boot jar was built from canonical main **before** the key was added — the running
backend's i18n bundle lacks it (`/api/i18n/messages?lang=zh-CN` → `view_saved present: False`,
positive control `common.sort present: True`). A backend rebuilt from this worktree
would resolve the text. (Defensive nit for source: the `|| 'Saved to current view'`
fallback is dead because `t()` returns the key, not empty — but that's pre-existing
`t()` semantics, out of this golden's verify-only scope.)

#### §3-C — PASS: dark floating batch bar with accent count badge  ✅

Selection is DSL opt-in (`tableBlock.table.selection`), which the imported
`crm_account_list` page does not enable by default — so it was enabled for this
golden via the page PUT API (`selection:true` on the table block; test config only,
no source touched), matching the T9 precedent. After enabling, the table renders a
checkbox column (header select-all + 6 row checkboxes). Selecting 2 rows surfaces a
floating bar (`/tmp/s3-3-batchbar.png`):

- `position: fixed`, bottom-center, surface `rgb(26,26,34)` — **DARK** (luminance 27,
  inverse-surface token), not white.
- Accent **count badge** `rgb(37,99,235)` (blue) showing **"2"** + "selected" label.
- All 4 action buttons present & visible: `bulk-edit-btn` (编辑), `bulk-delete-btn` (删除),
  `bulk-export-selected-btn` (Export selected), `bulk-clear-selection-btn` (×).

### Summary

- **§3-C dark batch bar: PASS** — dark inverse surface + accent count badge + 4 actions, real selection.
- **§3-B hint: UI PASS** — element appears top-right after a real `PUT /api/views` autoSave, fades ~2.2s;
  the **raw-key text leak is a stale-jar env artifact** (key exists in this worktree's backend yaml, absent
  from the canonical-built jar's bundle), not a frontend defect.
- **§3-A status dot: FAIL (real gap)** — live DSL list tables render dict-coded status/rating as filled
  pills because `TableBlockRenderer` + `RecordListView` have an inline dict-tag pill that short-circuits
  before the §3-A `StatusDot` in `CellRendererRegistry`. The dot conversion needs to be applied to those
  two block renderers' dict-coded branch.

### §3-A follow-up — bypass FIXED (commit 64fb05579)

The §3-A FAIL above was fixed the same session: `TableBlockRenderer.tsx` and
`RecordListView.tsx` now route their dict-coded status branch through
`StatusDot(resolveStatusTone(extension.color))` (dropping the inline pill +
the hardcoded `tagColorMap`/`DICT_COLOR_MAP`; palette ratchet 1271→1235).
Verified by reading the exact bypass sites the golden traced + tsc clean + 130
block-renderer tests + gate green. Re-golden of the rendered dots deferred (the
fix is a direct substitution at the traced root cause; StatusDot itself is
golden-verified). Will be confirmed on the next real-stack/deploy golden.

## Comprehensive interaction golden (2026-06-18)

> Real-browser re-verification of the T4–T6 + T10 renderer interactions that were
> previously only grep-confirmed, skipped, or deferred (incl. the deferred §3-A
> dot re-golden above). Branch `feat/ux-t4-t6-golden-closeout` HEAD `a2e3b4369`
> (= origin/main with all merged UX work). **Verify-only — no source modified.**
> Principle proven again below: *code-exists ≠ works* — the §3-A "fix" is in the
> branch and tsc-clean, yet the live list page STILL renders filled pills because
> the fix patched two renderers the `/p/<model>` page path doesn't use.

### Stack (host-first, isolated, zero docker)

- **Backend**: prebuilt `platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`
  (canonical-main build, mtime Jun 16 23:40) via `java -jar` on **free port 6556**
  (6543 was held by a concurrent session — left untouched), pointed at **isolated
  DB `aura_ux_cg`** (`database/schema.sql` applied directly — 307 tables, 0 errors)
  + **Redis DB 15**, `SPRING_PROFILES_ACTIVE=dev`, explicit `JWT_SECRET`. Same OTel
  autoconfigure-exclude launch flags + `MANAGEMENT_TRACING_ENABLED=false` as the
  prior runs (the bundled OTel skew still applies to this Jun-16 jar; note the jar
  PREDATES the Jun-18 Flyway baseline #799, so it has NO Flyway — schema is applied
  by `reset-db.sh`/`schema.sql`, not on-boot migration).
- **Frontend (THIS worktree)**: `pnpm dev:full` — Vite **5188** / BFF **3518**, both
  with `SPRING_BOOT_URL` + `PROXY_TARGET` → 6556. **Served code confirmed NEW, not
  stale**: `curl :5188/app/.../TableBlockRenderer.tsx` shows the `StatusDot` route
  (3 refs) AND `curl :5188/app/.../ListPageContent.tsx` shows the `colorMap` pill
  (3 refs, 0 `StatusDot`) — i.e. the served list renderer genuinely lacks the fix.
- **Seed**: `POST /api/bootstrap/setup` (`admin@auraboot.com` / `Test2026x`, tenant
  `325896038403674112`) + `import-directory-sync` of `crm-starter` and `showcase`
  (both `success:true`). Records via the **real command pipeline**
  (`POST /api/meta/commands/execute/{code}`): **6 `crm_account`** (varied rating
  A/B/C; 2 flipped to `inactive` via `crm:update_account` → 4 active / 2 inactive),
  **2 `showcase_all_fields`** (`sc:create_showcase`). Import test (check 7) added 2
  more crm_account → 8 total.
- **Driver**: Playwright 1.60 (`@playwright/test`) + bundled chromium
  (`chromium.launch({headless:true})`); auth via the BFF `/login` form POST →
  `__session` cookie injected into the context. Standalone scripts, removed after.

### Results

| # | Check | Verdict | Evidence |
| - | ----- | ------- | -------- |
| 1 | §3-A status as 色点+文字 on list (post-fix re-verify) | **FAIL (real product gap — fix bypassed)** | `/tmp/cg-1-list.png` |
| 2 | §4 required-empty submit → first-error scroll + field error | **PARTIAL: field-level red error PASS; scroll/focus-to-first-invalid NOT implemented on page form** | `/tmp/cg-2b-validation.png` |
| 3 | §4 reference picker search (remote filter) | **PASS** | `/tmp/cg-3b-ref-search.png` |
| 4 | §4 typed controls render token-styled | **PASS** | `/tmp/cg-4-showcase-form.png` |
| 5 | §5 detail state-transition toolbar + tabs | **PASS (toolbar + tab switch); no dedicated lifecycle state cmd on crm_account (by design)** | `/tmp/cg-5-detail.png` |
| 6 | §5 typed value renderers (badge/hyperlink/etc.) | **PASS (hyperlink + status/rating badge render); status renders as pill not dot — same §3-A family** | `/tmp/cg-5-detail.png` |
| 7 | T10 generic import creates records | **PASS** | `/tmp/cg-7-list-after-import.png` |

#### Check 1 — FAIL: list status/rating STILL render as filled pills, NOT dots 🔴

On `/p/crm_account` the 状态 (活跃/停用) and 评级 (A-重点/B-重要/C-一般) columns render
as **filled rounded pills** — DOM-inspected: `dotCount=0`, `filledPillCount=12`
(6 rows × 2 columns), pill class
`rounded-pill inline-flex px-2 py-1 text-xs font-medium bg-blue-100 text-blue-800`.
Screenshot `/tmp/cg-1-list.png` shows pill-shaped statuses. **This is the SAME
failure the prior §3-A golden flagged — the "FIXED" claim in the section above is
WRONG for the live list page.**

**Root cause (traced, served-code-confirmed):** the §3-A fix `64fb05579` wired
`StatusDot` into `TableBlockRenderer.tsx`, `RecordListView.tsx`, `statusTone.tsx`,
`CellRendererRegistry.tsx`. **But the live admin-profile `/p/<model>` list renderer
is a THIRD renderer the fix never touched** —
`app/framework/meta/profiles/admin/index.ts:135` maps `['list', ListPageContent]`,
and `app/framework/meta/rendering/pages/ListPageContent.tsx:1882-1914` has its OWN
inline dict-coded pill branch:

```
1883  if (column.dictCode) {
1889    const tagColor = item.extension?.color || 'blue';
1890    const colorMap = { gray:'bg-gray-100 text-gray-800', ..., blue:'bg-blue-100 text-blue-800', ... };
1905    return <span className={`rounded-pill inline-flex px-2 py-1 text-xs font-medium ${colorCls}`}>{item.label}</span>;
```

This short-circuits before any cell renderer. Worse: of the two files the prior
golden "fixed", **`RecordListView.tsx` is referenced by NO live profile/route**
(`grep -rln RecordListView app/.../profiles app/routes` = empty) — so patching it
had zero live effect. **Fix scope:** route `ListPageContent.tsx:1882-1914` through
`StatusDot(resolveStatusTone(item.extension?.color))` (drop the hardcoded
`colorMap` pill). Served `ListPageContent.tsx` over `:5188` confirmed to contain
the `colorMap` pill + 0 `StatusDot` refs, so this is a genuine gap, not a
Vite-cache / wrong-worktree artifact.

#### Check 2 — PARTIAL: field-level error PASS; scroll/focus-first-invalid NOT wired 🟡

On `/p/crm_account/new`, clicking 保存 with 客户名称 empty:
- ✅ a **field-level red error** `请填写客户名称` renders directly under the input —
  color `rgb(220,38,38)`, class `text-aux text-status-red dark:text-red-400 mt-1`
  (the canonical `ErrorText` token component, `app/ui/ui/error-text.tsx:10`). NOT
  just a generic toast — screenshot `/tmp/cg-2b-validation.png` shows both the
  inline error AND a top-center toast.
- 🟡 **scroll-to / focus-the-first-invalid-field is NOT implemented on the page-level
  form.** `app/framework/meta/rendering/pages/FormPageContent.tsx` has **0**
  `scrollIntoView`/`.focus()` calls — its `handleFormAction` only does
  `setFieldErrors(...)` + `showErrorToast(...)` (lines ~1289-1296, ~1259-1264). The
  scroll+focus logic exists ONLY in `ValidationSummary.tsx:25-29`
  (`scrollIntoView({block:'center'})` + `input.focus()`), which is used by
  `FormDialog`/`GerberViewerBlockRenderer` — **not** by the page form. DOM probe
  confirmed `document.activeElement` = BODY after submit (no field focused);
  `scrollY=0` is uninformative because the crm_account form fits the viewport, but
  the behavior is genuinely absent — a long form would not scroll to the first error.
  **Fix scope:** call `scrollIntoView`/`focus` on the first invalid field (or render
  `ValidationSummary`) in `FormPageContent.handleFormAction`'s validation-fail branch.

#### Check 3 — PASS: reference picker filters via remote keyword fetch ✅

Showcase `sc_owner_user` (a `reference` field → `sys_user`, refTarget targetField
`username`) renders the 负责人 / 请选择用户 picker (UserSelect, person icon). Clicking
it opens a dropdown with a search box; typing "adm" fired **3 new remote fetches**,
each carrying the incrementally-typed keyword (network-captured):
`POST /api/tenant/members/search {"pageNum":1,"pageSize":50,"status":"active","keyword":"a"}`
→ `"keyword":"ad"` → `"keyword":"adm"`. Dropdown then showed the filtered result
`admin@auraboot.com` (`/tmp/cg-3b-ref-search.png`). The remote keyword filter is the
load-bearing proof.

#### Check 4 — PASS: typed controls render token-styled ✅

`/p/showcase_all_fields/new` renders all typed inputs, sectioned and token-styled
(`/tmp/cg-4-showcase-form.png` + `/tmp/cg-3b-ref-search.png`): 数量/价格 **number
steppers** (−/+), 预算金额 **¥ currency**, 进度 **slider + % field**, 评分 **5-star
rating**, 状态/优先级/分类 **selects**, 是否启用 **toggle switch**, 颜色标记 **color
picker** swatch (#3b82f6), 富文本内容 **rich-text toolbar** (B/I/S/H1-3/lists/quote/
code/link), 附件文件 **drag-drop upload zone** (点击上传 ≤10MB · 最多5个), 地址
**cascade selects**, AI 摘要 **AI Generate**. Sample input is token-styled (h=34px,
radius=6px, token border). 31 inputs / 12 selects / 5 date pickers / 4 textareas /
2 uploads counted.

#### Check 5 — PASS: detail toolbar + tabs (no lifecycle state cmd on crm_account) ✅

`/p/crm_account/view/{id}` renders breadcrumb back-arrow + 客户详情 title + action
toolbar `分享 / Report / 打印 / 编辑` + tab nav `概览 / 联系人 / 评论 / 活动记录 /
变更历史` (概览 active w/ blue underline). Clicking a tab switches content
(verified via aria-selected/active-class flip). **No dedicated 激活/归档 lifecycle
command in the toolbar — by design:** crm_account has no separate activate/archive
command; status changes go through `crm:update_account` (its `inputFields` include
`crm_acc_status`), surfaced via 编辑. So the "state-transition toolbar" is the
edit-driven path, not a lifecycle-command path. Screenshot `/tmp/cg-5-detail.png`.

#### Check 6 — PASS: typed value renderers (hyperlink + status/rating badge) ✅ / 🟡

Detail page renders: 网站 as an **accent hyperlink** (`<a href="https://acme.example.com">`,
blue), 2-col read-only description list under section header 基本信息, and 状态 (活跃)
+ 评级 (A-重点客户) as **badges**. crm_account has no switch/progress/rating field so
those weren't exercised here (they're on showcase, see check 4). 🟡 Note: the detail
status/rating badges render as **filled gray pills, NOT semantic dots** — the same
§3-A family of bypass as check 1, on the detail value path (a separate, secondary
gap from the list `ListPageContent` pill; not traced to an exact line this pass).
Check 6's bar — "the typed value renderers render" — is met (badge is acceptable
rendering); the dot-vs-pill conversion is check 1's concern.

#### Check 7 — PASS: generic import creates records ✅

The generic Excel import works end-to-end. The crm_account default list page config
does NOT surface an Import button in the toolbar (only 新建/排序/字段/筛选), so the
import was driven via the platform endpoint (`ListModals` wires the same API):
1. `GET /api/meta/excel/template/crm_account` → 200, valid .xlsx (headers
   `* 客户编号 | * 客户名称 | 行业 | 网站 | 电话 | 地址 | 评级 | 负责人 | 状态 | 备注`).
2. Built a 2-row .xlsx (field-code headers) → dry-run
   (`POST /api/meta/excel/import/crm_account?dryRun=true`) = `totalRows:2, errorCount:0`.
3. Real import (`POST /api/meta/excel/import/crm_account`) =
   `successCount:2, createdCount:2, errorCount:0`.
4. DB count **6 → 8**; the 2 imported rows (`IMP-001 Imported Wayne Enterprises`,
   `IMP-002 Imported Cyberdyne Systems`) appear in `mt_crm_account` AND in the
   reloaded list UI (`/tmp/cg-7-list-after-import.png`, 8 rows).

**No permission 403** (unlike the T9 export gap): the import endpoint is
`@RequirePermission(MetaPermission.MODEL_MANAGE)` = `"meta.model.update"`, a
platform-wide permission the admin holds (template download returned 200) — NOT a
per-model `model.crm_account.import`. So T10 import is not blocked by any plugin
perm gap.

### Summary for the main agent

- **Check 1 §3-A list dot: FAIL — the "fix" is bypassed.** Live list renderer is
  `ListPageContent.tsx:1882-1914` (admin/index.ts:135), still rendering an inline
  `colorMap` pill; `StatusDot` was wired into `TableBlockRenderer`/`RecordListView`
  but `RecordListView` is unused by any live route, and `ListPageContent` was never
  patched. Fix = route `ListPageContent`'s dict-coded branch through `StatusDot`.
- **Check 2: field-level error PASS, but scroll/focus-to-first-invalid NOT
  implemented** on the page form (`FormPageContent.tsx` has 0 scroll/focus; the
  logic lives only in `ValidationSummary.tsx`, used by FormDialog not the page).
- **Check 6 (secondary):** detail status/rating also render as pills not dots
  (same §3-A family, detail value path) — fix alongside check 1.
- **Checks 3, 4, 5, 7: PASS** with the DOM/network/DB evidence above.

## Final confirmation golden (2026-06-18)

> Real-browser **confirmation** that the two fixes in commit `556c55533`
> (`fix(web-admin): status dots on the LIVE list/detail renderers + form
> first-error scroll`) — which the "Comprehensive interaction golden" above caught
> as Check 1 FAIL (§3-A bypassed) and Check 2 PARTIAL (§4 scroll not wired) — now
> actually run on real pages. Branch `feat/ux-t4-t6-golden-closeout`, HEAD
> `556c55533`. **Verify-only: no source modified** (only this doc appended).
>
> The fix patched the THREE live renderers the prior golden traced as untouched:
> `ListPageContent.tsx:1890` (the live `/p/<model>` admin list), the shared
> `DynamicField` at `routes/_shared/dynamic-route-utils.tsx:550` (the live detail
> field value), and `DetailPageContent.tsx` `DataPathTable` (detail sub-table cell)
> — all three now route their dict-coded status/rating branch through
> `StatusDot(resolveStatusTone(item.extension?.color))`, dropping the inline
> `colorMap` pill. §4 adds `scrollToFormField.ts` + calls it from
> `FormPageContent.notifyValidationFailure`.

### Stack (host-first, isolated, zero docker)

- **Backend**: prebuilt `platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`
  (canonical-main build, mtime Jun 16 23:40) via `java -jar` on **free port 6557**
  (6543 held by a concurrent session — left untouched), pointed at **isolated DB
  `aura_ux_final`** (`database/schema.sql` applied directly — 307 tables, 0 errors)
  + **Redis DB 6**, `SPRING_PROFILES_ACTIVE=dev`. Same OTel autoconfigure-exclude
  launch flags + `MANAGEMENT_TRACING_ENABLED=false` as the prior runs (the bundled
  OTel skew still applies to this jar).
- **Frontend (THIS worktree)**: `pnpm dev:full` — Vite **5191** / BFF **3521**, both
  with `SPRING_BOOT_URL` + `PROXY_TARGET` → 6557. **Served code confirmed NEW, not
  stale**: `curl :5191/app/.../ListPageContent.tsx` → **2 `StatusDot` refs, 0
  `colorMap`/`bg-blue-100`**; `.../dynamic-route-utils.tsx` → 2 `StatusDot`;
  `.../FormPageContent.tsx` → 2 `scrollToFormField` — i.e. the served renderers
  genuinely carry the fix (the prior golden's served code had the bypass).
- **Seed**: `POST /api/bootstrap/setup` (`admin@auraboot.com`, tenant
  `325904696441180160`) + `import-directory-sync` of `crm-starter` (`success:true`;
  6 models / 19 pages / 41 commands / 54 fields / 9 dicts). **6 `crm_account`** via
  the **real command pipeline** (`POST /api/meta/commands/execute/crm:create_account`,
  varied ratings A/A/B/B/C/C); **2 flipped to `inactive` via `crm:update_account`**
  → final **4 active / 2 inactive** (so ≥2 distinct statuses present).
- **Driver**: Playwright 1.60 (`@playwright/test`) + bundled chromium
  (`chromium.launch({headless:true})`); auth via the in-browser `/login` form (real
  cookie session) → lands `/home`. Standalone scripts, removed after.

### Results

| # | Check | Verdict | Evidence |
| - | ----- | ------- | -------- |
| 1 | §3-A list status as 色点+文字 (dot, not filled pill) | **PASS (primary)** — but dots are NOT semantically colored (secondary gap) | `/tmp/final-1-list.png` |
| 2 | §3-A detail status field as dot+text (not pill) | **PASS** — same secondary color gap | `/tmp/final-2-detail.png` |
| 3 | §4 required-empty submit → first-error scroll + focus + field error | **PASS** | `/tmp/final-3b-after-submit.png` |

#### Check 1 — PASS (primary): live list status/rating are dots+text, 0 filled pills ✅

On `/p/crm_account` (6 rows), DOM-inspected:
`dotCount=12` (6 rows × 状态 + 评级), `filledPillCount=0`, `dotWrapperCount=12`.
Each dot is `span.rounded-pill.h-2.w-2.shrink-0.bg-status-<tone>` inside a wrapper
`span.inline-flex.items-center.gap-1.5` carrying the label text (活跃 / 停用 /
A-重点客户 / B-重要客户 / C-一般客户). **No `px-2 py-1 bg-blue-100` filled pill anywhere.**
The screenshot shows the 状态 column with • 活跃 (4 rows) / • 停用 (2 rows: Globex,
Acme) and 评级 with • A/B/C — all dot-prefixed, not pills. This is exactly the
§3-A `StatusDot` route at `ListPageContent.tsx:1890`, live. **The prior golden's
Check-1 FAIL (filled pills via the old inline `colorMap`) is resolved.**

🟡 **Secondary gap found (NOT the §3-A requirement, but it fails the task's
"≥2 statuses → ≥2 dot colors" sub-assertion):** every dot — both statuses AND
ratings — renders `bg-status-gray` (`rgb(113,113,122)`); `distinctBg = 1 color`.
Root cause traced (served-code + live-API confirmed):

- The dict items carry **hex color values** in `item.extension.color`, not tone
  names — `/api/meta/dict/by-code/crm_account_status/data` returns
  `active → {"color":"#10b981"}`, `inactive → {"color":"#9ca3af"}` (and
  rating A→`#ef4444`, B→`#f59e0b`, C→`#3b82f6`).
- `ListPageContent.tsx:1890` calls `resolveStatusTone(item.extension?.color)` i.e.
  `resolveStatusTone("#10b981")`.
- `statusTone.tsx:104-107` `resolveStatusTone` only matches **color NAMES** via
  `TONE_BY_NAME` (`'green'`, `'success'`, `'active'`, …). A hex string is not a key
  → falls through to the `?? 'gray'` default. So **all hex-colored dicts collapse to
  gray.**

**Fix scope (out of this verify-only pass):** make `resolveStatusTone` also map hex
values (e.g. parse `#10b981`/`#9ca3af`/`#ef4444`/`#f59e0b`/`#3b82f6` to the nearest
canonical tone, or seed `TONE_BY_NAME` by the dict's `value` like `active`/`inactive`
in addition to its color). File:line = `web-admin/app/framework/meta/runtime/renderers/statusTone.tsx:104-107`.
The §3-A *shape* (dot+text, no pill) is fully delivered; the *semantic color* is the
remaining piece.

#### Check 2 — PASS: detail status field is a dot+text, not a pill ✅

On `/p/crm_account/view/01KVCVAN8RGN1GCF9GQ2H6FVEM` (Stark Industries): the 状态
field value renders **• 活跃** and 评级 renders **• C - 一般客户** as
`StatusDot` (`dotCount=2`, `filledPillCount=0`, dot wrapper `inline-flex
items-center gap-1.5` with the label). Screenshot `/tmp/final-2-detail.png` shows
both as dot+text, NOT the prior `bg-gray-100` filled pill. This is the shared
`DynamicField` at `dynamic-route-utils.tsx:550` (the live detail field-value path),
live. (Same gray-dot color caveat as Check 1 — the detail dict path uses the same
`resolveStatusTone`.) **The prior golden's Check-6 secondary "detail renders pills
not dots" is resolved (shape-wise).**

#### Check 3 — PASS: empty-required submit scrolls to + focuses the first invalid field ✅

On `/p/crm_account/new`, the form has 8 `[data-testid="form-field-<code>"]` wrappers;
first = `form-field-crm_acc_name`. After clicking 保存 with all fields empty, DOM-probed:
- ✅ **Field-level red error** `请填写客户名称` renders under the 客户名称 input
  (`text-status-red`, `errorCount` includes the `*` marker + the message), plus a
  top toast — NOT a generic-only toast. Screenshot `/tmp/final-3b-after-submit.png`
  shows the first input with a **focus ring** + the inline red error directly beneath.
- ✅ **`document.activeElement` is the INPUT inside `form-field-crm_acc_name`**
  (`activeTag=INPUT`, `activeTestId=form-field-crm_acc_name`,
  `activeInFirstWrapper=true`) — i.e. `scrollToFormField(firstFieldCode)` from
  `FormPageContent.notifyValidationFailure` ran and focused the first invalid field.
- ✅ First field **in view** (`firstInView=true`, `firstRectTop=202px`).
  (`scrollY` stayed 0 because the whole crm_account form fits the 900px viewport, so
  there was nothing to scroll; the load-bearing proof is the real `.focus()` landing
  on the first invalid input — on a long form the same `scrollIntoView({block:'center'})`
  would bring it into view, as the unit tests cover.)

**The prior golden's Check-2 PARTIAL (scroll/focus-to-first-invalid NOT wired,
`FormPageContent` had 0 scroll/focus) is resolved:** `FormPageContent.tsx:1270`
now calls `scrollToFormField(firstFieldCode)` via `requestAnimationFrame` in
`notifyValidationFailure`, and it demonstrably focuses the first invalid field.

### Summary

- **§3-A list (Check 1): PASS for the requirement** (色点+文字, 0 filled pills, live
  on `ListPageContent.tsx:1890`). 🟡 Separate secondary gap: dots are all gray
  because `resolveStatusTone` (`statusTone.tsx:104-107`) doesn't handle the **hex**
  color values the dicts carry — so the task's "≥2 statuses → ≥2 dot colors"
  sub-assertion fails (1 color). Fix = teach `resolveStatusTone` hex→tone (or seed by
  dict value). Out of the fix-commit's scope (it was a shape fix); flagged for follow-up.
- **§3-A detail (Check 2): PASS** — detail status/rating now dot+text (live
  `DynamicField` `dynamic-route-utils.tsx:550`), not pills. Same gray caveat.
- **§4 form scroll (Check 3): PASS** — empty submit focuses + brings into view the
  first invalid field with a field-level red error (`FormPageContent.tsx:1270` →
  `scrollToFormField`).
- Both fixes from `556c55533` that the prior golden caught as FAIL/PARTIAL now WORK
  on real pages (shape + behavior). The only residual is the cosmetic dot-color
  semantics (hex-not-handled), a distinct, smaller gap from the original pill bug.

## Status-dot color confirmation golden (2026-06-18)

> Real-browser **confirmation** that the residual flagged at the end of the
> "Final confirmation golden" above — *dots render with the right shape but all
> `bg-status-gray` because `resolveStatusTone` only mapped color NAMES, not the
> hex values the dicts carry* — is now resolved by the hex→tone fix (commit
> `a201fb973`, folded into branch HEAD `530708b89` / `#812`,
> `statusTone.tsx` now has `hexToTone(...)`; `grep -c hexToTone statusTone.tsx` = 2).
> Branch `feat/ux-status-dot-color-golden` (= origin/main with the hex fix).
> **Verify-only: no source modified** (only this doc appended).

### Stack (host-first, isolated, zero docker)

- **Backend**: prebuilt `platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`
  (canonical-main build, mtime Jun 16 23:40) via `java -jar` on a **free port 6560**
  (6543/6554/6556/6557 all held by concurrent sessions — left untouched), pointed at
  **isolated DB `aura_ux_color`** (`database/schema.sql` applied directly — 307
  tables, 0 errors) + **Redis DB 7**, `SPRING_PROFILES_ACTIVE=dev`, explicit
  `JWT_SECRET`. Same OTel autoconfigure-exclude launch flags +
  `MANAGEMENT_TRACING_ENABLED=false` as the prior runs (the bundled OTel skew still
  applies to this Jun-16 jar). The shared `aura_boot` DB and other sessions' DBs
  (`aura_ux_t10*`, `aura_ux_s3a`) were never touched (§20).
- **Frontend (THIS worktree)**: `pnpm dev:full` — Vite **5193** / BFF **3523**, both
  with `SPRING_BOOT_URL` + `PROXY_TARGET` → 6560. **Served code confirmed NEW, not
  stale**: `curl :5193/app/.../statusTone.tsx` → **2 `hexToTone` refs**;
  `.../ListPageContent.tsx` → **2 `StatusDot` + 2 `resolveStatusTone`** (the live
  `/p/<model>` list renderer); `.../dynamic-route-utils.tsx` → 2 `StatusDot` (detail
  path) — i.e. the served renderers genuinely carry the hex fix.
- **Seed**: `POST /api/bootstrap/setup` (`admin@auraboot.com`, tenant
  `325910279982551040`) + `import-directory-sync` of `crm-starter` (`success:true`).
  **6 `crm_account`** via the **real command pipeline**
  (`POST /api/meta/commands/execute/crm:create_account`, ratings A/A/B/B/C/C);
  `crm:create_account` force-sets `crm_acc_status=active`, so **2 rows flipped to
  `inactive` via the real `crm:update_account` command** → final **4 active / 2
  inactive** (so the 状态 column carries BOTH active AND inactive).
- **Dict colors confirmed live** (`/api/meta/dict/by-code/<code>/data`):
  `crm_account_status` active=`#10b981` / inactive=`#9ca3af`;
  `crm_account_rating` A=`#ef4444` / B=`#f59e0b` / C=`#3b82f6` / D=`#9ca3af`.
- **Driver**: Playwright 1.60 (`@playwright/test`) + bundled chromium
  (`chromium.launch({headless:true})`); auth via the BFF `/login` form POST →
  `__session` cookie. Standalone script, removed after the run (no source added).

### Result — PASS ✅ : dots now show DISTINCT semantic colors (was "all gray / 1 color")

On `/p/crm_account` (6 rows × 状态 + 评级 = 12 dots), DOM-inspected via
`getComputedStyle().backgroundColor` on each `span.rounded-pill.h-2.w-2`:
**`dotCount=12`, `filledPillCount=0`, `distinctBg = 5 colors`** (the prior golden's
failure was 1 color / all gray). Per-status → dot class → computed color mapping:

| Status / Rating value | Dict hex | Dot class | Computed background | Tone |
| --------------------- | -------- | --------- | ------------------- | ---- |
| 活跃 (active)         | `#10b981` | `bg-status-green` | `rgb(21, 163, 74)`  | **GREEN** ✅ |
| 停用 (inactive)       | `#9ca3af` | `bg-status-gray`  | `rgb(113, 113, 122)`| **GRAY** ✅ |
| A - 重点客户          | `#ef4444` | `bg-status-red`   | `rgb(220, 38, 38)`  | **RED** ✅ |
| B - 重要客户          | `#f59e0b` | `bg-status-amber` | `rgb(194, 117, 10)` | **AMBER** ✅ |
| C - 一般客户          | `#3b82f6` | `bg-status-blue`  | `rgb(37, 99, 235)`  | **BLUE** ✅ |

- **active → GREEN** dot (`bg-status-green`, computed `rgb(21,163,74)`) ✅ —
  exactly the task's required assertion.
- **inactive → GRAY** dot (`bg-status-gray`, `rgb(113,113,122)`) ✅.
- **ratings show 3 distinct non-gray colors** (A=red, B=amber, C=blue) matching
  their hex ✅ — well above the "≥2 distinct non-gray" bar.
- **≥2 distinct dot colors on the page**: 5 distinct (green/gray/red/amber/blue),
  resolving the prior golden's "all gray / 1 color" failure.

Visual eyeball of `/tmp/color-list.png` confirms: the 状态 column shows green dots
(活跃) and gray dots (停用), and the 评级 column shows red (A), amber (B), blue (C)
dots — visually distinct colored dots, not uniform gray.

**Trace of WHY it now works (vs the prior gray failure):** the dict items carry
**hex** color values in `item.extension.color` (e.g. `active → #10b981`).
`ListPageContent.tsx:1890` calls `resolveStatusTone(item.extension?.color)`. Before
the fix, `resolveStatusTone` (`statusTone.tsx:104-109`) only matched color NAMES via
`TONE_BY_NAME`, so a hex string fell through to `?? 'gray'` → all dots gray. The fix
(`a201fb973`) added `hexToTone(...)` (`statusTone.tsx:77-103`): a hue/saturation
mapper that the resolver now tries between the name lookup and the gray default
(`TONE_BY_NAME[key] ?? hexToTone(key) ?? 'gray'`). The hex→tone math was verified
independently to map these exact dict hexes to the observed tones
(`#10b981→green`, `#9ca3af→gray`, `#ef4444→red`, `#f59e0b→amber`, `#3b82f6→blue`),
and the live page confirms each dot renders that tone.

**Verdict: PASS** — the dict-coded status/rating dots render in the CORRECT semantic
colors (5 distinct, no longer all gray). Screenshot: `/tmp/color-list.png`.
