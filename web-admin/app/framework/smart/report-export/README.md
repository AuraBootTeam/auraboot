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
