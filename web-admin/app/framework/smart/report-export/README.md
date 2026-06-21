# Report export — WYSIWYG renderer (Phase 3, Option A′)

Server-side renderer that turns a report (block-tree + resolved data sets) into a
competitive-grade **WYSIWYG PDF**: real vector charts (echarts), running
header/footer + page numbers, watermark, grouped/cross-tab tables, aggregation,
real CODE128 barcodes, and CJK.

Architecture decision: `auraboot-enterprise` →
`docs/standards/decisions/DDR-2026-06-21-report-export-rendering-source-of-truth.md`.
**Single rendering source of truth** — the chart path runs the *real* frontend
`chartSpecToEChartsOption` via echarts SSR, so export never drifts from on-screen.

## Modules

| File | Role |
|------|------|
| `print-render.ts` | chart block → ChartSpec → echarts-SSR SVG; aggregation parity (`aggregateChartRows`); `renderBarcodeSvg` (CODE128) |
| `print-html.ts` | block-tree → self-contained print HTML (+ Chromium running header/footer templates) |
| `render-pdf.ts` | print doc → PDF via an injected headless browser (`BrowserLike`) |
| `cli-core.ts` / `cli.ts` | the JVM↔Node subprocess entrypoint: JSON request on stdin → PDF on stdout / `--out` |

The Java side (`com.auraboot.framework.bi.service.impl.ReportRenderClient`) spawns
the CLI as a subprocess; `ReportExportServiceImpl.exportPdf` tries it first and
falls back to the legacy PDFBox text export when it is not configured or fails.

## Enabling in a deployment

The renderer is **off by absence**: `auraboot.report-export.renderer.command` is
empty by default → PDF export uses the legacy PDFBox text path (no behaviour
change). To enable WYSIWYG:

```yaml
auraboot:
  report-export:
    renderer:
      enabled: true
      # The client appends `--out <tempfile>` and writes the request JSON to stdin.
      command:
        - /app/web-admin/node_modules/.bin/tsx
        - /app/web-admin/app/framework/smart/report-export/cli.ts
      timeout-seconds: 30
```

### Runtime requirements (subprocess is co-located with the JVM)

The current platform runtime image (`eclipse-temurin:25-jre-alpine`) is JRE-only
and **cannot** run the renderer. The image (or host) that runs the platform must
additionally provide, co-located with the JVM:

- **Node.js** (≥ 20) + **tsx** (plain `node cli.ts` does NOT work — the
  extensionless `.ts` imports need tsx; or pre-bundle the CLI).
- the web-admin node deps the renderer imports: `echarts`, `jsbarcode`, and a
  **Playwright** package with a **Chromium** binary (`playwright` for prod, or
  `@playwright/test` as used by the local golden).
- **CJK fonts** installed in the image (e.g. Noto Sans CJK) so Chinese renders.

### Hardening (see DDR §7)

- **Pin the Chromium version** so PDF output is deterministic across upgrades.
- **Resource-limit** the renderer process; the print HTML is self-contained and
  must not fetch external resources — keep the renderer network-isolated (SSRF).
- Batch/cron throughput: if many reports render concurrently, graduate to a
  resident renderer service + queue (DDR §9) — that variant needs an HTTP
  `ReportRenderClient` instead of the current ProcessBuilder/subprocess client.

## Local golden (host-first, zero docker)

The committed JUnit goldens run the real chain locally using `@playwright/test`'s
chromium (no docker):

- `ReportRenderLiveIT` — renderer chain over all block types.
- `ReportExportServiceLiveIT` — the real `exportPdf` service path → real renderer.

Both are guarded (`@EnabledIf("rendererAvailable")`) and skip when the web-admin
renderer deps are absent. Run from `platform/`:

```bash
./gradlew :test --tests "com.auraboot.framework.bi.ReportRenderLiveIT" \
                --tests "com.auraboot.framework.bi.ReportExportServiceLiveIT"
```

## Test coverage matrix

Report export has 10 block types × 4 export paths. Every cell below is exercised
(audited 2026-06-21 by grepping the test tree, not asserted).

