/**
 * Phase 3 report-export render core (Option A', slice 2c — see
 * DDR-2026-06-21-report-export-rendering-source-of-truth).
 *
 * Pure request -> PDF logic for the JVM↔Node subprocess boundary, separated from
 * the cli.ts entrypoint so it is unit-testable without spawning a process or a
 * real browser. The headless browser is resolved at runtime (prod: "playwright";
 * dev/golden: "@playwright/test") via a non-literal dynamic import, so neither
 * the build nor a missing dev dependency breaks module load — the production
 * renderer image provides playwright (DDR §7/§9).
 */
import {
  renderReportToPrintDocument,
  type ReportPrintModel,
  type PrintDataSets,
} from './print-html';
import { renderHtmlToPdf, type BrowserLike } from './render-pdf';

export interface RenderRequest {
  model: ReportPrintModel;
  dataSets?: PrintDataSets;
  options?: { format?: string };
}

export interface ChromiumLike {
  launch(options?: Record<string, unknown>): Promise<BrowserLike>;
}

/** Resolve a Playwright-compatible chromium from prod or dev packages. */
export async function loadChromium(): Promise<ChromiumLike> {
  const candidates = ['playwright', '@playwright/test'];
  for (const pkg of candidates) {
    try {
      const mod: { chromium?: ChromiumLike } = await import(pkg);
      if (mod.chromium) {
        return mod.chromium;
      }
    } catch {
      // try the next candidate
    }
  }
  throw new Error(
    'report-export CLI: no headless browser found — install "playwright" (production) or "@playwright/test" (dev/golden).',
  );
}

/** Render a request JSON string to PDF bytes. */
export async function renderRequestToPdf(
  requestJson: string,
  loadBrowser: () => Promise<ChromiumLike> = loadChromium,
): Promise<Buffer> {
  const request = JSON.parse(requestJson) as RenderRequest;
  if (!request?.model || !Array.isArray(request.model.blocks)) {
    throw new Error('report-export CLI: request.model.blocks[] is required');
  }
  const doc = renderReportToPrintDocument(request.model, request.dataSets ?? {});
  const chromium = await loadBrowser();
  return renderHtmlToPdf(doc, {
    launchBrowser: () => chromium.launch(),
    format: request.options?.format,
  });
}
