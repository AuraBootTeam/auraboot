# README Screenshots Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use browser-use for local UI capture and visual verification. Use Computer Use only when a required screenshot cannot be captured from the browser surface.

**Goal:** Deliver launch-ready README screenshots for the OSS repository, with separate English and Simplified Chinese variants.

**Architecture:** Use the running AuraBoot web UI as the source of truth. Store canonical English images at the paths required by `docs/community/readme-screenshots-spec.md`, and store Chinese variants beside them with `.zh-CN.png` suffixes. Update `README.md` to reference only the canonical English images after screenshot acceptance.

**Tech Stack:** AuraBoot web-admin, Browser automation, PNG assets, README Markdown.

---

## Files

- Modify: `README.md`
- Create: `docs/assets/screenshots/dashboard.png`
- Create: `docs/assets/screenshots/page-designer.png`
- Create: `docs/assets/screenshots/command-pipeline.png`
- Create: `docs/assets/screenshots/aurabot.png`
- Create: `docs/assets/screenshots/dashboard.zh-CN.png`
- Create: `docs/assets/screenshots/page-designer.zh-CN.png`
- Create: `docs/assets/screenshots/command-pipeline.zh-CN.png`
- Create: `docs/assets/screenshots/aurabot.zh-CN.png`

## Task Checklist

### Task 1: Baseline

- [x] Confirm `/Users/ghj/work/auraboot/auraboot` is on `main`.
- [x] Confirm `/Users/ghj/work/auraboot/auraboot-enterprise` is on `main`.
- [x] Read `docs/community/readme-screenshots-spec.md`.
- [x] Inspect existing README screenshot placeholder.
- [x] Confirm `docs/assets/screenshots/` did not already contain launch screenshots.

### Task 2: Runtime Access

- [x] Confirm the web UI URL and backend health.
- [x] Login as `admin@auraboot.com`.
- [x] Set locale to `en-US`.
- [x] Verify light theme and close dev tools/overlays.

### Task 3: English Screenshots

- [x] Capture dashboard with sidebar, top bar, and 2-3 meaningful widgets.
- [x] Capture page designer with palette, canvas, selected block, and property panel.
- [x] Capture command pipeline with a realistic command DSL or pipeline inspector.
- [x] Capture AuraBot trace detail with run metadata, timeline waterfall, spans, and detail panel.

### Task 4: Chinese Screenshots

- [x] Switch locale to `zh-CN`.
- [x] Capture the same four slots with Chinese UI text.
- [x] Keep layout and data materially equivalent to the English variants.

### Task 5: QA

- [x] Confirm each image is 16:9.
- [x] Confirm no OS/browser chrome, cursor, dev tools, or modal noise.
- [x] Confirm light theme.
- [x] Confirm no `$i18n:*`, `undefined`, `null`, stack traces, or obvious control-level mixed-locale UI.
- [x] Confirm data looks realistic and contains no PII or internal customer data.
- [x] Confirm file sizes and prefer raw real-site screenshots over size reduction.

### Task 6: README Integration

- [x] Replace the screenshot TODO comment in `README.md` with four Markdown image references after visual acceptance.
- [x] Run a file-size check for all screenshots.
- [x] Run `git diff -- README.md docs/assets/screenshots docs/superpowers/plans/2026-05-10-readme-screenshots.md` after README integration.

## Execution Notes

- Browser workflow and repo-local Playwright capture were used for local UI verification and deterministic file output.
- The final accepted assets are raw screenshots from the running local website at `http://localhost:5173`. They are not staged product compositions, stitched mockups, generated images, resized screenshots, or browser-frame crops.
- The first `1280x800` pass was rejected during review because the focus was unclear and the screenshots were visually cramped.
- A later pass incorrectly captured the default `System Overview` dashboard. Root cause: `/dashboards?code=arsenal_capability_dashboard` goes through the multi-tab dashboard list, and the published-list page did not include the arsenal dashboard in the first loaded tab set. The accepted capture now uses `/dashboards/view/arsenal_capability_dashboard`.
- The showcase/arsenal plugin resources and seed data were verified in the active tenant before the final capture. `arsenal_capability_dashboard` has 24 widgets and `showcase_all_fields_*` page schemas are present.
- The final review set uses direct `1920x1080` PNG viewport screenshots. No resize or quantization pass is applied after capture.
- The in-app browser connector timed out during this session. The fallback capture path used repo-local Playwright against the same running website after restoring the OSS backend and refreshing the real admin session.
- Dashboard source route: `/dashboards/view/arsenal_capability_dashboard`.
- Page Designer source route: `/page-designer/01KR9608BH5J7KWRKAPXVATK40`, with the Blocks tab and first form section selected.
- Command Pipeline source route: `/meta/commands/workbench`, executing `sc:list_showcase` with `{ "pageNum": 1, "pageSize": 10 }`.
- AuraBot source route: `/aurabot/traces/25581e22-0df8-4027-bed5-9989ba2279a1`.
- Command Workbench required two runtime fixes before capture: registering `/meta/commands/workbench` in `core-meta/resources.ts`, and treating backend `code: "0"` as success through `ResultHelper.isSuccess`.
- Chinese variants intentionally retain product names, keyboard names, file paths, command codes, and DSL identifiers where those are proper nouns or code-level data rather than UI labels.
- README integration references the canonical English screenshots only. Chinese variants remain beside them for localized publishing.
