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
