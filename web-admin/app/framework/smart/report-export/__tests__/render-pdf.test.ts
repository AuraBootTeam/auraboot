import { describe, it, expect } from 'vitest';
import { renderHtmlToPdf, type BrowserLike, type PageLike } from '../render-pdf';

class FakePage implements PageLike {
  setContentArgs?: { html: string };
  pdfArgs?: Record<string, unknown>;
  async setContent(html: string): Promise<void> {
    this.setContentArgs = { html };
  }
  async pdf(opts: Record<string, unknown>): Promise<Uint8Array> {
    this.pdfArgs = opts;
    return new TextEncoder().encode('%PDF-FAKE');
  }
}

class FakeBrowser implements BrowserLike {
  page = new FakePage();
  closed = false;
  async newPage(): Promise<PageLike> {
    return this.page;
  }
  async close(): Promise<void> {
    this.closed = true;
  }
}

describe('renderHtmlToPdf', () => {
  it('feeds the self-contained html to the page and returns PDF bytes', async () => {
    const browser = new FakeBrowser();
    const pdf = await renderHtmlToPdf(
      { html: '<!doctype html><html><body>hi</body></html>' },
      { launchBrowser: async () => browser },
    );
    expect(browser.page.setContentArgs?.html).toContain('<body>hi</body>');
    expect(pdf.subarray(0, 5).toString('latin1')).toBe('%PDF-');
    expect(browser.closed).toBe(true); // always cleans up the browser
  });

  it('enables running header/footer only when templates are present, and threads them through', async () => {
    const browser = new FakeBrowser();
    await renderHtmlToPdf(
      { html: '<html></html>', headerTemplate: '<div>HDR</div>', footerTemplate: '<div>FTR</div>' },
      { launchBrowser: async () => browser },
    );
    expect(browser.page.pdfArgs?.displayHeaderFooter).toBe(true);
    expect(browser.page.pdfArgs?.headerTemplate).toBe('<div>HDR</div>');
    expect(browser.page.pdfArgs?.footerTemplate).toBe('<div>FTR</div>');
  });

  it('disables header/footer when no templates are present', async () => {
    const browser = new FakeBrowser();
    await renderHtmlToPdf({ html: '<html></html>' }, { launchBrowser: async () => browser });
    expect(browser.page.pdfArgs?.displayHeaderFooter).toBe(false);
  });

  it('closes the browser even if pdf() throws', async () => {
    const browser = new FakeBrowser();
    browser.page.pdf = async () => {
      throw new Error('boom');
    };
    await expect(
      renderHtmlToPdf({ html: '<html></html>' }, { launchBrowser: async () => browser }),
    ).rejects.toThrow('boom');
    expect(browser.closed).toBe(true);
  });
});
