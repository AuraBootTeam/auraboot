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