| Block | TS unit | WYSIWYG PDF (real Chromium) | PDFBox PDF (fallback) | Excel (POI) | JSON |
|-------|:---:|:---:|:---:|:---:|:---:|
| chart | ✅ | ✅ | ✅ | ✅ + native chart | ✅ |
| table | ✅ | ✅ | ✅ | ✅ | ✅ |
| grouped-table | ✅ | ✅ | ✅ | ✅ | ✅ |
| cross-tab | ✅ | ✅ | ✅ | ✅ | ✅ |
| stat-card | ✅ | ✅ | ✅ | ✅ | ✅ |
| rich-text | ✅ | ✅ | ✅ | ✅ | ✅ |
| barcode | ✅ | ✅ | ✅ | ✅ | ✅ |
| page-header | ✅ | ✅ | ✅ | ✅ | ✅ |
| page-footer | ✅ | ✅ | ✅ | ✅ | ✅ |
| watermark | ✅ | ✅ | ✅ | ✅ | ✅ |

### Cross-cutting scenarios (beyond per-block)

- **Aggregation** sum / avg / count / min / max; chart shapes A/B/C; chart types
  bar/line/pie/area; illegal type → bar fallback — `print-render.test.ts`.
- **Barcode** CODE128 + non-128/empty → text fallback — `print-render.test.ts`.
- **WYSIWYG ↔ PDFBox fallback** switch — `ReportExportServiceTest`
  (`exportPdf_usesWysiwygRenderer`, `exportPdf_fallsBackToPdfBox`).
- **Subprocess pipeline** stub / non-zero exit / timeout / non-PDF — `ReportRenderClientTest`.
- **§5 PDF golden** %PDF + per-page running header + `第 N / M 页` page numbers +
  CJK + zero "Category" — `ReportRenderLiveIT`.
- **Data sources** static / model / namedQuery / api — `ReportExportServiceTest`.
- **Storage read-switch** ab_report-first / page-schema fallback / backfill —
  `ReportExportServiceReadSwitchIT`, `ReportStorageServiceIT`, `ReportBackfillIT`.
- **Audit** export PDF/Excel/JSON, schedule, storage — `ReportExportServiceTest`,
  `ReportScheduleAuditTest`, `ReportStorageAuditTest`.
- **Permissions (authz)** export / schedule / definition — `ReportExportControllerAuthzTest`,
  `ReportScheduleControllerAuthzTest`, `ReportDefinitionControllerIT`,
  `ReportPermissionFamilyGrantIT`.
- **Scheduled delivery (B7)** renders the real report as a PDF attachment —
  `ReportDeliveryServiceTest`, `ReportDeliveryLiveIT`.
- **Error/empty states** missing DSL → ValidationException, no data, empty
  recipients skip, mail failure wraps — `ReportExportServiceTest`, `ReportDeliveryServiceTest`.
- **Real warm stack** `@SpringBootTest` + real DB — `ReportExportServiceReadSwitchIT`
  (run against an isolated DB, never the shared one — see below).

### Counts (2026-06-21, all green)

- TS (vitest, `web-admin/app/framework/smart/report-export/`): **33** —
  cli-core 4 / print-html 14 / print-render 11 / render-pdf 4.
- Java non-DB (`platform/`): **51** — RenderLiveIT 1 / RenderClientTest 5 /
  ExportServiceTest 18 / ExportServiceLiveIT 1 / DeliveryServiceTest 9 /
  DeliveryLiveIT 1 / Export+ScheduleControllerAuthz 2+2 / ScheduleServiceTest 9 /
  ScheduleAuditTest 3.
- Java DB ITs: ReadSwitchIT 2 + ReportBackfillIT / ReportStorageServiceIT /
  ReportStorageAuditTest / ReportPermissionFamilyGrantIT.

### Generating the reports

Reports are generated on demand (not committed):

```bash
# Java → platform/build/reports/tests/test/index.html (+ build/test-results/test/TEST-*.xml)
cd platform && ./gradlew :test --tests "com.auraboot.framework.bi.Report*"
# TS → vitest html/json report
cd web-admin && pnpm exec vitest run app/framework/smart/report-export/ --reporter=html
```

The DB-backed ITs (`ReadSwitchIT` etc.) use `@ActiveProfiles("integration-test")`
which points at the shared `localhost:5432/aura_boot`. To run them without
disrupting other sessions, build a throwaway DB and override the URL:

```bash
createdb aura_boot_rsit
flyway -url=jdbc:postgresql://localhost:5432/aura_boot_rsit -user=$(whoami) \
  -locations=filesystem:platform/src/main/resources/db/migration/core migrate
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/aura_boot_rsit?charSet=UTF8' \
  ./gradlew -p platform :test --tests "com.auraboot.framework.bi.ReportExportServiceReadSwitchIT"
dropdb aura_boot_rsit
```
