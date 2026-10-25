/**
 * Phase 3 report-export PDF step (Option A', slice 2b — see
 * DDR-2026-06-21-report-export-rendering-source-of-truth).
 *
 * Paints a self-contained print document (from print-html.ts) to PDF via a
 * headless browser. The browser is injected (BrowserLike) rather than hard-
 * imported, so this module:
 *   - carries no runtime dependency on a specific browser package (the prod
 *     renderer service wires in playwright; tests wire in a fake),
 *   - is unit-testable without launching a real browser,
 *   - matches the `ReportRenderClient` seam in the DDR (§8/§9).
 */
import type { PrintDocument } from './print-html';

/** Minimal subset of a Playwright Page used here. */
export interface PageLike {
  setContent(html: string, options?: { waitUntil?: string }): Promise<void>;
  pdf(options: Record<string, unknown>): Promise<Uint8Array | Buffer>;
}

/** Minimal subset of a Playwright Browser used here. */
export interface BrowserLike {
  newPage(): Promise<PageLike>;
  close(): Promise<void>;
}

export interface RenderPdfOptions {
  /** Launch a headless browser (e.g. () => chromium.launch()). */
  launchBrowser: () => Promise<BrowserLike>;
  /** Page size; defaults to A4. */
  format?: string;
  landscape?: boolean;
  /** Page margins; must leave room for the running header/footer. */
  margin?: { top?: string; bottom?: string; left?: string; right?: string };
}

const DEFAULT_MARGIN = { top: '24mm', bottom: '18mm', left: '14mm', right: '14mm' };
const EMPTY_TEMPLATE = '<span></span>';

function reserveBandSpace(value: string, bandHeight: number): string {
  const match = /^(\d+(?:\.\d+)?)\s*(mm|cm|in|px)$/.exec(value);
  if (!match) throw new Error('Invalid report PDF margin');
  const factor = { mm: 1, cm: 10, in: 25.4, px: 25.4 / 96 }[match[2] as 'mm' | 'cm' | 'in' | 'px'];
  const millimetres = Number(match[1]) * factor;
  if (!Number.isFinite(millimetres)) throw new Error('Invalid report PDF margin');
  return millimetres >= bandHeight + 4 ? value : `${bandHeight + 4}mm`;
}

export async function renderHtmlToPdf(doc: PrintDocument, opts: RenderPdfOptions): Promise<Buffer> {
  const browser = await opts.launchBrowser();
  try {
    const page = await browser.newPage();
    await page.setContent(doc.html, { waitUntil: 'networkidle' });

    const displayHeaderFooter = Boolean(doc.headerTemplate || doc.footerTemplate);
    const bytes = await page.pdf({
      format: opts.format ?? 'A4',
      landscape: opts.landscape ?? false,
      printBackground: true,
      displayHeaderFooter,
      headerTemplate: doc.headerTemplate ?? EMPTY_TEMPLATE,
      footerTemplate: doc.footerTemplate ?? EMPTY_TEMPLATE,
      margin: {
        ...(opts.margin ?? DEFAULT_MARGIN),
        ...(doc.headerHeightMm
          ? {
              top: reserveBandSpace(opts.margin?.top ?? DEFAULT_MARGIN.top, doc.headerHeightMm),
            }
          : {}),
        ...(doc.footerHeightMm
          ? {
              bottom: reserveBandSpace(
                opts.margin?.bottom ?? DEFAULT_MARGIN.bottom,
                doc.footerHeightMm,
              ),
            }
          : {}),
      },
    });
    return Buffer.from(bytes);
  } finally {
    await browser.close();
  }
}
